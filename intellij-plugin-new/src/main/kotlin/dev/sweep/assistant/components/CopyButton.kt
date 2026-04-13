package dev.sweep.assistant.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import dev.sweep.assistant.theme.SweepIcons.brighter
import dev.sweep.assistant.theme.SweepIcons.darker
import dev.sweep.assistant.utils.isIDEDarkMode
import dev.sweep.assistant.views.Darkenable
import java.awt.Cursor
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Timer

/**
 * A copy button component that shows a checkmark on successful copy
 * and brightens on hover.
 */
class CopyButton(
    private val tooltipText: String = "Copy",
    private val textToCopy: () -> String,
    parentDisposable: Disposable,
) : JBLabel(),
    Disposable,
    Darkenable {
    private val normalIcon = AllIcons.Actions.Copy
    private val checkmarkIcon = AllIcons.Actions.Checked

    private var isShowingSuccess = false
    private var resetTimer: Timer? = null

    private val mouseAdapter =
        object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                if (!isShowingSuccess) {
                    icon = normalIcon
                }
            }

            override fun mouseExited(e: MouseEvent?) {
                if (!isShowingSuccess) {
                    icon = normalIcon
                }
            }

            override fun mouseClicked(e: MouseEvent?) {
                handleClick()
            }
        }

    init {
        Disposer.register(parentDisposable, this)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = this.tooltipText
        icon = normalIcon
        addMouseListener(mouseAdapter)
    }

    private fun handleClick() {
        try {
            CopyPasteManager.getInstance().setContents(StringSelection(textToCopy()))
            if (!isShowingSuccess) {
                showSuccessState()
            }
        } catch (e: Exception) {
            // Silently fail if copy operation fails
        }
    }

    private fun showSuccessState() {
        isShowingSuccess = true

        // Cancel any existing timer
        resetTimer?.stop()

        // Change to checkmark icon
        icon = checkmarkIcon
        toolTipText = "Copied!"

        // Reset after 2 seconds
        resetTimer =
            Timer(2000) {
                isShowingSuccess = false
                icon = normalIcon
                toolTipText = tooltipText
                resetTimer = null
            }.apply {
                isRepeats = false
                start()
            }
    }

    override fun applyDarkening() {
        if (isIDEDarkMode()) {
            icon = icon?.darker()
        } else {
            icon = icon?.brighter()
        }
    }

    override fun revertDarkening() {
        icon = normalIcon
    }

    override fun dispose() {
        resetTimer?.stop()
        resetTimer = null
        removeMouseListener(mouseAdapter)
        icon = null // Clear icon reference to allow plugin class loader to be garbage collected
    }
}
