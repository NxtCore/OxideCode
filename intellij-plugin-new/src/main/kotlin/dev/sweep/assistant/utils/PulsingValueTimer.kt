package dev.sweep.assistant.utils

import javax.swing.Timer

class PulsingValueTimer(
    private val minValue: Float = 0.3f,
    private val maxValue: Float = 1.0f,
    private val step: Float = 0.05f,
    private val intervalMs: Int = 50,
    private val onUpdate: (Float) -> Unit,
) {
    private var value = maxValue
    private var decreasing = true
    private var timer: Timer? = null

    init {
        start()
    }

    fun start() {
        timer =
            Timer(intervalMs) {
                if (decreasing) {
                    value -= step
                    if (value <= minValue) decreasing = false
                } else {
                    value += step
                    if (value >= maxValue) decreasing = true
                }
                onUpdate(value)
            }.apply {
                isRepeats = true
                start()
            }
    }

    fun stop() {
        timer?.stop()
        timer = null
    }
}
