package dev.sweep.assistant.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import dev.sweep.assistant.views.RevertBeforeRewriteConfirmation

@State(name = "SweepMetaData", storages = [Storage("SweepMetaData.xml")])
class SweepMetaData : PersistentStateComponent<SweepMetaData.MetaData> {
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
        // Whether the user's privacy mode has been migrated from their project level settings (SweepConfig)
        var hasPrivacyModeBeenUpdatedFromProject: Boolean = false,
        // Whether to skip confirmation dialog when reverting changes
        var skipRevertConfirmation: Boolean = false,
        // How to handle confirmation dialog when reverting before rewriting
        var skipRevertBeforeRewriteConfirmation: RevertBeforeRewriteConfirmation = RevertBeforeRewriteConfirmation.ASK_ALWAYS,
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

    var lastNotifiedVersion: String?
        get() = metaData.lastNotifiedVersion
        set(value) {
            metaData.lastNotifiedVersion = value
        }

    var historyButtonClicks: Int
        get() = metaData.historyButtonClicks
        set(value) {
            metaData.historyButtonClicks = value
        }

    var hasSeenTutorialV2: Boolean
        get() = metaData.hasSeenTutorialV2
        set(value) {
            metaData.hasSeenTutorialV2 = value
        }

    var newButtonClicks: Int
        get() = metaData.newButtonClicks
        set(value) {
            metaData.newButtonClicks = value
        }

    var commitMessageButtonClicks: Int
        get() = metaData.commitMessageButtonClicks
        set(value) {
            metaData.commitMessageButtonClicks = value
        }

    var configButtonClicks: Int
        get() = metaData.configButtonClicks
        set(value) {
            metaData.configButtonClicks = value
        }

    var reportButtonClicks: Int
        get() = metaData.reportButtonClicks
        set(value) {
            metaData.reportButtonClicks = value
        }

    var applyButtonClicks: Int
        get() = metaData.applyButtonClicks
        set(value) {
            metaData.applyButtonClicks = value
        }

    var suggestedUserInputCount: Int
        get() = metaData.suggestedUserInputCount
        set(value) {
            metaData.suggestedUserInputCount = value
        }

    var acceptedSuggestedUserInputCount: Int
        get() = metaData.acceptedSuggestedUserInputCount
        set(value) {
            metaData.acceptedSuggestedUserInputCount = value
        }

    var rejectedSuggestedUserInputCount: Int
        get() = metaData.rejectedSuggestedUserInputCount
        set(value) {
            metaData.rejectedSuggestedUserInputCount = value
        }

    var chatWithSearch: Int
        get() = metaData.chatWithSearch
        set(value) {
            metaData.chatWithSearch = value
        }

    var chatWithoutSearch: Int
        get() = metaData.chatWithoutSearch
        set(value) {
            metaData.chatWithoutSearch = value
        }

    var fileContextUsageCount: Int
        get() = metaData.fileContextUsageCount
        set(value) {
            metaData.fileContextUsageCount = value
        }

    var chatsSent: Int
        get() = metaData.chatsSent
        set(value) {
            metaData.chatsSent = value
        }

    var projectFullSyncedList: List<String>
        get() = metaData.projectFullSyncedList
        set(value) {
            metaData.projectFullSyncedList = value
        }

    var hasUsedFileShortcut: Boolean
        get() = metaData.hasUsedFileShortcut
        set(value) {
            metaData.hasUsedFileShortcut = value
        }

    var hasShownFileShortcutBalloon: Boolean
        get() = metaData.hasShownFileShortcutBalloon
        set(value) {
            metaData.hasShownFileShortcutBalloon = value
        }

    var hasShownNewChatBalloon: Boolean
        get() = metaData.hasShownNewChatBalloon
        set(value) {
            metaData.hasShownNewChatBalloon = value
        }

    var privacyModeEnabled: Boolean
        get() = metaData.privacyModeEnabled
        set(value) {
            metaData.privacyModeEnabled = value
        }

    var hasPrivacyModeBeenUpdatedFromProject: Boolean
        get() = metaData.hasPrivacyModeBeenUpdatedFromProject
        set(value) {
            metaData.hasPrivacyModeBeenUpdatedFromProject = value
        }

    var hasShownClickToAddFilesBalloon: Boolean
        get() = metaData.hasShownClickToAddFilesBalloon
        set(value) {
            metaData.hasShownClickToAddFilesBalloon = value
        }

    var chatHistoryUsed: Int
        get() = metaData.chatHistoryUsed
        set(value) {
            metaData.chatHistoryUsed = value
        }

    var chatHistoryBalloonWasShown: Boolean
        get() = metaData.chatHistoryBalloonWasShown
        set(value) {
            metaData.chatHistoryBalloonWasShown = value
        }

    var hasShownProblemsWindow: Boolean
        get() = metaData.hasShownProblemsWindow
        set(value) {
            metaData.hasShownProblemsWindow = value
        }

    var hasShownSearchPopup: Boolean
        get() = metaData.hasShownSearchPopup
        set(value) {
            metaData.hasShownSearchPopup = value
        }

    var hasShownAgentPopup: Boolean
        get() = metaData.hasShownAgentPopup
        set(value) {
            metaData.hasShownAgentPopup = value
        }

    var shownUpdateVersions: MutableList<String>
        get() = metaData.shownUpdateVersions
        set(value) {
            metaData.shownUpdateVersions = value
        }

    var autocompleteAcceptCount: Int
        get() = metaData.ghostTextTabAcceptCount
        set(value) {
            metaData.ghostTextTabAcceptCount = value
        }

    var modelToggleUsed: Boolean
        get() = metaData.modelToggleUsed
        set(value) {
            metaData.modelToggleUsed = value
        }

    var chatModeToggleUsed: Boolean
        get() = metaData.chatModeToggleUsed
        set(value) {
            metaData.chatModeToggleUsed = value
        }

    var hasHandledPluginConflictsOnFirstInstall: Boolean
        get() = metaData.hasHandledPluginConflictsOnFirstInstall
        set(value) {
            metaData.hasHandledPluginConflictsOnFirstInstall = value
        }

    var hasSeenInstallationTelemetryEvent: Boolean
        get() = metaData.hasSeenInstallationTelemetryEvent
        set(value) {
            metaData.hasSeenInstallationTelemetryEvent = value
        }

    var isToolWindowVisible: Boolean
        get() = metaData.isToolWindowVisible
        set(value) {
            metaData.isToolWindowVisible = value
        }

    var skipRevertConfirmation: Boolean
        get() = metaData.skipRevertConfirmation
        set(value) {
            metaData.skipRevertConfirmation = value
        }

    var skipRevertBeforeRewriteConfirmation: RevertBeforeRewriteConfirmation
        get() = metaData.skipRevertBeforeRewriteConfirmation
        set(value) {
            metaData.skipRevertBeforeRewriteConfirmation = value
        }

    var cachedModels: String?
        get() = metaData.cachedModels
        set(value) {
            metaData.cachedModels = value
        }

    var cachedDefaultModel: String?
        get() = metaData.cachedDefaultModel
        set(value) {
            metaData.cachedDefaultModel = value
        }

    var hasUsedLookupItem: Boolean
        get() = metaData.hasUsedLookupItem
        set(value) {
            metaData.hasUsedLookupItem = value
        }

    fun hasShownUpdateForVersion(version: String): Boolean = metaData.shownUpdateVersions.contains(version)

    fun markUpdateAsShown(version: String) {
        if (!metaData.shownUpdateVersions.contains(version)) {
            metaData.shownUpdateVersions.add(version)
        }
    }

    @Synchronized // Basic synchronization for list modification
    fun isFilesCachePopulationFinishedForProject(projectHash: String): Boolean {
        val entry = metaData.finishedFilesCachePopulationList.find { it.startsWith("${projectHash}_") }
        return entry?.substringAfterLast('_')?.toBooleanStrictOrNull() ?: false
    }

    @Synchronized
    fun setFilesCachePopulationFinishedForProject(
        projectHash: String,
        finished: Boolean,
    ) {
        val prefix = "${projectHash}_"
        val index = metaData.finishedFilesCachePopulationList.indexOfFirst { it.startsWith(prefix) }
        val newValue = "$prefix$finished"
        if (index != -1) {
            metaData.finishedFilesCachePopulationList[index] = newValue
        } else {
            metaData.finishedFilesCachePopulationList.add(newValue)
        }
    }

    @Synchronized
    fun getLastIndexedFileForProject(projectHash: String): Int {
        val entry = metaData.lastIndexedFileList.find { it.startsWith("${projectHash}_") }
        return entry?.substringAfterLast('_')?.toIntOrNull() ?: 0
    }

    @Synchronized
    fun setLastIndexedFileForProject(
        projectHash: String,
        index: Int,
    ) {
        val prefix = "${projectHash}_"
        val listIndex = metaData.lastIndexedFileList.indexOfFirst { it.startsWith(prefix) }
        val newValue = "$prefix$index"
        if (listIndex != -1) {
            metaData.lastIndexedFileList[listIndex] = newValue
        } else {
            metaData.lastIndexedFileList.add(newValue)
        }
    }

    @Synchronized
    fun isEntitiesCachePopulationFinishedForProject(projectHash: String): Boolean {
        val entry = metaData.finishedEntitiesCachePopulationList.find { it.startsWith("${projectHash}_") }
        return entry?.substringAfterLast('_')?.toBooleanStrictOrNull() ?: false
    }

    @Synchronized
    fun setEntitiesCachePopulationFinishedForProject(
        projectHash: String,
        finished: Boolean,
    ) {
        val prefix = "${projectHash}_"
        val index = metaData.finishedEntitiesCachePopulationList.indexOfFirst { it.startsWith(prefix) }
        val newValue = "$prefix$finished"
        if (index != -1) {
            metaData.finishedEntitiesCachePopulationList[index] = newValue
        } else {
            metaData.finishedEntitiesCachePopulationList.add(newValue)
        }
    }

    @Synchronized
    fun getLastIndexedEntityFileForProject(projectHash: String): Int {
        val entry = metaData.lastIndexedEntityFileList.find { it.startsWith("${projectHash}_") }
        return entry?.substringAfterLast('_')?.toIntOrNull() ?: 0
    }

    @Synchronized
    fun setLastIndexedEntityFileForProject(
        projectHash: String,
        index: Int,
    ) {
        val prefix = "${projectHash}_"
        val listIndex = metaData.lastIndexedEntityFileList.indexOfFirst { it.startsWith(prefix) }
        val newValue = "$prefix$index"
        if (listIndex != -1) {
            metaData.lastIndexedEntityFileList[listIndex] = newValue
        } else {
            metaData.lastIndexedEntityFileList.add(newValue)
        }
    }

    @Synchronized
    fun getLastKnownFileCountForProject(projectHash: String): Int {
        val entry = metaData.lastKnownFileCountList.find { it.startsWith("${projectHash}_") }
        return entry?.substringAfterLast('_')?.toIntOrNull() ?: 0
    }

    @Synchronized
    fun setLastKnownFileCountForProject(
        projectHash: String,
        count: Int,
    ) {
        val prefix = "${projectHash}_"
        val listIndex = metaData.lastKnownFileCountList.indexOfFirst { it.startsWith(prefix) }
        val newValue = "$prefix$count"
        if (listIndex != -1) {
            metaData.lastKnownFileCountList[listIndex] = newValue
        } else {
            metaData.lastKnownFileCountList.add(newValue)
        }
    }

    @Synchronized
    fun getDefaultBranchForProject(projectHash: String): String? {
        val entry = metaData.defaultBranchListForFileAutocomplete.find { it.startsWith("${projectHash}_") }
        return entry?.substringAfterLast('_')
    }

    @Synchronized
    fun setDefaultBranchForProject(
        projectHash: String,
        branchName: String,
    ) {
        val prefix = "${projectHash}_"
        val index = metaData.defaultBranchListForFileAutocomplete.indexOfFirst { it.startsWith(prefix) }
        val newValue = "$prefix$branchName"
        if (index != -1) {
            metaData.defaultBranchListForFileAutocomplete[index] = newValue
        } else {
            metaData.defaultBranchListForFileAutocomplete.add(newValue)
        }
    }

    @Synchronized
    fun resetSweepCache(projectHash: String) {
        // Completely wipe all metadata by resetting to default state
        metaData = MetaData()
    }

    @Synchronized
    fun getTipShowCount(tipHash: Int): Int = metaData.tipShowCounts[tipHash] ?: 0

    @Synchronized
    fun incrementTipShowCount(tipHash: Int) {
        val currentCount = metaData.tipShowCounts[tipHash] ?: 0
        metaData.tipShowCounts[tipHash] = currentCount + 1
    }

    var hasShownGatewayClientOnboarding: Boolean
        get() = metaData.hasShownGatewayClientOnboarding
        set(value) {
            metaData.hasShownGatewayClientOnboarding = value
        }

    var hasShownGatewayHostOnboarding: Boolean
        get() = metaData.hasShownGatewayHostOnboarding
        set(value) {
            metaData.hasShownGatewayHostOnboarding = value
        }

    var hasSeenChatTutorial: Boolean
        get() = metaData.hasSeenChatTutorial
        set(value) {
            metaData.hasSeenChatTutorial = value
        }

    var dontShowShortcutNotifications: Boolean
        get() = metaData.dontShowShortcutNotifications
        set(value) {
            metaData.dontShowShortcutNotifications = value
        }

    var dontShowConflictNotifications: Boolean
        get() = metaData.dontShowConflictNotifications
        set(value) {
            metaData.dontShowConflictNotifications = value
        }

    var dontShowCmdJConflictNotifications: Boolean
        get() = metaData.dontShowCmdJConflictNotifications
        set(value) {
            metaData.dontShowCmdJConflictNotifications = value
        }

    var hasUsedReviewPRAction: Boolean
        get() = metaData.hasUsedReviewPRAction
        set(value) {
            metaData.hasUsedReviewPRAction = value
        }

    var hasClickedWebSearch: Boolean
        get() = metaData.hasClickedWebSearch
        set(value) {
            metaData.hasClickedWebSearch = value
        }

    var hasShownTokenUsageClickToShowDetailsHint: Boolean
        get() = metaData.hasShownTokenUsageClickToShowDetailsHint
        set(value) {
            metaData.hasShownTokenUsageClickToShowDetailsHint = value
        }

    var hasShownTokenUsageClickToHideDetailsHint: Boolean
        get() = metaData.hasShownTokenUsageClickToHideDetailsHint
        set(value) {
            metaData.hasShownTokenUsageClickToHideDetailsHint = value
        }

    var favoriteModels: MutableList<String>
        get() = metaData.favoriteModels
        set(value) {
            metaData.favoriteModels = value
        }

    var favoriteModelsVersion: Int
        get() = metaData.favoriteModelsVersion
        set(value) {
            metaData.favoriteModelsVersion = value
        }

    companion object {
        fun getInstance(): SweepMetaData = ApplicationManager.getApplication().getService(SweepMetaData::class.java)
    }
}
