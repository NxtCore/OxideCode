package dev.sweep.assistant.theme

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import dev.sweep.assistant.theme.SweepIcons.brighter
import dev.sweep.assistant.theme.SweepIcons.darker
import dev.sweep.assistant.utils.isIDEDarkMode
import dev.sweep.assistant.views.RoundedButton
import java.awt.Container
import javax.swing.UIManager

class EditorThemeManager(
    private val editor: EditorEx,
) {
    fun applyDarkenedTheme() {
        if (editor.isDisposed) return
        // Create a new color scheme to avoid modifying the global one
        val colorsScheme = EditorColorsManager.getInstance().globalScheme
        val newScheme = (colorsScheme as EditorColorsSchemeImpl).clone() as EditorColorsSchemeImpl
        val currentBackground = colorsScheme.defaultBackground

        with(newScheme) {
            // Set background colors
            setColor(EditorColors.READONLY_BACKGROUND_COLOR, currentBackground)
            setColor(EditorColors.GUTTER_BACKGROUND, currentBackground)
            setColor(EditorColors.EDITOR_GUTTER_BACKGROUND, currentBackground)

            // Darken each syntax highlighting element individually
            setAttributes(
                HighlighterColors.TEXT,
                darkenAttributes(colorsScheme.getAttributes(HighlighterColors.TEXT)),
            )

            // Keywords and identifiers
            setAttributes(
                DefaultLanguageHighlighterColors.KEYWORD,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.KEYWORD)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.IDENTIFIER,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.IDENTIFIER)),
            )

            // Literals
            setAttributes(
                DefaultLanguageHighlighterColors.NUMBER,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.NUMBER)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.STRING,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.STRING)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE)),
            )

            // Comments
            setAttributes(
                DefaultLanguageHighlighterColors.LINE_COMMENT,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.BLOCK_COMMENT,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.BLOCK_COMMENT)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.DOC_COMMENT,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.DOC_COMMENT)),
            )

            // Operators and punctuation
            setAttributes(
                DefaultLanguageHighlighterColors.OPERATION_SIGN,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.OPERATION_SIGN)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.PARENTHESES,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.PARENTHESES)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.BRACKETS,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.BRACKETS)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.BRACES,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.BRACES)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.DOT,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.DOT)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.COMMA,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.COMMA)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.SEMICOLON,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.SEMICOLON)),
            )

            // Functions and variables
            setAttributes(
                DefaultLanguageHighlighterColors.FUNCTION_DECLARATION,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.FUNCTION_CALL,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.FUNCTION_CALL)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.LOCAL_VARIABLE,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.LOCAL_VARIABLE)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.GLOBAL_VARIABLE,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.GLOBAL_VARIABLE)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.PARAMETER,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.PARAMETER)),
            )

            // Classes and interfaces
            setAttributes(
                DefaultLanguageHighlighterColors.CLASS_NAME,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.CLASS_NAME)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.INTERFACE_NAME,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.INTERFACE_NAME)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.CLASS_REFERENCE,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.CLASS_REFERENCE)),
            )

            // Instance and static members
            setAttributes(
                DefaultLanguageHighlighterColors.INSTANCE_METHOD,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.INSTANCE_METHOD)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.INSTANCE_FIELD,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.INSTANCE_FIELD)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.STATIC_METHOD,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.STATIC_METHOD)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.STATIC_FIELD,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.STATIC_FIELD)),
            )

            // Metadata and markup
            setAttributes(
                DefaultLanguageHighlighterColors.METADATA,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.METADATA)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.MARKUP_TAG,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.MARKUP_TAG)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.MARKUP_ATTRIBUTE,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.MARKUP_ATTRIBUTE)),
            )
            setAttributes(
                DefaultLanguageHighlighterColors.MARKUP_ENTITY,
                darkenAttributes(colorsScheme.getAttributes(DefaultLanguageHighlighterColors.MARKUP_ENTITY)),
            )
        }

        editor.colorsScheme = newScheme
        removeErrorTheming()
    }

    fun revertTheme() {
        if (editor.isDisposed) return
        editor.colorsScheme = EditorColorsManager.getInstance().schemeForCurrentUITheme
        removeErrorTheming()
    }

    fun removeErrorTheming() {
        if (editor.isDisposed) return
        val scheme =
            editor.colorsScheme.clone() as? EditorColorsScheme
                ?: EditorColorsManager.getInstance().schemeForCurrentUITheme.clone() as EditorColorsScheme
        listOf(
            CodeInsightColors.ERRORS_ATTRIBUTES,
            CodeInsightColors.WARNINGS_ATTRIBUTES,
            CodeInsightColors.WEAK_WARNING_ATTRIBUTES,
            CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES,
            CodeInsightColors.DEPRECATED_ATTRIBUTES,
            CodeInsightColors.MARKED_FOR_REMOVAL_ATTRIBUTES,
            CodeInsightColors.GENERIC_SERVER_ERROR_OR_WARNING,
            CodeInsightColors.WRONG_REFERENCES_ATTRIBUTES,
            CodeInsightColors.UNMATCHED_BRACE_ATTRIBUTES,
        ).forEach { key ->
            val origAttrs = scheme.getAttributes(key) ?: return@forEach
            val newAttrs = origAttrs.clone()
            newAttrs.effectType = null
            newAttrs.effectColor = null
            newAttrs.foregroundColor = scheme.defaultForeground
            scheme.setAttributes(key, newAttrs)
        }

        editor.colorsScheme = scheme
    }

    fun darkenContainer(container: Container?) {
        if (container == null) return
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed) return@invokeLater
            for (child in container.components) {
                child.foreground = child.foreground.withAlpha(0.5f)
                if (child is RoundedButton) {
                    child.icon?.let { currentIcon ->
                        // Cache original icon if not already cached
                        val originalIcon =
                            child.getClientProperty("sweep.originalIcon") as? javax.swing.Icon
                                ?: currentIcon.also { child.putClientProperty("sweep.originalIcon", it) }

                        // Apply darkening/brightening to the original icon, not the current one
                        child.icon =
                            if (isIDEDarkMode()) {
                                originalIcon.darker(5)
                            } else {
                                originalIcon.brighter(5)
                            }
                    }
                }

                // Recursively handle nested containers
                if (child is Container) {
                    darkenContainer(child)
                }
            }
        }
    }

    fun revertContainer(container: Container?) {
        if (container == null) return
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed) return@invokeLater
            for (child in container.components) {
                child.foreground = UIManager.getColor("Panel.foreground")
                if (child is RoundedButton) {
                    // Restore the original icon instead of brightening the current one
                    val originalIcon = child.getClientProperty("sweep.originalIcon") as? javax.swing.Icon
                    if (originalIcon != null) {
                        child.icon = originalIcon
                    }
                }
                // Recursively revert nested containers
                if (child is Container) {
                    revertContainer(child)
                }
            }
        }
    }

    private fun darkenAttributes(original: TextAttributes?): TextAttributes =
        TextAttributes().apply {
            foregroundColor = original?.foregroundColor?.withAlpha(0.6f)
                ?: editor.colorsScheme.defaultForeground.withAlpha(0.6f)
            backgroundColor = editor.colorsScheme.defaultBackground
            original?.let {
                fontType = it.fontType
                effectType = it.effectType
                effectColor = it.effectColor?.withAlpha(0.6f)
            }
        }
}
