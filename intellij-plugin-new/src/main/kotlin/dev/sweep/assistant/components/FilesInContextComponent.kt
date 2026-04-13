package dev.sweep.assistant.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.messages.Topic
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.sweep.assistant.controllers.CurrentFileInContextManager
import dev.sweep.assistant.controllers.FileAutocomplete
import dev.sweep.assistant.controllers.FocusChatController
import dev.sweep.assistant.controllers.ImageManager
import dev.sweep.assistant.data.FileInfo
import dev.sweep.assistant.data.SelectedSnippet
import dev.sweep.assistant.data.Snippet
import dev.sweep.assistant.entities.EntitiesCache
import dev.sweep.assistant.listener.FileChangedAction
import dev.sweep.assistant.services.SweepNonProjectFilesService
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.theme.SweepIcons.brighter
import dev.sweep.assistant.theme.SweepIcons.darker
import dev.sweep.assistant.theme.SweepIcons.scale
import dev.sweep.assistant.utils.*
import dev.sweep.assistant.views.*
import java.awt.*
import java.awt.event.ActionListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseListener
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.*
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager

/**
 * Holds the saved state of files in context for a session.
 * Used to save/restore state when switching between tabs/sessions.
 */
data class FilesInContextState(
    val includedFiles: Map<String, String> = emptyMap(),
    val includedSnippets: Map<String, String> = emptyMap(), // SelectedSnippet.denotation -> path
    val includedImages: List<dev.sweep.assistant.data.Image> = emptyList(),
    val includedGeneralTextSnippets: List<FileInfo> = emptyList(),
)

class FilesInContextComponent(
    private val project: Project,
    private val textComponent: RoundedTextArea,
    val focusChatController: FocusChatController,
    private val embeddedFilePanel: EmbeddedFilePanel, // Add parameter for EmbeddedFilePanel
    private val onEmbeddedFilePanelStateChanged: (() -> Unit)? = null, // Callback for when embedded file panel state changes
) : Darkenable,
    Disposable {
    var dontAutoOpenEmbeddedFile: Boolean = false
    var doNotShowCurrentFileInContext: Boolean = false
    var isAttachedToUserMessageComponent: Boolean = false
    var autoIncludeOpenFileInAutocomplete: Boolean = true

    private var maxVisibleFiles = 1
    private var isExpanded = false
    private var resizeListener: ComponentAdapter? = null

    // List to store all file panels for managing visibility
    private val allFilePanels = mutableListOf<JPanel>()

    private var tooltipBalloon: Balloon? = null
    private var newChatTooltipBalloon: Balloon? = null
    private var imagePopupBalloon: JBPopup? = null
    private var imagePopupBrowser: JBCefBrowser? = null
    private var imagePopupScrollListener: javax.swing.event.ChangeListener? = null
    private var imagePopupDisposalTime: Long = 0

    private fun showTooltipBalloon() {
        tooltipBalloon?.dispose()

        SweepMetaData.getInstance().hasShownClickToAddFilesBalloon = true

        tooltipBalloon =
            JBPopupFactory
                .getInstance()
                .createHtmlTextBalloonBuilder(
                    "<div style='padding: 4px; width: 220px; word-wrap: break-word;'><b>💡 Click to add files to context or type @</b></div>",
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

        val plusButton =
            panel.components.firstOrNull { it is RoundedPanel }?.let {
                (it as RoundedPanel).components.firstOrNull { comp -> comp is JButton && comp.name == "plusButton" }
            } ?: return

        val point = Point(plusButton.width / 2, -8)
        val relativePoint = RelativePoint(plusButton, point)
        if (TooltipManager.getInstance(project).showTooltip(tooltipBalloon!!)) {
            tooltipBalloon?.show(relativePoint, Balloon.Position.above)

            // Use a timer to automatically hide the balloon after a delay
            Timer(5000) {
                // 5000ms = 5 seconds
                ApplicationManager.getApplication().invokeLater {
                    tooltipBalloon?.hide()
                    tooltipBalloon?.dispose()
                }
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    fun showNewChatTooltipBalloon() {
        SweepMetaData.getInstance().hasShownNewChatBalloon = true
        newChatTooltipBalloon?.dispose()

        newChatTooltipBalloon =
            JBPopupFactory
                .getInstance()
                .createHtmlTextBalloonBuilder(
                    "<div style='padding: 4px; width: 260px; word-wrap: break-word;'><b>💬 Click to start a new chat or press ${SweepConstants.META_KEY}N</b></div>",
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

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)
        val toolWindowComponent = toolWindow?.component ?: return
        // Position at the top of the tool window, where the + button would be
        val point = Point(toolWindowComponent.width - 190, 0)
        val relativePoint = RelativePoint(toolWindowComponent, point)

        if (TooltipManager.getInstance(project).showTooltip(newChatTooltipBalloon!!)) {
            newChatTooltipBalloon?.show(relativePoint, Balloon.Position.below)

            // Use a timer to automatically hide the balloon after a delay
            Timer(5000) {
                // 5000ms = 5 seconds
                ApplicationManager.getApplication().invokeLater {
                    newChatTooltipBalloon?.hide()
                    newChatTooltipBalloon?.dispose()
                }
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    companion object {
        fun create(
            project: Project,
            textArea: RoundedTextArea,
            embeddedFilePanel: EmbeddedFilePanel, // Add parameter for EmbeddedFilePanel
            focusChatController: FocusChatController = FocusChatController(project, textArea),
            onEmbeddedFilePanelStateChanged: (() -> Unit)? = null,
        ): FilesInContextComponent {
            val component =
                FilesInContextComponent(project, textArea, focusChatController, embeddedFilePanel, onEmbeddedFilePanelStateChanged)
            Disposer.register(textArea, component)
            return component
        }

        val SUGGESTED_SNIPPET_TOPIC =
            Topic.create(
                "Suggested General Text Snippet Changes",
                SuggestedGeneralTextSnippetListener::class.java,
            )
    }

    interface SuggestedGeneralTextSnippetListener {
        fun onSuggestedGeneralTextSnippetChanged(hasSnippets: Boolean)
    }

    private val currentFileInContextManager = CurrentFileInContextManager(project, this)
    var fileAutocomplete =
        FileAutocomplete
            .create(
                project,
                textComponent,
                this,
                currentFileInContextManager,
            )

    // Fixes stale fileAutocomplete service state, after theme switch
    fun recreateFileAutocomplete() {
        fileAutocomplete.dispose()

        fileAutocomplete =
            FileAutocomplete
                .create(
                    project,
                    textComponent,
                    this,
                    currentFileInContextManager,
                )

        // Re-wire callbacks
        fileAutocomplete.setOnMentionsChanged { files, onClose ->
            updateAutoCompletedMentions(files, onClose)
        }
    }

    val imageManager = ImageManager(project, this)

    private val listenersToRemove = mutableListOf<Pair<Component, EventListener>>()

    private fun registerListener(
        component: Component,
        listener: EventListener,
    ) {
        listenersToRemove.add(component to listener)
    }

    private var panel =
        JPanel(WrappedFlowLayout(4, 4)).apply {
            border = JBUI.Borders.emptyBottom(6)
            background = null
            add(createOpenAutoCompleteButton())
            val mouseListener =
                MouseReleasedAdapter {
                    textComponent.requestFocus()
                }
            addMouseListener(mouseListener)
            registerListener(this, mouseListener)
        }
    private var fileInEmbeddedFilePanel: String? = null

    val component: JPanel
        get() = panel

    val includedSnippets
        get() = focusChatController.getFocusedFiles()

    val includedFiles
        get() =
            fileAutocomplete.getFilesMap().also {
                if (autoIncludeOpenFileInAutocomplete) {
                    currentFileInContextManager.takeIf { cf -> cf.name != null }?.let { cf ->
                        it[cf.name!!] = cf.relativePath!!
                    }
                }
            }

    fun getIncludedFiles(includeCurrentOpenFile: Boolean = true): Map<String, String> =
        fileAutocomplete.getFilesMap().also {
            if (includeCurrentOpenFile && autoIncludeOpenFileInAutocomplete) {
                currentFileInContextManager.takeIf { cf -> cf.name != null }?.let { cf ->
                    it[cf.name!!] = cf.relativePath!!
                }
            }
        }

    var includedGeneralTextSnippets: MutableList<FileInfo> = mutableListOf()

    val currentOpenFile
        get() = currentFileInContextManager.relativePath

    init {
        currentFileInContextManager.setOnFileChanged { file, onClose ->
            changeCurrentFile(file, onClose)
        }
        fileAutocomplete.setOnMentionsChanged { files, onClose ->
            updateAutoCompletedMentions(files, onClose)
        }
        // Use the per‑instance FocusChatController.
        focusChatController.setListener { snippets, onClose ->
            updateSelectedSnippets(snippets, onClose)
        }
        imageManager.setOnImagesChanged { images, onClose ->
            updateImages(images, onClose)
        }

        // Add file change listener to detect navigation
        currentFileInContextManager.fileChangeListener.addOnFileChangedAction(
            FileChangedAction(
                identifier = "FilesInContextComponentFileChangeListener",
                onFileChanged = { _, oldFile ->
                    if (fileInEmbeddedFilePanel == oldFile?.path?.split('/')?.lastOrNull()) {
                        fileInEmbeddedFilePanel = null
                        updateColors()
                    }
                },
            ),
        )

        // Set up resize listener for dynamic maxVisibleFiles calculation
        setupResizeListener()
        updateMaxVisibleFiles()
    }

    private fun setupResizeListener() {
        // Use the tool window component directly for resize events
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)
        val toolWindowComponent = toolWindow?.component

        if (toolWindowComponent != null && resizeListener == null) {
            resizeListener =
                object : ComponentAdapter() {
                    override fun componentResized(e: ComponentEvent?) {
                        updateMaxVisibleFiles()
                    }
                }
            toolWindowComponent.addComponentListener(resizeListener)
        }
    }

    private fun updateMaxVisibleFiles() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)
        val availableWidth = toolWindow?.component?.width ?: return

        val estimatedPanelWidth = 80 // this number works
        val addFilesButtonWidth = 80
        val padding = 20 // Safety

        val availableWidthForFiles = availableWidth - addFilesButtonWidth - padding
        val calculatedMaxFiles = (availableWidthForFiles / estimatedPanelWidth).coerceAtLeast(1)

        if (calculatedMaxFiles != maxVisibleFiles) {
            maxVisibleFiles = calculatedMaxFiles
            updatePanelVisibility()
        }

        // Also update truncation when size changes
        updateFilePillsTruncation()
    }

    private fun getMaxFileNameWidth(): Int {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)
        val availableWidth = toolWindow?.component?.width ?: return JBUI.scale(200)

        val addFilesButtonWidth = 80
        val padding = 20 // Safety
        val horizontalGap = 4 // Gap between pills in WrappedFlowLayout

        // Calculate available width for all file pills
        val availableWidthForFiles = availableWidth - addFilesButtonWidth - padding

        // If we have many files, each pill should be smaller
        // If we have few files, each pill can be larger
        val visibleFileCount = minOf(allFilePanels.size, maxVisibleFiles).coerceAtLeast(1)

        // Calculate width per file pill, accounting for gaps between them
        val totalGapWidth = (visibleFileCount - 1) * horizontalGap
        val widthPerFile = (availableWidthForFiles - totalGapWidth) / visibleFileCount

        // Account for close button width (22) and padding
        val closeButtonWidth = 22
        val pillPadding = 8

        val maxTextWidth = widthPerFile - closeButtonWidth - pillPadding

        // Ensure minimum width for readability
        return maxTextWidth.coerceIn(JBUI.scale(50), JBUI.scale(300))
    }

    private fun updateFilePillsTruncation() {
        val maxFileNameWidth = getMaxFileNameWidth()

        allFilePanels.forEach { filePanel ->
            val fileNameButton = filePanel.components.firstOrNull { it is JButton } as? JButton
            fileNameButton?.let { button ->
                val fullFileName = button.toolTipText
                if (fullFileName != null && fullFileName.isNotEmpty()) {
                    val fontMetrics = button.getFontMetrics(button.font)
                    val displayFileName = calculateTruncatedText(fullFileName, maxFileNameWidth, fontMetrics)
                    button.text = displayFileName

                    // Update the button's preferred size to match the new truncated text
                    val textWidth = fontMetrics.stringWidth(displayFileName)
                    val iconWidth = button.icon?.iconWidth ?: 0
                    val iconTextGap = if (button.icon != null) 4 else 0
                    val closeButtonWidth = 22
                    val pillPadding = 8

                    val totalWidth = textWidth + iconWidth + iconTextGap + closeButtonWidth + pillPadding
                    button.preferredSize = Dimension(totalWidth, 22).scaled

                    // Update the outer panel's preferred size to match the total content width
                    val actionButton = filePanel.components.find { it is JButton && it != button } as? JButton
                    val actionButtonWidth = actionButton?.preferredSize?.width ?: 22
                    val totalPanelWidth = totalWidth + actionButtonWidth
                    filePanel.preferredSize = Dimension(totalPanelWidth, 22).scaled
                }
            }
        }
    }

    private fun createMoreButton(hiddenCount: Int): JPanel =
        RoundedPanel(FlowLayout(FlowLayout.CENTER, 0, 0), this).apply {
            background = null
            borderColor = SweepColors.activeBorderColor
            margin = JBUI.emptyInsets()

            JButton(if (isExpanded) "Show less" else "+ $hiddenCount more")
                .apply {
                    name = "moreButton"
                    background = null
                    isBorderPainted = false
                    isFocusPainted = false
                    isContentAreaFilled = false
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    withSweepFont(project, scale = 0.9f)
                    foreground = JBColor.BLUE

                    // Set tight margins for the button text
                    margin = JBUI.emptyInsets()
                    border = JBUI.Borders.empty()

                    val actionListener =
                        ActionListener {
                            isExpanded = !isExpanded
                            updatePanelVisibility()
                        }
                    addActionListener(actionListener)
                    registerListener(this, actionListener)
                    preferredSize = Dimension(preferredSize.width, 22).scaled // This creates a new Dimension and sets it
                }.also { add(it) }
        }

    private fun updatePanelVisibility() {
        // Check for duplicates and remove them based on priority
        val fileNames =
            allFilePanels.mapNotNull { panel ->
                (panel.components.firstOrNull() as? JButton)?.text
            }
        val duplicates = fileNames.groupBy { it }.filter { it.value.size > 1 }.keys

        // Keep track of promoted filenames
        val promotedFileNames = mutableSetOf<String>()

        for (duplicateName in duplicates) {
            val panelsWithName =
                allFilePanels.filter { panel ->
                    (panel.components.firstOrNull() as? JButton)?.text == duplicateName
                }

            // Find opened file panel if it exists
            val openedFilePanel = panelsWithName.find { it.identifierEquals("openedFile") }

            if (openedFilePanel != null) {
                // If there's an opened file panel, remove it and track the filename
                allFilePanels.remove(openedFilePanel)
                promotedFileNames.add(duplicateName)
            } else {
                // Original priority-based removal for other cases
                val panelToRemove =
                    panelsWithName.find { it.identifierEquals("autocompletedMention") }
                        ?: panelsWithName.find { it.identifierEquals("snippets") }
                panelToRemove?.let { allFilePanels.remove(it) }
            }
        }

        // Ensure UI updates happen on EDT
        val updateUI: () -> Unit = {
            panel.apply {
                removeAll()
                add(createOpenAutoCompleteButton())
                val filePanelsCopy = ArrayList(allFilePanels)

                // Sort panels by type and promoted status
                val sortedPanels =
                    filePanelsCopy.sortedWith(
                        compareBy { panel ->
                            val fileName = (panel.components.firstOrNull() as? JButton)?.text
                            when {
                                fileName in promotedFileNames -> -1 // Highest priority for promoted files
                                panel.identifierEquals("openedFile") -> 0
                                panel.identifierEquals("snippets") -> 1
                                panel.identifierEquals("autocompletedMention") -> 2
                                else -> 3
                            }
                        },
                    )

                if (sortedPanels.size <= maxVisibleFiles) {
                    sortedPanels.forEach { add(it) }
                } else if (isExpanded) {
                    sortedPanels.forEach { add(it) }
                    add(createMoreButton(0))
                } else {
                    sortedPanels.take(maxVisibleFiles).forEach { add(it) }
                    add(createMoreButton(sortedPanels.size - maxVisibleFiles))
                }

                updateColors()
                revalidate()
                repaint()
            }
            Unit
        }

        if (ApplicationManager.getApplication().isDispatchThread) {
            updateUI()
        } else {
            ApplicationManager.getApplication().invokeLater(updateUI)
        }
    }

    private fun addFilePanel(filePanel: JPanel) {
        allFilePanels.add(filePanel)
        // Trigger initial calculation if this is the first time we have panels and resize listener is set up
        updateMaxVisibleFiles()
        updatePanelVisibility()
    }

    fun isNew(): Boolean =
        currentFileInContextManager.isNew() &&
            fileAutocomplete.isNew() &&
            imageManager.isNew() &&
            fileInEmbeddedFilePanel == null

    fun addSuggestedFile(path: String) {
        fileAutocomplete.addSuggestedFile(path)
    }

    private fun updateColors() {
        panel.components?.forEach {
            if (it is JButton) return@forEach
            ((it as JPanel).components.firstOrNull() as JButton).apply {
                if (it.identifierNotEquals("suggestedFile")) {
                    foreground =
                        if (fileInEmbeddedFilePanel == text) {
                            JBColor.BLUE
                        } else {
                            JBColor.BLACK
                        }
                }
            }
        }
    }

    private fun createThumbnailIcon(
        base64Data: String,
        size: Int = 16,
    ): ImageIcon? =
        try {
            val imageBytes = Base64.getDecoder().decode(base64Data)
            val originalImage = ImageIO.read(ByteArrayInputStream(imageBytes))

            if (originalImage != null) {
                val scaledImage = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
                val g2d = scaledImage.createGraphics()
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.drawImage(originalImage, 0, 0, size, size, null)
                g2d.dispose()

                ImageIcon(scaledImage)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }

    private fun showImagePopup(
        base64Data: String,
        sourceComponent: Component,
    ) {
        // Close existing popup if open
        imagePopupBalloon?.dispose()

        try {
            // Check if JCEF is supported, fallback to Swing if not
            if (!JBCefApp.isSupported()) {
                showSwingImagePopup(base64Data, sourceComponent)
                return
            }

            // Calculate optimal popup dimensions based on image aspect ratio
            val imageBytes = Base64.getDecoder().decode(base64Data)
            val originalImage = ImageIO.read(ByteArrayInputStream(imageBytes))
            val popupDimensions = calculateOptimalPopupSize(originalImage)

            // Create HTML content with proper high-DPI CSS styling
            val htmlContent = createImageHtml(base64Data, popupDimensions.first, popupDimensions.second)

            // Create JCEF browser for crisp image rendering
            val browser =
                JBCefBrowser()
                    .apply {
                        loadHTML(htmlContent)
                        component.preferredSize = Dimension(JBUI.scale(popupDimensions.first + 12), JBUI.scale(popupDimensions.second + 12)) // Add padding
                    }.also {
                        imagePopupBrowser = it
                    }

            imagePopupBalloon =
                JBPopupFactory
                    .getInstance()
                    .createComponentPopupBuilder(browser.component, null)
                    .setResizable(false)
                    .setMovable(false)
                    .setRequestFocus(false)
                    .setCancelCallback {
                        // Dispose browser when popup closes
                        browser.dispose()

                        // Clicking off the popup disposes it but if you click on the image file panel it will dispose and then reopen.
                        // Kinda hacky solution is to not reopen the popup if closed occurred recently
                        imagePopupDisposalTime = System.currentTimeMillis()

                        // Clean up scroll listener when popup is dismissed
                        imagePopupScrollListener?.let { listener ->
                            findScrollableAncestor(sourceComponent)?.viewport?.removeChangeListener(listener)
                        }
                        imagePopupScrollListener = null
                        // Reset state when popup is dismissed
                        fileInEmbeddedFilePanel = null
                        updateColors()

                        true // Allow the popup to close
                    }.createPopup()

            // Function to calculate popup position relative to source component
            fun calculatePopupPosition(): RelativePoint {
                val point = Point(sourceComponent.width / 2 - JBUI.scale(110), sourceComponent.height + 5)
                return RelativePoint(sourceComponent, point)
            }

            // Function to check if source component is visible in viewport
            fun isSourceComponentVisible(): Boolean {
                val scrollPane = findScrollableAncestor(sourceComponent) ?: return true
                val viewport = scrollPane.viewport
                val viewportBounds = viewport.viewRect
                val sourceBounds =
                    SwingUtilities.convertRectangle(
                        sourceComponent.parent,
                        sourceComponent.bounds,
                        viewport.view,
                    )
                return viewportBounds.intersects(sourceBounds)
            }

            // Show popup initially
            imagePopupBalloon?.show(calculatePopupPosition())

            // Find scrollable ancestor and add viewport listener
            val scrollPane = findScrollableAncestor(sourceComponent)
            if (scrollPane != null) {
                imagePopupScrollListener =
                    javax.swing.event.ChangeListener {
                        ApplicationManager.getApplication().invokeLater {
                            if (imagePopupBalloon?.isVisible == true) {
                                if (isSourceComponentVisible()) {
                                    // Update popup position to follow the source component
                                    imagePopupBalloon?.setLocation(calculatePopupPosition().screenPoint)
                                } else {
                                    // Hide popup if source component is off-screen
                                    imagePopupBalloon?.dispose()
                                    fileInEmbeddedFilePanel = null
                                    updateColors()
                                }
                            }
                        }
                    }
                scrollPane.viewport.addChangeListener(imagePopupScrollListener)
            }
        } catch (e: Exception) {
            // Show error notification if image can't be displayed
            showNotification(project, "Image Error", "Failed to display image: ${e.message}")
        }
    }

    private fun calculateOptimalPopupSize(image: BufferedImage?): Pair<Int, Int> {
        if (image == null) {
            return Pair(400, 280) // Default fallback size
        }

        val maxWidth = 400
        val maxHeight = 300
        val minWidth = 200
        val minHeight = 150

        val imageWidth = image.width
        val imageHeight = image.height
        val aspectRatio = imageWidth.toDouble() / imageHeight.toDouble()

        // Calculate dimensions that maintain aspect ratio while fitting within bounds
        var popupWidth = imageWidth
        var popupHeight = imageHeight

        // Scale down if too large
        if (popupWidth > maxWidth || popupHeight > maxHeight) {
            val scaleX = maxWidth.toDouble() / popupWidth
            val scaleY = maxHeight.toDouble() / popupHeight
            val scale = minOf(scaleX, scaleY)

            popupWidth = (popupWidth * scale).toInt()
            popupHeight = (popupHeight * scale).toInt()
        }

        // Scale up if too small (but maintain aspect ratio)
        if (popupWidth < minWidth && popupHeight < minHeight) {
            val scaleX = minWidth.toDouble() / popupWidth
            val scaleY = minHeight.toDouble() / popupHeight
            val scale = minOf(scaleX, scaleY)

            popupWidth = (popupWidth * scale).toInt()
            popupHeight = (popupHeight * scale).toInt()
        }

        return Pair(popupWidth, popupHeight)
    }

    private fun createImageHtml(
        base64Data: String,
        maxWidth: Int,
        maxHeight: Int,
    ): String =
        """
        <!DOCTYPE html>
        <html>
        <head>
            <style>
                html, body {
                    margin: 0;
                    padding: 6px;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    height: 100vh;
                    width: 100vw;
                    background: transparent;
                    overflow: hidden;
                    box-sizing: border-box;
                }
                img {
                    max-width: ${maxWidth}px;
                    max-height: ${maxHeight}px;
                    width: auto;
                    height: auto;
                    object-fit: contain;
                    image-rendering: -webkit-optimize-contrast;
                    image-rendering: crisp-edges;
                    border-radius: 6px;
                    box-shadow: 0 4px 12px rgba(0,0,0,0.2);
                    display: block;
                }
            </style>
        </head>
        <body>
            <img src="data:image/png;base64,$base64Data" alt="Image preview" />
        </body>
        </html>
        """.trimIndent()

    private fun showSwingImagePopup(
        base64Data: String,
        sourceComponent: Component,
    ) {
        // Fallback to original Swing implementation if JCEF is not supported
        val imageBytes = Base64.getDecoder().decode(base64Data)
        val originalImage = ImageIO.read(ByteArrayInputStream(imageBytes))

        if (originalImage != null) {
            val maxWidth = JBUI.scale(200)
            val maxHeight = JBUI.scale(150)
            val scaledImage = scaleImageToFit(originalImage, maxWidth, maxHeight)

            val imageLabel =
                javax.swing.JLabel(ImageIcon(scaledImage)).apply {
                    horizontalAlignment = javax.swing.JLabel.CENTER
                    verticalAlignment = javax.swing.JLabel.CENTER
                    border = JBUI.Borders.empty(8)
                }

            imagePopupBalloon =
                JBPopupFactory
                    .getInstance()
                    .createComponentPopupBuilder(imageLabel, null)
                    .setResizable(false)
                    .setMovable(false)
                    .setRequestFocus(false)
                    .setCancelCallback {
                        imagePopupDisposalTime = System.currentTimeMillis()
                        imagePopupScrollListener?.let { listener ->
                            findScrollableAncestor(sourceComponent)?.viewport?.removeChangeListener(listener)
                        }
                        imagePopupScrollListener = null
                        fileInEmbeddedFilePanel = null
                        updateColors()
                        true
                    }.createPopup()

            val point = Point(sourceComponent.width / 2 - scaledImage.width / 2, sourceComponent.height + 5)
            imagePopupBalloon?.show(RelativePoint(sourceComponent, point))
        }
    }

    private fun scaleImageToFit(
        originalImage: BufferedImage,
        maxWidth: Int,
        maxHeight: Int,
    ): BufferedImage {
        val originalWidth = originalImage.width
        val originalHeight = originalImage.height

        // Calculate scaling factor to fit within bounds while maintaining aspect ratio
        val scaleX = maxWidth.toDouble() / originalWidth
        val scaleY = maxHeight.toDouble() / originalHeight
        val scale = minOf(scaleX, scaleY, 1.0) // Don't scale up, only down

        val newWidth = (originalWidth * scale).toInt()
        val newHeight = (originalHeight * scale).toInt()

        // Create DPI-aware image using IntelliJ's UIUtil for proper high-DPI support
        val scaledImage = UIUtil.createImage(null, newWidth, newHeight, BufferedImage.TYPE_INT_ARGB)
        val g2d = scaledImage.createGraphics()

        // Enhanced rendering hints for high-DPI displays
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
        g2d.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        g2d.drawImage(originalImage, 0, 0, newWidth, newHeight, null)
        g2d.dispose()

        return scaledImage
    }

    // Helper to find the scrollable ancestor (JScrollPane) of a component
    private fun findScrollableAncestor(component: Component): JBScrollPane? {
        var parent = component.parent
        while (parent != null) {
            if (parent is JBScrollPane) {
                return parent
            }
            parent = parent.parent
        }
        return null
    }

    // Helper to recursively process components.
    private fun forEachComponent(
        comp: Component,
        action: (Component) -> Unit,
    ) {
        action(comp)
        if (comp is Container) {
            comp.components.forEach { child ->
                forEachComponent(child, action)
            }
        }
    }

    private fun editorNavigation(path: String) {
        // Navigate to the file in the editor, supporting non-project files
        val nonProjectService = SweepNonProjectFilesService.getInstance(project)
        val isNonProjectFile = nonProjectService.isAllowedFile(path)
        val relativePath = if (isNonProjectFile) path else relativePath(project, path)
        if (relativePath != null) {
            // Run openFileInEditor on a background thread to avoid slow operation on EDT
            ApplicationManager.getApplication().executeOnPooledThread {
                openFileInEditor(project, relativePath)
            }
        }
    }

    private fun createFilePanel(
        file: String,
        path: String,
        fileType: String,
        onClose: ((String) -> Unit)?,
        showLatest: Boolean = true,
        imageBase64: String? = null,
        overrideAutoOpenEmbeddedFile: Boolean = false,
    ): JPanel {
        fun minimize() {
            fileInEmbeddedFilePanel = null
            updateColors()
            embeddedFilePanel.showFile(null)
            onEmbeddedFilePanelStateChanged?.invoke()
        }

        fun close() {
            if (fileInEmbeddedFilePanel == file) {
                fileInEmbeddedFilePanel = null
                embeddedFilePanel.showFile(null)
                updateColors()
            }
            onClose?.invoke(file)
            onEmbeddedFilePanelStateChanged?.invoke()
        }

        // Create the fileName button.
        val fileNameButton =
            JButton(file).apply {
                name = "fileNameButton" // for darkening purposes
                toolTipText = file // Show full file name in tooltip

                // Add thumbnail icon for image panels
                if (fileType == "image" && imageBase64 != null) {
                    icon = createThumbnailIcon(imageBase64, 16)
                    horizontalAlignment = SwingConstants.LEFT
                    iconTextGap = 4
                    margin = JBUI.insets(0, 6, 0, 0)
                }

                val actionListener =
                    ActionListener {
                        if (fileInEmbeddedFilePanel == file) {
                            minimize()
                        } else {
                            if (fileType == "snippets") {
                                fileInEmbeddedFilePanel = file
                                updateColors()
                                if (file != "Selection") {
                                    val selectedSnippet = SelectedSnippet.fromDenotation(file)
                                    embeddedFilePanel.showFile(
                                        path,
                                        { minimize() },
                                        { close() },
                                        selectedSnippet.second - 1,
                                        selectedSnippet.third - 1,
                                    )
                                    onEmbeddedFilePanelStateChanged?.invoke()
                                    editorNavigation(path)
                                }
                            } else if (fileType == "suggestedFile") {
                                fileAutocomplete.addSuggestedFile(path)
                            } else if (fileType.startsWith(SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX)) {
                                // Find the matching entry in includedGeneralTextSnippets
                                val fileInfoToAdd = includedGeneralTextSnippets.find { it.relativePath == path }
                                var newFileName = fileType.substring(SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX.length)
                                newFileName = "${SweepConstants.GENERAL_TEXT_SNIPPET_PREFIX}$newFileName"
                                if (fileInfoToAdd != null) {
                                    val updatedFileInfo =
                                        FileInfo(
                                            newFileName,
                                            fileInfoToAdd.relativePath,
                                            span = null,
                                            codeSnippet = fileInfoToAdd.codeSnippet,
                                        )

                                    includedGeneralTextSnippets.remove(fileInfoToAdd)
                                    includedGeneralTextSnippets.add(updatedFileInfo)

                                    // Update the UI to reflect the changes
                                    updateIncludedGeneralTextSnippets { close() }
                                }
                            } else if (fileType.startsWith(SweepConstants.GENERAL_TEXT_SNIPPET_PREFIX)) {
                                fileInEmbeddedFilePanel = file
                                updateColors()
                                embeddedFilePanel.showFile(
                                    path,
                                    { minimize() },
                                    { close() },
                                    isEditable = true, // General text snippets should be editable
                                )
                                onEmbeddedFilePanelStateChanged?.invoke()
                            } else if (fileType == "image") {
                                if (imageBase64 != null) {
                                    // Check if this image is currently selected and popup is visible
                                    val isCurrentlySelected = fileInEmbeddedFilePanel == file
                                    val isPopupVisible = imagePopupBalloon?.isVisible() == true

                                    if (isCurrentlySelected && isPopupVisible) {
                                        // Close popup if clicking the same image that's already open
                                        imagePopupBalloon?.dispose()
                                        fileInEmbeddedFilePanel = null
                                        updateColors()
                                    } else {
                                        // Check if popup was recently disposed (within 10ms)
                                        val currentTime = System.currentTimeMillis()
                                        val timeSinceDisposal = currentTime - imagePopupDisposalTime

                                        if (timeSinceDisposal > 50) {
                                            // Close any existing popup and show new one
                                            imagePopupBalloon?.dispose()
                                            fileInEmbeddedFilePanel = file
                                            updateColors()
                                            showImagePopup(imageBase64, this@apply)
                                        }
                                    }
                                }
                            } else if (fileType == "directory") {
                                fileAutocomplete.addSuggestedFile(path)
                            } else {
                                if (entityNameFromPathString(path).isNotEmpty()) {
                                    updateColors()
                                    val (pathString, entityName) = path.split("::")
                                    val entity =
                                        EntitiesCache.getInstance(project).findEntity(pathString, entityName) ?: return@ActionListener
                                    if (entity.endLine - entity.startLine <= 100) {
                                        embeddedFilePanel.showFile(
                                            pathString,
                                            { minimize() },
                                            { close() },
                                            entity.startLine - 1,
                                            entity.endLine - 1,
                                        )
                                        onEmbeddedFilePanelStateChanged?.invoke()
                                    }
                                }

                                editorNavigation(path.split("::")[0])
                            }
                        }
                    }
                addActionListener(actionListener)
                registerListener(this, actionListener)

                border = JBUI.Borders.empty()
                if (fileType == "suggestedFile") {
                    foreground = JBColor.GRAY
                    toolTipText = "Recently Used File"
                } else if (fileType.startsWith(SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX)) {
                    foreground = JBColor.GRAY
                    toolTipText = "Suggested Context From Recent Actions"
                } else {
                    foreground = JBColor.BLACK
                }
                // Save the original foreground color.
                putClientProperty("originalForeground", foreground)
                isBorderPainted = false
                isFocusPainted = false
                isContentAreaFilled = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                withSweepFont(project, scale = 0.9f)

                // Truncate file name if it's too long after font is set
                val maxFileNameWidth = getMaxFileNameWidth()
                val fontMetrics = getFontMetrics(font)
                val displayFileName = calculateTruncatedText(file, maxFileNameWidth, fontMetrics)
                text = displayFileName

                // Calculate the actual width needed for the truncated text
                val textWidth = fontMetrics.stringWidth(displayFileName)
                val iconWidth = icon?.iconWidth ?: 0
                val iconTextGap = if (icon != null) 4 else 0
                val closeButtonWidth = 22
                val pillPadding = 8

                // Set the preferred size to match the actual content width
                val totalWidth = textWidth + iconWidth + iconTextGap + closeButtonWidth + pillPadding
                preferredSize = Dimension(totalWidth, 22).scaled
            }

        val closeButton =
            JButton(AllIcons.Actions.Close).apply {
                preferredSize = Dimension(22, 22).scaled
                val actionListener = ActionListener { close() }
                addActionListener(actionListener)
                registerListener(this, actionListener)
                border = JBUI.Borders.empty()
                isOpaque = false
                isBorderPainted = false
                isFocusPainted = false
                isContentAreaFilled = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                margin = JBUI.emptyInsets()
            }

        val addButton =
            JButton(
                AllIcons.General.Add
                    .scale(10f)
                    .darker(3),
            ).apply {
                preferredSize = Dimension(22, 22).scaled
                val actionListener = ActionListener { fileAutocomplete.addSuggestedFile(path) }
                addActionListener(actionListener)
                registerListener(this, actionListener)
                border = JBUI.Borders.empty()
                toolTipText =
                    if (fileType.startsWith(SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX)) {
                        "Suggested Context From Recent Actions"
                    } else {
                        "Recently Used File"
                    }
                isOpaque = false
                isBorderPainted = false
                isFocusPainted = false
                isContentAreaFilled = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                margin = JBUI.emptyInsets()
            }

        return RoundedPanel(FlowLayout(FlowLayout.LEFT, 0, 0), this@FilesInContextComponent).apply {
            background = null
            borderColor = SweepColors.activeBorderColor
            hoverEnabled = true

            // Add hover effect to the button that triggers the panel's hover
            val panel = this
            val mouseListener =
                object : java.awt.event.MouseAdapter() {
                    override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                        panel.applyHoverEffect()
                    }

                    override fun mouseExited(e: java.awt.event.MouseEvent?) {
                        panel.removeHoverEffect()
                    }
                }
            fileNameButton.addMouseListener(mouseListener)
            registerListener(fileNameButton, mouseListener)

            add(fileNameButton)
            if (fileType == "suggestedFile") {
                dottedBorder = true
                add(addButton)
            } else if (fileType.startsWith(SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX)) {
                dottedBorder = true
                add(addButton)
            } else {
                add(closeButton)
            }
            val latest = (fileType.startsWith(SweepConstants.GENERAL_TEXT_SNIPPET_PREFIX))
            val isCopyPaste = file.contains("CopyPaste")
            if ((overrideAutoOpenEmbeddedFile || (showLatest && latest && !dontAutoOpenEmbeddedFile)) && isCopyPaste) {
                // Handle double selection by programmatically "clicking" the button.
                if (file != fileInEmbeddedFilePanel) {
                    fileNameButton.apply {
                        doClick()
                    }
                }
            }
            identifier = fileType

            // Set the panel's preferred size to match the total content width
            val buttonWidth = fileNameButton.preferredSize.width
            val actionButtonWidth =
                if (fileType == "suggestedFile" ||
                    fileType.startsWith(SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX)
                ) {
                    addButton.preferredSize.width
                } else {
                    closeButton.preferredSize.width
                }
            val totalPanelWidth = buttonWidth + actionButtonWidth
            preferredSize = Dimension(totalPanelWidth, 22).scaled
        }
    }

    private fun createOpenAutoCompleteButton() =
        RoundedPanel(FlowLayout(FlowLayout.CENTER, 0, 0), this).apply {
            background = null
            borderColor = SweepColors.activeBorderColor
            hoverEnabled = true

            // Capture panel reference for hover effect
            val panel = this

            // Create the plus button.
            val metaData = SweepMetaData.getInstance()
            val btn =
                JButton(if (metaData.fileContextUsageCount < SweepConstants.FILE_CONTEXT_USAGE_COUNT_THRESHOLD) "Add Files" else "")
                    .apply {
                        name = "plusButton"
                        icon = SweepIcons.At.scale(11f)
                        putClientProperty("originalIcon", icon)
                        background = null
                        withSweepFont(project, scale = 0.9f)
                        preferredSize =
                            if (metaData.fileContextUsageCount < SweepConstants.FILE_CONTEXT_USAGE_COUNT_THRESHOLD) {
                                Dimension(80, 24).scaled
                            } else {
                                Dimension(22, 24).scaled
                            }
                        minimumSize = preferredSize
                        maximumSize = preferredSize
                        foreground = JBColor.WHITE
                        putClientProperty("originalForeground", foreground)
                        isBorderPainted = false
                        isFocusPainted = false
                        isContentAreaFilled = false
                        toolTipText = "Add files to context"
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        horizontalAlignment = SwingConstants.CENTER
                        verticalAlignment = SwingConstants.CENTER
                        // Center the icon in the button
                        iconTextGap = 4
                        margin = JBUI.emptyInsets()

                        // Add hover effect to the button that triggers the panel's hover
                        val mouseListener =
                            object : java.awt.event.MouseAdapter() {
                                override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                                    panel.applyHoverEffect()
                                }

                                override fun mouseExited(e: java.awt.event.MouseEvent?) {
                                    panel.removeHoverEffect()
                                }
                            }
                        addMouseListener(mouseListener)
                        registerListener(this, mouseListener)

                        val actionListener =
                            ActionListener {
                                ApplicationManager.getApplication().invokeLater {
                                    textComponent.apply {
                                        if (text.lastOrNull() != '@') {
                                            text += "@"
                                        }
                                        requestFocus()
                                    }
                                    metaData.fileContextUsageCount++

                                    fileAutocomplete.scheduleMentionChooser()
                                }
                            }
                        addActionListener(actionListener)
                        registerListener(this, actionListener)
                    }.also { add(it) }

            // Set the panel's size after adding the button
            preferredSize = Dimension(btn.preferredSize.width + JBUI.scale(2), JBUI.scale(22))
            minimumSize = preferredSize
            maximumSize = preferredSize
        }

    private fun countFilesOfType(fileType: String): Int = allFilePanels.count { it.identifierEquals(fileType) }

    private fun removeFilesOfType(fileType: String): List<JPanel> {
        val allFilePanelsCopy = ArrayList(allFilePanels)
        val removed = allFilePanelsCopy.filter { it.identifierEquals(fileType) }
        allFilePanels.removeAll(removed.toSet())
        updatePanelVisibility()
        return removed
    }

    private fun removeFilesThatStartWithType(fileTypePrefix: String): List<JPanel> {
        val allFilePanelsCopy = ArrayList(allFilePanels)
        val removed = allFilePanelsCopy.filter { it.identifierStartsWith(fileTypePrefix) }
        allFilePanels.removeAll(removed.toSet())
        updatePanelVisibility()
        return removed
    }

    private fun getGeneralTextSnippetName(fileInfo: FileInfo): String {
        var lastDashIndex = fileInfo.name.lastIndexOf(SweepConstants.GENERAL_TEXT_SNIPPET_SEPARATOR)
        val notIn = lastDashIndex < 0
        if (lastDashIndex == -1) {
            lastDashIndex = fileInfo.name.length
        }

        val baseName = fileInfo.name.substring(0, lastDashIndex)
        val numberSuffix = fileInfo.name.substring(lastDashIndex)

        val baseDisplayName =
            SweepConstants.CUSTOM_FILE_INFO_MAP[baseName]
                ?: if (baseName.startsWith(SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX)) {
                    baseName.substring(SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX.length)
                } else if (baseName.startsWith(SweepConstants.GENERAL_TEXT_SNIPPET_PREFIX)) {
                    baseName.substring(SweepConstants.GENERAL_TEXT_SNIPPET_PREFIX.length)
                } else {
                    baseName
                }

        if (notIn) {
            return baseDisplayName
        }

        return baseDisplayName + numberSuffix
    }

    fun addGeneralTextSnippet(
        fileInfo: FileInfo,
        onClose: (FileInfo) -> Unit,
        overrideAutoOpenEmbeddedFile: Boolean = false,
    ) {
        includedGeneralTextSnippets.add(fileInfo)

        val stringCallbackAdapter: (String) -> Unit = { _ ->
            // When the string callback is invoked, call the FileInfo callback with the actual fileInfo
            onClose(fileInfo)
        }

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }

            val filePanel =
                createFilePanel(
                    getGeneralTextSnippetName(fileInfo),
                    fileInfo.relativePath,
                    fileInfo.name,
                    stringCallbackAdapter,
                    overrideAutoOpenEmbeddedFile = overrideAutoOpenEmbeddedFile,
                )
            addFilePanel(filePanel)
            val hasSuggestedSnippets =
                includedGeneralTextSnippets.any {
                    it.name.startsWith(SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX)
                }
            project.messageBus.syncPublisher(SUGGESTED_SNIPPET_TOPIC).onSuggestedGeneralTextSnippetChanged(hasSuggestedSnippets)
        }
    }

    private fun changeCurrentFile(
        file: Pair<String, String>?,
        onClose: (String) -> Unit,
    ) {
        if (doNotShowCurrentFileInContext) return

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }

            removeFilesOfType("openedFile")
            if (file != null && autoIncludeOpenFileInAutocomplete) {
                // To avoid name collision, check the autocompleted files and see if the file already has a display name or another file conflicts with the desired name
                val relativePath = relativePath(project, file.second) ?: file.second
                val displayName = fileAutocomplete.getDisambiguousNameForNewOpenFile(relativePath) ?: file.first

                // Send current open file to fileAutocomplete so that new additions to mention map can avoid this name
                fileAutocomplete.setCurrentOpenFile(Pair(displayName, relativePath))
                val filePanel = createFilePanel(displayName, file.second, "openedFile", onClose)
                addFilePanel(filePanel)
            }
        }
    }

    /**
     * Public helper to update the current open file shown in the UI.
     * This wraps the internal changeCurrentFile so external callers (e.g. FileAutocomplete)
     * can request the UI to refresh the current-open-file pill when a rename/move occurs.
     */
    fun setCurrentOpenFileInUI(
        displayName: String,
        relativePath: String,
    ) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            changeCurrentFile(Pair(displayName, relativePath)) { /* no-op close handler */ }
        }
    }

    private fun updateAutoCompletedMentions(
        mentions: MutableMap<String, String>,
        onClose: (String) -> Unit,
    ) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }

            val showLatest = countFilesOfType("autocompletedMention") < mentions.size

            // Remove existing autocompleted mentions
            removeFilesOfType("autocompletedMention")

            // Add new mentions
            val newPanels =
                mentions.keys.map { key ->
                    val path = mentions[key]!!
                    createFilePanel(
                        key,
                        absolutePath(project, path),
                        "autocompletedMention",
                        onClose,
                        showLatest,
                    )
                }

            allFilePanels.addAll(newPanels)
            updatePanelVisibility()

            if (isAttachedToUserMessageComponent) {
                addIncludedFiles(mentions.values.toList())
            }
        }
    }

    private fun updateSelectedSnippets(
        snippets: MutableMap<SelectedSnippet, String>,
        onClose: (String) -> Unit,
    ) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }

            val showLatest = countFilesOfType("snippets") < snippets.size

            // Remove existing snippets
            removeFilesOfType("snippets")

            // Create new snippet panels
            val newPanels =
                snippets.map { (k, v) ->
                    createFilePanel(
                        k.denotation,
                        absolutePath(project, v),
                        "snippets",
                        onClose,
                        showLatest,
                    )
                }

            // Add new panels and update UI
            allFilePanels.addAll(newPanels)
            updatePanelVisibility()
        }
    }

    private fun updateImages(
        images: Map<String, dev.sweep.assistant.data.Image>,
        onClose: (String) -> Unit,
    ) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }

            val showLatest = countFilesOfType("image") < images.size

            // Remove existing images
            removeFilesOfType("image")

            // Create new image panels
            val newPanels =
                images.map { (k, v) ->
                    createFilePanel(
                        k,
                        "", // Empty path for images
                        "image",
                        onClose,
                        showLatest,
                        v.base64,
                    )
                }

            // Add new panels and update UI
            allFilePanels.addAll(newPanels)
            updatePanelVisibility()
        }
    }

    fun updateIncludedGeneralTextSnippets(onClose: (FileInfo) -> Unit) {
        val snippetsCopy = ArrayList(includedGeneralTextSnippets)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }

            // Remove existing general text snippets
            removeFilesThatStartWithType(SweepConstants.GENERAL_TEXT_SNIPPET_PREFIX)
            removeFilesThatStartWithType(SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX)

            // Add updated snippets
            val newPanels =
                snippetsCopy.map { fileInfo ->
                    val stringCallbackAdapter: (String) -> Unit = { _ -> onClose(fileInfo) }
                    createFilePanel(
                        getGeneralTextSnippetName(fileInfo),
                        fileInfo.relativePath,
                        fileInfo.name,
                        stringCallbackAdapter,
                    )
                }

            allFilePanels.addAll(newPanels)
            updatePanelVisibility()

            val hasSuggestedSnippets =
                includedGeneralTextSnippets.any {
                    it.name.startsWith(SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX)
                }
            project.messageBus.syncPublisher(SUGGESTED_SNIPPET_TOPIC).onSuggestedGeneralTextSnippetChanged(hasSuggestedSnippets)
        }
    }

    fun reset() {
        fileInEmbeddedFilePanel = null
        embeddedFilePanel.releaseEditor()
        currentFileInContextManager.reset()
        fileAutocomplete.reset()
        imageManager.reset()
        focusChatController.reset()

        // Delete suggested general text snippets and their associated files
        val suggestedSnippets =
            includedGeneralTextSnippets.filter { fileInfo ->
                fileInfo.name.startsWith(SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX)
            }

        ApplicationManager.getApplication().executeOnPooledThread {
            for (fileInfo in suggestedSnippets) {
                safeDeleteFile(fileInfo.relativePath)
            }
        }

        includedGeneralTextSnippets.clear()
        if (!project.isDisposed) {
            project.messageBus.syncPublisher(SUGGESTED_SNIPPET_TOPIC).onSuggestedGeneralTextSnippetChanged(false)
        }

        // Clear all file panels and reset expansion state
        allFilePanels.clear()
        isExpanded = false

        panel.apply {
            removeAll()
            add(createOpenAutoCompleteButton())
            revalidate()
            repaint()
        }
    }

    /**
     * Saves the current state of files in context for later restoration.
     * Used when switching away from a tab/session to preserve its file context.
     */
    fun saveState(): FilesInContextState {
        // Get files from autocomplete (excluding current open file which is dynamic)
        val files = fileAutocomplete.getFilesMap()

        // Get snippets - convert SelectedSnippet keys to their denotation strings for serialization
        val snippets = focusChatController.getFocusedFiles().mapKeys { it.key.denotation }

        // Get images
        val images = imageManager.getImages()

        // Get general text snippets (excluding suggested ones which are transient)
        val generalSnippets =
            includedGeneralTextSnippets.filter { fileInfo ->
                !fileInfo.name.startsWith(SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX)
            }

        return FilesInContextState(
            includedFiles = files,
            includedSnippets = snippets,
            includedImages = images,
            includedGeneralTextSnippets = generalSnippets,
        )
    }

    /**
     * Restores the state of files in context from a previously saved state.
     * Used when switching back to a tab/session to restore its file context.
     */
    fun restoreState(state: FilesInContextState) {
        // First reset everything to clear the current state
        fileInEmbeddedFilePanel = null
        embeddedFilePanel.releaseEditor()

        // Reset file autocomplete and restore files
        fileAutocomplete.reset()
        if (state.includedFiles.isNotEmpty()) {
            replaceIncludedFiles(state.includedFiles)
        }

        // Reset snippets and restore
        focusChatController.reset()
        if (state.includedSnippets.isNotEmpty()) {
            replaceIncludedSnippets(state.includedSnippets)
        }

        // Reset images and restore
        imageManager.reset()
        if (state.includedImages.isNotEmpty()) {
            replaceIncludedImages(state.includedImages)
        }

        // Clear and restore general text snippets (not suggested ones)
        includedGeneralTextSnippets.clear()
        includedGeneralTextSnippets.addAll(state.includedGeneralTextSnippets)

        // Clear all file panels and reset expansion state - the restore methods above will trigger UI updates
        allFilePanels.clear()
        isExpanded = false

        // Rebuild the panel from scratch
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            panel.apply {
                removeAll()
                add(createOpenAutoCompleteButton())
                revalidate()
                repaint()
            }

            // Trigger current file manager to re-add the current open file pill
            currentFileInContextManager.reset()

            // Notify about general text snippets state
            val hasSuggestedSnippets =
                includedGeneralTextSnippets.any {
                    it.name.startsWith(SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX)
                }
            if (!project.isDisposed) {
                project.messageBus.syncPublisher(SUGGESTED_SNIPPET_TOPIC).onSuggestedGeneralTextSnippetChanged(hasSuggestedSnippets)
            }
        }
    }

    fun hideIfCurrentlyOpen(mention: String) {
        if (fileInEmbeddedFilePanel == mention) {
            fileInEmbeddedFilePanel = null
            embeddedFilePanel.showFile(null)
        }
    }

    fun addIncludedFiles(files: List<String>) {
        fileAutocomplete.addIncludedFiles(files.filterNot { it in includedFiles })
    }

    fun replaceIncludedFiles(files: Map<String, String>) {
        fileAutocomplete.replaceIncludedFiles(files)
    }

    fun addIncludedSnippets(snippets: List<Snippet>) {
        focusChatController.addIncludedSnippets(snippets)
    }

    fun replaceIncludedSnippets(snippets: Map<String, String>) {
        focusChatController.replaceIncludedSnippets(snippets)
    }

    fun replaceIncludedImages(images: List<dev.sweep.assistant.data.Image>) {
        imageManager.replaceIncludedImages(images)
    }

    override fun applyDarkening() {
        forEachComponent(panel) { comp ->
            when (comp) {
                is JButton -> {
                    when (comp.name) {
                        "plusButton" -> {
                            comp.icon?.let { currentIcon ->
                                // Cache original icon if not already cached
                                val originalIcon =
                                    comp.getClientProperty("sweep.originalIcon") as? javax.swing.Icon
                                        ?: currentIcon.also { comp.putClientProperty("sweep.originalIcon", it) }

                                // Apply darkening/brightening to the original icon, not the current one
                                comp.icon =
                                    if (isIDEDarkMode()) {
                                        originalIcon.darker(5)
                                    } else {
                                        originalIcon.brighter(5)
                                    }
                            }
                        }
                        "fileNameButton" -> {
                            // Cache original foreground if not already cached
                            val originalForeground =
                                comp.getClientProperty("originalForeground") as? Color
                                    ?: comp.foreground.also { comp.putClientProperty("originalForeground", it) }

                            comp.foreground =
                                if (isIDEDarkMode()) {
                                    originalForeground.darker()
                                } else {
                                    originalForeground.customBrighter(0.5f)
                                }
                        }
                    }
                }
                is RoundedPanel -> {
                    comp.borderColor = comp.borderColor
                }
            }
        }
    }

    override fun revertDarkening() {
        forEachComponent(panel) { comp ->
            when (comp) {
                is JButton -> {
                    when (comp.name) {
                        "plusButton" -> {
                            // Restore the original icon instead of brightening the current one
                            val originalIcon = comp.getClientProperty("sweep.originalIcon") as? javax.swing.Icon
                            if (originalIcon != null) {
                                comp.icon = originalIcon
                            }
                        }
                        "fileNameButton" -> {
                            // Restore the original foreground instead of using UIManager color
                            val originalForeground = comp.getClientProperty("originalForeground") as? Color
                            comp.foreground = originalForeground ?: UIManager.getColor("Panel.foreground")
                        }
                    }
                }
                is RoundedPanel -> {
                    comp.borderColor = comp.borderColor
                }
            }
        }
    }

    override fun dispose() {
        tooltipBalloon?.dispose() // Dispose of balloon when component is disposed
        newChatTooltipBalloon?.dispose() // Dispose of new chat tooltip balloon

        // Dispose of image popup and browser
        imagePopupBalloon?.dispose()
        imagePopupBrowser?.dispose()
        imagePopupBrowser = null

        // Remove resize listener
        resizeListener?.let { listener ->
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)
            toolWindow?.component?.removeComponentListener(listener)
        }
        resizeListener = null

        currentFileInContextManager.fileChangeListener.removeOnFileChangedAction("FilesInContextComponentFileChangeListener")

        for ((component, listener) in listenersToRemove) {
            when (listener) {
                is ActionListener -> (component as? JButton)?.removeActionListener(listener)
                is MouseListener -> component.removeMouseListener(listener)
            }
        }
        listenersToRemove.clear()

        // Close any open embedded file panel
        fileInEmbeddedFilePanel = null
        embeddedFilePanel.releaseEditor()

        // Reset current file context manager
        currentFileInContextManager.reset()

        // Clear our collections
        includedGeneralTextSnippets.clear()

        // Clean up UI components
        panel.removeAll()

        // Let message bus listeners know we're clean
        if (!project.isDisposed) {
            project.messageBus
                .syncPublisher(SUGGESTED_SNIPPET_TOPIC)
                .onSuggestedGeneralTextSnippetChanged(false)
        }
    }
}
