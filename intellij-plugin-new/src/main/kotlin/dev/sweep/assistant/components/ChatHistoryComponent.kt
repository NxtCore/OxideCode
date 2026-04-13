package dev.sweep.assistant.components

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.componentsList.layout.VerticalStackLayout
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.sweep.assistant.controllers.Stream
import dev.sweep.assistant.data.MessageRole
import dev.sweep.assistant.data.distinctFileInfos
import dev.sweep.assistant.services.ChatHistory
import dev.sweep.assistant.services.MessageList
import dev.sweep.assistant.services.SweepSessionManager
import dev.sweep.assistant.services.TabManager
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepColors.borderColor
import dev.sweep.assistant.utils.*
import dev.sweep.assistant.views.ButtonHoverState
import dev.sweep.assistant.views.HistoryListCellRenderer
import dev.sweep.assistant.views.HistoryListItem
import java.awt.*
import java.awt.event.*
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import javax.swing.*
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

@Service(Service.Level.PROJECT)
class ChatHistoryComponent(
    private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): ChatHistoryComponent = project.getService(ChatHistoryComponent::class.java)

        private var maxWidth = 330.scaled
        private var maxHeight = 400.scaled
        private const val IMPORT_PANEL_NAME = "importHistoryPanel"
        private const val POPUP_IMPORT_PANEL_NAME = "popupImportHistoryPanel"
    }

    private val logger = Logger.getInstance(ChatHistoryComponent::class.java)
    private var recentChatsScrollPane: JBScrollPane? = null

    // Token to guard against out-of-order loadConversation UI updates
    private val latestLoadToken = AtomicReference<String>()

    private var isExpanded: Boolean
        get() = PropertiesComponent.getInstance(project).getBoolean(SweepConstants.PREVIOUS_CHATS_EXPANDED_KEY, true)
        set(value) {

            PropertiesComponent.getInstance(project).setValue(SweepConstants.PREVIOUS_CHATS_EXPANDED_KEY, value, true)
        }

    private fun registerListener(
        component: Component,
        listener: EventListener,
    ) {
        // Register with this component as the parent disposable
        Disposer.register(
            this,
            {
                try {
                    when (listener) {
                        is MouseListener -> component.removeMouseListener(listener)
                        is MouseMotionListener -> component.removeMouseMotionListener(listener)
                        is ComponentListener -> component.removeComponentListener(listener)
                        is KeyListener -> component.removeKeyListener(listener)
                        is FocusListener -> component.removeFocusListener(listener)
                        is DocumentListener -> (component as? JTextComponent)?.document?.removeDocumentListener(listener)
                        is ActionListener -> (component as? AbstractButton)?.removeActionListener(listener)
                    }
                } catch (e: Exception) {
                    logger.warn("Error removing listener", e)
                }
            },
        )
    }

    private fun registerPopupListener(
        component: Component,
        listener: EventListener,
        disposable: Disposable,
    ) {
        // Register a disposable that will remove the listener when disposed
        Disposer.register(
            disposable,
            {
                try {
                    when (listener) {
                        is MouseListener -> component.removeMouseListener(listener)
                        is MouseMotionListener -> component.removeMouseMotionListener(listener)
                        is ComponentListener -> component.removeComponentListener(listener)
                        is KeyListener -> component.removeKeyListener(listener)
                        is FocusListener -> component.removeFocusListener(listener)
                        is DocumentListener -> (component as? JTextComponent)?.document?.removeDocumentListener(listener)
                        is ActionListener -> (component as? AbstractButton)?.removeActionListener(listener)
                    }
                } catch (e: Exception) {
                    logger.warn("Error removing popup listener", e)
                }
            },
        )
    }

    fun loadConversation(conversationId: String) {
        // loadConversation is called from mouse events which run on EDT so we must run slow database operations on background thread
        // Generate a token for this load to avoid stale UI updates being applied out of order
        val token =
            UUID
                .randomUUID()
                .toString()
        latestLoadToken.set(token)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                saveCurrentConversation(project)
                SweepMetaData.getInstance().chatHistoryUsed++

                // Get conversation name for tab title
                val conversationName =
                    ChatHistory.getInstance(project).getConversationName(conversationId)
                        ?: ChatHistory
                            .getInstance(project)
                            .getConversation(conversationId)
                            .firstOrNull { it.role == MessageRole.USER }
                            ?.content
                            ?.take(50)

                val savedMessages = ChatHistory.getInstance(project).getConversation(conversationId)

                // Switch back to EDT for UI updates
                ApplicationManager.getApplication().invokeLater {
                    // Ignore stale load if a newer one was requested after this started
                    if (latestLoadToken.get() != token) return@invokeLater

                    // Check if this conversation is already open in an existing tab
                    // If so, just switch to it without stopping streams or reloading messages
                    val sessionManager = SweepSessionManager.getInstance(project)
                    val existingSession = sessionManager.getSessionByConversationId(conversationId)
                    if (existingSession?.content != null) {
                        // Conversation is already open - just switch to that tab
                        TabManager.getInstance(project).contentManager.setSelectedContent(existingSession.content!!)
                        logger.info("Switched to existing tab for conversation: $conversationId")
                        return@invokeLater
                    }

                    // Create a new tab for this conversation
                    val session =
                        TabManager.getInstance(project).openOrCreateTabForConversation(
                            conversationId,
                            conversationName,
                        )

                    if (savedMessages.isNotEmpty()) {
                        // Gather all mentionedFiles from all messages into a single distinct array
                        val customFileSnippets =
                            savedMessages
                                .flatMap { it.mentionedFiles }
                                .distinctFileInfos()
                                .filter { fileInfo ->
                                    fileInfo.name.startsWith(SweepConstants.GENERAL_TEXT_SNIPPET_PREFIX) &&
                                        fileInfo.span == null &&
                                        fileInfo.codeSnippet?.trim()?.isNotEmpty() == true
                                }

                        // Process custom file snippets in background
                        if (customFileSnippets.isNotEmpty()) {
                            ApplicationManager.getApplication().executeOnPooledThread {
                                customFileSnippets.forEach { fileInfo ->
                                    try {
                                        val file = File(fileInfo.relativePath)
                                        if (file.createNewFile()) {
                                            logger.info("Creating new file: ${fileInfo.relativePath}")
                                            file.writeText(fileInfo.codeSnippet!!)
                                        }
                                    } catch (e: Exception) {
                                        logger.warn("Failed to create file for custom snippet: ${fileInfo.name}", e)
                                    }
                                }
                            }
                        }

                        val lastMessage = savedMessages.lastOrNull()
                        val messagesToLoad =
                            if (lastMessage?.role == MessageRole.USER) {
                                // Set the last user message to the text field
                                val mentionedFiles = lastMessage.mentionedFiles
                                val mentionedFilesMap =
                                    mentionedFiles
                                        .filter { it.span == null }
                                        .associate { it.name to it.relativePath }

                                val mentionedSnippetsMap =
                                    mentionedFiles
                                        .filter { it.span != null }
                                        .associate {
                                            it.name to
                                                File(
                                                    project.osBasePath!!,
                                                    it.relativePath,
                                                ).absolutePath
                                        }
                                ChatComponent.getInstance(project).textField.text = lastMessage.content
                                ChatComponent.getInstance(project).filesInContextComponent.replaceIncludedSnippets(
                                    mentionedSnippetsMap,
                                )
                                ChatComponent.getInstance(project).filesInContextComponent.replaceIncludedFiles(
                                    mentionedFilesMap,
                                )
                                // Load all messages except the last one
                                savedMessages.dropLast(1)
                            } else {
                                val lastUserMessage = savedMessages.lastOrNull { it.role == MessageRole.USER }
                                val mentionedFiles = lastUserMessage?.mentionedFiles
                                val mentionedFilesMap =
                                    mentionedFiles
                                        ?.filter { it.is_full_file && !it.name.startsWith(SweepConstants.GENERAL_TEXT_SNIPPET_PREFIX) }
                                        ?.associate { it.name to it.relativePath }
                                if (!mentionedFilesMap.isNullOrEmpty()) {
                                    ChatComponent
                                        .getInstance(
                                            project,
                                        ).filesInContextComponent
                                        .reset()
                                    ChatComponent
                                        .getInstance(
                                            project,
                                        ).filesInContextComponent
                                        .addIncludedFiles(mentionedFilesMap.values.toList())
                                }
                                savedMessages
                            }

                        // Safety: ensure no stale stream exists for this conversationId
                        // (This shouldn't happen since we checked for existing sessions above, but just in case)
                        Stream.instances[conversationId]?.stop(isUserInitiated = false)

                        // Load messages into the session's message list
                        session.messageList
                            .resetMessages(messagesToLoad, resetConversationId = false)
                        session.messageList.conversationId = conversationId

                        // Update the session's UI component
                        if (messagesToLoad.isNotEmpty()) {
                            session.uiComponent?.showFollowupDisplay()
                        }

                        // Show loading overlay while switching conversations to prevent blank screen
                        MessagesComponent.getInstance(project).showLoadingOverlay(conversationName)
                        MessagesComponent.getInstance(project).update(
                            MessagesComponent.UpdateType.CHANGE_CHAT,
                            conversationId = conversationId,
                        )
                        ChatComponent.getInstance(project).textField.requestFocus()
                        ChatComponent.getInstance(project).updateChatComponentButtons()
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to load conversation: $conversationId", e)
                // Show error notification to user on EDT
                showNotification(
                    project,
                    "Failed to load conversation",
                    "The conversation could not be loaded due to an error. Please report this issue to Sweep if it persists.",
                    "Error Notifications",
                )
            }
        }
    }

    private fun buildPopupListModel(): DefaultListModel<HistoryListItem> {
        // This method should only be called from EDT and creates a model with loading placeholders
        val listModel = DefaultListModel<HistoryListItem>()

        // Add a loading indicator first
        listModel.addElement(
            HistoryListItem(
                conversationId = "loading",
                preview = "Loading conversations...",
                timestamp = System.currentTimeMillis(),
                onDelete = {},
                onRename = {},
                matchedIndices = emptyList(),
                isRecentChatsItem = false,
            ),
        )

        return listModel
    }

    private fun loadPopupListModelAsync(
        listModel: DefaultListModel<HistoryListItem>,
        listContainer: JPanel? = null,
        onLoadComplete: ((isEmpty: Boolean, importableHistories: List<ChatHistory.ImportableHistory>) -> Unit)? = null,
    ) {
        // Move heavy database operations to background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val conversations =
                    ChatHistory.getInstance(project).getRecentConversations(SweepConstants.MAX_RECENT_CONVERSATIONS)
                val historyItems = mutableListOf<HistoryListItem>()

                conversations.forEach { conversationId ->
                    val preview =
                        ChatHistory.getInstance(project).getConversationName(conversationId)
                            ?: ChatHistory
                                .getInstance(project)
                                .getConversation(conversationId)
                                .firstOrNull { it.role == MessageRole.USER }
                                ?.content
                            ?: "Empty conversation"

                    val timestamp = ChatHistory.getInstance(project).getTimestamp(conversationId) ?: 0L

                    historyItems.add(
                        HistoryListItem(
                            conversationId,
                            preview,
                            timestamp,
                            onDelete = {
                                // Delete operations should also be moved to background thread
                                ApplicationManager.getApplication().executeOnPooledThread {
                                    ChatHistory.getInstance(project).deleteConversation(conversationId)
                                    ApplicationManager.getApplication().invokeLater {
                                        listModel.removeElement(
                                            listModel.elements().toList().find { it.conversationId == conversationId },
                                        )
                                        refreshRecentChats()
                                    }
                                }
                            },
                            onRename = { newName ->
                                ApplicationManager.getApplication().executeOnPooledThread {
                                    ChatHistory.getInstance(project).renameChatHistoryName(conversationId, newName)
                                    ApplicationManager.getApplication().invokeLater {
                                        // Update the item in the list model
                                        val itemIndex =
                                            listModel
                                                .elements()
                                                .toList()
                                                .indexOfFirst { it.conversationId == conversationId }
                                        if (itemIndex >= 0) {
                                            val updatedItem = listModel.getElementAt(itemIndex).copy(preview = newName)
                                            listModel.setElementAt(updatedItem, itemIndex)
                                        }
                                        refreshRecentChats()

                                        // update tab title if this is the current conversation
                                        if (MessageList.getInstance(project).activeConversationId == conversationId) {
                                            TabManager.getInstance(project).setCurrentTitle(newName)
                                        }
                                    }
                                }
                            },
                            matchedIndices = emptyList(),
                        ),
                    )
                }

                // Check for importable histories if current history is empty
                val importableHistories =
                    if (conversations.isEmpty()) {
                        ChatHistory.getInstance(project).detectImportableHistories()
                    } else {
                        emptyList()
                    }

                // Update UI on EDT
                ApplicationManager.getApplication().invokeLater {
                    listModel.clear()
                    historyItems.forEach { listModel.addElement(it) }
                    // Store original items for search functionality
                    originalPopupItems = historyItems.toList()

                    // Notify callback about load completion
                    onLoadComplete?.invoke(historyItems.isEmpty(), importableHistories)
                }
            } catch (e: Exception) {
                logger.warn("Failed to load conversations for popup", e)
                ApplicationManager.getApplication().invokeLater {
                    listModel.clear()
                    listModel.addElement(
                        HistoryListItem(
                            conversationId = "error",
                            preview = "Error loading conversations",
                            timestamp = System.currentTimeMillis(),
                            onDelete = {},
                            onRename = {},
                            matchedIndices = emptyList(),
                            isRecentChatsItem = false,
                        ),
                    )
                    onLoadComplete?.invoke(true, emptyList())
                }
            }
        }
    }

    fun refreshRecentChats() {
        recentChatsScrollPane?.let { scrollPane ->
            val parent = scrollPane.parent
            parent?.remove(scrollPane)
            scrollPane.viewport.view?.let { view ->
                for (listener in view.mouseListeners) {
                    view.removeMouseListener(listener)
                }
                for (listener in view.mouseMotionListeners) {
                    view.removeMouseMotionListener(listener)
                }
            }
            recentChatsScrollPane = createRecentChats()
            parent?.add(recentChatsScrollPane)
            parent?.revalidate()
            parent?.repaint()
        }
    }

    private fun buildPopupList(popupDisposable: Disposable) =
        JBList(buildPopupListModel()).apply {
            val list = this
            cellRenderer = HistoryListCellRenderer(project, isInChatHistoryComponent = true)
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 10
            border = JBUI.Borders.empty()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            // Start async loading of actual data
            loadPopupListModelAsync(model as DefaultListModel<HistoryListItem>)

            val motionListener =
                MouseMotionMovedAdapter { e ->
                    val index = locationToIndex(e.point)
                    val bounds = if (index >= 0) getCellBounds(index, index) else null

                    // Determine which button area is being hovered
                    val buttonHoverState =
                        if (bounds != null) {
                            when {
                                e.x > bounds.width - 40.scaled -> ButtonHoverState.DELETE
                                e.x > bounds.width - 80.scaled && e.x <= bounds.width - 40.scaled -> ButtonHoverState.EDIT
                                else -> ButtonHoverState.NONE
                            }
                        } else {
                            ButtonHoverState.NONE
                        }

                    val oldIndex = getClientProperty("hoveredIndex")
                    val oldButtonState = getClientProperty("buttonHoverState")
                    val oldRowIndex = getClientProperty("hoveredRowIndex")

                    if (index != oldIndex || buttonHoverState != oldButtonState || index != oldRowIndex) {
                        putClientProperty("hoveredIndex", index)
                        putClientProperty("buttonHoverState", buttonHoverState)
                        putClientProperty("hoveredRowIndex", index)
                        repaint()
                    }
                }
            addMouseMotionListener(motionListener)
            registerPopupListener(this, motionListener, popupDisposable)

            val mouseAdapter =
                object : MouseAdapter() {
                    override fun mouseExited(e: MouseEvent) {
                        putClientProperty("hoveredIndex", -1)
                        putClientProperty("buttonHoverState", ButtonHoverState.NONE)
                        putClientProperty("hoveredRowIndex", -1)
                        repaint()
                    }

                    override fun mouseReleased(e: MouseEvent) {
                        val index = locationToIndex(e.point)
                        if (index >= 0) {
                            val item = model.getElementAt(index) as HistoryListItem
                            val bounds = getCellBounds(index, index)

                            // Ignore clicks on loading/error items
                            if (item.conversationId == "loading" || item.conversationId == "error") {
                                return
                            }

                            if (e.x > bounds.width - 40.scaled) {
                                // Don't allow delete on recent chats items
                                if (!item.isRecentChatsItem) {
                                    item.onDelete()
                                }
                                return
                            }

                            if (e.x > bounds.width - 80.scaled && e.x <= bounds.width - 40.scaled) {
                                // Don't allow rename on recent chats items
                                if (!item.isRecentChatsItem) {
                                    // Create overlay text field for editing
                                    showRenameOverlay(list, item, bounds)
                                }
                                return
                            }

                            loadConversation(item.conversationId)
                        }
                    }
                }
            addMouseListener(mouseAdapter)
            registerPopupListener(this, mouseAdapter, popupDisposable)
        }

    // Cache for search results to avoid repeated fuzzy matching
    private val searchResultsCache = mutableMapOf<String, List<Int>>()
    private var lastSearchText = ""

    /**
     * Optimized fuzzy matching with caching.
     * Returns the **positions** of matched characters.
     * If not all of `pattern` is matched, returns an empty list.
     */
    private fun fuzzyMatchIndices(
        pattern: String,
        text: String,
    ): List<Int> {
        if (pattern.isEmpty()) return emptyList()

        // Create cache key
        val cacheKey = "${pattern.lowercase()}_${text.lowercase()}"

        // Check cache first
        searchResultsCache[cacheKey]?.let { return it }

        // Pre-compute lowercase versions to avoid repeated calls
        val lowerPattern = pattern.lowercase()
        val lowerText = text.lowercase()

        val matchedIndices = mutableListOf<Int>()
        var i = 0 // index into pattern
        var j = 0 // index into text

        while (i < lowerPattern.length && j < lowerText.length) {
            if (lowerPattern[i] == lowerText[j]) {
                matchedIndices.add(j)
                i++
            }
            j++
        }

        val result = if (i == lowerPattern.length) matchedIndices else emptyList()

        // Cache the result
        searchResultsCache[cacheKey] = result

        return result
    }

    private fun clearSearchCache() {
        searchResultsCache.clear()
    }

    private var originalPopupItems: List<HistoryListItem> = emptyList()
    private var searchTimer: Timer? = null
    private var activeRenameTextField: JTextField? = null
    private var activeRenameList: JList<HistoryListItem>? = null

    private fun createSearchField(
        list: JBList<HistoryListItem>,
        popupDisposable: Disposable,
    ): SearchTextField =
        SearchTextField().apply {
            textEditor.emptyText.text = "Search Previous Chats..."

            val documentListener =
                object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent) = scheduleFilter()

                    override fun removeUpdate(e: DocumentEvent) = scheduleFilter()

                    override fun changedUpdate(e: DocumentEvent) = scheduleFilter()

                    private fun scheduleFilter() {
                        // Cancel previous timer to avoid excessive filtering
                        searchTimer?.stop()
                        searchTimer =
                            Timer(150) {
                                // 150ms delay for debouncing
                                ApplicationManager.getApplication().invokeLater {
                                    filterList()
                                }
                            }.apply {
                                isRepeats = false
                                start()
                            }
                    }

                    private fun filterList() {
                        val searchText = text.trim()
                        val listModel = list.model as DefaultListModel<HistoryListItem>

                        // Clear search cache when search text changes significantly
                        if (lastSearchText != searchText) {
                            if (searchText.length < lastSearchText.length - 1) {
                                clearSearchCache()
                            }
                            lastSearchText = searchText
                        }

                        // If we don't have original items yet, wait for async loading
                        if (originalPopupItems.isEmpty()) {
                            // Check if we have loaded items (not loading/error items)
                            val currentItems = listModel.elements().toList()
                            if (currentItems.isNotEmpty() &&
                                currentItems.none { it.conversationId == "loading" || it.conversationId == "error" }
                            ) {
                                originalPopupItems = currentItems.toList()
                            } else {
                                return // Still loading, wait
                            }
                        }

                        listModel.clear()

                        if (searchText.isEmpty()) {
                            originalPopupItems.forEach { item ->
                                item.matchedIndices = emptyList()
                                listModel.addElement(item)
                            }
                            return
                        }

                        // Perform search in background to avoid blocking EDT
                        ApplicationManager.getApplication().executeOnPooledThread {
                            val filteredItems = mutableListOf<HistoryListItem>()

                            for (item in originalPopupItems) {
                                val matchedPositions = fuzzyMatchIndices(searchText, item.preview)
                                if (matchedPositions.isNotEmpty()) {
                                    // Create a copy to avoid modifying the original
                                    val filteredItem = item.copy(matchedIndices = matchedPositions)
                                    filteredItems.add(filteredItem)
                                }
                            }

                            // Update UI on EDT
                            ApplicationManager.getApplication().invokeLater {
                                listModel.clear()
                                filteredItems.forEach { listModel.addElement(it) }
                            }
                        }
                    }
                }

            textEditor.document.addDocumentListener(documentListener)
            registerPopupListener(textEditor, documentListener, popupDisposable)

            // Store reference to update original items when async loading completes
            Disposer.register(popupDisposable) {
                searchTimer?.stop()
                originalPopupItems = emptyList()
                clearSearchCache()
            }
        }

    fun showChatHistoryPopup() {
        var popup: JBPopup? = null
        val popupDisposable = Disposer.newDisposable("ChatHistoryPopup")
        val list = buildPopupList(popupDisposable)

        // Calculate height based on expected row count instead of current content
        // since we now load asynchronously
        val rowHeight =
            list.cellRenderer
                .getListCellRendererComponent(
                    list,
                    HistoryListItem("", "Sample text", 0L, {}, {}, emptyList()),
                    0,
                    false,
                    false,
                ).preferredSize.height
        val expectedHeight = (rowHeight * list.visibleRowCount).coerceAtMost(maxHeight)

        val searchField = createSearchField(list, popupDisposable)

        val searchPanel =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(4, 8)
                add(searchField, BorderLayout.CENTER)
            }

        val headerLabel =
            JLabel("Chat History").apply {
                withSweepFont(project, scale = 1.1f, bold = true)
                border = JBUI.Borders.empty(8).scaled
            }

        val headerPanel =
            JPanel(BorderLayout()).apply {
                background = null
                add(headerLabel, BorderLayout.WEST)

                add(
                    JButton(AllIcons.Actions.Close).apply {
                        preferredSize = Dimension(24, 24).scaled
                        isBorderPainted = false
                        isContentAreaFilled = false
                        isOpaque = false
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        border = JBUI.Borders.empty(12, 12, 12, 24).scaled
                        addActionListener { popup?.closeOk(null) }
                    },
                    BorderLayout.EAST,
                )
            }

        // Create import panel placeholder (will be shown/hidden based on history state)
        val importPanelContainer =
            JPanel(BorderLayout()).apply {
                name = POPUP_IMPORT_PANEL_NAME
                isVisible = false
            }

        val listScrollPane =
            JBScrollPane(list).apply {
                preferredSize = Dimension(maxWidth, expectedHeight).scaled
                border = JBUI.Borders.empty()
            }

        val listContainer =
            JPanel(BorderLayout()).apply {
                add(
                    JPanel(BorderLayout()).apply {
                        add(
                            JSeparator().apply {
                                foreground = borderColor
                                border = JBUI.Borders.empty()
                            },
                            BorderLayout.NORTH,
                        )
                        add(searchPanel, BorderLayout.CENTER)
                    },
                    BorderLayout.NORTH,
                )

                add(
                    JPanel(BorderLayout()).apply {
                        add(
                            JSeparator().apply {
                                foreground = borderColor
                                border = JBUI.Borders.empty(0, 16).scaled
                            },
                            BorderLayout.NORTH,
                        )
                        add(importPanelContainer, BorderLayout.CENTER)
                        add(listScrollPane, BorderLayout.SOUTH)
                    },
                    BorderLayout.CENTER,
                )
            }

        val contentPanel =
            JPanel(BorderLayout()).apply {
                add(headerPanel, BorderLayout.NORTH)
                // This mouse listener lets user un-focus the search field by clicking outside
                val mouseListener =
                    MousePressedAdapter {
                        requestFocusInWindow()
                    }
                addMouseListener(mouseListener)
                registerPopupListener(this, mouseListener, popupDisposable)

                add(listContainer, BorderLayout.CENTER)
            }

        // Override the async loading to also handle import panel
        loadPopupListModelAsync(
            list.model as DefaultListModel<HistoryListItem>,
            listContainer,
        ) { isEmpty, importableHistories ->
            // Update header text based on empty state
            headerLabel.text = if (isEmpty && importableHistories.isEmpty()) "No Chat History" else "Chat History"

            // Show import panel if history is empty but importable histories exist
            if (isEmpty && importableHistories.isNotEmpty()) {
                importPanelContainer.removeAll()
                importPanelContainer.add(
                    createPopupImportPanel(importableHistories, listContainer),
                    BorderLayout.CENTER,
                )
                importPanelContainer.isVisible = true

                // Resize the popup to accommodate the import panel
                listScrollPane.preferredSize = Dimension(maxWidth, 100.scaled)
            } else {
                importPanelContainer.isVisible = false
                listScrollPane.preferredSize = Dimension(maxWidth, expectedHeight).scaled
            }

            contentPanel.revalidate()
            contentPanel.repaint()
        }

        popup =
            JBPopupFactory
                .getInstance()
                .createComponentPopupBuilder(contentPanel, searchField)
                .setRequestFocus(true)
                .setFocusable(true)
                .setCancelCallback {
                    // for rename remove overlay and keep popup
                    if (activeRenameTextField != null && activeRenameList != null) {
                        removeOverlay(activeRenameList!!, activeRenameTextField!!)
                        false
                    } else {
                        true // close
                    }
                }.createPopup()

        // Register the disposable with the popup so it gets disposed when popup is closed
        Disposer.register(popup, popupDisposable)

        // Get the active session's UI component (multi-tab support)
        val activeSession = SweepSessionManager.getInstance(project).getActiveSession()
        val sessionComponent = activeSession?.uiComponent

        // Check if the component is showing before getting location to avoid IllegalComponentStateException
        if (sessionComponent == null || !sessionComponent.component.isShowing) {
            Disposer.dispose(popupDisposable)
            return
        }

        val locationOnScreen = sessionComponent.locationOnScreen
        val point =
            Point(
                locationOnScreen.x - maxWidth,
                locationOnScreen.y,
            )

        popup.show(RelativePoint(point))
    }

    /**
     * Creates the import panel for the chat history popup
     */
    private fun createPopupImportPanel(
        importableHistories: List<ChatHistory.ImportableHistory>,
        parentContainer: JPanel,
    ): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = null
            border = JBUI.Borders.empty(12, 16, 8, 16).scaled

            add(
                JLabel(
                    "<html><div style='width: 280px;'>" +
                        "We detected chat history from a previous IDE version that you may want to import." +
                        "</div></html>",
                ).apply {
                    withSweepFont(project, scale = 0.9f)
                    foreground = JBColor.GRAY
                    alignmentX = Component.LEFT_ALIGNMENT
                },
            )

            add(Box.createVerticalStrut(8.scaled))

            add(
                JButton("Import Chat History").apply {
                    withSweepFont(project, scale = 0.9f)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    alignmentX = Component.LEFT_ALIGNMENT
                    addActionListener {
                        showImportHistoryPopup(importableHistories, parentContainer)
                    }
                },
            )
        }

    private fun showTooltipBalloon(
        panel: JComponent,
        anchorY: Int,
    ) {
        val balloon =
            JBPopupFactory
                .getInstance()
                .createHtmlTextBalloonBuilder(
                    // The HTML content of your balloon
                    "<div style='padding: 4px; width: 220px; word-wrap: break-word;'>" +
                        "<b>💬 View and continue your previous chats</b></div>",
                    null,
                    null,
                    SweepColors.tooltipBackgroundColor,
                    null,
                ).setAnimationCycle(0)
                .setHideOnAction(false)
                .setHideOnLinkClick(false)
                .setHideOnKeyOutside(false)
                .setHideOnClickOutside(false)
                .createBalloon()

        if (TooltipManager.getInstance(project).showTooltip(balloon)) {
            // Anchor at (x=0, y=labelCenterY) in the RoundedPanel's coordinate space
            // and place the balloon "to the left" (arrow on the right edge).
            balloon.show(RelativePoint(panel, Point(0, anchorY)), Balloon.Position.atLeft)

            // Use a timer to automatically hide the balloon after a delay
            Timer(3000) {
                // 3000ms = 3 seconds
                ApplicationManager.getApplication().invokeLater {
                    balloon.hide()
                    balloon.dispose()
                }
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    fun createRecentChats(): JBScrollPane {
        val container =
            JPanel(VerticalStackLayout()).apply {
                border = JBUI.Borders.empty(16, 0, 16, 0).scaled // Reduced top padding
                val mouseListener =
                    MousePressedAdapter {
                        requestFocusInWindow()
                    }
                addMouseListener(mouseListener)
                registerListener(this, mouseListener)

                val previousChatsLabel =
                    JLabel("Recent Chats").apply {
                        withSweepFont(project, bold = true)
                        border = JBUI.Borders.emptyLeft(4).scaled
                        foreground = if (isExpanded) UIUtil.getLabelForeground() else JBColor.GRAY
                    }

                val viewAllLabel =
                    JLabel("View All").apply {
                        withSweepFont(project)
                        foreground = JBColor.BLUE.withLightMode()
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        border = JBUI.Borders.empty(0, 12).scaled
                        isVisible = isExpanded
                        val mouseReleaseListener =
                            MouseReleasedAdapter {
                                showChatHistoryPopup()
                            }
                        addMouseListener(mouseReleaseListener)
                        registerListener(this, mouseReleaseListener)
                    }

                fun getArrowIcon(
                    expanded: Boolean,
                    grayed: Boolean,
                ): Icon {
                    val baseIcon = if (expanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
                    return if (grayed) {
                        IconLoader.getDisabledIcon(baseIcon)
                    } else {
                        baseIcon
                    }
                }

                val recentChatsContainer = JPanel(VerticalStackLayout()).apply { background = null }
                recentChatsContainer.add(
                    JPanel(BorderLayout()).apply {
                        background = null
                        border = JBUI.Borders.empty()
                        add(
                            JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                                background = null
                                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

                                add(previousChatsLabel)

                                val arrowButton =
                                    JButton(getArrowIcon(isExpanded, !isExpanded))
                                        .apply {
                                            isBorderPainted = false
                                            isContentAreaFilled = false
                                            isOpaque = false
                                            preferredSize = Dimension(16, 16).scaled
                                            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                            border = JBUI.Borders.empty(0, 24).scaled
                                            val actionListener =
                                                ActionListener {
                                                    isExpanded = !isExpanded
                                                    icon = getArrowIcon(isExpanded, !isExpanded)
                                                    previousChatsLabel.foreground =
                                                        if (isExpanded) UIUtil.getLabelForeground() else JBColor.GRAY
                                                    viewAllLabel.isVisible = isExpanded

                                                    // Toggle list visibility
                                                    val listComponent = recentChatsContainer.components.getOrNull(1)
                                                    listComponent?.isVisible = isExpanded

                                                    recentChatsContainer.revalidate()
                                                    recentChatsContainer.repaint()

                                                    // Load chat history in background when expanded
                                                    if (isExpanded) {
                                                        loadRecentChatsListAsync(recentChatsContainer)
                                                    }
                                                }
                                            addActionListener(actionListener)
                                            registerListener(this, actionListener)
                                        }.also { add(it) }

                                addMouseListener(
                                    MouseReleasedAdapter {
                                        isExpanded = !isExpanded
                                        arrowButton.icon = getArrowIcon(isExpanded, !isExpanded)
                                        previousChatsLabel.foreground =
                                            if (isExpanded) UIUtil.getLabelForeground() else JBColor.GRAY
                                        viewAllLabel.isVisible = isExpanded

                                        // Toggle list visibility
                                        val listComponent = recentChatsContainer.components.getOrNull(1)
                                        listComponent?.isVisible = isExpanded

                                        recentChatsContainer.revalidate()
                                        recentChatsContainer.repaint()

                                        // Load chat history in background when expanded
                                        if (isExpanded) {
                                            loadRecentChatsListAsync(recentChatsContainer)
                                        }
                                    },
                                )
                            },
                            BorderLayout.WEST,
                        )

                        add(viewAllLabel, BorderLayout.EAST)
                    },
                )

                // Create list model and list
                val listModel = DefaultListModel<HistoryListItem>()
                val chatsList =
                    JBList(listModel).apply {
                        cellRenderer = HistoryListCellRenderer(project, isInChatHistoryComponent = false)
                        visibleRowCount = 6
                        selectionMode = ListSelectionModel.SINGLE_SELECTION
                        border = JBUI.Borders.empty(8, 0)
                        isVisible = isExpanded

                        // Add mouse listeners for hover effects and selection
                        val motionListener =
                            MouseMotionMovedAdapter { e ->
                                val index = locationToIndex(e.point)
                                if (index != getClientProperty("hoveredIndex")) {
                                    putClientProperty("hoveredIndex", index)
                                    repaint()
                                }
                            }
                        addMouseMotionListener(motionListener)
                        registerListener(this, motionListener)

                        val mouseAdapter =
                            object : MouseAdapter() {
                                override fun mouseExited(e: MouseEvent) {
                                    putClientProperty("hoveredIndex", -1)
                                    repaint()
                                }

                                override fun mouseReleased(e: MouseEvent) {
                                    val index = locationToIndex(e.point)
                                    if (index >= 0) {
                                        val item = model.getElementAt(index) as HistoryListItem
                                        val bounds = getCellBounds(index, index)

                                        if (e.x > bounds.width - 40.scaled) {
                                            // Don't allow delete on recent chats items
                                            if (!item.isRecentChatsItem) {
                                                item.onDelete()
                                            }
                                            return
                                        }

                                        if (e.x > bounds.width - 80.scaled && e.x <= bounds.width - 40.scaled) {
                                            // Don't allow rename on recent chats items
                                            if (!item.isRecentChatsItem) {
                                                // Create overlay text field for editing
                                                showRenameOverlay(this@apply, item, bounds)
                                            }
                                            return
                                        }

                                        loadConversation(item.conversationId)
                                    }
                                }
                            }
                        addMouseListener(mouseAdapter)
                        registerListener(this, mouseAdapter)
                    }

                // Add the list to a scroll pane
                val listScrollPane =
                    JBScrollPane(chatsList).apply {
                        border = JBUI.Borders.empty()
                        isVisible = isExpanded
                        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                    }

                recentChatsContainer.add(listScrollPane)
                add(recentChatsContainer)

                // Initial load if expanded
                if (isExpanded) {
                    loadRecentChatsListAsync(recentChatsContainer)
                }

                revalidate()
                repaint()
            }

        return JBScrollPane(container)
            .apply {
                border = JBUI.Borders.empty(0, 12).scaled
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            }.also {
                recentChatsScrollPane = it
            }
    }

    private fun loadRecentChatsListAsync(container: JPanel) {
        // Get the list component
        val listScrollPane = container.components.getOrNull(1) as? JBScrollPane ?: return
        val list = listScrollPane.viewport.view as? JBList<*> ?: return
        val listModel = list.model as? DefaultListModel<HistoryListItem> ?: return

        // Clear the list
        listModel.clear()

        // Ensure visibility explicitly
        list.isVisible = true
        listScrollPane.isVisible = true

        // Load chats in background
        ApplicationManager.getApplication().executeOnPooledThread {
            val recentChats = ChatHistory.getInstance(project).getRecentConversations(6)
            val historyItems = mutableListOf<HistoryListItem>()

            recentChats.forEach { conversationId ->
                val preview =
                    ChatHistory.getInstance(project).getConversationName(conversationId)
                        ?: ChatHistory
                            .getInstance(project)
                            .getConversation(conversationId)
                            .firstOrNull { it.role == MessageRole.USER }
                            ?.content
                            ?.takeIf { it.isNotBlank() }
                        ?: SweepConstants.NEW_CHAT

                val timestamp = ChatHistory.getInstance(project).getTimestamp(conversationId) ?: 0L

                historyItems.add(
                    HistoryListItem(
                        conversationId = conversationId,
                        preview = preview,
                        timestamp = timestamp,
                        onDelete = {
                            ChatHistory.getInstance(project).deleteConversation(conversationId)
                            ApplicationManager.getApplication().invokeLater {
                                listModel.removeElement(
                                    listModel.elements().toList().find { it.conversationId == conversationId },
                                )
                            }
                        },
                        onRename = { newName ->
                            ChatHistory.getInstance(project).renameChatHistoryName(conversationId, newName)
                            ApplicationManager.getApplication().invokeLater {
                                // Update the item in the list model
                                val itemIndex =
                                    listModel.elements().toList().indexOfFirst { it.conversationId == conversationId }
                                if (itemIndex >= 0) {
                                    val updatedItem = listModel.getElementAt(itemIndex).copy(preview = newName)
                                    listModel.setElementAt(updatedItem, itemIndex)
                                }
                            }
                        },
                        isRecentChatsItem = true,
                    ),
                )
            }

            // Check for importable histories if current history is empty
            val importableHistories =
                if (recentChats.isEmpty()) {
                    ChatHistory.getInstance(project).detectImportableHistories()
                } else {
                    emptyList()
                }

            // Update UI on EDT with all items at once
            ApplicationManager.getApplication().invokeLater {
                // Add all items to model
                historyItems.forEach { listModel.addElement(it) }

                // If history is empty but we have importable histories, show import panel
                if (historyItems.isEmpty() && importableHistories.isNotEmpty()) {
                    showImportHistoryPanel(container, importableHistories)
                } else {
                    // Remove any existing import panel
                    removeImportHistoryPanel(container)
                }

                // Set fixed minimum size to ensure visibility
                list.minimumSize = Dimension(maxWidth, 100.scaled)
                list.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                list.invalidate()

                // Calculate proper sizes
                val innerH = (list.preferredSize.height).coerceAtMost(maxHeight)
                listScrollPane.preferredSize = Dimension(maxWidth, innerH)
                listScrollPane.minimumSize =
                    Dimension(
                        maxWidth,
                        if (historyItems.isEmpty()) 0 else 100.scaled,
                    )

                val headerH =
                    container.components
                        .getOrNull(0)
                        ?.preferredSize
                        ?.height ?: 30.scaled
                val importPanelH =
                    container.components
                        .find { it.name == IMPORT_PANEL_NAME }
                        ?.preferredSize
                        ?.height ?: 0
                val containerPadding = 24.scaled // 8px top + 16px bottom padding
                recentChatsScrollPane?.preferredSize = Dimension(maxWidth, headerH + innerH + importPanelH + containerPadding)

                // Apply layout changes through the component hierarchy
                list.revalidate()
                list.repaint()
                listScrollPane.revalidate()
                listScrollPane.repaint()
                container.revalidate()
                container.repaint()
                recentChatsScrollPane?.revalidate()
                recentChatsScrollPane?.repaint()

                // Show tooltip if needed
                val metaData = SweepMetaData.getInstance()
                if (metaData.chatsSent >= SweepConstants.CHAT_HISTORY_CHATS_SENT &&
                    metaData.chatHistoryUsed == 0 &&
                    !listModel.isEmpty &&
                    !metaData.chatHistoryBalloonWasShown
                ) {
                    showTooltipBalloon(list, list.height / 2)
                    metaData.chatHistoryBalloonWasShown = true
                }
            }
        }
    }

    private fun showImportHistoryPanel(
        container: JPanel,
        importableHistories: List<ChatHistory.ImportableHistory>,
    ) {
        // Remove existing import panel if any
        removeImportHistoryPanel(container)

        val importPanel =
            JPanel(BorderLayout()).apply {
                name = IMPORT_PANEL_NAME
                background = null
                border = JBUI.Borders.empty(8, 4, 0, 4).scaled

                val messagePanel =
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        background = null
                        border = JBUI.Borders.empty(8).scaled

                        add(
                            JLabel(
                                "<html><div style='width: 280px;'>" +
                                    "We detected chat history from a previous IDE version that you may want to import." +
                                    "</div></html>",
                            ).apply {
                                withSweepFont(project, scale = 0.9f)
                                foreground = JBColor.GRAY
                                alignmentX = Component.LEFT_ALIGNMENT
                            },
                        )

                        add(Box.createVerticalStrut(8.scaled))

                        add(
                            JButton("Import Chat History").apply {
                                withSweepFont(project, scale = 0.9f)
                                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                alignmentX = Component.LEFT_ALIGNMENT
                                addActionListener {
                                    showImportHistoryPopup(importableHistories, container)
                                }
                            },
                        )
                    }

                add(messagePanel, BorderLayout.CENTER)
            }

        // Add the import panel after the header (index 0) but before the list
        container.add(importPanel, 1)
        container.revalidate()
        container.repaint()
    }

    private fun removeImportHistoryPanel(container: JPanel) {
        container.components.find { it.name == IMPORT_PANEL_NAME }?.let {
            container.remove(it)
        }
    }

    private fun showImportHistoryPopup(
        importableHistories: List<ChatHistory.ImportableHistory>,
        parentContainer: JPanel,
    ) {
        var popup: JBPopup? = null
        val popupDisposable = Disposer.newDisposable("ImportHistoryPopup")

        val listModel = DefaultListModel<ChatHistory.ImportableHistory>()
        importableHistories.forEach { listModel.addElement(it) }

        val historyList =
            JBList(listModel).apply {
                cellRenderer =
                    object : DefaultListCellRenderer() {
                        override fun getListCellRendererComponent(
                            list: JList<*>?,
                            value: Any?,
                            index: Int,
                            isSelected: Boolean,
                            cellHasFocus: Boolean,
                        ): Component {
                            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                            val history = value as? ChatHistory.ImportableHistory
                            if (history != null) {
                                text =
                                    "<html><b>${history.ideVersion}</b> " +
                                    "<font color='gray'>(${history.conversationCount} conversations)</font></html>"
                                border = JBUI.Borders.empty(8, 12).scaled
                            }
                            return this
                        }
                    }
                selectionMode = ListSelectionModel.SINGLE_SELECTION
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

                val mouseAdapter =
                    object : MouseAdapter() {
                        override fun mouseReleased(e: MouseEvent) {
                            val index = locationToIndex(e.point)
                            if (index >= 0) {
                                val selectedHistory = model.getElementAt(index)
                                popup?.closeOk(null)
                                performImport(selectedHistory, parentContainer)
                            }
                        }
                    }
                addMouseListener(mouseAdapter)
                registerPopupListener(this, mouseAdapter, popupDisposable)
            }

        val contentPanel =
            JPanel(BorderLayout()).apply {
                add(
                    JLabel("Select IDE version to import from:").apply {
                        withSweepFont(project, bold = true)
                        border = JBUI.Borders.empty(12, 12, 8, 12).scaled
                    },
                    BorderLayout.NORTH,
                )
                add(
                    JBScrollPane(historyList).apply {
                        preferredSize = Dimension(300.scaled, 200.scaled)
                        border = JBUI.Borders.empty()
                    },
                    BorderLayout.CENTER,
                )
            }

        popup =
            JBPopupFactory
                .getInstance()
                .createComponentPopupBuilder(contentPanel, historyList)
                .setRequestFocus(true)
                .setFocusable(true)
                .setTitle("Import Chat History")
                .setMovable(true)
                .setResizable(false)
                .createPopup()

        Disposer.register(popup, popupDisposable)

        val sweepComponent = SweepComponent.getInstance(project)
        if (!sweepComponent.component.isShowing) {
            Disposer.dispose(popupDisposable)
            return
        }

        popup.showInCenterOf(sweepComponent.component)
    }

    private fun performImport(
        importableHistory: ChatHistory.ImportableHistory,
        parentContainer: JPanel,
    ) {
        // Show loading state
        ApplicationManager.getApplication().invokeLater {
            showNotification(
                project,
                "Importing Chat History",
                "Importing ${importableHistory.conversationCount} conversations from ${importableHistory.ideVersion}...",
                "Chat History Import",
            )
        }

        // Perform import in background
        ApplicationManager.getApplication().executeOnPooledThread {
            val importedCount = ChatHistory.getInstance(project).importFromHistory(importableHistory)

            ApplicationManager.getApplication().invokeLater {
                if (importedCount > 0) {
                    showNotification(
                        project,
                        "Import Successful",
                        "Successfully imported $importedCount conversations from ${importableHistory.ideVersion}.",
                        "Chat History Import",
                    )
                    // Refresh the recent chats list
                    refreshRecentChats()
                } else {
                    showNotification(
                        project,
                        "Import Failed",
                        "Failed to import chat history from ${importableHistory.ideVersion}. The conversations may already exist.",
                        "Chat History Import",
                    )
                }
            }
        }
    }

    private fun showRenameOverlay(
        list: JList<HistoryListItem>,
        item: HistoryListItem,
        cellBounds: Rectangle,
    ) {
        // Create overlay text field
        val textField = JTextField(item.preview.ifEmpty { SweepConstants.NEW_CHAT })
        activeRenameTextField = textField // Track the active rename text field
        activeRenameList = list // Track the parent list
        textField.apply {
            val timestampLabelTxt = getTimeAgo(item.timestamp)
            // we do this to respect the space needed for the timestampLabel, to prevent overlaps
            val overlayWidth = cellBounds.width - 80 - (timestampLabelTxt.length - 2) * 10

            setBounds(cellBounds.x + 4, cellBounds.y + 2, overlayWidth, cellBounds.height - 4)
            withSweepFont(project, scale = 0.9f)
            selectAll()

            // Handle Enter key to save
            addActionListener {
                val newName = text.trim()
                if (newName.isNotEmpty() && newName != item.preview) {
                    item.onRename(newName)
                }
                removeOverlay(list, this)
            }

            // Handle focus lost to cancel
            addFocusListener(
                object : FocusListener {
                    override fun focusGained(e: FocusEvent?) {}

                    override fun focusLost(e: FocusEvent?) {
                        removeOverlay(list, textField)
                    }
                },
            )
        }

        // Add overlay to list
        list.add(textField)
        textField.requestFocusInWindow()
        list.repaint()
    }

    private fun removeOverlay(
        list: JList<HistoryListItem>,
        textField: JTextField,
    ) {
        list.remove(textField)
        list.repaint()
        // Clear the active rename references
        if (activeRenameTextField == textField) {
            activeRenameTextField = null
            activeRenameList = null
        }
    }

    override fun dispose() {
        // Clean up references
        recentChatsScrollPane = null
        activeRenameTextField = null
        activeRenameList = null
        // All listeners are automatically cleaned up by the Disposer system
    }
}
