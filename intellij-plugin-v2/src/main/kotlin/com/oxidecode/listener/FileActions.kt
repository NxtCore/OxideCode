package com.oxidecode.listener

import com.intellij.openapi.vfs.VirtualFile

data class FileChangedAction(
    val identifier: String,
    val onFileChanged: (VirtualFile?, VirtualFile?) -> Unit,
)

data class FileEditedAction(
    val identifier: String,
    val onFileEdited: (VirtualFile?) -> Unit,
)
