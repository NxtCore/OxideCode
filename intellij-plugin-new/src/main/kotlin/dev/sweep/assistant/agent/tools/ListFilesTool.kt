package dev.sweep.assistant.agent.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.FileLocation
import dev.sweep.assistant.data.ToolCall
import java.io.File
import java.nio.file.Paths

/**
 * Represents a node in the file tree.
 *
 * @param name The display name of the node. Can be modified to include counts for stubs.
 * @param parent The parent node in the tree. Null for the root.
 * @param children A list of child nodes.
 * @param isDirectory True if this node represents a directory, false for a file or synthetic node.
 * @param virtualFile The IntelliJ VirtualFile associated with this node, if it represents an actual file or directory.
 *                    Null for synthetic nodes like "... X more files" or the conceptual project root if it doesn't map directly to a single VF.
 */
data class TreeNode(
    var name: String,
    val parent: TreeNode? = null,
    val children: MutableList<TreeNode> = mutableListOf(),
    val isDirectory: Boolean,
    val virtualFile: VirtualFile? = null,
) {
    val path: List<TreeNode> by lazy {
        val p = mutableListOf<TreeNode>()
        var current: TreeNode? = this
        while (current != null) {
            p.add(0, current)
            current = current.parent
        }
        p
    }

    /**
     * Calculates the relative path of this node's VirtualFile with respect to the project's base VirtualFile.
     * If this node is synthetic (no VirtualFile), it attempts to construct a path from its ancestors' names.
     *
     * @param projectBaseVf The VirtualFile representing the root of the project.
     * @return A string representing the relative path, using '/' as a separator. Returns an empty string for the project root itself
     *         if this node's virtualFile is the projectBaseVf.
     */
    fun getRelativePathFromProjectRoot(projectBaseVf: VirtualFile): String {
        if (this.virtualFile == null) {
            if (this.parent == null) return "" // Should not happen if root has virtualFile

            return path
                .drop(1) // Drop the conceptual root (which should have a virtualFile)
                .joinToString("/") { treeNode ->
                    treeNode.name.substringBeforeLast("/ (")
                }
        }
        // For actual files/directories
        return VfsUtilCore.getRelativePath(this.virtualFile, projectBaseVf, '/') ?: ""
    }
}

class ListFilesTool : SweepTool {
    companion object {
        private const val DEFAULT_MAX_DEPTH = 10
        private const val DEFAULT_MAX_FILES = 1000
        private const val DEFAULT_MAX_LENGTH = 15000
    }

    /**
     * Generates a tree structure of files and directories.
     *
     * Expected toolCall.toolParameters:
     * - "path" (String, required, default: "."): Root directory for listing, relative to project root.
     * - "recursive" (Boolean, required, default: false): If we should recursively list files.
     */
    override fun execute(
        toolCall: ToolCall,
        project: Project,
        conversationId: String?,
    ): CompletedToolCall {
        val projectBasePath =
            project.basePath ?: return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = toolCall.toolName,
                resultString = "Error: Project base path not found.",
                status = false,
            )
        val projectBaseVf =
            VirtualFileManager.getInstance().findFileByUrl("file://$projectBasePath")
                ?: return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = toolCall.toolName,
                    resultString = "Error: Project base VirtualFile not found.",
                    status = false,
                )

        val maxDepth = DEFAULT_MAX_DEPTH
        val maxFiles = DEFAULT_MAX_FILES

        val startPathArg = toolCall.toolParameters["path"] ?: "."
        val recursive = toolCall.toolParameters["recursive"]?.toBoolean() ?: false
        // Resolve the requested path against the project base path and ensure it does not escape the project directory
        val basePath = Paths.get(projectBasePath).normalize().toAbsolutePath()
        val requestedPath = basePath.resolve(startPathArg).normalize().toAbsolutePath()

        // Prevent listing directories not within the project path or its siblings
        val baseParent = basePath.parent
        if (!requestedPath.startsWith(basePath) &&
            (baseParent == null || !requestedPath.startsWith(baseParent))
        ) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = toolCall.toolName,
                resultString = "Error: Path is outside project root and its siblings: $startPathArg (resolved to $requestedPath). Access outside the project parent directory is not allowed.",
                status = false,
            )
        }

        val absoluteStartPath = requestedPath.toString()

        val startVirtualFile = VirtualFileManager.getInstance().findFileByUrl("file://$absoluteStartPath")
        if (startVirtualFile == null || !startVirtualFile.exists() || !startVirtualFile.isDirectory) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = toolCall.toolName,
                resultString = "Error: Start directory not found at path: $startPathArg (resolved to $absoluteStartPath)",
                status = false,
            )
        }

        try {
            // Function to generate the tree with given parameters
            fun generateTree(
                depth: Int,
                files: Int,
            ): Pair<String, List<FileLocation>> {
                val descendantCounts = mutableMapOf<String, Int>()
                calculateDescendantFileCounts(
                    startVirtualFile,
                    projectBaseVf,
                    descendantCounts,
                )

                val rootDisplayName =
                    if (startPathArg == "." || startPathArg.isEmpty() || startPathArg.isBlank()) {
                        startVirtualFile.name
                    } else {
                        normalizePathString(startPathArg)
                    }

                val rootDisplayNode =
                    TreeNode(
                        name = rootDisplayName,
                        parent = null,
                        isDirectory = true,
                        virtualFile = startVirtualFile,
                    )

                val fileLocations = mutableListOf<FileLocation>()
                addChildrenRecursive(
                    rootDisplayNode,
                    startVirtualFile,
                    0,
                    if (recursive) depth else 1,
                    files,
                    projectBaseVf,
                    descendantCounts,
                )

                val (treeString, displayedFileLocations) =
                    renderTreeWithFileLocations(
                        rootDisplayNode,
                        setOf(),
                        descendantCounts,
                        projectBaseVf,
                    )
                return Pair(treeString, displayedFileLocations)
            }

            // Generate initial tree
            var (treeString, fileLocations) = generateTree(maxDepth, maxFiles)

            var wasReduced = false

            if (treeString.length > DEFAULT_MAX_LENGTH) {
                // Try with reduced parameters
                val reducedDepth = maxOf(2, maxDepth / 2)
                val reducedFiles = maxOf(50, maxFiles / 2)

                val (reducedTreeString, reducedFileLocations) = generateTree(reducedDepth, reducedFiles)
                treeString = reducedTreeString
                fileLocations = reducedFileLocations
                wasReduced = true

                // If still too large, truncate
                if (treeString.length > DEFAULT_MAX_LENGTH) {
                    treeString =
                        treeString.take(DEFAULT_MAX_LENGTH) +
                        "\n\n[Output truncated - result was too large. Please use a more specific path or set recursive=false for smaller output.]"
                } else if (wasReduced) {
                    treeString +=
                        "\n\n[Output was reduced (depth: $reducedDepth, max files: $reducedFiles) due to size. Use a more specific path for full detail.]"
                }
            }

            val resultString =
                if (treeString.lines().size == 1) {
                    "No files or directories found in this directory."
                } else {
                    treeString
                }

            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = toolCall.toolName,
                resultString = resultString,
                status = true,
                fileLocations = fileLocations,
            )
        } catch (e: Exception) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = toolCall.toolName,
                resultString = "Error generating file tree: ${e.message ?: e.javaClass.simpleName}",
                status = false,
            )
        }
    }

    private fun normalizePathString(filePath: String): String = filePath.replace(File.separatorChar, '/').removePrefix("./").trimEnd('/')

    private fun calculateDescendantFileCounts(
        currentVf: VirtualFile,
        projectBaseVf: VirtualFile,
        descendantCountsMap: MutableMap<String, Int>,
    ): Int {
        // Hard guard: do not traverse outside the physical project base directory
        if (!VfsUtilCore.isAncestor(projectBaseVf, currentVf, false)) {
            return 0
        }
        if (!currentVf.isDirectory ||
            (currentVf.name.startsWith(".") && currentVf.name.length > 1)
        ) {
            return 0
        }

        var filesInThisDirAndSubdirs = 0
        currentVf.children?.forEach { child ->
            // Skip any child not under the project base directory
            if (!VfsUtilCore.isAncestor(projectBaseVf, child, false)) return@forEach
            if (child.isDirectory) {
                if (!(child.name.startsWith(".") && child.name.length > 1)) {
                    filesInThisDirAndSubdirs +=
                        calculateDescendantFileCounts(
                            child,
                            projectBaseVf,
                            descendantCountsMap,
                        )
                }
            } else {
                filesInThisDirAndSubdirs++
            }
        }

        val relativePathToProject = VfsUtilCore.getRelativePath(currentVf, projectBaseVf, '/') ?: ""
        descendantCountsMap[relativePathToProject] = filesInThisDirAndSubdirs

        return filesInThisDirAndSubdirs
    }

    private fun addChildrenRecursive(
        parentNode: TreeNode,
        currentListingVf: VirtualFile,
        depth: Int,
        maxDepth: Int,
        maxFiles: Int,
        projectBaseVf: VirtualFile,
        descendantCounts: Map<String, Int>,
    ) {
        if (depth >= maxDepth) {
            // parentNode is the directory AT maxDepth. Modify its name and stop adding children to it.
            val parentNodeRelativePath =
                parentNode.virtualFile?.let { VfsUtilCore.getRelativePath(it, projectBaseVf, '/') } ?: ""
            val count = descendantCounts[parentNodeRelativePath]
            if (count != null && count > 0 && parentNode.isDirectory) {
                if (!parentNode.name.contains(" files)")) {
                    parentNode.name += "/ ($count files)"
                }
            }
            return // Stop recursion: children of this parentNode will not be added/processed.
        }

        val childrenOfCurrentListingVf = currentListingVf.children ?: return
        val directories = mutableListOf<VirtualFile>()
        val files = mutableListOf<VirtualFile>()

        for (itemVf in childrenOfCurrentListingVf) {
            // Hard guard: ensure we never list entries outside the physical project directory
            if (!VfsUtilCore.isAncestor(projectBaseVf, itemVf, false)) {
                continue
            }

            val itemName = itemVf.name
            if (itemName.startsWith(".") && itemName.length > 1) continue
            if (itemVf.isDirectory) {
                if (!itemName.startsWith("tmp")) {
                    directories.add(itemVf)
                }
            } else {
                files.add(itemVf)
            }
        }

        directories.sortBy { it.name }
        for (dirVf in directories) {
            val childNode = TreeNode(dirVf.name, parent = parentNode, isDirectory = true, virtualFile = dirVf)
            parentNode.children.add(childNode)
            // Children of this childNode will be at depth + 1
            addChildrenRecursive(
                childNode,
                dirVf,
                depth + 1,
                maxDepth,
                maxFiles,
                projectBaseVf,
                descendantCounts,
            )
        }

        // User's modified file handling (no explicit sort by priority)
        files.forEachIndexed { i, fileVf ->
            if (i < maxFiles) {
                val fileNode = TreeNode(fileVf.name, parent = parentNode, isDirectory = false, virtualFile = fileVf)
                parentNode.children.add(fileNode)
            } else if (i == maxFiles) {
                val remaining = files.size - maxFiles
                if (remaining > 0) {
                    val moreNode =
                        TreeNode(
                            "... $remaining more files",
                            parent = parentNode,
                            isDirectory = false,
                            virtualFile = null,
                        )
                    parentNode.children.add(moreNode)
                }
                return@forEachIndexed
            }
        }
    }

    private fun renderTreeWithFileLocations(
        rootDisplayNode: TreeNode, // Represents the starting directory (e.g., "src" or "project-name")
        targetFilePaths: Set<String>,
        descendantCounts: Map<String, Int>,
        projectBaseVf: VirtualFile,
    ): Pair<String, List<FileLocation>> {
        val displayedFileLocations = mutableListOf<FileLocation>()
        val sb = StringBuilder()

        // --- Handle the first line (the rootDisplayNode itself) ---
        var finalRootDisplayName = rootDisplayNode.name // This might already be "name/ (N files)" if maxDepth = 0

        // If targetFilePaths are specified, the root line might also get a count,
        // similar to how Python's RenderTree would treat the root element.
        if (targetFilePaths.isNotEmpty() && !finalRootDisplayName.contains(" files)")) {
            val rootNodeRelativePath = rootDisplayNode.getRelativePathFromProjectRoot(projectBaseVf)
            val count = descendantCounts[rootNodeRelativePath] // Count of files under the starting directory
            if (count != null && count > 0) {
                finalRootDisplayName = "${finalRootDisplayName.removeSuffix("/")}/ ($count files)"
            }
        }
        sb.appendLine(finalRootDisplayName)
        // --- End first line handling ---

        val renderItems = mutableListOf<Pair<String, TreeNode>>()

        fun buildRenderList(
            node: TreeNode,
            currentPrefix: String,
            childrenPrefixFill: String,
        ) {
            renderItems.add(currentPrefix to node)
            node.children.forEachIndexed { i, child ->
                val isLast = i == node.children.size - 1
                val itemPrefix = childrenPrefixFill + (if (isLast) "└ " else "├ ")
                val nextChildrenPrefixFill = childrenPrefixFill + (if (isLast) "    " else "│   ")
                buildRenderList(child, itemPrefix, nextChildrenPrefixFill)
            }
        }

        rootDisplayNode.children.forEachIndexed { i, child ->
            val isLast = i == rootDisplayNode.children.size - 1
            val itemPrefix = if (isLast) "└ " else "├ "
            val childrenPrefixFill = if (isLast) "    " else "│   "
            buildRenderList(child, itemPrefix, childrenPrefixFill)
        }

        val targetFileDirs = targetFilePaths.mapNotNull { it.substringBeforeLast('/', "").ifEmpty { null } }.toSet()

        for ((itemLinePrefix, node) in renderItems) {
            val nodeRelativePath = node.getRelativePathFromProjectRoot(projectBaseVf)
            var effectiveNodeName = node.name

            var displayThisNode = false

            if (targetFilePaths.isNotEmpty()) {
                // val nodeParentRelativePath = node.parent?.getRelativePathFromProjectRoot(projectBaseVf) ?: "" // Not directly used in this version of logic

                if (nodeRelativePath in targetFilePaths) {
                    displayThisNode = true
                } else if (node.isDirectory && targetFileDirs.any { it == nodeRelativePath }) {
                    displayThisNode = true
                } else if (node.isDirectory &&
                    targetFilePaths.any { it.startsWith("$nodeRelativePath/") && nodeRelativePath.isNotEmpty() }
                ) {
                    displayThisNode = true
                } else if (node.path.size <= 2) { // node.path starts with rootDisplayNode. Children are at size 2.
                    displayThisNode = true
                }

                if (displayThisNode && node.isDirectory) {
                    if (!effectiveNodeName.contains(" files)")) {
                        val count = descendantCounts[nodeRelativePath]
                        if (count != null && count > 0) {
                            effectiveNodeName = "${effectiveNodeName.removeSuffix("/")}/ ($count files)"
                        } else if (!effectiveNodeName.endsWith("/")) {
                            effectiveNodeName += "/"
                        }
                    }
                }
            } else {
                displayThisNode = true
                if (node.isDirectory) {
                    if (!effectiveNodeName.contains(" files)") && !effectiveNodeName.endsWith("/")) {
                        effectiveNodeName += "/"
                    }
                }
            }

            if (displayThisNode) {
                sb.append(itemLinePrefix).append(effectiveNodeName).appendLine()

                // Add file location for displayed items
                node.virtualFile?.let { vf ->
                    val relativePath = VfsUtilCore.getRelativePath(vf, projectBaseVf, '/') ?: vf.path
                    displayedFileLocations.add(FileLocation(filePath = relativePath, isDirectory = node.isDirectory))
                }
            }
        }
        return Pair(sb.toString().trimEnd(), displayedFileLocations)
    }
}
