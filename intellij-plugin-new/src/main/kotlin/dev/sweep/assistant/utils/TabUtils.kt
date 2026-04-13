package dev.sweep.assistant.utils

import com.intellij.openapi.editor.Editor
import java.awt.FontMetrics
import java.awt.Graphics

/**
 * Extension functions for Graphics to properly handle tab characters in text rendering
 */

/**
 * Calculate text width properly handling tabs
 */
fun Graphics.getStringWidthWithTabs(
    text: String,
    editor: Editor,
): Int {
    if (!text.contains('\t')) {
        return fontMetrics.stringWidth(text)
    }

    val tabSize = editor.settings.getTabSize(editor.project)
    val spaceWidth = fontMetrics.charWidth(' ')
    var width = 0
    var column = 0

    for (char in text) {
        when (char) {
            '\t' -> {
                val spacesToNextTabStop = tabSize - (column % tabSize)
                width += spaceWidth * spacesToNextTabStop
                column += spacesToNextTabStop
            }
            '\n' -> {
                column = 0
            }
            else -> {
                width += fontMetrics.charWidth(char)
                column++
            }
        }
    }

    return width
}

/**
 * Draw text properly handling tabs
 * @return the total width of the drawn text
 */
fun Graphics.drawStringWithTabs(
    text: String,
    x: Int,
    y: Int,
    editor: Editor,
): Int {
    if (!text.contains('\t')) {
        drawString(text, x, y)
        return fontMetrics.stringWidth(text)
    }

    val tabSize = editor.settings.getTabSize(editor.project)
    val spaceWidth = fontMetrics.charWidth(' ')
    var currentX = x
    var column = 0
    val sb = StringBuilder()

    for (char in text) {
        when (char) {
            '\t' -> {
                // Draw accumulated text first
                if (sb.isNotEmpty()) {
                    drawString(sb.toString(), currentX, y)
                    currentX += fontMetrics.stringWidth(sb.toString())
                    sb.clear()
                }
                // Add spaces for tab
                val spacesToNextTabStop = tabSize - (column % tabSize)
                currentX += spaceWidth * spacesToNextTabStop
                column += spacesToNextTabStop
            }
            '\n' -> {
                column = 0
                sb.append(char)
            }
            else -> {
                sb.append(char)
                column++
            }
        }
    }

    // Draw remaining text
    if (sb.isNotEmpty()) {
        drawString(sb.toString(), currentX, y)
        currentX += fontMetrics.stringWidth(sb.toString())
    }

    return currentX - x
}

/**
 * Calculate text width properly handling tabs using provided FontMetrics
 * (for cases where Graphics context is not available)
 */
fun FontMetrics.getStringWidthWithTabs(
    text: String,
    editor: Editor,
): Int {
    if (!text.contains('\t')) {
        return stringWidth(text)
    }

    val tabSize = editor.settings.getTabSize(editor.project)
    val spaceWidth = charWidth(' ')
    var width = 0
    var column = 0

    for (char in text) {
        when (char) {
            '\t' -> {
                val spacesToNextTabStop = tabSize - (column % tabSize)
                width += spaceWidth * spacesToNextTabStop
                column += spacesToNextTabStop
            }
            '\n' -> {
                column = 0
            }
            else -> {
                width += charWidth(char)
                column++
            }
        }
    }

    return width
}
