package com.oxidecode.autocomplete.edit

import com.intellij.openapi.actionSystem.IdeActions
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * Maps KeyStroke objects to their corresponding EditorAction IDs (IdeActions.ACTION_*).
 *
 * This is used to dynamically intercept editor actions based on user's keymap configuration.
 * When a user configures a keystroke for AcceptEditCompletionAction or RejectEditCompletionAction,
 * we need to intercept the corresponding low-level EditorAction to handle autocomplete.
 */
object KeystrokeToEditorActionMapper {
    /**
     * Maps a keystroke to its corresponding EditorAction ID.
     *
     * @param keyStroke The keystroke to map
     * @return The IdeActions.ACTION_* constant ID, or null if no direct mapping exists
     */
    fun mapToEditorAction(keyStroke: KeyStroke): String? {
        val keyCode = keyStroke.keyCode
        val modifiers = keyStroke.modifiers

        // Check if specific modifiers are present (using bitwise operations)
        val hasShift = (modifiers and KeyEvent.SHIFT_DOWN_MASK) != 0 || (modifiers and KeyEvent.SHIFT_MASK) != 0
        val hasCtrl = (modifiers and KeyEvent.CTRL_DOWN_MASK) != 0 || (modifiers and KeyEvent.CTRL_MASK) != 0
        val hasMeta = (modifiers and KeyEvent.META_DOWN_MASK) != 0 || (modifiers and KeyEvent.META_MASK) != 0
        val hasAlt = (modifiers and KeyEvent.ALT_DOWN_MASK) != 0 || (modifiers and KeyEvent.ALT_MASK) != 0

        // Check for ONLY specific modifiers (no other modifiers pressed)
        val hasOnlyShift = hasShift && !hasCtrl && !hasMeta && !hasAlt
        val noModifiers = modifiers == 0

        return when {
            // TAB key - main accept keybinding for inline completions
            keyCode == KeyEvent.VK_TAB && noModifiers -> IdeActions.ACTION_EDITOR_TAB

            // Shift+TAB - alternative accept keybinding (maps to EditorUnindentSelection)
            keyCode == KeyEvent.VK_TAB && hasOnlyShift -> IdeActions.ACTION_EDITOR_UNINDENT_SELECTION

            // ENTER key - alternative accept keybinding
            keyCode == KeyEvent.VK_ENTER && noModifiers -> IdeActions.ACTION_EDITOR_ENTER

            // ESCAPE key
            keyCode == KeyEvent.VK_ESCAPE && noModifiers -> IdeActions.ACTION_EDITOR_ESCAPE

            // Arrow keys (no modifiers)
            keyCode == KeyEvent.VK_RIGHT && noModifiers -> IdeActions.ACTION_EDITOR_MOVE_CARET_RIGHT
            keyCode == KeyEvent.VK_LEFT && noModifiers -> IdeActions.ACTION_EDITOR_MOVE_CARET_LEFT
            keyCode == KeyEvent.VK_UP && noModifiers -> IdeActions.ACTION_EDITOR_MOVE_CARET_UP
            keyCode == KeyEvent.VK_DOWN && noModifiers -> IdeActions.ACTION_EDITOR_MOVE_CARET_DOWN

            // Shift + Right Arrow - accept next word (like IntelliJ's InsertInlineCompletionWordAction)
            keyCode == KeyEvent.VK_RIGHT && hasOnlyShift -> IdeActions.ACTION_EDITOR_MOVE_CARET_RIGHT_WITH_SELECTION

            // Delete/Backspace
            keyCode == KeyEvent.VK_DELETE && noModifiers -> IdeActions.ACTION_EDITOR_DELETE
            keyCode == KeyEvent.VK_BACK_SPACE && noModifiers -> IdeActions.ACTION_EDITOR_BACKSPACE

            // Space
            keyCode == KeyEvent.VK_SPACE && noModifiers -> IdeActions.ACTION_EDITOR_ENTER

            // Alt/Option + Arrow combinations - accept next word (only Alt, no other modifiers)
            keyCode == KeyEvent.VK_RIGHT && hasAlt && !hasShift && !hasCtrl && !hasMeta -> IdeActions.ACTION_EDITOR_NEXT_WORD
            keyCode == KeyEvent.VK_LEFT && hasAlt && !hasShift && !hasCtrl && !hasMeta -> IdeActions.ACTION_EDITOR_PREVIOUS_WORD

            // Home/End - End key can be used to accept line (like IntelliJ's InsertInlineCompletionLineAction)
            keyCode == KeyEvent.VK_END && noModifiers -> IdeActions.ACTION_EDITOR_MOVE_LINE_END
            keyCode == KeyEvent.VK_HOME && noModifiers -> IdeActions.ACTION_EDITOR_MOVE_LINE_START

            // Cmd+Right (Mac) or Ctrl+Right - accept line (only Ctrl/Meta, no other modifiers)
            keyCode == KeyEvent.VK_RIGHT && (hasCtrl || hasMeta) && !hasShift && !hasAlt -> IdeActions.ACTION_EDITOR_MOVE_LINE_END

            // Note: Ctrl/Cmd+Space (CODE_COMPLETION) is not included because it's not an EditorAction
            // and cannot be wrapped by EditorActionManager

            else -> null // No direct mapping for this keystroke
        }
    }

    /**
     * Maps multiple keystrokes to their corresponding EditorAction IDs.
     * Filters out keystrokes that don't have a direct mapping.
     *
     * @param keyStrokes List of keystrokes to map
     * @return List of unique EditorAction IDs
     */
    fun mapToEditorActions(keyStrokes: List<KeyStroke>): List<String> =
        keyStrokes
            .mapNotNull { mapToEditorAction(it) }
            .distinct()
}
