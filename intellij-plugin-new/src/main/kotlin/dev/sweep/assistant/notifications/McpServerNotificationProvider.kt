package dev.sweep.assistant.notifications

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import dev.sweep.assistant.services.SweepMcpService
import dev.sweep.assistant.utils.getMcpConfigPath
import java.io.File
import java.util.function.Function
import javax.swing.JComponent

class McpServerNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?> =
        Function { fileEditor ->
            if (shouldShowBanner(file)) {
                createNotificationPanel(project, fileEditor)
            } else {
                null
            }
        }

    private fun shouldShowBanner(file: VirtualFile): Boolean {
        // Show banner for MCP configuration files
        val mcpConfigPath = getMcpConfigPath()
        val mcpConfigFile = File(mcpConfigPath)
        // Normalize paths for comparison (VirtualFile uses forward slashes, File uses OS-specific separators)
        val normalizedFilePath = file.path.replace('\\', '/')
        val normalizedConfigPath = mcpConfigFile.absolutePath.replace('\\', '/')
        return normalizedFilePath == normalizedConfigPath
    }

    private fun createNotificationPanel(
        project: Project,
        fileEditor: FileEditor,
    ): EditorNotificationPanel {
        val panel = EditorNotificationPanel(fileEditor, EditorNotificationPanel.Status.Info)

        // Set the banner text
        panel.text = "Sweep supports local and remote MCP servers. Click \"Refresh MCP\" to apply changes."
        panel.createActionLabel("🔄 Refresh MCP") {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    SweepMcpService.getInstance(project).handleConfigFileClosedDebounced()
                } catch (e: Exception) {
                    // Handle exception silently
                }
            }
        }
        panel.createActionLabel("How to use MCP with Sweep") {
            BrowserUtil.browse("https://docs.sweep.dev/mcp-servers")
        }

        return panel
    }
}
