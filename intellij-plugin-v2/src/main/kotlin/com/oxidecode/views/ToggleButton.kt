package com.oxidecode.views

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.oxidecode.theme.OxideCodeColors
import com.oxidecode.utils.withSweepFont
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JLabel
import javax.swing.SwingConstants

class ToggleButton(
    project: Project,
    private var firstMode: String,
    private var secondMode: String,
) : JLabel("", SwingConstants.CENTER),
    Hoverable {
    private var currentMode: String = secondMode // default to on
    private val listeners = mutableListOf<(String) -> Unit>()
    private val logger = Logger.getInstance(ToggleButton::class.java)
    override var isHovered = false
    override var hoverEnabled: Boolean = true
    var hoverBackgroundColor: Color = background.darker()
    var borderColor: Color? = OxideCodeColors.borderColor
    var activeBorderColor: Color? = OxideCodeColors.activeBorderColor
    private val cornerRadius = 8

    private val activeColor: JBColor
        get() = OxideCodeColors.sendButtonColorForeground

    init {
        isOpaque = false
        withSweepFont(project, scale = 1f)
        border = JBUI.Borders.empty(4, 6)
        foreground = activeColor
        updateDisplayText()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        setupHoverListener()

        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (isEnabled) {
                        toggle()
                    }
                }
            },
        )
    }

    fun reset() {
        setMode(firstMode)
    }

    fun toggle() {
        currentMode = if (currentMode == firstMode) secondMode else firstMode
        updateDisplayText()
        notifyListeners()
    }

    fun getMode(): String = currentMode

    fun setMode(mode: String) {
        if (mode != firstMode && mode != secondMode) {
            logger.warn("$mode is not a valid mode. Use $firstMode or $secondMode")
            return
        }
        if (currentMode == mode) return
        currentMode = mode
        updateDisplayText()
        notifyListeners()
    }

    fun addModeChangeListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it(currentMode) }
    }

    private fun updateDisplayText() {
        text = "search: " +
            if (currentMode == firstMode) {
                "off"
            } else {
                "on"
            }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val strokeWidth = 1.0f
        val roundRect =
            RoundRectangle2D.Float(
                strokeWidth / 2,
                strokeWidth / 2,
                width - strokeWidth - 1f,
                height - strokeWidth - 1f,
                cornerRadius.toFloat(),
                cornerRadius.toFloat(),
            )

        if (background != null) {
            g2.color = if (isHovered && hoverEnabled) hoverBackgroundColor else background
            g2.fill(roundRect)
        }

        borderColor?.let { color ->
            g2.color = if (hasFocus()) activeBorderColor ?: color else color
            g2.stroke = BasicStroke(strokeWidth)
            g2.draw(roundRect)
        }

        super.paintComponent(g)
    }

    override fun updateUI() {
        super.updateUI()
        if (isDisplayable) {
            updateDisplayText()
        }
    }
}
