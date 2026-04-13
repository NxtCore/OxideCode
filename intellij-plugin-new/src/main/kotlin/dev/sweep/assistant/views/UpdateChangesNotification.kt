package dev.sweep.assistant.views

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.services.SweepColorChangeService
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.settings.SweepSettingsParser
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.utils.getCurrentSweepPluginVersion
import dev.sweep.assistant.utils.withSweepFont
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.event.HyperlinkListener

class UpdateChangesNotification(
    private val project: Project,
    private val parentDisposable: Disposable? = null,
) : JBPanel<UpdateChangesNotification>(),
    Disposable {
    private val version: String = getCurrentSweepPluginVersion() ?: "unknown"
    private val title: String = "🎉 What's new in Sweep $version"

    private val cloudContent: String = """
    <ul>
      <li><b>Bug Fixes and Improvements</b>
        <ul>
            <li>Streamed file modifications now show additions and removals as they happen.</li>
        </ul>
      </li>
    </ul>
    <p>Check out our <a href="https://docs.sweep.dev">documentation</a> or <a href="https://discord.com/invite/sweep">Discord</a>!</p>
    """

    private val gatewayContent: String = """
    <ul>
      <li><b>Bug Fixes and Improvements</b>
        <ul>
            <li>Streamed file modifications now show additions and removals as they happen.</li>
        </ul>
      </li>
    </ul>
    <p>Check out our <a href="https://docs.sweep.dev">documentation</a> or <a href="https://discord.com/invite/sweep">Discord</a>!</p>
    """

    private val nonCloudContent: String = """
    <ul>
      <li><b>Bug Fixes and Improvements</b>
        <ul>
            <li>Streamed file modifications now show additions and removals as they happen.</li>
        </ul>
      </li>
    </ul>
    <p>Check out our <a href="https://docs.sweep.dev">documentation</a> or <a href="https://discord.com/invite/sweep">Discord</a>!</p>
    """

    private val content: String
        get() =
            when {
                SweepSettingsParser.isCloudEnvironment() -> cloudContent
                SweepSettingsParser.isGatewayMode() -> gatewayContent
                else -> nonCloudContent
            }

    private val titleLabel: JEditorPane
    private val closeButton: JButton
    private val contentPane: JEditorPane
    private val preferredNotificationSize = Dimension(400, 275)
    private val contentHyperlinkListener: HyperlinkListener =
        HyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                BrowserUtil.browse(e.url)
            }
        }

    init {
        if (parentDisposable != null) {
            Disposer.register(parentDisposable, this)
        }

        layout = BoxLayout(this, BoxLayout.X_AXIS)
        background = null
        border = JBUI.Borders.customLine(SweepColors.activeBorderColor, 1)

        // Handle theme changes
        SweepColorChangeService.getInstance(project).addThemeChangeListener(this) {
            border = JBUI.Borders.customLine(SweepColors.activeBorderColor, 1)
            titleLabel.foreground = SweepColors.foregroundColor
            contentPane.text = formatContent(content)
            repaint()
        }

        // Title label with proper text wrapping support
        titleLabel =
            JEditorPane().apply {
                contentType = "text/html"
                val baseFontPt =
                    JBUI.Fonts
                        .label()
                        .size
                        .toFloat()
                text =
                    """
                    <html>
                      <body style="font-family: ${JBUI.Fonts.label().family}; 
                                   font-size: ${baseFontPt * 1.2}pt; 
                                   font-weight: bold;
                                   margin:0; padding:0;">
                        $title
                      </body>
                    </html>
                    """.trimIndent()
                isEditable = false
                isOpaque = false
                isFocusable = false
                border = JBUI.Borders.empty(10, 15, 5, 15)

                foreground = SweepColors.foregroundColor
            }

        // Content pane for HTML content
        contentPane =
            JEditorPane().apply {
                contentType = "text/html"
                text = formatContent(content)
                isEditable = false
                isOpaque = false
                border = JBUI.Borders.empty(0, 15, 10, 15)
                withSweepFont(project, scale = 1.0f)
                addHyperlinkListener(contentHyperlinkListener)
            }

        // Left panel containing title and content (scrollable together)
        val leftPanel = JPanel()
        leftPanel.layout = BoxLayout(leftPanel, BoxLayout.Y_AXIS)
        leftPanel.isOpaque = false
        leftPanel.add(titleLabel)
        leftPanel.add(contentPane)

        val scrollPane =
            JBScrollPane(leftPanel).apply {
                border = JBUI.Borders.empty(10, 0, 0, 0) // Add top padding
                isOpaque = false
                viewport.isOpaque = false
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER

                // Ensure scroll position starts at the top
                ApplicationManager.getApplication().invokeLater {
                    viewport.viewPosition = java.awt.Point(0, 0)
                }
            }

        // Close button
        closeButton =
            JButton(SweepIcons.Close).apply {
                isBorderPainted = false
                isContentAreaFilled = false
                isFocusPainted = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Close notification"
                // Ensure the button has a fixed size and doesn't shrink
                val buttonSize = Dimension(24, 24)
                preferredSize = buttonSize
                minimumSize = buttonSize
                maximumSize = buttonSize
                addActionListener {
                    // Mark this version as seen
                    SweepMetaData.getInstance().markUpdateAsShown(version)
                    this@UpdateChangesNotification.isVisible = false
                }
            }

        // Create right panel to position close button at top right
        val rightPanel = JPanel()
        rightPanel.layout = BoxLayout(rightPanel, BoxLayout.Y_AXIS)
        rightPanel.isOpaque = false
        rightPanel.border = JBUI.Borders.empty(10, 0, 0, 0) // Add top padding to align with scroll pane
        rightPanel.add(closeButton)
        rightPanel.add(Box.createVerticalGlue()) // Push button to top

        // Add components with proper spacing - left scrollable content and right close button
        add(scrollPane)
        add(Box.createHorizontalStrut(8)) // Add some spacing
        add(rightPanel)
        add(Box.createHorizontalStrut(15)) // Right padding

        // Set preferred size
        preferredSize = preferredNotificationSize
    }

    private fun formatContent(content: String): String {
        val textColor = SweepColors.foregroundColorHex // Use theme-aware color instead of hardcoded "#DDDDDD"
        val fontSize =
            try {
                SweepConfig.getInstance(project).state.fontSize
            } catch (e: Exception) {
                JBUI.Fonts
                    .label()
                    .size
                    .toFloat()
            }

        return """
            <html>
            <body style="color: $textColor; font-size: ${fontSize}pt; font-family: arial;">
            $content
            </body>
            </html>
            """.trimIndent()
    }

    fun createUpdateNotificationPanel(): JPanel? {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(0, 12, 8, 12)

        // Only show if this version hasn't been shown before, version is known, title/content aren't empty,
        // and tutorial is not currently open
        val developerHasUsedSweepBefore = (
            SweepMetaData.getInstance().chatsSent > 0 ||
                SweepMetaData.getInstance().autocompleteAcceptCount > 0
        )
        if (version != "unknown" &&
            !SweepMetaData.getInstance().hasShownUpdateForVersion(version) &&
            title.isNotBlank() &&
            content.isNotBlank() &&
            developerHasUsedSweepBefore
        ) {
            panel.add(this, BorderLayout.CENTER)
            return panel
        }

        return null
    }

    companion object {
        /**
         * Creates an update notification panel if it hasn't been shown for this version yet.
         * @return A panel containing the notification if it hasn't been shown before, otherwise an empty panel
         */
        fun createNotificationPanel(
            project: Project,
            parentDisposable: Disposable? = null,
        ): JPanel? {
            val notification =
                UpdateChangesNotification(
                    project = project,
                    parentDisposable = parentDisposable,
                )

            return notification.createUpdateNotificationPanel()
        }
    }

    override fun dispose() {
        // Ensure hyperlink listener is removed to prevent leaks
        try {
            contentPane.removeHyperlinkListener(contentHyperlinkListener)
        } catch (_: Exception) {
        }
    }
}
