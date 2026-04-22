package com.oxidecode.utils

import com.intellij.ide.actions.ShowSettingsUtilImpl
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ExecutionDataKeys
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.impl.ui.EditKeymapsDialog
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import javax.swing.KeyStroke

fun isTerminalContext(e: AnActionEvent): Boolean {
    val env = e.getData(ExecutionDataKeys.EXECUTION_ENVIRONMENT)
    return env != null
}

fun isTerminalEditor(e: EditorMouseEvent): Boolean =
    e.editor.virtualFile
        ?.fileType
        ?.name == null

fun isValidSelection(text: String?): Boolean {
    if (text.isNullOrBlank()) return false

    val trimmed = text.trim()
    // Check if it's a meaningful selection:
    // - Contains at least one word character
    return trimmed.any { it.isLetterOrDigit() }
}

fun getKeyStrokesForAction(actionId: String): List<KeyStroke> {
    val keymap = KeymapManager.getInstance().activeKeymap
    return keymap
        .getShortcuts(actionId)
        .asSequence()
        .filterIsInstance<KeyboardShortcut>()
        .flatMap { sequenceOf(it.firstKeyStroke, it.secondKeyStroke) }
        .filterNotNull()
        .toList()
}

fun parseKeyStrokesToPrint(k: KeyStroke?): String? {
    if (k == null) return null
    return k
        .toString()
        .replace("pressed ", "")
        .replace("meta", "⌘")
        .replace("control", "Ctrl")
        .replace("ctrl", "Ctrl")
        .replace("alt", if (SystemInfo.isMac) "⌥" else "Alt")
        .replace("shift", if (SystemInfo.isMac) "⇧" else "Shift")
        .replace("BACK_SPACE", "⌫")
        .replace("ENTER", "⏎")
        .replace(" ", if (SystemInfo.isMac) "" else "+")
}

fun getActionText(actionId: String): String {
    val action = ActionManager.getInstance().getAction(actionId)
    return action?.templateText ?: actionId
}

/**
 * Opens the keymap settings dialog for a specific action.
 * Attempts to open the EditKeymapsDialog twice (as it may fail on first attempt),
 * and falls back to the general keymap settings if both attempts fail.
 *
 * @param project The current project
 * @param actionId The ID of the action to configure
 */
fun showKeymapDialog(
    project: Project,
    actionId: String,
) {
    try {
        EditKeymapsDialog(project, actionId)
            .show()
    } catch (e: Throwable) {
        // this might fail on the first request so we do this
        try {
            EditKeymapsDialog(project, actionId)
                .show()
        } catch (e: Throwable) {
            ShowSettingsUtilImpl.showSettingsDialog(project, "preferences.keymap", null)
        }
    }
}
