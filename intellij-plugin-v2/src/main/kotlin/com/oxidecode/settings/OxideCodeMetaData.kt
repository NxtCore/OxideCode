package com.oxidecode.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "OxideCodeMetaData", storages = [Storage("OxideCodeMetaData.xml")])
class OxideCodeMetaData : PersistentStateComponent<OxideCodeMetaData.MetaData> {
    data class MetaData(
        var lastNotifiedVersion: String? = null,
        var historyButtonClicks: Int = 0,
        var newButtonClicks: Int = 0,
        var commitMessageButtonClicks: Int = 0,
        var configButtonClicks: Int = 0,
        var reportButtonClicks: Int = 0,
        var applyButtonClicks: Int = 0,
        var hasSeenTutorialV2: Boolean = false,
        var hasSeenChatTutorial: Boolean = false,
        var suggestedUserInputCount: Int = 0,
        var acceptedSuggestedUserInputCount: Int = 0,
        var rejectedSuggestedUserInputCount: Int = 0,
        var chatWithSearch: Int = 0,
        var chatWithoutSearch: Int = 0,
        var fileContextUsageCount: Int = 0,
        var chatsSent: Int = 0,
        var projectFullSyncedList: List<String> = emptyList(),
        var hasUsedFileShortcut: Boolean = false,
        var hasShownFileShortcutBalloon: Boolean = false,
        var hasShownNewChatBalloon: Boolean = false,
        var hasShownClickToAddFilesBalloon: Boolean = false,
        var chatHistoryUsed: Int = 0,
        var chatHistoryBalloonWasShown: Boolean = false,
        var hasShownProblemsWindow: Boolean = false,
        var hasShownSearchPopup: Boolean = false,
        var hasShownAgentPopup: Boolean = false,
        var ghostTextTabAcceptCount: Int = 0,
        var modelToggleUsed: Boolean = false,
        var chatModeToggleUsed: Boolean = false,
        var hasHandledPluginConflictsOnFirstInstall: Boolean = false,
        var hasSeenInstallationTelemetryEvent: Boolean = false,
        var isToolWindowVisible: Boolean = true,
        // Format: "<projectHash>_true" or "<projectHash>_false"
        var finishedFilesCachePopulationList: MutableList<String> = mutableListOf(),
        // Format: "<projectHash>_<lastIndex>"
        var lastIndexedFileList: MutableList<String> = mutableListOf(),
        // Format: "<projectHash>_<lastIndex>"
        var lastIndexedEntityFileList: MutableList<String> = mutableListOf(),
        // Format: "<projectHash>_<count>"
        var lastKnownFileCountList: MutableList<String> = mutableListOf(),
        // Format: "<projectHash>_true" or "<projectHash>_false"
        var finishedEntitiesCachePopulationList: MutableList<String> = mutableListOf(),
        // List of version numbers for which update notifications have been shown
        var shownUpdateVersions: MutableList<String> = mutableListOf(),
        // Format: "<projectHash>_<branchName>"
        var defaultBranchListForFileAutocomplete: MutableList<String> = mutableListOf(),
        var privacyModeEnabled: Boolean = false,
        // Whether the user's privacy mode has been migrated from their project level settings (OxideCodeConfig)
        var hasPrivacyModeBeenUpdatedFromProject: Boolean = false,
        // Whether to skip confirmation dialog when reverting changes
        var skipRevertConfirmation: Boolean = false,
        // Cache for allowed models from backend
        var cachedModels: String? = null,
        var cachedDefaultModel: String? = null,
        // Whether the user has used ACTION_CHOOSE_LOOKUP_ITEM (pressed Enter on autocomplete)
        var hasUsedLookupItem: Boolean = false,
        var hasShownConfigureKeybindsForCmdKRequest: Boolean = false,
        var hasShownConfigureKeybindsForCmdJRequest: Boolean = false,
        // Map of tip hash to show count (to bias towards showing new tips and limit to 3 shows per tip)
        var tipShowCounts: MutableMap<Int, Int> = mutableMapOf(),
        // Gateway onboarding flags
        var hasShownGatewayClientOnboarding: Boolean = false,
        var hasShownGatewayHostOnboarding: Boolean = false,
        // Whether to show shortcut update notifications (true = don't show)
        var dontShowShortcutNotifications: Boolean = false,
        // Whether to show conflict plugin notifications (true = don't show)
        var dontShowConflictNotifications: Boolean = false,
        // Whether to show Cmd-J conflict notifications (true = don't show)
        var dontShowCmdJConflictNotifications: Boolean = false,
        // Whether the user has used the Review PR action before
        var hasUsedReviewPRAction: Boolean = false,
        // Whether the user has clicked the web search button
        var hasClickedWebSearch: Boolean = false,
        // TokenUsageIndicator tooltip hint state
        // Whether we've ever shown the "(click to show details)" tooltip hint.
        // Once true, we stop appending that hint to reduce tooltip noise.
        var hasShownTokenUsageClickToShowDetailsHint: Boolean = false,
        // Whether we've ever shown the "(click to hide details)" tooltip hint.
        // Once true, we stop appending that hint to reduce tooltip noise.
        var hasShownTokenUsageClickToHideDetailsHint: Boolean = false,
        // List of favorite model display names for quick cycling
        var favoriteModels: MutableList<String> = mutableListOf(),
        // Version of favorite models from backend, used to append new favorites when server version increases
        var favoriteModelsVersion: Int = 0,
    )

    private var metaData = MetaData()

    override fun getState(): MetaData = metaData

    override fun loadState(state: MetaData) {
        this.metaData =
            state.copy(
                finishedFilesCachePopulationList = state.finishedFilesCachePopulationList.toMutableList(),
                lastIndexedFileList = state.lastIndexedFileList.toMutableList(),
                favoriteModels = state.favoriteModels.toMutableList(),
            )
    }

    var privacyModeEnabled: Boolean
        get() = metaData.privacyModeEnabled
        set(value) {
            metaData.privacyModeEnabled = value
        }

    var autocompleteAcceptCount: Int
        get() = metaData.ghostTextTabAcceptCount
        set(value) {
            metaData.ghostTextTabAcceptCount = value
        }

    var hasUsedLookupItem: Boolean
        get() = metaData.hasUsedLookupItem
        set(value) {
            metaData.hasUsedLookupItem = value
        }

    companion object {
        fun getInstance(): OxideCodeMetaData = ApplicationManager.getApplication().getService(OxideCodeMetaData::class.java)
    }
}
