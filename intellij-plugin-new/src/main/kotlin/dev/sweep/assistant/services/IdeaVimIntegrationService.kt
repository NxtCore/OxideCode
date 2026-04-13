package dev.sweep.assistant.services

import com.intellij.ide.DataManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_ESCAPE
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption

@Service(Service.Level.PROJECT)
class IdeaVimIntegrationService(
    private val project: Project,
) {
    companion object {
        private const val IDEAVIM_PLUGIN_ID = "IdeaVIM"
        private const val SWEEP_TAB_MAPPING = "map <Tab> :action dev.sweep.assistant.autocomplete.edit.AcceptEditCompletionAction<CR>"
        private const val SWEEP_MAPPING_COMMENT = "\" Sweep AI Tab completion mapping"

        fun getInstance(project: Project): IdeaVimIntegrationService = project.getService(IdeaVimIntegrationService::class.java)
    }

    private val logger = Logger.getInstance(IdeaVimIntegrationService::class.java)

    /**
     * Checks if IdeaVim plugin is installed and enabled
     */
    fun isIdeaVimActive(): Boolean {
        val pluginId = PluginId.getId(IDEAVIM_PLUGIN_ID)
        return PluginManagerCore.isPluginInstalled(pluginId) &&
            PluginManagerCore.getPlugin(pluginId)?.isEnabled == true
    }

    /**
     * Calls vim escape to ensure user enters normal mode when popup is explicitly closed.
     * This method centralizes the vim escape logic used by various popup components.
     */
    fun callVimEscape(editor: Editor) {
        // Presses ESC to exit insert mode in vim
        if (isIdeaVimActive()) {
            val dataContext = DataManager.getInstance().getDataContext(editor.component)
            val escHandler = EditorActionManager.getInstance().getActionHandler(ACTION_EDITOR_ESCAPE)
            escHandler.execute(editor, editor.caretModel.currentCaret, dataContext)
        }
    }

    /**
     * Checks if showing ghost text at the given position would conflict with VIM plugin
     */
    fun wouldConflictWithVim(
        editor: Editor,
        offset: Int,
    ): Boolean {
        if (!isIdeaVimActive()) return false

        // VIM plugin has issues with inlays at column 0
        val document = editor.document
        val line = document.getLineNumber(offset)
        val lineStartOffset = document.getLineStartOffset(line)
        return offset == lineStartOffset && editor.caretModel.offset == lineStartOffset
    }

    /**
     * Checks if IdeaVim plugin is installed and enabled
     * @deprecated Use isIdeaVimActive() instead
     */
    private fun isIdeaVimInstalled(): Boolean = isIdeaVimActive()

    /**
     * Gets the path to the user's .ideavimrc file
     */
    private fun getIdeavimrcPath(): File {
        val userHome = System.getProperty("user.home")
        return File(userHome, ".ideavimrc")
    }

    /**
     * Checks if the Sweep Tab mapping already exists in .ideavimrc
     */
    private fun hasSweepTabMapping(ideavimrcFile: File): Boolean {
        if (!ideavimrcFile.exists()) {
            return false
        }

        return try {
            val content = ideavimrcFile.readText()
            content.contains(SWEEP_TAB_MAPPING)
        } catch (e: Exception) {
            logger.warn("Error reading .ideavimrc file", e)
            false
        }
    }

    /**
     * Adds the Sweep Tab mapping to the .ideavimrc file
     */
    private fun addSweepTabMapping(ideavimrcFile: File) {
        try {
            val mappingWithComment = "\n$SWEEP_MAPPING_COMMENT\n$SWEEP_TAB_MAPPING\n"

            if (ideavimrcFile.exists()) {
                // Append to existing file
                Files.write(
                    ideavimrcFile.toPath(),
                    mappingWithComment.toByteArray(),
                    StandardOpenOption.APPEND,
                )
            } else {
                // Create new file
                Files.write(
                    ideavimrcFile.toPath(),
                    mappingWithComment.toByteArray(),
                    StandardOpenOption.CREATE,
                )
            }

            logger.info("Successfully added Sweep Tab mapping to .ideavimrc")
        } catch (e: Exception) {
            logger.warn("Error adding Sweep Tab mapping to .ideavimrc", e)
        }
    }

    /**
     * Shows a notification asking the user to restart their IDE with a restart button
     */
    private fun showRestartNotification() {
        ApplicationManager.getApplication().invokeLater {
            val notification =
                NotificationGroupManager
                    .getInstance()
                    .getNotificationGroup("Sweep AI Notifications")
                    .createNotification(
                        "IdeaVim Integration Complete",
                        "Sweep has configured your Vim settings for Tab completion. Please restart your IDE to activate the changes.",
                        NotificationType.INFORMATION,
                    ).addAction(
                        NotificationAction.createSimpleExpiring("Restart now") {
                            ApplicationManagerEx.getApplicationEx().restart(true)
                        },
                    )

            notification.notify(project)
        }
    }

    /**
     * Configures IdeaVim integration if the plugin is installed
     */
    fun configureIdeaVimIntegration() {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!isIdeaVimActive()) {
                logger.info("IdeaVim plugin not installed or not enabled, skipping configuration")
                return@executeOnPooledThread
            }

            val ideavimrcFile = getIdeavimrcPath()

            if (hasSweepTabMapping(ideavimrcFile)) {
                logger.info("Sweep Tab mapping already exists in .ideavimrc")
                return@executeOnPooledThread
            }

            logger.info("IdeaVim detected, adding Sweep Tab mapping to .ideavimrc")
            addSweepTabMapping(ideavimrcFile)
            showRestartNotification()
        }
    }
}
