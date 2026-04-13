package dev.sweep.assistant.e2e

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.data.RemoteComponent
import com.intellij.remoterobot.fixtures.*
import com.intellij.remoterobot.search.locators.byXpath
import java.time.Duration

@DefaultXpath(by = "JPanel type", xpath = "//div[@class='IdeFrameImpl']")
class MainWindow(
    remoteRobot: RemoteRobot,
    remoteComponent: RemoteComponent,
) : ContainerFixture(remoteRobot, remoteComponent) {
    val currentEditorFixture: EditorFixture?
        get() = findOrNull<EditorFixture>(EditorFixture.locator)

    val currentlyEditedFile: ComponentFixture
        get() = find(byXpath("//div[@class='EditorCompositePanel']//div[@class='EditorComponentImpl']"))

    val currentlyEditedFileText = currentlyEditedFile.data.getAll().joinToString("") { it.text }
}

val EditorFixture.acceptButton: ComponentFixture?
    get() = findOrNull<ComponentFixture>(byXpath("//div[contains(@text, 'Accept')]"))

val EditorFixture.rejectButton: ComponentFixture?
    get() = findOrNull<ComponentFixture>(byXpath("//div[contains(@text, 'Reject')]"))

fun fileHeaderXPath(text: String) = byXpath("//div[@class='SimpleColoredComponent' and @visible_text='$text']")

@DefaultXpath(
    by = "JPanel type",
    xpath = "//div[@class='MarkdownDisplay']",
)
class MarkdownDisplayFixture(
    remoteRobot: RemoteRobot,
    remoteComponent: RemoteComponent,
) : ContainerFixture(remoteRobot, remoteComponent) {
    val explanationBlocks: List<JTextAreaFixture>
        get() = findAll(byXpath("//div[@class='JTextPane']"))

    val allTextsString
        get() = explanationBlocks.joinToString("\n") { it.text }
}

@DefaultXpath(
    by = "JPanel type",
    xpath =
        "//div[contains(@class,'InternalDecoratorImpl')]" +
            "//div[contains(@class,'BaseLabel') and @visible_text='Sweep AI']" +
            "/ancestor::div[contains(@class,'InternalDecoratorImpl')][1]",
)
class ToolWindow(
    remoteRobot: RemoteRobot,
    remoteComponent: RemoteComponent,
) : ContainerFixture(remoteRobot, remoteComponent) {
    private val defaultTimeout = Duration.ofSeconds(10)
    val sweepAI: ComponentFixture
        get() = find(byXpath("//div[@class='BaseLabel']"), timeout = getAdjustedTimeout(defaultTimeout))

    val newChatButton: ComponentFixture
        get() = find(byXpath("//div[@text='New Chat']"), timeout = getAdjustedTimeout(defaultTimeout))

    val chatInput: ComponentFixture?
        get() = findOrNull(byXpath("//div[contains(@myemptytext, 'Ask Sweep')]"))

    val userMessages: List<ComponentFixture>
        get() = findAll(byXpath("//div[@class='UserMessageComponent']"))

    val assistantMessages: List<MarkdownDisplayFixture>
        get() = findAll(byXpath("//div[@class='MarkdownDisplay']"))

    val firstUserMessage: ComponentFixture
        get() = find(byXpath("//div[@myemptytext='What does this file do?']"), timeout = getAdjustedTimeout(defaultTimeout))

    // this could be flaky, if it is search by text instead of timestamp
    val recentChats: List<ComponentFixture>
        get() = findAll(byXpath("//div[@class='TruncatedLabel']"))

    val copyButtonFromApply: ComponentFixture?
        get() = findOrNull(byXpath("//div[@text='Copy']"))

    val applyButton: ComponentFixture?
        get() = findOrNull(byXpath("//div[@text='Apply']"))

    val openSettings: ComponentFixture?
        get() = find(byXpath("//div[@class='ActionLink']"))
}

@DefaultXpath(by = "JFileSelector type", xpath = "//div[@class='ProjectViewTree']")
class ProjectFileTree(
    remoteRobot: RemoteRobot,
    remoteComponent: RemoteComponent,
) : ComponentFixture(remoteRobot, remoteComponent)

@DefaultXpath(by = "JPanel type", xpath = "//div[@class='MyDialog']")
class SettingsPage(
    remoteRobot: RemoteRobot,
    remoteComponent: RemoteComponent,
) : ContainerFixture(remoteRobot, remoteComponent) {
    private val defaultTimeout = Duration.ofSeconds(10)

    @DefaultXpath(by = "JButton type", xpath = "//div[@class='ActionLink']")
    class OpenButton(
        remoteRobot: RemoteRobot,
        remoteComponent: RemoteComponent,
    ) : ContainerFixture(remoteRobot, remoteComponent)

    val githubPatField: ComponentFixture
        get() = find(byXpath("//div[@class='JPasswordField']"), timeout = getAdjustedTimeout(defaultTimeout))

    val baseUrlField: ComponentFixture
        get() = find(byXpath("//div[@class='JTextField']"), timeout = getAdjustedTimeout(defaultTimeout))

    val applyButton: ComponentFixture
        get() = find(byXpath("//div[@text='Apply']"), timeout = getAdjustedTimeout(defaultTimeout))

    val okButton: ComponentFixture
        get() = find(byXpath("//div[@text='OK']"), timeout = getAdjustedTimeout(defaultTimeout))
}

@DefaultXpath(by = "type", xpath = "//div[@class='MyComponent'][.//div[@class='LinkLabel']]")
class DevKitPopup(
    remoteRobot: RemoteRobot,
    remoteComponent: RemoteComponent,
) : ContainerFixture(remoteRobot, remoteComponent) {
    val closeButton: ComponentFixture
        get() = find(byXpath("//div[@class='LinkLabel']"))
}

@DefaultXpath(by = "type", xpath = "//div[@class='HeavyWeightWindow']")
class IDESearchWindow(
    remoteRobot: RemoteRobot,
    remoteComponent: RemoteComponent,
) : ContainerFixture(remoteRobot, remoteComponent) {
    private val defaultTimeout = Duration.ofSeconds(10)
    val helloWorldResult: ComponentFixture
        get() = find(byXpath("//div[@class='JBList']"), timeout = getAdjustedTimeout(defaultTimeout))

    val rejectButtonAll: ComponentFixture
        get() = find(byXpath("//div[@text='✗ Reject All']"))
}
