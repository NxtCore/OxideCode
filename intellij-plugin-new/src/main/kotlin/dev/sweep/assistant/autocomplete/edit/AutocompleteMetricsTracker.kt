package dev.sweep.assistant.autocomplete.edit

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PermanentInstallationID
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.data.BaseRequest
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.getConnection
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AutocompleteMetricsRequest(
    val event_type: String,
    val suggestion_type: String? = null,
    val additions: Int,
    val deletions: Int,
    val autocomplete_id: String,
    val edit_tracking: String? = null, // File contents after 30 seconds
    val edit_tracking_15: String? = null, // File contents after 15 seconds
    val edit_tracking_30: String? = null, // File contents after 30 seconds
    val edit_tracking_60: String? = null, // File contents after 60 seconds
    val edit_tracking_120: String? = null, // File contents after 120 seconds
    val edit_tracking_300: String? = null, // File contents after 300 seconds
    val edit_tracking_line: FileChunk? = null,
    val lifespan: Long? = null,
    val privacy_mode_enabled: Boolean = false,
    val num_definitions_retrieved: Int? = null,
    val num_usages_retrieved: Int? = null,
) : BaseRequest()

@Service(Service.Level.PROJECT)
class AutocompleteMetricsTracker(
    private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): AutocompleteMetricsTracker = project.getService(AutocompleteMetricsTracker::class.java)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun dispose() {
        scope.cancel()
    }

    fun trackSuggestionShown(suggestion: AutocompleteSuggestion) {
        val suggestionType = suggestion.type
        val additionsAndDeletions = Pair(suggestion.suggestionAdditions, suggestion.suggestionDeletions)
        val autocompleteId = suggestion.autocomplete_id
        val numDefinitions = suggestion.numDefinitionsRetrieved
        val numUsages = suggestion.numUsagesRetrieved
        scope.launch {
            delay(1 * 1000)
            sendRequest(
                "autocomplete_suggestion_shown",
                suggestionType.name,
                additionsAndDeletions,
                autocompleteId,
                numDefinitionsRetrieved = numDefinitions,
                numUsagesRetrieved = numUsages,
            )
        }
    }

    fun trackSuggestionDisposed(suggestion: AutocompleteSuggestion) {
        val suggestionType = suggestion.type
        val additionsAndDeletions = Pair(suggestion.suggestionAdditions, suggestion.suggestionDeletions)
        val autocompleteId = suggestion.autocomplete_id
        val lifespan = suggestion.getLifespan()
        val numDefinitions = suggestion.numDefinitionsRetrieved
        val numUsages = suggestion.numUsagesRetrieved
        scope.launch {
            delay(5 * 1000)
            sendRequest(
                "autocomplete_suggestion_disposed",
                suggestionType.name,
                additionsAndDeletions,
                autocompleteId,
                lifespan = lifespan,
                numDefinitionsRetrieved = numDefinitions,
                numUsagesRetrieved = numUsages,
            )
        }
    }

    fun trackSuggestionAccepted(suggestion: AutocompleteSuggestion) {
        val suggestionType = suggestion.type
        val additionsAndDeletions = Pair(suggestion.suggestionAdditions, suggestion.suggestionDeletions)
        val autocompleteId = suggestion.autocomplete_id
        val numDefinitions = suggestion.numDefinitionsRetrieved
        val numUsages = suggestion.numUsagesRetrieved
        scope.launch {
            delay(5 * 1000)
            sendRequest(
                "autocomplete_suggestion_accepted",
                suggestionType.name,
                additionsAndDeletions,
                autocompleteId,
                numDefinitionsRetrieved = numDefinitions,
                numUsagesRetrieved = numUsages,
            )
        }
    }

    fun trackFileContentsAfterDelay(
        document: Document,
        rangeMarker: RangeMarker? = null,
        suggestionType: String,
        additionsAndDeletions: Pair<Int, Int> = Pair(0, 0),
        autocompleteId: String,
    ) {
        scope.launch(Dispatchers.IO + CoroutineName("LineStateTracker")) {
            try {
                // for each interval: 15s, 30s, 60s, 120s, 300s, we wait and send a request
                val intervals = listOf(15, 30, 60, 120, 300) // seconds
                var lastTime = 0

                for (interval in intervals) {
                    val delayTime = interval - lastTime
                    delay(delayTime * 1000L)
                    lastTime = interval

                    val editTrackingLine =
                        if (rangeMarker?.isValid == true) {
                            ReadAction
                                .nonBlocking<FileChunk?> {
                                    val doc = rangeMarker.document
                                    val lineContent =
                                        doc.getText(
                                            com.intellij.openapi.util.TextRange(
                                                rangeMarker.startOffset,
                                                rangeMarker.endOffset,
                                            ),
                                        )
                                    val startLine = doc.getLineNumber(rangeMarker.startOffset)
                                    val endLine = doc.getLineNumber(rangeMarker.endOffset)

                                    FileChunk(
                                        file_path = "",
                                        start_line = startLine,
                                        end_line = endLine,
                                        content = lineContent,
                                    )
                                }.submit(AppExecutorUtil.getAppExecutorService())
                                .get()
                        } else {
                            null
                        }

                    val text = document.text

                    sendRequest(
                        event_type = "autocomplete_edit_tracking",
                        suggestionType = suggestionType,
                        additionsAndDeletions = additionsAndDeletions,
                        autocompleteId = autocompleteId,
                        edit_tracking = if (interval == 30) text else null,
                        edit_tracking_15 = if (interval == 15) text else null,
                        edit_tracking_30 = if (interval == 30) text else null,
                        edit_tracking_60 = if (interval == 60) text else null,
                        edit_tracking_120 = if (interval == 120) text else null,
                        edit_tracking_300 = if (interval == 300) text else null,
                        edit_tracking_line = editTrackingLine,
                    )
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                println("Error getting file contents after delay: ${e.message}")
            } finally {
                // Dispose the range marker and highlighter
                try {
                    rangeMarker?.dispose()
                } catch (e: Exception) {
                    println("Error disposing range marker: ${e.message}")
                }
            }
        }
    }

    fun sendRequest(
        event_type: String,
        suggestionType: String,
        additionsAndDeletions: Pair<Int, Int>,
        autocompleteId: String,
        edit_tracking: String? = null,
        edit_tracking_15: String? = null,
        edit_tracking_30: String? = null,
        edit_tracking_60: String? = null,
        edit_tracking_120: String? = null,
        edit_tracking_300: String? = null,
        edit_tracking_line: FileChunk? = null,
        lifespan: Long? = null,
        numDefinitionsRetrieved: Int? = null,
        numUsagesRetrieved: Int? = null,
    ) {
        scope.launch(Dispatchers.IO + CoroutineName("AutocompleteMetricsTracker")) {
            try {
                val (additions, deletions) = additionsAndDeletions
                val autocompleteRequest =
                    AutocompleteMetricsRequest(
                        event_type = event_type,
                        suggestion_type = suggestionType,
                        additions = additions,
                        deletions = deletions,
                        autocomplete_id = autocompleteId,
                        edit_tracking = edit_tracking,
                        edit_tracking_15 = edit_tracking_15,
                        edit_tracking_30 = edit_tracking_30,
                        edit_tracking_60 = edit_tracking_60,
                        edit_tracking_120 = edit_tracking_120,
                        edit_tracking_300 = edit_tracking_300,
                        edit_tracking_line = edit_tracking_line,
                        lifespan = lifespan,
                        privacy_mode_enabled = SweepConfig.getInstance(project).isPrivacyModeEnabled(),
                        num_definitions_retrieved = numDefinitionsRetrieved,
                        num_usages_retrieved = numUsagesRetrieved,
                    )

                val authorization =
                    if (SweepSettings.getInstance().githubToken.isBlank()) {
                        "Bearer device_id_${PermanentInstallationID.get()}"
                    } else {
                        "Bearer ${SweepSettings.getInstance().githubToken}"
                    }

                withTimeout(3000) {
                    val connection = getConnection("backend/track_autocomplete_metrics", authorization = authorization)
                    val json = Json { encodeDefaults = true }
                    val postData =
                        json.encodeToString(
                            AutocompleteMetricsRequest.serializer(),
                            autocompleteRequest,
                        )
                    connection.outputStream.use { os ->
                        os.write(postData.toByteArray())
                        os.flush()
                    }
                    connection.inputStream.bufferedReader().use { it.readText() }
                    connection.disconnect()
                }
            } catch (e: Exception) {
                // Just log the error without affecting user experience
                println("Error tracking autocomplete metrics: ${e.message}")
            }
        }
    }
}
