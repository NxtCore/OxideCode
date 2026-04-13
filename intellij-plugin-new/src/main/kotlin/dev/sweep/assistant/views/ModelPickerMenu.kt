package dev.sweep.assistant.views

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.components.SweepComponent
import dev.sweep.assistant.services.SweepColorChangeService
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.brighter
import dev.sweep.assistant.utils.isIDEDarkMode
import dev.sweep.assistant.utils.withSweepFont
import java.awt.BorderLayout
import javax.swing.JPanel

class ModelPickerMenu(
    private val project: Project,
    parentDisposable: Disposable,
) : JPanel(),
    Disposable {
    companion object {
        private val logger = Logger.getInstance(ModelPickerMenu::class.java)
        private const val DEFAULT_MODEL = "Auto"
    }

    private var currentModel: String = DEFAULT_MODEL
    private val listeners = mutableListOf<(String) -> Unit>()
    private val comboBox = RoundedComboBox<String>()
    private val configureFavoritesOption = "+ More models"

    init {
        isOpaque = false
        layout = BorderLayout()
        border = JBUI.Borders.empty()

        comboBox.withSweepFont(project, scale = 1f)
        comboBox.isOpaque = false
        updateSecondaryText()
        comboBox.toolTipText = "${SweepConstants.META_KEY}/ to toggle between models"
        comboBox.isTransparent = true

        val savedModel = SweepComponent.getSelectedModel(project)
        val availableModels = getConfiguredModels()
        currentModel =
            when {
                savedModel.isNotEmpty() && availableModels.contains(savedModel) -> savedModel
                availableModels.contains(DEFAULT_MODEL) -> DEFAULT_MODEL
                availableModels.isNotEmpty() -> availableModels.first()
                else -> DEFAULT_MODEL
            }

        updateComboBoxModel()

        comboBox.addActionListener {
            val selectedModel = comboBox.selectedItem as? String ?: return@addActionListener
            when {
                selectedModel == configureFavoritesOption -> {
                    comboBox.selectedItem = currentModel
                    openFavoriteModelsDialog()
                }
                selectedModel != currentModel -> setModel(selectedModel)
            }
        }

        add(comboBox, BorderLayout.CENTER)

        project.messageBus.connect(this).subscribe(
            SweepComponent.MODEL_STATE_TOPIC,
            object : SweepComponent.ModelStateListener {
                override fun onModelChanged(model: String) {
                    if (model.isNotEmpty() && model != currentModel && getConfiguredModels().contains(model)) {
                        currentModel = model
                        updateComboBoxModel()
                    }
                }
            },
        )

        project.messageBus.connect(this).subscribe(
            SweepSettings.SettingsChangedNotifier.TOPIC,
            SweepSettings.SettingsChangedNotifier {
                refreshFromSettings()
            },
        )

        SweepColorChangeService.getInstance(project).addThemeChangeListener(this) {
            border = JBUI.Borders.empty()
            comboBox.foreground =
                if (isIDEDarkMode()) SweepColors.sendButtonColorForeground.darker() else SweepColors.sendButtonColorForeground.brighter(12)
            comboBox.updateThemeColors()
        }
        Disposer.register(parentDisposable, this)
    }

    private fun getConfiguredModels(): List<String> {
        val provider = SweepSettings.getInstance().directAutocompleteProvider
        return buildList<String> {
            add(DEFAULT_MODEL)
            addAll(provider.availableModels)
            if (provider.model.isNotBlank()) add(provider.model)
            if (provider.completionModel.isNotBlank()) add(provider.completionModel)
        }.filter { it.isNotBlank() }.distinct()
    }

    private fun refreshFromSettings() {
        val availableModels = getConfiguredModels()
        if (!availableModels.contains(currentModel)) {
            currentModel = availableModels.firstOrNull() ?: DEFAULT_MODEL
            SweepComponent.setSelectedModel(project, currentModel)
        }
        ApplicationManager.getApplication().invokeLater {
            updateComboBoxModel()
        }
    }

    private fun updateComboBoxModel() {
        val availableModels = getConfiguredModels()
        val favorites = SweepMetaData.getInstance().favoriteModels
        val validFavorites = favorites.filter { availableModels.contains(it) }
        val modelNames = validFavorites.ifEmpty { availableModels }
        val options = modelNames + configureFavoritesOption
        comboBox.setOptions(options)
        comboBox.selectedItem =
            when {
                currentModel == configureFavoritesOption -> modelNames.firstOrNull() ?: DEFAULT_MODEL
                modelNames.contains(currentModel) -> currentModel
                modelNames.isNotEmpty() -> modelNames.first()
                else -> DEFAULT_MODEL
            }
    }

    private fun setModel(model: String) {
        if (!getConfiguredModels().contains(model)) {
            logger.warn("$model is not a valid model. Use one of ${getConfiguredModels()}")
            return
        }
        if (currentModel == model) return
        currentModel = model
        updateComboBoxModel()
        SweepComponent.setSelectedModel(project, model)
        notifyListeners()
    }

    fun reset() {
        refreshFromSettings()
    }

    fun addModelChangeListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it(currentModel) }
    }

    fun getModel(): String {
        if (currentModel == DEFAULT_MODEL) {
            return SweepSettings.getInstance().directAutocompleteProvider.model.ifBlank { DEFAULT_MODEL.lowercase() }
        }
        return currentModel
    }

    fun getAvailableModels(): List<String> = getConfiguredModels().toList()

    private fun getCycleableModels(): List<String> {
        val availableModels = getConfiguredModels()
        val favorites = SweepMetaData.getInstance().favoriteModels
        val validFavorites = favorites.filter { availableModels.contains(it) }
        return validFavorites.ifEmpty { availableModels }
    }

    fun cycleToNextModel() {
        val cycleableModels = getCycleableModels()
        val currentIndex = cycleableModels.indexOf(currentModel)
        val nextIndex = if (cycleableModels.isEmpty()) 0 else (currentIndex + 1).floorMod(cycleableModels.size)
        val nextModel = cycleableModels.getOrNull(nextIndex) ?: return
        setModel(nextModel)
    }

    private fun Int.floorMod(mod: Int): Int = if (mod == 0) 0 else ((this % mod) + mod) % mod

    private fun openFavoriteModelsDialog() {
        val availableModels = getConfiguredModels()
        val currentFavorites = SweepMetaData.getInstance().favoriteModels
        val dialog = FavoriteModelsDialog(project, availableModels, currentFavorites)
        if (dialog.showAndGet()) {
            val selectedFavorites = dialog.getSelectedFavorites()
            SweepMetaData.getInstance().favoriteModels = selectedFavorites.toMutableList()
            updateComboBoxModel()
        }
    }

    fun updateSecondaryText() {
        comboBox.secondaryText = "${SweepConstants.META_KEY}/"
    }

    fun getSelectedModelName(): String = selectedItem ?: ""

    fun setCustomDisplayText(text: String?) {
        val roundedComboBox = comboBox as? RoundedComboBox<*>
        roundedComboBox?.text = text
    }

    fun setTooltipText(text: String) {
        comboBox.toolTipText = text
    }

    fun setBorderOverride(border: javax.swing.border.Border?) {
        comboBox.setBorderOverride(border)
    }

    private val selectedItem: String?
        get() = comboBox.selectedItem as? String

    override fun dispose() {
        listeners.clear()
    }
}
