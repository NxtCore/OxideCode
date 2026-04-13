package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class AutocompleteSnoozeService(
    private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): AutocompleteSnoozeService = project.getService(AutocompleteSnoozeService::class.java)

        // Snooze durations in milliseconds
        const val SNOOZE_5_MINUTES = 5 * 60 * 1000L
        const val SNOOZE_15_MINUTES = 15 * 60 * 1000L
        const val SNOOZE_30_MINUTES = 30 * 60 * 1000L
        const val SNOOZE_1_HOUR = 60 * 60 * 1000L
        const val SNOOZE_2_HOURS = 2 * 60 * 60 * 1000L
    }

    private val isSnoozed = AtomicBoolean(false)
    private val snoozeEndTime = AtomicLong(0)
    private val snoozeServiceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + snoozeServiceJob)
    private var snoozeJob: Job? = null

    private val listeners = mutableListOf<() -> Unit>()

    fun isAutocompleteSnooze(): Boolean {
        val currentTime = System.currentTimeMillis()
        if (isSnoozed.get() && currentTime >= snoozeEndTime.get()) {
            // Snooze period has ended
            unsnooze()
            return false
        }
        return isSnoozed.get()
    }

    fun snoozeAutocomplete(durationMs: Long) {
        val endTime = System.currentTimeMillis() + durationMs
        snoozeEndTime.set(endTime)
        isSnoozed.set(true)

        // Cancel any existing snooze job
        snoozeJob?.cancel()

        // Start a new job to automatically unsnooze
        snoozeJob =
            scope.launch {
                delay(durationMs)
                unsnooze()
            }

        notifyListeners()
    }

    fun unsnooze() {
        isSnoozed.set(false)
        snoozeEndTime.set(0)
        snoozeJob?.cancel()
        snoozeJob = null
        notifyListeners()
    }

    fun getRemainingSnoozeTime(): Long {
        if (!isSnoozed.get()) return 0
        val remaining = snoozeEndTime.get() - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0
    }

    fun addSnoozeStateListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeSnoozeStateListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }

    fun formatRemainingTime(): String {
        val remaining = getRemainingSnoozeTime()
        if (remaining <= 0) return ""

        val minutes = remaining / (60 * 1000)
        val hours = minutes / 60

        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m"
            else -> "<1m"
        }
    }

    override fun dispose() {
        // Cancel any running snooze job
        snoozeJob?.cancel()
        snoozeJob = null

        // Cancel the coroutine scope to prevent memory leaks
        snoozeServiceJob.cancel()

        // Clear listeners
        listeners.clear()

        // Reset state
        isSnoozed.set(false)
        snoozeEndTime.set(0)
    }
}
