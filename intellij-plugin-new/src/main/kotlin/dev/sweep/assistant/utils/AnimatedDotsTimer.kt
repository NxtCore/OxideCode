package dev.sweep.assistant.utils

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import javax.swing.Timer

class AnimatedDotsTimer(
    private val updateCallback: (String) -> Unit,
    parentDisposable: Disposable,
    private val intervalMs: Int = 500,
) : Disposable {
    private var timer: Timer? = null
    private var dotCount = 1
    private val maxDots = 5

    init {
        Disposer.register(parentDisposable, this)
    }

    fun start() {
        stop()
        dotCount = 1
        timer =
            Timer(intervalMs) {
                val dots = ".".repeat(dotCount)
                updateCallback(dots)
                dotCount = if (dotCount >= maxDots) 1 else dotCount + 1
            }
        timer?.start()
    }

    fun stop() {
        timer?.stop()
        timer = null
    }

    override fun dispose() {
        stop()
    }
}
