package dev.sweep.assistant.listener

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.sweep.assistant.utils.osBasePath
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import kotlin.concurrent.thread

private typealias FileEventListener = (kind: WatchEvent.Kind<*>, filePath: Path) -> Unit

class FileSystemWatcher(
    project: Project,
    private val gitIgnoredDirectories: Set<String>,
    private val onEvent: FileEventListener,
) {
    private val watchService = FileSystems.getDefault().newWatchService()
    private var watchThread: Thread? = null
    private val directoryToWatch = Paths.get(project.osBasePath!!)
    private val logger = Logger.getInstance(FileSystemWatcher::class.java)
    private val watchKeys = mutableMapOf<WatchKey, Path>()

    init {
        registerAll(directoryToWatch)
    }

    private fun registerAll(start: Path) {
        Files.walkFileTree(
            start,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    val relativePathString = directoryToWatch.relativize(dir).toString()
                    if (relativePathString == ".git" || gitIgnoredDirectories.contains(relativePathString)) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    val hasRegularFiles =
                        Files.list(dir).use { paths ->
                            paths.anyMatch { !Files.isDirectory(it) }
                        }
                    val hasAnyFiles = Files.list(dir).findFirst().isPresent
                    // Add a directory only when it has some files. Empty directories can be added only when they're leaf directories
                    // This is done to avoid crossing fd limits
                    if (!hasAnyFiles || hasRegularFiles) {
                        registerDirectory(dir)
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun registerDirectory(dir: Path) {
        try {
            val key =
                dir.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                )
            watchKeys[key] = dir
        } catch (e: IOException) {
            logger.error("Failed to register directory: ${dir.fileName}", e)
        }
    }

    fun startWatching() {
        if (watchThread?.isAlive == true) {
            return
        }
        watchThread =
            thread(start = true, name = "DirectoryWatcherThread") {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val key = watchService.take()
                        val dir = watchKeys[key]!!
                        for (event in key.pollEvents()) {
                            val kind = event.kind()
                            if (kind == StandardWatchEventKinds.OVERFLOW) {
                                continue
                            }
                            val name = event.context() as Path
                            val child = dir.resolve(name)
                            if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                                try {
                                    if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                                        registerAll(child)
                                    }
                                } catch (e: IOException) {
                                    logger.error("Failed to register new directory", e)
                                }
                            }
                            onEvent.invoke(kind, child)
                        }
                        if (!key.reset()) {
                            watchKeys.remove(key)
                            if (watchKeys.isEmpty()) break
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e)
                } finally {
                    watchService.close()
                }
            }
    }

    fun stopWatching() {
        watchThread?.interrupt()
        watchThread = null
    }
}
