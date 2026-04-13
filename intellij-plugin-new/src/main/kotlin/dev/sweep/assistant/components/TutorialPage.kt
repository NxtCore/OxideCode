package dev.sweep.assistant.components

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.impl.NonProjectFileWritingAccessProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.GotItTooltip
import dev.sweep.assistant.services.SweepNonProjectFilesService
import dev.sweep.assistant.services.SweepProjectService
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.tracking.EventType
import dev.sweep.assistant.tracking.TelemetryService
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.showNotification
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import javax.swing.Timer

object TutorialPage {
    enum class IdeConfig(
        val ideName: String,
        val extension: String,
        val fileType: String,
        val useSnakeCase: Boolean,
    ) {
        PYCHARM("pycharm", "py", "Python", true),
        INTELLIJ("intellij", "java", "JAVA", false),
        ANDROID_STUDIO("android studio", "kt", "Kotlin", false),
        WEBSTORM("webstorm", "ts", "TypeScript", false),
        RUBYMINE("rubymine", "rb", "Ruby", true),
        GOLAND("goland", "go", "Go", true),
        CLION("clion", "cpp", "C/C++", true),
        RUSTROVER("rustrover", "rs", "Rust", true),
        RIDER("rider", "cs", "C#", false),
        DEFAULT("", "ts", "TypeScript", false),
        ;

        companion object {
            fun fromIde(ideName: String): IdeConfig = values().find { ideName.contains(it.ideName, ignoreCase = true) } ?: DEFAULT
        }
    }

//    private val currentIde = IdeConfig.fromIde(ApplicationInfo.getInstance().fullApplicationName)
    private val currentIde = IdeConfig.fromIde("PyCharm")
    private val isMac = System.getProperty("os.name").lowercase().contains("mac")
    private val cmdPrefix = if (isMac) "Cmd" else "Ctrl"

    private val parentDir = if (currentIde.useSnakeCase) "sweep" else ""

    // Autocomplete tutorial file naming
    private val autocompleteFileName = if (currentIde.useSnakeCase) "sweep_tutorial" else "SweepTutorial"
    val AUTOCOMPLETE_NAME = "$autocompleteFileName.${currentIde.extension}"
    private val autocompleteTmpDir = File(System.getProperty("java.io.tmpdir"), parentDir)
    private val autocompleteTmpFile = File(autocompleteTmpDir, AUTOCOMPLETE_NAME)
    val AUTOCOMPLETE_PATH = autocompleteTmpFile.absolutePath
    val AUTOCOMPLETE_PATH_URI = autocompleteTmpFile.toURI()

    // Chat tutorial file naming
    private val chatFileName = if (currentIde.useSnakeCase) "chat_tutorial" else "SweepChatTutorial"
    val CHAT_NAME = "$chatFileName.${currentIde.extension}"
    private val chatTmpDir = File(System.getProperty("java.io.tmpdir"), parentDir)
    private val chatTmpFile = File(chatTmpDir, CHAT_NAME)
    val CHAT_PATH = chatTmpFile.absolutePath
    val CHAT_PATH_URI = chatTmpFile.toURI()

    val autoCompleteTutorialContent: String
        get() =
            this::class.java
                .getResource("/tutorials/AutocompleteTutorial.${currentIde.extension}")
                ?.readText()
                ?.replace("{CMD_J}", "$cmdPrefix+J")
                ?: throw IllegalStateException("Could not load autocomplete tutorial template for ${currentIde.name}")

    val chatTutorialContent: String
        get() =
            this::class.java
                .getResource("/tutorials/ChatTutorial.${currentIde.extension}")
                ?.readText()
                ?.replace("{CMD_J}", "$cmdPrefix+J")
                ?: throw IllegalStateException("Could not load chat tutorial template for ${currentIde.name}")

    private fun createTutorialFile(isChatTutorial: Boolean): VirtualFile? =
        try {
            val targetFile = if (isChatTutorial) chatTmpFile else autocompleteTmpFile
            val targetDir = if (isChatTutorial) chatTmpDir else autocompleteTmpDir
            val targetPath = if (isChatTutorial) CHAT_PATH else AUTOCOMPLETE_PATH
            val content = if (isChatTutorial) chatTutorialContent else autoCompleteTutorialContent

            // Create the parent directory if it doesn't exist
            targetDir.mkdirs()

            // Write content to tmp file
            Files.write(
                targetFile.toPath(),
                content.toByteArray(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )

            // Get virtual file from local file system
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(targetPath)

            // Disable edit protection for this virtual file
            virtualFile?.let {
                NonProjectFileWritingAccessProvider.allowWriting(listOf(it))
            }

            virtualFile
        } catch (e: Exception) {
            null
        }

    fun getAutoCompleteTutorialVirtualFile(): VirtualFile? {
        // Always create/recreate the autocomplete tutorial file in tmp
        return createTutorialFile(isChatTutorial = false)
    }

    fun getChatTutorialVirtualFile(): VirtualFile? {
        // Always create/recreate the chat tutorial file in tmp
        return createTutorialFile(isChatTutorial = true)
    }

    /**
     * Normalizes file paths for tutorial files to help the LLM during tutorial mode.
     * If the file path contains tutorial patterns, it returns the standardized tmp path.
     */
    fun normalizeTutorialPath(filePath: String): String =
        when {
            filePath.contains("sweep_tutorial") || filePath.contains("SweepTutorial") ->
                autocompleteTmpFile.absolutePath
            filePath.contains("chat_tutorial") || filePath.contains("SweepChatTutorial") ->
                chatTmpFile.absolutePath
            filePath.contains("tutorial.py") -> // Legacy support
                autocompleteTmpFile.absolutePath
            else ->
                filePath
        }

    private fun setupTutorialTracking(
        project: Project,
        virtualFile: VirtualFile?,
        isChatTutorial: Boolean,
    ) {
        val telemetryService = TelemetryService.getInstance()

        // Send tutorial shown event
        telemetryService.sendUsageEvent(
            eventType = if (isChatTutorial) EventType.CHAT_TUTORIAL_SHOWN else EventType.AUTOCOMPLETE_TUTORIAL_SHOWN,
        )

        // Set up completion tracking
        if (virtualFile != null) {
            val document = FileDocumentManager.getInstance().getDocument(virtualFile)
            document?.let { doc ->
                var hasCompletedTutorial = false

                val listener =
                    object : DocumentListener {
                        override fun documentChanged(event: DocumentEvent) {
                            if (!hasCompletedTutorial && isTutorialCompleted(doc.text, isChatTutorial)) {
                                hasCompletedTutorial = true
                                telemetryService.sendUsageEvent(
                                    eventType = if (isChatTutorial) EventType.CHAT_TUTORIAL_COMPLETED else EventType.AUTOCOMPLETE_TUTORIAL_COMPLETED,
                                )
                                // Clean up immediately after completion
                                doc.removeDocumentListener(this)
                            }
                        }
                    }

                doc.addDocumentListener(listener, SweepProjectService.getInstance(project))
            }
        }
    }

    private fun isTutorialCompleted(
        content: String,
        isChatTutorial: Boolean,
    ): Boolean =
        if (isChatTutorial) {
            // Chat tutorial: Check if the edge case in calculate_average is fixed (should handle empty list)
            content.contains("len(numbers) == 0") ||
                content.contains("len(numbers) > 0") ||
                content.contains("if not numbers") ||
                content.contains("if numbers:") ||
                content.contains("if len(numbers) == 0") ||
                content.contains("if len(numbers) != 0")
        } else {
            // Autocomplete tutorial: Check if Priority enum values are used instead of strings
            val hasHighPriority =
                content.contains("Priority.HIGH") &&
                    !content.contains("task.priority = \"high\"")
            val hasMediumPriority =
                content.contains("Priority.MEDIUM") &&
                    !content.contains("task.priority = \"medium\"")
            val hasLowPriority =
                content.contains("Priority.LOW") &&
                    !content.contains("task.priority = \"low\"")

            listOf(hasHighPriority, hasMediumPriority, hasLowPriority).count { it } == 3
        }

    fun showAutoCompleteTutorial(
        project: Project,
        forceShow: Boolean,
    ) {
        if (!forceShow && SweepMetaData.getInstance().hasSeenTutorialV2) return
        SweepMetaData.getInstance().hasSeenTutorialV2 = true

        // Execute file system operations on a background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            val nonProjectFilesService = SweepNonProjectFilesService.getInstance(project)
            nonProjectFilesService.addAllowedFile(AUTOCOMPLETE_PATH)

            val virtualFile = getAutoCompleteTutorialVirtualFile()

            // Switch back to EDT for UI operations
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    if (virtualFile != null) {
                        // Open file with caret at line 11 (0-indexed: line 10), position 20
                        val descriptor = OpenFileDescriptor(project, virtualFile, 10, 20)
                        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)

                        // Delay tooltip to ensure editor is fully rendered
                        Timer(500) {
                            if (!project.isDisposed) {
                                showCursorTooltip(project)
                            }
                        }.apply {
                            isRepeats = false
                            start()
                        }
                    }
                    setupTutorialTracking(project, virtualFile, isChatTutorial = false)
                }
            }
        }
    }

    fun showChatTutorial(
        project: Project,
        forceShow: Boolean,
    ) {
        if (!forceShow && SweepMetaData.getInstance().hasSeenChatTutorial) return
        SweepMetaData.getInstance().hasSeenChatTutorial = true

        // Execute file system operations on a background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            val nonProjectFilesService = SweepNonProjectFilesService.getInstance(project)
            nonProjectFilesService.addAllowedFile(CHAT_PATH)

            val virtualFile = getChatTutorialVirtualFile()

            // Switch back to EDT for UI operations
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    openToolWindowWithInstructions(project)
                    // Open file the standard way
                    virtualFile?.let {
                        FileEditorManager.getInstance(project).openFile(it, true)
                    }
                    setupTutorialTracking(project, virtualFile, isChatTutorial = true)
                }
            }
        }
    }

    private fun showCursorTooltip(project: Project) {
        // Get the current text editor
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return

        // Get the visual position of the cursor
        val caretModel = editor.caretModel
        val visualPosition = caretModel.visualPosition

        // Convert visual position to point in editor coordinates
        val point = editor.visualPositionToXY(visualPosition)
        val editorComponent = editor.contentComponent

        // Create and show the GotIt tooltip above the cursor position
        GotItTooltip(
            "sweep.tutorial.cursor.attention",
            "Start typing \"Priority\". Then press tab ⇥ to accept the completion.",
            SweepProjectService.getInstance(project),
        ).withTimeout(10000)
            .withPosition(Balloon.Position.above) // Explicitly set position to above
            .show(editorComponent) { _, _ ->
                // Return the cursor position directly - the balloon will position itself above
                point
            }
    }

    private fun openToolWindowWithInstructions(project: Project) {
        // Get the Sweep toolwindow and show it
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)
        toolWindow?.show()

        // Populate the chat text field with the first tutorial instruction if not already populated
        val bugfixText = "fix the edge case in calculate_average (it's a temp file)"
        val chatComponent = ChatComponent.getInstance(project)
        if (!chatComponent.textField.text.contains(bugfixText)) {
            chatComponent.appendToTextField(bugfixText)
        }

        // add notification after 5s
        Timer(1000 * 5) {
            showNotification(
                project,
                "Learn to use Sweep Agent",
                "Click Send ↑ in the side bar to get started.",
            )
        }.apply {
            isRepeats = false
            start()
        }
    }
}
