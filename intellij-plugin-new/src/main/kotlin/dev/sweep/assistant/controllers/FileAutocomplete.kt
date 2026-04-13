package dev.sweep.assistant.controllers

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.util.width
import com.intellij.util.Alarm
import com.intellij.util.Consumer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.sweep.assistant.components.FilesInContextComponent
import dev.sweep.assistant.data.RecentlyUsedFiles
import dev.sweep.assistant.entities.EntitiesCache
import dev.sweep.assistant.entities.EntityType
import dev.sweep.assistant.listener.FileRenameListener
import dev.sweep.assistant.services.FileRankingService
import dev.sweep.assistant.services.FileSearcher
import dev.sweep.assistant.services.FileUsageManager
import dev.sweep.assistant.services.SweepNonProjectFilesService
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.theme.RoundedHighlightPainter
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.theme.SweepIcons.scale
import dev.sweep.assistant.utils.*
import dev.sweep.assistant.utils.MentionSpan
import dev.sweep.assistant.utils.MentionUtils.computeMentionSpans
import dev.sweep.assistant.utils.MentionUtils.findMentionSpanAtCaretStrict
import dev.sweep.assistant.utils.MentionUtils.findMentionSpanAtOrAdjacent
import dev.sweep.assistant.views.RoundedTextArea
import io.ktor.util.collections.*
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.geom.Rectangle2D
import java.io.File
import java.util.*
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.event.CaretListener
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.Highlighter

class MentionMapWrapper(
    val project: Project,
    private val currentFileManager: CurrentFileInContextManager,
    private val onFilesChanged: (MutableMap<String, String>, (String) -> Unit) -> Unit,
    private val removeText: (String) -> Unit,
    private val highlightText: (Boolean) -> Unit,
    private val normalizeDirectoryKey: (String) -> String,
    private val invalidateCache: () -> Unit,
) : ConcurrentOrderedHashMap<String, String>() {
    var currentOpenFile: Pair<String, String>? = null

    override fun put(
        key: String,
        value: String,
    ): String? {
        val result = super.put(key, value)
        invalidateCache() // Invalidate cache when mentions change
        sendNotification()
        return result
    }

    fun removeAndUpdateText(key: String): String? {
        val result = remove(key)
        if (result != null) {
            removeText(key)
        }
        return result
    }

    override fun remove(key: String): String? {
        val result = super.remove(key)
        invalidateCache() // Invalidate cache when mentions change
        sendNotification()
        return result
    }

    override fun clear() {
        super.clear()
        invalidateCache() // Invalidate cache when mentions change
        sendNotification()
    }

    private fun sendNotification() {
        onFilesChanged(copy()) { removeAndUpdateText(it) }
    }

    fun putWithoutOverriding(
        value: String,
        sendNotification: Boolean = true,
        isDirectory: Boolean = false,
    ): String? {
        // Check if the exact filepath is already in the map (for both files and directories)
        if (value in this.values) {
            // Find and return the existing key for this filepath
            return entries.find { it.value == value }?.key
        }

        // Handle directory listing - if value starts with "/" it's a directory request
        if (isDirectory) {
            val projectBasePath = project.basePath ?: return null
            val directory = File(projectBasePath, value)

            if (directory.exists() && directory.isDirectory) {
                // Apply the same disambiguation logic as files for directories
                val openFile = currentOpenFile
                var currentKey = ""
                // If the directory is open and has a file pill, we should use the same name
                if (openFile != null && openFile.second == value) {
                    currentKey = openFile.first
                } else {
                    // Use File to properly handle path components regardless of separator style
                    val components = directory.path.split(File.separator).reversed()
                    if (components.isEmpty()) return null
                    currentKey = components[0]
                    var componentIndex = 1

                    // Avoid name collision
                    while (containsKey(normalizeDirectoryKey(currentKey)) ||
                        containsKey(currentKey) ||
                        (openFile != null && openFile.first == currentKey)
                    ) {
                        if (componentIndex < components.size) {
                            currentKey = components[componentIndex] + File.separator + currentKey
                            componentIndex++
                        } else {
                            currentKey = "$currentKey`"
                        }
                    }
                }

                // Later use ListFilesTool.execute
                // If the file is open and has a file pill, we should use the same name
                super.put(normalizeDirectoryKey(currentKey), value)

                if (sendNotification) sendNotification()
                return currentKey
            }
            return null
        }

        val openFile = currentOpenFile
        var currentKey = ""
        // If the file is open and has a file pill, we should use the same name
        if (openFile != null && openFile.second == value) {
            currentKey = openFile.first
        } else {
            // Use File to properly handle path components regardless of separator style
            val components = File(value).path.split(File.separator).reversed()
            if (components.isEmpty()) return null
            currentKey = components[0]
            var componentIndex = 1

            // Avoid name collision
            while (containsKey(currentKey) || (openFile != null && openFile.first == currentKey)) {
                if (componentIndex < components.size) {
                    currentKey = components[componentIndex] + File.separator + currentKey
                    componentIndex++
                } else {
                    currentKey = "$currentKey`"
                }
            }
        }

        super.put(currentKey, value)
        if (sendNotification) sendNotification()
        return currentKey
    }

    fun getDisambiguousNameForNewOpenFile(value: String): String? {
        if (value in this.values) {
            // Find and return the existing key for this filepath
            return entries.find { it.value == value }?.key
        }

        val components = File(value).path.split(File.separator).reversed()
        if (components.isEmpty()) return null
        var currentKey = components[0]
        var componentIndex = 1

        while (containsKey(currentKey)) {
            if (componentIndex < components.size) {
                currentKey = components[componentIndex] + File.separator + currentKey
                componentIndex++
            } else {
                currentKey = "$currentKey`"
            }
        }

        return currentKey
    }

    fun reset(files: Map<String, String>) {
        // Clear existing mentions and restore exactly the provided keys so
        // previously sent messages keep their original mention tokens.
        super.clear()
        // Preserve directory keys that start with '/'; computeMentionSpans relies on
        // matching characters immediately after '@', so losing the leading '/'
        // would break span detection for directories such as @/src.
        for ((key, value) in files) {
            val isDirectory = key.startsWith("/")
            if (isDirectory) {
                super.put(normalizeDirectoryKey(key), value)
            } else {
                super.put(key, value)
            }
        }
        // Spans depend on mention keys; invalidate any cached spans.
        invalidateCache()
        highlightText(false)
        sendNotification()
    }

    fun putWithoutOverridingBatch(
        values: List<String>,
        sendNotification: Boolean = true,
    ): List<String> {
        val results = mutableListOf<String>()
        var hasNewFiles = false
        for (value in values) {
            if (value in this.values) {
                // Find and return the existing key for this filepath
                val existingKey = entries.find { it.value == value }?.key
                if (existingKey != null) {
                    results.add(existingKey)
                }
                continue
            }

            val components = File(value).path.split(File.separator).reversed()
            if (components.isEmpty()) continue

            var currentKey = components[0]
            var componentIndex = 1

            while (containsKey(currentKey)) {
                if (componentIndex < components.size) {
                    currentKey = components[componentIndex] + File.separator + currentKey
                    componentIndex++
                } else {
                    currentKey = "$currentKey`"
                }
            }

            hasNewFiles = true
            super.put(currentKey, value)
            results.add(currentKey)
        }

        if (hasNewFiles) {
            if (sendNotification) sendNotification()
        }

        return results
    }

    fun replaceKey(
        oldKey: String,
        newKey: String,
        newValue: String,
    ) {
        remove(oldKey)
        put(newKey, newValue)
    }
}

private data class Shortcut(
    val name: String,
    val searchable_names: List<String>,
    val description: String,
    val icon: Icon,
    val action: () -> List<String>,
)

class FileAutocomplete(
    private val project: Project,
    private val textComponent: RoundedTextArea,
    private val filesInContextComponent: FilesInContextComponent,
    private val currentFileManager: CurrentFileInContextManager,
) : Disposable {
    // Utility functions for consistent slash handling
    private fun isDirectoryPath(path: String): Boolean = path.startsWith("/") || path.startsWith("\\")

    private fun normalizeDirectoryKey(key: String): String = if (!key.startsWith("/")) "/$key" else key

    private fun getDisplayKey(
        key: String,
        isDirectory: Boolean,
    ): String = if (isDirectory && !key.startsWith("/")) "/$key" else key

    private fun invalidateMentionSpansCache() {
        cachedMentionSpans = null
        cachedMentionSpansText = null
        cachedMentionSpansKeys = null
    }

    private fun computeMentionSpans(text: String): List<MentionSpan> {
        val currentKeys = mentionsMap.keys.toSet()

        // Check if we can use cached value
        if (cachedMentionSpans != null &&
            cachedMentionSpansText == text &&
            cachedMentionSpansKeys == currentKeys
        ) {
            return cachedMentionSpans!!
        }

        // Compute new spans and cache them
        val spans = computeMentionSpans(text, currentKeys)
        cachedMentionSpans = spans
        cachedMentionSpansText = text
        cachedMentionSpansKeys = currentKeys

        return spans
    }

    private fun findMentionSpanAtCaretStrict(
        text: String,
        caret: Int,
    ): MentionSpan? = findMentionSpanAtCaretStrict(text, caret, mentionsMap.keys)

    private fun findMentionSpanAtOrAdjacent(
        text: String,
        caret: Int,
    ): MentionSpan? = findMentionSpanAtOrAdjacent(text, caret, mentionsMap.keys)

    companion object {
        fun create(
            project: Project,
            textArea: RoundedTextArea,
            filesInContextComponent: FilesInContextComponent,
            currentFileManager: CurrentFileInContextManager,
        ): FileAutocomplete {
            val component = FileAutocomplete(project, textArea, filesInContextComponent, currentFileManager)
            Disposer.register(filesInContextComponent, component)
            return component
        }

        private val fileHighlightPainter =
            RoundedHighlightPainter(
                SweepColors.backgroundColor
                    .brighter()
                    .brighter(),
            )

        private const val FUZZY_MATCH_THRESHOLD = 20

        private const val MAX_FILE_LENGTH_IN_AUTOCOMPLETE = 40

        private const val SUGGESTED_FILES_COUNT = 2
    }

    private val logger = Logger.getInstance(FileAutocomplete::class.java)
    private var popup: JBPopup? = null
    private val listModel = DefaultListModel<String>()
    private var fileList: JBList<String> = JBList(listModel)

    // For deletions as I wasn't able to get the value of the text before backspace otherwise
    private var currentText: String = ""
    private var lastText: String = ""
    private var currentCaretPosition: Int = 0
    private var lastCaretPosition: Int = 0

    // Cache for mention spans to avoid recomputation on cursor movement
    private var cachedMentionSpans: List<MentionSpan>? = null
    private var cachedMentionSpansText: String? = null
    private var cachedMentionSpansKeys: Set<String>? = null

    private var alarm: Alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    @Volatile
    private var isDisposed = false
    private var fileHighlightPainter = RoundedHighlightPainter(SweepConstants.FILE_MENTION_HIGHLIGHT_COLOR)

    private var documentListener: DocumentListener? = null
    private var keyListener: KeyAdapter? = null
    private var caretListener: CaretListener? = null

    private var mentionsMap: MentionMapWrapper =
        MentionMapWrapper(
            project,
            currentFileManager,
            { _, _ -> }, // Empty default implementation
            ::removeMention,
            ::highlightFileMentions,
            ::normalizeDirectoryKey,
            ::invalidateMentionSpansCache,
        )

    private val filesFromSuggestion = ConcurrentSet<String>()

    private var popupLocation: Point? = null

    private var shortcuts =
        mapOf(
            "Open.Shortcut" to
                Shortcut(
                    name = "Open Files",
                    searchable_names = listOf("Open", "Files", "Open Files"),
                    description = "Opened files in the editor",
                    icon = AllIcons.Actions.ListFiles.scale(11f),
                ) {
                    SweepMetaData.getInstance().hasUsedFileShortcut = true
                    getAllOpenFiles(project)
                        .mapNotNull { file ->
                            if (SweepNonProjectFilesService.getInstance(project).isAllowedFile(file.url)) {
                                file.url
                            } else {
                                relativePath(project, file)
                            }
                        }.filterNot { path ->
                            mentionsMap.containsValue(path) ||
                                path == currentFileManager.relativePath
                        }.let { paths ->
                            mentionsMap.putWithoutOverridingBatch(paths)
                        }
                },
            "Current.Changes" to
                Shortcut(
                    name = "Commit",
                    searchable_names = listOf("Current", "Changes", "Git", "Diff", "Current Changes", "Commit"),
                    description = "Diff of Working State",
                    icon = AllIcons.Vcs.CommitNode.scale(11f),
                ) {
                    val changeListManager = ChangeListManager.getInstance(project)
                    val currentFile = getCurrentSelectedFile(project)
                    val currentFileHasChanges =
                        currentFile != null && changeListManager.getChange(currentFile) != null

                    if (changeListManager.changeLists.size == 1 && !currentFileHasChanges) {
                        // If only default changelist exists and current file has no changes, directly append changes
                        appendChangeListToChat(project, "All Changes", logger, filesInContextComponent)
                    } else {
                        // Multiple changelists OR current file has changes - show selection popup
                        currentMode = SweepConstants.AutocompleteMode.CHANGE_LIST
                    }
                    emptyList()
                },
            "Git.Shortcut" to
                Shortcut(
                    name = "Branch",
                    searchable_names = listOf("Current", "Branch", "Current Branch", "Git", "Diff", "Branch Diff"),
                    description = "Diff with Default Branch",
                    AllIcons.Vcs.Branch.scale(11f),
                ) {
                    handleGitDiffShortcut(project, logger, filesInContextComponent)
                    emptyList()
                },
            "Problems.Shortcut" to
                Shortcut(
                    name = "Problems",
                    searchable_names = listOf("Problems", "Lint", "Errors"),
                    description = "Severe problems for the current file",
                    icon = SweepIcons.ErrorWarningIcon.scale(11f),
                ) {
                    handleProblemsShortcut(project, currentFileManager, logger, filesInContextComponent)
                    emptyList()
                },
            "Terminal.Shortcut" to
                Shortcut(
                    name = "Terminal",
                    searchable_names = listOf("Terminal", "Console"),
                    description = "Adds terminal output to context",
                    icon = AllIcons.Nodes.Console.scale(11f),
                ) {
                    if (!handleTerminalShortcut(project, logger, filesInContextComponent)) {
                        // change modes
                        currentMode = SweepConstants.AutocompleteMode.ACTIVE_TERMINALS
                    }
                    emptyList()
                },
        )

    private val fileUsageManager = FileUsageManager.getInstance(project)
    private val fileRankingService = FileRankingService.getInstance(project)
    private var fileRenameHandlerIdentifier = UUID.randomUUID().toString()
    private var fileRenameListener: FileRenameListener? = null
    private var fileDeletionHandlerIdentifier = UUID.randomUUID().toString()

    private var currentMode: SweepConstants.AutocompleteMode = SweepConstants.AutocompleteMode.DEFAULT

    init {
        setupKeyListener()
        setupMentionList()
        currentFileManager.setFileAutocomplete(this)

        // Initialize text states with current text content
        currentText = textComponent.text
        lastText = textComponent.text
        currentCaretPosition = textComponent.caretPosition
        lastCaretPosition = textComponent.caretPosition
    }

    fun setCurrentOpenFile(openFile: Pair<String, String>) {
        mentionsMap.currentOpenFile = openFile
    }

    fun getDisambiguousNameForNewOpenFile(newFilePath: String): String? = mentionsMap.getDisambiguousNameForNewOpenFile(newFilePath)

    fun setupFileRenamingHandler() {
        fileRenameListener?.addOnFileRenamedAction(fileRenameHandlerIdentifier) { renamedFile, oldPath, newPath ->
            // Convert absolute paths to relative paths for comparison
            val oldRelativePath = relativePath(project, oldPath)
            val newRelativePath = relativePath(project, newPath)

            if (oldRelativePath != null && newRelativePath != null) {
                // need update the values in place, keeping the same keys (mention names)
                val entries = mentionsMap.entries.toList()
                for ((key, value) in entries) {
                    when {
                        // Exact file match
                        value == oldRelativePath -> {
                            // Use the same disambiguation logic as when adding new files
                            val newKey =
                                getDisambiguousNameForNewOpenFile(newRelativePath)
                                    ?: File(newRelativePath).name

                            // Always update both key and value since filename changed
                            mentionsMap.replaceKey(key, newKey, newRelativePath)

                            // Update the text component to reflect the new mention key
                            updateTextComponentForRenameSingleFile(key, newKey)

                            ApplicationManager.getApplication().invokeLater {
                                highlightFileMentions()
                            }
                        }

                        // Directory rename: update mentions pointing to files inside the renamed directory
                        value.startsWith(oldRelativePath + File.separator) ||
                            value.startsWith(oldRelativePath + "/") ||
                            value.startsWith(oldRelativePath + "\\") -> {
                            // Compute the new value by replacing the old prefix with the new one
                            val suffix = value.substring(oldRelativePath.length)
                            val updatedValue = newRelativePath + suffix

                            // Compute a disambiguated key for the new path
                            val newKey = getDisambiguousNameForNewOpenFile(updatedValue) ?: File(updatedValue).name

                            // Replace key and value in the mentions map
                            mentionsMap.replaceKey(key, newKey, updatedValue)

                            // Update text occurrences for this key
                            updateTextComponentForRenameSingleFile(key, newKey)

                            ApplicationManager.getApplication().invokeLater {
                                highlightFileMentions()
                            }
                        }
                    }
                }

                // check if the renamed file is the current open file in the IDE
                // Note currentFileManager's VirtualFile will already have the new path, so we need to compare the VirtualFile itself
                val currentOpenFile = currentFileManager.currentFileInContext
                if (currentOpenFile != null && currentOpenFile == renamedFile) {
                    // Always update the UI to show the renamed file with the correct display name
                    // Calculate the display name for the renamed file
                    val displayName =
                        getDisambiguousNameForNewOpenFile(newRelativePath)
                            ?: File(newRelativePath).name

                    // Update the FilesInContextComponent UI to reflect the rename
                    filesInContextComponent.setCurrentOpenFileInUI(displayName, newRelativePath)

                    setCurrentOpenFile(displayName to newRelativePath)
                }
            }
        }
    }

    fun setOnMentionsChanged(onFilesChanged: (MutableMap<String, String>, (String) -> Unit) -> Unit) {
        // Update the callback in the existing filesMap instance
        mentionsMap =
            MentionMapWrapper(
                project,
                currentFileManager,
                onFilesChanged,
                ::removeMention,
                ::highlightFileMentions,
                ::normalizeDirectoryKey,
                ::invalidateMentionSpansCache,
            )

        if (fileRenameListener == null) {
            fileRenameListener = FileRenameListener.create(project, this)
        }

        fileRenameListener?.addOnFileDeletedAction(fileDeletionHandlerIdentifier) { file, abspath ->
            val path = relativePath(project, abspath)
            // Hide previews
            mentionsMap.keys
                .filter { key ->
                    mentionsMap[key]?.let { it == path || it.startsWith("$path${File.separator}") } == true
                }.forEach { key ->
                    filesInContextComponent.hideIfCurrentlyOpen(key)
                }
            // Remove from the map.
            val keys = mentionsMap.keys.toList()
            for (key in keys) {
                mentionsMap[key]?.let { value ->
                    if (value == path || value.startsWith("$path${File.separator}")) {
                        mentionsMap.removeAndUpdateText(key)
                    }
                }
            }
        }
        setupFileRenamingHandler()
    }

    fun getFilesMap() = mentionsMap.copy()

    fun replaceIncludedFiles(files: Map<String, String>) {
        // Reset mentions and highlight cache
        mentionsMap.reset(files)
        // Track which files came from suggestion (not @-mentions)
        filesFromSuggestion.clear()
        filesFromSuggestion.addAll(files.keys)
        // Ensure popup/search state is fresh for a new chat session
        hidePopup()
        currentMode = SweepConstants.AutocompleteMode.DEFAULT
        invalidateCache()
        lastFiles = null
    }

    fun addIncludedFiles(files: List<String>) {
        for (file in files) {
            val key = mentionsMap.putWithoutOverriding(file)
            key?.let { filesFromSuggestion.add(key) }
        }
    }

    fun getLast() = mentionsMap.lastKey

    fun reset() {
        hidePopup()
        mentionsMap.clear()
        filesFromSuggestion.clear()
        listModel.clear()
        alarm.cancelAllRequests()
        // Ensure we return to default mode and clear any stale popup/search state
        currentMode = SweepConstants.AutocompleteMode.DEFAULT
        // Invalidate cached geometry and search so first '@' reliably triggers a fresh popup
        invalidateCache()
        lastFiles = null
    }

    fun isNew(): Boolean = mentionsMap.isEmpty()

    private fun updateTextStates(
        newText: String,
        newCaretPosition: Int,
    ) {
        lastText = currentText
        lastCaretPosition = currentCaretPosition
        currentText = newText
        currentCaretPosition = newCaretPosition
    }

    private fun addNewMentions(insertedText: String) {
        // Greedily detect mentions only for already-known keys; insertion of new mentions happens in insertSelection
        val spans = computeMentionSpans(insertedText)
        if (spans.isNotEmpty()) {
            highlightFileMentions()
        }
    }

    private fun setupKeyListener() {
        // This is needed to mute the default key event which moves the cursor for up and down arrows
        textComponent.setDefaultKeyEventTriggerCondition { !isPopupVisible }
        // Add document listener to capture text state before changes
        textComponent.run {
            documentListener =
                object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent) {
                        val insertedText = e.document.getText(e.offset, e.length)
                        updateTextStates(text, caretPosition)
                        addNewMentions(insertedText)
                    }

                    override fun removeUpdate(e: DocumentEvent) {
                        updateTextStates(text, caretPosition)
                    }

                    override fun changedUpdate(e: DocumentEvent) {
                        updateTextStates(text, caretPosition)
                    }
                }.also { textComponent.addDocumentListener(it) }
        }

        keyListener =
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_UP -> {
                            if (isPopupVisible) {
                                e.consume()
                                moveSelection(-1)
                            }
                        }

                        KeyEvent.VK_DOWN -> {
                            if (isPopupVisible) {
                                e.consume()
                                moveSelection(1)
                            }
                        }

                        KeyEvent.VK_P -> {
                            if (isPopupVisible && e.isControlDown) {
                                e.consume()
                                moveSelection(-1)
                            }
                        }

                        KeyEvent.VK_N -> {
                            if (isPopupVisible && e.isControlDown) {
                                e.consume()
                                moveSelection(1)
                            }
                        }

                        KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                            if (isPopupVisible) {
                                e.consume()
                                insertSelection()
                                val metadata = SweepMetaData.getInstance()
                                metadata.fileContextUsageCount++
                                hidePopup()
                            }
                        }

                        KeyEvent.VK_ESCAPE -> {
                            if (isPopupVisible) {
                                e.consume()
                                hidePopup()
                            }
                        }

                        KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT -> {
                            val textNow = textComponent.text
                            val caret = textComponent.caretPosition
                            val span = findMentionSpanAtOrAdjacent(textNow, caret)

                            if (span != null) {
                                val moveRight = e.keyCode == KeyEvent.VK_RIGHT
                                val atStart = caret == span.start
                                val atEnd =
                                    caret == span.end
                                val inside = caret > span.start && caret < span.end

                                when {
                                    inside -> {
                                        e.consume()
                                        moveCursorOutsideMention(moveRight)
                                    }

                                    atStart && moveRight -> {
                                        // From start, Right should skip the whole mention
                                        e.consume()
                                        moveCursorOutsideMention(true)
                                    }

                                    atEnd && !moveRight -> {
                                        // From end, Left should skip the whole mention
                                        e.consume()
                                        moveCursorOutsideMention(false)
                                    }

                                    else -> {
                                        // Allow default behavior
                                    }
                                }
                            }
                        }
                    }
                }

                override fun keyReleased(e: KeyEvent) {
                    if (isPrintableChar(e)) {
                        if (e.keyCode == KeyEvent.VK_ENTER || e.keyCode == KeyEvent.VK_TAB) {
                            if (isPopupVisible) {
                                return
                            }
                        }
                        scheduleMentionChooser()
                    } else if (e.keyCode == KeyEvent.VK_BACK_SPACE || e.keyCode == KeyEvent.VK_DELETE) {
                        val textBefore = lastText
                        val caret = textComponent.caretPosition
                        val span = findMentionSpanAtOrAdjacent(textBefore, caret)

                        if (span != null) {
                            // Only delete the mention if cursor is at the end or inside the mention
                            // (not when cursor is before the mention)
                            if (caret > span.start && caret < span.end) {
//                                println("backspace: Deleting mention at cursor position $caret, span: $span")
                                deleteCurrentMention(textBefore)
//                                println("backspace: After delete, moved caret to ${textComponent.caretPosition}")
                            }
                        }
                        e.consume()

                        // Always reschedule chooser on backspace; it will close itself if no active '@'
                        scheduleMentionChooser()
                    }
                    highlightFileMentions()
                }
            }.also { textComponent.textArea.addKeyListener(it) }

        // Prevent mouse clicks inside mentions
        caretListener =
            CaretListener {
                if (isInsideMention()) {
                    moveCursorToNearestValidPosition()
                }
            }.also { textComponent.addCaretListener(it) }
    }

    private fun isInsideMention(): Boolean {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            var result: Boolean? = null
            ApplicationManager.getApplication().invokeAndWait { result = isInsideMention() }
            return result!!
        }
        val text = textComponent.text
        val caretPosition = textComponent.caretPosition
        return findMentionSpanAtCaretStrict(text, caretPosition) != null
    }

    private fun moveCursorToNearestValidPosition() {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { moveCursorToNearestValidPosition() }
            return
        }
        val text = textComponent.text
        val caretPosition = textComponent.caretPosition
        val span = findMentionSpanAtCaretStrict(text, caretPosition) ?: return
        // If closer to start, move before mention; otherwise move after mention
        val distToStart = caretPosition - span.start
        val distToEnd = span.end - caretPosition
        textComponent.caretPosition = if (distToStart <= distToEnd) span.start else span.end
    }

    private fun moveCursorOutsideMention(moveRight: Boolean) {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { moveCursorOutsideMention(moveRight) }
            return
        }
        val text = textComponent.text
        val caretPosition = textComponent.caretPosition
        val span = findMentionSpanAtOrAdjacent(text, caretPosition) ?: return
        textComponent.caretPosition =
            if (moveRight) {
                span.end
            } else {
                span.start
            }
    }

    private fun isAtMention(deletion: Boolean): Boolean {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            var result: Boolean? = null
            ApplicationManager.getApplication().invokeAndWait { result = isAtMention(deletion) }
            return result!!
        }

        return if (deletion) {
            // Consider caret strictly inside or at either boundary as being "at mention" for deletion logic
            findMentionSpanAtOrAdjacent(lastText, lastCaretPosition) != null
        } else {
            val text = textComponent.text
            val caretPosition = textComponent.caretPosition
            val spans = computeMentionSpans(text)
            val inExistingSpan = spans.any { caretPosition >= it.start && caretPosition <= it.end }
            val currentMention = getCurrentMention()
            inExistingSpan || currentMention != null
        }
    }

    private fun updateTextComponentForRenameSingleFile(
        oldKey: String,
        newKey: String,
    ) {
        updateTextComponentForRename(listOf(oldKey to newKey))
    }

    private fun updateTextComponentForRename(keysToUpdate: List<Pair<String, String>>) {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { updateTextComponentForRename(keysToUpdate) }
            return
        }
        if (keysToUpdate.isEmpty()) return

        // Temporarily remove listeners to avoid re-entrancy while we mutate the text
        documentListener?.let { textComponent.textArea.document.removeDocumentListener(it) }
        caretListener?.let { textComponent.textArea.removeCaretListener(it) }

        try {
            var text = textComponent.text
            var caret = textComponent.caretPosition

            // perform replacements right-to-left to avoid shifting indices
            val sortedRenames = keysToUpdate.sortedByDescending { it.first.length }

            for ((oldKey, newKey) in sortedRenames) {
                if (oldKey == newKey) continue

                val oldToken = "@$oldKey"
                val newToken = "@$newKey"

                var searchIndex = 0
                val occurrences = mutableListOf<IntRange>()
                while (true) {
                    val idx = text.indexOf(oldToken, searchIndex)
                    if (idx == -1) break
                    // Ensure we match whole mention (followed by whitespace or end or punctuation)
                    val endIdx = idx + oldToken.length
                    occurrences.add(idx until endIdx)
                    searchIndex = endIdx
                }

                if (occurrences.isEmpty()) continue

                for (occurrence in occurrences.reversed()) {
                    val start = occurrence.first
                    val end = occurrence.last + 1

                    // Avoid creating duplicate mentions: if newToken already exists adjacent, skip
                    val before = if (start - 1 >= 0) text[start - 1] else null
                    val after = if (end < text.length) text[end] else null

                    // Replace
                    text = text.substring(0, start) + newToken + text.substring(end)

                    // Adjust caret if it was after the replaced range
                    if (caret >= end) {
                        caret += newToken.length - (end - start)
                    } else if (caret in start..end) {
                        // If caret was inside the old mention, move it to end of new mention
                        caret = start + newToken.length
                    }
                }
            }

            // Commit text and restore caret
            textComponent.text = text
            textComponent.caretPosition = caret.coerceIn(0, text.length)

            // Recompute mention spans cache and re-highlight
            invalidateMentionSpansCache()
            highlightFileMentions()
        } finally {
            // Re-add listeners
            documentListener?.let { textComponent.textArea.document.addDocumentListener(it) }
            caretListener?.let { textComponent.textArea.addCaretListener(it) }
        }
    }

    private fun removeMention(mention: String) {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { removeMention(mention) }
            return
        }
        val mentionInText = "@$mention"
        val originalText = textComponent.text

        // Find all occurrences of the mention
        val occurrences = mutableListOf<IntRange>()
        var searchIndex = 0
        while (searchIndex < originalText.length) {
            val index = originalText.indexOf(mentionInText, searchIndex)
            if (index == -1) break
            occurrences.add(index until (index + mentionInText.length))
            searchIndex = index + 1
        }

        if (occurrences.isEmpty()) return

        // Process occurrences from right to left to maintain correct indices
        var text = originalText
        var lastRemovedPosition = -1

        for (occurrence in occurrences.reversed()) {
            val startIndex = occurrence.first
            val endIndex = occurrence.last + 1
            var finalStartIndex = startIndex
            var finalEndIndex = endIndex

            // Handle spacing based on position
            when {
                // Mention is at the beginning of text
                startIndex == 0 -> {
                    // Remove trailing spaces after the mention
                    while (finalEndIndex < text.length && text[finalEndIndex] == ' ') {
                        finalEndIndex++
                    }
                }
                // Mention is at the end of text
                endIndex == text.length -> {
                    // Remove leading spaces before the mention
                    while (finalStartIndex > 0 && text[finalStartIndex - 1] == ' ') {
                        finalStartIndex--
                    }
                }
                // Mention is between other text
                else -> {
                    // Check if there are spaces both before and after
                    val hasSpaceBefore = finalStartIndex > 0 && text[finalStartIndex - 1] == ' '
                    val hasSpaceAfter = finalEndIndex < text.length && text[finalEndIndex] == ' '

                    if (hasSpaceBefore && hasSpaceAfter) {
                        // Remove trailing spaces to avoid double spacing
                        while (finalEndIndex < text.length && text[finalEndIndex] == ' ') {
                            finalEndIndex++
                        }
                    } else if (!hasSpaceBefore && hasSpaceAfter) {
                        // Remove trailing spaces if no space before
                        while (finalEndIndex < text.length && text[finalEndIndex] == ' ') {
                            finalEndIndex++
                        }
                    } else if (hasSpaceBefore && !hasSpaceAfter) {
                        // Remove leading spaces if no space after
                        while (finalStartIndex > 0 && text[finalStartIndex - 1] == ' ') {
                            finalStartIndex--
                        }
                    }
                }
            }

            text = text.substring(0, finalStartIndex) + text.substring(finalEndIndex)
            lastRemovedPosition = finalStartIndex
        }

        // Update the text component only once after all removals
        if (lastRemovedPosition != -1) {
            textComponent.text = text
            textComponent.caretPosition = lastRemovedPosition
            highlightFileMentions()
        }
    }

    private fun deleteCurrentMention(text: String) {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { deleteCurrentMention(text) }
            return
        }
        val caret = textComponent.caretPosition
        val span = findMentionSpanAtOrAdjacent(text, caret) ?: return

        // Remove the mention from the text
        // span.end is already exclusive, so we don't need to add 1
        val startIdx = span.start
        val endIdx = span.end

        // Track the final cursor position (where the deletion starts)
        val newCaretPosition = startIdx

        val newText = text.substring(0, startIdx) + text.substring(endIdx)

        // Temporarily remove listeners to prevent interference
        documentListener?.let { textComponent.textArea.document.removeDocumentListener(it) }
        caretListener?.let { textComponent.textArea.removeCaretListener(it) }

        textComponent.text = newText

        // Use invokeLater to ensure cursor position is set after any pending cursor movements
        ApplicationManager.getApplication().invokeLater {
            textComponent.caretPosition = newCaretPosition.coerceAtLeast(0).coerceAtMost(newText.length)

            // Re-add listeners after cursor is positioned
            documentListener?.let { textComponent.textArea.document.addDocumentListener(it) }
            caretListener?.let { textComponent.textArea.addCaretListener(it) }
        }

        // Check if any other instances of this mention still exist in the updated text
        val remainingSpans = computeMentionSpans(newText)
        val hasOtherInstances = remainingSpans.any { it.key == span.key }

        // Only remove from maps if no other instances exist
        if (!hasOtherInstances) {
            mentionsMap.remove(span.key)
            filesFromSuggestion.remove(span.key)
            // Hide current snippet preview only if completely removed
            filesInContextComponent.hideIfCurrentlyOpen(span.key)
        }

        // Clear any autocomplete components
        hidePopup()
        alarm.cancelAllRequests()

        // Don't call highlightFileMentions here with removeFilesNotInHighlight=true
        // because we've already handled the removal above
        highlightFileMentions(removeFilesNotInHighlight = false)
    }

    private fun setupMentionList() {
        fileList.setEmptyText("No matching files found")

        fileList.cellRenderer =
            ListCellRenderer { _, value, _, selected, _ ->
                var name: String
                var description: String
                var icon: Icon

                if (value in shortcuts) {
                    name = shortcuts[value]!!.name
                    description = shortcuts[value]!!.description
                    icon = shortcuts[value]!!.icon
                } else {
                    if (value.contains("::")) {
                        val pathString = value.split("::")[0]
                        name = value.split("::")[1]
                        description = truncatePath(pathString)
                        icon =
                            if (currentMode == SweepConstants.AutocompleteMode.CHANGE_LIST &&
                                (pathString.contains("files in") || pathString.contains("files changed"))
                            ) {
                                AllIcons.Vcs.CommitNode.scale(11f)
                            } else if (currentMode == SweepConstants.AutocompleteMode.ACTIVE_TERMINALS) {
                                AllIcons.Nodes.Console.scale(11f)
                            } else {
                                when (EntitiesCache.getInstance(project).findEntity(pathString, name)?.type) {
                                    EntityType.CLASS -> SweepIcons.EntityIcons.classIcon
                                    EntityType.ENUM_CLASS -> SweepIcons.EntityIcons.enumIcon
                                    EntityType.FUNCTION -> SweepIcons.EntityIcons.functionIcon
                                    EntityType.INTERFACE -> SweepIcons.EntityIcons.interfaceIcon
                                    EntityType.OBJECT -> SweepIcons.EntityIcons.objectIcon
                                    EntityType.PROPERTY -> SweepIcons.EntityIcons.propertyIcon
                                    null -> SweepIcons.EntityIcons.naIcon
                                }.scale(11f)
                            }
                    } else {
                        File(value).run {
                            name = this.name
                            description = truncatePath(parentFile?.path ?: path)
                            icon = SweepIcons.iconForFile(project, this, 11f)
                        }
                    }
                }

                val currentInput = getCurrentMention()?.trim()
                val (matchStart, matchLength) = findLongestCommonSubstring(name, currentInput)

                val leftComp =
                    SimpleColoredComponent().apply {
                        this.icon = icon
                        val baseNameAttrs =
                            if (selected) {
                                SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                            } else {
                                SimpleTextAttributes.REGULAR_ATTRIBUTES
                            }
                        if (matchLength > 1) {
                            append(name.substring(0, matchStart), baseNameAttrs)
                            append(
                                name.substring(matchStart, matchStart + matchLength),
                                SimpleTextAttributes(
                                    SimpleTextAttributes.STYLE_BOLD or SimpleTextAttributes.STYLE_SEARCH_MATCH,
                                    if (selected) UIUtil.getListSelectionForeground(true) else UIUtil.getListForeground(),
                                ),
                            )
                            append(name.substring(matchStart + matchLength), baseNameAttrs)
                        } else {
                            append(name, baseNameAttrs)
                        }
                        append(" ".repeat(3), baseNameAttrs)
                        isOpaque = false
                        withSweepFont(project)
                    }

                val rightComp =
                    SimpleColoredComponent().apply {
                        val pathAttrs =
                            if (selected) {
                                SimpleTextAttributes(
                                    SimpleTextAttributes.STYLE_PLAIN,
                                    UIUtil.getListSelectionForeground(true),
                                )
                            } else {
                                SimpleTextAttributes.GRAYED_ATTRIBUTES
                            }
                        append(description, pathAttrs)
                        withSweepFont(project, scale = 0.9f)
                        isOpaque = false
                    }

                val panel =
                    JPanel(BorderLayout()).apply {
                        border = JBUI.Borders.empty(3, 6)
                        add(leftComp, BorderLayout.WEST)
                        add(rightComp, BorderLayout.EAST)
                        isOpaque = true
                        background =
                            if (selected) {
                                UIUtil.getListSelectionBackground(true)
                            } else {
                                UIUtil.getListBackground()
                            }
                    }

                panel
            }
    }

    private fun truncatePath(path: String): String {
        if (path.length <= MAX_FILE_LENGTH_IN_AUTOCOMPLETE) return path
        val splits = path.split(File.separator)

        // Handle case with too few components
        if (splits.size <= 3) return path

        // Create a truncated path that preserves uniqueness better
        val directoryTruncatedPath =
            buildString {
                // Always include first component if available
                if (splits.isNotEmpty()) append(splits.first()).append(File.separator)

                // Add ellipsis if we have middle components to skip
                if (splits.size > 3) append("...").append(File.separator)

                // Add last two components if available
                if (splits.size >= 2) append(splits[splits.size - 2]).append(File.separator)
                if (splits.isNotEmpty()) append(splits.last())
            }

        if (directoryTruncatedPath.length <= MAX_FILE_LENGTH_IN_AUTOCOMPLETE) return directoryTruncatedPath

        // If still too long, truncate from the end but preserve uniqueness with path suffix
        val suffixLength = minOf(MAX_FILE_LENGTH_IN_AUTOCOMPLETE - 3, path.length)
        return "..." + path.takeLast(suffixLength)
    }

    fun scheduleMentionChooser() {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { scheduleMentionChooser() }
            return
        }

        // If there's no active '@' mention being typed right now, close any popup immediately
        // and avoid scheduling a search (prevents lingering dropdown after deleting '@').
        val immediateMention = getCurrentMention()
        if (immediateMention == null) {
            hidePopup()
            alarm.cancelAllRequests()
            currentSearchWord = null
            lastFiles = null
            return
        }

        alarm.cancelAllRequests()
        alarm.addRequest({
            // Since alarm runs on pooled thread, we need to get the mention text from EDT
            var currentWord: String? = null

            // Use invokeAndWait to get the current mention from EDT (not inside a read action to avoid deadlock)
            if (!ApplicationManager.getApplication().isDispatchThread) {
                ApplicationManager.getApplication().invokeAndWait {
                    currentWord = getCurrentMention()
                }
            } else {
                currentWord = getCurrentMention()
            }

            // Early exit if no mention found
            if (currentWord == null) {
                ApplicationManager.getApplication().invokeLater {
                    // Ensure popup is closed if the mention disappeared before results arrived
                    hidePopup()
                    currentSearchWord = null
                    lastFiles = null
                }
                return@addRequest
            }

            // Use a local variable to avoid smart cast issues
            val searchWord = currentWord

            // Update the current search word to track what we're searching for
            currentSearchWord = searchWord

            // Execute file search on pooled thread (we're already on pooled thread)
            val files = findMatchingFiles(searchWord!!)

            ApplicationManager.getApplication().invokeLater {
                if (isDisposed) return@invokeLater
                if (searchWord == currentSearchWord) {
                    showPopupWithFiles(files)
                }
                // If searchWord != currentSearchWord, this search is stale - ignore results
            }
        }, 0)
    }

    private fun getCurrentMention(): String? {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            var result: String? = null
            ApplicationManager.getApplication().invokeAndWait { result = getCurrentMention() }
            return result
        }
        val caretPosition = textComponent.caretPosition
        val text = textComponent.text

        // Find the last '@' before or at caret
        val lastAtSymbol = text.lastIndexOf('@', caretPosition - 1)
        if (lastAtSymbol == -1) return null

        // Note: We don't check for existing spans here because we want to allow
        // autocomplete when starting a new mention or extending existing ones

        // Find the end of the current mention text
        var mentionEnd = caretPosition
        while (mentionEnd < text.length && !text[mentionEnd].isWhitespace()) {
            mentionEnd++
        }

        // Extract the mention text (without the '@')
        val mentionText = text.substring(lastAtSymbol + 1, mentionEnd)

        // Validate that we have a reasonable mention:
        // - Not empty (unless we just typed '@')
        // - No spaces within the mention text
        // - Caret is positioned appropriately
        if (mentionText.contains(' ')) {
            // There's a space in what we think is the mention, so we're probably
            // after a completed mention. Example: "@file.txt some other text"
            return null
        }

        // If caret is exactly at the end of the mention and the mention is complete, consider it not a mention
        if (caretPosition == mentionEnd && mentionText in mentionsMap.keys) {
            return null
        }

        // Check if we're immediately after the '@' or within the mention text
        if (caretPosition > lastAtSymbol) {
            return mentionText
        }

        return null
    }

    private val lastMentionedFile: String?
        get() = mentionsMap.lastValue ?: currentFileManager.relativePath

    private fun findMatchingShortcuts(input: String): List<String> {
        val res = mutableListOf<String>()
        val loweredInput = input.lowercase()

        for (shortcut in shortcuts) {
            for (searchName in shortcut.value.searchable_names) {
                if (searchName.lowercase().startsWith(loweredInput)) {
                    res.add(shortcut.key)
                    break
                }
            }
        }

        return res
    }

    private fun findMatchingEntities(input: String): List<String> {
        if (input.isBlank()) return emptyList()

        return EntitiesCache
            .getInstance(project)
            .entitiesWithSubstring(input)
            .map { (_, entityInfo) -> "${entityInfo.location}::${entityInfo.name}" }
            .filterNot { mentionsMap.containsValue(it) }
            .distinct()
            .toList()
    }

    private fun findMatchingFiles(
        input: String,
        findShortcuts: Boolean = true,
    ): List<String> {
        // Precompute open files
        val openFiles =
            getAllOpenFiles(project)
                .mapNotNull { file ->
                    if (SweepNonProjectFilesService.getInstance(project).isAllowedFile(file.url)) {
                        file.url
                    } else {
                        relativePath(project, file)
                    }
                }.toSet()

        if (currentMode == SweepConstants.AutocompleteMode.CHANGE_LIST) {
            return findChangeListOptions(project, input)
        } else if (currentMode == SweepConstants.AutocompleteMode.ACTIVE_TERMINALS) {
            val terminalManager = TerminalManagerService.getInstance(project)
            val activeTerminal = terminalManager.activeTerminal
            val editorSelectionManager = EditorSelectionManager.getInstance(project)

            val terminalOptions = mutableListOf<String>()

            // Add active terminal if it's showing
            if (activeTerminal != null && activeTerminal.component.isShowing) {
                val terminal = activeTerminal as? ShellTerminalWidget
                if (terminal != null) {
                    val terminalName = terminal.name ?: "Terminal"
                    terminalOptions.add("Active Terminal::$terminalName")
                }
            }

            // Add visible editors (consoles)
            val editors = editorSelectionManager.getEditorListeners()
            val visibleEditors =
                editors.keys.filter { editorInstance ->
                    editorInstance.contentComponent.isShowing
                }
            visibleEditors.forEachIndexed { index, editor ->
                // Try to get a meaningful name, fallback to generic
                val editorName = editor.virtualFile?.name ?: "Console ${index + 1}"
                // Ensure we don't add the active terminal's editor representation if already added
                if (activeTerminal == null || editor.component != activeTerminal.component) {
                    // Get the first 40 characters of the console text
                    val previewLength = minOf(40, editor.document.textLength)
                    val previewText =
                        editor.document.charsSequence
                            .subSequence(0, previewLength)
                            .toString()
                            .trim()
                            .let { if (it.length == 40) "$it..." else it }
                            .ifEmpty { "Visible Console" }
                    terminalOptions.add("$previewText::$editorName")
                }
            }

            return terminalOptions.filter { it.split("::")[0].startsWith(input, ignoreCase = true) }
        }
        // used to augment file autocomplete in some situations
        val nonProjectFiles = SweepNonProjectFilesService.getInstance(project).getAllowedFiles()
        val filteredNonProjectFiles =
            if (input.isNotEmpty()) {
                nonProjectFiles.filter { it.contains(input, ignoreCase = true) }
            } else {
                nonProjectFiles
            }

        // Extract parent directories from open files
        val openFileDirectories =
            openFiles
                .mapNotNull { filePath ->
                    val file =
                        if (filePath.startsWith("file://") || filePath.startsWith("http")) {
                            // Handle URLs by extracting path
                            try {
                                File(java.net.URI(filePath).path).parentFile?.path
                            } catch (e: Exception) {
                                null
                            }
                        } else {
                            // Handle regular file paths
                            File(filePath).parentFile?.path
                        }
                    file?.let { parentPath ->
                        // Convert to relative path if it's within the project
                        relativePath(project, parentPath) ?: parentPath
                    }
                }.filter { dirPath ->
                    val projectBasePath = project.basePath ?: return@filter false
                    val dir = File(projectBasePath, dirPath)
                    dir.exists() && dir.isDirectory && !dir.name.startsWith(".")
                }.toSet()

        // Helper function to convert string paths to Pair(originalPath, filename)
        fun pathToPair(path: String): Pair<String, String> {
            val lastSeparatorIndex = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
            val filename = if (lastSeparatorIndex >= 0) path.substring(lastSeparatorIndex + 1) else path
            return Pair(path, filename)
        }

        // Get matching shortcuts first
        val matchingShortcuts = if (findShortcuts) findMatchingShortcuts(input) else emptyList()

        // Handle two cases differently: empty input (use recent files) vs search query (use platform ranking)
        if (input.isEmpty()) {
            // Empty input: show recent files, need custom ranking
            val matchingFiles =
                (
                    RecentlyUsedFiles
                        .getInstance(project)
                        .getFiles()
                        .filterTo(mutableSetOf()) { FileSearcher.getInstance(project).contains(it) } +
                        openFiles +
                        openFileDirectories
                )
//                    .filterNot { mentionsMap.containsValue(it) }
//                    .filterNot { !showCurrentOpenFileInAutocomplete && it == currentFileManager.relativePath }
                    .take(6)
                    .map { pathToPair(it) }

            val result = matchingFiles.toMutableList()
            result.addAll(findMatchingEntities(input).map { pathToPair(it) })

            // Rank recent files since they're not pre-ranked
            val sortedFiles =
                fileRankingService
                    .rankFiles(
                        result,
                        input,
                        openFiles,
                    ).take(maxOf(0, 10 - matchingShortcuts.size))
            return matchingShortcuts.take(10) + sortedFiles
        } else {
            // Non-empty input: use FileSearcher's built-in ranking, but also rank additional sources
            val searcherResults = FileSearcher.getInstance(project).searchFiles(input, maxResults = 10).map { pathToPair(it) }

            // IMPORTANT: the next few rankings are not that important imo, unless we run into an edge case where they are
            // At which time we can fix/adjust the filematchscoring systems
            // For now, we should stick as closely to the intellij Scores as possible
            // Rank and filter nonProjectFiles, openFiles, and openFileDirectories based on input match
            val rankedNonProjectFiles =
                filteredNonProjectFiles
                    .map { pathToPair(it) }
                    .map { fileInfo -> Pair(fileInfo, calculateFileMatchScore(fileInfo, input)) }
                    .filter { it.second < 0 } // Only keep files with positive matches (negative scores)
                    .sortedBy { it.second }
                    .map { it.first }

            val rankedOpenFiles =
                openFiles
                    .map { pathToPair(it) }
                    .map { fileInfo -> Pair(fileInfo, calculateFileMatchScore(fileInfo, input)) }
                    .filter { it.second < 0 } // Only keep files with positive matches
                    .sortedBy { it.second }
                    .map { it.first }

            val rankedOpenFileDirectories =
                openFileDirectories
                    .map { pathToPair(it) }
                    .map { fileInfo -> Pair(fileInfo, calculateFileMatchScore(fileInfo, input)) }
                    .filter { it.second < 0 } // Only keep files with positive matches
                    .sortedBy { it.second }
                    .map { it.first }

            // Add entities (also pre-filtered by input) and convert to same format
            val entities = findMatchingEntities(input).map { pathToPair(it) }

            // Keep top 2 searcher results exempt from scoring, they stay at the top
            val topSearcherResults = searcherResults.take(2)
            val restSearcherResults = searcherResults.drop(2)

            // Merge remaining sources (rest of searcher + other files + entities) with scoring
            val scoredSources =
                (restSearcherResults + rankedNonProjectFiles + rankedOpenFiles + rankedOpenFileDirectories + entities)
                    .map { fileInfo -> Pair(fileInfo, calculateFileMatchScore(fileInfo, input)) }
                    .sortedBy { it.second }
                    .map { it.first }
                    .distinctBy { it.first } // Remove duplicates by path

            // Combine: top searcher results + scored results, then remove duplicates
            // distinctBy keeps first occurrence, so top searcher results (highest priority) are preserved
            val matchingFiles = (topSearcherResults + scoredSources).distinctBy { it.first }

            // Filter for directories only if query starts with a slash
            val filteredFiles =
                if (isDirectoryPath(input)) {
                    val projectBasePath = project.basePath ?: return emptyList()
                    matchingFiles
                        .filter { fileInfo ->
                            // first filter to all files without periods, assume directories don't contain periods
                            !fileInfo.first.contains(".")
                        }.filter { fileInfo ->
                            val file = File(projectBasePath, fileInfo.first)
                            file.isDirectory &&
                                !file.name.startsWith(".") &&
                                file.name !in setOf("build", "target", "node_modules", ".gradle", "out", "dist")
                        }
                } else {
                    matchingFiles
                }

            // Return shortcuts + already-ranked files (now including entities), properly ranked
            return (matchingShortcuts.take(10) + filteredFiles.map { it.first }).take(10)
        }
    }

    fun addSuggestedFile(path: String) {
        val nonProjectService = SweepNonProjectFilesService.getInstance(project)
        val relativeOrUrl =
            if (nonProjectService.isAllowedFile(path)) {
                path
            } else {
                relativePath(project, path)
            } ?: return
        val disambiguatedName = mentionsMap.putWithoutOverriding(relativeOrUrl)
        disambiguatedName?.let { filesFromSuggestion.add(it) }
    }

    val isPopupVisible: Boolean
        get() = popup?.isVisible == true

    // Cache for performance optimizations
    private var lastFiles: List<String>? = null
    private var currentSearchWord: String? = null // Track current search to prevent stale results
    private var cachedCaretPosition = -1
    private var cachedCaretRect: Rectangle2D? = null
    private var cachedLocationOnScreen: Point? = null
    private var cachedPreferredSize: Dimension? = null
    private var cachedFileCount = -1

    private fun invalidateCache() {
        cachedCaretPosition = -1
        cachedCaretRect = null
        cachedLocationOnScreen = null
        cachedPreferredSize = null
        cachedFileCount = -1
        currentSearchWord = null // Clear search tracking when cache is invalidated
    }

    private fun getCachedCaretInfo(): Pair<Rectangle2D, Point>? {
        val currentCaretPos = textComponent.caretPosition
        if (currentCaretPos != cachedCaretPosition || cachedCaretRect == null || cachedLocationOnScreen == null) {
            try {
                cachedCaretPosition = currentCaretPos
                cachedCaretRect = textComponent.textArea.modelToView2D(currentCaretPos)
                cachedLocationOnScreen = textComponent.textArea.locationOnScreen
            } catch (e: Exception) {
                // Graceful fallback if UI calculations fail
                invalidateCache()
                return null
            }
        }

        return if (cachedCaretRect != null && cachedLocationOnScreen != null) {
            Pair(cachedCaretRect!!, cachedLocationOnScreen!!)
        } else {
            null
        }
    }

    private fun getCachedPreferredSize(): Dimension {
        if (cachedFileCount != listModel.size() || cachedPreferredSize == null) {
            try {
                cachedFileCount = listModel.size()
                cachedPreferredSize = fileList.preferredSize
            } catch (e: Exception) {
                // Fallback to a reasonable default size if calculation fails
                cachedPreferredSize = Dimension(200, 100)
            }
        }
        return cachedPreferredSize ?: Dimension(200, 100)
    }

    private fun showPopupWithFiles(files: List<String>) {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { showPopupWithFiles(files) }
            return
        }

        // If files are unchanged but the user kept typing, we still need to repaint
        // so per-cell highlights (which depend on current input) refresh.
        if (files == lastFiles && isPopupVisible) {
            fileList.repaint()
            return
        }

        // Only update list model if files have changed
        if (files != lastFiles) {
            listModel.clear()
            files.forEach { listModel.addElement(it) }
            lastFiles = files.toList() // Create defensive copy
            // Invalidate cached size when list changes
            cachedPreferredSize = null
            cachedFileCount = -1
        }

        if (listModel.size() > 0) {
            if (isPopupVisible) {
                fileList.revalidate()
                fileList.repaint()
                val preferredSize = getCachedPreferredSize()
                popup?.size =
                    Dimension(
                        maxOf(preferredSize.width, popup!!.width),
                        preferredSize.height,
                    )

                // Recalculate position if showing above (dynamic offset may have changed)
                val currentLocation = popup?.locationOnScreen
                if (currentLocation != null && popupLocation != null) {
                    val caretInfo = getCachedCaretInfo()
                    if (caretInfo != null) {
                        val (caretRect, locationOnScreen) = caretInfo

                        // Check if we're showing above (popup is above the caret)
                        if (currentLocation.y < locationOnScreen.y + caretRect.y) {
                            // Recalculate position for "show above" case with updated dynamic offset
                            val dynamicOffset = if (listModel.size() < 8) 10 else 20
                            val newY =
                                (locationOnScreen.y + caretRect.y).toInt() - preferredSize.height - caretRect.height.toInt() -
                                    dynamicOffset
                            popup?.setLocation(Point(currentLocation.x, newY))
                        }
                    }
                }
            } else {
                popup =
                    JBPopupFactory
                        .getInstance()
                        .createListPopupBuilder(fileList)
                        .setRequestFocus(false)
                        .setItemChosenCallback(
                            Consumer {
                                insertSelection()
                                hidePopup()
                            },
                        ).createPopup()
                        .also { it.content.border = JBUI.Borders.empty() }

                // Get the location of the caret in screen coordinates using cached values
                val caretInfo = getCachedCaretInfo()
                if (caretInfo != null) {
                    val (caretRect, locationOnScreen) = caretInfo
                    val point =
                        Point(
                            locationOnScreen.x,
                            (locationOnScreen.y + caretRect.y).toInt(),
                        )

                    // Calculate available space above and below
                    val screenBounds = textComponent.graphicsConfiguration.bounds
                    val spaceAbove = point.y - screenBounds.y
                    val spaceBelow = screenBounds.height - (point.y - screenBounds.y)

                    val insets = fileList.border?.getBorderInsets(fileList) ?: JBUI.emptyInsets()
                    val preferredSize = getCachedPreferredSize()
                    val fileListHeight = preferredSize.height + insets.top + insets.bottom + (6) * fileList.itemsCount

                    // Decide whether to show above or below based on available space
                    if (spaceBelow >= fileListHeight || spaceBelow >= spaceAbove) {
                        // Show below
                        point.y += caretRect.height.toInt()
                        popup?.show(RelativePoint(point))
                    } else {
                        // Note 20 is the exact amount to place the popup above the text input in toolWindow so it does not block it.
                        point.y -= preferredSize.height + caretRect.height.toInt() + 20
                        popup?.show(RelativePoint(point))
                    }
                    popupLocation = popup?.locationOnScreen
                } else {
                    // Fallback: show popup at a default location if cache fails
                    try {
                        val caretRect = textComponent.textArea.modelToView2D(textComponent.caretPosition)
                        val locationOnScreen = textComponent.textArea.locationOnScreen
                        val fallbackPoint =
                            Point(
                                locationOnScreen.x,
                                (locationOnScreen.y + caretRect.y + caretRect.height).toInt(),
                            )
                        popup?.show(RelativePoint(fallbackPoint))
                    } catch (e: Exception) {
                        // Last resort: show at component location
                        popup?.showUnderneathOf(textComponent.textArea)
                    }
                    popupLocation = popup?.locationOnScreen
                }
            }
            fileList.selectedIndex = 0
        } else {
            hidePopup()
        }
    }

    private fun moveSelection(delta: Int) {
        val size = listModel.size()
        if (size > 0) {
            val newIndex = (fileList.selectedIndex + delta + size) % size
            fileList.selectedIndex = newIndex
            fileList.ensureIndexIsVisible(newIndex)
        }
    }

    private fun highlightFileMentions(removeFilesNotInHighlight: Boolean = true) {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { highlightFileMentions(removeFilesNotInHighlight) }
            return
        }

        if (isDisposed) return
        // Avoid interacting with the highlighter if the underlying Swing component is not displayable
        if (!textComponent.textArea.isDisplayable) return
        val currentHighlighter = textComponent.highlighter
        // Defensive: prevent stale internal reference to a unaccessible JTextComponent
        if (!highlighterHasValidComponent(currentHighlighter)) return
        val highlights = currentHighlighter.highlights ?: return
        for (highlight in highlights.toList()) {
            if (highlight.painter is RoundedHighlightPainter) {
                currentHighlighter.removeHighlight(highlight)
            }
        }

        val text = textComponent.text
        val spans = computeMentionSpans(text)

        val foundMatches = mutableSetOf<String>()
        for (span in spans) {
            // end is exclusive for Highlighter API
            currentHighlighter.addHighlight(span.start, span.end, fileHighlightPainter)
            foundMatches.add(span.key)
        }

        if (removeFilesNotInHighlight) {
            val toRemove =
                mentionsMap.keys.filter { key ->
                    // Key is not currently found in @-mentions
                    key !in foundMatches &&
                        // not @-mentioned in the text
                        !text.contains("@$key") &&
                        // keep current open file
                        mentionsMap.currentOpenFile?.first != key &&
                        // don't remove suggested files
                        key !in filesFromSuggestion
                }

            for (key in toRemove) {
                mentionsMap.removeAndUpdateText(key)
            }
        }
    }

    // Reflection helper: check if a Highlighter has a non-null internal JTextComponent.
    private fun highlighterHasValidComponent(highlighter: Highlighter?): Boolean {
        if (highlighter == null) return false
        return try {
            val field = highlighter.javaClass.getDeclaredField("component")
            field.isAccessible = true
            val comp = field.get(highlighter) as? javax.swing.text.JTextComponent
            comp != null
        } catch (e: Exception) {
            true
        }
    }

    private fun insertSelection() {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { insertSelection() }
            return
        }
        val text = textComponent.text
        val caretPosition = textComponent.caretPosition

        val atSymbolPos = text.lastIndexOf('@', caretPosition - 1)
        if (atSymbolPos == -1) return

        // Find the end of the current mention to replace the entire mention when editing within one
        var mentionEndPos = caretPosition
        while (mentionEndPos < text.length && !text[mentionEndPos].isWhitespace()) {
            mentionEndPos++
        }

        var newText = text.substring(0, atSymbolPos)

        val selection = fileList.selectedValue ?: return
        val addedMentions = mutableListOf<String>()

        fileUsageManager.addOrRefreshUsage(selection)
        val metadata = SweepMetaData.getInstance()
        metadata.fileContextUsageCount++

        if (shortcuts.contains(selection)) {
            if (selection == "Current.Changes") {
                val changeListManager = ChangeListManager.getInstance(project)
                val currentFile = getCurrentSelectedFile(project)
                val currentFileHasChanges = currentFile != null && changeListManager.getChange(currentFile) != null

                if (changeListManager.changeLists.size == 1 && !currentFileHasChanges) {
                    shortcuts[selection]!!.action() // single changelist perform action
                } else {
                    // multiple changelists OR current file has changes - open new list
                    val atCurrentSymbolPos = text.lastIndexOf('@', caretPosition - 1)
                    if (atCurrentSymbolPos != -1) {
                        textComponent.text = text.substring(0, atCurrentSymbolPos + 1) + text.substring(caretPosition)
                        textComponent.caretPosition = atCurrentSymbolPos + 1
                    }
                    shortcuts[selection]!!.action()
                    scheduleMentionChooser()
                    return
                }
            } else if (selection == "Terminal.Shortcut") {
                val activeTerminal = TerminalManagerService.getInstance(project).activeTerminal
                val editors = EditorSelectionManager.getInstance(project).getEditorListeners()
                val visibleEditors =
                    editors.keys.filter { editorInstance ->
                        editorInstance.contentComponent.isShowing
                    }

                val visibleEditorsCount =
                    visibleEditors.size + (if (activeTerminal?.component?.isShowing == true) 1 else 0)
                if (visibleEditorsCount == 1) {
                    shortcuts[selection]!!.action() // single showing terminal perform action
                } else {
                    // multiple showing terminals open new list
                    val atCurrentSymbolPos = text.lastIndexOf('@', caretPosition - 1)
                    if (atCurrentSymbolPos != -1) {
                        textComponent.text = text.substring(0, atCurrentSymbolPos + 1) + text.substring(caretPosition)
                        textComponent.caretPosition = atCurrentSymbolPos + 1
                    }
                    shortcuts[selection]!!.action()
                    scheduleMentionChooser()
                    return
                }
            } else {
                addedMentions.addAll(shortcuts[selection]!!.action())
            }
        } else {
            if (currentMode == SweepConstants.AutocompleteMode.DEFAULT) {
                if (entityNameFromPathString(selection).isNotEmpty()) {
                    mentionsMap.putWithoutOverriding(selection)?.let { addedMentions.add(it) }
                } else {
                    val file = File(project.osBasePath, selection)
                    if (file.isFile) {
                        mentionsMap.putWithoutOverriding(selection)?.let { addedMentions.add(it) }
                    } else if (file.isDirectory) {
                        mentionsMap.putWithoutOverriding(selection, isDirectory = true)?.let {
                            addedMentions.add(getDisplayKey(it, true))
                        }
                    } else {
                        // handle non project files case
                        val virtualFile =
                            SweepNonProjectFilesService
                                .getInstance(
                                    project,
                                ).getVirtualFileAssociatedWithAllowedFile(project, selection)
                        if (virtualFile != null) {
                            mentionsMap.putWithoutOverriding(selection)?.let { addedMentions.add(it) }
                        }
                    }
                }
            }
        }

        if (currentMode == SweepConstants.AutocompleteMode.CHANGE_LIST) {
            val splitValue = fileList.selectedValue?.split("::") ?: return
            if (splitValue.size < 2) return
            val currentChangeList = splitValue[1]
            appendChangeListToChat(project, currentChangeList, logger, filesInContextComponent)
            currentMode = SweepConstants.AutocompleteMode.DEFAULT
        } else if (currentMode == SweepConstants.AutocompleteMode.ACTIVE_TERMINALS) {
            val splitValue = fileList.selectedValue?.split("::") ?: return
            if (splitValue.size < 2) return
            val type = splitValue[0]
            val name = splitValue[1]

            var textToAppend: String? = null

            if (name.contains("Terminal")) {
                val activeTerminal = TerminalManagerService.getInstance(project).activeTerminal as? ShellTerminalWidget
                textToAppend = activeTerminal?.text?.trim() ?: ""
            } else if (name.contains("Console")) {
                val editorSelectionManager = EditorSelectionManager.getInstance(project)
                val editors = editorSelectionManager.getEditorListeners()
                val visibleEditors =
                    editors.keys.filter { editorInstance ->
                        editorInstance.contentComponent.isShowing
                    }
                // note this is a source of error, might not be in sorted order
                val visibleEditor =
                    visibleEditors.find { editorInstance ->
                        editorInstance.contentComponent.isShowing &&
                            (
                                editorInstance.virtualFile?.name
                                    ?: "Console ${visibleEditors.indexOf(editorInstance) + 1}"
                            ) == name
                    }
                textToAppend = visibleEditor?.document?.text?.trim()
            }

            if (!textToAppend.isNullOrBlank()) {
                val commandName = parseCommandFromTerminalOutput(textToAppend)
                appendSelectionToChat(
                    project,
                    textToAppend,
                    commandName,
                    logger,
                    suggested = false,
                    showToolWindow = false,
                    requestFocus = false,
                    alwaysAddFilePill = true,
                )
            } else {
                showNotification(project, "Empty Output", "Selected terminal or console has no output.")
            }
            currentMode = SweepConstants.AutocompleteMode.DEFAULT
        }

        val insertedText =
            if (addedMentions.isNotEmpty()) {
                addedMentions.joinToString(" ") { "@$it" } + " "
            } else {
                ""
            }

        newText += insertedText + text.substring(mentionEndPos)
        val newCaretPosition = atSymbolPos + insertedText.length

        // Temporarily remove listeners to prevent interference with cursor positioning
        documentListener?.let { textComponent.textArea.document.removeDocumentListener(it) }
        caretListener?.let { textComponent.textArea.removeCaretListener(it) }

        textComponent.text = newText

        // Not redundant: need to ensure cursor position is set after any pending cursor movements (race condition)
        ApplicationManager.getApplication().invokeLater {
            if (isDisposed) return@invokeLater
            textComponent.caretPosition = newCaretPosition.coerceAtLeast(0).coerceAtMost(newText.length)

            // Re-add listeners after cursor is positioned
            documentListener?.let { textComponent.textArea.document.addDocumentListener(it) }
            caretListener?.let { textComponent.textArea.addCaretListener(it) }

            // Invalidate cache to ensure new mentions are found
            invalidateMentionSpansCache()
            highlightFileMentions()
        }

        hidePopup()
    }

    private fun hidePopup() {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeAndWait { hidePopup() }
            return
        }

        popup?.run {
            cancel()
            dispose()
        }
        popup = null

        // Detach the persistent JBList from any parent (popup wrappers) to break
        // the strong reference chain back to the disposed AbstractPopup.
        // This prevents memory leaks via: JBList -> JBViewport -> MyListWrapper ->
        // PopupListAdapter -> PopupChooserBuilder -> myPopup (AbstractPopup disposed)
        fileList.parent?.remove(fileList)

        // Invalidate cache when popup is hidden to ensure fresh calculations next time
        invalidateCache()

        // Clean up any listeners that may have been added to fileList by the popup
        // This prevents memory leaks from PopupChooserBuilder mouse listeners
        fileList.mouseListeners.toList().forEach { listener ->
            // Only remove listeners that are likely from PopupChooserBuilder to avoid breaking our own listeners
            if (listener.javaClass.name.contains("PopupChooserBuilder") ||
                listener.javaClass.name.contains("AbstractPopup")
            ) {
                fileList.removeMouseListener(listener)
            }
        }

        // Only reset mode if we're not currently selecting the Change Mode shortcut
        if (fileList.selectedValue != "Current.Changes" && fileList.selectedValue != "Terminal.Shortcut") {
            currentMode = SweepConstants.AutocompleteMode.DEFAULT
        }

        textComponent.run {
            invalidate()
            revalidate()
            repaint()
            parent?.validate()
        }
    }

    override fun dispose() {
        // Mark disposed early so any queued invokeLater tasks will no-op
        isDisposed = true
        // Cancel any pending alarm requests before disposing other resources
        alarm.cancelAllRequests()
        alarm.dispose()
        hidePopup()
        // Clean up file rename listener
        fileRenameListener?.removeOnFileRenamedAction(fileRenameHandlerIdentifier)
        fileRenameListener?.removeOnFileDeletedAction(fileDeletionHandlerIdentifier)
        fileRenameListener?.dispose()
        fileRenameListener = null

        documentListener?.let { textComponent.textArea.document.removeDocumentListener(it) }
        keyListener?.let { textComponent.textArea.removeKeyListener(it) }
        caretListener?.let { textComponent.textArea.removeCaretListener(it) }

        // Remove all mouse listeners that may have been added by PopupChooserBuilder
        // This prevents memory leaks from disposed popups holding references
        fileList.mouseListeners.forEach { listener ->
            fileList.removeMouseListener(listener)
        }

        documentListener = null
        keyListener = null
        caretListener = null

        // Clear shortcuts to release lambda references and prevent PluginClassLoader leak
        shortcuts = emptyMap()
//        instances.remove(project)
    }
}
