package dev.sweep.assistant.e2e

import com.intellij.openapi.util.SystemInfo
import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ContainerFixture
import com.intellij.remoterobot.fixtures.Fixture
import com.intellij.remoterobot.search.locators.Locator
import com.intellij.remoterobot.utils.Keyboard
import com.intellij.remoterobot.utils.waitFor
import java.awt.event.KeyEvent
import java.io.File
import java.time.Duration
import javax.imageio.ImageIO

inline fun <reified T : Fixture> ContainerFixture.exists() = findAll(T::class.java).isNotEmpty()

inline fun <reified T : Fixture> ContainerFixture.findOrNull() = findAll(T::class.java).firstOrNull()

inline fun <reified T : Fixture> ContainerFixture.findOrNull(locator: Locator) = findAll(T::class.java, locator).firstOrNull()

inline fun <reified T : Fixture> ContainerFixture.exists(locator: Locator) = findAll(T::class.java, locator).isNotEmpty()

inline fun <reified T : Fixture> RemoteRobot.exists() = findAll(T::class.java).isNotEmpty()

inline fun <reified T : Fixture> RemoteRobot.exists(locator: Locator) = findAll(T::class.java, locator).isNotEmpty()

inline fun <reified T : Fixture> RemoteRobot.findOrNull() = findAll(T::class.java).firstOrNull()

inline fun <reified T : Fixture> RemoteRobot.findOrNull(locator: Locator) = findAll(T::class.java, locator).firstOrNull()

fun RemoteRobot.saveScreenshot(fileName: String) = ImageIO.write(getScreenshot(), "png", File("build/reports", "$fileName.png"))

fun Keyboard.clear() {
    selectAll()
    backspace()
}

fun Keyboard.enterTextFast(text: String) {
    val clipboard =
        java.awt.Toolkit
            .getDefaultToolkit()
            .systemClipboard
    val stringSelection = java.awt.datatransfer.StringSelection(text)
    val oldClipboardContents = clipboard.getContents(null)
    clipboard.setContents(stringSelection, null)
    with(this) {
        executeHotkeys(listOf(getControlKey(), KeyEvent.VK_V))
    }
    clipboard.getContents(oldClipboardContents)
}

fun Keyboard.set(text: String) {
    clear()
    // it is possible in headless ci that it is not fully cleared right away
    waitFor(getAdjustedTimeout(Duration.ofSeconds(1))) { true }
    if (isInGHA() && !SystemInfo.isMac) {
        enterText(text)
    } else {
        enterTextFast(text)
    }
}

fun waitForRobot(url: String): RemoteRobot {
    var robot: RemoteRobot? = null
    waitFor(Duration.ofSeconds(30)) {
        try {
            robot = RemoteRobot(url)
            return@waitFor true
        } catch (e: Exception) {
            return@waitFor false
        }
    }
    return robot ?: throw IllegalStateException("Could not connect to robot at $url")
}

fun getControlKey(): Int =
    if (SystemInfo.isMac) {
        KeyEvent.VK_META
    } else {
        KeyEvent.VK_CONTROL
    }

fun getOpenFileShortcut(): List<Int> =
    if (SystemInfo.isMac) {
        listOf(KeyEvent.VK_META, KeyEvent.VK_SHIFT, KeyEvent.VK_O)
    } else {
        listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_N)
    }

fun isInGHA(): Boolean = System.getenv("GITHUB_ACTIONS") == "true"

// scale timeout based on if it is running in gha or not
fun getAdjustedTimeout(baseTimeout: Duration): Duration =
    if (isInGHA()) {
        baseTimeout.multipliedBy(3)
    } else {
        baseTimeout
    }

// to help execute hotkeys
fun Keyboard.executeHotkeys(keys: List<Int>) {
    when (keys.size) {
        2 -> hotKey(keys[0], keys[1])
        3 -> hotKey(keys[0], keys[1], keys[2])
        else -> throw IllegalStateException("Unexpected shortcut combination")
    }
}
