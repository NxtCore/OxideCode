package com.oxidecode.autocomplete

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.oxidecode.CoreBridge
import com.oxidecode.settings.OxideCodeSettings

/**
 * IntelliJ completion contributor that calls the Rust core for AI completions.
 *
 * Runs in a background thread (IntelliJ calls `fillCompletionVariants` off EDT).
 * We use a blocking JNI call here since IntelliJ's completion framework is
 * synchronous — the actual HTTP streaming happens inside the Rust tokio runtime.
 */
class OxideCompletionContributor : CompletionContributor() {

    override fun fillCompletionVariants(
        parameters: CompletionParameters,
        result: CompletionResultSet,
    ) {
        val settings = OxideCodeSettings.instance
        if (!settings.autocompleteEnabled) return

        val editor = parameters.editor
        val document = editor.document
        val offset = parameters.offset
        val project = parameters.originalFile.project

        val filepath = FileDocumentManager.getInstance()
            .getFile(document)
            ?.let { VfsUtilCore.getRelativePath(it, project.baseDir ?: return@let it.path) }
            ?: return

        val text = document.text
        val prefix = text.substring(0, offset)
        val suffix = text.substring(offset)
        val language = parameters.originalFile.language.id.lowercase()

        val bridge = service<CoreBridge>()

        val completion = runCatching {
            bridge.getCompletion(
                baseUrl = settings.baseUrl,
                apiKey = settings.apiKey,
                model = settings.model,
                completionModel = settings.completionModel,
                prefix = prefix,
                suffix = suffix,
                language = language,
                filepath = filepath,
                completionEndpoint = settings.completionEndpoint,
                promptStyle = settings.nesPromptStyle,
            )
        }.getOrNull()

        if (!completion.isNullOrBlank()) {
            result.addElement(
                LookupElementBuilder.create(completion)
                    .withPresentableText(completion.lines().firstOrNull() ?: completion)
                    .withTailText(" (OxideCode)", true)
            )
        }
    }
}
