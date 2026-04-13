package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.sweep.assistant.controllers.ResponseFinishedListener
import dev.sweep.assistant.controllers.Stream
import dev.sweep.assistant.settings.SweepSettings
import java.io.BufferedInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

/**
 * Service that provides OS-level feedback for Sweep agent completion events.
 * This service listens for agent completion events and provides audio/visual feedback
 * to inform users when the agent has finished processing their requests.
 */
@Service(Service.Level.PROJECT)
class OSNotificationService(
    private val project: Project,
) : Disposable {
    private val logger = Logger.getInstance(OSNotificationService::class.java)

    // Pre-loaded clip for efficient reuse
    private var completionClip: Clip? = null

    companion object {
        fun getInstance(project: Project): OSNotificationService = project.getService(OSNotificationService::class.java)
    }

    init {
        // Pre-load the completion sound for efficient reuse
        loadCompletionSound()

        // Subscribe to response finished events
        project.messageBus.connect(SweepProjectService.getInstance(project)).subscribe(
            Stream.RESPONSE_FINISHED_TOPIC,
            object : ResponseFinishedListener {
                override fun onResponseFinished(conversationId: String) {
                    // Check if notification is enabled in settings
                    val settings = SweepSettings.getInstance()
                    if (settings.playNotificationOnStreamEnd) {
                        showCompletionNotification()
                    }
                }
            },
        )
    }

    /**
     * Provides audio and visual feedback when the agent completes processing.
     * On macOS, this will make the dock icon bounce and play a completion sound.
     */
    private fun showCompletionNotification() {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                try {
                    // Play completion sound
                    playCompletionSound()
                } catch (e: Exception) {
                    logger.warn("Failed to provide agent completion feedback", e)
                }
            }
        }
    }

    /**
     * Loads the completion sound into memory once for efficient reuse.
     * This should be called during initialization to avoid latency on first play.
     */
    private fun loadCompletionSound() {
        try {
            val soundStream = javaClass.getResourceAsStream("/sounds/completion.aiff")
            if (soundStream != null) {
                soundStream.use { stream ->
                    val bufferedStream = BufferedInputStream(stream)
                    bufferedStream.use { buffered ->
                        val audioInputStream = AudioSystem.getAudioInputStream(buffered)
                        audioInputStream.use { audio ->
                            val clip = AudioSystem.getClip()
                            clip.open(audio)

                            // Add listener to reset clip position when playback finishes
                            clip.addLineListener { event ->
                                if (event.type == LineEvent.Type.STOP) {
                                    clip.framePosition = 0 // Reset to beginning for next play
                                }
                            }

                            completionClip = clip
                            logger.debug("Successfully loaded completion sound")
                        }
                    }
                }
            } else {
                logger.debug("Bundled sound file not found")
            }
        } catch (e: Exception) {
            logger.debug("Failed to load completion sound: ${e.message}")
        }
    }

    /**
     * Plays a pleasant completion sound to indicate the agent has finished.
     * Uses the pre-loaded clip for minimal latency.
     */
    private fun playCompletionSound() {
        try {
            completionClip?.let { clip ->
                if (!clip.isRunning) {
                    clip.start()
                    logger.debug("Played completion sound")
                } else {
                    logger.debug("Completion sound already playing, skipping")
                }
            } ?: run {
                logger.debug("Completion sound not loaded")
            }
        } catch (e: Exception) {
            logger.debug("Failed to play completion sound: ${e.message}")
        }
    }

    override fun dispose() {
        // Close the completion clip to release audio resources
        completionClip?.close()
        completionClip = null
    }
}
