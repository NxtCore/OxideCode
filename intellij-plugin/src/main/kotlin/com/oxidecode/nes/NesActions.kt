package com.oxidecode.nes

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager

class AcceptNesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.project?.let {
            FileEditorManager.getInstance(it).selectedTextEditor
        } ?: return
        NesHintManager.accept(editor)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = NesHintManager.activeHint != null
    }
}

class DismissNesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.project?.let {
            FileEditorManager.getInstance(it).selectedTextEditor
        } ?: return
        NesHintManager.dismiss(editor)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = NesHintManager.activeHint != null
    }
}
