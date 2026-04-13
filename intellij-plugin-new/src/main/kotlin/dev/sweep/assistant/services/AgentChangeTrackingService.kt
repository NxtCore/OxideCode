package dev.sweep.assistant.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.sweep.assistant.components.SweepComponent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Service that tracks changes made by agent tools (StringReplaceTool, CreateFileTool) in real-time.
 * This provides efficient detection of when the last change was made by the agent vs the user.
 */
@Service(Service.Level.PROJECT)
class AgentChangeTrackingService(
    private val project: Project,
) {
    companion object {
        fun getInstance(project: Project): AgentChangeTrackingService = project.getService(AgentChangeTrackingService::class.java)

        // Keep changes for 10 minutes to prevent memory leaks
        private const val CHANGE_RETENTION_PERIOD = 10 * 60 * 1000L

        // Consider changes recent if within 30 seconds
        private const val RECENT_CHANGE_THRESHOLD = 30 * 1000L
        private const val AGENT_CHANGE_DELAY_THRESHOLD = 200L
    }

    private data class AgentChange(
        val timestamp: Long,
        val toolName: String,
        val filePath: String? = null,
    )

    // Store agent changes with timestamps - using thread-safe CopyOnWriteArrayList
    private val agentChanges = CopyOnWriteArrayList<AgentChange>()

    // Cache for performance - using AtomicLong for thread-safe updates
    private val lastAgentChangeTimestamp = AtomicLong(0L)

    /**
     * Records when an agent tool makes a change
     */
    fun recordAgentChange(
        toolName: String,
        filePath: String? = null,
    ) {
        val timestamp = System.currentTimeMillis()

        // Add the new change
        agentChanges.add(AgentChange(timestamp, toolName, filePath))
        lastAgentChangeTimestamp.set(timestamp)

        // Keep only recent changes to prevent memory leaks
        val cutoffTime = timestamp - CHANGE_RETENTION_PERIOD
        agentChanges.removeIf { it.timestamp < cutoffTime }
    }

    /**
     * Checks if the last change was made by the agent
     * @param lastUserEditTimestamp The timestamp of the most recent user edit
     * @return true if the agent made a change more recently than the user edit
     */
    fun wasLastChangeByAgent(lastUserEditTimestamp: Long): Boolean {
        // Only consider agent changes if we're in agent mode
        if (SweepComponent.getMode(project) != "Agent") {
            return false
        }

        return lastAgentChangeTimestamp.get() - lastUserEditTimestamp > -AGENT_CHANGE_DELAY_THRESHOLD
    }
}
