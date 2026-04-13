package dev.sweep.assistant.entities

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import dev.sweep.assistant.utils.getSafeStartAndEndLines
import dev.sweep.assistant.utils.tryLoadClass

private object RubyReflection {
    val rubyFileClass = tryLoadClass("org.jetbrains.plugins.ruby.ruby.lang.psi.RFile")
    val rubyClassClass = tryLoadClass("org.jetbrains.plugins.ruby.ruby.lang.psi.RClass")
    val rubyMethodClass = tryLoadClass("org.jetbrains.plugins.ruby.ruby.lang.psi.RMethod")
    val rubyConstantClass = tryLoadClass("org.jetbrains.plugins.ruby.ruby.lang.psi.variables.RConstant")
    val rubyModuleClass = tryLoadClass("org.jetbrains.plugins.ruby.ruby.lang.psi.RModule")
}

fun isRubyFile(psiFile: PsiFile): Boolean = psiFile.fileType.name.equals("Ruby", ignoreCase = true)

fun getRubyEntitiesWithoutReferences(psiFile: PsiFile): Map<PsiNameIdentifierOwner, EntityInfo> {
    if (!isRubyFile(psiFile)) return emptyMap()
    val document = psiFile.viewProvider.document ?: return emptyMap()
    val entities = mutableMapOf<PsiNameIdentifierOwner, EntityInfo>()

    val elementClasses =
        listOfNotNull(
            RubyReflection.rubyClassClass,
            RubyReflection.rubyMethodClass,
            RubyReflection.rubyConstantClass,
            RubyReflection.rubyModuleClass,
        )

    elementClasses.forEach { elementClass ->
        getElementsOfType<PsiElement>(psiFile, elementClass).forEach innerLoop@{ element ->
            runCatching {
                if (element !is PsiNameIdentifierOwner) return@innerLoop

                val (startLine, endLine) = getSafeStartAndEndLines(element.textRange, document)

                val type =
                    when {
                        RubyReflection.rubyClassClass?.isInstance(element) == true -> EntityType.CLASS
                        RubyReflection.rubyMethodClass?.isInstance(element) == true -> EntityType.FUNCTION
                        RubyReflection.rubyConstantClass?.isInstance(element) == true -> {
                            if (RubyReflection.rubyFileClass?.isInstance(element.parent) == true) EntityType.PROPERTY else null
                        }
                        RubyReflection.rubyModuleClass?.isInstance(element) == true -> EntityType.OBJECT
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
