package dev.sweep.assistant.entities

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import dev.sweep.assistant.utils.tryLoadClass

private object TypeScriptReflection {
    val tsFileClass = tryLoadClass("com.intellij.lang.javascript.psi.JSFile")
    val tsClassClass = tryLoadClass("com.intellij.lang.javascript.psi.JSClass")
    val tsFunctionClass = tryLoadClass("com.intellij.lang.javascript.psi.JSFunction")
    val tsVariableClass = tryLoadClass("com.intellij.lang.javascript.psi.JSVariable")
    val tsInterfaceClass = tryLoadClass("com.intellij.lang.javascript.psi.JSClass") // Interfaces are also JSClass

    val hasEnumKeywordMethod = tsClassClass?.getMethod("isEnum")
}

fun isTSFile(psiFile: PsiFile): Boolean =
    psiFile.fileType.name.equals("JavaScript", ignoreCase = true) ||
        psiFile.fileType.name.equals("TypeScript JSX", ignoreCase = true) ||
        psiFile.fileType.name.equals("TypeScript", ignoreCase = true)

fun getTSEntitiesWithoutReferences(psiFile: PsiFile): Map<PsiNameIdentifierOwner, EntityInfo> {
    if (!isTSFile(psiFile)) return emptyMap()

    return runCatching {
        val document = psiFile.viewProvider.document ?: return emptyMap()
        val entities = mutableMapOf<PsiNameIdentifierOwner, EntityInfo>()

        val elementClasses =
            listOfNotNull(
                TypeScriptReflection.tsClassClass,
                TypeScriptReflection.tsFunctionClass,
                TypeScriptReflection.tsVariableClass,
                TypeScriptReflection.tsInterfaceClass,
            )

        elementClasses.forEach { elementClass ->
            getElementsOfType<PsiElement>(psiFile, elementClass).forEach innerLoop@{ element ->
                runCatching {
                    if (element !is PsiNameIdentifierOwner) return@innerLoop

                    val textRange = element.textRange
                    val startLine = document.getLineNumber(textRange.startOffset)
                    val endLine = document.getLineNumber(textRange.endOffset)

                    val type =
                        when {
                            TypeScriptReflection.tsClassClass?.isInstance(element) == true -> {
                                val isEnum =
                                    runCatching {
                                        TypeScriptReflection.hasEnumKeywordMethod?.invoke(
                                            element,
                                        ) as? Boolean ?: false
                                    }.getOrDefault(false)
                                if (isEnum) EntityType.ENUM_CLASS else EntityType.CLASS
                            }
                            TypeScriptReflection.tsFunctionClass?.isInstance(element) == true -> EntityType.FUNCTION
                            TypeScriptReflection.tsVariableClass?.isInstance(element) == true -> {
                                if (TypeScriptReflection.tsFileClass?.isInstance(element.parent) == true) EntityType.PROPERTY else null
                            }
                            TypeScriptReflection.tsInterfaceClass?.isInstance(element) == true -> EntityType.INTERFACE
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

        entities
    }.getOrElse { emptyMap() }
}
