package dev.sweep.assistant.statusbar

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PermanentInstallationID
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.Consumer
import com.intellij.vcsUtil.showAbove
import dev.sweep.assistant.services.AutocompleteSnoozeService
import dev.sweep.assistant.services.FeatureFlagService
import dev.sweep.assistant.services.RustCoreBridge
import dev.sweep.assistant.services.SweepProjectService
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.tracking.EventType
import dev.sweep.assistant.tracking.TelemetryService
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.event.MouseEvent
import javax.swing.Icon

@Serializable
data class AutocompleteEntitlementResponse(
    val is_entitled: Boolean,
    val autocomplete_suggestions_remaining: Int,
    val autocomplete_budget: Int,
)

class AutocompleteStatusBarWidget(
    private val project: Project,
) : StatusBarWidget,
    StatusBarWidget.IconPresentation,
    Disposable {
    companion object {
        const val ID = "SweepAutocompleteStatus"
        private const val CHECK_INTERVAL_MS = 900000L // Check every 15 minutes
        private const val TIMEOUT_MS = 5000 // 5 second timeout for health check
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isAlive = true
    private var clickHandler: Consumer<MouseEvent>? = null
    private var entitlementInfo: AutocompleteEntitlementResponse? = null
    private var hasShownLowCompletionsWarning = false
    private var hasShownOutOfCompletionsNotification = false
    private val snoozeService = AutocompleteSnoozeService.getInstance(project)
    private val snoozeStateListener = { updateWidget() }
    private val rustCoreBridge by lazy {
        ApplicationManager.getApplication().getService(RustCoreBridge::class.java)
    }

    init {
        Disposer.register(SweepProjectService.getInstance(project), this)
        snoozeService.addSnoozeStateListener(snoozeStateListener)
        startHealthCheck()
    }

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: com.intellij.openapi.wm.StatusBar) {
        // Widget is installed
    }

    override fun dispose() {
        snoozeService.removeSnoozeStateListener(snoozeStateListener)
        scope.cancel()
    }

    // IconPresentation implementation
    override fun getIcon(): Icon? =
        when {
            shouldShowDarkerIcon() -> getSweepIcon(IconState.DARKER)
            else -> getSweepIcon(IconState.NORMAL)
        }

    private enum class IconState {
        NORMAL,
        DARKER,
    }

    private fun shouldShowDarkerIcon(): Boolean {
        // Show darker icon when snoozed, offline, or out of completions
        return snoozeService.isAutocompleteSnooze() ||
            !isAlive ||
            (entitlementInfo?.autocomplete_suggestions_remaining == 0)
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? = clickHandler

    override fun getTooltipText(): String =
        when {
            snoozeService.isAutocompleteSnooze() -> {
                val remaining = snoozeService.formatRemainingTime()
                "OxideCode Autocomplete: Snoozed ($remaining remaining) - Click for options"
            }
            isAlive -> "OxideCode Autocomplete: Online - Click for options"
            else -> "OxideCode Autocomplete: Offline - Click for options"
        }

    private fun getSweepIcon(state: IconState): Icon {
        val baseIcon = IconLoader.getIcon("/icons/oxidecode16x16.svg", javaClass)

        return baseIcon
    }

    private fun startHealthCheck() {
        // Set up click handler to show popup menu
        clickHandler =
            Consumer { event ->
                showPopupMenu(event)
            }

        // Start periodic health check
        scope.launch {
            while (isActive) {
                checkAutocompleteHealth()
                updateWidget()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private fun showPopupMenu(event: MouseEvent) {
        // Refresh entitlement info when user clicks
        scope.launch {
            checkAutocompleteHealth()
        }
        updateWidget()

        val menuItems = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        val currentEntitlementInfo = entitlementInfo

        val accessStatus =
            when {
                snoozeService.isAutocompleteSnooze() -> {
                    val remaining = snoozeService.formatRemainingTime()
                    "🔄 Snoozed ($remaining remaining)"
                }
                !isAlive -> "Offline"
                currentEntitlementInfo == null -> "Unauthorized"
                currentEntitlementInfo.autocomplete_suggestions_remaining == 0 -> "No Completions Left"
                currentEntitlementInfo.autocomplete_suggestions_remaining <= currentEntitlementInfo.autocomplete_budget -> {
                    "${maxOf(
                        0,
                        currentEntitlementInfo.autocomplete_suggestions_remaining,
                    )}/${currentEntitlementInfo.autocomplete_budget} remaining"
                }
                else -> "Unlimited access"
            }

        if (snoozeService.isAutocompleteSnooze()) {
            // Show unsnooze option
            val remaining = snoozeService.formatRemainingTime()
            menuItems.add("Unsnooze ($remaining remaining)")
            actions.add { snoozeService.unsnooze() }
        } else {
            // Show snooze options
            val snoozeOptions =
                listOf(
                    "Snooze for 5 minutes" to AutocompleteSnoozeService.SNOOZE_5_MINUTES,
                    "Snooze for 15 minutes" to AutocompleteSnoozeService.SNOOZE_15_MINUTES,
                    "Snooze for 30 minutes" to AutocompleteSnoozeService.SNOOZE_30_MINUTES,
                    "Snooze for 1 hour" to AutocompleteSnoozeService.SNOOZE_1_HOUR,
                    "Snooze for 2 hours" to AutocompleteSnoozeService.SNOOZE_2_HOURS,
                )

            snoozeOptions.forEach { (label, duration) ->
                menuItems.add(label)
                actions.add {
                    snoozeService.snoozeAutocomplete(duration)
                    TelemetryService.getInstance().sendUsageEvent(
                        EventType.AUTOCOMPLETE_SNOOZED,
                        eventProperties = mapOf("duration_ms" to duration.toString()),
                    )
                }
            }

            if (!isAlive) {
                menuItems.add("Retry Connection")
                actions.add { checkAutocompleteHealth() }
            }
        }

        val popupStep =
            object : BaseListPopupStep<String>("OxideCode Autocomplete\n($accessStatus)", menuItems) {
                override fun onChosen(
                    selectedValue: String?,
                    finalChoice: Boolean,
                ): PopupStep<*>? {
                    if (finalChoice) {
                        val index = menuItems.indexOf(selectedValue)
                        if (index >= 0 && index < actions.size) {
                            actions[index].invoke()
                        }
                    }
                    return PopupStep.FINAL_CHOICE
                }

                override fun isSelectable(value: String?): Boolean {
                    // All menu items are selectable since status is now in title
                    return true
                }
            }

        val popup = JBPopupFactory.getInstance().createListPopup(popupStep)
        popup.showAbove(event.component)
    }

    private fun updateWidget() {
        ApplicationManager.getApplication().invokeLater {
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(ID)
        }
    }

    private fun showLowCompletionsWarning(
        remaining: Int,
        budget: Int,
    ) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val notificationGroup =
                    NotificationGroupManager
                        .getInstance()
                        .getNotificationGroup("OxideCode Autocomplete")

                if (notificationGroup == null) {
                    println("OxideCode Autocomplete: Notification group not found!")
                    return@invokeLater
                }

                val notification =
                    notificationGroup.createNotification(
                        "OxideCode Autocomplete",
                        "You're running low on autocomplete suggestions ($remaining/$budget remaining). Upgrade to Pro for unlimited completions.",
                        NotificationType.WARNING,
                    )

                notification.addAction(
                    object : AnAction("Upgrade to Pro") {
                        override fun actionPerformed(e: AnActionEvent) {
                            BrowserUtil.browse("https://app.sweep.dev")
                            notification.expire()
                        }
                    },
                )

                notification.notify(project)
            } catch (e: Exception) {
                println("OxideCode Autocomplete: Error showing low completions warning: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun showOutOfCompletionsNotification() {
        ApplicationManager.getApplication().invokeLater {
            try {
                val notificationGroup =
                    NotificationGroupManager
                        .getInstance()
                        .getNotificationGroup("OxideCode Autocomplete")

                if (notificationGroup == null) {
                    println("OxideCode Autocomplete: Notification group not found!")
                    return@invokeLater
                }

                val notification =
                    notificationGroup.createNotification(
                        "OxideCode Autocomplete",
                        "You've run out of autocomplete suggestions. Upgrade to Pro for unlimited completions.",
                        NotificationType.ERROR,
                    )

                notification.addAction(
                    object : AnAction("Upgrade to Pro") {
                        override fun actionPerformed(e: AnActionEvent) {
                            BrowserUtil.browse("https://app.sweep.dev")
                            notification.expire()
                        }
                    },
                )

                notification.notify(project)
            } catch (e: Exception) {
                println("OxideCode Autocomplete: Error showing out of completions notification: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun checkAutocompleteHealth() {
        scope.launch {
            try {
                isAlive = performHealthCheck()
            } catch (e: Exception) {
                isAlive = false
                entitlementInfo = null
            }
        }
    }

    private suspend fun performHealthCheck(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                return@withContext SweepSettings.getInstance().directAutocompleteProvider.baseUrl.trim().isNotBlank() &&
                    SweepSettings.getInstance().directAutocompleteProvider.model.trim().isNotBlank()

            } catch (e: Exception) {
                entitlementInfo = null
                false
            }
        }
    }
}
