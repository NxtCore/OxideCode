package com.oxidecode.autocomplete

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Font
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

@Service(Service.Level.PROJECT)
class LookupUiCustomizer(private val project: Project) : Disposable {
    private val lookupCustomizations = linkedMapOf<Lookup, JComponent>()
    private val propertyChangeListener = PropertyChangeListener(::onLookupChanged)

    init {
        LookupManager.getInstance(project).addPropertyChangeListener(propertyChangeListener)
    }

    private fun onLookupChanged(event: PropertyChangeEvent) {
        if (event.propertyName != "activeLookup") return
        val lookup = event.newValue as? LookupImpl ?: return
        ApplicationManager.getApplication().invokeLater { customizeLookupUi(lookup) }
    }

    private fun customizeLookupUi(lookup: LookupImpl) {
        if (lookupCustomizations.containsKey(lookup)) return
        val lookupComponent = lookup.component as? JPanel ?: return

        val footerLabel = JLabel("<html><b>Press enter to accept completion</b></html>", JLabel.CENTER).apply {
            font = font.deriveFont(Font.BOLD, 12f)
            border = JBUI.Borders.empty(4, 0)
            foreground = JBColor.GRAY
            background = lookupComponent.background
            isOpaque = true
        }

        lookupComponent.add(footerLabel, "South")
        lookupComponent.revalidate()
        lookupComponent.repaint()
        lookupCustomizations[lookup] = footerLabel

        Disposer.register(lookup) {
            lookupCustomizations.remove(lookup)
            if (lookupComponent.isAncestorOf(footerLabel)) {
                lookupComponent.remove(footerLabel)
                lookupComponent.revalidate()
                lookupComponent.repaint()
            }
        }
    }

    override fun dispose() {
        lookupCustomizations.clear()
    }
}
