package dev.sweep.assistant.components

import com.intellij.ide.DataManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.controllers.*
import dev.sweep.assistant.data.*
import dev.sweep.assistant.services.*
import dev.sweep.assistant.utils.*
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.*
import javax.swing.*

class FeedbackComponent(
    private val project: Project,
    private val modelPickerProvider: () -> String,
    private val currentOpenFileProvider: () -> String?,
) {
    private val feedbackContainer =
        JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            background = null
        }

    init {
        val thumbsDownLabel =
            JLabel("👎").apply {
                withSweepFont(project, scale = 1.2f)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Not helpful response"
                border = JBUI.Borders.empty(2, 4)
                verticalAlignment = SwingConstants.CENTER
                addMouseListener(
                    MouseReleasedAdapter { e ->
                        // Create a popup to get feedback from the user
                        val feedbackPanel = JPanel(BorderLayout())
                        val feedbackField = JTextArea(4, 30)
                        feedbackField.lineWrap = true
                        feedbackField.wrapStyleWord = true
                        feedbackField.border = JBUI.Borders.empty(4)

                        val scrollPane = JScrollPane(feedbackField)
                        scrollPane.border = JBUI.Borders.empty(8)

                        feedbackPanel.add(scrollPane, BorderLayout.CENTER)

                        val popup =
                            JBPopupFactory
                                .getInstance()
                                .createComponentPopupBuilder(feedbackPanel, feedbackField)
                                .setTitle("Why Was This Response Unsatisfactory?")
                                .setMovable(true)
                                .setResizable(true)
                                .setRequestFocus(true)
                                .setCancelOnClickOutside(true)
                                .setAdText("Press Enter to submit")
                                .createPopup()

                        feedbackField.addKeyListener(
                            object : KeyAdapter() {
                                override fun keyPressed(e: KeyEvent) {
                                    if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                                        e.consume()
                                        val lastMessage =
                                            MessageList
                                                .getInstance(project)
                                                .getLastUserMessage()
                                                ?.getFormattedUserMessage(project)
                                        val files =
                                            MessageList
                                                .getInstance(project)
                                                .snapshot()
                                                .flatMap { it.mentionedFiles }
                                                .distinctFileInfos()
                                        getCurrentBranchNameAsync(project) { branchName ->
                                            val feedbackSubmission =
                                                FeedbackSubmission(
                                                    feedback = feedbackField.text,
                                                    messages = MessageList.getInstance(project).snapshot(),
                                                    snippets = files.map { it.toSnippet() },
                                                    lastMessage = lastMessage,
                                                    codeReplacements =
                                                        MessageList
                                                            .getInstance(project)
                                                            .snapshot()
                                                            .flatMap {
                                                                it.annotations?.codeReplacements
                                                                    ?: emptyList()
                                                            },
                                                    metadata =
                                                        mapOf(
                                                            "sweep_rules" to
                                                                SweepConfig
                                                                    .getInstance(project)
                                                                    .getState()
                                                                    .rules,
                                                            "last_diff" to
                                                                lastMessage?.diffString,
                                                            "model_to_use" to
                                                                modelPickerProvider(),
                                                            "repo_name" to
                                                                SweepConstantsService.getInstance(project).repoName!!,
                                                            "branch" to branchName,
                                                            "current_open_file" to
                                                                currentOpenFileProvider(),
                                                            "telemetry_source" to "jetbrains",
                                                        ),
                                                )
                                            CoroutineScope(Dispatchers.Default).launch {
                                                sendToApi(
                                                    "backend/response_feedback_submission",
                                                    feedbackSubmission,
                                                    FeedbackSubmission.serializer(),
                                                )
                                            }
                                        }
                                        popup.closeOk(null)
                                    }
                                }
                            },
                        )

                        popup.showInBestPositionFor(
                            DataManager.getInstance().getDataContext(e.component),
                        )
                    },
                )
            }

        feedbackContainer.add(thumbsDownLabel)
    }

    fun getComponent(): JPanel = feedbackContainer

    fun setVisible(visible: Boolean) {
        feedbackContainer.isVisible = visible
    }

    fun isVisible(): Boolean = feedbackContainer.isVisible

    fun setBackground(background: Color?) {
        feedbackContainer.background = background
    }

    fun getBackground(): Color? = feedbackContainer.background
}
