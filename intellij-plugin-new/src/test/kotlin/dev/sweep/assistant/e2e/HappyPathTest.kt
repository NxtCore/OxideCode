package dev.sweep.assistant.e2e

import com.automation.remarks.junit5.Video
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.utils.keyboard
import com.intellij.remoterobot.utils.waitFor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.event.KeyEvent
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class HappyPathTest {
    private val url: String = System.getProperty("remote-robot-url") ?: "http://127.0.0.1:8082"
    private val robot = waitForRobot(url)
    private val defaultTimeout = Duration.ofSeconds(15)

    private var setupSuccessful = false

    @Test
    @Order(1)
    @Video
    fun setup() {
        try {
            with(robot) {
                openFile("HelloWorld.kts")
                openChat()
                with(find<ToolWindow>()) {
                    if (openSettings != null) {
                        configurePluginSettings()
                    }
                    chatInput shouldNotBe null
                }
                findOrNull<DevKitPopup>()?.closeButton?.click()
            }
            setupSuccessful = true
        } catch (e: Throwable) {
            setupSuccessful = false
            throw e
        }
    }

    @Test
    @Order(2)
    @Video
    fun testHappyPath() {
        assumeTrue(setupSuccessful) { "Setup was not successful, skipping test" }
        with(robot.find<ToolWindow>()) {
            sendChat("What does this file do?")
            userMessages.size shouldBe 1
            waitFor(getAdjustedTimeout(defaultTimeout)) {
                (assistantMessages.size < 2) shouldBe true
                val assistantMessage = assistantMessages.lastOrNull()
                return@waitFor (assistantMessage?.allTextsString?.length ?: 0) > 200
            }
            sendChat("Tell me more.")
            waitFor(getAdjustedTimeout(defaultTimeout)) {
                (assistantMessages.size in 2..3) shouldBe true
                if (assistantMessages.size == 1) return@waitFor false
                val assistantMessage = assistantMessages.lastOrNull()
                return@waitFor (assistantMessage?.allTextsString?.length ?: 0) > 200
            }
            reSendChat()
            waitFor(getAdjustedTimeout(defaultTimeout)) {
                userMessages.size shouldBe 1
                (assistantMessages.size < 2) shouldBe true
                val assistantMessage = assistantMessages.lastOrNull()
                return@waitFor (assistantMessage?.allTextsString?.length ?: 0) > 200
            }
        }
    }

    @Test
    @Order(3)
    @Video
    fun testOpenRecentChat() {
        assumeTrue(setupSuccessful) { "Setup was not successful, skipping test" }
        with(robot.find<ToolWindow>()) {
            openNewChat()
            waitFor(getAdjustedTimeout(defaultTimeout)) {
                userMessages.size shouldBe 0
                (assistantMessages.isEmpty()) shouldBe true
            }
            openPreviousChat()
            waitFor(getAdjustedTimeout(defaultTimeout)) {
                userMessages.size shouldBe 1
                (assistantMessages.size == 1) shouldBe true
            }
            sendChat("Add doc strings to the main function.")
            waitFor(getAdjustedTimeout(defaultTimeout)) {
                val userMessageCheck = userMessages.size == 2
                val assistantMessageCheck = assistantMessages.size == 2
                val copyButtonCheck = copyButtonFromApply?.isShowing == true

                userMessageCheck && assistantMessageCheck && copyButtonCheck
            }
        }
    }

    @Test
    @Order(4)
    @Video
    fun testApplyWorkflow() {
        assumeTrue(setupSuccessful) { "Setup was not successful, skipping test" }
        with(robot) {
            with(find<ToolWindow>()) {
                openNewChat()
                sendChat("Change this file to goodbye world.")
                userMessages.size shouldBe 1
                waitFor(getAdjustedTimeout(defaultTimeout)) {
                    (assistantMessages.size == 1) shouldBe true
                    return@waitFor applyButton != null
                }
                applyButton?.click()
            }
            with(find<MainWindow>()) {
                currentEditorFixture?.also { editor ->
                    editor.text shouldContain "println(\"Goodbye World"
                    with(find<IDESearchWindow>()) {
                        rejectButtonAll.click()
                    }
                    editor.text shouldContain "println(\"Hello World"
                    editor.text shouldNotContain "println(\"Goodbye World"
                } shouldNotBe null
            }
        }
    }

    private fun configurePluginSettings() =
        with(robot) {
            findOrNull<SettingsPage.OpenButton>()?.click() ?: return@with

            with(find<SettingsPage>(timeout = Duration.ofSeconds(300))) {
                val githubPat =
                    System.getenv("GITHUB_PAT")?.takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException("GITHUB_PAT environment variable not set or empty")
                githubPatField.click()
                waitFor(getAdjustedTimeout(Duration.ofSeconds(1))) {
                    githubPatField.isFocusOwner
                }
                keyboard {
                    set(githubPat)
                }
                baseUrlField.click()
                waitFor(getAdjustedTimeout(Duration.ofSeconds(1))) {
                    baseUrlField.isFocusOwner
                }
                keyboard {
                    set("https://backend.app.sweep.dev")
                }
                applyButton.click()
                okButton.click()
            }

            exists<SettingsPage>() shouldBe false
        }

    private fun openFile(filePath: String) =
        with(robot) {
            if (findOrNull<ComponentFixture>(fileHeaderXPath(filePath)) != null) return
            keyboard {
                val shortcut = getOpenFileShortcut()
                executeHotkeys(shortcut)
                waitFor(getAdjustedTimeout(Duration.ofSeconds(1))) {
                    true
                }
                set(filePath)
                waitFor(getAdjustedTimeout(Duration.ofSeconds(10))) {
                    with(find<IDESearchWindow>()) {
                        helloWorldResult.isShowing shouldBe true
                    }
                }
                find<IDESearchWindow>().helloWorldResult.click()
            }
        }

    private fun toggleFocusChat() =
        with(robot) {
            keyboard {
                executeHotkeys(listOf(getControlKey(), KeyEvent.VK_J))
            }
        }

    private fun openChat() =
        with(robot) {
            if (!exists<ToolWindow>()) {
                toggleFocusChat()
            }
        }

    private fun createNewChat() =
        with(robot) {
            with(find<ToolWindow>()) {
                click()
                if (userMessages.isNotEmpty()) {
                    newChatButton.click()
                }
                userMessages.size shouldBe 0
            }
        }

    private fun sendChat(text: String) =
        with(robot) {
//            openChat()
            with(find<ToolWindow>()) {
                chatInput?.click()
                keyboard {
                    set(text)
                    enter()
                }
            }
        }

    private fun reSendChat() =
        with(robot) {
            with(find<ToolWindow>()) {
                firstUserMessage.click()
                keyboard {
                    enter()
                }
            }
        }

    private fun openPreviousChat() =
        with(robot) {
            with(find<ToolWindow>()) {
                recentChats.first().click()
            }
        }

    private fun openNewChat() =
        with(robot) {
            with(find<ToolWindow>()) {
                newChatButton.click()
                userMessages.size shouldBe 0
            }
        }
}
