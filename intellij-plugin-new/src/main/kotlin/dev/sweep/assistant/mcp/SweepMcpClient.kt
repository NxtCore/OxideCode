package dev.sweep.assistant.mcp
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import dev.sweep.assistant.mcp.auth.McpOAuthDiscovery
import dev.sweep.assistant.mcp.auth.McpOAuthProvider
import dev.sweep.assistant.mcp.auth.McpOAuthTokenStorage
import dev.sweep.assistant.mcp.auth.OAuthException
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.http.headers
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.io.buffered

enum class MCPServerStatus {
    CONNECTING,
    CONNECTED,
    FAILED,
    DISCONNECTED,

    /** Server requires OAuth authentication that needs user interaction (browser) */
    PENDING_AUTH,
}

// Listener interface for MCP server status changes
interface MCPServerStatusListener {
    fun onServerStatusChanged(
        serverName: String,
        status: MCPServerStatus,
        errorMessage: String? = null,
        toolCount: Int = 0,
    )
}

// Topic for MCP server status changes
object MCPServerStatusNotifier {
    val TOPIC = Topic.create("MCPServerStatusChanged", MCPServerStatusListener::class.java)
}

class SweepMcpClient : AutoCloseable {
    companion object {
        private val logger = Logger.getInstance(SweepMcpClient::class.java)
    }

    // Initialize MCP client
    private val mcp: Client = Client(clientInfo = Implementation(name = "mcp-client-cli", version = "1.0.0"))

    // List of tools offered by the server
    private lateinit var tools: List<Map<String, String>>

    // Server name for notifications
    private var serverName: String? = null

    // Project reference for notifications
    private var project: Project? = null

    // HttpClient for remote server connections (stored for proper cleanup)
    private var httpClient: HttpClient? = null

    // Process for local server connections (stored for proper cleanup)
    private var localServerProcess: Process? = null

    // Store the command that was run (for error reporting)
    private var lastCommand: String? = null

    // Store stderr output from local server process
    private var stderrOutput: StringBuilder? = null

    // Get the number of available tools
    fun getToolCount(): Int = if (::tools.isInitialized) tools.size else 0

    // Get the MCP client instance
    fun getMcpClient(): Client = mcp

    // Server status tracking
    var status: MCPServerStatus = MCPServerStatus.DISCONNECTED
        private set
    var errorMessage: String? = null
        private set
    var fullErrorOutput: String? = null
        private set

    // Notify status change
    private fun notifyStatusChange(project: Project? = null) {
        if (project?.isDisposed == true) return
        val projectToUse = project ?: this.project
        if (serverName != null && projectToUse != null) {
            ApplicationManager.getApplication().invokeLater {
                projectToUse.messageBus
                    .syncPublisher(MCPServerStatusNotifier.TOPIC)
                    .onServerStatusChanged(serverName!!, status, errorMessage, getToolCount())
            }
        }
    }

    // Set failure state with error message
    fun setFailureState(
        message: String,
        project: Project? = null,
        fullOutput: String? = null,
    ) {
        this.project = project
        status = MCPServerStatus.FAILED
        errorMessage = message
        fullErrorOutput = fullOutput
        notifyStatusChange(project)
    }

    // Set pending auth state for servers requiring OAuth browser authentication
    fun setPendingAuthState(
        message: String = "Requires authentication",
        project: Project? = null,
    ) {
        this.project = project
        status = MCPServerStatus.PENDING_AUTH
        errorMessage = message
        notifyStatusChange(project)
    }

    // Set server name for notifications
    fun setServerName(name: String) {
        serverName = name
    }

    // Connect to the server using MCPServerConfig
    suspend fun connectToServer(
        serverConfig: MCPServerConfig,
        project: Project? = null,
    ) {
        this.project = project
        status = MCPServerStatus.CONNECTING
        errorMessage = null
        notifyStatusChange(project)

        try {
            val transport =
                if (serverConfig.isRemote()) {
                    connectToRemoteServer(serverConfig, serverName)
                } else {
                    connectToLocalServer(serverConfig)
                }

            logger.info("Connecting MCP client to transport...")
            mcp.connect(transport)
            logger.info("MCP client connected, requesting tools list...")

            // Request the list of available tools from the server
            val toolsResult = mcp.listTools()
            logger.info("Tools list received: ${toolsResult.tools?.size ?: 0} tools")
            val mapper = ObjectMapper()
            tools = toolsResult.tools?.map { tool ->
                mapOf(
                    "name" to tool.name,
                    "description" to (tool.description ?: ""),
                    "properties" to tool.inputSchema.properties.toString(), // DO NOT use mapper for this, use toString
                    "required" to mapper.writeValueAsString(tool.inputSchema.required),
                    "type" to tool.inputSchema.type,
                )
            } ?: emptyList()

            status = MCPServerStatus.CONNECTED
            notifyStatusChange(project)
        } catch (e: Exception) {
            status = MCPServerStatus.FAILED
            errorMessage = e.message
            // Build full error output for copying
            fullErrorOutput = buildFullErrorOutput(serverConfig, e)
            notifyStatusChange(project)
            // Log at debug level instead of throwing to avoid red error in IDE
            logger.debug("MCP server connection failed: ${e.message}", e)
            throw e
        }
    }

    /**
     * Build a comprehensive error output that includes the command run, stderr, and exception details.
     */
    private fun buildFullErrorOutput(
        serverConfig: MCPServerConfig,
        exception: Exception,
    ): String {
        val sb = StringBuilder()

        // Add the command that was run
        if (lastCommand != null) {
            sb.appendLine("Command: $lastCommand")
            sb.appendLine()
        } else if (serverConfig.isRemote()) {
            sb.appendLine("URL: ${serverConfig.getRemoteUrl()}")
            sb.appendLine()
        }

        // Add process output if available (stderr and any remaining stdout)
        val processOutput = captureProcessOutput()
        if (processOutput.isNotBlank()) {
            sb.appendLine("Process Output:")
            sb.appendLine(processOutput)
            sb.appendLine()
        }

        // Add exception details
        sb.appendLine("Error: ${exception.message}")
        sb.appendLine()
        sb.appendLine("Stack Trace:")
        sb.appendLine(exception.stackTraceToString())

        return sb.toString()
    }

    /**
     * Capture any process output that has been accumulated (stderr and remaining stdout).
     * Since the connection has failed, we can safely try to read any remaining stdout
     * that might contain useful diagnostic information.
     */
    private fun captureProcessOutput(): String {
        val output = StringBuilder()

        // First add our accumulated stderr
        stderrOutput?.toString()?.let { stderr ->
            if (stderr.isNotBlank()) {
                output.appendLine("[stderr]")
                output.appendLine(stderr.trim())
            }
        }

        // Try to read any remaining stderr from the process
        try {
            localServerProcess?.errorStream?.let { stream ->
                if (stream.available() > 0) {
                    val remaining = stream.bufferedReader().readText()
                    if (remaining.isNotBlank()) {
                        if (output.isEmpty()) output.appendLine("[stderr]")
                        output.appendLine(remaining.trim())
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore - stream may be closed
        }

        // Try to read any remaining stdout from the process
        // Since connection failed, this might contain error messages or partial output
        try {
            localServerProcess?.inputStream?.let { stream ->
                if (stream.available() > 0) {
                    val stdout = stream.bufferedReader().readText()
                    if (stdout.isNotBlank()) {
                        if (output.isNotEmpty()) output.appendLine()
                        output.appendLine("[stdout]")
                        output.appendLine(stdout.trim())
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore - stream may be closed or consumed by transport
        }

        return output.toString().trim()
    }

    private suspend fun connectToRemoteServer(
        serverConfig: MCPServerConfig,
        serverName: String? = null,
    ): Transport {
        val remoteUrl =
            serverConfig.getRemoteUrl()
                ?: throw IllegalArgumentException("URL is required for remote MCP server")
        val name = serverName ?: "default"
        val useStreamableHttp = serverConfig.usesStreamableHttp()

        logger.info(
            "Connecting to remote MCP server '$name' at $remoteUrl (transport: ${if (useStreamableHttp) "StreamableHTTP" else "SSE"})",
        )

        // First, check if we already have a valid OAuth token or if OAuth is explicitly configured
        var accessToken = resolveAccessToken(serverConfig, name, remoteUrl)

        // If no token yet and no explicit OAuth config, try connecting first to see if auth is required
        if (accessToken == null && serverConfig.oauth == null && serverConfig.authorization_token == null) {
            // Check if server requires OAuth by making a probe request
            val discoveredConfig = probeServerForOAuth(remoteUrl)
            if (discoveredConfig != null) {
                logger.info("Server '$name' requires OAuth authentication, starting flow...")
                accessToken = performOAuthAndGetToken(name, discoveredConfig, remoteUrl)
            }
        }

        // Create the appropriate transport based on configuration
        val customHeaders = serverConfig.headers
        return if (useStreamableHttp) {
            createStreamableHttpTransport(remoteUrl, accessToken, customHeaders)
        } else {
            createSseTransport(remoteUrl, accessToken, customHeaders)
        }
    }

    /**
     * Probe the server to check if OAuth is required.
     * Makes a HEAD request and checks for 401 response with WWW-Authenticate header.
     */
    private suspend fun probeServerForOAuth(mcpServerUrl: String): MCPOAuthConfig? =
        try {
            McpOAuthDiscovery.handleOAuthDiscoveryFor401(mcpServerUrl, null)
        } catch (e: Exception) {
            logger.debug("OAuth probe failed for $mcpServerUrl: ${e.message}")
            null
        }

    /**
     * Perform OAuth authentication and return the access token.
     */
    private suspend fun performOAuthAndGetToken(
        serverName: String,
        config: MCPOAuthConfig,
        mcpServerUrl: String,
    ): String {
        val oauthProvider = McpOAuthProvider()
        val token =
            oauthProvider.authenticate(
                serverName = serverName,
                config = config,
                mcpServerUrl = mcpServerUrl,
            )
        return token.accessToken
    }

    /**
     * Create a Streamable HTTP transport (modern MCP transport).
     * This is used by servers like Notion MCP that require the newer transport protocol.
     * Note: SSE plugin is required because StreamableHttpClientTransport uses SSE internally for streaming.
     */
    private fun createStreamableHttpTransport(
        url: String,
        accessToken: String?,
        customHeaders: Map<String, String?>? = null,
    ): Transport {
        logger.info("Creating StreamableHttpClientTransport for URL: $url (token present: ${accessToken != null})")

        // Close any existing HttpClient before creating a new one
        httpClient?.close()
        httpClient =
            HttpClient(CIO) {
                install(SSE)

                // Configure timeouts
                engine {
                    requestTimeout = 120_000 // 2 minutes
                }

                defaultRequest {
                    // Required headers for MCP Streamable HTTP protocol
                    headers {
                        append("Accept", "application/json, text/event-stream")
                        append("Content-Type", "application/json")
                        // Apply custom headers from config (skip null values)
                        customHeaders?.forEach { (key, value) ->
                            value?.let { append(key, it) }
                        }
                        // Apply authorization header if present
                        accessToken?.let { token ->
                            append("Authorization", "Bearer $token")
                        }
                    }
                }
            }

        return StreamableHttpClientTransport(
            client = httpClient!!,
            url = url,
        )
    }

    /**
     * Create the SSE transport with optional authorization (legacy transport).
     * Following the MCP SSE protocol:
     * 1. Client connects to the SSE endpoint (GET request)
     * 2. Server sends back the message endpoint URL via SSE 'endpoint' event
     * 3. Client POSTs messages to that endpoint
     */
    private fun createSseTransport(
        url: String,
        accessToken: String?,
        customHeaders: Map<String, String?>? = null,
    ): Transport {
        // Close any existing HttpClient before creating a new one
        httpClient?.close()
        httpClient =
            HttpClient(CIO) {
                install(SSE)
            }

        return SseClientTransport(
            client = httpClient!!,
            urlString = url, // Pass URL directly to the transport
            reconnectionTime = null,
            requestBuilder = {
                // Apply custom headers from config (skip null values)
                customHeaders?.forEach { (key, value) ->
                    value?.let { headers.append(key, it) }
                }
                // Apply authorization header if present
                accessToken?.let { token ->
                    headers.append("Authorization", "Bearer $token")
                }
            },
        )
    }

    /**
     * Resolve the access token to use for the remote server connection.
     * Handles both static tokens and OAuth authentication.
     */
    private suspend fun resolveAccessToken(
        serverConfig: MCPServerConfig,
        serverName: String,
        mcpServerUrl: String,
    ): String? {
        // If OAuth is explicitly configured, use OAuth flow
        if (serverConfig.oauth != null) {
            val oauthProvider = McpOAuthProvider()

            try {
                // First try to get an existing valid token
                var accessToken = oauthProvider.getValidToken(serverName, serverConfig.oauth)

                // If no valid token, perform full authentication flow
                if (accessToken == null) {
                    logger.info("No valid OAuth token found for MCP server '$serverName', starting authentication...")
                    val token =
                        oauthProvider.authenticate(
                            serverName = serverName,
                            config = serverConfig.oauth,
                            mcpServerUrl = mcpServerUrl,
                        )
                    accessToken = token.accessToken
                }

                return accessToken
            } catch (e: OAuthException) {
                logger.error("OAuth authentication failed for MCP server '$serverName'", e)
                throw IllegalStateException("OAuth authentication failed: ${e.message}", e)
            } catch (e: Exception) {
                logger.warn("Unexpected error during OAuth authentication for MCP server '$serverName'", e)
                throw e
            }
        }

        // Check if we have a stored OAuth token from a previous session
        val tokenStorage = McpOAuthTokenStorage()
        val storedToken = tokenStorage.getValidAccessToken(serverName)
        if (storedToken != null) {
            logger.debug("Using stored OAuth token for MCP server '$serverName'")
            return storedToken
        }

        // Fall back to static authorization token
        return serverConfig.authorization_token
    }

    private suspend fun connectToLocalServer(serverConfig: MCPServerConfig): Transport {
        val commandParts =
            buildList {
                add(requireNotNull(serverConfig.command) { "serverConfig.command must not be null" })
                addAll(serverConfig.args ?: emptyList())
            }

        // Store the command for error reporting
        lastCommand = commandParts.joinToString(" ")

        val pb = ProcessBuilder(commandParts)

        // All blocking operations wrapped in withContext(Dispatchers.IO) to avoid UI freezes
        // This includes loading shell environment (spawns process and waits) and resolving executable
        val resolvedExe =
            withContext(Dispatchers.IO) {
                // 1) Load a correct login/interactive environment (or IDE's when running inside IntelliJ)
                val loadedEnv = loadIdeOrLoginShellEnv().toMutableMap()

                // 2) Merge in any custom environment variables from serverConfig
                serverConfig.env?.let { loadedEnv.putAll(it) }

                // 3) Apply it to the child process environment
                pb.environment().apply {
                    clear()
                    putAll(loadedEnv)
                }

                // 4) Resolve the executable on the intended PATH
                resolveExecutableOnPath(commandParts.first(), loadedEnv["PATH"])
            }

        if (resolvedExe != null) {
            pb.command(mutableListOf(resolvedExe).apply { addAll(commandParts.drop(1)) })
            // Update stored command with resolved path
            lastCommand = (mutableListOf(resolvedExe) + commandParts.drop(1)).joinToString(" ")
        } // else leave as-is; it will work if the JVM's PATH already contains it

        // Store the process reference for cleanup on close()
        // Use withContext(Dispatchers.IO) since pb.start() is a blocking call
        localServerProcess = withContext(Dispatchers.IO) { pb.start() }

        // Start a background thread to capture stderr
        stderrOutput = StringBuilder()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                localServerProcess?.errorStream?.bufferedReader()?.useLines { lines ->
                    lines.forEach { line ->
                        stderrOutput?.appendLine(line)
                    }
                }
            } catch (e: Exception) {
                // Process may have been closed, ignore
            }
        }

        return StdioClientTransport(
            input = localServerProcess!!.inputStream.asSource().buffered(),
            output = localServerProcess!!.outputStream.asSink().buffered(),
        )
    }

    /**
     * Resolve 'exe' against the given PATH (POSIX-style). Returns an absolute path if found and executable.
     */
    private fun resolveExecutableOnPath(
        exe: String,
        path: String?,
    ): String? {
        if (exe.contains(File.separatorChar)) {
            val f = File(exe)
            return if (f.isFile && f.canExecute()) f.absolutePath else null
        }
        if (path.isNullOrEmpty()) return null
        for (dir in path.split(File.pathSeparatorChar)) {
            if (dir.isEmpty()) continue
            val cand = File(dir, exe)
            if (cand.isFile && cand.canExecute()) {
                return cand.absolutePath
            }
        }
        return null
    }

    /**
     * Prefer IntelliJ’s EnvironmentUtil when inside the IDE (macOS PATH is “fixed” there),
     * otherwise load a login+interactive shell environment.
     */
    private fun loadIdeOrLoginShellEnv(): Map<String, String> {
        try {
            val env =
                com.intellij.util.EnvironmentUtil
                    .getEnvironmentMap()
            if (env.isNotEmpty()) return env
        } catch (_: Throwable) {
            // not in IDE or classpath missing
        }
        return loadLoginInteractiveShellEnv()
    }

    /**
     * Ask the user’s shell for a login + interactive environment and parse it.
     */
    private fun loadLoginInteractiveShellEnv(): Map<String, String> {
        val shell = System.getenv("SHELL") ?: "/bin/zsh"
        val shellName = shell.substringAfterLast('/')

        // -l (login) and -i (interactive) approximate a real terminal session
        val args =
            when (shellName) {
                "zsh", "bash" -> arrayOf(shell, "-l", "-i", "-c", "env -0")
                else -> arrayOf(shell, "-l", "-c", "env -0")
            }

        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val bytes = p.inputStream.readAllBytes()
        val exit = p.waitFor()
        if (exit != 0) return System.getenv()

        val text = bytes.toString(StandardCharsets.UTF_8)
        val result = LinkedHashMap<String, String>()
        text.split('\u0000').forEach { entry ->
            if (entry.isNotEmpty()) {
                val idx = entry.indexOf('=')
                if (idx > 0) result[entry.substring(0, idx)] = entry.substring(idx + 1)
            }
        }
        return if (result.isEmpty()) System.getenv() else result
    }

    override fun close() {
        // Update status immediately
        status = MCPServerStatus.DISCONNECTED
        notifyStatusChange()

        // Clear project reference immediately to prevent memory leaks
        project = null

        // Perform cleanup in background thread to avoid blocking EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Use runBlocking here since we're on a background thread
                runBlocking {
                    try {
                        mcp.close()
                    } catch (e: Exception) {
                        logger.warn("Error closing MCP client: ${e.message}")
                    }
                    try {
                        httpClient?.close()
                        httpClient = null
                    } catch (e: Exception) {
                        logger.warn("Error closing HttpClient: ${e.message}")
                    }
                    // Destroy the local server process if it exists
                    localServerProcess?.let { process ->
                        try {
                            process.destroy()
                            // Give it a moment to terminate gracefully
                            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                                process.destroyForcibly()
                            }
                        } catch (e: Exception) {
                            logger.warn("Error destroying local server process: ${e.message}")
                        }
                    }
                    localServerProcess = null
                }
            } catch (e: Exception) {
                logger.warn("Error during MCP client cleanup: ${e.message}")
            }
        }
    }
}
