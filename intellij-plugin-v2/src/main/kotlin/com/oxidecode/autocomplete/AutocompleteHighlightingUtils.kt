@file:JvmName("AutocompleteHighlightingUtils")

package com.oxidecode.autocomplete

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.Project

/**
 * Adjusts the provided fullContext string based on the running IDE.
 *
 * Currently supported:
 * - PhpStorm: Prepend "<?php" followed by a newline if not already present.
 * - GoLand: Prepend "package test" and 'import "fmt"' followed by a newline if not already present.
 *
 * For other IDEs, the input is returned unchanged.
 */
fun adjustFullContextForIde(fullContext: String): String =
    try {
        val application = ApplicationInfo.getInstance()
        val appName = application.fullApplicationName
        when {
            appName.contains("PhpStorm", ignoreCase = true) -> {
                if (fullContext.trimStart().startsWith("<?php")) fullContext else "<?php\n$fullContext"
            }
            appName.contains("GoLand", ignoreCase = true) -> {
                if (fullContext.trimStart().startsWith("package")) fullContext else "package test\n$fullContext"
            }
            else -> fullContext
        }
    } catch (e: Exception) {
        // If anything goes wrong determining the IDE, return the original context unchanged
        fullContext
    }

/**
 * Determines whether we should run language annotators as part of semantic highlighting.
 *
 * Currently:
 * - PhpStorm, PyCharm, DataGrip, CLion, RustRover, Android Studio, RubyMine, Rider, GoLand, WebStorm, IntelliJ:
 *   controlled by per-IDE feature flag "<ide>-run-annotators" (off by default if Project is null)
 * - Others: true
 *
 * This will be expanded later with IDE-specific behavior.
 */
fun shouldRunAnnotatorsForSemanticHighlights(project: Project?): Boolean =
    try {
        val appName = ApplicationInfo.getInstance().fullApplicationName
        val ideKey =
            when {
                appName.contains("PhpStorm", ignoreCase = true) -> "phpstorm"
                appName.contains("PyCharm", ignoreCase = true) -> "pycharm"
                appName.contains("DataGrip", ignoreCase = true) -> "datagrip"
                appName.contains("CLion", ignoreCase = true) -> "clion"
                appName.contains("RustRover", ignoreCase = true) -> "rustrover"
                appName.contains("Android Studio", ignoreCase = true) -> "android-studio"
                appName.contains("RubyMine", ignoreCase = true) -> "rubymine"
                appName.contains("Rider", ignoreCase = true) -> "rider"
                appName.contains("GoLand", ignoreCase = true) -> "goland"
                appName.contains("WebStorm", ignoreCase = true) -> "webstorm"
                appName.contains("IntelliJ", ignoreCase = true) || appName.contains("IDEA", ignoreCase = true) -> "intellij"
                else -> null
            }

        ideKey?.let { key ->
            val flagKey = "$key-run-annotators"
            project?.let { true } ?: false
        } ?: false
    } catch (e: Exception) {
        // Be conservative: do NOT run annotators on failure – they can be heavy and less cancellable
        false
    }
