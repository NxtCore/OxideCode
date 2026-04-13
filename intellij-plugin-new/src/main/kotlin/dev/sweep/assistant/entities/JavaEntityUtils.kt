package dev.sweep.assistant.entities

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import dev.sweep.assistant.utils.getSafeStartAndEndLines
import dev.sweep.assistant.utils.relativePath
import dev.sweep.assistant.utils.tryLoadClass
import dev.sweep.assistant.utils.tryMethod
import java.lang.reflect.Method

private object JavaReflection {
    val javaClassClass = tryLoadClass("com.intellij.psi.PsiClass")
    val javaMethodClass = tryLoadClass("com.intellij.psi.PsiMethod")
    val javaFileClass = tryLoadClass("com.intellij.psi.PsiJavaFile")
    val importListClass = tryLoadClass("com.intellij.psi.PsiImportList")
    val importStatementClass = tryLoadClass("com.intellij.psi.PsiImportStatement")

    val isEnumMethod: Method? = javaClassClass?.getMethod("isEnum")

    val getImportListMethod = tryMethod(javaFileClass, "getImportList")
    val getAllImportStatementsMethod = tryMethod(importListClass, "getAllImportStatements")
    val getQualifiedNameMethod = tryMethod(importStatementClass, "getQualifiedName")
}

private fun isJavaAvailable(): Boolean = JavaReflection.javaMethodClass != null

fun isJavaFile(psiFile: PsiFile): Boolean = psiFile.fileType.name.equals("java", ignoreCase = true)

private val logger = Logger.getInstance("dev.sweep.assistant.entities.JavaEntityUtils")

fun getJavaEntitiesWithoutReferences(psiFile: PsiFile): Map<PsiNameIdentifierOwner, EntityInfo> {
    if (!isJavaAvailable() || !isJavaFile(psiFile)) return emptyMap()
    val document = psiFile.viewProvider.document ?: return emptyMap()
    val entities = mutableMapOf<PsiNameIdentifierOwner, EntityInfo>()

    val elementClasses =
        listOfNotNull(
            JavaReflection.javaClassClass,
            JavaReflection.javaMethodClass,
        )

    elementClasses.forEach { elementClass ->
        getElementsOfType<PsiElement>(psiFile, elementClass).forEach innerLoop@{ element ->
            runCatching {
                if (element !is PsiNameIdentifierOwner) return@innerLoop

                val (startLine, endLine) = getSafeStartAndEndLines(element.textRange, document)

                val type =
                    when {
                        JavaReflection.javaClassClass?.isInstance(element) == true -> {
                            val isEnum =
                                runCatching {
                                    JavaReflection.isEnumMethod?.invoke(
                                        element,
                                    ) as? Boolean ?: false
                                }.getOrDefault(false)
                            if (isEnum) EntityType.ENUM_CLASS else EntityType.CLASS
                        }
                        JavaReflection.javaMethodClass?.isInstance(element) == true -> EntityType.FUNCTION
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

fun getJavaImports(psiFile: PsiFile): List<String> {
    if (!isJavaAvailable() || !isJavaFile(psiFile)) return emptyList()
    if (JavaReflection.javaFileClass?.isInstance(psiFile) != true) return emptyList()

    val project = psiFile.project
    val imports = mutableListOf<String>()

    try {
        val importList = JavaReflection.getImportListMethod?.invoke(psiFile) ?: return emptyList()
        val importStatements = JavaReflection.getAllImportStatementsMethod?.invoke(importList) as? Array<*> ?: return emptyList()

        val projectScope = ProjectScope.getProjectScope(project)
        val fileIndex = ProjectFileIndex.getInstance(project)

        for (importStatement in importStatements) {
            if (importStatement == null) continue

            try {
                val qualifiedName = JavaReflection.getQualifiedNameMethod?.invoke(importStatement) as? String ?: continue

                // Look up the class using JavaPsiFacade
                val javaPsiFacadeClass = Class.forName("com.intellij.psi.JavaPsiFacade")
                val getInstanceMethod = javaPsiFacadeClass.getMethod("getInstance", Project::class.java)
                val javaPsiFacade = getInstanceMethod.invoke(null, project)

                val findClassMethod =
                    javaPsiFacade.javaClass.getMethod(
                        "findClass",
                        String::class.java,
                        GlobalSearchScope::class.java,
                    )
                val psiClass = findClassMethod.invoke(javaPsiFacade, qualifiedName, projectScope)

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
                }
            } catch (e: Exception) {
                logger.warn("Error processing Java import: ${e.message}")
            }
        }
    } catch (e: Exception) {
        logger.warn("Error processing Java imports: ${e.message}", e)
    }

    return imports.mapNotNull { relativePath(project, it) }.distinct()
}
