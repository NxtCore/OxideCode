package dev.sweep.assistant.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.sweep.assistant.agent.tools.BashToolService
import dev.sweep.assistant.agent.tools.ToolType
import dev.sweep.assistant.components.ChatComponent
import dev.sweep.assistant.components.MessagesComponent
import dev.sweep.assistant.components.MessagesComponent.LazyMessageSlot
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.controllers.Stream
import dev.sweep.assistant.data.*
import dev.sweep.assistant.services.AppliedCodeBlockManager
import dev.sweep.assistant.services.SessionMessageList
import dev.sweep.assistant.services.StreamStateService
import dev.sweep.assistant.services.SweepSessionManager
import dev.sweep.assistant.tracking.EventType
import dev.sweep.assistant.tracking.TelemetryService
import dev.sweep.assistant.views.MarkdownDisplay
import kotlinx.coroutines.delay
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Per-session agent state for Sweep chat.
 *
 * This class holds all the tool execution state for a single chat session:
 * - Pending and completed tool calls
 * - Job tracking (jobsById)
 * - String replace gating state
 * - Cancellation flags
 *
 * Each SweepSession should own one instance of this class.
 * The SweepAgentManager creates and manages these instances.
 *
 * Thread-safety: All operations are thread-safe using locks and concurrent data structures.
 *
 * @param project The IntelliJ project
 * @param conversationId The conversation ID this session is bound to
 * @param executor Shared executor for tool execution (provided by SweepAgentManager)
 */
class SweepAgentSession(
    private val project: Project,
    val conversationId: String,
    private val executor: ExecutorService,
) : Disposable {
    private val logger = Logger.getInstance(SweepAgentSession::class.java)

    /**
     * Gets the SessionMessageList for this agent session's conversation.
     * Falls back to null if no session exists (should not happen in normal operation).
     */
    private fun getMessageList(): SessionMessageList? =
        SweepSessionManager.getInstance(project).getSessionByConversationId(conversationId)?.messageList

    /**
     * Checks if this session is still the active conversation.
     * Used to guard against cross-session contamination.
     */
    private fun isActiveConversation(): Boolean =
        SweepSessionManager.getInstance(project).getActiveSession()?.conversationId == conversationId

    // Lists to track tool calls
    val pendingToolCalls: MutableList<ToolCall> = mutableListOf()
    val completedToolCalls: MutableList<CompletedToolCall> = mutableListOf()
    private val lock = ReentrantLock()

    // Job tracking for incremental tool call execution
    private val jobsById = ConcurrentHashMap<String, ToolJob>()

    // Session state flags
    private val sessionHasRejected = AtomicBoolean(false)
    private val sessionHasRunningJobs = AtomicInteger(0)

    @Volatile
    private var toolExecutionCancelledViaStopButton = false

    // Record of user decisions that might arrive before gate is ready
    private val stickyStrReplaceDecisions = ConcurrentHashMap<String, Boolean>() // toolCallId -> accepted

    // Gate session for str_replace coordination
    private data class StrReplaceGate(
        val conversationId: String?,
        val targetAssistantIndex: Int,
        val pendingIds: MutableSet<String>,
        val decisions: MutableMap<String, Boolean> = mutableMapOf(),
        val anyRejected: AtomicBoolean = AtomicBoolean(false),
    )

    private val gateLock = ReentrantLock()
    private val gateCondition = gateLock.newCondition()

    @Volatile
    private var activeStrGate: StrReplaceGate? = null

    // Single-threaded drain for completed tool calls
    private data class CompletedQueueItem(
        val call: CompletedToolCall,
        val onUIUpdateComplete: (() -> Unit)? = null,
        val alreadyRecorded: Boolean = false,
    )

    private val completedQueue = ConcurrentLinkedQueue<CompletedQueueItem>()
    private val drainingCompleted = AtomicBoolean(false)

    override fun dispose() {
        // Cancel all running jobs
        stopToolExecution()

        lock.withLock {
            pendingToolCalls.clear()
            completedToolCalls.clear()
        }

        jobsById.clear()
        completedQueue.clear()

        gateLock.withLock {
            stickyStrReplaceDecisions.clear()
            activeStrGate = null
        }
    }

    /**
     * Enqueues completed tool calls for processing and UI update.
     */
    fun enqueueCompletedToolCalls(
        calls: List<CompletedToolCall>,
        onUIUpdateComplete: (() -> Unit)? = null,
        alreadyRecorded: Boolean = false,
    ) {
        if (calls.isEmpty()) return

        // Update session rejection flag for any rejected calls
        if (calls.any { it.isRejected }) {
            sessionHasRejected.set(true)
        }

        logger.info(
            "[SweepAgentSession.enqueue] enqueue count=${calls.size} alreadyRecorded=$alreadyRecorded ids=${
                calls.joinToString(",") { it.toolCallId }
            } queueSizeBefore=${completedQueue.size}",
        )

        // Enqueue all; only attach the callback to the last to preserve single-callback semantics
        calls.forEachIndexed { idx, c ->
            val cb = if (idx == calls.lastIndex) onUIUpdateComplete else null
            completedQueue.add(CompletedQueueItem(c, cb, alreadyRecorded = alreadyRecorded))
        }

        drainCompletedQueue()
    }

    private fun drainCompletedQueue() {
        if (!drainingCompleted.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val batch = mutableListOf<CompletedQueueItem>()
                while (true) {
                    val item = completedQueue.poll() ?: break
                    batch.add(item)
                }
                if (batch.isEmpty()) return@executeOnPooledThread

                // Log the tools being drained
                logger.info(
                    "[SweepAgentSession.drain] draining ${batch.size} completed tools: ${
                        batch.joinToString(", ") { "${it.call.toolName}(${it.call.toolCallId})" }
                    }",
                )

                // For items not yet recorded, update legacy lists and remove from pending
                val newCompletions = batch.filter { !it.alreadyRecorded }.map { it.call }
                if (newCompletions.isNotEmpty()) moveToolCallsToCompleted(newCompletions)

                // Use this session's message list (session-scoped)
                val messageList = getMessageList() ?: return@executeOnPooledThread
                val currentConv = conversationId

                // Group by target message index
                val messageUpdates = mutableMapOf<Int, MutableList<CompletedToolCall>>()
                batch.forEach { item ->
                    val job = jobsById[item.call.toolCallId]
                    if (job != null && job.messageIndex >= 0) {
                        if (job.conversationId == null || job.conversationId == currentConv) {
                            messageUpdates.getOrPut(job.messageIndex) { mutableListOf() }.add(item.call)
                        } else {
                            println("Skipping completed tool call for different conversation: ${item.call.toolCallId}")
                        }
                    }
                }

                // Apply atomic updates to MessageList
                val updatedByIndex = mutableMapOf<Int, Message>()
                messageUpdates.forEach { (index, completedCalls) ->
                    val updated =
                        messageList.updateAt(index) { current ->
                            current.copy(
                                annotations =
                                    (current.annotations ?: Annotations()).copy(
                                        completedToolCalls =
                                            ((current.annotations?.completedToolCalls ?: emptyList()) + completedCalls)
                                                .toMutableList(),
                                    ),
                            )
                        }
                    if (updated != null) updatedByIndex[index] = updated
                }

                // Decouple lifecycle from UI: mark jobs finished/failed immediately after MessageList is updated
                val nowTs = System.currentTimeMillis()
                batch.forEach { item ->
                    jobsById[item.call.toolCallId]?.let { j ->
                        j.status = if (item.call.status) ToolCallStatus.FINISHED else ToolCallStatus.FAILED
                        j.completedAt = nowTs
                    }
                }

                // Run UI updates ASAP on EDT, then callbacks after the last update
                val callbacks = batch.mapNotNull { it.onUIUpdateComplete }
                var remaining = updatedByIndex.size

                if (remaining == 0) {
                    callbacks.forEach { it.invoke() }
                } else {
                    updatedByIndex.forEach { (index, updatedMessage) ->
                        ApplicationManager.getApplication().invokeLater {
                            if (!project.isDisposed) {
                                // Guard: Only apply UI updates if this session is still active
                                // This prevents tool call UI from appearing on the wrong tab when switching sessions
                                if (!isActiveConversation()) {
                                    // Still count this as completed for callback purposes
                                    val newRemaining = --remaining
                                    if (newRemaining == 0) {
                                        callbacks.forEach { it.invoke() }
                                    }
                                    return@invokeLater
                                }

                                val messagesPanel = MessagesComponent.getInstance(project).messagesPanel
                                val atIndex = messagesPanel.components.getOrNull(index)
                                val markdownDisplay: MarkdownDisplay? =
                                    when (atIndex) {
                                        is MarkdownDisplay -> atIndex
                                        is LazyMessageSlot -> atIndex.realizedComponent as? MarkdownDisplay
                                        else -> null
                                    }

                                if (markdownDisplay != null) {
                                    // Use fresh session MessageList state to avoid stale snapshots
                                    val freshMessage = getMessageList()?.getOrNull(index) ?: updatedMessage
                                    val oldCompletedCount =
                                        markdownDisplay.message
                                            .annotations
                                            ?.completedToolCalls
                                            ?.size ?: 0
                                    markdownDisplay.message = freshMessage
                                } else {
                                    // Slot not realized yet or component not a MarkdownDisplay; skip without warning
                                    logger.info(
                                        "[SweepAgentSession.drain][EDT] skip index=$index (slot not realized or component not MarkdownDisplay)",
                                    )
                                }
                            } else {
                                logger.warn("EDT Update[$index]: Project disposed, skipping update")
                            }

                            val newRemaining = --remaining

                            if (newRemaining == 0) {
                                callbacks.forEach { it.invoke() }
                            }
                        }
                    }
                }
            } finally {
                drainingCompleted.set(false)
                // If more arrived while we were draining, process them
                if (completedQueue.isNotEmpty()) drainCompletedQueue()
            }
        }
    }

    /**
     * Stops all tool execution for this session.
     */
    fun stopToolExecution() {
        lock.withLock {
            toolExecutionCancelledViaStopButton = true
            pendingToolCalls.clear()
        }

        // Get bash tool service for stopping running bash commands
        val bashToolService = BashToolService.getInstance(project)

        // Cancel all running/scheduled futures and stop bash commands
        jobsById.values.forEach { job ->
            if (job.status == ToolCallStatus.QUEUED || job.status == ToolCallStatus.IN_PROGRESS) {
                // Stop any running bash/powershell commands (this is safe to call for non-bash tools)
                bashToolService.stopExecution(job.toolCall.toolCallId)
                job.future?.cancel(true)
                job.status = ToolCallStatus.CANCELED
            }
        }
        jobsById.clear()

        // Notify that tool execution has stopped
        StreamStateService.getInstance(project).notify(false, false, false, conversationId)
    }

    /**
     * Checks if tool execution was cancelled via stop button.
     */
    fun isToolExecutionCancelled(): Boolean = toolExecutionCancelledViaStopButton

    /**
     * Records a user decision (accept/reject) for a string replace tool call.
     */
    fun recordStringReplaceDecision(
        toolCallId: String,
        accepted: Boolean,
    ) {
        // Always remember the latest decision
        stickyStrReplaceDecisions[toolCallId] = accepted

        gateLock.withLock {
            val gate = activeStrGate
            if (gate != null && gate.conversationId == conversationId && gate.pendingIds.contains(toolCallId)) {
                if (!gate.decisions.containsKey(toolCallId)) {
                    gate.decisions[toolCallId] = accepted
                    if (!accepted) gate.anyRejected.set(true)
                    gateCondition.signalAll()
                }
            }
        }
    }

    /**
     * Adds tool calls to the pending list.
     */
    fun addToolCalls(toolCalls: List<ToolCall>) {
        lock.withLock {
            pendingToolCalls.addAll(toolCalls)
        }
    }

    /**
     * Moves completed tool calls from pending to completed list.
     */
    fun moveToolCallsToCompleted(completedToolCalls: List<CompletedToolCall>) {
        if (completedToolCalls.isEmpty()) return

        lock.withLock {
            this.completedToolCalls.addAll(completedToolCalls)
            // Remove from pending list if they exist there
            val completedIds = completedToolCalls.map { it.toolCallId }.toSet()
            pendingToolCalls.removeIf { it.toolCallId in completedIds }
        }
    }

    /**
     * Merges tool call updates (for incremental streaming).
     */
    private fun mergeToolCall(
        existing: ToolCall?,
        update: ToolCall,
    ): ToolCall =
        existing?.copy(
            toolName = update.toolName,
            toolParameters = update.toolParameters.ifEmpty { existing.toolParameters },
            rawText = update.rawText,
            fullyFormed = update.fullyFormed,
            isMcp = update.isMcp,
            mcpProperties = update.mcpProperties.ifEmpty { existing.mcpProperties },
        ) ?: update

    /**
     * Ingests tool calls incrementally from streaming.
     * This method is called as tool calls are streamed from the backend.
     */
    fun ingestToolCalls(toolCalls: List<ToolCall>) {
        logger.info("[SweepAgentSession.ingestToolCalls] start count=${toolCalls.size} cid=$conversationId")

        // Reset cancellation flag at the start of a new ingestion
        toolExecutionCancelledViaStopButton = false

        var firstScheduled = false

        for (tc in toolCalls) {
            // Resolve the target assistant message index at ingestion time (session-scoped)
            val messageList = getMessageList() ?: continue
            var boundIndex =
                messageList.indexOfLast { message ->
                    message.role == MessageRole.ASSISTANT &&
                        message.annotations?.toolCalls?.any { it.toolCallId == tc.toolCallId } == true
                }
            if (boundIndex == -1) {
                // Fallback to last assistant message or last message
                val lastAssistant = messageList.indexOfLastRole(MessageRole.ASSISTANT)
                boundIndex = if (lastAssistant >= 0) lastAssistant else (messageList.size() - 1).coerceAtLeast(0)
            }

            jobsById.compute(tc.toolCallId) { _, existing ->
                // Only update tool calls that are still queued to avoid races
                val updated =
                    when {
                        existing == null -> ToolJob(tc, boundIndex, conversationId)
                        existing.status == ToolCallStatus.QUEUED ->
                            existing.copy(
                                toolCall = mergeToolCall(existing.toolCall, tc),
                            )
                        else -> existing // Ignore updates while running/finished/cancelled
                    }

                // Schedule if ready, not already scheduled, and not cancelled
                if (updated.status == ToolCallStatus.QUEUED &&
                    updated.toolCall.fullyFormed &&
                    updated.future == null &&
                    !isToolExecutionCancelled()
                ) {
                    scheduleIfReady(updated)
                    if (!firstScheduled) {
                        firstScheduled = true
                        // Show stop button and cursor on first scheduled job
                        StreamStateService.getInstance(project).notify(false, true, true, conversationId)
                    }
                }

                updated
            }
        }
    }

    /**
     * Schedules a tool call for execution if ready.
     */
    private fun scheduleIfReady(job: ToolJob) {
        if (job.status == ToolCallStatus.QUEUED && job.toolCall.fullyFormed) {
            job.status = ToolCallStatus.IN_PROGRESS
            job.startedAt = System.currentTimeMillis()
            logger.info(
                "[SweepAgentSession.tools] start toolCallId=${job.toolCall.toolCallId} tool=${job.toolCall.toolName} msgIndex=${job.messageIndex} cid=$conversationId",
            )
            sessionHasRunningJobs.incrementAndGet()

            job.future =
                executor
                    .submit<CompletedToolCall?> {
                        try {
                            executeToolCall(job.toolCall)
                        } catch (t: Throwable) {
                            // Convert exception into a CompletedToolCall failure
                            CompletedToolCall(
                                toolCallId = job.toolCall.toolCallId,
                                toolName = job.toolCall.toolName,
                                resultString = "Error: ${t.message ?: t.javaClass.simpleName}",
                                status = false,
                                isMcp = job.toolCall.isMcp,
                                mcpProperties = job.toolCall.mcpProperties,
                            )
                        }
                    }.also { future ->
                        // Completion handler
                        ApplicationManager.getApplication().executeOnPooledThread {
                            var completed: CompletedToolCall? = null
                            var wasCancelled = false
                            try {
                                completed = future.get() // blocking wait for just this job
                            } catch (_: CancellationException) {
                                wasCancelled = true
                            } catch (t: Throwable) {
                                logger.warn(
                                    "[SweepAgentSession.tools] error executing toolCallId=${job.toolCall.toolCallId}: ${t.message}",
                                    t,
                                )
                            }

                            val now = System.currentTimeMillis()
                            val dur = (now - (job.startedAt ?: now))

                            // Handle null results from unimplemented tools as failures
                            if (completed == null && !wasCancelled) {
                                completed =
                                    CompletedToolCall(
                                        toolCallId = job.toolCall.toolCallId,
                                        toolName = job.toolCall.toolName,
                                        resultString = "Error: ${job.toolCall.toolName} is not implemented",
                                        status = false,
                                        isMcp = job.toolCall.isMcp,
                                        mcpProperties = job.toolCall.mcpProperties,
                                    )
                            }

                            if (completed != null) {
                                logger.info(
                                    "[SweepAgentSession.tools] finish toolCallId=${job.toolCall.toolCallId} tool=${job.toolCall.toolName} status=${if (completed.status) "SUCCESS" else "FAIL"} rejected=${completed.isRejected} durMs=$dur",
                                )
                                // Update session rejection flag
                                if (completed.isRejected) {
                                    sessionHasRejected.set(true)
                                }

                                // Enqueue for single-threaded drain
                                enqueueCompletedToolCalls(
                                    listOf(completed),
                                    onUIUpdateComplete = null,
                                    alreadyRecorded = false,
                                )
                            } else {
                                // Handle cancellation case immediately since no UI update needed
                                jobsById[job.toolCall.toolCallId]?.let { currentJob ->
                                    currentJob.status = ToolCallStatus.CANCELED
                                    currentJob.completedAt = System.currentTimeMillis()
                                }
                            }

                            sessionHasRunningJobs.decrementAndGet()
                        }
                    }
        }
    }

    /**
     * Waits for all tool calls to complete and then requests follow-up.
     */
    suspend fun awaitToolCalls(message: Message) {
        logger.info("[SweepAgentSession.awaitToolCalls] start cid=$conversationId")

        // Get expected tool call IDs from the message, or fall back to jobsById keys
        // This handles cases like prompt_crunching where the backend sends tool calls
        // via ingestToolCalls but doesn't include them in message.annotations.toolCalls
        val messageToolCallIds =
            message.annotations
                ?.toolCalls
                ?.map { it.toolCallId }
                ?.toSet() ?: emptySet()

        val expectedToolCallIds =
            messageToolCallIds.ifEmpty {
                // Fall back to jobsById keys if message annotations don't have tool calls
                val jobIds = jobsById.keys.toSet()
                if (jobIds.isNotEmpty()) {
                    logger.info(
                        "[SweepAgentSession.awaitToolCalls] using jobsById keys as expected tool calls: ${
                            jobIds.joinToString(
                                ",",
                            )
                        }",
                    )
                }
                jobIds
            }

        // Wait for all tool jobs to complete based on their status
        while (true) {
            delay(100)
            // Get the set of completed tool call IDs from jobsById
            val completedToolCallIds =
                jobsById.values
                    .filter { job ->
                        job.status in setOf(ToolCallStatus.FINISHED, ToolCallStatus.FAILED, ToolCallStatus.CANCELED)
                    }.map { it.toolCall.toolCallId }
                    .toSet()

            if (jobsById.isEmpty()) {
                logger.info("[SweepAgentSession.awaitToolCalls] finishing as there are no tool calls anymore")
                break
            }

            // Check if we have expected tool calls
            if (expectedToolCallIds.isNotEmpty()) {
                // Break only when every expected tool call ID exists in the completed set
                if (expectedToolCallIds.all { it in completedToolCallIds }) {
                    logger.info(
                        "[SweepAgentSession.awaitToolCalls] all expected tool calls completed: ${
                            expectedToolCallIds.joinToString(",")
                        }",
                    )
                    break
                }
            } else {
                logger.info("[SweepAgentSession.awaitToolCalls] no expected tool calls")
                break
            }
        }

        // Check if cancelled
        if (isToolExecutionCancelled()) {
            logger.info("[SweepAgentSession.awaitToolCalls] cancelled via stop button")
            StreamStateService.getInstance(project).notify(false, false, false, conversationId)
            return
        }

        // Gate str_replace calls if enabled in configuration
        val gateEnabled = SweepConfig.getInstance(project).isGateStringReplaceInChatMode()
        logger.info("[SweepAgentSession.awaitToolCalls] gateCheck enabled=$gateEnabled")

        if (gateEnabled) {
            // Use this session's message list for gate checks
            val ml = getMessageList() ?: return
            val targetIndex = ml.indexOfLastRole(MessageRole.ASSISTANT)
            val gateMessage = ml.getOrNull(targetIndex)

            // Collect str_replace tool calls requiring review for this message
            val gateIds =
                gateMessage
                    ?.annotations
                    ?.completedToolCalls
                    ?.filter {
                        it.toolName in setOf("str_replace", "apply_patch", "multi_str_replace", "create_file") &&
                            it.mcpProperties["requires_review"] == "true"
                    }?.map { it.toolCallId }
                    ?.toMutableSet() ?: mutableSetOf()

            if (gateIds.isNotEmpty()) {
                logger.info("[SweepAgentSession.awaitToolCalls] gate init pendingIds=${gateIds.size} targetIndex=$targetIndex")
                // Initialize gate (pre-seed with sticky decisions)
                gateLock.withLock {
                    val decisions = mutableMapOf<String, Boolean>()
                    gateIds.forEach { id -> stickyStrReplaceDecisions[id]?.let { decisions[id] = it } }
                    val gate = StrReplaceGate(conversationId, targetIndex, gateIds, decisions.toMutableMap())
                    if (decisions.values.any { !it }) gate.anyRejected.set(true)
                    activeStrGate = gate
                }

                // Wait until all decided, any rejected, or cancelled/conversation changed
                while (true) {
                    if (isToolExecutionCancelled()) break
                    // Check if the active conversation changed (session was switched)
                    if (!isActiveConversation()) break
                    if (AppliedCodeBlockManager.getInstance(project).getTotalAppliedBlocksCount() == 0) break

                    val done =
                        gateLock.withLock {
                            val gate = activeStrGate
                            if (gate == null) {
                                true
                            } else if (gate.anyRejected.get()) {
                                true
                            } else {
                                gateCondition.await(200, TimeUnit.MILLISECONDS)
                                false
                            }
                        }
                    if (done) break
                }

                // Snapshot result & clear gate
                val anyRejected =
                    gateLock.withLock {
                        val rejected = activeStrGate?.anyRejected?.get() == true
                        activeStrGate = null
                        rejected
                    }

                if (isToolExecutionCancelled() || !isActiveConversation()) {
                    logger.info("[SweepAgentSession.awaitToolCalls] gate aborted (cancelled or conversation changed)")
                    StreamStateService.getInstance(project).notify(false, false, false, conversationId)
                    return
                }

                if (anyRejected) {
                    logger.info("[SweepAgentSession.awaitToolCalls] gate result: REJECTED -> follow-up")
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            ChatComponent.getInstance(project).textField.setPlaceholder("Follow up - Tell Sweep what to do instead.")
                        }
                    }
                    StreamStateService.getInstance(project).notify(false, false, false, conversationId)

                    // Fire response finished event since stream is ending due to rejection
                    project.messageBus
                        .syncPublisher(Stream.RESPONSE_FINISHED_TOPIC)
                        .onResponseFinished(conversationId = conversationId)

                    TelemetryService.getInstance().sendUsageEvent(
                        eventType = EventType.MESSAGE_TERMINATED_BY_USER,
                        eventProperties = mapOf("uniqueChatID" to (getMessageList()?.uniqueChatID ?: "")),
                    )
                    TelemetryService.getInstance().reportUserStoppingChatEvent(project)

                    toolExecutionCancelledViaStopButton = false
                    logger.info("[SweepAgentSession.awaitToolCalls] end (rejected)")
                    return
                }
                // else: fall through to normal continuation below
                logger.info("[SweepAgentSession.awaitToolCalls] gate result: ACCEPTED -> continue")
            }
        }

        // Check for rejections - only continue if there were tool calls that completed successfully
        // If there were no tool calls at all, the response is complete and we shouldn't queue a follow-up
        val rejected = sessionHasRejected.get()
        sessionHasRejected.set(false)

        if (expectedToolCallIds.isEmpty()) {
            // No tool calls at all - response is complete, nothing more to do
            StreamStateService.getInstance(project).notify(false, false, false, conversationId)
        } else if (!rejected) {
            // Add empty assistant message in anticipation of follow-up (session-scoped)
            getMessageList()?.add(Message(MessageRole.ASSISTANT, ""))
            // Pass explicit conversationId to ensure UI updates use this session's message list,
            // not whichever session happens to be active (prevents race condition when switching tabs)
            MessagesComponent.getInstance(project).update(
                MessagesComponent.UpdateType.CONTINUE_AGENT,
                conversationId = conversationId,
            )
            StreamStateService.getInstance(project).notify(false, false, true, conversationId)
        } else {
            logger.info("[SweepAgentSession.awaitToolCalls] rejected path: prompt for follow-up")
            // Set placeholder for follow-up on EDT
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    ChatComponent.getInstance(project).textField.setPlaceholder("Follow up - Tell Sweep what to do instead.")
                }
            }
            StreamStateService.getInstance(project).notify(false, false, false, conversationId)

            // Fire response finished event since stream is ending due to rejection
            project.messageBus
                .syncPublisher(Stream.RESPONSE_FINISHED_TOPIC)
                .onResponseFinished(conversationId = conversationId)

            TelemetryService.getInstance().sendUsageEvent(
                eventType = EventType.MESSAGE_TERMINATED_BY_USER,
                eventProperties = mapOf("uniqueChatID" to (getMessageList()?.uniqueChatID ?: "")),
            )
            TelemetryService.getInstance().reportUserStoppingChatEvent(project)
        }

        // Reset session state
        toolExecutionCancelledViaStopButton = false
        logger.info("[SweepAgentSession.awaitToolCalls] end")
    }

    /**
     * Executes a single tool call.
     */
    private fun executeToolCall(toolCall: ToolCall): CompletedToolCall? =
        ToolType.createToolInstance(toolCall.toolName, toolCall.isMcp)?.execute(toolCall, project, conversationId)
            ?: run {
                logger.warn("Tool ${toolCall.toolName} not implemented")
                null
            }
}
