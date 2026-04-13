package dev.sweep.assistant.services

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.sweep.assistant.utils.SweepConstants

/**
 * Service responsible for handling JetBrains Gateway onboarding notifications.
 * Shows appropriate messages to users based on whether they're running on the Gateway client or host.
 */
@Service(Service.Level.APP)
class GatewayOnboardingService : Disposable {
    companion object {
        fun getInstance(): GatewayOnboardingService = ApplicationManager.getApplication().getService(GatewayOnboardingService::class.java)
    }

    /**
     * Shows the appropriate Gateway onboarding notification based on the current mode.
     * Only shows the notification once per mode.
     */
    fun showGatewayOnboardingIfNeeded(project: Project) {
        val gatewayMode = SweepConstants.GATEWAY_MODE

        when (gatewayMode) {
            SweepConstants.GatewayMode.CLIENT -> {
                showClientOnboardingIfNeeded(project)
            }
            SweepConstants.GatewayMode.HOST -> {
                showHostOnboardingIfNeeded(project)
            }
            SweepConstants.GatewayMode.NA -> {
                // No onboarding for non-Gateway modes
            }
        }
    }

    private fun showClientOnboardingIfNeeded(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            showGatewayNotification(
                project = project,
                title = SweepConstants.GATEWAY_CLIENT_ONBOARDING_TITLE,
                message = SweepConstants.GATEWAY_CLIENT_ONBOARDING_MESSAGE,
            )
        }
    }

    private fun showHostOnboardingIfNeeded(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            showGatewayNotification(
                project = project,
                title = SweepConstants.GATEWAY_HOST_ONBOARDING_TITLE,
                message = SweepConstants.GATEWAY_HOST_ONBOARDING_MESSAGE,
            )
        }
    }

    private fun showGatewayNotification(
        project: Project,
        title: String,
        message: String,
    ) {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup("Sweep AI Notifications")
            .createNotification(
                title = title,
                content = message,
                type = NotificationType.INFORMATION,
            ).setImportant(true)
            .notify(project)
    }

    override fun dispose() {
        // Nothing to dispose of currently, but implementing Disposable for consistency
    }
}
