package dev.sweep.assistant

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindow.SHOW_CONTENT_ICON
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.actions.CancelStreamAction
import dev.sweep.assistant.components.*
import dev.sweep.assistant.controllers.Stream
import dev.sweep.assistant.controllers.SweepGhostText
import dev.sweep.assistant.data.RecentlyUsedFiles
import dev.sweep.assistant.listener.SelectedFileChangeListener
import dev.sweep.assistant.services.*
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.theme.SweepIcons.scale
import dev.sweep.assistant.utils.MouseClickedAdapter
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.SweepConstants.TOOLWINDOW_NAME
import dev.sweep.assistant.utils.getGithubRepoName
import dev.sweep.assistant.utils.setSoftFileDescriptorLimit
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class Sweep :
    ToolWindowFactory,
    Disposable,
    DumbAware {
    private fun redirectToSettingsPage(
        toolWindow: ToolWindow,
        project: Project,
        showToolWindow: Boolean = true,
    ) {
        // Clear all session and tab state before removing contents.
        // This is necessary because the contentRemoved listener in TabManager
        // has an early return when hasBeenSet is false, so sessions wouldn't be
        // disposed otherwise. Without this, opening conversations from history
        // after re-logging in would fail (try to switch to non-existent tabs).
        SweepSessionManager.getInstance(project).clearAllSessions()
        TabManager.getInstance(project).clearAllTabState()

        toolWindow.contentManager.run {
            removeAllContents(true)
            addContent(
                factory.createContent(
                    WelcomeScreen(project).create(),
                    "",
                    false,
                ),
            )
        }

        if (showToolWindow) {
            toolWindow.show()
        }
    }

    private fun createNoGitRepoPage(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        toolWindow.contentManager.run {
            removeAllContents(true)
            addContent(
                factory.createContent(
                    panel {
                        row {
                            text(
                                "No git repo is initialized for this directory! Sweep requires a valid git repo. Please initialize and try again.",
                            )
                        }
                        row {
                            link("Try again") {
                                displayChatInterface(project, toolWindow)
                            }
                        }
                    }.withBorder(JBUI.Borders.empty(12)),
                    "",
                    false,
                ),
            )
        }
    }

    private fun displayChatInterface(
        project: Project,
        toolWindow: ToolWindow,
        showToolWindow: Boolean = true,
    ) {
        displayWipPlaceholder(project, toolWindow, showToolWindow)
    }

    private fun displayWipPlaceholder(
        project: Project,
        toolWindow: ToolWindow,
        showToolWindow: Boolean = true,
    ) {
        val content =
            toolWindow.contentManager.factory
                .createContent(
                    JPanel(BorderLayout()).apply {
                        border = JBUI.Borders.empty(16)
                        add(
                            JLabel("OxideCode UI is WIP in this plugin build. Existing chat code is still present but temporarily hidden.").apply {
                                border = JBUI.Borders.empty(8)
                            },
                            BorderLayout.NORTH,
                        )
                    },
                    SweepConstants.NEW_CHAT,
                    true,
                ).apply {
                    // Enable content icon display for streaming indicators
                    putUserData(SHOW_CONTENT_ICON, true)
                }

        toolWindow.title = SweepConstants.NEW_CHAT
        toolWindow.isAutoHide = false
        toolWindow.setTitleActions(
            listOfNotNull(
                SweepActionManager.getInstance(project).settingsAction,
            ),
        )

        if (showToolWindow) {
            toolWindow.show()
        }
    }

    private fun updateToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
        showToolWindow: Boolean = true,
    ) = if (!SweepSettings.getInstance().hasBeenSet) {
        TutorialPage.showAutoCompleteTutorial(project, forceShow = false)
        redirectToSettingsPage(toolWindow, project, true)
    } else {
//        TutorialPage.showChatTutorial(project, forceShow = false)
        displayChatInterface(project, toolWindow, showToolWindow)
    }

    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        _createToolWindowContent(project, toolWindow, true)
    }

    fun _createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
        showToolWindow: Boolean = true,
    ) {
        Disposer.register(SweepProjectService.getInstance(project), this)

        // Hide the tool window title text in the header while keeping the tooltip on the stripe button
        toolWindow.component.putClientProperty(ToolWindowContentUi.HIDE_ID_LABEL, "true")

        // Add left spacing to the header so it's not flush with the sidebar
        addHeaderLeftPadding(toolWindow)

        SweepConstantsService.getInstance(project).repoName = ""
        SweepColorChangeService.getInstance(project).addThemeChangeListener(this) {
            handleThemeChangeHardReset(project, toolWindow)
        }
        TabManager.getInstance(project).setToolWindow(toolWindow)
        RecentlyUsedFiles.getInstance(project)
        SweepGhostText.getInstance(project).attachGhostTextTo(ChatComponent.getInstance(project).textField)
        setSoftFileDescriptorLimit(32768)
        updateToolWindowContent(project, toolWindow, showToolWindow)
        ApplicationManager.getApplication().invokeLater {
            // Track whether settings were configured before, so we only rebuild UI
            // when the configuration state changes (not on every settings change)
            var wasConfigured = SweepSettings.getInstance().hasBeenSet

            project.messageBus.connect(SweepProjectService.getInstance(project)).apply {
                subscribe(
                    SweepSettings.SettingsChangedNotifier.TOPIC,
                    SweepSettings.SettingsChangedNotifier {
                        val settings = SweepSettings.getInstance()
                        val isNowConfigured = settings.hasBeenSet

                        // Only rebuild the tool window content when the configuration state changes:
                        // - unconfigured -> configured: show the chat interface
                        // - configured -> unconfigured: show the sign-in page
                        // This prevents wiping all chat tabs when users just change settings
                        // like the backend URL while staying in a configured state.
                        if (wasConfigured != isNowConfigured) {
                            updateToolWindowContent(project, toolWindow)
                        }
                        wasConfigured = isNowConfigured

                        if (isNowConfigured) {
                            // Do not auto-open the tool window on settings changes.
                        }
                    },
                )
                subscribe(
                    FileEditorManagerListener.FILE_EDITOR_MANAGER,
                    SelectedFileChangeListener.create(project, this),
                )
            }
        }
    }

    /**
     * Rebuilds Sweep's UI safely on theme switch without requiring restart:
     * - Refreshes theme colors
     * - Rebuilds message UI from the existing MessageList (preserves conversation history)
     * - Resets chat surfaces to pick up new colors and borders
     * - Revalidates the ToolWindow to avoid stale layouts
     */
    private fun handleThemeChangeHardReset(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        ApplicationManager.getApplication().invokeLater {
            try {
                // Ensure color cache is up-to-date first
                SweepColors.refreshColors()

                // Stop all active streams/tools to avoid cross-thread UI updates during rebuild
                try {
                    val streams = Stream.instances.values.toList()
                    streams.forEach { it.stop(isUserInitiated = false) }
                } catch (_: Throwable) {
                }

                // Reset major UI surfaces so they reconstruct state with fresh theme values
                // This preserves chat history (MessageList) and settings
                MessagesComponent.getInstance(project).reset()
                ChatComponent.getInstance(project).reset()
                SweepComponent.getInstance(project).reset()

                // Rebuild the ToolWindow content like initial load (no restart)
                val wasVisible = toolWindow.isVisible
                toolWindow.contentManager.removeAllContents(true)
                updateToolWindowContent(project, toolWindow, showToolWindow = wasVisible)

                // Final layout/paint pass
                SwingUtilities.updateComponentTreeUI(toolWindow.component)
                toolWindow.component.revalidate()
                toolWindow.component.repaint()
            } catch (t: Throwable) {
                // fallback, reload tool window content if a targeted reset fails
                Logger.getInstance(Sweep::class.java).warn("Theme change hard reset failed, reloading content", t)
                updateToolWindowContent(project, toolWindow, showToolWindow = toolWindow.isVisible)
            }
        }
    }

    /**
     * Adds left padding to the tool window header so it's not flush with the sidebar.
     */
    private fun addHeaderLeftPadding(toolWindow: ToolWindow) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val toolWindowEx = toolWindow as? ToolWindowEx ?: return@invokeLater
                val decorator = toolWindowEx.decorator ?: return@invokeLater

                // Find the first JPanel child of the decorator (the header panel)
                for (child in decorator.components) {
                    if (child is javax.swing.JPanel) {
                        child.border = JBUI.Borders.emptyLeft(0)
                        child.revalidate()
                        child.repaint()
                        return@invokeLater
                    }
                }
            } catch (_: Exception) {
                // Silently fail - padding is nice-to-have
            }
        }
    }

    override fun dispose() {
//        SweepComponent.disposeAll()
    }

    override suspend fun isApplicableAsync(project: Project): Boolean {
        // Register tool window only if not in frontend mode
        return SweepConstants.GATEWAY_MODE != SweepConstants.GatewayMode.CLIENT
    }
}
