package dev.sweep.assistant.version

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginNode
import com.intellij.ide.plugins.RepositoryHelper
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.updateSettings.impl.PluginDownloader
import com.intellij.openapi.updateSettings.impl.UpdateSettings
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.theme.SweepIcons.scale
import dev.sweep.assistant.utils.SweepBundle
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.getCurrentSweepPluginVersion
import dev.sweep.assistant.utils.showNotification
import java.util.concurrent.TimeUnit

private val logger = Logger.getInstance(VersionChecker::class.java)

/**
 * Returns true if versions need updating at all
 */
internal fun needsUpdate(
    currentVersion: String?,
    latestVersion: String?,
): Boolean {
    if (currentVersion == null || latestVersion == null) return false
    try {
        val currentParts = currentVersion.split(".").map { it.toInt() }
        val latestParts = latestVersion.split(".").map { it.toInt() }

        // Pad shorter version with zeros
        val maxLength = maxOf(currentParts.size, latestParts.size)
        val current = currentParts + List(maxLength - currentParts.size) { 0 }
        val latest = latestParts + List(maxLength - latestParts.size) { 0 }

        // Compare each part sequentially
        for (i in 0 until maxLength) {
            when {
                latest[i] > current[i] -> return true
                latest[i] < current[i] -> return false
                // If equal, continue to next part
            }
        }
        return false // All parts are equal
    } catch (e: NumberFormatException) {
        logger.warn("Failed to parse version numbers: current=$currentVersion, latest=$latestVersion", e)
        return false
    }
}

/**
 * Loads plugins using the best available API.
 * Tries the new public API first, falls back to deprecated API if not available.
 */
private fun loadPluginsFromMarketplace(pluginId: PluginId): Collection<PluginNode> =
    try {
        // Try the new public API first (available in newer platform versions)
        val method = RepositoryHelper::class.java.getMethod("loadPlugins", Set::class.java)
        @Suppress("UNCHECKED_CAST")
        method.invoke(null, setOf(pluginId)) as Collection<PluginNode>
    } catch (e: NoSuchMethodException) {
        // Fall back to the deprecated API for older platform versions
        logger.info("Using deprecated RepositoryHelper API (platform version < 2024.1)")
        @Suppress("DEPRECATION")
        RepositoryHelper.loadPlugins(
            null, // null means use default marketplace
            null, // null means current build
            null, // no progress indicator
        )
    } catch (e: Exception) {
        logger.warn("Failed to load plugins using new API, falling back to deprecated API", e)
        @Suppress("DEPRECATION")
        RepositoryHelper.loadPlugins(
            null, // null means use default marketplace
            null, // null means current build
            null, // no progress indicator
        )
    }

private fun getLatestPluginVersion(): String? =
    try {
        val pluginId = PluginId.getId(SweepBundle.message(SweepConstants.PLUGIN_ID_KEY))
        val marketplacePlugins = loadPluginsFromMarketplace(pluginId)
        val sweepPlugin = marketplacePlugins.find { it.pluginId == pluginId }
        sweepPlugin?.version
    } catch (e: Exception) {
        logger.info("Failed to fetch latest plugin version: ${e.message}")
        null
    }

private fun getSweepDownloader(plugin: IdeaPluginDescriptor): PluginDownloader = PluginDownloader.createDownloader(plugin, null, null)

class VersionChecker : ProjectActivity {
    override suspend fun execute(project: Project) {
        scheduleVersionChecks(project)
    }

    private fun scheduleVersionChecks(project: Project) {
        AppExecutorUtil
            .getAppScheduledExecutorService()
            .scheduleWithFixedDelay({ VersionCheckTask(project).queue() }, 1, 3 * 60 * 60, TimeUnit.SECONDS)
    }

    private class VersionCheckTask(
        project: Project,
    ) : Task.Backgroundable(project, "SweepVersionCheck", true) {
        override fun run(indicator: ProgressIndicator) {
            val updateSettings = UpdateSettings.getInstance()

            // Skip if user has auto-update enabled
            try {
                val autoUpdateEnabledMethod = UpdateSettings::class.java.getDeclaredMethod("isPluginsAutoUpdateEnabled")
                if (autoUpdateEnabledMethod.invoke(updateSettings) as Boolean) {
                    return
                }
            } catch (e: Exception) {
                return
            }

            val currentVersion = getCurrentSweepPluginVersion() ?: return
            val latestVersion = getLatestPluginVersion() ?: return

            val metaData = SweepMetaData.getInstance()
            val lastNotifiedVersion = metaData.lastNotifiedVersion

            // Skip if we already notified for this version
            if (lastNotifiedVersion == latestVersion) {
                return
            }

            // Check if major version update is available
            if (needsUpdate(currentVersion, latestVersion)) {
                ApplicationManager.getApplication().invokeLater {
                    promptUserForUpdate(project, latestVersion)
                }
            }
        }

        private fun promptUserForUpdate(
            project: Project,
            latestVersion: String,
        ) {
            // Move network operations to background thread
            object : Backgroundable(project, "Fetching Plugin Information", false) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val pluginId = PluginId.getId(SweepBundle.message(SweepConstants.PLUGIN_ID_KEY))
                        val marketplacePlugins = loadPluginsFromMarketplace(pluginId)
                        val sweepPlugin = marketplacePlugins.find { it.pluginId == pluginId }

                        if (sweepPlugin == null) {
                            logger.warn("Could not find Sweep plugin in marketplace")
                            return
                        }

                        // Switch back to EDT for UI operations
                        ApplicationManager.getApplication().invokeLater {
                            showUpdateNotification(project, latestVersion, sweepPlugin)
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to fetch plugin information", e)
                    }
                }
            }.queue()
        }

        private fun showUpdateNotification(
            project: Project,
            latestVersion: String,
            sweepPlugin: IdeaPluginDescriptor,
        ) {
            val isGatewayMode = SweepConstants.GATEWAY_MODE != SweepConstants.GatewayMode.NA
            val message =
                if (isGatewayMode) {
                    "A new version of Sweep ($latestVersion) is available. You are using Gateway mode, which requires a Gateway-specific version of Sweep. Please refer to the Gateway documentation at https://docs.sweep.dev/gateway for installation instructions."
                } else {
                    "A new major version of Sweep ($latestVersion) is available. Install and restart?"
                }

            val notification =
                NotificationGroupManager
                    .getInstance()
                    .getNotificationGroup("Sweep Version Update")
                    .createNotification(
                        "Sweep $latestVersion available",
                        message,
                        NotificationType.INFORMATION,
                    ).setIcon(SweepIcons.SweepIcon.scale(19f))
                    .addAction(
                        NotificationAction.createSimpleExpiring("Install && Restart") {
                            object : Backgroundable(project, "Installing Sweep Update", true) {
                                override fun run(indicator: ProgressIndicator) {
                                    try {
                                        val downloader = getSweepDownloader(sweepPlugin)
                                        if (!downloader.prepareToInstall(indicator)) {
                                            return
                                        }
                                        downloader.install()

                                        val metaData = SweepMetaData.getInstance()
                                        metaData.lastNotifiedVersion = latestVersion

                                        // Automatically restart after successful installation
                                        ApplicationManager.getApplication().invokeLater {
                                            ApplicationManagerEx.getApplicationEx().restart(true)
                                        }
                                    } catch (pce: ProcessCanceledException) {
                                        logger.info("Sweep update installation cancelled.")
                                        throw pce
                                    } catch (e: Exception) {
                                        logger.error("Failed to install Sweep update", e)
                                        ApplicationManager.getApplication().invokeLater {
                                            showNotification(
                                                project,
                                                "Update Failed",
                                                "Failed to install Sweep update: ${e.message}. Please upgrade from the Marketplace directly.",
                                                "Sweep Version Update",
                                            )
                                        }
                                    }
                                }
                            }.queue()
                        },
                    ).addAction(
                        NotificationAction.createSimpleExpiring("Later") {
                            // Just dismiss the notification
                        },
                    )

            notification.notify(project)

            // Auto-expire after 10 minutes
            AppExecutorUtil.getAppScheduledExecutorService().schedule({
                notification.expire()
            }, 10, TimeUnit.MINUTES)
        }
    }
}
