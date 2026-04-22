package com.oxidecode.theme

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil
import javax.swing.Icon

object OxideCodeIcons {
    private fun loadIcon(path: String): Icon = IconLoader.getIcon(path, OxideCodeIcons::class.java)

    val SweepIcon get() = loadIcon("/icons/sweep13x13.svg")
    val ChevronUp get() = loadIcon("/icons/chevronUp.svg")
    val ChevronDown get() = loadIcon("/icons/chevronDown.svg")
    val Close get() = loadIcon("/icons/close.svg")
    val SearchIcon get() = loadIcon("/icons/search_files_icon.svg")
    val ReadFileIcon get() = loadIcon("/icons/read_file_icon.svg")
    val EditIcon get() = IconLoader.getIcon("/icons/edit_icon.svg", OxideCodeIcons::class.java)
    val PlayIcon get() = AllIcons.Actions.Execute

    object FileType {
        val Python get() = loadIcon("/icons/python.svg")

        val Kotlin get() = loadIcon("/icons/kotlin.svg")

        val Cpp get() = loadIcon("/icons/cpp.svg")

        val Scala get() = loadIcon("/icons/scala.svg")

        val Rust get() = loadIcon("/icons/rust.svg")

        val Go get() = loadIcon("/icons/go.svg")

        val Csv get() = loadIcon("/icons/csv.svg")

        val GitIgnore get() = loadIcon("/icons/gitignore.svg")

        val Executable get() = loadIcon("/icons/terminal.svg")

        val Typescript get() = loadIcon("/icons/typescript.svg")
    }

    fun Icon.scale(targetSize: Float): Icon = IconUtil.scale(this, null, targetSize / iconWidth.toFloat())

    fun Icon.darker(factor: Int = 2): Icon = IconUtil.darker(this, factor)

    fun Icon.brighter(factor: Int = 2): Icon = IconUtil.brighter(this, factor)

}
