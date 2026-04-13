package dev.sweep.assistant.views

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.components.SweepComponent
import dev.sweep.assistant.data.AllowedModelsV2Request
import dev.sweep.assistant.data.AllowedModelsV2Response
import dev.sweep.assistant.services.SweepColorChangeService
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.brighter
import dev.sweep.assistant.utils.getConnection
import dev.sweep.assistant.utils.isIDEDarkMode
import dev.sweep.assistant.utils.withSweepFont
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.awt.BorderLayout
import java.net.HttpURLConnection
import javax.swing.JPanel

class ModelPickerMenu(
    private val project: Project,
    parentDisposable: Disposable,
    private var models: Map<String, String> = DEFAULT_MODELS,
    private var initialModel: String = DEFAULT_MODEL,
) : JPanel(),
    Disposable {
    companion object {
        // Fallback models in case backend is down
        private val DEFAULT_MODELS =
            mapOf(
                // Pass through the special "auto" key when Auto is selected
                "Auto" to "auto",
                "Sonnet 4 (thinking)" to "claude-sonnet-4-20250514:thinking",
                "Sonnet 4" to "claude-sonnet-4-20250514",
            )
        private const val DEFAULT_MODEL = "Auto"

        // Cache for model display names to IDs mapping
        private var modelIdCache: Map<String, String> = mapOf()
        private var lastFetchTime: Long = 0
    }

    private var currentModel: String = initialModel
    private var defaultModelFromBackend: String = "Sonnet 4" // fallback
    private val listeners = mutableListOf<(String) -> Unit>()
    private val logger = Logger.getInstance(ModelPickerMenu::class.java)
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val comboBox = RoundedComboBox<String>()
    private var pollingJob: Job? = null
    private val configureFavoritesOption = "+ More models"

    init {
        isOpaque = false
        layout = BorderLayout()
        border = JBUI.Borders.empty()

        comboBox.withSweepFont(project, scale = 1f)
        comboBox.isOpaque = false
        updateSecondaryText()
        comboBox.toolTipText = "${SweepConstants.META_KEY}/ to toggle between models"
        comboBox.setItemTooltips(SweepConstants.MODEL_HINTS)
        comboBox.isTransparent = true

        // Try to load cached models first
        loadCachedModels()

        // Check if there's a saved model preference
        val savedModel = SweepComponent.getSelectedModel(project)
        if (savedModel.isNotEmpty() && models.keys.contains(savedModel)) {
            currentModel = savedModel
        }

        // Initialize the combo box with models
        updateComboBoxModel()

        // Add action listener to handle selection changes
        comboBox.addActionListener {
            val selectedModel = comboBox.selectedItem as String
            when {
                selectedModel == configureFavoritesOption -> {
                    // Reset selection to current model
                    comboBox.selectedItem = currentModel
                    // Open favorites configuration dialog
                    openFavoriteModelsDialog()
                }
                selectedModel != currentModel -> {
                    setModel(selectedModel)
                }
            }
        }

        add(comboBox, BorderLayout.CENTER)

        // Fetch allowed models from backend
        fetchAllowedModels()

        // Start polling for model updates
        startPolling()

        // Listen for model changes from other components
        project.messageBus.connect(this).subscribe(
            SweepComponent.MODEL_STATE_TOPIC,
            object : SweepComponent.ModelStateListener {
                override fun onModelChanged(model: String) {
                    if (model.isNotEmpty() && model != currentModel && models.keys.contains(model)) {
                        currentModel = model
                        updateComboBoxSelection()
                    }
                }
            },
        )

        SweepColorChangeService.getInstance(project).addThemeChangeListener(this) {
            border = JBUI.Borders.empty()
            comboBox.foreground =
                if (isIDEDarkMode()) SweepColors.sendButtonColorForeground.darker() else SweepColors.sendButtonColorForeground.brighter(12)
            comboBox.updateThemeColors()
        }
        Disposer.register(parentDisposable, this)

        // Re-fetch models when settings change (e.g. backend URL changed)
        project.messageBus.connect(this).subscribe(
            SweepSettings.SettingsChangedNotifier.TOPIC,
            SweepSettings.SettingsChangedNotifier {
                fetchAllowedModels()
            },
        )
    }

    private fun updateComboBoxModel() {
        val favorites = SweepMetaData.getInstance().favoriteModels
        val validFavorites = favorites.filter { models.keys.contains(it) }

        // Determine which models to show - fall back to all models if favorites are empty
        var modelNames =
            validFavorites.ifEmpty {
                models.keys.toList()
            }

        // Add options at the end
        val options = modelNames + configureFavoritesOption

        comboBox.setOptions(options)
        // Ensure we never show "+ More models" as selected - it's only an action option
        comboBox.selectedItem =
            if (currentModel == configureFavoritesOption) {
                modelNames.firstOrNull() ?: currentModel
            } else {
                currentModel
            }
    }

    private fun updateComboBoxSelection() {
        comboBox.selectedItem = currentModel
    }

    private fun loadCachedModels() {
        try {
            val metaData = SweepMetaData.getInstance()
            val cachedModelsJson = metaData.cachedModels
            val cachedDefaultModel = metaData.cachedDefaultModel

            if (!cachedModelsJson.isNullOrEmpty()) {
                val json = Json { ignoreUnknownKeys = true }
                val cachedModels = json.decodeFromString<Map<String, String>>(cachedModelsJson)

                if (cachedModels.isNotEmpty()) {
                    // Update the cache (without "Auto" as it's not from backend)
                    modelIdCache = cachedModels

                    // Ensure "Auto" is included in the models map for UI
                    val updatedModelsMap = mutableMapOf("Auto" to "auto")
                    updatedModelsMap.putAll(cachedModels)
                    models = updatedModelsMap

                    if (!cachedDefaultModel.isNullOrEmpty()) {
                        defaultModelFromBackend = cachedDefaultModel
                    }

                    logger.info("Loaded ${cachedModels.size} models from cache")
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to load cached models", e)
        }
    }

    private fun saveCachedModels(
        modelsMap: Map<String, String>,
        defaultModel: String,
    ) {
        try {
            val metaData = SweepMetaData.getInstance()
            val json = Json { ignoreUnknownKeys = true }
            // Serialize the map as a JSON string
            val modelsJson = json.encodeToString(kotlinx.serialization.serializer<Map<String, String>>(), modelsMap)

            metaData.cachedModels = modelsJson
            metaData.cachedDefaultModel = defaultModel

            logger.info("Saved ${modelsMap.size} models to cache")
        } catch (e: Exception) {
            logger.warn("Failed to save models to cache", e)
        }
    }

    private fun startPolling() {
        pollingJob =
            coroutineScope.launch {
                while (isActive) {
                    delay(15 * 60 * 1000L) // 15 minutes in milliseconds
                    fetchAllowedModels()
                }
            }
    }

    private fun fetchAllowedModels() {
        coroutineScope.launch {
            try {
                var connection: HttpURLConnection? = null
                try {
                    connection = getConnection("backend/sweep-jetbrains-allowed-models-v2")

                    val allowedModelsRequest =
                        AllowedModelsV2Request(
                            repo_name =
                                SweepSettings
                                    .getInstance()
                                    .githubToken
                                    .split("/")
                                    .lastOrNull() ?: "",
                        )

                    val json = Json { encodeDefaults = true }
                    val postData = json.encodeToString(AllowedModelsV2Request.serializer(), allowedModelsRequest)

                    connection.outputStream.use { os ->
                        os.write(postData.toByteArray())
                        os.flush()
                    }

                    val response = connection.inputStream.bufferedReader().use { it.readText() }

                    // Parse the v2 response format
                    val responseMap = json.decodeFromString<AllowedModelsV2Response>(response)

                    val modelsMap = responseMap.models
                    val defaultModelMap = responseMap.default_model
                    val favoriteModels = responseMap.favorite_models
                    val favoriteVersion = responseMap.favorite_version

                    ApplicationManager.getApplication().invokeLater {
                        if (modelsMap.isNotEmpty()) {
                            // Update the cache
                            modelIdCache = modelsMap
                            lastFetchTime = System.currentTimeMillis()

                            // Store the default model from backend
                            defaultModelFromBackend = defaultModelMap.keys.firstOrNull() ?: "claude-sonnet-4-20250514:thinking"

                            // Update favorite models based on version:
                            // - If user has no local favorites, set them from backend
                            // - If backend version is greater, append new favorites to user's list
                            val metaData = SweepMetaData.getInstance()
                            val currentLocalFavorites = metaData.favoriteModels
                            val localVersion = metaData.favoriteModelsVersion

                            if (favoriteModels.isNotEmpty()) {
                                val validFavorites = favoriteModels.filter { modelsMap.keys.contains(it.key) }
                                if (validFavorites.isNotEmpty()) {
                                    if (currentLocalFavorites.isEmpty()) {
                                        // No local favorites - set from backend
                                        val favoritesWithAuto = mutableListOf("Auto")
                                        favoritesWithAuto.addAll(validFavorites.map { it.key })
                                        metaData.favoriteModels = favoritesWithAuto
                                        metaData.favoriteModelsVersion = favoriteVersion
                                    } else if (favoriteVersion > localVersion) {
                                        // Backend has newer version - append new favorites to user's list
                                        val newFavorites = validFavorites.map { it.key }.filter { !currentLocalFavorites.contains(it) }
                                        if (newFavorites.isNotEmpty()) {
                                            val updatedFavorites = currentLocalFavorites.toMutableList()
                                            updatedFavorites.addAll(newFavorites)
                                            metaData.favoriteModels = updatedFavorites
                                        }
                                        metaData.favoriteModelsVersion = favoriteVersion
                                    }
                                }
                            }

                            // Save to persistent cache
                            saveCachedModels(modelsMap, defaultModelFromBackend)

                            // Ensure "Auto" is included in the models map
                            val updatedModelsMap = mutableMapOf("Auto" to "auto")
                            updatedModelsMap.putAll(modelsMap)
                            models = updatedModelsMap

                            // Check if there's a saved model preference and it's valid
                            val savedModel = SweepComponent.getSelectedModel(project)
                            if (savedModel.isNotEmpty() && updatedModelsMap.keys.contains(savedModel)) {
                                initialModel = savedModel
                                currentModel = savedModel
                            } else if (updatedModelsMap.keys.contains(currentModel)) {
                                // Keep the current model if it's still valid (e.g., "Auto")
                                initialModel = currentModel
                            } else {
                                // Fall back to default model from the default_model map or use the first model
                                initialModel = defaultModelFromBackend
                                currentModel = initialModel
                            }

                            updateComboBoxModel()
                            logger.info("Successfully fetched and cached allowed models from backend (v2)")
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to fetch allowed models", e)
                } finally {
                    connection?.disconnect()
                }
            } catch (e: Exception) {
                logger.warn("Error in fetchAllowedModels", e)
            }
        }
    }

    fun reset() {
        setModel(initialModel)
    }

    private fun setModel(model: String) {
        if (!models.keys.contains(model)) {
            logger.warn("$model is not a valid model. Use one of ${models.keys}")
            return
        }
        if (currentModel == model) return
        currentModel = model
        // Rebuild the combo box to remove any temporarily added models
        updateComboBoxModel()
        // Save the selected model to persistent storage
        SweepComponent.setSelectedModel(project, model)
        notifyListeners()
    }

    fun addModelChangeListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it(currentModel) }
    }

    // for external usage
    fun getModel(): String {
        // When "Auto" is selected, pass through the special key "auto" so the backend can route
        if (currentModel == "Auto") {
            return models["Auto"] ?: modelIdCache["Auto"] ?: "auto"
        }
        // Otherwise return the model id mapped from the display name
        return models[currentModel] ?: modelIdCache[currentModel] ?: currentModel
    }

    // for external usage only
    private fun getDefaultModelName(): String = defaultModelFromBackend

    // Add this method to expose available models
    fun getAvailableModels(): List<String> = models.keys.toList()

    /**
     * Gets the list of favorite models for cycling.
     * Returns all available models if no favorites are configured.
     */
    private fun getCycleableModels(): List<String> {
        val favorites = SweepMetaData.getInstance().favoriteModels
        // Filter favorites to only include models that are currently available
        val validFavorites = favorites.filter { models.keys.contains(it) }
        return validFavorites.ifEmpty {
            models.keys.toList()
        }
    }

    /**
     * Cycles to the next model in the favorites list (or all models if no favorites).
     */
    fun cycleToNextModel() {
        val cycleableModels = getCycleableModels()
        val currentIndex = cycleableModels.indexOf(currentModel)
        val nextIndex = (currentIndex + 1) % cycleableModels.size
        val nextModel = cycleableModels[nextIndex]
        setModel(nextModel)
    }

    /**
     * Opens the favorite models configuration dialog.
     */
    private fun openFavoriteModelsDialog() {
        val availableModels = models.keys.toList()
        val currentFavorites = SweepMetaData.getInstance().favoriteModels

        val dialog = FavoriteModelsDialog(project, availableModels, currentFavorites)
        if (dialog.showAndGet()) {
            val selectedFavorites = dialog.getSelectedFavorites()
            SweepMetaData.getInstance().favoriteModels = selectedFavorites.toMutableList()
            logger.info("Updated favorite models: $selectedFavorites")

            updateComboBoxModel()
        }
    }

    fun updateSecondaryText() {
        comboBox.secondaryText = "${SweepConstants.META_KEY}/"
    }

    /**
     * Gets the currently selected model name for display purposes.
     */
    fun getSelectedModelName(): String = selectedItem ?: ""

    /**
     * Sets custom display text for the combo box.
     */
    fun setCustomDisplayText(text: String?) {
        val roundedComboBox = comboBox as? RoundedComboBox<*>
        roundedComboBox?.text = text
    }

    /**
     * Sets the tooltip text for the combo box.
     */
    fun setTooltipText(text: String) {
        comboBox.toolTipText = text
    }

    /**
     * Sets a custom border override for the combo box.
     * This allows for complete control over the border styling.
     * @param border The border to apply to the combo box, or null to use default
     */
    fun setBorderOverride(border: javax.swing.border.Border?) {
        comboBox.setBorderOverride(border)
    }

    /**
     * Gets the currently selected item as a string.
     */
    private val selectedItem: String?
        get() = comboBox.selectedItem as? String

    override fun dispose() {
        pollingJob?.cancel()
        coroutineScope.cancel()
        listeners.clear()
    }
}
