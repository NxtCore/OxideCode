package com.oxidecode.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.ui.FormBuilder
import javax.swing.JComboBox
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

class OxideCodeConfigurable : Configurable {
    private val settings = OxideCodeSettings.getInstance()

    private val baseUrlField = JTextField(40)
    private val apiKeyField = JPasswordField(40)
    private val modelField = JTextField(40)
    private val exclusionPatternsArea = JTextArea(6, 40)
    private val nextEditSuggestionsEnabledBox = JCheckBox("Enable Next Edit Suggestions")
    private val nesDebounceMsField = JSpinner(SpinnerNumberModel(50, 20, 5000, 10))
    private val nesPromptStyleField = JComboBox(arrayOf("generic", "zeta1", "zeta2", "sweep"))
    private val debugLogsDirField = JTextField(40)

    override fun getDisplayName(): String = "OxideCode"

    override fun createComponent(): JComponent {
        nesPromptStyleField.isEditable = true
        reset()
        return FormBuilder
            .createFormBuilder()
            .addLabeledComponent("Provider base URL:", baseUrlField)
            .addLabeledComponent("API key (empty for local models):", apiKeyField)
            .addLabeledComponent("Model:", modelField)
            .addLabeledComponent("Autocomplete exclusion patterns:", JScrollPane(exclusionPatternsArea))
            .addSeparator()
            .addComponent(nextEditSuggestionsEnabledBox)
            .addLabeledComponent("NES debounce (ms):", nesDebounceMsField)
            .addLabeledComponent("NES prompt style:", nesPromptStyleField)
            .addLabeledComponent("Debug logs dir (empty = off):", debugLogsDirField)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val settingsChanged =
            baseUrlField.text.trim() != settings.baseUrl ||
                String(apiKeyField.password) != settings.anthropicApiKey ||
                modelField.text.trim() != settings.model ||
                nextEditSuggestionsEnabledBox.isSelected != settings.nextEditPredictionFlagOn ||
                ((nesDebounceMsField.value as Int).toLong()) != effectiveDebounceMs() ||
                (nesPromptStyleField.selectedItem as String) != settings.nesPromptStyle ||
                debugLogsDirField.text.trim() != settings.debugLogDir

        val exclusionPatternsChanged =
            normalizePatternText(exclusionPatternsArea.text) != normalizePatternText(getExclusionPatternsText())

        return settingsChanged || exclusionPatternsChanged
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        settings.baseUrl = baseUrlField.text.trim()
        settings.anthropicApiKey = String(apiKeyField.password)
        settings.model = modelField.text.trim()
        settings.nextEditPredictionFlagOn = nextEditSuggestionsEnabledBox.isSelected
        settings.autocompleteDebounceMs = (nesDebounceMsField.value as Int).toLong()
        settings.nesPromptStyle = nesPromptStyleField.selectedItem as String
        settings.debugLogDir = debugLogsDirField.text.trim()

        val updatedPatterns =
            normalizePatternText(exclusionPatternsArea.text)
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()

        ProjectManager.getInstance().openProjects.forEach { project ->
            OxideCodeConfig.getInstance(project).updateAutocompleteExclusionPatterns(updatedPatterns)
        }
    }

    override fun reset() {
        baseUrlField.text = settings.baseUrl
        apiKeyField.text = settings.anthropicApiKey
        modelField.text = settings.model
        exclusionPatternsArea.text = getExclusionPatternsText()
        nextEditSuggestionsEnabledBox.isSelected = settings.nextEditPredictionFlagOn
        nesDebounceMsField.value = effectiveDebounceMs().toInt()
        nesPromptStyleField.selectedItem = settings.nesPromptStyle
        debugLogsDirField.text = settings.debugLogDir
    }

    private fun getExclusionPatternsText(): String {
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return ""
        return OxideCodeConfig
            .getInstance(project)
            .getAutocompleteExclusionPatterns()
            .toList()
            .sorted()
            .joinToString("\n")
    }

    private fun effectiveDebounceMs(): Long {
        val value = settings.autocompleteDebounceMs
        if (value <= 0L) return 50L
        return value.coerceIn(20L, 5000L)
    }

    private fun normalizePatternText(raw: String): String =
        raw
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("\n")
}
