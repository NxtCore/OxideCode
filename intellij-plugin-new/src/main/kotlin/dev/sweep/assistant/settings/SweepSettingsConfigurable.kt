package dev.sweep.assistant.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.colorizeIcon
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.event.WindowEvent
import javax.swing.*

class SweepSettingsConfigurable(
    private val project: Project,
) : Configurable {
    private var githubTokenField: JPasswordField? = null
    private var baseUrlField: JTextField? = null
    private var anthropicApiKeyField: JPasswordField? = null
    private val settings = SweepSettings.getInstance()
    private var betaFlagField: JCheckBox? = null

    override fun createComponent(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(20)

        val centerPanel = JPanel()
        centerPanel.layout = BoxLayout(centerPanel, BoxLayout.Y_AXIS)
        centerPanel.border = JBUI.Borders.empty(20)

        val messageLabel =
            JLabel("You can configure Sweep below. The settings are also located in the Sweep Sidebar (${SweepConstants.META_KEY}J). ")
        messageLabel.font = messageLabel.font.deriveFont(Font.BOLD, 16f)
        messageLabel.alignmentX = Component.CENTER_ALIGNMENT
        centerPanel.add(messageLabel)

        centerPanel.add(Box.createRigidArea(Dimension(0, 10)))

        val openConfigButton =
            JButton("Configure Settings").apply {
                icon = colorizeIcon(AllIcons.General.Settings, JBColor(0x5C8CF9, 0x7AA2F7))
                alignmentX = Component.CENTER_ALIGNMENT
                font = font.deriveFont(font.size * 1.2f)
                preferredSize =
                    Dimension(
                        (preferredSize.width * 2).coerceAtLeast(200),
                        (preferredSize.height * 2).coerceAtLeast(60),
                    )
            }
        openConfigButton.addActionListener {
            SwingUtilities.getWindowAncestor(openConfigButton)?.dispatchEvent(
                WindowEvent(SwingUtilities.getWindowAncestor(openConfigButton), WindowEvent.WINDOW_CLOSING),
            )
            // open tool window and show config popup
            ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)?.show()
            SweepConfig.getInstance(project).showConfigPopup()
        }
        centerPanel.add(openConfigButton)

        panel.add(centerPanel, BorderLayout.CENTER)
        return panel
    }

    override fun isModified(): Boolean = false

    override fun apply() {
        return // no op
    }

    override fun getDisplayName(): String = SweepConstants.PLUGIN_NAME

    override fun reset() {
        githubTokenField?.text = settings.githubToken
        baseUrlField?.text = settings.baseUrl
        betaFlagField?.isSelected = settings.betaFlagOn
    }
}
