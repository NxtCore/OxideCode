package dev.sweep.assistant.components

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.services.SweepProjectService
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.settings.SweepSettingsParser
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.theme.SweepIcons.scale
import dev.sweep.assistant.utils.SweepConstants.TOOLWINDOW_NAME
import dev.sweep.assistant.views.RoundedButton
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

class WelcomeScreen(
    private val project: Project,
    private val parentDisposable: Disposable = SweepProjectService.getInstance(project),
) {
    fun create(): JBScrollPane {
        val contentPanel =
            panel {
                row {
                    icon(SweepIcons.BigSweepIcon.scale(80f)).align(AlignX.CENTER)
                }
                row {
                    text("Welcome!")
                        .applyToComponent {
                            font = font.deriveFont(java.awt.Font.BOLD, font.size * 1.2f)
                        }.align(AlignX.CENTER)
                }.topGap(TopGap.SMALL)
                row {
                    text("Please sign in below:").applyToComponent { font = font.deriveFont(font.size * 1.1f) }.align(AlignX.CENTER)
                }
                row {
                    cell(
                        RoundedButton(
                            text = " Sign in",
                            parentDisposable = parentDisposable,
                            onClick = {
                                val isCloudEnvironment = SweepSettingsParser.isCloudEnvironment()
                                if (isCloudEnvironment) {
                                    // Trigger Sweep OAuth flow
                                    ToolWindowManager
                                        .getInstance(project)
                                        .getToolWindow(TOOLWINDOW_NAME)
                                        ?.hide()
                                    SweepSettings.getInstance().initiateGitHubAuth(project)
                                } else {
                                    SweepConfig.getInstance(project).showConfigPopup()
                                }
                            },
                        ).apply {
                            icon = SweepIcons.UserIcon.scale(16f)
                            background = SweepColors.loginButtonColor
                            foreground = SweepColors.textOnPrimary
                            font = font.deriveFont(font.size * 1.2f)
                            border = JBUI.Borders.empty(6, 24)
                        },
                    ).align(AlignX.CENTER)
                }.topGap(TopGap.SMALL)

                row {
                    text(
                        """
                        <div style='text-align: center; font-size: 1.1em;'>
                        Sweep features:<br>
                        <a href='https://docs.sweep.dev/agent'>Agent</a><br>
                        <a href='https://docs.sweep.dev/autocomplete'>Autocomplete</a>
                        </div>
                        """.trimIndent(),
                    ).align(AlignX.CENTER)
                }.topGap(TopGap.MEDIUM)
            }

        // Center the content panel both horizontally and vertically
        val containerPanel =
            JPanel(GridBagLayout()).apply {
                val gbc = GridBagConstraints()
                gbc.gridx = 0
                gbc.gridy = 0
                gbc.weightx = 1.0
                gbc.weighty = 1.0
                gbc.anchor = GridBagConstraints.CENTER
                gbc.insets = JBUI.insets(0, 0, 40, 0)
                add(contentPanel, gbc)
            }

        return JBScrollPane(containerPanel).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            border = null
        }
    }
}
