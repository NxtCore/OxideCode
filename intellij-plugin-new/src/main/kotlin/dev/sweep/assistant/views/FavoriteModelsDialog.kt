package dev.sweep.assistant.views

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.utils.withSweepFont
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class FavoriteModelsDialog(
    private val project: Project,
    private val availableModels: List<String>,
    private val currentFavorites: List<String>,
) : DialogWrapper(project) {
    private val checkBoxMap = mutableMapOf<String, JBCheckBox>()
    private val selectedModels = currentFavorites.toMutableSet()
    private var resultFavorites: List<String> = currentFavorites
    private lateinit var listPanel: JPanel
    private lateinit var searchField: SearchTextField
    private lateinit var selectedCountLabel: JBLabel

    init {
        title = "Select Models"
        isModal = true
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = JBUI.Borders.empty(12)

        // Create header with description
        val headerPanel = JPanel(BorderLayout(0, 8))
        headerPanel.isOpaque = false

        val descriptionLabel = JBLabel("Choose which models appear in your model picker:")
        descriptionLabel.withSweepFont(project, scale = 1f)
        headerPanel.add(descriptionLabel, BorderLayout.NORTH)

        // Create search field
        searchField =
            SearchTextField().apply {
                textEditor.emptyText.text = "Search models..."
                withSweepFont(project, scale = 1f)

                textEditor.document.addDocumentListener(
                    object : DocumentListener {
                        override fun insertUpdate(e: DocumentEvent) = filterModels()

                        override fun removeUpdate(e: DocumentEvent) = filterModels()

                        override fun changedUpdate(e: DocumentEvent) = filterModels()
                    },
                )
            }
        headerPanel.add(searchField, BorderLayout.CENTER)

        panel.add(headerPanel, BorderLayout.NORTH)

        // Create list panel with checkboxes
        listPanel = JPanel()
        listPanel.layout = BoxLayout(listPanel, BoxLayout.Y_AXIS)
        listPanel.border = JBUI.Borders.empty(4)

        // Keep models in backend order, but ensure Auto is first
        val sortedModels = availableModels.sortedWith(compareBy { it != "Auto" })

        for (model in sortedModels) {
            val checkBox = createModelCheckBox(model)
            checkBoxMap[model] = checkBox
            listPanel.add(checkBox)
        }

        // Wrap in scroll pane
        val scrollPane = JBScrollPane(listPanel)
        scrollPane.border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1)
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        scrollPane.preferredSize = Dimension(350, 300)

        panel.add(scrollPane, BorderLayout.CENTER)

        // Footer with selected count and actions
        val footerPanel = JPanel(BorderLayout())
        footerPanel.isOpaque = false
        footerPanel.border = JBUI.Borders.emptyTop(8)

        selectedCountLabel = JBLabel()
        selectedCountLabel.withSweepFont(project, scale = 0.9f)
        updateSelectedCount()

        footerPanel.add(selectedCountLabel, BorderLayout.WEST)

        // Select all checkbox
        val selectAllCheckBox =
            JBCheckBox("Select All").apply {
                withSweepFont(project, scale = 0.9f)
                // Check if all visible models (except Auto) are selected
                isSelected = getVisibleModels().filter { it != "Auto" }.all { selectedModels.contains(it) }

                addActionListener {
                    if (isSelected) {
                        getVisibleModels().forEach { model ->
                            selectedModels.add(model)
                            checkBoxMap[model]?.isSelected = true
                        }
                    } else {
                        getVisibleModels().forEach { model ->
                            // Don't deselect "Auto" - it should always be selected
                            if (model != "Auto") {
                                selectedModels.remove(model)
                                checkBoxMap[model]?.isSelected = false
                            }
                        }
                    }
                    updateSelectedCount()
                }
            }
        footerPanel.add(selectAllCheckBox, BorderLayout.EAST)

        panel.add(footerPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun createModelCheckBox(model: String): JBCheckBox {
        val isSelected = selectedModels.contains(model)
        val isAuto = model == "Auto"

        return JBCheckBox(model).apply {
            this.isSelected = isSelected || isAuto // Auto is always selected
            this.isEnabled = !isAuto // Auto cannot be deselected
            withSweepFont(project, scale = 1f)
            border = JBUI.Borders.empty(4, 4)
            alignmentX = Component.LEFT_ALIGNMENT

            if (isAuto) {
                toolTipText = "Auto is always available"
                selectedModels.add("Auto")
            }

            addActionListener {
                if (this.isSelected) {
                    selectedModels.add(model)
                } else {
                    selectedModels.remove(model)
                }
                updateSelectedCount()
            }
        }
    }

    private fun filterModels() {
        val searchText = searchField.text.trim().lowercase()

        for ((model, checkBox) in checkBoxMap) {
            val matches = searchText.isEmpty() || model.lowercase().contains(searchText)
            checkBox.isVisible = matches
        }

        listPanel.revalidate()
        listPanel.repaint()
    }

    private fun getVisibleModels(): List<String> = checkBoxMap.filter { it.value.isVisible }.keys.toList()

    private fun updateSelectedCount() {
        val count = selectedModels.size
        selectedCountLabel.text = "$count model${if (count != 1) "s" else ""} selected"
    }

    override fun doOKAction() {
        // Collect selected models, maintaining the backend order but with Auto first
        val sortedSelected =
            availableModels
                .filter { selectedModels.contains(it) }
                .sortedWith(compareBy { it != "Auto" })
        resultFavorites = sortedSelected
        super.doOKAction()
    }

    fun getSelectedFavorites(): List<String> = resultFavorites
}
