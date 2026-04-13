package dev.sweep.assistant.startup

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.sweep.assistant.views.CodeBlockDisplay
import dev.sweep.assistant.views.MarkdownDisplay
import java.awt.Component
import java.awt.Container

class FindActionInitializer : ProjectActivity {
    override suspend fun execute(project: Project) {
        val editorActionManager = EditorActionManager.getInstance()
        val originalFindHandler = editorActionManager.getActionHandler(IdeActions.ACTION_FIND)

        editorActionManager.setActionHandler(
            IdeActions.ACTION_FIND,
            object : EditorActionHandler() {
                override fun doExecute(
                    editor: Editor,
                    caret: Caret?,
                    dataContext: DataContext?,
                ) {
                    var component: Component? = editor.component
                    var isInCodeBlock = false
                    var markdownDisplay: MarkdownDisplay? = null

                    while (component != null) {
                        when (component) {
                            is CodeBlockDisplay -> isInCodeBlock = true
                            is MarkdownDisplay -> {
                                markdownDisplay = component
                                break
                            }
                        }
                        component = component.parent
                    }

                    if (isInCodeBlock && markdownDisplay != null) {
                        // Find all MarkdownDisplay instances in the same messages panel
                        val allDisplays = findAllMarkdownDisplays(markdownDisplay)
                        markdownDisplay.showFindDialog(allDisplays)
                        return
                    }

                    originalFindHandler.execute(editor, caret, dataContext)
                }

                private fun findAllMarkdownDisplays(markdownDisplay: MarkdownDisplay): List<MarkdownDisplay> {
                    // Traverse up to find a container that has multiple children containing MarkdownDisplays
                    // This is typically the messagesPanel which contains LazyMessageSlot components
                    var component: Component? = markdownDisplay.parent

                    while (component != null) {
                        if (component is Container) {
                            // Collect all MarkdownDisplay instances from this container's children
                            val displays = collectMarkdownDisplays(component)
                            // If we found multiple displays (or at least the current one), use this container
                            if (displays.size > 1 || (displays.size == 1 && displays.contains(markdownDisplay))) {
                                // Continue up to find the highest container with MarkdownDisplays
                                val parent = component.parent
                                if (parent is Container) {
                                    val parentDisplays = collectMarkdownDisplays(parent)
                                    if (parentDisplays.size >= displays.size) {
                                        component = parent
                                        continue
                                    }
                                }
                                return displays
                            }
                        }
                        component = component.parent
                    }

                    // Fallback to just the current display
                    return listOf(markdownDisplay)
                }

                private fun collectMarkdownDisplays(container: Container): List<MarkdownDisplay> {
                    val result = mutableListOf<MarkdownDisplay>()
                    for (child in container.components) {
                        when (child) {
                            is MarkdownDisplay -> result.add(child)
                            is Container -> {
                                // Check immediate children of slots (LazyMessageSlot pattern)
                                for (grandchild in child.components) {
                                    if (grandchild is MarkdownDisplay) {
                                        result.add(grandchild)
                                    }
                                }
                            }
                        }
                    }
                    return result
                }
            },
        )
    }
}
