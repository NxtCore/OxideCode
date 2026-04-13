package dev.sweep.assistant.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindowManager
import dev.sweep.assistant.components.ChatComponent
import dev.sweep.assistant.services.SweepNonProjectFilesService
import dev.sweep.assistant.utils.SweepConstants

class AddToContextFromProjectAction : AnAction() {
    companion object {
        private val ICON = IconLoader.getIcon("/icons/oxidecode16x16.svg", AddToContextFromProjectAction::class.java)
    }

    init {
        templatePresentation.apply {
            text = "Add File to Sweep Agent"
            description = "Add this file to context"
            icon = ICON
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return

        val chatComponent = ChatComponent.getInstance(project)
        val filesInContextComponent = chatComponent.filesInContextComponent

        val filesToProcess = virtualFiles.toMutableList()
        var index = 0

        while (index < filesToProcess.size) {
            // Check file limit at the beginning of each iteration
            if (filesToProcess.size > 50) {
                com.intellij.openapi.ui.Messages.showWarningDialog(
                    project,
                    "Cannot add more than 50 files to context at once. Please use Sweep agent (https://docs.sweep.dev/agent) instead.",
                    "File Limit Exceeded",
                )
                return
            }

            val virtualFile = filesToProcess[index]
            if (!virtualFile.isDirectory) {
                // Check if the file is a non-project file and add to SweepNonProjectFilesService if so
                val nonProjectFilesService = SweepNonProjectFilesService.getInstance(project)
                val isInProject = ProjectFileIndex.getInstance(project).isInContent(virtualFile)
                if (!isInProject) {
                    nonProjectFilesService.addAllowedFile(virtualFile.path)
                }
                filesInContextComponent.addSuggestedFile(virtualFile.path)
            } else {
                val children = virtualFile.children
                // Check if adding children would exceed the limit
                if (filesToProcess.size + children.size > 50) {
                    com.intellij.openapi.ui.Messages.showWarningDialog(
                        project,
                        "Cannot add more than 50 files to context at once. Please use Sweep agent (https://docs.sweep.dev/agent) instead.",
                        "File Limit Exceeded",
                    )
                    return
                }
                filesToProcess.addAll(children)
            }
            index++
        }

        // Show the tool window if it's not already visible
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)
        if (toolWindow?.isVisible == false) {
            toolWindow.show()
        }
    }

    override fun update(e: AnActionEvent) {
        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        e.presentation.isVisible = virtualFiles != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
