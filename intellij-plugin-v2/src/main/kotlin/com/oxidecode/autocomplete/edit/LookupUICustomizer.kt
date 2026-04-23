package com.oxidecode.autocomplete.edit

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.oxidecode.settings.OxideCodeMetaData
import java.awt.BorderLayout
import java.awt.Font
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Handles customization of IntelliJ's lookup (code completion) UI to add
 * a "Press enter to accept" message at the bottom of the dropdown.
 */
class LookupUICustomizer(
    private val project: Project,
) : Disposable {
    private var lookupCustomizations = mutableMapOf<Lookup, JComponent>()
    private var propertyChangeListener: PropertyChangeListener? = null

    /**
     * Initializes the lookup listener to monitor when lookups become active
     * and customize their UI accordingly.
     */
    fun initialize() {
        val lookupManager = LookupManager.getInstance(project)
        propertyChangeListener =
            PropertyChangeListener { event ->
                if (event.propertyName == LookupManager.PROP_ACTIVE_LOOKUP) {
                    val lookup = event.newValue as? Lookup
                    if (lookup is LookupImpl) {
                        val tracker = RecentEditsTracker.getInstance(project)
                        tracker.clearAutocomplete(AutocompleteDisposeReason.LOOKUP_SHOWN)
                        tracker.scheduleAutocompleteWithPrefetch()

                        // Customize the lookup UI when it becomes active
                        ApplicationManager.getApplication().invokeLater {
                            customizeLookupUI(lookup)
                        }
                    }
                }
            }
        lookupManager.addPropertyChangeListener(propertyChangeListener!!)
    }

    /**
     * Customizes the given lookup's UI by adding a footer message.
     *
     * @param lookup The lookup to customize
     */
    private fun customizeLookupUI(lookup: LookupImpl) {
        if (lookupCustomizations.containsKey(lookup)) return
        if (OxideCodeMetaData.getInstance().hasUsedLookupItem) return

        try {
            // Get the lookup component
            val lookupComponent = lookup.component

            if (lookupComponent is JPanel) {
                // Create the footer message
                val footerLabel =
                    JLabel("<html><b>Press enter to accept completion</b></html>", SwingConstants.CENTER).apply {
                        font = font.deriveFont(Font.BOLD, 12f)
                        border = JBUI.Borders.empty(4, 0)
                        foreground = JBColor.GRAY
                        background = lookupComponent.background
                        isOpaque = true
                    }

                // Add footer to the lookup component
                lookupComponent.add(footerLabel, BorderLayout.SOUTH)
                lookupComponent.revalidate()
                lookupComponent.repaint()

                // Track this customization
                lookupCustomizations[lookup] = footerLabel

                // Add listener to clean up when lookup is disposed
                val disposable =
                    Disposable {
                        lookupCustomizations.remove(lookup)
                        try {
                            if (lookupComponent.isAncestorOf(footerLabel)) {
                                lookupComponent.remove(footerLabel)
                                lookupComponent.revalidate()
                                lookupComponent.repaint()
                            }
                        } catch (e: Exception) {
                            // Ignore exceptions during cleanup
                        }
                    }

                Disposer.register(lookup, disposable)
            }
        } catch (e: Exception) {
            // Fail silently if UI customization doesn't work
            println("Failed to customize lookup UI: ${e.message}")
        }
    }

    override fun dispose() {
        // Property change listener will be cleaned up automatically when project is disposed
        propertyChangeListener = null

        // Clear customizations map (individual cleanup is handled by Disposer.register)
        lookupCustomizations.clear()
    }
}
