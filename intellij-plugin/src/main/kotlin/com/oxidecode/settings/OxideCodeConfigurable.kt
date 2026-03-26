package com.oxidecode.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.util.ui.FormBuilder
import javax.swing.*

class OxideCodeConfigurable : Configurable {

    private val settings = OxideCodeSettings.instance

    private val baseUrlField = JTextField(40)
    private val apiKeyField = JPasswordField(40)
    private val modelField = JTextField(40)
    private val completionModelField = JTextField(40)
    private val autocompleteEnabledBox = JCheckBox("Enable inline completions")
    private val nesEnabledBox = JCheckBox("Enable Next Edit Suggestions")
    private val nesDebounceMsField = JSpinner(SpinnerNumberModel(300, 50, 5000, 50))

    override fun getDisplayName() = "OxideCode"

    override fun createComponent(): JComponent {
        reset()
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Provider base URL:", baseUrlField)
            .addLabeledComponent("API key (empty for local models):", apiKeyField)
            .addLabeledComponent("Model:", modelField)
            .addLabeledComponent("Completion model (optional, faster):", completionModelField)
            .addSeparator()
            .addComponent(autocompleteEnabledBox)
            .addComponent(nesEnabledBox)
            .addLabeledComponent("NES debounce (ms):", nesDebounceMsField)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean =
        baseUrlField.text != settings.baseUrl ||
        String(apiKeyField.password) != settings.apiKey ||
        modelField.text != settings.model ||
        completionModelField.text != settings.completionModel ||
        autocompleteEnabledBox.isSelected != settings.autocompleteEnabled ||
        nesEnabledBox.isSelected != settings.nesEnabled ||
        (nesDebounceMsField.value as Int) != settings.nesDebounceMs

    @Throws(ConfigurationException::class)
    override fun apply() {
        settings.baseUrl = baseUrlField.text.trim()
        settings.apiKey = String(apiKeyField.password)
        settings.model = modelField.text.trim()
        settings.completionModel = completionModelField.text.trim()
        settings.autocompleteEnabled = autocompleteEnabledBox.isSelected
        settings.nesEnabled = nesEnabledBox.isSelected
        settings.nesDebounceMs = nesDebounceMsField.value as Int
    }

    override fun reset() {
        baseUrlField.text = settings.baseUrl
        apiKeyField.text = settings.apiKey
        modelField.text = settings.model
        completionModelField.text = settings.completionModel
        autocompleteEnabledBox.isSelected = settings.autocompleteEnabled
        nesEnabledBox.isSelected = settings.nesEnabled
        nesDebounceMsField.value = settings.nesDebounceMs
    }
}
