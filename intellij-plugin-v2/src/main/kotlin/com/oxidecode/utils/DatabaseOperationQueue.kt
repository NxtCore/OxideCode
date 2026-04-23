package com.oxidecode.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class DatabaseOperationQueue(
    private val project: Project,
) {
    private val logger = Logger.getInstance(DatabaseOperationQueue::class.java)
    private val entityDbQueue = LinkedBlockingQueue<() -> Unit>()
    private val fileDbQueue = LinkedBlockingQueue<() -> Unit>()

    enum class QueueType { FILE, ENTITY }

    companion object {
        fun getInstance(project: Project): DatabaseOperationQueue = project.getService(DatabaseOperationQueue::class.java)
    }

    init {
        // Start workers to process each queue
        startQueueWorker(entityDbQueue, "EntityDB-Worker")
        startQueueWorker(fileDbQueue, "FileDB-Worker")
    }

    private fun startQueueWorker(
        queue: LinkedBlockingQueue<() -> Unit>,
        name: String,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.currentThread().name = name
            while (!project.isDisposed) {
                try {
                    val operation = queue.take() // Blocks until an operation is available
                    operation()
                } catch (e: Exception) {
                    println("Exception occurred while processing $e")
                }
            }
        }
    }

    fun enqueueEntityOperation(operation: () -> Unit) {
        entityDbQueue.offer(operation)
    }

    fun enqueueFileOperation(operation: () -> Unit) {
        fileDbQueue.offer(operation)
    }

    fun <T> executeDbOperationWithTimeout(
        queueOperation: (CompletableFuture<T>, AtomicBoolean) -> Unit,
        timeoutMs: Long = 500,
        errorMsg: String = "Error executing database operation",
        timeoutMsg: String = "Timeout executing database operation",
        defaultValue: T,
    ): T {
        val resultFuture = CompletableFuture<T>()
        val canceled = AtomicBoolean(false)

        queueOperation(resultFuture, canceled)

        try {
            return resultFuture.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            canceled.set(true)
            logger.info(timeoutMsg)
            return defaultValue
        } catch (e: Exception) {
            logger.warn("$errorMsg: ${e.message}", e)
            return defaultValue
        }
    }

    private fun isQueueBusy(queueType: QueueType): Boolean =
        when (queueType) {
            QueueType.FILE -> fileDbQueue.isNotEmpty()
            QueueType.ENTITY -> entityDbQueue.isNotEmpty()
        }

    fun <T> executeDbOperationSkipIfBusy(
        queueType: QueueType,
        queueOperation: (CompletableFuture<T>, AtomicBoolean) -> Unit,
        timeoutMs: Long = 500,
        errorMsg: String = "Error executing database operation",
        timeoutMsg: String = "Timeout executing database operation",
        defaultValue: T,
    ): T {
        // Skip operation if queue is already busy
        if (isQueueBusy(queueType)) {
            logger.debug("${queueType.name} queue busy, skipping operation")
            return defaultValue
        }

        // Proceed with normal operation if queue appears empty
        return executeDbOperationWithTimeout(
            queueOperation,
            timeoutMs,
            errorMsg,
            timeoutMsg,
            defaultValue,
        )
    }

    fun clearQueue(queueType: QueueType) {
        val queue = getQueue(queueType)
        val sizeBefore = queue.size
        if (sizeBefore > 0) {
            logger.info("Clearing ${queueType.name} queue. Removing $sizeBefore pending operations.")
            queue.clear()
        } else {
            logger.debug("${queueType.name} queue is already empty.")
        }
    }

    private fun getQueue(queueType: QueueType): LinkedBlockingQueue<() -> Unit> =
        when (queueType) {
            QueueType.FILE -> fileDbQueue
            QueueType.ENTITY -> entityDbQueue
        }
}
