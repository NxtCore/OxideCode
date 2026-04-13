package dev.sweep.assistant.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import dev.sweep.assistant.agent.SweepAgentManager
import dev.sweep.assistant.agent.tools.BashToolService
import dev.sweep.assistant.components.MessagesComponent
import dev.sweep.assistant.controllers.Stream
import dev.sweep.assistant.data.Message
import dev.sweep.assistant.services.SweepSession
import dev.sweep.assistant.services.SweepSessionManager
import dev.sweep.assistant.settings.SweepSettings
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.text.DecimalFormat
import javax.swing.JComponent

/**
 * Action to estimate memory usage of all tabs/sessions.
 * This is only available in developer mode for debugging purposes.
 *
 * Computes comprehensive memory estimates for:
 * - SweepSession wrapper
 * - SessionMessageList (messages, content sizes)
 * - SweepAgentSession (tool call state)
 * - SweepSessionComponent (UI component tree)
 * - SweepSessionUI (UI state container)
 * - MessagesComponent LazyMessageSlots (realized vs unrealized)
 * - Stream instances
 * - BashToolService executors
 *
 * Shows the difference between active and non-active tabs.
 */
class EstimateTabMemoryAction : AnAction() {
    companion object {
        const val ACTION_ID = "dev.sweep.assistant.actions.EstimateTabMemoryAction"

        private val sizeFormat = DecimalFormat("#,##0.00")

        // Base object overhead in bytes (approximate JVM overhead per object)
        private const val OBJECT_OVERHEAD = 16L
        private const val REFERENCE_SIZE = 8L
        private const val STRING_OVERHEAD = 40L // String object + char array header
        private const val LIST_OVERHEAD = 48L
        private const val MAP_OVERHEAD = 64L
        private const val ATOMIC_OVERHEAD = 24L
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val isDeveloperModeEnabled = SweepSettings.getInstance().developerModeOn
        val project = event.project
        event.presentation.isEnabledAndVisible = isDeveloperModeEnabled && project != null && !project.isDisposed
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        val sessionManager = SweepSessionManager.getInstance(project)
        val agentManager = SweepAgentManager.getInstance(project)
        val bashToolService = BashToolService.getInstance(project)
        val messagesComponent = MessagesComponent.getInstance(project)

        val allSessions = sessionManager.getAllSessions()
        val activeSession = sessionManager.getActiveSession()

        val report =
            buildString {
                appendLine("╔══════════════════════════════════════════════════════════════════╗")
                appendLine("║           TAB MEMORY ESTIMATION REPORT                           ║")
                appendLine("╠══════════════════════════════════════════════════════════════════╣")
                appendLine()
                appendLine("Total Sessions/Tabs: ${allSessions.size}")
                appendLine("Active Session: ${activeSession?.sessionId?.id ?: "None"}")
                appendLine()

                // Global components (shared across tabs)
                appendLine("═══════════════════════════════════════════════════════════════════")
                appendLine("GLOBAL/SHARED COMPONENTS (project-level singletons)")
                appendLine("═══════════════════════════════════════════════════════════════════")

                val globalEstimate = estimateGlobalComponents(messagesComponent, project)
                appendLine(globalEstimate.report)
                appendLine("Total Global Memory: ${formatBytes(globalEstimate.totalBytes)}")
                appendLine()

                // Stream instances
                appendLine("Stream Instances (by conversationId):")
                val streamInstances = Stream.instances
                if (streamInstances.isEmpty()) {
                    appendLine("  (none)")
                } else {
                    streamInstances.forEach { (convId, stream) ->
                        val isStreaming = stream.isStreaming
                        appendLine("  - $convId: streaming=$isStreaming (~${formatBytes(estimateStreamMemory())})")
                    }
                }
                appendLine()

                // Per-session breakdown
                appendLine("═══════════════════════════════════════════════════════════════════")
                appendLine("PER-SESSION BREAKDOWN")
                appendLine("═══════════════════════════════════════════════════════════════════")

                var totalActiveMemory = 0L
                var totalInactiveMemory = 0L

                allSessions.forEachIndexed { index, session ->
                    val isActive = session.sessionId == activeSession?.sessionId
                    val sessionEstimate = estimateSessionMemory(session, agentManager, bashToolService, messagesComponent, isActive)

                    appendLine()
                    appendLine("───────────────────────────────────────────────────────────────────")
                    appendLine("Session ${index + 1}: ${session.sessionId.id.take(8)}...")
                    appendLine("  Status: ${if (isActive) "★ ACTIVE ★" else "INACTIVE"}")
                    appendLine("  ConversationId: ${session.conversationId.take(8)}...")
                    appendLine("───────────────────────────────────────────────────────────────────")
                    appendLine(sessionEstimate.report)
                    appendLine("  TOTAL SESSION MEMORY: ${formatBytes(sessionEstimate.totalBytes)}")

                    if (isActive) {
                        totalActiveMemory += sessionEstimate.totalBytes
                    } else {
                        totalInactiveMemory += sessionEstimate.totalBytes
                    }
                }

                // Summary
                appendLine()
                appendLine("═══════════════════════════════════════════════════════════════════")
                appendLine("SUMMARY")
                appendLine("═══════════════════════════════════════════════════════════════════")
                appendLine()
                appendLine("Active Tab Memory:     ${formatBytes(totalActiveMemory)}")
                appendLine("Inactive Tabs Memory:  ${formatBytes(totalInactiveMemory)}")
                appendLine("Global Components:     ${formatBytes(globalEstimate.totalBytes)}")
                appendLine("─────────────────────────────────────────────────────────────")
                appendLine("TOTAL ESTIMATED:       ${formatBytes(totalActiveMemory + totalInactiveMemory + globalEstimate.totalBytes)}")
                appendLine()

                if (allSessions.size > 1) {
                    val avgInactivePerTab = if (allSessions.size > 1) totalInactiveMemory / (allSessions.size - 1) else 0L
                    appendLine("Average per inactive tab: ${formatBytes(avgInactivePerTab)}")
                    appendLine()
                    appendLine("POTENTIAL SAVINGS WITH LAZY TAB UI:")
                    appendLine("  If we dispose UI for inactive tabs, we could save:")
                    appendLine("  - UI Components: ~80-90% of inactive tab memory")
                    appendLine("  - Estimated savings: ${formatBytes((totalInactiveMemory * 0.85).toLong())}")
                }

                appendLine()
                appendLine("╚══════════════════════════════════════════════════════════════════╝")
            }

        // Copy to clipboard
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(report), null)

        // Also print to console for immediate viewing
        println(report)
    }

    private data class MemoryEstimate(
        val totalBytes: Long,
        val report: String,
    )

    private fun estimateGlobalComponents(
        messagesComponent: MessagesComponent,
        project: com.intellij.openapi.project.Project,
    ): MemoryEstimate {
        val report = StringBuilder()
        var total = 0L

        // MessagesComponent singleton
        val messagesPanelSize = estimateSwingComponentTree(messagesComponent.component)
        report.appendLine("  MessagesComponent (singleton):")
        report.appendLine("    - messagesPanel UI tree: ${formatBytes(messagesPanelSize)}")
        total += messagesPanelSize

        // Count LazyMessageSlots
        val slots =
            messagesComponent.messagesPanel.components
                .filterIsInstance<MessagesComponent.LazyMessageSlot>()
        val realizedSlots = slots.count { it.isRealized }
        val unrealizedSlots = slots.size - realizedSlots

        report.appendLine("    - LazyMessageSlots: ${slots.size} total")
        report.appendLine("      - Realized: $realizedSlots")
        report.appendLine("      - Unrealized (placeholder): $unrealizedSlots")

        // Estimate realized slot memory
        var realizedSlotMemory = 0L
        slots.filter { it.isRealized }.forEach { slot ->
            realizedSlotMemory += estimateSwingComponentTree(slot)
        }
        report.appendLine("    - Realized slots UI memory: ${formatBytes(realizedSlotMemory)}")
        total += realizedSlotMemory

        // ChatComponent singleton (estimate)
        val chatComponentSize = 50_000L // ~50KB estimate for ChatComponent
        report.appendLine("  ChatComponent (singleton): ~${formatBytes(chatComponentSize)}")
        total += chatComponentSize

        return MemoryEstimate(total, report.toString())
    }

    private fun estimateSessionMemory(
        session: SweepSession,
        agentManager: SweepAgentManager,
        bashToolService: BashToolService,
        messagesComponent: MessagesComponent,
        isActive: Boolean,
    ): MemoryEstimate {
        val report = StringBuilder()
        var total = 0L

        // 1. SweepSession wrapper
        val sessionWrapperSize = OBJECT_OVERHEAD + (REFERENCE_SIZE * 6) // ~64 bytes
        report.appendLine("  SweepSession wrapper: ${formatBytes(sessionWrapperSize)}")
        total += sessionWrapperSize

        // 2. SessionMessageList
        val messageList = session.messageList
        val messages = messageList.snapshot()
        val messageListSize = estimateMessageListMemory(messages)
        report.appendLine("  SessionMessageList:")
        report.appendLine("    - Message count: ${messages.size}")
        report.appendLine("    - Total content size: ${formatBytes(messageListSize.contentBytes)}")
        report.appendLine("    - Mentioned files data: ${formatBytes(messageListSize.mentionedFilesBytes)}")
        report.appendLine("    - Annotations/metadata: ${formatBytes(messageListSize.annotationsBytes)}")
        report.appendLine("    - Total: ${formatBytes(messageListSize.totalBytes)}")
        total += messageListSize.totalBytes

        // 3. SweepSessionComponent (UI)
        val uiComponent = session.uiComponent
        if (uiComponent != null) {
            val uiComponentSize = estimateSwingComponentTree(uiComponent.component)
            report.appendLine("  SweepSessionComponent (UI):")
            report.appendLine("    - Component tree: ${formatBytes(uiComponentSize)}")
            report.appendLine("    - Listeners/adapters: ~${formatBytes(5_000)}")
            total += uiComponentSize + 5_000
        } else {
            report.appendLine("  SweepSessionComponent: (null - not created)")
        }

        // 4. SweepSessionUI
        val sessionUI = session.sessionUI
        if (sessionUI != null) {
            var sessionUISize = OBJECT_OVERHEAD + (REFERENCE_SIZE * 5)

            // TokenUsageIndicator
            val tokenIndicator = sessionUI.tokenUsageIndicatorComponent
            if (tokenIndicator != null) {
                val indicatorSize = estimateSwingComponentTree(tokenIndicator)
                report.appendLine("  SweepSessionUI:")
                report.appendLine("    - TokenUsageIndicator: ${formatBytes(indicatorSize)}")
                sessionUISize += indicatorSize
            } else {
                report.appendLine("  SweepSessionUI:")
                report.appendLine("    - TokenUsageIndicator: (not created)")
            }

            // FilesInContextState
            sessionUISize += 2_000 // Estimate for FilesInContextState
            report.appendLine("    - FilesInContextState: ~${formatBytes(2_000)}")
            total += sessionUISize
        } else {
            report.appendLine("  SweepSessionUI: (null)")
        }

        // 5. SweepAgentSession
        val agentSession = agentManager.getSession(session.conversationId)
        if (agentSession != null) {
            val agentSessionSize = estimateAgentSessionMemory(agentSession)
            report.appendLine("  SweepAgentSession:")
            report.appendLine("    - Pending tool calls: ${agentSessionSize.pendingCount}")
            report.appendLine("    - Completed tool calls: ${agentSessionSize.completedCount}")
            report.appendLine("    - Jobs map entries: ${agentSessionSize.jobsCount}")
            report.appendLine("    - Total: ${formatBytes(agentSessionSize.totalBytes)}")
            total += agentSessionSize.totalBytes
        } else {
            report.appendLine("  SweepAgentSession: (none)")
        }

        // 6. Stream instance
        val stream = Stream.instances[session.conversationId]
        if (stream != null) {
            val streamSize = estimateStreamMemory()
            report.appendLine("  Stream instance: ${formatBytes(streamSize)}")
            report.appendLine("    - isStreaming: ${stream.isStreaming}")
            total += streamSize
        } else {
            report.appendLine("  Stream instance: (none)")
        }

        // 7. Background Bash Executors (estimated based on BashToolService)
        val bashExecutorSize = 50_000L // ~50KB per executor (shell process, buffers)
        report.appendLine("  Background Bash Executors: ~${formatBytes(bashExecutorSize)}")
        total += bashExecutorSize

        // 8. Tab Content reference
        if (session.content != null) {
            report.appendLine("  Tab Content: (bound)")
        } else {
            report.appendLine("  Tab Content: (not bound)")
        }

        // Active vs Inactive indicator
        if (isActive) {
            report.appendLine()
            report.appendLine("  [★ This tab owns the realized MessagesComponent slots]")
        }

        return MemoryEstimate(total, report.toString())
    }

    private data class MessageListMemoryEstimate(
        val totalBytes: Long,
        val contentBytes: Long,
        val mentionedFilesBytes: Long,
        val annotationsBytes: Long,
    )

    private fun estimateMessageListMemory(messages: List<Message>): MessageListMemoryEstimate {
        var contentBytes = 0L
        var mentionedFilesBytes = 0L
        var annotationsBytes = 0L

        messages.forEach { message ->
            // Content string
            contentBytes += STRING_OVERHEAD + (message.content.length * 2) // UTF-16 chars

            // Mentioned files
            message.mentionedFiles.forEach { fileInfo ->
                mentionedFilesBytes += OBJECT_OVERHEAD
                mentionedFilesBytes += STRING_OVERHEAD + (fileInfo.relativePath.length * 2)
                mentionedFilesBytes += STRING_OVERHEAD + (fileInfo.name.length * 2)
                fileInfo.codeSnippet?.let {
                    mentionedFilesBytes += STRING_OVERHEAD + (it.length * 2)
                }
            }

            // Stored file contents metadata (FullFileContentStore stores hashes, not actual content)
            message.mentionedFilesStoredContents?.forEach { storedContent ->
                mentionedFilesBytes += OBJECT_OVERHEAD
                mentionedFilesBytes += STRING_OVERHEAD + (storedContent.name.length * 2)
                mentionedFilesBytes += STRING_OVERHEAD + (storedContent.relativePath.length * 2)
                storedContent.codeSnippet?.let { mentionedFilesBytes += STRING_OVERHEAD + (it.length * 2) }
            }

            // Annotations
            message.annotations?.let { ann ->
                annotationsBytes += OBJECT_OVERHEAD * 3 // Annotations + nested objects
                ann.toolCalls?.forEach { toolCall ->
                    annotationsBytes += OBJECT_OVERHEAD
                    annotationsBytes += STRING_OVERHEAD + (toolCall.toolName.length * 2)
                    annotationsBytes += STRING_OVERHEAD + (toolCall.toolCallId.length * 2)
                    annotationsBytes += STRING_OVERHEAD + (toolCall.rawText.length * 2)
                    // toolParameters map
                    annotationsBytes += MAP_OVERHEAD
                    toolCall.toolParameters.forEach { (k, v) ->
                        annotationsBytes += STRING_OVERHEAD + (k.length * 2) + (v.length * 2)
                    }
                }
                ann.completedToolCalls?.forEach { completedCall ->
                    annotationsBytes += OBJECT_OVERHEAD
                    annotationsBytes += STRING_OVERHEAD + (completedCall.toolCallId.length * 2)
                    annotationsBytes += STRING_OVERHEAD + (completedCall.toolName.length * 2)
                    annotationsBytes += STRING_OVERHEAD + (completedCall.resultString.length * 2)
                }
            }

            // Images
            message.images.forEach { image ->
                // Base64 encoded images can be very large
                mentionedFilesBytes += OBJECT_OVERHEAD
                mentionedFilesBytes += STRING_OVERHEAD + (image.file_type.length * 2)
                image.url?.let { mentionedFilesBytes += STRING_OVERHEAD + (it.length * 2) }
                image.base64?.let { mentionedFilesBytes += STRING_OVERHEAD + (it.length * 2) }
                image.filePath?.let { mentionedFilesBytes += STRING_OVERHEAD + (it.length * 2) }
            }

            // Applied code block records
            message.appliedCodeBlockRecords?.forEach { record ->
                annotationsBytes += OBJECT_OVERHEAD + 500 // Estimate per record
            }
        }

        val total = LIST_OVERHEAD + (messages.size * OBJECT_OVERHEAD) + contentBytes + mentionedFilesBytes + annotationsBytes

        return MessageListMemoryEstimate(total, contentBytes, mentionedFilesBytes, annotationsBytes)
    }

    private data class AgentSessionMemoryEstimate(
        val totalBytes: Long,
        val pendingCount: Int,
        val completedCount: Int,
        val jobsCount: Int,
    )

    private fun estimateAgentSessionMemory(agentSession: dev.sweep.assistant.agent.SweepAgentSession): AgentSessionMemoryEstimate {
        var total = OBJECT_OVERHEAD + (REFERENCE_SIZE * 10)

        // These are internal fields, estimate based on typical usage
        val pendingCount = agentSession.pendingToolCalls.size
        val completedCount = agentSession.completedToolCalls.size

        // Pending tool calls
        total += LIST_OVERHEAD + (pendingCount * 500) // ~500 bytes per tool call

        // Completed tool calls (can contain large results)
        total += LIST_OVERHEAD
        agentSession.completedToolCalls.forEach { call ->
            total += OBJECT_OVERHEAD
            total += STRING_OVERHEAD + (call.resultString.length * 2)
        }

        // Jobs map (estimate)
        val jobsCount = 0 // Can't easily access jobsById
        total += MAP_OVERHEAD

        // Locks and atomics
        total += ATOMIC_OVERHEAD * 3

        return AgentSessionMemoryEstimate(total, pendingCount, completedCount, jobsCount)
    }

    private fun estimateStreamMemory(): Long {
        // Stream contains:
        // - Project reference
        // - ObjectMapper (shared, but ~10KB)
        // - Coroutine state
        // - Buffer strings
        var total = OBJECT_OVERHEAD + (REFERENCE_SIZE * 5)
        total += 10_000 // ObjectMapper
        total += 5_000 // Coroutine state
        total += 10_000 // Various buffers
        return total
    }

    private fun estimateSwingComponentTree(component: JComponent): Long {
        var total = 0L

        // Base component overhead
        total += 500 // JComponent base ~500 bytes

        // Recursively estimate children
        for (i in 0 until component.componentCount) {
            val child = component.getComponent(i)
            if (child is JComponent) {
                total += estimateSwingComponentTree(child)
            } else {
                total += 200 // Non-JComponent child
            }
        }

        // Additional overhead for specific component types
        val className = component.javaClass.simpleName
        when {
            className.contains("Editor") -> total += 50_000 // Editors are heavy
            className.contains("Terminal") -> total += 30_000 // Terminal widgets
            className.contains("Scroll") -> total += 1_000
            className.contains("Panel") -> total += 200
            className.contains("Label") -> total += 100
            className.contains("Button") -> total += 300
            className.contains("Text") -> total += 2_000
            className.contains("Markdown") -> total += 10_000 // MarkdownDisplay
        }

        return total
    }

    private fun formatBytes(bytes: Long): String =
        when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${sizeFormat.format(bytes / 1024.0)} KB"
            else -> "${sizeFormat.format(bytes / (1024.0 * 1024.0))} MB"
        }
}
