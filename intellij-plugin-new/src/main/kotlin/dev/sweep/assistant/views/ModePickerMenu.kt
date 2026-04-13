package dev.sweep.assistant.views

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.components.SweepComponent
import dev.sweep.assistant.services.SweepColorChangeService
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.colorizeIcon
import dev.sweep.assistant.utils.withSweepFont
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

class ModePickerMenu(
    private val project: Project,
    parentDisposable: Disposable,
) : JPanel(),
    Disposable {
    private val comboBox = RoundedComboBox<String>()
    private var availableOptions = listOf<String>()
    private val listeners = mutableListOf<(String) -> Unit>()
    private val mouseAdapter: MouseAdapter

    init {
        isOpaque = false
        layout = BorderLayout()
        border = JBUI.Borders.empty()
        comboBox.withSweepFont(project, scale = 1f)
        val currentMode = SweepComponent.getMode(project)
        setAvailableOptions(SweepConstants.CHAT_MODES)
        comboBox.selectedItem = currentMode
        updateColorsForMode(currentMode)
        updateSecondaryText()
        comboBox.toolTipText = "${SweepConstants.META_KEY}. to toggle between modes"
        comboBox.hoverEnabled = true

        // Format tooltips with HTML for better readability
        val formattedTooltips =
            SweepConstants.CHAT_MODES_HINTS.mapValues { (_, hint) ->
                "<html><div style='width: 250px;'>${hint.replace(". ", ".<br>")}</div></html>"
            }
        comboBox.setItemTooltips(formattedTooltips)

        // Set icons with theme-aware colors
        updateIconsForTheme()

        comboBox.addActionListener {
            val selectedMode = comboBox.selectedItem as String
            SweepComponent.setMode(project, selectedMode)
            updateColorsForMode(selectedMode)
            notifyListeners(selectedMode)
        }

        add(comboBox, BorderLayout.CENTER)

        // Listen for mode changes from other components
        project.messageBus.connect(this).subscribe(
            SweepComponent.MODE_STATE_TOPIC,
            object : SweepComponent.ModeStateListener {
                override fun onModeChanged(mode: String) {
                    if (mode.isNotEmpty() && comboBox.selectedItem != mode) {
                        comboBox.selectedItem = mode
                        updateColorsForMode(mode)
                    }
                }
            },
        )

        SweepColorChangeService.getInstance(project).addThemeChangeListener(this) {
            border = JBUI.Borders.empty()
            comboBox.updateThemeColors()
            updateIconsForTheme()
            // Restore mode-specific colors after theme change
            val currentMode = SweepComponent.getMode(project)
            updateColorsForMode(currentMode)
        }
        // propagate hover effects
        mouseAdapter =
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    comboBox.applyHoverEffect()
                }

                override fun mouseExited(e: MouseEvent) {
                    comboBox.removeHoverEffect()
                }
            }
        addMouseListener(mouseAdapter)

        Disposer.register(parentDisposable, this)
    }

    fun setAvailableOptions(options: Map<String, String>) {
        availableOptions = options.keys.toList()
        updateComboBoxModel()
    }

    private fun updateComboBoxModel() {
        val currentSelection = comboBox.selectedItem as? String
        comboBox.setOptions(availableOptions)
        if (currentSelection != null && availableOptions.contains(currentSelection)) {
            comboBox.selectedItem = currentSelection
        } else {
            comboBox.selectedItem = SweepConstants.DEFAULT_CHAT_MODE.keys.first()
        }
    }

    fun addModeChangeListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    private fun notifyListeners(mode: String) {
        listeners.forEach { it(mode) }
    }

    override fun dispose() {
        removeMouseListener(mouseAdapter)
        listeners.clear()
    }

    fun updateSecondaryText() {
        val metaData = SweepMetaData.getInstance()
        // comboBox.secondaryText = if (metaData.chatModeToggleUsed) "" else "${SweepConstants.META_KEY}."
        comboBox.secondaryText = "${SweepConstants.META_KEY}."
    }

    private fun updateIconsForTheme() {
        // Normalize icon sizes - scale both to 16x16 for now
        val targetSize = 16
        val chatBubbleIcon =
            com.intellij.util.IconUtil.scale(
                SweepIcons.ChatBubbleIcon,
                null,
                targetSize.toFloat() / SweepIcons.ChatBubbleIcon.iconWidth,
            )

        val pencilIcon =
            com.intellij.util.IconUtil
                .scale(SweepIcons.EditIcon, null, targetSize.toFloat() / SweepIcons.EditIcon.iconWidth)

        val modeIcons =
            mapOf(
                "Agent" to pencilIcon,
                "Ask" to chatBubbleIcon,
            )
        // temp fix to make icon looks more centered
        comboBox.setItemIconTopSpace("Ask", 1)
        comboBox.setItemIconTopSpace("Agent", 1)
        comboBox.setItemIcons(modeIcons)
    }

    /**
     * Updates the visual colors based on the currently selected mode
     */
    private fun updateColorsForMode(mode: String) {
        val backgroundColor = SweepColors.getModeBackgroundColor(mode)
        val hoverColor = SweepColors.getModeHoverColor(mode)
        val textColor = SweepColors.getModeTextColor(mode)
        comboBox.updateColors(backgroundColor, hoverColor, textColor)

        // Set colorized icon for main display only (Ask mode gets blue icon)
        val targetSize = 16
        val chatBubbleIconScaled =
            com.intellij.util.IconUtil.scale(
                SweepIcons.ChatBubbleIcon,
                null,
                targetSize.toFloat() / SweepIcons.ChatBubbleIcon.iconWidth,
            )

        val pencilIconScaled =
            com.intellij.util.IconUtil.scale(
                SweepIcons.EditIcon,
                null,
                targetSize.toFloat() / SweepIcons.EditIcon.iconWidth,
            )

        val colorizedIcon =
            when (mode.lowercase()) {
                "ask" -> colorizeIcon(chatBubbleIconScaled, SweepColors.askModeTextColor)
                else -> pencilIconScaled
            }

        comboBox.setSelectedItemIconOverride(colorizedIcon)
    }

    /**
     * Sets a custom border override for the combo box.
     * This allows for complete control over the border styling.
     * @param border The border to apply to the combo box, or null to use default
     */
    fun setBorderOverride(border: javax.swing.border.Border?) {
        comboBox.setBorderOverride(border)
    }

    /**
     * Sets a custom border for the entire mode picker panel.
     * This allows for additional padding or custom border styles.
     * @param border The border to apply to the panel
     */
    override fun setBorder(border: javax.swing.border.Border?) {
        super.setBorder(border)
    }
}
