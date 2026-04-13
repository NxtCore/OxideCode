package dev.sweep.assistant.theme

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.IconUtil
import dev.sweep.assistant.theme.SweepIcons.FileType.Cpp
import dev.sweep.assistant.theme.SweepIcons.FileType.Csv
import dev.sweep.assistant.theme.SweepIcons.FileType.Executable
import dev.sweep.assistant.theme.SweepIcons.FileType.GitIgnore
import dev.sweep.assistant.theme.SweepIcons.FileType.Go
import dev.sweep.assistant.theme.SweepIcons.FileType.Kotlin
import dev.sweep.assistant.theme.SweepIcons.FileType.Python
import dev.sweep.assistant.theme.SweepIcons.FileType.Rust
import dev.sweep.assistant.theme.SweepIcons.FileType.Scala
import dev.sweep.assistant.theme.SweepIcons.FileType.Typescript
import dev.sweep.assistant.utils.osBasePath
import java.awt.*
import java.io.File
import javax.swing.Icon
import javax.swing.Timer

object SweepIcons {
    private fun loadIcon(path: String): Icon = IconLoader.getIcon(path, SweepIcons::class.java)

    // Using getter properties instead of val to prevent memory leaks during dynamic plugin unload
    // IconLoader internally caches these, so performance is not affected
    val Plus get() = loadIcon("/icons/plus.svg")
    val At get() = loadIcon("/icons/at.svg")
    val SweepIcon get() = loadIcon("/icons/oxidecode13x13.svg")
    val Sweep16x16 get() = loadIcon("/icons/oxidecode16x16.svg")
    val BigSweepIcon get() = loadIcon("/icons/oxidecode.svg")
    val SendArrow get() = loadIcon("/icons/sendArrow.svg")
    val SendUpArrow get() = loadIcon("/icons/sendUpArrow.svg")
    val Enter get() = loadIcon("/icons/enter.svg").scale(12f)
    val DownArrow get() = loadIcon("/icons/downArrow.svg")
    val UpArrow get() = loadIcon("/icons/upArrow.svg")
    val ChevronUp get() = loadIcon("/icons/chevronUp.svg")
    val ChevronDown get() = loadIcon("/icons/chevronDown.svg")
    val Close get() = loadIcon("/icons/close.svg")
    val GithubIcon get() = IconLoader.getIcon("/icons/github.svg", SweepIcons::class.java)
    val SearchIcon get() = loadIcon("/icons/search_files_icon.svg")
    val ReadFileIcon get() = loadIcon("/icons/read_file_icon.svg")
    val ListFilesIcon get() = loadIcon("/icons/list_files_icon.svg")
    val ErrorIcon get() = loadIcon("/icons/error_icon.svg")
    val ErrorWarningIcon get() = loadIcon("/icons/error_warning.svg")
    val EditIcon get() = IconLoader.getIcon("/icons/edit_icon.svg", SweepIcons::class.java)
    val ProcessIcon get() = loadIcon("/icons/process_icon.svg")
    val BroomIcon get() = loadIcon("/icons/broom_icon.svg")
    val BashIcon get() = loadIcon("/icons/bash.svg")
    val ExpandAllIcon get() = loadIcon("/icons/expandAll.svg")
    val CollapseAllIcon get() = loadIcon("/icons/collapseAll.svg")
    val RefreshIcon get() = loadIcon("/icons/refresh.svg")
    val EyeIcon get() = AllIcons.Actions.Preview
    val PlayIcon get() = AllIcons.Actions.Execute

    // Todo status icons
    val TodoToolIcon get() = loadIcon("/icons/todo_tool_icon.svg")
    val TodoPendingIcon get() = loadIcon("/icons/todo_pending.svg")
    val TodoInProgressIcon get() = loadIcon("/icons/todo_in_progress.svg")
    val TodoCompletedIcon get() = loadIcon("/icons/todo_completed.svg")
    val TodoCancelledIcon get() = loadIcon("/icons/todo_cancelled.svg")

    // Todo status icons for headers (full opacity)
    val TodoPendingHeaderIcon get() = loadIcon("/icons/todo_pending_header.svg")
    val TodoInProgressHeaderIcon get() = loadIcon("/icons/todo_in_progress_header.svg")
    val TodoCompletedHeaderIcon get() = loadIcon("/icons/todo_completed_header.svg")
    val TodoCancelledHeaderIcon get() = loadIcon("/icons/todo_cancelled_header.svg")
    val ChatBubbleIcon get() = loadIcon("/icons/chatBubble.svg")
    val ImageUpload get() = loadIcon("/icons/imageUpload.svg")
    val WebSearch get() = loadIcon("/icons/webSearch.svg")
    val WebSearchBlue get() = loadIcon("/icons/webSearchBlue.svg")
    val UserIcon get() = loadIcon("/icons/user.svg")
    val SkillIcon get() = loadIcon("/icons/skill_icon.svg")

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

        val Jupyter get() = loadIcon("/icons/jupyterNotebook.svg")

        val Typescript get() = loadIcon("/icons/typescript.svg")
    }

    object EntityIcons {
        val classIcon get() = IconLoader.getIcon("/icons/class.svg", SweepIcons::class.java)

        val enumIcon get() = IconLoader.getIcon("/icons/enum.svg", SweepIcons::class.java)

        val functionIcon get() = IconLoader.getIcon("/icons/function.svg", SweepIcons::class.java)

        val interfaceIcon get() = IconLoader.getIcon("/icons/interface.svg", SweepIcons::class.java)

        val objectIcon get() = IconLoader.getIcon("/icons/object.svg", SweepIcons::class.java)

        val propertyIcon get() = IconLoader.getIcon("/icons/property.svg", SweepIcons::class.java)

        val naIcon get() = IconLoader.getIcon("/icons/na.svg", SweepIcons::class.java)
    }

    fun iconForFile(
        project: Project,
        file: File,
        size: Float,
    ): Icon =
        run {
            if (File(project.osBasePath, file.path).isDirectory) {
                AllIcons.Nodes.Folder
            } else if (file.extension == "py") {
                Python
            } else if (file.extension in setOf("kt", "kts")) {
                Kotlin
            } else if (file.extension in setOf("ts", "tsx")) {
                Typescript
            } else if (file.extension in setOf("js", "jsx")) {
                AllIcons.FileTypes.JavaScript
            } else if (file.name in setOf("html", "htm", "htmlx")) {
                AllIcons.FileTypes.Html
            } else if (file.name in setOf("css", "scss", "sass", "less")) {
                AllIcons.FileTypes.Css
            } else if (file.extension == "go") {
                Go
            } else if (file.extension in setOf("sbt", "scala", "sc")) {
                Scala
            } else if (file.extension in setOf("java", "class")) {
                AllIcons.FileTypes.Java
            } else if (file.extension in setOf("c", "cpp", "cc", "cxx", "h", "hpp")) {
                Cpp
            } else if (file.extension == "rs") {
                Rust
            } else if (file.extension in setOf("json", "jsonc")) {
                AllIcons.FileTypes.Json
            } else if (file.extension in setOf("xml", "xsd", "xsl")) {
                AllIcons.FileTypes.Xml
            } else if (file.extension in setOf("yml", "yaml")) {
                AllIcons.FileTypes.Yaml
            } else if (file.extension in setOf("csv", "tsv")) {
                Csv
            } else if (file.name == ".gitignore") {
                GitIgnore
            } else if (File(project.osBasePath, file.path).canExecute()) {
                Executable
            } else {
                AllIcons.FileTypes.Text
            }
        }.scale(size)

    fun Icon.scale(targetSize: Float): Icon = IconUtil.scale(this, null, targetSize / iconWidth.toFloat())

    fun Icon.darker(factor: Int = 2): Icon = IconUtil.darker(this, factor)

    fun Icon.brighter(factor: Int = 2): Icon = IconUtil.brighter(this, factor)

    class LoadingIcon : Icon {
        private var frame = 0
        private var timer: Timer? = null
        private val dotCount = 12
        private var component: Component? = null

        override fun paintIcon(
            c: Component?,
            g: Graphics,
            x: Int,
            y: Int,
        ) {
            component = c
            val g2 =
                (g.create() as Graphics2D).apply {
                    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                }

            val centerX = x + iconWidth / 2
            val centerY = y + iconHeight / 2
            val radius = 6

            for (i in 0 until dotCount) {
                val angle = 2.0 * Math.PI * i / dotCount
                val dotX = centerX + (radius * Math.cos(angle)).toInt()
                val dotY = centerY + (radius * Math.sin(angle)).toInt()

                val distance = (i - frame + dotCount) % dotCount
                val alpha = ((dotCount - distance) * 255 / dotCount).coerceIn(30, 255)

                g2.color = JBColor(Color(128, 128, 128, alpha), Color(169, 169, 169, alpha))
                g2.fillOval(dotX - 1, dotY - 1, 2, 2)
            }
            g2.dispose()
        }

        override fun getIconWidth() = 16

        override fun getIconHeight() = 16

        fun start() {
            timer?.stop()
            timer =
                Timer(80) {
                    frame = (frame + 1) % dotCount
                    ApplicationManager.getApplication().invokeLater {
                        component?.repaint()
                    }
                }.apply {
                    start()
                }
        }

        fun stop() {
            timer?.stop()
            timer = null
        }
    }
}
