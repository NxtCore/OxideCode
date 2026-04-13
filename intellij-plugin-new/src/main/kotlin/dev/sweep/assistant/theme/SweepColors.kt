package dev.sweep.assistant.theme

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.utils.customBrighter
import dev.sweep.assistant.utils.customDarker
import dev.sweep.assistant.utils.withLightMode
import java.awt.Color

fun Color.withAlpha(alpha: Float) = Color(red, green, blue, (255 * alpha).toInt())

fun Color.withAlpha(alpha: Int) = Color(red, green, blue, alpha)

fun JBColor.withAlpha(alpha: Float) =
    JBColor(
        (this as Color).withAlpha(alpha),
        (this as Color).withAlpha(alpha),
    )

fun JBColor.withAlpha(alpha: Int) =
    JBColor(
        (this as Color).withAlpha(alpha),
        (this as Color).withAlpha(alpha),
    )

fun JBColor.withAlpha(
    lightAlpha: Float,
    darkAlpha: Float,
) = JBColor(
    (this as Color).withAlpha(lightAlpha),
    (this as Color).withAlpha(darkAlpha),
)

fun Color.withAlpha(
    lightAlpha: Float,
    darkAlpha: Float,
) = JBColor(
    this.withAlpha(lightAlpha),
    this.withAlpha(darkAlpha),
)

operator fun Color.plus(other: Color) =
    Color(
        (this.red + other.red) / 2,
        (this.green + other.green) / 2,
        (this.blue + other.blue) / 2,
        (this.alpha + other.alpha) / 2,
    )

object SweepColors {
    const val HOVER_COLOR_FACTOR = 0.9

    val transparent = Color(0, 0, 0, 0)
    val acceptedGlowColor = Color(117, 197, 144, 31)
    val acceptedHighlightColor = Color(117, 197, 144, 31)

    val whitespaceHighlightColor = Color(87, 255, 137, (255 * 0.06).toInt())

    val additionHighlightColor = Color(87, 255, 137, (255 * 0.14).toInt())
    val deletionHighlightColor get() = Color(255, 86, 91, (255 * 0.18).toInt())

    private fun calculateDynamicAlpha(): Int {
        // Calculate brightness of background (0-255 scale)
        // Dark -> 70
        // Darcula -> 71
        // Deep Ocean -> 79
        // High contrast -> 90
        val brightness = (backgroundColor.red * 0.299 + backgroundColor.green * 0.587 + backgroundColor.blue * 0.114).toInt()
        return (90 - brightness).coerceIn(70, 90)
    }

    val borderColor: Color
        get() = JBUI.CurrentTheme.Popup.borderColor(false)

    val activeBorderColor: JBColor
        get() =
            JBColor(
                borderColor.darker().withAlpha(0.2f),
                borderColor
                    .brighter()
                    .brighter()
                    .brighter()
                    .withAlpha(0.2f),
            )

    // Subtle border for file labels using theme colors
    val fileLabelBorder get() =
        JBUI.CurrentTheme.Popup
            .borderColor(false)
            .darker()

    val semanticColors =
        listOf(
            JBColor(
                Color(82, 122, 190),
                Color(73, 113, 181),
            ),
            JBColor(
                Color(190, 112, 112),
                Color(181, 103, 103),
            ),
            JBColor(
                Color(61, 118, 118),
                Color(52, 109, 109),
            ),
            JBColor(
                Color(190, 153, 112),
                Color(181, 144, 103),
            ),
            JBColor(
                Color(157, 82, 124),
                Color(148, 73, 115),
            ),
        )

    // Background color for UI elements
    val backgroundColor: Color
        get() = JBColor.background().darker().withLightMode()

    // Light grey background color for chat and user message components
    val chatAndUserMessageBackground: JBColor
        get() =
            JBColor(
                Gray._253, // Light mode: very light grey
                Gray._27, // Dark mode: darker than tool window, lighter than editor
            )

    // Dynamic property for active explanation block background
    val activeExplanationBlockBackgroundColor: JBColor
        get() =
            JBColor(
                Color.BLACK.withAlpha(0.05f),
                Color.WHITE.withAlpha(0.05f),
            )

    // Background color for inactive components - slightly darker than regular background
    val inactiveExplanationBlockBackgroundColor: JBColor
        get() =
            JBColor(
                backgroundColor.customDarker(0.1f),
                backgroundColor.customBrighter(0.05f),
            )

    // Dynamic tool window background color
    val toolWindowBackgroundColor: Color
        get() = JBColor.background()

    // Foreground color for text
    val foregroundColor: Color
        get() = JBColor.foreground()

    // Editor's default foreground color hex
    val editorForegroundColorHex get() =
        String.format(
            "%06x",
            EditorColorsManager
                .getInstance()
                .globalScheme.defaultForeground.rgb and 0xFFFFFF,
        )

    // Editor's default background color hex
    val editorBackgroundColorHex get() =
        String.format(
            "%06x",
            EditorColorsManager
                .getInstance()
                .globalScheme.defaultBackground.rgb and 0xFFFFFF,
        )

    val streamingColor =
        JBColor(
            Color(128, 128, 128, 50),
            Color(200, 200, 200, 50),
        )

    // Hex string representation of foreground color (without alpha)
    val foregroundColorHex get() = String.format("%06x", foregroundColor.rgb and 0xFFFFFF)

    val fileLabelBorderHex get() = String.format("%06x", fileLabelBorder.rgb and 0xFFFFFF)

    // Hex string representation of background color (without alpha)
    val backgroundColorHex get() = String.format("%06x", backgroundColor.rgb and 0xFFFFFF)

    // Background color for inline code blocks
    val codeBackgroundColor get() = backgroundColor.darker()

    // Hacky fix for High contrast but works
    val hoverableBackgroundColor get() =
        if (backgroundColor == Color.BLACK) {
            JBColor(
                Color(64, 64, 64),
                Color(64, 64, 64),
            )
        } else {
            backgroundColor
        }

    // Hex string representation of code background color (without alpha)
    val codeExplanationDisplayTextColor get() = "D1A8FE"

    // Send button color
    val sendButtonColor: JBColor
        get() =
            JBColor(
                ColorUtil.fromHex("#e8e6e6"),
                ColorUtil.fromHex("#414244"),
            )

    // Send button foreground color
    val sendButtonColorForeground: JBColor
        get() =
            JBColor(
                Gray._0,
                Gray._255,
            )

    // Ask mode blue colors (subtle blue)
    val askModeTextColor: JBColor
        get() =
            JBColor(
                Color(60, 130, 215), // Light mode: #3C82D7 (softer blue)
                Color(90, 150, 225), // Dark mode: #5A96E1 (softer blue)
            )

    // Ask mode semi-transparent background
    private val askModeBackgroundColor: JBColor
        get() =
            JBColor(
                Color(60, 130, 215, 20), // Light mode
                Color(90, 150, 225, 12), // Dark mode: blue with ~5% opacity
            )

    // Helper function to get mode-specific background color
    fun getModeBackgroundColor(mode: String): JBColor =
        when (mode.lowercase()) {
            "ask" -> askModeBackgroundColor
            "agent" -> sendButtonColor
            else -> sendButtonColor
        }

    // Helper function to get mode-specific text color
    fun getModeTextColor(mode: String): JBColor =
        when (mode.lowercase()) {
            "ask" -> askModeTextColor
            "agent" -> sendButtonColorForeground
            else -> sendButtonColorForeground
        }

    // Helper function to get mode-specific hover color
    fun getModeHoverColor(mode: String): JBColor {
        // Always use default gray hover color regardless of mode
        return createHoverColor(backgroundColor)
    }

    // Code block border color - static as it doesn't change with theme
    val codeBlockBorderColor: Color = ColorUtil.fromHex("#48494b")

    // Sweep rules accent color - static as it's a brand color
    val sweepRulesAccentColor: JBColor = JBColor(Color(88, 157, 246), Color(104, 159, 244))

    // Dropdown panel background based on current background
    val sweepDropdownPanelBackground: JBColor
        get() =
            JBColor(
                Gray._255,
                (backgroundColor.brighter() + backgroundColor),
            )

    val tooltipBackgroundColor = JBColor(0x7B9ADB, 0x486AA9)

    val listItemSelectionBackGround = JBColor(Color(172, 173, 175), Color(33, 35, 38))

    val backgroundTransparentColor =
        JBColor(
            Color(0, 0, 0, 0),
            Color(0, 0, 0, 0),
        )

    // GitHub button colors that adapt to light/dark themes
    val githubColor = JBColor(Color(36, 41, 47), Color(36, 41, 47)) // GitHub's brand color
    val textOnPrimary = JBColor(Color(255, 255, 255), Color(255, 255, 255)) // White text regardless of theme

    // Primary button blue color - used for accept buttons and other primary actions
    val primaryButtonColor = JBColor(Color(52, 116, 240, 255), Color(52, 116, 240, 255))
    val loginButtonColor = JBColor(Color(33, 150, 243, 255), Color(33, 150, 243, 255))

    // Subtle grey color for UI elements like token usage indicator and copy button
    val subtleGreyColor = JBColor(0x6E6E6E, 0x5A5D61)

    // Planning mode indicator text color
    val planningModeTextColor = primaryButtonColor

    // Blended text color for reasoning blocks and other subtle text (80% opacity blend)
    // This creates a softer text appearance by blending foreground with background
    val blendedTextColor: Color
        get() {
            val opacity = 0.8f
            return Color(
                (foregroundColor.red * opacity + backgroundColor.red * (1 - opacity)).toInt(),
                (foregroundColor.green * opacity + backgroundColor.green * (1 - opacity)).toInt(),
                (foregroundColor.blue * opacity + backgroundColor.blue * (1 - opacity)).toInt(),
            )
        }

    fun refreshColors() {
        // This method is now deprecated since all colors are dynamically computed.
        // Kept for backward compatibility but does nothing.
        // Components will automatically get updated colors through the dynamic properties.
    }

    fun colorToHex(color: Color): String = String.format("#%02x%02x%02x", color.red, color.green, color.blue)

    /**
     * Creates a hover effect color based on the background color
     */
    fun createHoverColor(background: Color): JBColor =
        JBColor(
            Color(
                (background.getRed() * HOVER_COLOR_FACTOR).toInt(),
                (background.getGreen() * HOVER_COLOR_FACTOR).toInt(),
                (background.getBlue() * HOVER_COLOR_FACTOR).toInt(),
            ),
            background.customBrighter(0.15f),
        )

    fun createHoverColor(
        background: Color,
        factor: Float = 0.1f,
    ): JBColor =
        JBColor(
            Color(
                (background.red * (1 - factor)).toInt().coerceIn(0, 255),
                (background.green * (1 - factor)).toInt().coerceIn(0, 255),
                (background.blue * (1 - factor)).toInt().coerceIn(0, 255),
            ),
            background.customBrighter(factor),
        )
}
