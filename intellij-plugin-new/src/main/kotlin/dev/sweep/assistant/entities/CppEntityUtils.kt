package dev.sweep.assistant.entities

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import dev.sweep.assistant.utils.getSafeStartAndEndLines
import dev.sweep.assistant.utils.tryLoadClass

private object CppReflection {
    val cppFileClass = tryLoadClass("com.jetbrains.cidr.lang.psi.OCFile")
    val cppClassClass = tryLoadClass("com.jetbrains.cidr.lang.psi.OCClass")
    val cppFunctionClass = tryLoadClass("com.jetbrains.cidr.lang.psi.OCFunction")
    val cppVariableClass = tryLoadClass("com.jetbrains.cidr.lang.psi.OCVariable")
    val cppEnumClass = tryLoadClass("com.jetbrains.cidr.lang.psi.OCEnum")
}

fun isCppFile(psiFile: PsiFile): Boolean = CppReflection.cppFileClass?.isInstance(psiFile) ?: false

fun getCppEntitiesWithoutReferences(psiFile: PsiFile): Map<PsiNameIdentifierOwner, EntityInfo> {
    if (!isCppFile(psiFile)) return emptyMap()
    val document = psiFile.viewProvider.document ?: return emptyMap()
    val entities = mutableMapOf<PsiNameIdentifierOwner, EntityInfo>()

    val elementClasses =
        listOfNotNull(
            CppReflection.cppClassClass,
            CppReflection.cppFunctionClass,
            CppReflection.cppVariableClass,
            CppReflection.cppEnumClass,
        )

    elementClasses.forEach { elementClass ->
        getElementsOfType<PsiElement>(psiFile, elementClass).forEach innerLoop@{ element ->
            runCatching {
                if (element !is PsiNameIdentifierOwner) return@innerLoop

                val (startLine, endLine) = getSafeStartAndEndLines(element.textRange, document)

                val type =
                    when {
                        CppReflection.cppEnumClass?.isInstance(element) == true -> EntityType.ENUM_CLASS
                        CppReflection.cppClassClass?.isInstance(element) == true -> EntityType.CLASS
                        CppReflection.cppFunctionClass?.isInstance(element) == true -> EntityType.FUNCTION
                        CppReflection.cppVariableClass?.isInstance(element) == true -> {
                            if (CppReflection.cppFileClass?.isInstance(element.parent) == true) EntityType.PROPERTY else null
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
