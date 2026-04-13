package dev.sweep.assistant.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.notification.NotificationAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.MessageBusConnection
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.mcp.MCPServerConfig
import dev.sweep.assistant.mcp.MCPServersConfig
import dev.sweep.assistant.mcp.SweepMcpClient
import dev.sweep.assistant.mcp.SweepMcpClientManager
import dev.sweep.assistant.mcp.auth.McpOAuthDiscovery
import dev.sweep.assistant.mcp.auth.McpOAuthTokenStorage
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.getMcpConfigPath
import dev.sweep.assistant.utils.showNotification
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Service(Service.Level.PROJECT)
class SweepMcpService(
    private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): SweepMcpService = project.getService(SweepMcpService::class.java)

        // Server connection timeout in milliseconds
        // Remote OAuth servers (like Notion) need more time for OAuth flow + connection + initialization
        const val SERVER_CONNECTION_TIMEOUT_MS = 120_000L // 2 minutes for remote servers

        // Server name validation pattern from Vertex AI API requirements
        val SERVER_NAME_PATTERN = Regex("^[a-zA-Z0-9_-]{1,128}$")

        const val SERVER_NAME_REQUIREMENTS = "Server name must contain only letters, numbers, underscores, and hyphens (1-128 characters)"
        const val SERVER_NAME_EXAMPLE = "Example: \"server name\" should be \"server-name\""
        const val SERVER_NAME_ERROR_SHORT = "Invalid server name. Must contain only letters, numbers, underscores, and hyphens (1-128 characters). Example: \"server name\" should be \"server-name\"."
        const val SERVER_NAME_ERROR_DETAILED = "Server name must contain only letters, numbers, underscores, and hyphens (1-128 characters). Example: \"server name\" should be \"server-name\". Please update your MCP configuration."

        fun isValidServerName(serverName: String): Boolean = serverName.matches(SERVER_NAME_PATTERN)
    }

    private val sweepMcpClientManager = SweepMcpClientManager()
    private val objectMapper = ObjectMapper()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val restartMutex = Mutex()

    // File watching for automatic config updates
    private var configFileWatcher: MessageBusConnection? = null
    private var fileEditorWatcher: MessageBusConnection? = null
    private var lastConfigContent: String? = null
    private val configUpdateMutex = Mutex()
    private var configChangeJob: Job? = null
    private val debounceDelay = 500L // milliseconds
    private var refreshOnCloseJob: Job? = null

    init {
        // Initialize MCP servers on startup
        scope.launch {
            initializeMCPServers()
            // Start watching config file for changes
            initializeConfigFileWatcher()
            initializeFileEditorWatcher()
        }
    }

    private suspend fun initializeMCPServers() {
        try {
            val configPath = getMcpConfigPath()
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(configPath)

            if (virtualFile?.exists() != true) {
                return
            }

            val contents =
                ApplicationManager.getApplication().runReadAction<String?> {
                    FileDocumentManager.getInstance().getDocument(virtualFile)?.text
                }
            lastConfigContent = contents // Store the initial config content

            val config = objectMapper.readValue(contents, MCPServersConfig::class.java)

            config.mcpServers.forEach { (serverName, serverConfig) ->
                // Validate server name
                if (!isValidServerName(serverName)) {
                    sweepMcpClientManager.addFailedServer(
                        serverName,
                        SERVER_NAME_ERROR_SHORT,
                        project,
                    )
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            showNotification(
                                project,
                                "Invalid MCP Server Name: $serverName",
                                SERVER_NAME_ERROR_DETAILED,
                                "Error Notifications",
                            )
                        }
                    }
                    return@forEach // Skip connecting to this server
                }

                // Skip disabled servers
                if (!SweepConfig.getInstance(project).isMcpServerEnabled(serverName)) {
                    return@forEach
                }

                // Check if this is a remote OAuth server that needs user interaction
                if (shouldDeferConnection(serverName, serverConfig)) {
                    sweepMcpClientManager.addPendingAuthServer(serverName, serverConfig, project)
                    return@forEach
                }

                try {
                    withTimeout(SERVER_CONNECTION_TIMEOUT_MS) {
                        sweepMcpClientManager.addServer(serverName, serverConfig, project)
                    }
                } catch (e: TimeoutCancellationException) {
                    // Add the server with failed status
                    sweepMcpClientManager.addFailedServer(
                        serverName,
                        "Couldn't connect after ${SERVER_CONNECTION_TIMEOUT_MS / 1000} seconds, please double check that your paths are correct!",
                        project,
                    )
                } catch (e: Exception) {
                    println("Failed to add server $serverName: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("Failed to initialize MCP servers: ${e.message}")
        }
    }

    /**
     * Check if a server connection should be deferred because it requires OAuth browser authentication.
     * Returns true if:
     * - Server is remote AND
     * - Server requires OAuth (explicitly configured or discovered via probe) AND
     * - We don't have valid cached credentials
     */
    private suspend fun shouldDeferConnection(
        serverName: String,
        serverConfig: MCPServerConfig,
    ): Boolean {
        // Local servers can always auto-connect
        if (serverConfig.isLocal()) {
            return false
        }

        // Remote servers with a static authorization token can auto-connect
        if (serverConfig.authorization_token != null) {
            return false
        }

        val tokenStorage = McpOAuthTokenStorage()

        // If OAuth is explicitly configured, check for cached credentials
        if (serverConfig.oauth != null) {
            return !tokenStorage.hasValidOrRefreshableCredentials(serverName)
        }

        // For remote servers without explicit OAuth config, we need to probe
        // to see if OAuth is required
        val remoteUrl = serverConfig.getRemoteUrl() ?: return false

        try {
            // Probe the server to check if OAuth is required
            val discoveredConfig = McpOAuthDiscovery.handleOAuthDiscoveryFor401(remoteUrl, null)
            if (discoveredConfig != null) {
                // Server requires OAuth - check if we have cached credentials
                return !tokenStorage.hasValidOrRefreshableCredentials(serverName)
            }
        } catch (e: Exception) {
            // If probe fails, assume no OAuth is needed and try to connect normally
            println("OAuth probe failed for $serverName: ${e.message}")
        }

        // No OAuth required, can auto-connect
        return false
    }

    fun getClientManager(): SweepMcpClientManager = sweepMcpClientManager

    suspend fun restartAllServers() {
        restartMutex.withLock {
            try {
                // Clear all existing connections
                sweepMcpClientManager.clearAllServers()

                // Re-initialize all servers
                initializeMCPServers()

                println("Successfully restarted all MCP servers")
            } catch (e: Exception) {
                println("Failed to restart MCP servers: ${e.message}")
            }
        }
    }

    private fun initializeConfigFileWatcher() {
        if (configFileWatcher != null) return

        configFileWatcher =
            project.messageBus.connect(this).apply {
                subscribe(
                    VirtualFileManager.VFS_CHANGES,
                    object : BulkFileListener {
                        override fun after(events: List<VFileEvent>) {
                            events.forEach { event ->
                                val file = event.file ?: return@forEach
                                if (!file.isInLocalFileSystem) return@forEach

                                // Check if this is our MCP config file
                                if (file.path == getMcpConfigPath()) {
                                    when (event) {
                                        is VFileContentChangeEvent, is VFileCreateEvent -> {
                                            // Handle config file changes with debouncing
                                            handleConfigFileChangeDebounced()
                                        }
                                    }
                                }
                            }
                        }
                    },
                )
            }
    }

    private fun handleConfigFileChangeDebounced() {
        configChangeJob?.cancel()
        configChangeJob =
            scope.launch {
                delay(debounceDelay)
                handleConfigFileChange()
            }
    }

    private suspend fun handleConfigFileChange() {
        configUpdateMutex.withLock {
            try {
                val configPath = getMcpConfigPath()
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(configPath)

                if (virtualFile?.exists() != true) {
                    // Config file was deleted - clear all servers
                    sweepMcpClientManager.clearAllServers()
                    lastConfigContent = null
                    return
                }

                val newContent =
                    ApplicationManager.getApplication().runReadAction<String?> {
                        FileDocumentManager.getInstance().getDocument(virtualFile)?.text
                    }

                if (newContent.isNullOrEmpty()) {
                    // Config file was deleted - clear all servers
                    sweepMcpClientManager.clearAllServers()
                    lastConfigContent = null
                    return
                }

                // Check if content actually changed
                if (newContent == lastConfigContent) {
                    return
                }

                lastConfigContent = newContent

                // Parse new config
                val newConfig = objectMapper.readValue(newContent, MCPServersConfig::class.java)
                val currentServers = sweepMcpClientManager.getAllSweepClients().keys
                val newServers = newConfig.mcpServers.keys

                // Find servers to remove (in current but not in new)
                val serversToRemove = currentServers - newServers

                // Find servers to add (in new but not in current)
                val serversToAdd = newServers - currentServers

                // Find servers to potentially update (in both, but config might have changed)
                val serversToCheck = currentServers.intersect(newServers)

                // Remove servers that are no longer in config
                serversToRemove.forEach { serverName ->
                    sweepMcpClientManager.removeServer(serverName)
                }

                // Add new servers
                serversToAdd.forEach { serverName ->
                    // Validate server name
                    if (!isValidServerName(serverName)) {
                        sweepMcpClientManager.addFailedServer(
                            serverName,
                            SERVER_NAME_ERROR_SHORT,
                            project,
                        )
                        ApplicationManager.getApplication().invokeLater {
                            if (!project.isDisposed) {
                                showNotification(
                                    project,
                                    "Invalid MCP Server Name: $serverName",
                                    SERVER_NAME_ERROR_DETAILED,
                                    "Error Notifications",
                                )
                            }
                        }
                        return@forEach // Skip connecting to this server
                    }

                    // Skip disabled servers
                    if (!SweepConfig.getInstance(project).isMcpServerEnabled(serverName)) {
                        return@forEach
                    }

                    val serverConfig = newConfig.mcpServers[serverName]!!

                    // Check if this is a remote OAuth server that needs user interaction
                    if (shouldDeferConnection(serverName, serverConfig)) {
                        sweepMcpClientManager.addPendingAuthServer(serverName, serverConfig, project)
                        return@forEach
                    }

                    try {
                        withTimeout(SERVER_CONNECTION_TIMEOUT_MS) {
                            sweepMcpClientManager.addServer(serverName, serverConfig, project)
                        }
                    } catch (e: TimeoutCancellationException) {
                        sweepMcpClientManager.addFailedServer(
                            serverName,
                            "Couldn't connect after ${SERVER_CONNECTION_TIMEOUT_MS / 1000} seconds, please double check that your paths are correct!",
                            project,
                        )
                    } catch (e: Exception) {
                        // Handle error
                    }
                }

                // For existing servers, check if their config changed and restart if needed
                serversToCheck.forEach { serverName ->
                    val newServerConfig = newConfig.mcpServers[serverName]!!
                    val currentClient = sweepMcpClientManager.getSweepClient(serverName)

                    // Skip disabled servers
                    if (!SweepConfig.getInstance(project).isMcpServerEnabled(serverName)) {
                        // If server was previously connected but is now disabled, remove it
                        if (currentClient != null) {
                            currentClient.close()
                            sweepMcpClientManager.removeServer(serverName)
                        }
                        return@forEach
                    }

                    // Compare configs and restart if different
                    if (shouldRestartServer(currentClient, newServerConfig)) {
                        currentClient?.close()
                        sweepMcpClientManager.removeServer(serverName)

                        // Check if this is a remote OAuth server that needs user interaction
                        if (shouldDeferConnection(serverName, newServerConfig)) {
                            sweepMcpClientManager.addPendingAuthServer(serverName, newServerConfig, project)
                            return@forEach
                        }

                        try {
                            withTimeout(SERVER_CONNECTION_TIMEOUT_MS) {
                                sweepMcpClientManager.addServer(serverName, newServerConfig, project)
                            }
                        } catch (e: TimeoutCancellationException) {
                            sweepMcpClientManager.addFailedServer(
                                serverName,
                                "Couldn't connect after ${SERVER_CONNECTION_TIMEOUT_MS / 1000} seconds, please double check that your paths are correct!",
                                project,
                            )
                        } catch (e: Exception) {
                            // Handle error
                        }
                    }
                }
            } catch (e: Exception) {
                // Log error but don't crash
                println("Failed to handle MCP config file change: ${e.message}")
            }
        }
    }

    /**
     * Public method to refresh servers by reading config directly from disk.
     * This performs an incremental update - only adding new servers and removing deleted ones,
     * without restarting servers that haven't changed.
     *
     * Use this when you've modified the config file and want to trigger an update
     * without relying on the file watcher (which requires the file to be open).
     */
    suspend fun refreshServersFromDisk() {
        configUpdateMutex.withLock {
            try {
                val configPath = getMcpConfigPath()
                val configFile = java.io.File(configPath)

                if (!configFile.exists()) {
                    // Config file was deleted - clear all servers
                    sweepMcpClientManager.clearAllServers()
                    lastConfigContent = null
                    return
                }

                val newContent = configFile.readText()

                if (newContent.isEmpty()) {
                    // Config file is empty - clear all servers
                    sweepMcpClientManager.clearAllServers()
                    lastConfigContent = null
                    return
                }

                // Check if content actually changed
                if (newContent == lastConfigContent) {
                    return
                }

                lastConfigContent = newContent

                // Parse new config
                val newConfig = objectMapper.readValue(newContent, MCPServersConfig::class.java)
                val currentServers = sweepMcpClientManager.getAllSweepClients().keys
                val newServers = newConfig.mcpServers.keys

                // Find servers to remove (in current but not in new)
                val serversToRemove = currentServers - newServers

                // Find servers to add (in new but not in current)
                val serversToAdd = newServers - currentServers

                // Find servers to potentially update (in both, but config might have changed)
                val serversToCheck = currentServers.intersect(newServers)

                // Remove servers that are no longer in config
                serversToRemove.forEach { serverName ->
                    sweepMcpClientManager.removeServer(serverName)
                }

                // Add new servers
                serversToAdd.forEach { serverName ->
                    // Validate server name
                    if (!isValidServerName(serverName)) {
                        sweepMcpClientManager.addFailedServer(
                            serverName,
                            SERVER_NAME_ERROR_SHORT,
                            project,
                        )
                        ApplicationManager.getApplication().invokeLater {
                            if (!project.isDisposed) {
                                showNotification(
                                    project,
                                    "Invalid MCP Server Name: $serverName",
                                    SERVER_NAME_ERROR_DETAILED,
                                    "Error Notifications",
                                )
                            }
                        }
                        return@forEach // Skip connecting to this server
                    }

                    // Skip disabled servers
                    if (!SweepConfig.getInstance(project).isMcpServerEnabled(serverName)) {
                        return@forEach
                    }

                    val serverConfig = newConfig.mcpServers[serverName]!!

                    // Check if this is a remote OAuth server that needs user interaction
                    if (shouldDeferConnection(serverName, serverConfig)) {
                        sweepMcpClientManager.addPendingAuthServer(serverName, serverConfig, project)
                        return@forEach
                    }

                    try {
                        withTimeout(SERVER_CONNECTION_TIMEOUT_MS) {
                            sweepMcpClientManager.addServer(serverName, serverConfig, project)
                        }
                    } catch (e: TimeoutCancellationException) {
                        sweepMcpClientManager.addFailedServer(
                            serverName,
                            "Couldn't connect after ${SERVER_CONNECTION_TIMEOUT_MS / 1000} seconds, please double check that your paths are correct!",
                            project,
                        )
                    } catch (e: Exception) {
                        // Handle error
                    }
                }

                // For existing servers, check if their config changed and restart if needed
                serversToCheck.forEach { serverName ->
                    val newServerConfig = newConfig.mcpServers[serverName]!!
                    val currentClient = sweepMcpClientManager.getSweepClient(serverName)

                    // Skip disabled servers
                    if (!SweepConfig.getInstance(project).isMcpServerEnabled(serverName)) {
                        // If server was previously connected but is now disabled, remove it
                        if (currentClient != null) {
                            currentClient.close()
                            sweepMcpClientManager.removeServer(serverName)
                        }
                        return@forEach
                    }

                    // Compare configs and restart if different
                    if (shouldRestartServer(currentClient, newServerConfig)) {
                        currentClient?.close()
                        sweepMcpClientManager.removeServer(serverName)

                        // Check if this is a remote OAuth server that needs user interaction
                        if (shouldDeferConnection(serverName, newServerConfig)) {
                            sweepMcpClientManager.addPendingAuthServer(serverName, newServerConfig, project)
                            return@forEach
                        }

                        try {
                            withTimeout(SERVER_CONNECTION_TIMEOUT_MS) {
                                sweepMcpClientManager.addServer(serverName, newServerConfig, project)
                            }
                        } catch (e: TimeoutCancellationException) {
                            sweepMcpClientManager.addFailedServer(
                                serverName,
                                "Couldn't connect after ${SERVER_CONNECTION_TIMEOUT_MS / 1000} seconds, please double check that your paths are correct!",
                                project,
                            )
                        } catch (e: Exception) {
                            // Handle error
                        }
                    }
                }
            } catch (e: Exception) {
                // Log error but don't crash
                println("Failed to refresh MCP servers from disk: ${e.message}")
            }
        }
    }

    private fun shouldRestartServer(
        currentClient: dev.sweep.assistant.mcp.SweepMcpClient?,
        newServerConfig: dev.sweep.assistant.mcp.MCPServerConfig,
    ): Boolean {
        // For now, we'll restart servers when their config changes
        // This could be made more intelligent by comparing specific config fields
        return true
    }

    private fun initializeFileEditorWatcher() {
        if (fileEditorWatcher != null) return

        fileEditorWatcher =
            project.messageBus.connect(this).apply {
                subscribe(
                    FileEditorManagerListener.FILE_EDITOR_MANAGER,
                    object : FileEditorManagerListener {
                        override fun fileClosed(
                            source: FileEditorManager,
                            file: VirtualFile,
                        ) {
                            // Check if the closed file is our MCP config file
                            if (file.path == getMcpConfigPath()) {
                                handleConfigFileClosedDebounced()
                            }
                        }
                    },
                )
            }
    }

    fun handleConfigFileClosedDebounced() {
        refreshOnCloseJob?.cancel()
        refreshOnCloseJob =
            scope.launch {
                delay(debounceDelay)
                ApplicationManager.getApplication().executeOnPooledThread {
                    runBlocking {
                        handleConfigFileChange()
                        val serverStatusList = sweepMcpClientManager.renderServerStatusList()
                        val hasPendingAuth = sweepMcpClientManager.hasPendingAuthServers()
                        // Show notification after refresh completes
                        ApplicationManager.getApplication().invokeLater {
                            if (!project.isDisposed) {
                                val mcpSettingsAction =
                                    if (hasPendingAuth) {
                                        val mcpTabName =
                                            if (SweepSettings.getInstance().hasBeenSet) {
                                                "MCP Servers"
                                            } else {
                                                "MCP Servers (Beta)"
                                            }
                                        NotificationAction.createSimpleExpiring("Open MCP Settings to Connect") {
                                            SweepConfig.getInstance(project).showConfigPopup(mcpTabName)
                                        }
                                    } else {
                                        null
                                    }
                                showNotification(
                                    project,
                                    "MCP Status",
                                    "<html>MCP Servers:<br>$serverStatusList</html>",
                                    action = mcpSettingsAction,
                                )
                            }
                        }
                    }
                }
            }
    }

    override fun dispose() {
        // First cancel any ongoing configuration changes
        configChangeJob?.cancel()
        refreshOnCloseJob?.cancel()

        // Disconnect the file watchers
        configFileWatcher?.disconnect()
        fileEditorWatcher?.disconnect()

        // Cancel the coroutine scope to stop all ongoing operations
        scope.cancel()

        // Close the MCP client manager (this is now non-blocking)
        sweepMcpClientManager.close()
    }
}
