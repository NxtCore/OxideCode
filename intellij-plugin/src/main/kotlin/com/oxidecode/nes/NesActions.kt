package com.oxidecode.nes

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.oxidecode.autocomplete.InlineCompletionManager

class AcceptNesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.project?.let {
            FileEditorManager.getInstance(it).selectedTextEditor
        } ?: return
        when {
            InlineCompletionManager.isShowing(editor) -> InlineCompletionManager.accept(editor)
            NesHintManager.isShowing(editor) -> NesHintManager.accept(editor)
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.project?.let {
            FileEditorManager.getInstance(it).selectedTextEditor
        }
        e.presentation.isEnabled =
            InlineCompletionManager.isShowing(editor) || NesHintManager.isShowing(editor)
    }
}

class DismissNesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.project?.let {
            FileEditorManager.getInstance(it).selectedTextEditor
        } ?: return
        InlineCompletionManager.dismiss(editor)
        NesHintManager.dismiss(editor)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.project?.let {
            FileEditorManager.getInstance(it).selectedTextEditor
        }
        e.presentation.isEnabled =
            InlineCompletionManager.isShowing(editor) || NesHintManager.isShowing(editor)
    }
}
