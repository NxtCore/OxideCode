package dev.sweep.assistant.mcp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.sweep.assistant.utils.showNotification
import java.util.concurrent.ConcurrentHashMap

data class MCPServerStatusInfo(
    val name: String,
    val status: MCPServerStatus,
    val errorMessage: String? = null,
    val fullErrorOutput: String? = null,
    val toolCount: Int = 0,
)

data class MCPToolInfo(
    val name: String,
    val description: String,
    val serverName: String,
    val isEnabled: Boolean = true,
    val properties: String,
    val required: String,
    val type: String,
)

class SweepMcpClientManager : AutoCloseable {
    private val clients = ConcurrentHashMap<String, SweepMcpClient>()

    // Store server configs for pending auth servers so we can connect them later
    private val pendingAuthConfigs = ConcurrentHashMap<String, MCPServerConfig>()

    companion object {
        private val logger = Logger.getInstance(SweepMcpClientManager::class.java)
    }

    suspend fun addServer(
        serverName: String,
        serverConfig: MCPServerConfig,
        project: Project? = null,
    ) {
        val client = SweepMcpClient()
        try {
            client.setServerName(serverName)
            clients[serverName] = client
            client.connectToServer(serverConfig, project)
        } catch (e: Exception) {
            // Still add the client even if connection failed so we can track the failure
            clients[serverName] = client
            // Show a friendly notification instead of a red error
            project?.let { proj ->
                val errorMsg = e.message ?: "Unknown error"
                showNotification(
                    proj,
                    "MCP Server '$serverName' Connection Failed",
                    "Could not connect to MCP server. Please check your configuration.\n\nError: $errorMsg",
                )
            }
            logger.info("MCP server '$serverName' connection failed: ${e.message}")
        }
    }

    fun addFailedServer(
        serverName: String,
        errorMessage: String,
        project: Project? = null,
    ) {
        val client = SweepMcpClient()
        client.setServerName(serverName)
        client.setFailureState(errorMessage, project)
        clients[serverName] = client
    }

    /**
     * Add a server that requires OAuth authentication with browser interaction.
     * The server will be in PENDING_AUTH state until the user manually triggers connection.
     */
    fun addPendingAuthServer(
        serverName: String,
        serverConfig: MCPServerConfig,
        project: Project? = null,
    ) {
        val client = SweepMcpClient()
        client.setServerName(serverName)
        client.setPendingAuthState("Requires authentication", project)
        clients[serverName] = client
        // Store the config so we can use it when user clicks Connect
        pendingAuthConfigs[serverName] = serverConfig
    }

    /**
     * Manually connect a pending auth server.
     * This is called when the user clicks the Connect button.
     */
    suspend fun connectPendingAuthServer(
        serverName: String,
        project: Project? = null,
    ): Boolean {
        val serverConfig = pendingAuthConfigs[serverName]
        if (serverConfig == null) {
            logger.warn("No pending auth config found for server: $serverName")
            return false
        }

        // Remove any existing client
        clients.remove(serverName)?.close()

        // Try to connect (this will trigger the OAuth flow with browser)
        val client = SweepMcpClient()
        try {
            client.setServerName(serverName)
            clients[serverName] = client
            client.connectToServer(serverConfig, project)

            // Connection successful, remove from pending configs
            pendingAuthConfigs.remove(serverName)
            return true
        } catch (e: Exception) {
            // Connection failed
            clients[serverName] = client
            project?.let { proj ->
                val errorMsg = e.message ?: "Unknown error"
                showNotification(
                    proj,
                    "MCP Server '$serverName' Connection Failed",
                    "Could not connect to MCP server. Please check your configuration.\n\nError: $errorMsg",
                )
            }
            logger.info("MCP server '$serverName' connection failed: ${e.message}")
            return false
        }
    }

    /**
     * Check if a server is pending authentication.
     */
    fun isPendingAuth(serverName: String): Boolean = pendingAuthConfigs.containsKey(serverName)

    /**
     * Check if any servers are pending authentication.
     */
    fun hasPendingAuthServers(): Boolean = pendingAuthConfigs.isNotEmpty()

    fun getSweepClient(name: String): SweepMcpClient? = clients[name]

    fun getAllSweepClients(): Map<String, SweepMcpClient> = clients.toMap()

    fun getServerStatusList(): List<MCPServerStatusInfo> =
        clients.map { (name, client) ->
            MCPServerStatusInfo(
                name = name,
                status = client.status,
                errorMessage = client.errorMessage,
                fullErrorOutput = client.fullErrorOutput,
                toolCount = client.getToolCount(),
            )
        }

    fun renderServerStatusList(): String {
        val statusList = getServerStatusList()
        if (statusList.isEmpty()) {
            return "No servers"
        }
        return statusList.joinToString("<br>") { server ->
            val statusText =
                server.status.name
                    .lowercase()
                    .replaceFirstChar { it.uppercase() }
            "${if (server.status == MCPServerStatus.CONNECTED) "✓ " else ""}${server.name}: $statusText${if (server.errorMessage != null) " - ${server.errorMessage}" else ""}"
        }
    }

    fun clearAllServers() {
        clients.values.forEach { it.close() }
        clients.clear()
        pendingAuthConfigs.clear()
    }

    fun removeServer(serverName: String) {
        clients.remove(serverName)?.close()
        pendingAuthConfigs.remove(serverName)
    }

    fun hasServer(serverName: String): Boolean = clients.containsKey(serverName)

    /**
     * Fetches all available MCP tools from all connected MCP servers.
     *
     * @param disabledServers Set of server names that are disabled. Servers not in this set are included.
     * @param disabledTools Map of server names to sets of disabled tool names. Tools not in these sets are included.
     * @return A list of maps containing tool information. Each map contains:
     *         - "name": The tool name
     *         - "description": The tool description
     *         - "properties": The tool input schema properties as string
     *         - "required": The required properties as string
     *         - "type": The input schema type
     *         - "serverName": The name of the MCP server providing this tool
     */
    fun fetchAllMcpTools(
        disabledServers: Set<String> = emptySet(),
        disabledTools: Map<String, Set<String>> = emptyMap(),
    ): List<Map<String, String>> {
        val allTools = mutableListOf<Map<String, String>>()

        // Iterate through each client and collect their tools
        for ((serverName, client) in clients) {
            try {
                // Only fetch tools from successfully connected clients that are not disabled
                val isServerEnabled = !disabledServers.contains(serverName)
                if (client.status == MCPServerStatus.CONNECTED && isServerEnabled) {
                    // Access the private tools field using reflection since it's not exposed
                    val toolsField = client.javaClass.getDeclaredField("tools")
                    toolsField.isAccessible = true

                    // Check if tools field is initialized before accessing it
                    val isToolsInitialized =
                        try {
                            // Try to get the tools field, if it throws UninitializedPropertyAccessException, it's not initialized
                            toolsField.get(client)
                            true
                        } catch (e: kotlin.UninitializedPropertyAccessException) {
                            false
                        }

                    @Suppress("UNCHECKED_CAST")
                    val serverTools =
                        if (isToolsInitialized) {
                            toolsField.get(client) as? List<Map<String, String>> ?: emptyList()
                        } else {
                            emptyList()
                        }

                    // Filter tools based on disabled tools - exclude tools that are disabled
                    val disabledToolsForServer = disabledTools[serverName] ?: emptySet()
                    val filteredTools =
                        serverTools.filter { tool ->
                            val toolName = tool["name"] ?: ""
                            !disabledToolsForServer.contains(toolName)
                        }

                    // Add server name to each tool for identification
                    val toolsWithServerName =
                        filteredTools.map { tool ->
                            tool.toMutableMap().apply {
                                put("serverName", serverName)
                            }
                        }

                    allTools.addAll(toolsWithServerName)
                }
            } catch (e: Exception) {
                logger.warn("Failed to fetch tools from MCP server '$serverName': ${e.message}")
            }
        }

        return allTools
    }

    /**
     * Gets all available tools from all connected servers for UI purposes.
     */
    fun getAllAvailableTools(): List<MCPToolInfo> {
        val allTools = mutableListOf<MCPToolInfo>()

        for ((serverName, client) in clients) {
            try {
                if (client.status == MCPServerStatus.CONNECTED) {
                    val toolsField = client.javaClass.getDeclaredField("tools")
                    toolsField.isAccessible = true

                    // Check if tools field is initialized before accessing it
                    val isToolsInitialized =
                        try {
                            // Try to get the tools field, if it throws UninitializedPropertyAccessException, it's not initialized
                            toolsField.get(client)
                            true
                        } catch (e: kotlin.UninitializedPropertyAccessException) {
                            false
                        }

                    @Suppress("UNCHECKED_CAST")
                    val serverTools =
                        if (isToolsInitialized) {
                            toolsField.get(client) as? List<Map<String, String>> ?: emptyList()
                        } else {
                            emptyList()
                        }

                    val toolInfos =
                        serverTools.map { tool ->
                            MCPToolInfo(
                                name = tool["name"] ?: "",
                                description = tool["description"] ?: "",
                                serverName = serverName,
                                isEnabled = true, // Default to enabled, will be overridden by config
                                properties = tool["properties"] ?: "",
                                required = tool["required"] ?: "",
                                type = tool["type"] ?: "",
                            )
                        }

                    allTools.addAll(toolInfos)
                }
            } catch (e: Exception) {
                logger.warn("Failed to fetch tool info from MCP server '$serverName': ${e.message}")
            }
        }

        return allTools
    }

    override fun close() {
        // Close all clients (each close() is now non-blocking)
        clients.values.forEach { client ->
            try {
                client.close()
            } catch (e: Exception) {
                logger.warn("Error closing MCP client: ${e.message}")
            }
        }
        // Clear the clients map immediately after initiating closures
        clients.clear()
    }
}
