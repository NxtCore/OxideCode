package dev.sweep.assistant.controllers

import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.theme.SweepIcons.scale
import dev.sweep.assistant.utils.*
import dev.sweep.assistant.views.RoundedButton
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.awt.Cursor
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ContainerListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

@Service(Level.PROJECT)
class EditorSelectionManager(
    private val project: Project,
) : Disposable {
    companion object {
        private const val POPUP_BUFFER_ZONE = 300

        @JvmStatic
        fun getInstance(project: Project): EditorSelectionManager = project.getService(EditorSelectionManager::class.java)
    }

    private val logger = Logger.getInstance(EditorSelectionManager::class.java)
    private var currentPopup: JBPopup? = null
    private var editorFactoryListener: EditorFactoryListener? = null
    private val editorToListenerMap = mutableMapOf<Editor, EditorMouseListener>()
    private val terminalContainerListeners = mutableMapOf<java.awt.Container, ContainerListener>()
    private var previousSelection: String? = null
    private var currentEditor: Editor? = null
    private var currentSelectionEnd: Int = 0

    fun getCurrentEditor(): Editor? = currentEditor

    fun getEditorListeners(): Map<Editor, EditorMouseListener> = editorToListenerMap

    init {
        editorFactoryListener =
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    val editor = event.editor
                    // Only attach listeners if the editor belongs to this project.
                    if (editor.project == project && editor.editorKind == EditorKind.CONSOLE) {
                        attachSelectionListener(editor)
                        attachScrollListener(editor)
                    }
                }

                override fun editorReleased(event: EditorFactoryEvent) {
                    cleanupEditor(event.editor)
                }
            }.also { listener ->
                EditorFactory.getInstance().addEditorFactoryListener(listener, this)
            }
        searchForExistingReworkedTerminals(project)
    }

    private fun searchForExistingReworkedTerminals(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val terminalToolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
            terminalToolWindow?.contentManager?.contents?.forEach { content ->
                val widget: TerminalWidget? = TerminalToolWindowManager.findWidgetByContent(content)
                if (widget != null) {
                    // Works with both Classic and Reworked engines. For Reworked we can resolve the Editor:
                    val editor =
                        DataManager
                            .getInstance()
                            .getDataContext(TerminalToolWindowManager.findWidgetByContent(content)?.component)
                            .getData(CommonDataKeys.EDITOR)
                    if (editor != null) {
                        attachSelectionListener(editor)
                        attachScrollListener(editor)
                    }
                }
            }
        }
    }

    private fun isPopupVisible(
        visibleArea: Rectangle,
        editorPoint: Point,
        popupWidth: Int,
        popupHeight: Int,
        bufferZone: Int = POPUP_BUFFER_ZONE,
    ): Boolean =
        visibleArea.contains(editorPoint) &&
            editorPoint.x + popupWidth <= visibleArea.x + visibleArea.width &&
            editorPoint.y + popupHeight <= visibleArea.y + visibleArea.height + bufferZone

    private fun attachScrollListener(editor: Editor) {
        if (editor.project != project) return
        editor.scrollingModel.addVisibleAreaListener { _ ->
            if (editor == currentEditor && previousSelection != null) {
                if (currentPopup?.isVisible != true) {
                    val visibleArea = editor.scrollingModel.visibleArea
                    val caretPointEditorSpace = editor.offsetToXY(currentSelectionEnd)
                    caretPointEditorSpace.x += 200
                    caretPointEditorSpace.y += 20

                    val popupPoint = RelativePoint(editor.contentComponent, caretPointEditorSpace)
                    val editorPoint =
                        RelativePoint(popupPoint.screenPoint)
                            .getPoint(editor.contentComponent)

                    if (isPopupVisible(visibleArea, editorPoint, 0, 0)) {
                        showSelectionPopup(editor)
                    }
                } else {
                    updatePopupPosition()
                }
            }
        }
    }

    private fun updatePopupPosition() {
        currentEditor?.let { editor ->
            val caretPointEditorSpace = editor.offsetToXY(currentSelectionEnd)
            caretPointEditorSpace.x += 200
            caretPointEditorSpace.y += 20

            val visibleArea = editor.scrollingModel.visibleArea
            val popupPoint = RelativePoint(editor.contentComponent, caretPointEditorSpace)
            val screenPoint = popupPoint.screenPoint
            val popupComponent = currentPopup?.content
            val popupWidth = popupComponent?.width ?: 0
            val popupHeight = popupComponent?.height ?: 0
            val editorPoint = RelativePoint(screenPoint).getPoint(editor.contentComponent)

            val isVisible = isPopupVisible(visibleArea, editorPoint, popupWidth, popupHeight)

            if (isVisible) {
                currentPopup?.setLocation(popupPoint.screenPoint)
            } else {
                currentPopup?.cancel()
                currentPopup = null
            }
        }
    }

    fun attachSelectionListener(editor: Editor) {
        if (editor.project != project) return

        val listener =
            object : EditorMouseListener {
                override fun mouseReleased(event: EditorMouseEvent) {
                    if (editor.project != project) return

                    var parent = editor.component.parent
                    while (parent != null) {
                        val parentClassName = parent.javaClass.name
                        if (parentClassName.startsWith("dev.sweep.assistant.views") ||
                            parentClassName.contains("commit.CommitPanel")
                        ) {
                            return
                        }
                        parent = parent.parent
                    }

                    val selectedText = editor.selectionModel.selectedText

                    if (selectedText.isNullOrEmpty()) {
                        currentPopup?.cancel()
                        currentPopup = null
                        previousSelection = null
                        currentEditor = null
                    } else if (selectedText != previousSelection) {
                        // Only show popup if the selection has changed
                        if (editor.selectionModel.hasSelection() &&
                            isTerminalEditor(event) &&
                            isValidSelection(selectedText)
                        ) {
                            showSelectionPopup(editor)
                        }
                        previousSelection = selectedText
                    }
                }
            }

        // Store the listener so we can clean it up later.
        editorToListenerMap[editor] = listener
        editor.addEditorMouseListener(listener)
    }

    private fun showSelectionPopup(editor: Editor) {
        if (editor.project != project) return
        if (!SweepConfig.getInstance(project).isShowTerminalAddToSweepButtonEnabled()) {
            return
        }

        currentPopup?.cancel()
        val selectedText = editor.selectionModel.selectedText
        currentEditor = editor
        currentSelectionEnd = editor.selectionModel.selectionEnd

        val addToChatButton =
            RoundedButton(
                text = "Add to Sweep",
                onClick = {
                    appendSelectionToChat(project, selectedText, "ConsoleOutput", logger)
                    currentPopup?.cancel()
                    currentPopup = null
                },
            ).apply {
                icon = SweepIcons.SweepIcon.scale(16f)
                withSweepFont(project, scale = 1.05f)
                border = JBUI.Borders.empty(8)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                isOpaque = false
                background = null
                secondaryText = "${SweepConstants.META_KEY}J"
                addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseEntered(e: MouseEvent) {
                            background = SweepColors.hoverableBackgroundColor
                        }

                        override fun mouseExited(e: MouseEvent) {
                            background = null
                        }
                    },
                )
            }

        val popup =
            JBPopupFactory
                .getInstance()
                .createComponentPopupBuilder(addToChatButton, null)
                .setCancelOnClickOutside(true)
                .setCancelOnOtherWindowOpen(true)
                .setCancelOnWindowDeactivation(true)
                .setLocateWithinScreenBounds(true)
                .setFocusable(false)
                .setRequestFocus(false)
                .setBelongsToGlobalPopupStack(false)
                .createPopup()

        val caretPointEditorSpace = editor.offsetToXY(currentSelectionEnd)
        caretPointEditorSpace.x += 200
        caretPointEditorSpace.y += 20

        val visibleArea = editor.scrollingModel.visibleArea
        val popupPoint = RelativePoint(editor.contentComponent, caretPointEditorSpace)
        val screenPoint = popupPoint.screenPoint
        val popupWidth = addToChatButton.preferredSize.width
        val popupHeight = addToChatButton.preferredSize.height
        val editorPoint = RelativePoint(screenPoint).getPoint(editor.contentComponent)

        val isVisible = isPopupVisible(visibleArea, editorPoint, popupWidth, popupHeight)

        if (isVisible) {
            popup.show(popupPoint)
            currentPopup = popup
        }
    }

    private fun cleanupEditor(editor: Editor) {
        editorToListenerMap[editor]?.let { listener ->
            editor.removeEditorMouseListener(listener)
            editorToListenerMap.remove(editor)
        }
    }

    override fun dispose() {
        editorFactoryListener = null

        editorToListenerMap.forEach { (editor, listener) ->
            editor.removeEditorMouseListener(listener)
        }
        editorToListenerMap.clear()

        terminalContainerListeners.forEach { (container, listener) ->
            container.removeContainerListener(listener)
        }
        terminalContainerListeners.clear()

        currentPopup?.cancel()
        currentPopup = null
        previousSelection = null
        currentEditor = null
    }
}
