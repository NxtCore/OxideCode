package dev.sweep.assistant.services

import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.*
import com.intellij.openapi.vcs.ui.CommitMessage
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.serviceContainer.AlreadyDisposedException
import com.intellij.util.messages.MessageBusConnection
import com.intellij.vcs.commit.*
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.data.CommitMessageRequest
import dev.sweep.assistant.utils.PartialChangeInfo
import dev.sweep.assistant.utils.generateCombinedDiffString
import dev.sweep.assistant.utils.generateDiffStringFromChanges
import dev.sweep.assistant.utils.generateDiffStringFromUnversionedFiles
import dev.sweep.assistant.utils.getConnection
import dev.sweep.assistant.utils.getCurrentBranchName
import dev.sweep.assistant.utils.getRecentCommitMessages
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.Future

@Service(Service.Level.PROJECT)
class SweepCommitMessageService(
    private val project: Project,
) : Disposable {
    private var previousMessage: String? = null
    private var commitUi: CommitMessageUi? = null
    private var lastUpdateTime: Long = 0
    private var messageBusConnection: MessageBusConnection? = null
    private val runningTasks = mutableListOf<Future<*>>()

    init {
        messageBusConnection = project.messageBus.connect(this) // Connect with disposable
        messageBusConnection?.subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: ToolWindowManager) {
                    if (project.isDisposed) return

                    val activeId = toolWindowManager.activeToolWindowId
                    // if they interact with commit tab
                    // note that we have a 5 minute cooldown which prevents
                    // excessive commit message creation, if we change the cool down to be less
                    // we need to change this to keep track of previous activeid
                    if (activeId == "Commit" || activeId == "Version Control") {
                        try {
                            val focusOwner = IdeFocusManager.getInstance(project).focusOwner
                            if (focusOwner != null) {
                                val dataContext = DataManager.getInstance().getDataContext(focusOwner)

                                // this will not work for 2023 but wont error anything
                                val commitMessage = VcsDataKeys.COMMIT_MESSAGE_CONTROL.getData(dataContext) as? CommitMessage ?: return

                                synchronized(runningTasks) {
                                    if (!project.isDisposed) {
                                        val task =
                                            ApplicationManager.getApplication().executeOnPooledThread {
                                                try {
                                                    updateCommitMessage(commitMessage)
                                                } catch (e: ProcessCanceledException) {
                                                    // Rethrow ProcessCanceledException as required by IntelliJ
                                                    throw e
                                                } catch (e: AlreadyDisposedException) {
                                                    // Project/service is disposed, this is expected during shutdown
                                                    logger.debug("Project disposed during commit message generation")
                                                    throw e
                                                } catch (_: CancellationException) {
                                                    // Task was cancelled, which is expected during shutdown
                                                    logger.debug("Commit message generation cancelled")
                                                } catch (e: Exception) {
                                                    logger.warn("Error updating commit message", e)
                                                }
                                            }
                                        runningTasks.add(task)
                                    }
                                }
                            }
                        } catch (e: ProcessCanceledException) {
                            // Rethrow ProcessCanceledException as required by IntelliJ
                            throw e
                        } catch (e: Exception) {
                            println("failed to initialize")
                            logger.error("Error initializing commit message service", e)
                        }
                    }
                }
            },
        )
    }

    fun updateCommitMessage(
        commitMessage: CommitMessage,
        selectedChanges: List<Change> = emptyList(),
        partialChanges: List<PartialChangeInfo> = emptyList(),
        unversionedFiles: List<FilePath> = emptyList(),
        overrideCurrentMessage: Boolean = false,
        ignoreDelay: Boolean = false,
    ) {
        if (project.isDisposed) return

        if (!canUpdate() && !ignoreDelay) {
            logger.debug("Skipping commit message update: cooldown period not elapsed")
            return
        }

        try {
            lastUpdateTime = Instant.now().toEpochMilli()

            // Check disposal before potentially long operation
            if (project.isDisposed) return

            val apiResponse = generateCommitMessage(selectedChanges, partialChanges, unversionedFiles)

            if (project.isDisposed) return // Check again after potentially long operation

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater

                commitMessage.let { ui ->
                    val currentMessage = ui.text
                    if (
                        currentMessage.isBlank() ||
                        currentMessage.trim() == previousMessage?.trim() ||
                        overrideCurrentMessage
                    ) {
                        previousMessage = apiResponse
                        ui.text = apiResponse
                    }
                }
            }
        } catch (e: ProcessCanceledException) {
            // Rethrow ProcessCanceledException as required by IntelliJ
            throw e
        } catch (e: AlreadyDisposedException) {
            // Project/service is disposed, this is expected during shutdown - don't log as error
            logger.debug("Project disposed during commit message generation")
            throw e
        } catch (_: CancellationException) {
            // Task was cancelled, which is expected during shutdown
            logger.debug("Commit message generation cancelled")
        } catch (e: Exception) {
            logger.warn("Error making API call", e)
        }
    }

    private fun canUpdate(): Boolean {
        val currentTime = System.currentTimeMillis()
        return (currentTime - lastUpdateTime) >= UPDATE_COOLDOWN_MS
    }

    private fun generateCommitMessage(
        selectedChanges: List<Change> = emptyList(),
        partialChanges: List<PartialChangeInfo> = emptyList(),
        unversionedFiles: List<FilePath> = emptyList(),
    ): String {
        if (project.isDisposed) return ""

        val currentBranch = getCurrentBranchName(project)
        val changeListManager = ChangeListManager.getInstance(project)
        val defaultChangeList = changeListManager.defaultChangeList
        // Only fall back to default change list if no changes AND no unversioned files are explicitly selected
        val latestChanges =
            if (selectedChanges.isNotEmpty() || unversionedFiles.isNotEmpty()) {
                selectedChanges
            } else {
                defaultChangeList.changes.toList()
            }

        // Check disposal status before generating diff
        if (project.isDisposed) return ""

        val diffString =
            ProgressManager.getInstance().runProcess<String>(
                {
                    // Use combined diff generation if we have partial changes
                    val changesDiff =
                        if (partialChanges.isNotEmpty()) {
                            generateCombinedDiffString(latestChanges, partialChanges, project)
                        } else {
                            generateDiffStringFromChanges(latestChanges, project)
                        }

                    // Add unversioned files diff
                    val unversionedDiff =
                        if (unversionedFiles.isNotEmpty()) {
                            generateDiffStringFromUnversionedFiles(unversionedFiles, project)
                        } else {
                            ""
                        }

                    changesDiff + unversionedDiff
                },
                EmptyProgressIndicator(),
            )

        val previousCommitsString =
            if (!project.isDisposed && SweepConfig.getInstance(project).shouldUseCustomizedCommitMessages()) {
                "Recent Commit Messages:\n" +
                    getRecentCommitMessages(project, maxCount = 20)
                        .filterNot { it.contains("merge pull request", ignoreCase = true) }
                        .take(10)
                        .mapIndexed { index, commit -> "${index + 1}. $commit" }
                        .joinToString("\n")
            } else {
                ""
            }

        // Optional user-provided commit message template
        // Priority: Project-specific sweep-commit-template.md > Global ~/.sweep/sweep-commit-template.md
        val commitTemplate: String? =
            try {
                SweepConfig.getInstance(project).getEffectiveCommitMessageRules()?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }

        var commitMessage = ""
        try {
            var connection: HttpURLConnection? = null
            try {
                connection = getConnection("backend/create_commit_message")

                // Set timeouts to prevent hanging
                connection.connectTimeout = 10000 // 10 seconds
                connection.readTimeout = 30000 // 30 seconds

                val commitMessageRequest =
                    CommitMessageRequest(
                        context = diffString,
                        previous_commits = previousCommitsString,
                        branch = currentBranch ?: "",
                        commit_template = commitTemplate,
                    )
                val json = Json { encodeDefaults = true }
                val postData = json.encodeToString(CommitMessageRequest.serializer(), commitMessageRequest)

                connection.outputStream.use { os ->
                    os.write(postData.toByteArray())
                    os.flush()
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val newCommitMessage = json.decodeFromString<Map<String, String>>(response)["commit_message"]

                commitMessage = newCommitMessage.toString()
            } finally {
                connection?.disconnect()
            }
        } catch (e: Exception) {
            logger.warn("Failed to generate commit message", e)
        }

        return commitMessage.trim()
    }

    companion object {
        private val logger = Logger.getInstance(SweepCommitMessageService::class.java)

        // min time between git commit message updates
        private const val UPDATE_COOLDOWN_MS = 5 * 60 * 1000

        fun getInstance(project: Project): SweepCommitMessageService = project.getService(SweepCommitMessageService::class.java)
    }

    override fun dispose() {
        // Cancel any running tasks
        synchronized(runningTasks) {
            runningTasks.forEach { it.cancel(true) }
            runningTasks.clear()
        }

        // Disconnect message bus
        messageBusConnection?.disconnect()
        messageBusConnection = null

        // Clear references
        commitUi = null
        previousMessage = null
    }
}
