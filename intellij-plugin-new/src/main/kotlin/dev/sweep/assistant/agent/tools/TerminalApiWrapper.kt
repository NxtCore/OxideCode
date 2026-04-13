package dev.sweep.assistant.agent.tools

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.terminal.JBTerminalWidget
import com.intellij.terminal.ui.TerminalWidget
import dev.sweep.assistant.utils.*
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.lang.reflect.Method

/**
 * Wrapper class to handle different terminal API versions using reflection.
 * Supports both newer TerminalWidget API and older ShellWidget/JBTerminalWidget APIs.
 */
class TerminalApiWrapper {
    companion object {
        private val logger = Logger.getInstance(TerminalApiWrapper::class.java)

        // Cache for API detection
        private var isNewApiAvailable: Boolean? = null
        private var terminalWidgetClass: Class<*>? = null
        private var shellWidgetClass: Class<*>? = null
        private var jbTerminalWidgetClass: Class<*>? = null

        // Method caches for new API (TerminalWidget)
        private var sendCommandToExecuteMethod: Method? = null
        private var getTextMethod: Method? = null
        private var getTtyConnectorMethod: Method? = null
        private var getTerminalTitleMethod: Method? = null
        private var getComponentMethod: Method? = null

        // Method caches for old API (ShellWidget/JBTerminalWidget)
        private var executeCommandMethod: Method? = null
        private var getTerminalTextBufferMethod: Method? = null

        init {
            detectApiVersion()
        }

        private fun detectApiVersion() {
            try {
                // Try to load new API classes first
                terminalWidgetClass = tryLoadClass("com.intellij.terminal.ui.TerminalWidget")

                if (terminalWidgetClass != null) {
                    // Try to find new API methods
                    sendCommandToExecuteMethod =
                        tryMethodWithParams(
                            terminalWidgetClass,
                            "sendCommandToExecute",
                            String::class.java,
                        )
                    getTextMethod = tryMethod(terminalWidgetClass, "getText")
                    getTtyConnectorMethod = tryMethod(terminalWidgetClass, "getTtyConnector")
                    getTerminalTitleMethod = tryMethod(terminalWidgetClass, "getTerminalTitle")
                    getComponentMethod = tryMethod(terminalWidgetClass, "getComponent")

                    // If all essential methods exist, we have new API
                    if (sendCommandToExecuteMethod != null && getTextMethod != null) {
                        isNewApiAvailable = true
                        logger.info("Terminal API detection: Using new TerminalWidget API")
                        return
                    }
                }

                // Try to load old API classes
                shellWidgetClass = tryLoadClass("org.jetbrains.plugins.terminal.ShellTerminalWidget")
                jbTerminalWidgetClass = tryLoadClass("org.jetbrains.plugins.terminal.JBTerminalWidget")

                if (shellWidgetClass != null) {
                    executeCommandMethod =
                        tryMethodWithParams(
                            shellWidgetClass,
                            "executeCommand",
                            String::class.java,
                        )

                    // For old API, text buffer might be accessed differently
                    val terminalTextBufferClass = tryLoadClass("com.jediterm.terminal.model.TerminalTextBuffer")
                    if (terminalTextBufferClass != null) {
                        getTerminalTextBufferMethod = tryMethod(shellWidgetClass, "getTerminalTextBuffer")
                    }

                    if (executeCommandMethod != null) {
                        isNewApiAvailable = false
                        logger.info("Terminal API detection: Using old ShellWidget API")
                        return
                    }
                }

                // If we can't detect either API, log a warning
                logger.warn("Terminal API detection: Could not detect terminal API version, will attempt runtime detection")
            } catch (e: Exception) {
                logger.error("Error detecting terminal API version", e)
            }
        }

        /**
         * Check if the terminal is using PowerShell.
         */
        fun isPowerShell(project: Project): Boolean =
            try {
                val shellName = detectShellName(project).lowercase()
                // Check for PowerShell or pwsh (PowerShell Core)
                shellName.contains("powershell") || shellName.contains("pwsh")
            } catch (e: Exception) {
                logger.debug("Could not determine shell type, assuming not PowerShell", e)
                false
            }

        /**
         * Fixes multiline commands for PowerShell terminal input.
         *
         * PSReadLine (PowerShell's readline module) has a known bug where pasting/sending text with
         * \n (LF only) line endings causes lines to appear in REVERSE order.
         *
         * Converting to \r\n (CRLF) doesn't work either because each \r triggers immediate execution
         * of that line, fragmenting the command.
         *
         * The solution is to Base64 encode the entire command and decode/execute it in PowerShell.
         * This is bulletproof because Base64 only contains alphanumeric characters (A-Z, a-z, 0-9, +, /, =)
         * so there are no escaping issues regardless of what the original command contains.
         *
         * See:
         * - https://github.com/PowerShell/PSReadLine/issues/579 ("Right click paste should work mostly like Ctrl+v paste")
         * - https://github.com/PowerShell/PSReadLine/issues/496 ("Right-click paste with \n comes out upside down")
         * - https://github.com/microsoft/terminal/issues/8138 ("Lines are pasted in reverse order")
         */
        private fun fixMultilineCommandForPowerShell(
            command: String,
            isPowerShell: Boolean,
        ): String {
            // Only apply fix if command contains newlines
            if (!command.contains('\n')) {
                return command
            }

            // Check if the terminal is using PowerShell
            if (!isPowerShell) {
                return command
            }

            // Base64 encode the command - this completely avoids all escaping issues
            // because Base64 only contains safe alphanumeric characters.
            val base64Command =
                java.util.Base64
                    .getEncoder()
                    .encodeToString(command.toByteArray(Charsets.UTF_8))

            // Decode and execute in PowerShell using Invoke-Expression
            return "[System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String('$base64Command')) | Invoke-Expression"
        }

        /**
         * Send a command to execute in the terminal.
         * Automatically uses the correct API based on the widget type.
         *
         * @param widget The terminal widget
         * @param command The command to send
         * @param project The project
         * @param isPowerShellTerminal Precomputed PowerShell check (to avoid EDT threading violations)
         */
        fun sendCommand(
            widget: Any,
            command: String,
            project: Project,
            isPowerShellTerminal: Boolean = isPowerShell(project),
        ): Boolean {
            try {
                val commandToSend = fixMultilineCommandForPowerShell(command, isPowerShellTerminal)

                // Try new API first
                if (sendCommandToExecuteMethod != null &&
                    terminalWidgetClass?.isInstance(widget) == true
                ) {
                    invokeMethod(widget, sendCommandToExecuteMethod, commandToSend)
                    return true
                }

                // Try old API
                if (executeCommandMethod != null &&
                    shellWidgetClass?.isInstance(widget) == true
                ) {
                    invokeMethod(widget, executeCommandMethod, commandToSend)
                    return true
                }

                // Fallback: try to find method at runtime
                val widgetClass = widget.javaClass

                // Try sendCommandToExecute (new API)
                val sendMethod =
                    tryMethodWithParams(
                        widgetClass,
                        "sendCommandToExecute",
                        String::class.java,
                    )
                if (sendMethod != null) {
                    invokeMethod(widget, sendMethod, commandToSend)
                    return true
                }

                // Try executeCommand (old API)
                val execMethod =
                    tryMethodWithParams(
                        widgetClass,
                        "executeCommand",
                        String::class.java,
                    )
                if (execMethod != null) {
                    invokeMethod(widget, execMethod, commandToSend)
                    return true
                }

                logger.error("Could not find method to send command for widget type: ${widgetClass.name}")
                return false
            } catch (e: Exception) {
                logger.error("Error sending command to terminal", e)
                return false
            }
        }

        /**
         * Get text from the terminal.
         * Automatically uses the correct API based on the widget type.
         */
        fun getText(widget: Any): String? {
            try {
                // Try new API first
                if (getTextMethod != null &&
                    terminalWidgetClass?.isInstance(widget) == true
                ) {
                    return tryInvokeMethod(widget, getTextMethod)?.toString()
                }

                // Try old API - use JBTerminalWidget approach
                try {
                    val jbWidget =
                        JBTerminalWidget.asJediTermWidget(widget as TerminalWidget)
                            ?: throw RuntimeException("Unable to access terminal widget")
                    val shellWidget = jbWidget as ShellTerminalWidget
                    return shellWidget.text.trim()
                } catch (e: Exception) {
                    throw RuntimeException("Unable to access terminal widget")
                }
            } catch (e: Exception) {
                logger.error("Error getting text from terminal", e)
                return null
            }
        }

        /**
         * Check if the new terminal API is available.
         * @return true if the new TerminalWidget API is available, false if using old API or if detection failed
         */
        fun getIsNewApiAvailable(): Boolean = isNewApiAvailable ?: false

        /**
         * Get the UI component from the terminal widget.
         */
        fun getComponent(widget: Any): java.awt.Component? {
            try {
                // Try new API first
                if (getComponentMethod != null &&
                    terminalWidgetClass?.isInstance(widget) == true
                ) {
                    return tryInvokeMethod(widget, getComponentMethod) as? java.awt.Component
                }

                // For old API, the widget itself might be the component
                if (widget is java.awt.Component) {
                    return widget
                }

                // Fallback: try to find method at runtime
                val widgetClass = widget.javaClass

                // Try getComponent
                val getCompMethod = tryMethod(widgetClass, "getComponent")
                if (getCompMethod != null) {
                    return tryInvokeMethod(widget, getCompMethod) as? java.awt.Component
                }

                // Try getPreferredFocusableComponent (some versions)
                val getFocusMethod = tryMethod(widgetClass, "getPreferredFocusableComponent")
                if (getFocusMethod != null) {
                    return tryInvokeMethod(widget, getFocusMethod) as? java.awt.Component
                }

                return null
            } catch (e: Exception) {
                logger.error("Error getting component from terminal widget", e)
                return null
            }
        }
    }
}
