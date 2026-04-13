package dev.sweep.assistant.entities

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.SlowOperations
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sweep.assistant.data.ProjectFilesCache
import dev.sweep.assistant.utils.getSafeStartAndEndLines
import dev.sweep.assistant.utils.getVirtualFile
import dev.sweep.assistant.utils.relativePath
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val boundedExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Sweep.Entities", 5)
private val referencesExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Sweep.FindReferences", 5)
private val entitiesWithoutReferencesExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Sweep.EntitiesWithoutReferences", 1)
private const val TOO_MANY_LINES = 5000

enum class EntityType {
    CLASS,
    ENUM_CLASS,
    FUNCTION,
    PROPERTY,
    OBJECT,
    INTERFACE,
}

data class ReferenceInfo(
    val location: String,
    val startLine: Int,
    val endLine: Int,
)

data class EntityInfo(
    val name: String,
    var location: String? = null,
    val startLine: Int,
    val endLine: Int,
    val type: EntityType,
    val references: List<ReferenceInfo> = emptyList(),
)

fun getAllEntities(
    project: Project,
    source: Any,
    onComplete: (List<EntityInfo>) -> Unit,
) {
    ReadAction
        .nonBlocking<Pair<PsiFile?, Boolean>> {
            val psiFile = convertToPsiFile(project, source)
            val shouldSkip =
                psiFile?.let { file ->
                    val document = file.viewProvider.document
                    document == null || document.lineCount > TOO_MANY_LINES
                } ?: true
            Pair(psiFile, shouldSkip)
        }.inSmartMode(project)
        .submit(boundedExecutor)
        .onSuccess { (psiFile, shouldSkip) ->
            if (shouldSkip) {
                onComplete(emptyList())
                return@onSuccess
            }
            psiFile?.let { file ->
                when {
                    isKotlinFile(file) -> processFileWithReferences(project, onComplete) { getKtEntitiesWithoutReferences(file) }
                    isJavaFile(file) -> processFileWithReferences(project, onComplete) { getJavaEntitiesWithoutReferences(file) }
                    isPythonFile(file) -> processFileWithReferences(project, onComplete) { getPyEntitiesWithoutReferences(file) }
                    isTSFile(file) -> processFileWithReferences(project, onComplete) { getTSEntitiesWithoutReferences(file) }
                    isCppFile(file) -> processFileWithReferences(project, onComplete) { getCppEntitiesWithoutReferences(file) }
//                    isRubyFile(file) -> processFileWithReferences(project, onComplete) { getRubyEntitiesWithoutReferences(file) }
                    else -> onComplete(emptyList())
                }
            } ?: onComplete(emptyList())
        }
}

fun getEntitiesWithoutReferences(
    project: Project,
    file: Any,
): List<EntityInfo> {
    SlowOperations.assertSlowOperationsAreAllowed()
    return runCatching {
        val res =
            ReadAction
                .nonBlocking<Pair<String?, List<EntityInfo>>> {
                    val psiFile = convertToPsiFile(project, file) ?: return@nonBlocking Pair(null, emptyList())
                    Pair(
                        relativePath(project, psiFile.virtualFile),
                        when {
                            isKotlinFile(psiFile) -> getKtEntitiesWithoutReferences(psiFile).values.toList()
                            isJavaFile(psiFile) -> getJavaEntitiesWithoutReferences(psiFile).values.toList()
                            isPythonFile(psiFile) -> getPyEntitiesWithoutReferences(psiFile).values.toList()
                            isTSFile(psiFile) -> getTSEntitiesWithoutReferences(psiFile).values.toList()
                            isCppFile(psiFile) -> getCppEntitiesWithoutReferences(psiFile).values.toList()
                            else -> emptyList()
                        },
                    )
                }.inSmartMode(project)
                .submit(entitiesWithoutReferencesExecutor)
                .get()
        res.second.map { it.apply { location = res.first } }
    }.getOrNull() ?: emptyList()
}

fun getImportedFiles(
    project: Project,
    file: Any,
    onComplete: (List<String>) -> Unit,
) {
    ApplicationManager.getApplication().executeOnPooledThread {
        runCatching {
            val res =
                ReadAction
                    .nonBlocking<List<String>> {
                        val psiFile = convertToPsiFile(project, file) ?: return@nonBlocking emptyList()
                        when {
                            isKotlinFile(psiFile) -> getKtImports(psiFile)
                            isJavaFile(psiFile) -> getJavaImports(psiFile)
                            isPythonFile(psiFile) -> getPyImports(psiFile)
//                        isTSFile(psiFile) -> getTSImports(psiFile)
//                        isCppFile(psiFile) -> getCppImports(psiFile)
                            else -> emptyList()
                        }
                    }.inSmartMode(project)
                    .executeSynchronously()
            onComplete(res)
        }
    }
}

fun getImportedFilesBlocking(
    project: Project,
    file: Any,
    timeoutMs: Long = 0,
): List<String> {
    val future = CompletableFuture<List<String>>()
    getImportedFiles(project, file) {
        future.complete(it)
    }
    return runCatching {
        if (timeoutMs > 0) {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } else {
            future.get()
        }
    }.getOrNull() ?: emptyList()
}

private fun processFileWithReferences(
    project: Project,
    onComplete: (List<EntityInfo>) -> Unit,
    getEntitiesWithoutReferences: () -> Map<PsiNameIdentifierOwner, EntityInfo>,
) {
    ReadAction
        .nonBlocking<Map<PsiNameIdentifierOwner, EntityInfo>> {
            getEntitiesWithoutReferences()
        }.inSmartMode(project)
        .submit(boundedExecutor)
        .onSuccess { entityMap ->
            findReferences(project, entityMap, onComplete)
        }
}

fun convertToPsiFile(
    project: Project,
    file: Any?,
): PsiFile? =
    when (file) {
        is PsiFile -> file
        is VirtualFile -> PsiManager.getInstance(project).findFile(file)
        is String -> getVirtualFile(project, file)?.let { PsiManager.getInstance(project).findFile(it) }
        else -> null
    }

private fun findReferences(
    project: Project,
    originalEntities: Map<PsiNameIdentifierOwner, EntityInfo>,
    onComplete: (List<EntityInfo>) -> Unit,
) {
    val updatedEntities = Collections.synchronizedList(mutableListOf<EntityInfo>())
    val latch = CountDownLatch(originalEntities.size)
    originalEntities.forEach { (element, entityInfo) ->
        ReadAction
            .nonBlocking<List<ReferenceInfo>> {
                doFindReferences(element, project)
            }.inSmartMode(project)
            .submit(referencesExecutor)
            .onSuccess {
                updatedEntities.add(entityInfo.copy(references = it))
                latch.countDown()
            }.onError {
                latch.countDown()
            }
    }
    referencesExecutor.execute {
        latch.await()
        onComplete(updatedEntities)
    }
}

private fun detectBlocksInFile(
    project: Project,
    file: Any,
): List<Pair<Int, Int>> {
    return ReadAction.compute<List<Pair<Int, Int>>, RuntimeException> {
        val psiFile = convertToPsiFile(project, file) ?: return@compute emptyList()
        val document = psiFile.viewProvider.document ?: return@compute emptyList()

        if (document.lineCount > TOO_MANY_LINES) return@compute emptyList()

        val blocks = mutableListOf<Pair<Int, Int>>()

        PsiTreeUtil.processElements(psiFile) { element ->
            val (startLine, endLine) = getSafeStartAndEndLines(element.textRange, document)
            blocks.add(Pair(startLine + 1, endLine + 1))
            true
        }
        blocks.sortedWith(compareBy({ it.first }, { it.second })).distinct()
    }
}

fun findSurroundingBlock(
    project: Project,
    file: String,
    start: Int,
    end: Int,
): Pair<Int, Int> {
    val blocks = detectBlocksInFile(project, file)
    return blocks
        .filter { (blockStart, blockEnd) ->
            blockStart <= start && blockEnd >= end
        }.minByOrNull { (blockStart, blockEnd) ->
            blockEnd - blockStart
        } ?: Pair(start, end)
}

private fun doFindReferences(
    element: PsiNameIdentifierOwner,
    project: Project,
): List<ReferenceInfo> {
    val references = mutableListOf<ReferenceInfo>()
    if (element.isValid.not() || project.isDisposed) {
        return references
    }
    try {
        val projectScope = ProjectScope.getProjectScope(project)
        val searchResults = ReferencesSearch.search(element, projectScope).findAll()
        for (reference in searchResults) {
            val referenceElement = reference.element
            if (!referenceElement.isValid) continue
            val containingFile = referenceElement.containingFile ?: continue
            val path = relativePath(project, containingFile.virtualFile)
            if (path == null || !ProjectFilesCache.getInstance(project).contains(path)) {
                continue
            }
            val document = containingFile.viewProvider.document ?: continue
            val startLine = document.getLineNumber(referenceElement.textRange.startOffset)
            val endLine = document.getLineNumber(referenceElement.textRange.endOffset)
            references.add(
                ReferenceInfo(
                    location = path,
                    startLine = startLine,
                    endLine = endLine,
                ),
            )
        }
    } catch (e: Exception) {
        return references
    }
    return references
}

/**
 * Safely extracts elements of a specific type from a PSI file with comprehensive validation.
 * Returns an empty collection if any errors occur.
 */
fun <T : PsiElement> getElementsOfType(
    psiFile: PsiFile,
    elementClass: Class<*>,
): Collection<T> =
    runCatching {
        @Suppress("UNCHECKED_CAST")
        PsiTreeUtil.collectElementsOfType(psiFile, elementClass as Class<T>)
    }.getOrElse {
        // Log the error for debugging but don't crash the plugin
        // This prevents the IndexNotReadyException and other PSI-related errors
        emptyList()
    }
