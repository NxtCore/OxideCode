package dev.sweep.assistant.utils

import com.intellij.openapi.application.ApplicationManager
import javax.swing.Timer

class SmoothAnimationTimer(
    private val startValue: Float = 0f,
    private val endValue: Float = 1f,
    private val durationMs: Int = 500,
    private val intervalMs: Int = 16, // ~60fps
    private val onUpdate: (Float) -> Unit,
    private val onComplete: (() -> Unit)? = null,
) {
    private var timer: Timer? = null
    private var startTime: Long = 0
    private var isRunning = false

    init {
        start()
    }

    fun start() {
        if (isRunning) return

        isRunning = true
        startTime = System.currentTimeMillis()

        timer =
            Timer(intervalMs) {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)

                // Use smooth easing function (ease-out)
                val easedProgress = 1f - (1f - progress) * (1f - progress)
                val currentValue = startValue + (endValue - startValue) * easedProgress

                ApplicationManager.getApplication().invokeLater {
                    onUpdate(currentValue)
                }

                if (progress >= 1f) {
                    stop()
                    ApplicationManager.getApplication().invokeLater {
                        onComplete?.invoke()
                    }
                }
            }.apply {
                isRepeats = true
                start()
            }
    }

    fun stop() {
        timer?.stop()
        timer = null
        isRunning = false
    }

    fun isRunning(): Boolean = isRunning
}
