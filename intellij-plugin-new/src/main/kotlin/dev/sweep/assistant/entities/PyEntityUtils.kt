package dev.sweep.assistant.entities

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import dev.sweep.assistant.utils.getSafeStartAndEndLines
import dev.sweep.assistant.utils.relativePath
import dev.sweep.assistant.utils.tryLoadClass
import dev.sweep.assistant.utils.tryMethod

private object PythonReflection {
    val pyFileClass = tryLoadClass("com.jetbrains.python.psi.PyFile")
    val pyClassClass = tryLoadClass("com.jetbrains.python.psi.PyClass")
    val pyFunctionClass = tryLoadClass("com.jetbrains.python.psi.PyFunction")
    val pyTargetExpressionClass = tryLoadClass("com.jetbrains.python.psi.PyTargetExpression")
    val pyFromImportStatementClass = tryLoadClass("com.jetbrains.python.psi.PyFromImportStatement")
    val pyImportStatementClass = tryLoadClass("com.jetbrains.python.psi.PyImportStatement")

    val superClassListMethod = tryMethod(pyClassClass, "getSuperClassExpressions")
    val getImportElementsMethod = tryMethod(pyImportStatementClass, "getImportElements")
}

private fun isPythonAvailable(): Boolean = PythonReflection.pyFileClass != null

fun isPythonFile(psiFile: PsiFile): Boolean = psiFile.fileType.name.equals("Python", ignoreCase = true)

private val logger = Logger.getInstance("dev.sweep.assistant.entities.PyEntityUtils")

fun getPyEntitiesWithoutReferences(psiFile: PsiFile): Map<PsiNameIdentifierOwner, EntityInfo> {
    if (!isPythonAvailable() || !isPythonFile(psiFile)) return emptyMap()

    val document = psiFile.viewProvider.document ?: return emptyMap()
    val entities = mutableMapOf<PsiNameIdentifierOwner, EntityInfo>()

    val elementClasses =
        listOfNotNull(
            PythonReflection.pyClassClass,
            PythonReflection.pyFunctionClass,
            PythonReflection.pyTargetExpressionClass,
        )

    elementClasses.forEach { elementClass ->
        getElementsOfType<PsiElement>(psiFile, elementClass).forEach innerLoop@{ element ->
            runCatching {
                if (element !is PsiNameIdentifierOwner) return@innerLoop

                val (startLine, endLine) = getSafeStartAndEndLines(element.textRange, document)

                val type =
                    when {
                        PythonReflection.pyClassClass?.isInstance(element) == true -> {
                            val superClasses = PythonReflection.superClassListMethod?.invoke(element) as? Array<*>
                            val isEnum =
                                superClasses?.any {
                                    it?.toString()?.contains("Enum") == true
                                } ?: false
                            if (isEnum) EntityType.ENUM_CLASS else EntityType.CLASS
                        }
                        PythonReflection.pyFunctionClass?.isInstance(element) == true -> {
                            if (PythonReflection.pyFileClass?.isInstance(element.parent) == true) EntityType.FUNCTION else null
                        }
                        PythonReflection.pyTargetExpressionClass?.isInstance(element) == true -> {
                            if (PythonReflection.pyFileClass?.isInstance(element.parent) == true) EntityType.PROPERTY else null
                        }
                        else -> null
                    }

                if (type != null && element.name != null) {
                    val entityInfo =
                        EntityInfo(
                            name = element.name!!,
                            startLine = startLine + 1,
                            endLine = endLine + 1,
                            type = type,
                        )
                    entities[element] = entityInfo
                }
            }
        }
    }

    return entities
}

fun getPyImports(psiFile: PsiFile): List<String> {
    if (!isPythonAvailable() || !isPythonFile(psiFile)) return emptyList()

    val project = psiFile.project
    val imports = mutableListOf<String>()

    try {
        val fileIndex = ProjectFileIndex.getInstance(project)

        val psiTreeUtilClass = Class.forName("com.intellij.psi.util.PsiTreeUtil")

        if (PythonReflection.pyImportStatementClass != null) {
            val findChildrenOfTypeMethod =
                psiTreeUtilClass.getMethod(
                    "findChildrenOfType",
                    PsiElement::class.java,
                    Class::class.java,
                )

            val importStatements =
                findChildrenOfTypeMethod.invoke(
                    null,
                    psiFile,
                    PythonReflection.pyImportStatementClass,
                ) as? Collection<*> ?: emptyList<Any>()

            for (importStatement in importStatements) {
                if (importStatement == null) continue

                try {
                    val importElements = PythonReflection.getImportElementsMethod?.invoke(importStatement) as? Array<*> ?: continue

                    for (importElement in importElements) {
                        if (importElement == null) continue

                        try {
                            val resolved: Any =
                                runCatching {
                                    val resolveMethod = importElement.javaClass.getMethod("resolve")
                                    resolveMethod.invoke(importElement)
                                }.getOrNull() ?: continue

                            val containingFileMethod = resolved.javaClass.getMethod("getContainingFile")
                            val containingFile = containingFileMethod.invoke(resolved) ?: continue

                            val virtualFileMethod = containingFile.javaClass.getMethod("getVirtualFile")
                            val virtualFile = virtualFileMethod.invoke(containingFile) as? VirtualFile

                            if (virtualFile != null && fileIndex.isInContent(virtualFile)) {
                                virtualFile.path.let { imports.add(it) }
                            }
                        } catch (e: Exception) {
                            logger.warn("Error resolving Python import element: ${e.message}", e)
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Error processing Python import: ${e.message}", e)
                }
            }
        }

        if (PythonReflection.pyFromImportStatementClass != null) {
            val findChildrenOfTypeMethod =
                psiTreeUtilClass.getMethod(
                    "findChildrenOfType",
                    PsiElement::class.java,
                    Class::class.java,
                )

            val fromImportStatements =
                findChildrenOfTypeMethod.invoke(
                    null,
                    psiFile,
                    PythonReflection.pyFromImportStatementClass,
                ) as? Collection<*> ?: emptyList<Any>()

            for (fromImportStatement in fromImportStatements) {
                if (fromImportStatement == null) continue

                try {
                    val getImportSourceMethod = fromImportStatement.javaClass.getMethod("getImportSource")
                    val importSource = getImportSourceMethod.invoke(fromImportStatement) ?: continue

                    val resolved =
                        runCatching {
                            val getReferenceMethod = importSource.javaClass.getMethod("getReference")
                            val reference = getReferenceMethod.invoke(importSource)
                            val referenceResolveMethod = reference?.javaClass?.getMethod("resolve")
                            referenceResolveMethod?.invoke(reference)
                        }.getOrNull() ?: continue

                    val containingFileMethod = resolved.javaClass.getMethod("getContainingFile")
                    val containingFile = containingFileMethod.invoke(resolved) ?: continue

                    val virtualFileMethod = containingFile.javaClass.getMethod("getVirtualFile")
                    val virtualFile = virtualFileMethod.invoke(containingFile) as? VirtualFile

                    if (virtualFile != null && fileIndex.isInContent(virtualFile)) {
                        virtualFile.path.let { imports.add(it) }
                    }
                } catch (e: Exception) {
                    logger.warn("Error processing Python from-import: ${e.message}", e)
                }
            }
        }
    } catch (e: Exception) {
        logger.warn("Error processing Python imports: ${e.message}", e)
    }

    return imports.mapNotNull { relativePath(project, it) }.distinct()
}
