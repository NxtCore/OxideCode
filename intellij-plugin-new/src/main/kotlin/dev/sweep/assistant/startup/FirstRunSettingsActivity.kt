package dev.sweep.assistant.startup

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.SweepConstants.ACTION_ID_TO_NAME
import dev.sweep.assistant.utils.SweepConstants.ALT_KEY
import dev.sweep.assistant.utils.SweepConstants.CONTROL_KEY
import dev.sweep.assistant.utils.SweepConstants.META_KEY
import dev.sweep.assistant.utils.SweepConstants.SHIFT_KEY
import dev.sweep.assistant.utils.SweepConstants.SWEEP_ACTION_IDS_TO_DEFAULT_SHORTCUTS
import dev.sweep.assistant.utils.showKeymapDialog
import java.util.concurrent.TimeUnit
import javax.swing.KeyStroke

class FirstRunSettingsActivity :
    ProjectActivity,
    Disposable {
    private data class ConflictInfo(
        val actionName: String,
        val formattedShortcut: String,
        val conflicts: List<String>,
    )

    /**
     * Get the user-friendly name for an action ID
     */
    private fun getActionName(actionId: String): String =
        ACTION_ID_TO_NAME[actionId] ?: actionId.substringAfterLast('.').replace("Action", "")

    /**
     * Format a keybinding string with proper symbols based on the OS
     * @param keyBinding The key binding string (e.g., "meta J", "ctrl shift J")
     * @return The formatted string with symbols (e.g., "⌘J", "Ctrl⇧J")
     */
    private fun formatKeyBinding(keyBinding: String): String {
        val parts = keyBinding.split(" ")
        val result = StringBuilder()

        for (part in parts) {
            when (part.lowercase()) {
                "meta" -> result.append(META_KEY)
                "ctrl" -> result.append(CONTROL_KEY)
                "shift" -> result.append(SHIFT_KEY)
                "alt" -> result.append(ALT_KEY)
                else -> result.append(part.uppercase())
            }
        }

        return result.toString()
    }

    /**
     * Get the OS-appropriate keybinding from a list of keybindings
     * @param keyBindings List of keybindings (e.g., ["meta J", "ctrl J"])
     * @return The appropriate keybinding for the current OS
     */
    private fun getOSAppropriateKeyBinding(keyBindings: List<String>): String =
        if (SystemInfo.isMac) {
            keyBindings.firstOrNull { it.contains("meta") } ?: keyBindings.first()
        } else {
            keyBindings.firstOrNull { it.contains("ctrl") } ?: keyBindings.first()
        }

    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            val keyMap = KeymapManager.getInstance().activeKeymap

            for ((actionId, keyBindings) in SWEEP_ACTION_IDS_TO_DEFAULT_SHORTCUTS) {
                var needsRebinding = false
                val existingShortcuts = keyMap.getShortcuts(actionId)
                // Requires rebinding if no shortcuts exist
                if (existingShortcuts.isEmpty()) {
                    needsRebinding = true
                }

                // Check if any other non-sweep actions share these shortcuts
                for (existingShortcut in existingShortcuts) {
                    val conflictingActions = keyMap.getActionIds(existingShortcut).filter { !it.startsWith("dev.sweep.assistant") }
                    if (conflictingActions.isNotEmpty()) {
                        needsRebinding = true
                        break
                    }
                }

                if (!needsRebinding) {
                    continue
                }

                var containsNonDefaultBinding = false

                // Check if the user has mapped this shortcut to a non default binding
                for (existingShortcut in existingShortcuts) {
                    if (existingShortcut !in keyBindings.map { KeyboardShortcut(KeyStroke.getKeyStroke(it), null) }) {
                        containsNonDefaultBinding = true
                        break
                    }
                }
                // If the user has manually remapped, do not overwrite their shortcuts
                if (containsNonDefaultBinding) {
                    continue
                }

                // Remove all conflicting shortcuts and track overridden key bindings
                var hasConflicts = false
                val osKeyBinding = getOSAppropriateKeyBinding(keyBindings)
                val keyStroke = KeyStroke.getKeyStroke(osKeyBinding)

                if (keyStroke != null) {
                    val conflictingActionIds = keyMap.getActionIds(keyStroke)
                    for (conflictingActionId in conflictingActionIds) {
                        if (!conflictingActionId.startsWith("dev.sweep.assistant")) {
                            val shortcuts = keyMap.getShortcuts(conflictingActionId)
                            for (shortcut in shortcuts) {
                                if (shortcut is KeyboardShortcut && shortcut.firstKeyStroke == keyStroke) {
                                    hasConflicts = true
                                    keyMap.removeShortcut(conflictingActionId, shortcut)
                                }
                            }
                        }
                    }
                }

                // Add the OS-appropriate shortcut
                if (keyStroke != null) {
                    keyMap.addShortcut(actionId, KeyboardShortcut(keyStroke, null))
                }
            }

            // Check for potential conflicts with Sweep actions
            checkActionConflicts(project, keyMap)
        }
    }

    /**
     * Check if Sweep actions have potential conflicts and notify the user
     */
    private fun checkActionConflicts(
        project: Project,
        keyMap: com.intellij.openapi.keymap.Keymap,
    ) {
        data class ActionToCheck(
            val actionId: String,
            val actionName: String,
            val expectedShortcuts: List<String>,
        )

        val actionsToCheck =
            listOf(
                ActionToCheck(
                    "dev.sweep.assistant.actions.NewChatAction",
                    "Open New Sweep Agent Chat",
                    listOf("meta J", "ctrl J"),
                ),
                ActionToCheck(
                    "dev.sweep.assistant.controllers.RightClickAction",
                    "Add Selection to Sweep",
                    listOf("meta shift J", "ctrl shift J"),
                ),
            )

        val allConflicts = mutableListOf<ConflictInfo>()

        for (actionToCheck in actionsToCheck) {
            val osKeyBinding = getOSAppropriateKeyBinding(actionToCheck.expectedShortcuts)
            val keyStroke = KeyStroke.getKeyStroke(osKeyBinding)

            // Check if the action already has a keyboard shortcut assigned that's NOT the default
            // The user has intentionally remapped the shortcut, so it's fine
            val existingShortcuts = keyMap.getShortcuts(actionToCheck.actionId)
            val hasNonDefaultShortcut =
                existingShortcuts.any { shortcut ->
                    if (shortcut is KeyboardShortcut) {
                        shortcut.firstKeyStroke != keyStroke
                    } else {
                        false
                    }
                }

            // Only check for conflicts if the action doesn't have a non-default keyboard shortcut
            if (hasNonDefaultShortcut) {
                continue
            }

            if (keyStroke != null) {
                val conflictingActionIds = keyMap.getActionIds(keyStroke)
                val conflicts = conflictingActionIds.filter { !it.startsWith("dev.sweep.assistant") }

                if (conflicts.isNotEmpty()) {
                    allConflicts.add(
                        ConflictInfo(
                            actionToCheck.actionName,
                            formatKeyBinding(osKeyBinding),
                            conflicts,
                        ),
                    )
                }
            }
        }

        if (allConflicts.isNotEmpty() && SweepSettings.getInstance().hasBeenSet) {
            val message = buildConflictMessage(allConflicts)
            val firstConflictActionId = allConflicts.first().conflicts.first()
            val title = if (allConflicts.size == 1) "Remap Conflicting Shortcut" else "Remap Conflicting Shortcuts"
            val actionLabel = if (allConflicts.size == 1) "Remap Conflicting Shortcut" else "Remap Conflicting Shortcuts"

            // Schedule notification to show 5 seconds later
            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                {
                    if (!project.isDisposed && !SweepMetaData.getInstance().dontShowCmdJConflictNotifications) {
                        ApplicationManager.getApplication().invokeLater {
                            val group = NotificationGroupManager.getInstance().getNotificationGroup("Sweep AI Notifications")

                            if (group != null) {
                                val notification = group.createNotification(title, message, NotificationType.INFORMATION)

                                notification.addAction(
                                    NotificationAction.createSimple(actionLabel) {
                                        showKeymapDialog(project, firstConflictActionId)
                                    },
                                )

                                notification.addAction(
                                    NotificationAction.createSimple("Don't Show Again") {
                                        SweepMetaData.getInstance().dontShowCmdJConflictNotifications = true
                                        notification.expire()
                                    },
                                )

                                notification.notify(project)
                            }
                        }
                    }
                },
                5,
                TimeUnit.SECONDS,
            )
        }
    }

    private val CONFLICT_INSTRUCTIONS = "Please remap this shortcut to another key combination."

    private val CONFLICT_INSTRUCTIONS_PLURAL = "Please remap these shortcuts to other key combinations."

    /**
     * Build a notification message for shortcut conflicts
     */
    private fun buildConflictMessage(conflicts: List<ConflictInfo>): String =
        when (conflicts.size) {
            1 -> {
                val conflict = conflicts.first()
                val conflictingActionNames =
                    conflict.conflicts
                        .take(3)
                        .mapNotNull { actionId ->
                            ACTION_ID_TO_NAME[actionId] ?: actionId.substringAfterLast('.').replace("Action", "")
                        }.joinToString(", ")

                if (conflict.conflicts.size > 3) {
                    "The shortcut ${conflict.formattedShortcut} for '${conflict.actionName}' conflicts with: $conflictingActionNames and ${conflict.conflicts.size - 3} more action(s). $CONFLICT_INSTRUCTIONS"
                } else {
                    "The shortcut ${conflict.formattedShortcut} for '${conflict.actionName}' conflicts with: $conflictingActionNames. $CONFLICT_INSTRUCTIONS"
                }
            }
            else -> {
                val conflictMessages =
                    conflicts.joinToString(" ") { conflict ->
                        val conflictingActionNames =
                            conflict.conflicts
                                .take(2)
                                .mapNotNull { actionId ->
                                    ACTION_ID_TO_NAME[actionId] ?: actionId.substringAfterLast('.').replace("Action", "")
                                }.joinToString(", ")

                        if (conflict.conflicts.size > 2) {
                            "${conflict.formattedShortcut} for '${conflict.actionName}' conflicts with $conflictingActionNames and ${conflict.conflicts.size - 2} more. $CONFLICT_INSTRUCTIONS_PLURAL"
                        } else {
                            "${conflict.formattedShortcut} for '${conflict.actionName}' conflicts with $conflictingActionNames. $CONFLICT_INSTRUCTIONS_PLURAL"
                        }
                    }
                "Multiple Sweep shortcuts have conflicts: $conflictMessages "
            }
        }

    override fun dispose() {
        // Cleanup will be handled automatically when the message bus connection is disposed
    }
}
