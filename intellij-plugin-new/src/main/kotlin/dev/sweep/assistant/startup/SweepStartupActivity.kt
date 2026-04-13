package dev.sweep.assistant.startup

import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.ShowSettingsUtilImpl
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Anchor
import com.intellij.openapi.actionSystem.Constraints
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sweep.assistant.actions.AddToContextFromProjectAction
import dev.sweep.assistant.actions.ReviewPRAction
import dev.sweep.assistant.actions.SweepCommitMessageAction
import dev.sweep.assistant.actions.SweepProblemsAction
import dev.sweep.assistant.autocomplete.edit.AcceptEditCompletionAction
import dev.sweep.assistant.autocomplete.edit.RecentEditsTracker
import dev.sweep.assistant.autocomplete.edit.RejectEditCompletionAction
import dev.sweep.assistant.autocomplete.vim.VimMotionGhostTextService
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.controllers.EditorSelectionManager
import dev.sweep.assistant.controllers.TerminalManagerService
import dev.sweep.assistant.data.ProjectFilesCache
import dev.sweep.assistant.entities.EntitiesCache
import dev.sweep.assistant.services.*
import dev.sweep.assistant.services.GatewayOnboardingService
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.settings.SweepSettingsParser
import dev.sweep.assistant.statusbar.FrontendStatusBarWidgetFactory
import dev.sweep.assistant.tracking.EventType
import dev.sweep.assistant.tracking.TelemetryService
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.disableFullLineCompletion
import dev.sweep.assistant.utils.disableFullLineCompletionAndNotify
import dev.sweep.assistant.utils.logStartupData
import dev.sweep.assistant.utils.showNotification
import dev.sweep.assistant.utils.untrackIdeaFile
import java.awt.event.KeyEvent
import java.util.concurrent.TimeUnit
import javax.swing.KeyStroke

class SweepStartupActivity :
    ProjectActivity,
    DumbAware {
    override suspend fun execute(project: Project) {
        // Handle Full Line completion conflicts with similar logic to plugin conflicts
        // Delay by 5 seconds to ensure IDE subsystems (like EditorColorsManager) are fully initialized
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                if (!project.isDisposed) {
                    // This must run on the EDT because it may trigger UI/settings logic
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            handleFullLineCompletionConflicts(project)
                        }
                    }
                }
            },
            5,
            TimeUnit.SECONDS,
        )

        // Install VimMotionGhostTextHandler to handle VIM motion with ghost text
        VimMotionGhostTextService.getInstance()

        // Register AddToContextFromProjectAction dynamically
        ApplicationManager.getApplication().invokeLater {
            val actionManager = ActionManager.getInstance()

            // Register AddToContextFromProjectAction (only once per IDE)
            val actionId = "dev.sweep.assistant.actions.AddToContextFromProjectAction"
            val addToContextAction =
                if (actionManager.getAction(actionId) == null) {
                    val action = AddToContextFromProjectAction()
                    actionManager.unregisterAction(actionId)
                    actionManager.registerAction(actionId, action)

                    // Add to ProjectViewPopupMenu
                    actionManager.getAction("ProjectViewPopupMenu")?.let { group ->
                        if (group is DefaultActionGroup) {
                            // Only add if not already present in the group
                            if (!group.containsAction(action)) {
                                group.addAction(
                                    action,
                                    Constraints(Anchor.BEFORE, "CutCopyPasteGroup"),
                                )
                            }
                        }
                    }
                    action
                } else {
                    actionManager.getAction(actionId) as AddToContextFromProjectAction
                }
            SweepActionManager.getInstance(project).addToContextAction = addToContextAction

            // Register SweepCommitMessageAction (only once per IDE)
            val commitMessageActionId = "dev.sweep.assistant.actions.SweepCommitMessageAction"
            val commitMessageAction =
                if (actionManager.getAction(commitMessageActionId) == null) {
                    val action = SweepCommitMessageAction()
                    actionManager.unregisterAction(commitMessageActionId)
                    actionManager.registerAction(commitMessageActionId, action)

                    // Add to Vcs.MessageActionGroup
                    actionManager.getAction("Vcs.MessageActionGroup")?.let { group ->
                        if (group is DefaultActionGroup) {
                            // Only add if not already present in the group
                            if (!group.containsAction(action)) {
                                group.addAction(
                                    action,
                                    Constraints(Anchor.LAST, null),
                                )
                            }
                        }
                    }
                    action
                } else {
                    actionManager.getAction(commitMessageActionId) as SweepCommitMessageAction
                }
            SweepActionManager.getInstance(project).commitMessageAction = commitMessageAction

            // Register SweepProblemsAction (only once per IDE)
            val problemsActionId = "dev.sweep.assistant.actions.SweepProblemsAction"
            val problemsAction =
                if (actionManager.getAction(problemsActionId) == null) {
                    val action = SweepProblemsAction()
                    actionManager.unregisterAction(problemsActionId)
                    actionManager.registerAction(problemsActionId, action)

                    // Add to ProblemsView.ToolWindow.TreePopup
                    actionManager.getAction("ProblemsView.ToolWindow.TreePopup")?.let { group ->
                        if (group is DefaultActionGroup) {
                            // Only add if not already present in the group
                            if (!group.containsAction(action)) {
                                group.addAction(
                                    action,
                                    Constraints(Anchor.FIRST, null),
                                )
                            }
                        }
                    }
                    action
                } else {
                    actionManager.getAction(problemsActionId) as SweepProblemsAction
                }
            SweepActionManager.getInstance(project).problemsAction = problemsAction

            // Register ReviewPRAction (only once per IDE)
            val reviewPRActionId = "dev.sweep.assistant.actions.ReviewPRAction"
            val reviewPRAction =
                if (actionManager.getAction(reviewPRActionId) == null) {
                    val action = ReviewPRAction()
                    actionManager.unregisterAction(reviewPRActionId)
                    actionManager.registerAction(reviewPRActionId, action)
                    action
                } else {
                    actionManager.getAction(reviewPRActionId) as ReviewPRAction
                }
            SweepActionManager.getInstance(project).reviewPRAction = reviewPRAction
        }

        ApplicationManager.getApplication().invokeLater {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)
            // Listen for tool window visibility changes to persist state
            project.messageBus.connect(SweepProjectService.getInstance(project)).subscribe(
                ToolWindowManagerListener.TOPIC,
                object : ToolWindowManagerListener {
                    override fun stateChanged(
                        toolWindowManager: ToolWindowManager,
                        changeType: ToolWindowManagerListener.ToolWindowManagerEventType,
                    ) {
                        if (changeType == ToolWindowManagerListener.ToolWindowManagerEventType.HideToolWindow ||
                            changeType == ToolWindowManagerListener.ToolWindowManagerEventType.ActivateToolWindow
                        ) {
                            val sweepToolWindow = toolWindowManager.getToolWindow(SweepConstants.TOOLWINDOW_NAME)
                            if (sweepToolWindow != null) {
                                SweepMetaData.getInstance().isToolWindowVisible = sweepToolWindow.isVisible
                            }
                        }
                    }
                },
            )
        }

        // Initialize application-level services
        RipgrepManager.getInstance() // Initialize ripgrep manager on startup

        // Initialize project-level services
        SweepProjectService.getInstance(project)
        FeatureFlagService.getInstance(project)
        ProjectFilesCache.getInstance(project)
        EntitiesCache.getInstance(project)
        TerminalManagerService.getInstance(project)
        EditorSelectionManager.getInstance(project)
        SweepNonProjectFilesService.getInstance(project)
        SweepCommitMessageService.getInstance(project)
        RecentEditsTracker.getInstance(project)
        IdeaVimIntegrationService.getInstance(project).configureIdeaVimIntegration()
        SweepMcpService.getInstance(project)
        SweepColorChangeService.getInstance(project)
        OSNotificationService.getInstance(project)

        // Send installation telemetry event on first run
        val metaData = SweepMetaData.getInstance()
        if (!metaData.hasSeenInstallationTelemetryEvent) {
            val gatewayModeName = SweepConstants.GATEWAY_MODE.name
            TelemetryService.getInstance().sendUsageEvent(
                EventType.INSTALL_SWEEP,
                userProperties = mapOf("gatewayMode" to gatewayModeName),
            )
            metaData.hasSeenInstallationTelemetryEvent = true
        }

        if (!SweepSettings.getInstance().hasBeenSet) {
            if (SweepSettingsParser.isCloudEnvironment()) {
                // Cloud: wait for explicit user open/login action
            } else {
                // Non-cloud: preserve existing behavior
                SweepAuthServer.start(project)
            }
        }

        // Register appropriate status bar widget based on gateway mode
        when (SweepConstants.GATEWAY_MODE) {
            SweepConstants.GatewayMode.CLIENT -> {
                // Frontend mode: simple widget that only opens settings
                ApplicationManager.getApplication().invokeLater {
                    val statusBar = WindowManager.getInstance().getStatusBar(project)
                    statusBar?.addWidget(FrontendStatusBarWidgetFactory().createWidget(project), "before Position")

                    // Only start auth flow and open browser if user is not authenticated
                    if (!SweepSettings.getInstance().hasBeenSet) {
                        SweepAuthServer.start(project)
                        BrowserUtil.browse("https://app.sweep.dev", project)
                    }
                }
            }
            SweepConstants.GatewayMode.HOST -> {
                // Backend mode: no widget
            }
            SweepConstants.GatewayMode.NA -> {
                // Widget is registered via plugin.xml extension point
            }
        }

        // Untrack plugin state files from VCS
        untrackIdeaFile(project, "GhostTextManager_v2.xml")
        untrackIdeaFile(project, "UnifiedUserActionsTrackerManager.xml")

        // Suppress KtLint plugin errors for ktlint
        // Skip when plugins with logger wrapper incompatibilities are installed
        val loggerIncompatiblePlugins =
            listOf(
                PluginId.getId("io.jmix.studio"),
            )

        val hasLoggerIncompatiblePlugin =
            loggerIncompatiblePlugins.any { pluginId ->
                PluginManagerCore.isPluginInstalled(pluginId) &&
                    PluginManagerCore.getPlugin(pluginId)?.isEnabled == true
            }

        if (!hasLoggerIncompatiblePlugin) {
            try {
                val ktlintLogger = Logger.getInstance("com.nbadal.ktlint.KtlintAnnotator")
                ktlintLogger.setLevel(LogLevel.OFF)
            } catch (e: Throwable) {
                // Ignore - some plugin logger wrappers don't support setLevel
                Logger
                    .getInstance(SweepStartupActivity::class.java)
                    .debug("Could not set log level for ktlint logger due to plugin compatibility issue", e)
            }
        }

        // Send health check to backend
        logStartupData()

        // Migrate privacy settings from SweepConfig to SweepMetaData
        val isNewUser = metaData.chatsSent == 0 && metaData.autocompleteAcceptCount == 0
        val oldPrivacyMode = if (isNewUser) false else SweepConfig.getInstance(project).isOldPrivacyModeEnabled()
        if (!metaData.hasPrivacyModeBeenUpdatedFromProject) {
            metaData.privacyModeEnabled = oldPrivacyMode
            metaData.hasPrivacyModeBeenUpdatedFromProject = true
        }

        // Show Gateway onboarding notification if needed
        GatewayOnboardingService.getInstance().showGatewayOnboardingIfNeeded(project)

        // Check for dual plugin installation (Cloud + Enterprise)
        checkForDualPluginInstallation(project)

        // Ensure accept/reject actions are bound
        ApplicationManager.getApplication().invokeLater {
            ensureEditAutocompleteActionsAreBound()
        }
    }

    private fun checkForDualPluginInstallation(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val cloudPluginId = PluginId.getId("dev.sweep.assistant.cloud")
            val enterprisePluginId = PluginId.getId("dev.sweep.assistant")

            val isCloudInstalled =
                PluginManagerCore.isPluginInstalled(cloudPluginId) &&
                    PluginManagerCore.getPlugin(cloudPluginId)?.isEnabled == true
            val isEnterpriseInstalled =
                PluginManagerCore.isPluginInstalled(enterprisePluginId) &&
                    PluginManagerCore.getPlugin(enterprisePluginId)?.isEnabled == true

            if (isCloudInstalled && isEnterpriseInstalled) {
                showDualInstallationWarning(project)
            }
        }
    }

    private fun showDualInstallationWarning(project: Project) {
        val notification =
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup("Sweep AI Notifications")
                .createNotification(
                    title = "Multiple Sweep Versions Detected",
                    content =
                        """
                        Both Cloud and Enterprise versions of Sweep are installed.
                        Please uninstall one of them to avoid conflicts.
                        If you are unsure which version to use, please contact the Sweep team at team@sweep.dev.
                        """.trimIndent(),
                    type = NotificationType.WARNING,
                )

        notification.addAction(
            object : NotificationAction("Open Plugin Settings") {
                override fun actionPerformed(
                    e: AnActionEvent,
                    notification: com.intellij.notification.Notification,
                ) {
                    com.intellij.ide.actions.ShowSettingsUtilImpl.showSettingsDialog(
                        project,
                        "preferences.pluginManager",
                        "Sweep",
                    )
                }
            },
        )

        notification.addAction(
            object : NotificationAction("Dismiss") {
                override fun actionPerformed(
                    e: AnActionEvent,
                    notification: com.intellij.notification.Notification,
                ) {
                    notification.expire()
                }
            },
        )

        notification.notify(project)
    }

    private fun handleFullLineCompletionConflicts(project: Project) {
        if (SweepSettings.getInstance().disableConflictingPlugins) {
            disableFullLineCompletion(project)
        } else {
            // Even when auto-disable is off, check for conflicts and notify user
            ApplicationManager.getApplication().invokeLater {
                checkAndNotifyConflictingPlugins(project, autoDisable = false)
            }
        }
    }

    private fun checkAndNotifyConflictingPlugins(
        project: Project,
        autoDisable: Boolean = false,
    ) {
        val metaData = SweepMetaData.getInstance()

        // Get only installed AND enabled conflicting plugins
        val conflictingPlugins = getConflictingPlugins()

        if (conflictingPlugins.isNotEmpty()) {
            // Double-check that plugins are actually enabled before proceeding
            val enabledConflictingPlugins =
                conflictingPlugins.filter { pluginId ->
                    PluginManagerCore.getPlugin(pluginId)?.isEnabled == true
                }

            if (enabledConflictingPlugins.isEmpty()) {
                return
            }

            // Check if user has dismissed notifications
            if (metaData.dontShowConflictNotifications) {
                return
            }

            val pluginNames =
                enabledConflictingPlugins
                    .map { pluginId ->
                        SweepConstants.PLUGIN_ID_TO_NAME[pluginId] ?: PluginManagerCore.getPlugin(pluginId)?.name ?: pluginId.idString
                    }.joinToString(separator = ", ")

            if (autoDisable) {
                // Auto-disable mode: disable Full Line completion instead of plugins
                disableFullLineCompletion(project)
            } else {
                // Manual mode: show notification with option to disable
                showNotification(
                    project = project,
                    title = "Conflicting Autocomplete Plugins Detected",
                    body =
                        "Sweep detected the following potentially conflicting plugins: $pluginNames. " +
                            "These plugins may interfere with Sweep's autocomplete functionality. " +
                            "You can manage these plugins in Settings > Plugins.",
                    notificationGroup = "Sweep Plugin Conflicts",
                    notificationType = NotificationType.WARNING,
                    action =
                        object : NotificationAction("Disable autocomplete for these plugins") {
                            override fun actionPerformed(
                                e: AnActionEvent,
                                notification: com.intellij.notification.Notification,
                            ) {
                                notification.expire()
                                metaData.dontShowConflictNotifications = true
                                disableFullLineCompletionAndNotify(project)
                            }
                        },
                    action2 =
                        object : NotificationAction("Don't show again") {
                            override fun actionPerformed(
                                e: AnActionEvent,
                                notification: com.intellij.notification.Notification,
                            ) {
                                notification.expire()
                                metaData.dontShowConflictNotifications = true
                            }
                        },
                )
            }
        }
    }

    private fun getConflictingPlugins() =
        SweepConstants.PLUGINS_TO_DISABLE
            .filter { PluginManagerCore.isPluginInstalled(it) && PluginManagerCore.getPlugin(it)?.isEnabled == true }

    private fun ensureEditAutocompleteActionsAreBound() {
        val keymap = KeymapManager.getInstance().activeKeymap
        val acceptActionId = AcceptEditCompletionAction.ACTION_ID
        val rejectActionId = RejectEditCompletionAction.ACTION_ID

        if (keymap.getShortcuts(acceptActionId).isEmpty()) {
            keymap.addShortcut(
                acceptActionId,
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), null),
            )
        }

        if (keymap.getShortcuts(rejectActionId).isEmpty()) {
            keymap.addShortcut(
                rejectActionId,
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), null),
            )
        }
    }
}
