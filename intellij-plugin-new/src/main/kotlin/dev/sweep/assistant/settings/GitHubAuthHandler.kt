package dev.sweep.assistant.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.services.SweepAuthServer
import dev.sweep.assistant.tracking.EventType
import dev.sweep.assistant.tracking.TelemetryService
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.scaled
import dev.sweep.assistant.utils.withSweepFont
import java.awt.Color
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField

class GitHubAuthHandler {
    companion object {
        private const val AUTH_URL = "https://accounts.sweep.dev/sign-up"
        private var currentDialog: DialogWrapper? = null

        fun initiateAuth(project: Project) {
            // Start the auth server for automatic token reception
            TelemetryService.getInstance().sendUsageEvent(
                eventType = EventType.AUTH_FLOW_STARTED,
            )
            SweepAuthServer.start(project)
            BrowserUtil.browse(AUTH_URL, project)
            showTokenInputDialog(project)
        }

        fun closeDialog() {
            currentDialog?.close(DialogWrapper.OK_EXIT_CODE)
            currentDialog = null
        }

        private fun showTokenInputDialog(project: Project) {
            val dialog =
                object : DialogWrapper(project, true) {
                    private val tokenField =
                        JPasswordField().apply {
                            withSweepFont(project)
                            preferredSize = Dimension(400, preferredSize.height)
                        }

                    init {
                        title = "Log in to Sweep"
                        init()

                        // Listen for settings changes to update token field when received via auth server
                        project.messageBus.connect(disposable).subscribe(
                            SweepSettings.SettingsChangedNotifier.TOPIC,
                            SweepSettings.SettingsChangedNotifier {
                                val currentToken = SweepSettings.getInstance().githubToken
                                if (currentToken.isNotBlank() && String(tokenField.password).trim() != currentToken) {
                                    ApplicationManager.getApplication().invokeLater {
                                        tokenField.text = currentToken
                                        // Auto-close dialog since token was received
                                        close(OK_EXIT_CODE)
                                        // Reopen the tool window after successful authentication
                                        ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)?.show()
                                    }
                                }
                            },
                        )
                    }

                    override fun createCenterPanel(): JComponent {
                        val panel =
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                border = JBUI.Borders.empty(40.scaled, 50.scaled, 40.scaled, 50.scaled)
                                preferredSize = Dimension(450.scaled, preferredSize.height)
                            }

                        panel.add(
                            JBLabel("Sweep AI").apply {
                                withSweepFont(project, scale = 2.2f, bold = true)
                                alignmentX = JComponent.CENTER_ALIGNMENT
                                border = JBUI.Borders.empty(0, 0, 8.scaled, 0)
                            },
                        )

                        // Subtitle "Authentication Required"
                        panel.add(
                            JBLabel("Authentication Required").apply {
                                withSweepFont(project, scale = 1.1f)
                                alignmentX = JComponent.CENTER_ALIGNMENT
                                border = JBUI.Borders.empty(0, 0, 32.scaled, 0)
                            },
                        )

                        // Dark background panel for login section
                        panel.add(
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                alignmentX = JComponent.CENTER_ALIGNMENT
                                maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
                                background = JBUI.CurrentTheme.CustomFrameDecorations.paneBackground()
                                border =
                                    JBUI.Borders.compound(
                                        RoundedLineBorder(JBColor(Color(100, 100, 100), Color(180, 180, 180)), 8, 1),
                                        JBUI.Borders.empty(20.scaled, 24.scaled),
                                    )

                                // "Log in at" with hyperlink
                                add(
                                    createInlineHyperlinkRow(
                                        project = project,
                                        leading = "Log in at ",
                                        linkText = AUTH_URL,
                                        url = AUTH_URL,
                                        trailing = null,
                                        scale = 1f,
                                        bold = false,
                                    ).apply {
                                        alignmentX = JComponent.LEFT_ALIGNMENT
                                        border = JBUI.Borders.empty(0, 0, 12.scaled, 0)
                                    },
                                )

                                // Instructions text
                                add(
                                    JBLabel(
                                        "<html>We'll try to automatically sign you in.<br>If something goes wrong, please paste the token below:</html>",
                                    ).apply {
                                        withSweepFont(project, scale = 0.95f)
                                        alignmentX = JComponent.LEFT_ALIGNMENT
                                    },
                                )
                            },
                        )

                        panel.add(Box.createRigidArea(Dimension(0, 24.scaled)))

                        // Token input field
                        panel.add(
                            tokenField.apply {
                                alignmentX = JComponent.CENTER_ALIGNMENT
                                maximumSize = Dimension(Int.MAX_VALUE, 40.scaled)
                                border =
                                    JBUI.Borders.compound(
                                        RoundedLineBorder(JBColor(Color(100, 100, 100), Color(180, 180, 180)), 8, 1),
                                        JBUI.Borders.empty(10.scaled, 12.scaled),
                                    )
                                putClientProperty("JTextField.placeholderText", "Paste your token here...")
                            },
                        )

                        panel.add(Box.createRigidArea(Dimension(0, 20.scaled)))

                        // Help text at bottom
                        panel.add(
                            createInlineHyperlinkRow(
                                project = project,
                                leading = "If the browser didn't open automatically, please ",
                                linkText = "click here",
                                url = AUTH_URL,
                                scale = 0.9f,
                            ).apply {
                                alignmentX = JComponent.CENTER_ALIGNMENT
                            },
                        )

                        return panel
                    }

                    // No wrapped text helper; use simple JBLabel for plain copy

                    private fun createInlineHyperlinkRow(
                        project: Project,
                        leading: String? = null,
                        linkText: String,
                        url: String,
                        trailing: String? = null,
                        scale: Float = 1f,
                        bold: Boolean = false,
                    ): JComponent =
                        HyperlinkLabel().apply {
                            withSweepFont(project, scale = scale, bold = bold)
                            setHyperlinkText(leading ?: "", linkText, trailing ?: "")
                            setHyperlinkTarget(url)
                            alignmentX = JComponent.LEFT_ALIGNMENT
                            alignmentY = 0.5f
                            isFocusable = false
                            isOpaque = false
                            border = JBUI.Borders.empty()
                        }

                    override fun doValidate(): ValidationInfo? {
                        val token = String(tokenField.password).trim()
                        if (token.isEmpty()) {
                            return ValidationInfo("Please enter a token", tokenField)
                        }
                        return null
                    }

                    override fun doOKAction() {
                        val token = String(tokenField.password).trim()
                        if (token.isNotEmpty()) {
                            // Track manual token entry
                            TelemetryService.getInstance().sendUsageEvent(
                                eventType = EventType.AUTH_FLOW_COMPLETED,
                                eventProperties = mapOf("success" to "true", "method" to "manual"),
                            )
                            // Set the token in SweepSettings and trigger notifications
                            SweepSettings.getInstance().githubToken = token
                            // Stop the auth server since we have a token
                            SweepAuthServer.stop()
                            super.doOKAction()
                        }
                    }

                    override fun doCancelAction() {
                        // Stop the auth server when dialog is cancelled
                        SweepAuthServer.stop()
                        super.doCancelAction()
                        // If user cancels and no token is set, show the config popup
                        if (SweepSettings.getInstance().githubToken.isBlank()) {
                            ApplicationManager.getApplication().invokeLater {
                                SweepConfig.getInstance(project).showConfigPopup()
                            }
                        }
                    }

                    override fun dispose() {
                        // Ensure auth server is stopped when dialog is disposed
                        SweepAuthServer.stop()
                        currentDialog = null
                        super.dispose()
                    }
                }

            currentDialog = dialog
            dialog.show()
        }
    }
}
