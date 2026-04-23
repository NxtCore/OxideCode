package com.oxidecode.theme

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil
import javax.swing.Icon

object OxideCodeIcons {
    private fun loadIcon(path: String): Icon = IconLoader.getIcon(path, OxideCodeIcons::class.java)

    val OxideCodeLogo get() = loadIcon("/icons/oxide_code_logo.svg")

    fun Icon.scale(targetSize: Float): Icon = IconUtil.scale(this, null, targetSize / iconWidth.toFloat())

    fun Icon.darker(factor: Int = 2): Icon = IconUtil.darker(this, factor)

    fun Icon.brighter(factor: Int = 2): Icon = IconUtil.brighter(this, factor)

}
