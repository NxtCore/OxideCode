package dev.sweep.assistant.listener

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.*
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

class VirtualFileSystemWatcher(
    private val project: Project,
    parentDisposable: Disposable,
    private val onEvent: (kind: WatchEvent.Kind<*>, filePath: Path) -> Unit,
) : Disposable {
    private val eventQueue = ConcurrentLinkedQueue<Pair<WatchEvent.Kind<*>, Path>>()
    private val eventProcessor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "VFS-Event-Processor-${project.name}")
        }

    private val fileListener =
        object : AsyncFileListener {
            override fun prepareChange(events: List<VFileEvent>): AsyncFileListener.ChangeApplier? {
                // Collect events that we care about
                val relevantEvents = mutableListOf<Pair<VFileEvent, EventType>>()

                for (event in events) {
                    when (event) {
                        is VFileCreateEvent -> {
                            relevantEvents.add(event to EventType.CREATE)
                        }
                        is VFileDeleteEvent -> {
                            relevantEvents.add(event to EventType.DELETE)
                        }
                        is VFileContentChangeEvent -> {
                            relevantEvents.add(event to EventType.MODIFY)
                        }
                        is VFileMoveEvent -> {
                            relevantEvents.add(event to EventType.MOVE)
                        }
                        is VFilePropertyChangeEvent -> {
                            if (event.isRename) {
                                relevantEvents.add(event to EventType.RENAME)
                            }
                        }
                        is VFileCopyEvent -> {
                            relevantEvents.add(event to EventType.COPY)
                        }
                    }
                }

                if (relevantEvents.isEmpty()) return null

                return object : AsyncFileListener.ChangeApplier {
                    override fun afterVfsChange() {
                        // Use invokeLater to escape the VFS callback context before spawning a pooled thread.
                        // Calling executeOnPooledThread directly here causes EDT freezes because Thread.start()
                        // can block while holding VFS locks during transferred write actions.
                        ApplicationManager.getApplication().invokeLater {
                            if (!project.isDisposed) {
                                ApplicationManager.getApplication().executeOnPooledThread {
                                    processEvents(relevantEvents)
                                }
                            }
                        }
                    }
                }
            }
        }

    private enum class EventType {
        CREATE,
        DELETE,
        MODIFY,
        MOVE,
        RENAME,
        COPY,
    }

    init {
        Disposer.register(parentDisposable, this)
    }

    fun startWatching() {
        if (project.isDisposed) return

        // Register the async file listener
        VirtualFileManager.getInstance().addAsyncFileListener(fileListener, this)
    }

    private fun processEvents(events: List<Pair<VFileEvent, EventType>>) {
        val fileIndex = ProjectFileIndex.getInstance(project)

        for ((event, type) in events) {
            when (type) {
                EventType.DELETE -> {
                    // For deletes, we need to use the path since the file is gone
                    val path =
                        when (event) {
                            is VFileDeleteEvent -> Path.of(event.path)
                            is VFileMoveEvent -> Path.of(event.oldPath)
                            is VFilePropertyChangeEvent -> {
                                // For rename, construct old path
                                val parent = event.file.parent?.path ?: continue
                                Path.of(parent, event.oldValue as String)
                            }
                            else -> continue
                        }

                    // For delete events, we can't check if it was in content (file is gone)
                    // So we check if the path is under project base path
                    if (isPathUnderProject(path)) {
                        eventQueue.offer(StandardWatchEventKinds.ENTRY_DELETE to path)
                    }
                }

                EventType.MOVE -> {
                    val moveEvent = event as VFileMoveEvent
                    // Handle old location deletion
                    val oldPath = Path.of(moveEvent.oldPath)
                    if (isPathUnderProject(oldPath)) {
                        eventQueue.offer(StandardWatchEventKinds.ENTRY_DELETE to oldPath)
                    }

                    // Handle new location creation
                    if (shouldProcessFile(moveEvent.file, fileIndex)) {
                        val newPath = moveEvent.file.toNioPath()
                        eventQueue.offer(StandardWatchEventKinds.ENTRY_CREATE to newPath)
                    }
                }

                EventType.RENAME -> {
                    val renameEvent = event as VFilePropertyChangeEvent
                    val parent = renameEvent.file.parent?.path ?: continue
                    val oldPath = Path.of(parent, renameEvent.oldValue as String)

                    // Handle old name deletion
                    if (isPathUnderProject(oldPath)) {
                        eventQueue.offer(StandardWatchEventKinds.ENTRY_DELETE to oldPath)
                    }

                    // Handle new name creation
                    if (shouldProcessFile(renameEvent.file, fileIndex)) {
                        val newPath = renameEvent.file.toNioPath()
                        eventQueue.offer(StandardWatchEventKinds.ENTRY_CREATE to newPath)
                    }
                }

                EventType.CREATE -> {
                    val file =
                        when (event) {
                            is VFileCreateEvent -> event.file
                            else -> continue
                        } ?: continue

                    if (shouldProcessFile(file, fileIndex)) {
                        val path = file.toNioPath()
                        eventQueue.offer(StandardWatchEventKinds.ENTRY_CREATE to path)
                    }
                }

                EventType.COPY -> {
                    val copyEvent = event as VFileCopyEvent
                    val newFile = copyEvent.findCreatedFile() ?: continue

                    if (shouldProcessFile(newFile, fileIndex)) {
                        val path = newFile.toNioPath()
                        eventQueue.offer(StandardWatchEventKinds.ENTRY_CREATE to path)
                    }
                }

                EventType.MODIFY -> {
                    val file = (event as VFileContentChangeEvent).file
                    if (shouldProcessFile(file, fileIndex)) {
                        val path = file.toNioPath()
                        eventQueue.offer(StandardWatchEventKinds.ENTRY_MODIFY to path)
                    }
                }
            }
        }

        // Process queued events
        processQueuedEvents()
    }

    /**
     * Checks if a file should be processed based on IntelliJ's content indexing rules.
     * Returns true if the file would be included in ProjectFileIndex.iterateContent().
     */
    private fun shouldProcessFile(
        file: VirtualFile?,
        fileIndex: ProjectFileIndex,
    ): Boolean {
        if (file == null || !file.isValid || file.isDirectory) return false
        if (!file.isInLocalFileSystem) return false

        // Check if file is in project content and not excluded/ignored
        // This is exactly what iterateContent would include
        return ReadAction.compute<Boolean, RuntimeException> {
            if (project.isDisposed) return@compute false

            // isInContent checks if file is under content roots and not excluded/ignored
            // This matches what iterateContent iterates over
            fileIndex.isInContent(file) && !fileIndex.isExcluded(file)
        }
    }

    /**
     * Check if a path is under the project base directory.
     * Used for delete events where we can't check the VirtualFile anymore.
     */
    private fun isPathUnderProject(path: Path): Boolean {
        val projectBasePath = project.basePath ?: return false
        val projectPath = Path.of(projectBasePath)

        return try {
            path.startsWith(projectPath)
        } catch (e: Exception) {
            false
        }
    }

    private fun processQueuedEvents() {
        eventProcessor.execute {
            while (!project.isDisposed) {
                val event = eventQueue.poll() ?: break
                val (kind, path) = event

                try {
                    onEvent(kind, path)
                } catch (e: Exception) {
                    // Log error but continue processing other events
                }
            }
        }
    }

    fun stopWatching() {
        // AsyncFileListener is automatically unregistered when the disposable is disposed
    }

    override fun dispose() {
        stopWatching()
        eventQueue.clear()
        eventProcessor.shutdown()
    }
}

// Extension function to find the created file from a copy event
private fun VFileCopyEvent.findCreatedFile(): VirtualFile? = newParent.findChild(newChildName)
