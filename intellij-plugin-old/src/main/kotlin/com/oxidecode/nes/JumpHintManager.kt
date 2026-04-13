package com.oxidecode.nes

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBViewport
import com.oxidecode.editor.TabJumpHintInlayRenderer
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JComponent

class JumpHintManager(
    private val editor: Editor,
    private val project: Project,
    private val targetLineNumber: Int,
    private val lineStartOffset: Int,
    parentDisposable: Disposable,
) : Disposable {
    private var jumpPopup: JBPopup? = null
    private var scrollListener: VisibleAreaListener? = null
    private var currentEditor: Editor? = null
    private var inlineInlay: Inlay<*>? = null
    private val wasVisibleOnCreation = isLineVisible(editor, lineStartOffset)

    init {
        Disposer.register(parentDisposable, this)
    }

    fun showIfNeeded() {
        createJumpInlay()
        scrollListener = VisibleAreaListener { e: VisibleAreaEvent ->
            updateVisibility(e.editor, targetLineNumber, lineStartOffset)
        }

        FileEditorManager.getInstance(project).selectedTextEditor?.let { selectedEditor ->
            selectedEditor.scrollingModel.addVisibleAreaListener(scrollListener!!)
            currentEditor = selectedEditor
        }

        updateVisibility(editor, targetLineNumber, lineStartOffset)
    }

    private fun createJumpInlay() {
        if (inlineInlay != null) return
        val lineEndOffset = editor.document.getLineEndOffset(targetLineNumber)
        val properties = InlayProperties().relatesToPrecedingText(true).disableSoftWrapping(true)
        inlineInlay = editor.inlayModel.addInlineElement(lineEndOffset, properties, TabJumpHintInlayRenderer(editor, this))
    }

    private fun updateVisibility(editor: Editor, lineNumber: Int, lineStartOffset: Int) {
        val visible = wasVisibleOnCreation || isLineVisible(editor, lineStartOffset)
        if (visible) {
            jumpPopup?.dispose()
            jumpPopup = null
        } else if (jumpPopup == null) {
            showJumpPopup(editor, lineNumber)
        }
    }

    private fun isLineVisible(editor: Editor, lineStartOffset: Int): Boolean {
        val visibleArea: Rectangle = editor.scrollingModel.visibleArea
        val lineStartY = editor.offsetToPoint2D(lineStartOffset).y
        val lineEndY = lineStartY + editor.lineHeight
        return lineStartY <= visibleArea.y + visibleArea.height && lineEndY >= visibleArea.y
    }

    private fun showJumpPopup(editor: Editor, targetLineNumber: Int) {
        jumpPopup?.dispose()
        val visibleArea = editor.scrollingModel.visibleArea
        val targetLineY = editor.visualLineToY(targetLineNumber)
        val isTargetBelow = targetLineY > visibleArea.y + visibleArea.height
        val renderer = JumpHintPopupRenderer(editor, isTargetBelow, this)
        val component = renderer.createComponent()
        jumpPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(component, null)
            .setResizable(false)
            .setMovable(true)
            .setRequestFocus(false)
            .setTitle(null)
            .setCancelOnClickOutside(true)
            .setShowBorder(false)
            .createPopup()

        val editorComponent = editor.contentComponent
        val viewport = editorComponent.parent as? JBViewport
        val relativeComponent = (viewport as? JComponent) ?: editorComponent
        val point = Point(
            relativeComponent.width / 2 - component.preferredSize.width / 2,
            if (isTargetBelow) relativeComponent.height - 20 - component.preferredSize.height else 20,
        )
        jumpPopup?.show(RelativePoint(relativeComponent, point))
    }

    override fun dispose() {
        jumpPopup?.dispose()
        jumpPopup = null
        scrollListener?.let { listener -> currentEditor?.scrollingModel?.removeVisibleAreaListener(listener) }
        scrollListener = null
        currentEditor = null
        inlineInlay?.dispose()
        inlineInlay = null
    }
}
