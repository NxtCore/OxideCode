package com.oxidecode.controllers

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import com.intellij.util.messages.Topic
import com.oxidecode.services.CodeEntityExtractor
import com.oxidecode.settings.OxideCodeConfig
import com.oxidecode.utils.KeyPressedAdapter
import com.oxidecode.views.RoundedTextArea
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.event.CaretEvent
import javax.swing.event.CaretListener
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * [OxideCodeGhostText] listens to any text change in the [TextFieldComponent] and
 * suggests entity names (classes, functions, properties, etc.) from the currently
 * focused code file as ghost text completions.
 *
 * It displays the most frequently used matching entity as ghost text in the text field.
 * This uses a simple Bayesian prior: entities that appear more often in the file
 * are more likely to be what the user wants to type.
 */
@Service(Service.Level.PROJECT)
class OxideCodeGhostText(
    private val project: Project,
) : Disposable {
    companion object {
        val GHOST_TEXT_TOPIC = Topic.create("OxideCode Ghost Text Changes", GhostTextListener::class.java)

        fun getInstance(project: Project): OxideCodeGhostText = project.getService(OxideCodeGhostText::class.java)
    }

    interface GhostTextListener {
        fun onGhostTextChanged()
    }

    private val alarm: Alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val entityExtractor = CodeEntityExtractor.getInstance(project)
    private val GHOST_TEXT_DELAY = 100L

    // Keep track of attached listeners
    private val attachedListeners = mutableMapOf<RoundedTextArea, ListenerContainer>()

    // Container for all listeners attached to a specific text area
    private data class ListenerContainer(
        val keyListener: KeyListener,
        val documentListener: DocumentListener,
        val focusListener: FocusListener,
        val caretListener: CaretListener,
    )

    private var activeHolder: RoundedTextArea? = null
    private var lastGhostText: String = ""

    // Track the full matched entity name for optimization (skip search if user types matching chars)
    private var lastBestMatch: String = ""

    /**
     * Refresh entity cache when the focused file changes.
     * Uses the two-tier system from CodeEntityExtractor.
     */
    private fun refreshEntitiesIfNeeded() {
        // Skip if entity suggestions are disabled via config
        if (!OxideCodeConfig.getInstance(project).isEntitySuggestionsEnabled()) {
            return
        }

        // Only refresh if the file has changed
        if (entityExtractor.hasFileChanged()) {
            entityExtractor.refreshEntities()
        }
    }

    /**
     * Find all matching entities for the given prefix.
     * Returns entities that match (prioritized by tier: viewport first, then secondary).
     */
    private fun findMatches(prefix: String): List<String> {
        if (prefix.isEmpty()) return emptyList()

        val startTime = System.nanoTime()

        val lowercasePrefix = prefix.lowercase()

        // Get entities with priority ordering (Tier 1 viewport first, then Tier 2 secondary)
        val entities = entityExtractor.getEntityNames()

        // Find all entities that start with the prefix (case-insensitive)
        // Already sorted by priority (viewport first) and frequency within each tier
        val results =
            entities.filter { entity ->
                entity.lowercase().startsWith(lowercasePrefix) &&
                    entity.length > prefix.length // Must be longer than what user typed
            }

        return results
    }

    /**
     * Find the best matching entity for the given prefix.
     * Returns the first matching entity (prioritized by tier: viewport first, then secondary).
     */
    private fun findBestMatch(prefix: String): String? = findMatches(prefix).firstOrNull()

    private fun hasGhostText(): Boolean =
        activeHolder
            ?.let { holder ->
                val holderTextLength = holder.text.trim().length
                val ghostTextLength = lastGhostText.length
                ghostTextLength > holderTextLength
            } ?: false

    fun isGhostTextVisible(): Boolean = hasGhostText()

    /**
     * Explicitly clears any ghost text currently associated with the given [holder].
     *
     * This is primarily used when a message is "sent" from a [RoundedTextArea] that
     * we keep displayed (e.g. resending from a [UserMessageComponent]). In that flow
     * the document text does not change, so our normal document listeners don't get
     * a chance to clear the suggestion.
     *
     * If [holder] is null, this is a no-op.
     */
    fun clearGhostText(holder: RoundedTextArea?) {
        val targetHolder = holder ?: return

        // Cancel any pending suggestion requests to prevent them from re-setting ghost text
        alarm.cancelAllRequests()

        lastGhostText = ""
        lastBestMatch = ""
        targetHolder.setGhostText("")
        targetHolder.setFullGhostText("")

        if (!project.isDisposed) {
            project.messageBus.syncPublisher(GHOST_TEXT_TOPIC).onGhostTextChanged()
        }
    }

    fun attachGhostTextTo(holder: RoundedTextArea) {
        // Detach any existing listeners first
        detachGhostTextFrom(holder)

        val keyListener =
            KeyPressedAdapter { e ->
                if (e.keyCode == KeyEvent.VK_TAB) {
                    if (holder.caretPosition > 0 && holder.text[holder.caretPosition - 1] != '@') {
                        // Accept full ghost text
                        holder.acceptGhostText()
                    }
                    e.consume()
                }
            }

        val documentListener =
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = scheduleSuggestion(holder)

                override fun removeUpdate(e: DocumentEvent) = scheduleSuggestion(holder)

                override fun changedUpdate(e: DocumentEvent) = scheduleSuggestion(holder)
            }

        val focusListener =
            object : FocusListener {
                override fun focusGained(e: FocusEvent?) {
                    activeHolder = holder
                }

                override fun focusLost(e: FocusEvent?) {
                    if (activeHolder == holder) {
                        activeHolder = null
                    }
                }
            }

        val caretListener =
            object : CaretListener {
                override fun caretUpdate(e: CaretEvent?) {
                    val text = holder.text
                    val caretPos = e?.dot ?: holder.caretPosition

                    // Check if caret is at the end of the text (only whitespace follows)
                    val textAfterCaret = if (caretPos < text.length) text.substring(caretPos) else ""
                    val isAtEndOfText = textAfterCaret.isBlank()

                    // Check if caret is at the end of a word
                    // (after an alphanumeric char or underscore and before whitespace or end of text)
                    val isAtEndOfWord =
                        caretPos > 0 &&
                            (
                                text.getOrNull(caretPos - 1)?.isLetterOrDigit() == true ||
                                    text.getOrNull(caretPos - 1) == '_'
                            ) &&
                            (caretPos >= text.length || text.getOrNull(caretPos)?.isWhitespace() == true)

                    // Only show ghost text if at end of text AND at end of a word
                    val shouldShowGhostText = isAtEndOfText && isAtEndOfWord

                    if (!shouldShowGhostText && lastGhostText.isNotEmpty()) {
                        // Caret moved away from end of text/word - clear ghost text
                        lastGhostText = ""
                        lastBestMatch = ""
                        holder.setGhostText("")
                        holder.setFullGhostText("")
                        if (!project.isDisposed) {
                            project.messageBus.syncPublisher(GHOST_TEXT_TOPIC).onGhostTextChanged()
                        }
                    } else if (shouldShowGhostText && lastGhostText.isEmpty() && text.isNotEmpty()) {
                        // Caret moved to end of text at a word - re-trigger search for suggestions
                        scheduleSuggestion(holder)
                    }
                }
            }

        // Attach listeners
        holder.textArea.addKeyListener(keyListener)
        holder.addDocumentListener(documentListener)
        holder.textArea.addFocusListener(focusListener)
        holder.textArea.addCaretListener(caretListener)

        // Store references to listeners
        attachedListeners[holder] = ListenerContainer(keyListener, documentListener, focusListener, caretListener)
        Disposer.register(holder, Disposable { detachGhostTextFrom(holder) })
    }

    private fun detachGhostTextFrom(holder: RoundedTextArea) {
        attachedListeners[holder]?.let { container ->
            holder.textArea.removeKeyListener(container.keyListener)
            holder.textArea.document.removeDocumentListener(container.documentListener)
            holder.textArea.removeFocusListener(container.focusListener)
            holder.textArea.removeCaretListener(container.caretListener)
            attachedListeners.remove(holder)

            // Clear ghost text if this is the active holder
            if (activeHolder == holder) {
                clearGhostText(holder)
                activeHolder = null
            }
        }
    }

    private fun scheduleSuggestion(holder: RoundedTextArea) {
        // Return early if project is disposed
        if (project.isDisposed) return

        // Only show suggestions when the text area has focus
        if (!holder.textArea.hasFocus()) return

        // Always cancel pending requests first to prevent race conditions
        alarm.cancelAllRequests()

        val currentText = holder.text.trim()
        alarm.addRequest({
            val config = OxideCodeConfig.getInstance(project)
            if (!config.isEntitySuggestionsEnabled()) {
                ApplicationManager.getApplication().invokeLater {
                    if (lastGhostText.isNotEmpty()) {
                        lastGhostText = ""
                        lastBestMatch = ""
                        holder.setGhostText("")
                    }
                }
                return@addRequest
            }

            // Refresh entities if needed (file may have changed)
            refreshEntitiesIfNeeded()

            if (currentText.isNotEmpty()) {
                // Get the word at caret position (entity names are single words)
                val caretPos = holder.caretPosition
                val text = holder.text

                // Only show ghost text if caret is at the end of the text (or only whitespace follows)
                val textAfterCaret = if (caretPos < text.length) text.substring(caretPos) else ""
                val isAtEndOfText = textAfterCaret.isBlank()
                if (!isAtEndOfText) {
                    ApplicationManager.getApplication().invokeLater {
                        if (lastGhostText.isNotEmpty()) {
                            lastGhostText = ""
                            lastBestMatch = ""
                            holder.setGhostText("")
                            holder.setFullGhostText("")
                            if (!project.isDisposed) {
                                project.messageBus.syncPublisher(GHOST_TEXT_TOPIC).onGhostTextChanged()
                            }
                        }
                    }
                    return@addRequest
                }

                // Find the start of the word at caret position
                // Look backwards to the earliest non alphanumeric or _ character
                var wordStart = caretPos
                while (wordStart > 0 &&
                    (text.getOrNull(wordStart - 1)?.isLetterOrDigit() == true || text.getOrNull(wordStart - 1) == '_')
                ) {
                    wordStart--
                }
                val wordAtCaret = if (caretPos > wordStart) text.substring(wordStart, caretPos) else ""

                // Optimization: Check if user is typing characters that match the current suggestion
                // If so, we can skip the search entirely
                val canSkipSearch =
                    lastBestMatch.isNotEmpty() &&
                        wordAtCaret.length >= 3 &&
                        lastBestMatch.lowercase().startsWith(wordAtCaret.lowercase()) &&
                        lastBestMatch.length > wordAtCaret.length

                val bestMatch =
                    if (canSkipSearch) {
                        // User is typing chars that continue to match - reuse the same match
                        lastBestMatch
                    } else if (wordAtCaret.length >= 3) {
                        // Need to do a fresh search
                        val matches = findMatches(wordAtCaret)
                        val candidate = matches.firstOrNull()

                        // Check if we're already showing a valid suggestion
                        // If so, keep showing it regardless of non-alphanumeric rules
                        val isAlreadyShowingSuggestion = lastGhostText.isNotEmpty()
                        // lastGhostText is just the completion part, so wordAtCaret + lastGhostText should equal candidate
                        val currentSuggestionStillValid =
                            isAlreadyShowingSuggestion &&
                                candidate != null &&
                                (wordAtCaret + lastGhostText) == candidate

                        if (currentSuggestionStillValid) {
                            // Keep showing the current valid suggestion
                            candidate
                        } else if (isAlreadyShowingSuggestion && candidate != null) {
                            // We're showing a suggestion but it changed - allow the new one
                            candidate
                        } else {
                            // Not showing a suggestion yet - apply non-alphanumeric filtering rules
                            val lastChar = wordAtCaret.lastOrNull()
                            val isLastCharAlphanumeric = lastChar?.isLetterOrDigit() == true

                            if (isLastCharAlphanumeric) {
                                // For alphanumeric characters, check if completion starts with non-alphanumeric
                                if (candidate != null) {
                                    val completionStart = candidate.getOrNull(wordAtCaret.length)
                                    val isCompletionStartAlphanumeric = completionStart?.isLetterOrDigit() == true
                                    if (isCompletionStartAlphanumeric || matches.size == 1) {
                                        candidate
                                    } else {
                                        // Completion starts with non-alphanumeric and multiple matches - don't suggest
                                        null
                                    }
                                } else {
                                    null
                                }
                            } else {
                                // For non-alphanumeric (like '_'), only show if there's exactly one match
                                if (matches.size == 1) matches.first() else null
                            }
                        }
                    } else {
                        null
                    }

                ApplicationManager.getApplication().invokeLater {
                    if (Disposer.isDisposed(holder)) return@invokeLater

                    if (bestMatch != null && bestMatch.length > wordAtCaret.length) {
                        // Ghost text should only show the completion part (what gets added after caret)
                        val completionPart = bestMatch.substring(wordAtCaret.length)
                        // Full suggestion is the entire text with the completion inserted
                        val prefixBeforeWord = text.substring(0, wordStart)
                        val suffixAfterCaret = text.substring(caretPos)
                        val fullSuggestion = prefixBeforeWord + bestMatch + suffixAfterCaret

                        // ghostTextForRendering needs to start with the full text so rendering code's
                        // ghostText.startsWith(text) check passes. It's: prefix + matched entity
                        val ghostTextForRendering = prefixBeforeWord + bestMatch

                        // lastGhostText stores just the completion for comparison logic
                        lastGhostText = completionPart
                        // lastBestMatch stores the full entity for skip-search optimization
                        lastBestMatch = bestMatch
                        // setGhostText needs text that starts with user's input (rendering code expects ghostText.startsWith(userText))
                        // Pass caretPos so rendering knows where to draw the ghost text (supports mid-text completions)
                        holder.setGhostText(ghostTextForRendering, caretPos)
                        holder.setFullGhostText(fullSuggestion)

                        if (!project.isDisposed) {
                            project.messageBus.syncPublisher(GHOST_TEXT_TOPIC).onGhostTextChanged()
                        }
                    } else {
                        lastGhostText = ""
                        lastBestMatch = ""
                        holder.setGhostText("")
                        if (!project.isDisposed) {
                            project.messageBus.syncPublisher(GHOST_TEXT_TOPIC).onGhostTextChanged()
                        }
                    }
                }
            } else {
                ApplicationManager.getApplication().invokeLater {
                    if (lastGhostText.isNotEmpty()) {
                        lastGhostText = ""
                        lastBestMatch = ""
                        holder.setGhostText("")
                        if (!project.isDisposed) {
                            project.messageBus.syncPublisher(GHOST_TEXT_TOPIC).onGhostTextChanged()
                        }
                    }
                }
            }
        }, GHOST_TEXT_DELAY)
    }

    override fun dispose() {
        // Clean up all attached listeners
        attachedListeners.keys.toList().forEach { holder ->
            detachGhostTextFrom(holder)
        }
        alarm.dispose()
    }
}
