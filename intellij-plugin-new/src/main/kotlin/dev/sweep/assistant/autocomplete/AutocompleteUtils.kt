package dev.sweep.assistant.autocomplete

import com.intellij.openapi.project.Project
import dev.sweep.assistant.autocomplete.edit.AutocompleteRejectionCache
import kotlinx.coroutines.*

class Debouncer(
    private val delayMillis: () -> Long,
    private val scope: CoroutineScope,
    private val project: Project,
    private val useAdaptiveDelay: Boolean = false,
    private val action: suspend () -> Unit,
) {
    private var job: Job? = null
    private var lastActionTime = System.currentTimeMillis()
    private val maxDebounceMs = 2000.0 // Set to 2 seconds because this is a good threshold

    fun resetTimer() {
        lastActionTime = System.currentTimeMillis()
    }

    fun cancel() = job?.cancel()

    private fun hasPaused(): Boolean {
        val currentTime = System.currentTimeMillis()
        return currentTime - lastActionTime >= getDelayMillis()
    }

    private fun getDelayMillis(): Long {
        val baseDelay = delayMillis()

        if (!useAdaptiveDelay) {
            // Return fixed delay without adaptive behavior
            return baseDelay
        }

        // Adaptive delay: increases exponentially as rejections enter
        val numRejections = AutocompleteRejectionCache.getInstance(project = project).getNumRejectionsInLastTimespan(timespanMs = 10_000L)
        val exponentialFactor = 1.6 // Adjust this factor as needed
        val adjustedDelay = baseDelay * (1 + exponentialFactor * numRejections)
        return adjustedDelay.coerceIn(100.0, maxDebounceMs).toLong()
    }

    fun schedule() {
        job?.cancel()
        val currentDelayMillis = getDelayMillis()
        job =
            scope.launch {
                delay(currentDelayMillis)
                if (hasPaused() && isActive) {
                    action()
                }
            }
    }
}
