package dev.sweep.assistant.services

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import dev.sweep.assistant.utils.relativePath
import java.util.concurrent.Callable

/**
 * Service for PSI-based symbol resolution.
 * Uses JetBrains' existing PSI indices for accurate, efficient symbol lookup.
 */
@Service(Service.Level.PROJECT)
class PsiSymbolService(
    private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): PsiSymbolService = project.getService(PsiSymbolService::class.java)

        private const val SYMBOL_SEARCH_TAB_ID = "SymbolSearchEverywhereContributor"
    }

    /**
     * Represents a matched symbol with navigation info.
     */
    data class SymbolMatch(
        val name: String,
        val filePath: String, // Relative path from project root
        val lineNumber: Int, // 1-based line number
        val type: String?, // "function", "class", "field", etc.
        val containerName: String?, // Parent class/object name
    )

    /**
     * Pre-compute all symbols from a list of files for fast lookup during streaming.
     * Returns a map of symbol name -> list of matches.
     *
     * @param filePaths List of relative file paths to extract symbols from
     * @return Map of symbol names to their matches (for O(1) lookup)
     */
    fun preComputeSymbolsFromFiles(filePaths: List<String>): Map<String, List<SymbolMatch>> {
        if (filePaths.isEmpty() || project.isDisposed) return emptyMap()

        return try {
            ReadAction
                .nonBlocking(
                    Callable {
                        preComputeSymbolsFromFilesInternal(filePaths)
                    },
                ).expireWith(SweepProjectService.getInstance(project))
                .executeSynchronously()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun preComputeSymbolsFromFilesInternal(filePaths: List<String>): Map<String, List<SymbolMatch>> {
        if (project.isDisposed) return emptyMap()

        val symbolMap = mutableMapOf<String, MutableList<SymbolMatch>>()
        val basePath = project.basePath ?: return emptyMap()
        val psiManager =
            com.intellij.psi.PsiManager
                .getInstance(project)
        val localFileSystem =
            com.intellij.openapi.vfs.LocalFileSystem
                .getInstance()

        for (relativePath in filePaths) {
            if (project.isDisposed) break

            try {
                val absolutePath = "$basePath/$relativePath"
                val virtualFile = localFileSystem.findFileByPath(absolutePath) ?: continue
                val psiFile = psiManager.findFile(virtualFile) ?: continue

                // Find all named elements (definitions) in this file
                val namedElements = PsiTreeUtil.findChildrenOfType(psiFile, PsiNameIdentifierOwner::class.java)
                for (element in namedElements) {
                    val name = element.name ?: continue
                    if (name.isBlank() || name.length < 2) continue // Skip very short names

                    val match = createSymbolMatch(element, getSymbolType(element)) ?: continue
                    symbolMap.getOrPut(name) { mutableListOf() }.add(match)
                }
            } catch (_: Exception) {
                // Skip files that can't be processed
            }
        }

        return symbolMap
    }

    /**
     * Find symbols by exact name match using JetBrains' PsiSearchHelper.
     * This leverages the existing IDE indices - no custom caching needed.
     *
     * @param name The symbol name to search for
     * @param limit Maximum number of results to return
     * @return List of matching symbols
     */
    fun findSymbolsByName(
        name: String,
        limit: Int = 10,
    ): List<SymbolMatch> {
        if (name.isBlank() || project.isDisposed) return emptyList()

        return try {
            ReadAction
                .nonBlocking(
                    Callable {
                        findSymbolsByNameInternal(name, limit)
                    },
                ).expireWith(SweepProjectService.getInstance(project))
                .executeSynchronously()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun findSymbolsByNameInternal(
        name: String,
        limit: Int,
    ): List<SymbolMatch> {
        if (project.isDisposed) return emptyList()

        val results = mutableListOf<SymbolMatch>()
        val scope = GlobalSearchScope.projectScope(project)
        val searchHelper = PsiSearchHelper.getInstance(project)
        val seenLocations = mutableSetOf<String>() // Deduplicate by file:line

        try {
            // Find all files containing the symbol name
            searchHelper.processAllFilesWithWord(
                name,
                scope,
                Processor { psiFile ->
                    if (results.size >= limit || project.isDisposed) {
                        return@Processor false
                    }

                    // Find all named elements (definitions) with the exact name in this file
                    val namedElements = PsiTreeUtil.findChildrenOfType(psiFile, PsiNameIdentifierOwner::class.java)
                    for (element in namedElements) {
                        if (results.size >= limit) break

                        // Check if this element's name matches exactly
                        if (element.name == name) {
                            val match = createSymbolMatch(element, getSymbolType(element))
                            if (match != null && seenLocations.add("${match.filePath}:${match.lineNumber}")) {
                                results.add(match)
                            }
                        }
                    }
                    true
                },
                true,
            )
        } catch (_: Exception) {
            // Silently handle errors
        }

        return results
    }

    /**
     * Determine the type of symbol based on the PSI element.
     */
    private fun getSymbolType(element: PsiElement): String {
        val className = element.javaClass.simpleName.lowercase()
        return when {
            className.contains("method") || className.contains("function") -> "function"
            className.contains("class") -> "class"
            className.contains("field") || className.contains("property") || className.contains("variable") -> "field"
            className.contains("interface") -> "interface"
            className.contains("enum") -> "enum"
            else -> "symbol"
        }
    }

    /**
     * Create a SymbolMatch from a PSI element.
     */
    private fun createSymbolMatch(
        element: PsiElement,
        type: String,
    ): SymbolMatch? {
        if (project.isDisposed) return null

        val containingFile = element.containingFile ?: return null
        val virtualFile = containingFile.virtualFile ?: return null
        val filePath = relativePath(project, virtualFile.path) ?: return null

        // Get line number (1-based)
        val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
        val lineNumber =
            if (document != null) {
                val offset = element.textOffset.coerceIn(0, document.textLength.coerceAtLeast(1) - 1)
                document.getLineNumber(offset) + 1 // Convert to 1-based
            } else {
                1
            }

        // Get name
        val name = (element as? PsiNamedElement)?.name ?: return null

        // Get container name (parent class/object)
        val containerName = getContainerName(element)

        return SymbolMatch(
            name = name,
            filePath = filePath,
            lineNumber = lineNumber,
            type = type,
            containerName = containerName,
        )
    }

    /**
     * Get the name of the containing class/object for context.
     */
    private fun getContainerName(element: PsiElement): String? {
        var parent = element.parent
        while (parent != null) {
            if (parent is PsiNamedElement) {
                val name = parent.name
                // Check if this looks like a class/object container (not just a file)
                if (name != null && parent.containingFile != parent) {
                    return name
                }
            }
            parent = parent.parent
        }
        return null
    }

    /**
     * Open JetBrains native "Go to Symbol" SearchEverywhere popup pre-filled with search text.
     * This provides users with the full native search experience for multiple matches.
     */
    fun openSymbolSearchPopup(searchText: String) {
        if (project.isDisposed) return

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            try {
                val dataContext =
                    SimpleDataContext
                        .builder()
                        .add(CommonDataKeys.PROJECT, project)
                        .build()

                val seManager = SearchEverywhereManager.getInstance(project)

                val event =
                    AnActionEvent(
                        null,
                        dataContext,
                        "Sweep.SymbolSearch",
                        Presentation(),
                        ActionManager.getInstance(),
                        0,
                    )

                seManager.show(SYMBOL_SEARCH_TAB_ID, searchText, event)
            } catch (e: Exception) {
                println("Failed to open SearchEverywhere: ${e.message}")
            }
        }
    }

    override fun dispose() {}
}
