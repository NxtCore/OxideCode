package dev.sweep.assistant.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.ui.CommitMessage
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler
import dev.sweep.assistant.services.SweepCommitMessageService
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.utils.getPartialChanges

class SweepCommitMessageAction : AnAction() {
    init {
        templatePresentation.apply {
            text = "Generate New Commit Message"
            description = "Generates a commit message"
            icon = SweepIcons.Sweep16x16
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val sweepCommitMessageService = SweepCommitMessageService.getInstance(project)
        val sweepMetaData = SweepMetaData.getInstance()
        sweepMetaData.commitMessageButtonClicks++

        val commitMessage = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) as? CommitMessage ?: return
        val abstractCommitWorkflowHandler = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) as? AbstractCommitWorkflowHandler<*, *>
        val selectedChanges = abstractCommitWorkflowHandler?.ui?.getIncludedChanges() ?: emptyList()
        val unversionedFiles = abstractCommitWorkflowHandler?.ui?.getIncludedUnversionedFiles() ?: emptyList()
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Generating Commit Message...", false) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true

                    // Detect partial changes (chunk-level selections)
                    val partialChanges = getPartialChanges(project, selectedChanges)

                    // Pass both file-level changes AND chunk-level partial changes
                    sweepCommitMessageService.updateCommitMessage(
                        commitMessage,
                        selectedChanges = selectedChanges,
                        partialChanges = partialChanges,
                        unversionedFiles = unversionedFiles,
                        overrideCurrentMessage = true,
                        ignoreDelay = true,
                    )
                }
            },
        )
    }
}
