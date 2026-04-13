package dev.sweep.assistant.utils

import com.intellij.openapi.Disposable
import javax.swing.Timer

/**
 * Interface for easing functions that transform animation progress.
 */
fun interface Easing {
    /**
     * Transform the linear progress (0f to 1f) with the easing function.
     */
    fun ease(progress: Float): Float

    companion object {
        /** Linear interpolation (no easing) */
        val Linear = Easing { it }

        /** Ease in (slow start, accelerating) */
        val EaseIn = Easing { it * it }

        /** Ease out (fast start, decelerating) */
        val EaseOut = Easing { it * (2 - it) }

        /** Ease in-out (slow start and end, fast middle) */
        val EaseInOut =
            Easing { t ->
                if (t < 0.5f) 2 * t * t else -1 + (4 - 2 * t) * t
            }

        /** Bounce effect at the end */
        val Bounce =
            Easing { t ->
                when {
                    t < 1 / 2.75f -> 7.5625f * t * t
                    t < 2 / 2.75f -> 7.5625f * (t - 1.5f / 2.75f) * (t - 1.5f / 2.75f) + 0.75f
                    t < 2.5f / 2.75f -> 7.5625f * (t - 2.25f / 2.75f) * (t - 2.25f / 2.75f) + 0.9375f
                    else -> 7.5625f * (t - 2.625f / 2.75f) * (t - 2.625f / 2.75f) + 0.984375f
                }
            }
    }
}

/**
 * A generic animation timer that smoothly interpolates between values over time.
 * Configure once at initialization, then simply call animateTo() at runtime.
 */
class AnimationTimer(
    private val durationMs: Int = 150,
    private val steps: Int = 15,
    private val easing: Easing = Easing.Linear,
    private val onUpdate: (Float) -> Unit,
) : Disposable {
    private var timer: Timer? = null
    private var currentValue = 0f
    private var targetValue = 0f
    private var startValue = 0f
    private var currentStep = 0

    val isAnimating: Boolean get() = timer?.isRunning == true
    val progress: Float get() = currentValue

    /**
     * Animate to the target value using the configured parameters.
     *
     * @param target The target value to animate to (0f to 1f)
     */
    fun animateTo(target: Float) {
        // Don't animate if we're already at the target
        if (currentValue == target) {
            return
        }

        // Stop any existing animation
        stop()

        // Set up new animation
        this.startValue = currentValue
        this.targetValue = target
        this.currentStep = 0

        val stepDelayMs = durationMs / steps

        timer = Timer(stepDelayMs) { animationStep() }
        timer?.start()
    }

    /**
     * Stop the current animation immediately.
     */
    fun stop() {
        timer?.stop()
        timer = null
    }

    private fun animationStep() {
        currentStep++

        // Calculate progress (0f to 1f)
        val stepProgress = currentStep.toFloat() / steps.toFloat()

        // Apply easing function
        val easedProgress = easing.ease(stepProgress)

        // Interpolate between start and target
        currentValue = startValue + (targetValue - startValue) * easedProgress

        // Call update callback
        onUpdate(currentValue)

        // Check if animation is complete
        if (currentStep >= steps) {
            currentValue = targetValue // Ensure we end exactly at target
            stop()
            onUpdate(currentValue)
        }
    }

    override fun dispose() {
        stop()
    }
}
