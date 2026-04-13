package dev.sweep.assistant.entities

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import dev.sweep.assistant.utils.getSafeStartAndEndLines
import dev.sweep.assistant.utils.relativePath
import dev.sweep.assistant.utils.tryLoadClass
import dev.sweep.assistant.utils.tryMethod

private val logger = Logger.getInstance("dev.sweep.assistant.entities.KtEntityUtils")

private object KotlinReflection {
    val ktFileClass = tryLoadClass("org.jetbrains.kotlin.psi.KtFile")
    val ktClassClass = tryLoadClass("org.jetbrains.kotlin.psi.KtClass")
    val ktNamedFunctionClass = tryLoadClass("org.jetbrains.kotlin.psi.KtNamedFunction")
    val ktPropertyClass = tryLoadClass("org.jetbrains.kotlin.psi.KtProperty")
    val ktObjectDeclarationClass = tryLoadClass("org.jetbrains.kotlin.psi.KtObjectDeclaration")
    val ktEnumEntryClass = tryLoadClass("org.jetbrains.kotlin.psi.KtEnumEntry")
    val ktImportClass = tryLoadClass("org.jetbrains.kotlin.psi.KtImportDirective")
    val ktImportListClass = tryLoadClass("org.jetbrains.kotlin.psi.KtImportList")

    val isEnumMethod = tryMethod(ktClassClass, "isEnum")

    val getImportListMethod = tryMethod(ktFileClass, "getImportList")
    val getImportsMethod = tryMethod(ktImportListClass, "getImports")
    val getImportedFqNameMethod = tryMethod(ktImportClass, "getImportedFqName")

    val fqNameClass = tryLoadClass("org.jetbrains.kotlin.name.FqName")
    val asStringMethod = tryMethod(fqNameClass, "asString")

    val kotlinFullClassNameIndexClass = tryLoadClass("org.jetbrains.kotlin.idea.stubindex.KotlinFullClassNameIndex")
}

private fun isKotlinAvailable(): Boolean = KotlinReflection.ktFileClass != null

fun isKotlinFile(psiFile: PsiFile): Boolean = KotlinReflection.ktFileClass?.isInstance(psiFile) ?: false

fun getKtEntitiesWithoutReferences(psiFile: PsiFile): Map<PsiNameIdentifierOwner, EntityInfo> {
    if (!isKotlinAvailable() || !isKotlinFile(psiFile)) return emptyMap()

    val document = psiFile.viewProvider.document ?: return emptyMap()
    val entities = mutableMapOf<PsiNameIdentifierOwner, EntityInfo>()

    val elementClasses =
        listOfNotNull(
            KotlinReflection.ktClassClass,
            KotlinReflection.ktNamedFunctionClass,
            KotlinReflection.ktPropertyClass,
            KotlinReflection.ktObjectDeclarationClass,
        )

    elementClasses.forEach { elementClass ->
        getElementsOfType<PsiElement>(psiFile, elementClass).forEach innerLoop@{ element ->
            runCatching {
                if (element !is PsiNameIdentifierOwner) return@innerLoop

                val (startLine, endLine) = getSafeStartAndEndLines(element.textRange, document)

                val type =
                    when {
                        KotlinReflection.ktClassClass?.isInstance(element) == true -> {
                            if (KotlinReflection.ktEnumEntryClass?.isInstance(element) == true) {
                                null
                            } else {
                                val isEnum =
                                    runCatching {
                                        KotlinReflection.isEnumMethod?.invoke(
                                            element,
                                        ) as? Boolean ?: false
                                    }.getOrDefault(false)
                                if (isEnum) EntityType.ENUM_CLASS else EntityType.CLASS
                            }
                        }
                        KotlinReflection.ktNamedFunctionClass?.isInstance(element) == true -> {
                            EntityType.FUNCTION
                        }
                        KotlinReflection.ktPropertyClass?.isInstance(element) == true -> {
                            EntityType.PROPERTY
                        }
                        KotlinReflection.ktObjectDeclarationClass?.isInstance(element) == true -> EntityType.OBJECT
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

fun getKtImports(psiFile: PsiFile): List<String> {
    if (!isKotlinAvailable() || !isKotlinFile(psiFile)) return emptyList()

    val project = psiFile.project
    val imports = mutableListOf<String>()

    try {
        val importList = KotlinReflection.getImportListMethod?.invoke(psiFile) ?: return emptyList()
        val importDirectives = KotlinReflection.getImportsMethod?.invoke(importList) as? List<*> ?: return emptyList()

        val projectScope = ProjectScope.getProjectScope(project)
        val fileIndex = ProjectFileIndex.getInstance(project)

        for (importDirective in importDirectives) {
            val fqNameObj = KotlinReflection.getImportedFqNameMethod?.invoke(importDirective) ?: continue
            val fqName = KotlinReflection.asStringMethod?.invoke(fqNameObj) as? String ?: continue

            try {
                val javaPsiFacadeClass = Class.forName("com.intellij.psi.JavaPsiFacade")
                val getInstanceMethod = javaPsiFacadeClass.getMethod("getInstance", Project::class.java)
                val javaPsiFacade = getInstanceMethod.invoke(null, project)

                val findClassMethod =
                    javaPsiFacade.javaClass.getMethod(
                        "findClass",
                        String::class.java,
                        com.intellij.psi.search.GlobalSearchScope::class.java,
                    )
                val psiClass = findClassMethod.invoke(javaPsiFacade, fqName, projectScope)

                if (psiClass != null) {
                    val containingFileMethod = psiClass.javaClass.getMethod("getContainingFile")
                    val containingFile = containingFileMethod.invoke(psiClass)
                    if (containingFile != null) {
                        val virtualFileMethod = containingFile.javaClass.getMethod("getVirtualFile")
                        val virtualFile = virtualFileMethod.invoke(containingFile) as? VirtualFile

                        if (virtualFile != null && fileIndex.isInContent(virtualFile)) {
                            virtualFile.path.let { imports.add(it) }
                        }
                    }
                    continue
                }
            } catch (e: Exception) {
                logger.warn("Error looking up Java class: ${e.message}")
            }

            try {
                var ktFiles: Collection<*>? = null

                try {
                    val instanceField = KotlinReflection.kotlinFullClassNameIndexClass?.getDeclaredField("INSTANCE")
                    instanceField?.isAccessible = true
                    val indexInstance = instanceField?.get(null)

                    if (indexInstance != null) {
                        val getMethod =
                            indexInstance.javaClass.getDeclaredMethod(
                                "get",
                                String::class.java,
                                Project::class.java,
                                GlobalSearchScope::class.java,
                            )
                        getMethod.isAccessible = true

                        val ktFilesResult = getMethod.invoke(indexInstance, fqName, project, projectScope)
                        ktFiles = ktFilesResult as? Collection<*>
                    }
                } catch (e: Exception) {
                    logger.warn("INSTANCE field approach failed: ${e.message}")
                }

                if (ktFiles != null) {
                    for (ktFile in ktFiles) {
                        if (ktFile == null) continue

                        try {
                            val containingFileMethod = ktFile.javaClass.getMethod("getContainingFile")
                            val containingFile = containingFileMethod.invoke(ktFile)

                            if (containingFile != null) {
                                val virtualFileMethod = containingFile.javaClass.getMethod("getVirtualFile")
                                val virtualFile = virtualFileMethod.invoke(containingFile) as? VirtualFile

                                if (virtualFile != null && fileIndex.isInContent(virtualFile)) {
                                    virtualFile.path.let { imports.add(it) }
                                }
                            }
                        } catch (e: Exception) {
                            logger.warn("Error processing Kotlin file: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("Error looking up Kotlin file: ${e.message}")
            }
        }
    } catch (e: Exception) {
        logger.warn("Error processing imports: ${e.message}", e)
    }

    return imports.mapNotNull { relativePath(project, it) }.distinct()
}
