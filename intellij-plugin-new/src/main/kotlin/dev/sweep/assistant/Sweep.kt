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
        val content =
            toolWindow.contentManager.factory
                .createContent(
                    panel {
                        row { label("Loading...") }
                    }.withBorder(JBUI.Borders.empty(12)),
                    SweepConstants.NEW_CHAT,
                    true,
                ).apply {
                    // Enable content icon display for streaming indicators
                    putUserData(SHOW_CONTENT_ICON, true)
                }

        ApplicationManager.getApplication().invokeLater {
            try {
                toolWindow.contentManager.run {
                    // Add new content BEFORE removing old content to prevent contentManager from becoming empty.
                    // This avoids triggering TabManager's contentRemoved -> newChat() auto-create behavior.
                    val oldContents = contents.toList()
                    addContent(content)
                    oldContents.forEach { removeContent(it, true) }
                }
            } catch (e: NullPointerException) {
                // Retry after 200ms delay on background thread, then schedule back to UI thread
                ApplicationManager.getApplication().executeOnPooledThread {
                    Thread.sleep(200)
                    ApplicationManager.getApplication().invokeLater {
                        try {
                            toolWindow.contentManager.run {
                                val oldContents = contents.toList()
                                addContent(content)
                                oldContents.forEach { removeContent(it, true) }
                            }
                        } catch (e2: Exception) {
                            // Log and fail silently
                            Logger
                                .getInstance(Sweep::class.java)
                                .warn("Failed to add content to ToolWindow after retry", e2)
                        }
                    }
                }
            }
        }

        getGithubRepoName(project) { foundRepoName ->
            ApplicationManager.getApplication().invokeLater {
                if (foundRepoName == null) {
                    createNoGitRepoPage(project, toolWindow)
                    return@invokeLater
                }
                SweepConstantsService.getInstance(project).repoName = foundRepoName
                toolWindow.title = SweepConstants.NEW_CHAT
                toolWindow.isAutoHide = false

                SweepActionManager.getInstance(project).newChatAction =
                    createCustomAction(
                        project = project,
                        text = if (SweepMetaData.getInstance().newButtonClicks >= 3) "" else "New Chat",
                        description = "Start a new chat ${SweepConstants.META_KEY}N",
                        icon = AllIcons.General.Add,
                    ) {
                        SweepMetaData.getInstance().newButtonClicks++
                        SweepComponent.getInstance(project).createNewChat()
                    }

                SweepActionManager.getInstance(project).historyAction =
                    createCustomAction(
                        project = project,
                        text = if (SweepMetaData.getInstance().historyButtonClicks >= 2) "" else "History",
                        description = "View chat history",
                        icon = AllIcons.Vcs.History,
                    ) {
                        SweepMetaData.getInstance().historyButtonClicks++
                        ChatHistoryComponent.getInstance(project).showChatHistoryPopup()
                    }

                SweepActionManager.getInstance(project).showTutorialAction =
                    object : AnAction("Sweep Tutorial", "", SweepIcons.SweepIcon.scale(16f)) {
                        override fun actionPerformed(e: AnActionEvent) {
                            TutorialPage.showAutoCompleteTutorial(project, forceShow = true)
                        }
                    }

                // Only create report action if user hasn't reached usage thresholds
                val shouldShowReport =
                    SweepMetaData.getInstance().reportButtonClicks < 1 &&
                        SweepMetaData.getInstance().chatsSent < 5 &&
                        SweepMetaData.getInstance().autocompleteAcceptCount < 50
                SweepActionManager.getInstance(project).reportAction =
                    if (!shouldShowReport) {
                        null
                    } else {
                        createCustomAction(
                            project = project,
                            text = "Feedback",
                            description = "Feedback",
                            icon = AllIcons.Actions.Report,
                        ) {
                            SweepMetaData.getInstance().reportButtonClicks++
                            try {
                                com.intellij.ide.BrowserUtil.browse(
                                    java.net.URI(
                                        "https://app.sweep.dev/feedback",
                                    ),
                                )
                            } catch (ex: Exception) {
                                // Silently handle any exceptions
                            }
                        }
                    }

                SweepActionManager.getInstance(project).settingsAction =
                    createCustomAction(
                        project = project,
                        text = if (SweepMetaData.getInstance().configButtonClicks >= 3) "" else "Settings",
                        description = "Open Preferences",
                        icon = AllIcons.General.Settings,
                    ) {
                        SweepMetaData.getInstance().configButtonClicks++
                        SweepConfig.getInstance(project).showConfigPopup()
                    }

                SweepActionManager.getInstance(project).openSettingsAction =
                    object : AnAction("Sweep Settings", "Configure Sweep", SweepIcons.SweepIcon.scale(16f)) {
                        override fun actionPerformed(e: AnActionEvent) {
                            SweepConfig.getInstance(project).showConfigPopup()
                        }
                    }

                ActionManager.getInstance().run {
                    unregisterAction("SweepNewChat")
                    SweepActionManager.getInstance(project).newChatAction?.let { registerAction("SweepNewChat", it) }
                    unregisterAction("SweepChatHistory")
                    SweepActionManager.getInstance(project).historyAction?.let {
                        registerAction(
                            "SweepChatHistory",
                            it,
                        )
                    }
                    unregisterAction("SweepTutorial")
                    SweepActionManager.getInstance(project).showTutorialAction?.let {
                        registerAction(
                            "SweepTutorial",
                            it,
                        )
                    }
                    unregisterAction("SweepReport")
                    if (shouldShowReport) {
                        SweepActionManager.getInstance(project).reportAction?.let { registerAction("SweepReport", it) }
                    }
                    unregisterAction("SweepSettings")
                    SweepActionManager.getInstance(project).settingsAction?.let { registerAction("SweepSettings", it) }
                    unregisterAction("SweepOpenSettings")
                    SweepActionManager.getInstance(project).openSettingsAction?.let {
                        registerAction(
                            "SweepOpenSettings",
                            it,
                        )
                    }
                }
                toolWindow.setTitleActions(
                    listOfNotNull(
                        SweepActionManager.getInstance(project).historyAction,
                        SweepActionManager.getInstance(project).reportAction,
                        SweepActionManager.getInstance(project).newChatAction,
                        SweepActionManager.getInstance(project).settingsAction,
                    ),
                )
                val connection = project.messageBus.connect(this) // myDisposable ensures cleanup

                // Create a proper session with its own UI component (same as newChat())
                // This eliminates the legacy path in TabManager
                val sessionManager = SweepSessionManager.getInstance(project)
                val session = sessionManager.createSession()
                val sessionComponent = sessionManager.createSessionUIComponent(session)

                // Set the session's UI component as the content for the tool window
                content.component = sessionComponent.component

                // Bind session to content so TabManager can find it
                sessionManager.bindSessionToContent(session, content)

                // Update TabManager's conversationIdMap for this content.
                // This is needed because contentAdded fires before the session is created,
                // so the map wasn't populated in the listener. Without this, the tab icon
                // won't show during streaming for the first tab.
                TabManager.getInstance(project).conversationIdMap[content] = session.conversationId

                // Set this session as the active session in SweepSessionManager.
                // This is critical because selectionChanged fires before the session is bound,
                // so the normal activation path in TabManager doesn't set the active session.
                // Without this, MessageList.activeConversationId falls back to the fallbackMessageList
                // which has a different conversationId, causing "No session found" errors in Stream.start.
                sessionManager.setActiveSession(session.sessionId)

                // Activate the session to populate the UI (ChatComponent, RecentChats, etc.)
                sessionComponent.activate()

                toolWindow.component.addMouseListener(
                    MouseClickedAdapter {
                        ChatComponent.getInstance(project).textField.requestFocusInWindow()
                    },
                )

                // Register alt+Backspace on the session component to trigger CancelStreamAction.
                CancelStreamAction.registerCustomShortcutSet(
                    CustomShortcutSet.fromString("shift BACK_SPACE"),
                    sessionComponent.component,
                    this@Sweep,
                )
            }
        }

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
                            ApplicationManager.getApplication().invokeLater {
                                if (!project.isDisposed && project.isOpen) {
                                    ToolWindowManager.getInstance(project).getToolWindow(TOOLWINDOW_NAME)?.show()
                                }
                            }
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
