package com.oxidecode.utils

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.SlowOperations
import com.intellij.util.ui.JBUI
import com.oxidecode.theme.OxideCodeIcons.scale
import java.awt.*
import java.awt.event.*
import java.io.File
import java.text.Normalizer
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.Border
import javax.swing.border.EmptyBorder
import kotlin.math.abs
import kotlin.math.min

fun Container.hasAnyFocusedDescendant(): Boolean {
    val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false

    if (focusOwner == this) return true

    for (component in components) {
        if (component is Container) {
            if (component.hasAnyFocusedDescendant()) return true
        } else if (component == focusOwner) {
            return true
        }
    }
    return false
}

val Component.ancestors get() = generateSequence(this) { it.parent }

var Component.identifier: Any?
    get() = (this as? JComponent)?.getClientProperty("identifier")
    set(value) = (this as? JComponent)?.putClientProperty("identifier", value) ?: Unit

fun Component.walk(): Sequence<Component> =
    sequence {
        yield(this@walk)

        if (this@walk is Container) {
            for (child in this@walk.components) {
                yieldAll(child.walk())
            }
        }
    }

fun Container.onDescendantAdded(callback: Component.() -> Unit) {
    // runs callback whenever a descendent is added
    // each descendent will add to their own descendent as well
    // i checked with o1, this should be bug free

    lateinit var adaptor: ContainerAdapter
    adaptor =
        object : ContainerAdapter() {
            override fun componentAdded(e: ContainerEvent) {
                e.child.walk().forEach {
                    it.callback()
                    (it as? Container)?.addContainerListener(adaptor)
                }
            }

            override fun componentRemoved(e: ContainerEvent) {
                e.child.walk().filterIsInstance<Container>().forEach {
                    it.removeContainerListener(adaptor)
                }
            }
        }
    walk()
        .filterIsInstance<Container>()
        .forEach {
            it.addContainerListener(adaptor)
        }
}

fun Container.addMouseListenerRecursive(adapter: MouseAdapter) {
    walk().forEach { it.addMouseListener(adapter) }
    onDescendantAdded { addMouseListener(adapter) }
}

fun Container.removeMouseListenerRecursive(adapter: MouseAdapter) {
    walk().forEach { it.removeMouseListener(adapter) }
}

fun Container.addFocusListenerRecursive(adaptor: FocusAdapter) {
    walk().forEach { it.addFocusListener(adaptor) }
    onDescendantAdded { addFocusListener(adaptor) }
}

fun Container.removeFocusListenerRecursive(adaptor: FocusAdapter) {
    walk().forEach { it.removeFocusListener(adaptor) }
}

fun Container.addKeyListenerRecursive(adaptor: KeyAdapter) {
    walk().forEach { it.addKeyListener(adaptor) }
    onDescendantAdded { addKeyListener(adaptor) }
}

fun Container.removeKeyListenerRecursive(adaptor: KeyAdapter) {
    walk().forEach { it.removeKeyListener(adaptor) }
}

inline fun <reified T> Component.identifierEquals(value: T): Boolean = identifier is T && identifier == value

fun Component.identifierStartsWith(prefix: String): Boolean = identifier is String && (identifier as String).startsWith(prefix)

fun Component.identifierNotEquals(value: Any): Boolean = !identifierEquals(value)

val Int.scaled: Int
    get() = JBUI.scale(this)

val Float.scaled: Float
    get() = JBUIScale.scale(this)

val Icon.scaled: Icon
    get() = this.scale(12f.scaled)

val Dimension.scaled: Dimension
    get() = Dimension(width.scaled, height.scaled)

val Border.scaled: Border
    get() =
        when (this) {
            is EmptyBorder -> {
                val insets = borderInsets
                JBUI.Borders.empty(
                    insets.top.scaled,
                    insets.left.scaled,
                    insets.bottom.scaled,
                    insets.right.scaled,
                )
            }
            else -> this
        }

fun Color.customBrighter(factor: Float = 0.2f): Color {
    val r = (this.red + 255 * factor).toInt().coerceIn(0, 255)
    val g = (this.green + 255 * factor).toInt().coerceIn(0, 255)
    val b = (this.blue + 255 * factor).toInt().coerceIn(0, 255)

    return Color(r, g, b, this.alpha)
}

fun Color.customDarker(factor: Float = 0.2f): Color {
    val r = (this.red - 255 * factor).toInt().coerceIn(0, 255)
    val g = (this.green - 255 * factor).toInt().coerceIn(0, 255)
    val b = (this.blue - 255 * factor).toInt().coerceIn(0, 255)

    return Color(r, g, b, this.alpha)
}

fun Color.contrastWithTheme(): JBColor =
    JBColor(
        this.darker(), // Light mode gets darker version
        this.brighter(), // Dark mode gets brighter version
    )

fun Color.harmonizeWithTheme(): JBColor =
    JBColor(
        this.brighter(), // Light mode gets brighter version
        this.darker(), // Dark mode gets darker version
    )

fun Color.withLightMode(): JBColor {
    val hsb = Color.RGBtoHSB(red, green, blue, null)

    // Adjust brightness while maintaining hue and reducing saturation
    val lightVersion =
        Color.getHSBColor(
            hsb[0], // Keep the same hue
            hsb[1] * 0.7f, // Reduce saturation slightly
            Math.min(1.0f, hsb[2] * 1.5f), // Increase brightness
        )
    return JBColor(lightVersion, this)
}

/**
 * Reduces the saturation of a color by the specified factor
 * @param factor The saturation multiplier (0.0 = no saturation, 1.0 = original saturation)
 */
fun Color.withReducedSaturation(factor: Float): Color {
    val hsb = Color.RGBtoHSB(red, green, blue, null)
    return Color.getHSBColor(
        hsb[0], // Keep the same hue
        hsb[1] * factor.coerceIn(0.0f, 1.0f), // Reduce saturation by factor
        hsb[2], // Keep the same brightness
    )
}

/**
 * Reduces the saturation of a color while preserving perceptual luminance
 * @param factor The saturation multiplier (0.0 = no saturation, 1.0 = original saturation)
 */
fun Color.withReducedSaturationPreservingLuminance(
    saturationFactor: Float, // 's' in our analysis (~0.5)
): Color {
    val s = saturationFactor.coerceIn(0.0f, 1.0f)

    // Calculate luminance using ITU-R BT.709 weights (Chromium standard)
    val luminance = red * 0.2126f + green * 0.7152f + blue * 0.0722f

    // Luminance-aware desaturation: L + s*(color - L)
    val desatRed = (luminance + s * (red - luminance)).toInt().coerceIn(0, 255)
    val desatGreen = (luminance + s * (green - luminance)).toInt().coerceIn(0, 255)
    val desatBlue = (luminance + s * (blue - luminance)).toInt().coerceIn(0, 255)

    return Color(desatRed, desatGreen, desatBlue, alpha)
}

/**
 * Reduces the saturation of a color while preserving perceptual luminance with different factors for light and dark modes
 * @param lightSaturationFactor The saturation multiplier for light mode (0.0 = no saturation, 1.0 = original saturation)
 * @param darkSaturationFactor The saturation multiplier for dark mode (0.0 = no saturation, 1.0 = original saturation)
 */
fun Color.withReducedSaturationPreservingLuminance(
    lightSaturationFactor: Float,
    darkSaturationFactor: Float,
) = JBColor(
    this.withReducedSaturationPreservingLuminance(lightSaturationFactor),
    this.withReducedSaturationPreservingLuminance(darkSaturationFactor),
)

/**
 * Adjust brightness (HSB value) while preserving hue, saturation, and alpha.
 * brightnessFactor: 0.0 = black, 1.0 = original, >1.0 = brighter
 */
fun Color.withAdjustedBrightnessPreservingHue(brightnessFactor: Float): Color {
    val hsb = Color.RGBtoHSB(red, green, blue, null)
    val newB = (hsb[2] * brightnessFactor).coerceIn(0.0f, 1.0f)
    val rgb = Color.getHSBColor(hsb[0], hsb[1], newB)
    return Color(rgb.red, rgb.green, rgb.blue, alpha)
}

/**
 * Theme-aware brightness adjustment while preserving hue and alpha.
 */
fun Color.withAdjustedBrightnessPreservingHue(
    lightBrightnessFactor: Float,
    darkBrightnessFactor: Float,
) = JBColor(
    this.withAdjustedBrightnessPreservingHue(lightBrightnessFactor),
    this.withAdjustedBrightnessPreservingHue(darkBrightnessFactor),
)

class ShowOnHoverMouseAdaptor(
    private val container: Component,
    private val child: Component,
    private val delay: Int = 100,
    private val shouldShow: () -> Boolean = { true },
) : MouseAdapter() {
    private var isMouseOver = false

    override fun mouseEntered(e: MouseEvent) {
        isMouseOver = true
        if (shouldShow()) {
            child.isVisible = true
            container.revalidate()
            container.repaint()
        }
    }

    override fun mouseExited(e: MouseEvent) {
        isMouseOver = false
        if (shouldShow() &&
            !child.bounds.contains(SwingUtilities.convertPoint(container, e.point, child))
        ) {
            Timer(delay) {
                if (!isMouseOver && shouldShow()) {
                    child.isVisible = false
                    container.revalidate()
                    container.repaint()
                }
            }.apply {
                isRepeats = false
                start()
            }
        }
    }
}

fun Container.showOnHoverMouseAdaptor(
    child: Component,
    delay: Int = 100,
    shouldShow: () -> Boolean = { true },
) = ShowOnHoverMouseAdaptor(
    this,
    child,
    delay,
    shouldShow,
)

fun <T> Iterable<T>.filterNotEquals(item: T) = this.filterNot { it == item }

fun <T> Sequence<T>.filterNotEquals(item: T) = this.filterNot { it == item }

fun <T> Iterable<T>.filterEquals(item: T) = this.filter { it == item }

fun <T> Sequence<T>.filterEquals(item: T) = this.filter { it == item }

// to make it testable
var lineSeparator = { System.lineSeparator() }

fun String.getLineSeparatorType(): String =
    when {
        this.contains("\r\n") -> "\r\n"
        this.contains("\r") -> "\r"
        this.contains("\n") -> "\n"
        else -> lineSeparator()
    }

fun String.normalizeLineEndings(): String = this.replace(Regex("""\r?\n"""), lineSeparator())

fun String.normalizeLineEndings(referenceContent: String): String = replace(getLineSeparatorType(), referenceContent.getLineSeparatorType())

fun String.convertLineEndings(lineEnding: String = "\n"): String = replace(Regex("""\r?\n"""), lineEnding)

fun String.normalizeCharacters(): String =
    this
        // Normalize apostrophes to standard keyboard apostrophe
        .replace("’", "'")

fun String.normalizeUsingNFC(): String = Normalizer.normalize(this, Normalizer.Form.NFC)

fun String.platformAwareContains(str: String): Boolean {
    // First try direct match
    if (this.contains(str)) return true

    // Try with line ending normalization
    if (this.contains(str.normalizeLineEndings(getLineSeparatorType()))) return true

    // Try with Unicode NFC normalization (handles composed vs decomposed characters like й)
    val normalizedThis = this.normalizeUsingNFC()
    val normalizedStr = str.normalizeUsingNFC()
    return normalizedThis.contains(normalizedStr)
}

fun String.platformAwareIndexOf(
    str: String,
    startIndex: Int = 0,
): Int {
    // assume this is using the newline from the system but str is not
    val lineSeparatorType = getLineSeparatorType()
    if (lineSeparatorType !in this) return this.indexOf(str, startIndex)
    val originalIndex = this.indexOf(str.normalizeLineEndings(getLineSeparatorType()), startIndex)
    if (originalIndex >= 0) return originalIndex

    // Try with Unicode NFC normalization (handles composed vs decomposed characters like й)
    val normalizedThis = this.normalizeUsingNFC()
    val normalizedStr = str.normalizeUsingNFC()
    val normalizedIndex = normalizedThis.indexOf(normalizedStr, startIndex)

    if (normalizedIndex >= 0) {
        // Calculate offset: difference between original and normalized prefix lengths
        val prefixBeforeMatch = this.substring(0, normalizedIndex.coerceAtMost(this.length))
        val normalizedPrefixLength = prefixBeforeMatch.normalizeUsingNFC().length
        val offset = prefixBeforeMatch.length - normalizedPrefixLength
        return normalizedIndex + offset
    }

    return -1
}

/**
 * Replaces the first occurrence of [oldStr] with [newStr], handling Unicode normalization differences.
 * This handles cases where the same character can be represented differently (e.g., й as single char vs и + combining breve).
 */
fun String.platformAwareReplace(
    oldStr: String,
    newStr: String,
): String {
    val normalizedThis = this.normalizeUsingNFC()
    val normalizedOldStr = oldStr.normalizeUsingNFC()
    val normalizedIndex = normalizedThis.indexOf(normalizedOldStr)

    if (normalizedIndex >= 0) {
        // Calculate start offset: difference between original and normalized prefix lengths
        val prefixBeforeMatch = this.substring(0, normalizedIndex.coerceAtMost(this.length))
        val startOffset = prefixBeforeMatch.length - prefixBeforeMatch.normalizeUsingNFC().length
        val originalStartIndex = normalizedIndex + startOffset

        // Calculate end offset: difference between original and normalized lengths up to end of match
        val normalizedEndIndex = normalizedIndex + normalizedOldStr.length
        val prefixBeforeEnd = this.substring(0, normalizedEndIndex.coerceAtMost(this.length))
        val endOffset = prefixBeforeEnd.length - prefixBeforeEnd.normalizeUsingNFC().length
        val originalEndIndex = normalizedEndIndex + endOffset

        // Replace the original substring with the new string, avoiding modifying other parts of the string
        return this.substring(0, originalStartIndex) + newStr + this.substring(originalEndIndex)
    }

    // No match found, return original string
    return this
}

fun Color.darker(factor: Int): Color {
    var res = this
    for (i in 0..factor) {
        res = res.darker()
    }
    return res
}

fun Color.brighter(factor: Int): Color {
    var res = this
    for (i in 0..factor) {
        res = res.brighter()
    }
    return res
}

val Project.osBasePath: String?
    get() = basePath?.replace("/", File.separator)

fun VirtualFile.readTextOnDisk(): String {
    SlowOperations.assertSlowOperationsAreAllowed()
    return contentsToByteArray().toString(Charsets.UTF_8)
}

fun String.safeSlice(range: IntRange) =
    slice(
        range.first.coerceAtLeast(0)..range.last.coerceAtMost(length - 1),
    )

fun <T> List<T>.safeSlice(range: IntRange) =
    slice(
        range.first.coerceAtLeast(0)..range.last.coerceAtMost(size - 1),
    )

fun String.countSubstrings(substring: String): Int {
    if (substring.isEmpty()) return 0
    var count = 0
    var index = 0
    while (index != -1) {
        index = indexOf(substring, index)
        if (index != -1) {
            count++
            index += substring.length
        }
    }
    return count
}

fun Document.linesToOffsetRange(range: IntRange): TextRange {
    val currentStartOffset = getLineStartOffset(range.first)
    val currentEndOffset = getLineEndOffset(range.last)
    return TextRange(currentStartOffset, currentEndOffset)
}

fun IntRange.expand(
    n: Int,
    maxSize: Int = Int.MAX_VALUE,
) = IntRange(
    (first - n).coerceAtLeast(0),
    (last + n).coerceAtMost(maxSize),
)

fun PsiElement.documentLinesRange(document: Document): TextRange = document.linesToOffsetRange(linesRange(document))

fun PsiElement.documentLines(document: Document) = document.getText(documentLinesRange(document))

fun PsiElement.findLargestParent(maxLines: Int): PsiElement {
    var currentBlock = this
    while (
        currentBlock.parent != null &&
        currentBlock.parent.numLines() <= maxLines
    ) {
        currentBlock = currentBlock.parent
    }
    return currentBlock
}

fun PsiElement.numLines() = text.lines().size

fun PsiElement.linesRange(document: Document): IntRange =
    IntRange(document.getLineNumber(textRange.startOffset), document.getLineNumber(textRange.endOffset))

infix fun IntRange.distanceTo(other: Int): Int {
    if (other in this) {
        return 0
    }
    return kotlin.math.min(abs(start - other), abs(endInclusive - other))
}

infix fun Int.distanceTo(other: IntRange): Int = other distanceTo this

infix fun IntRange.distanceTo(other: IntRange) = min(this distanceTo other.first, this distanceTo other.last)

fun hexToColor(hex: String): Color? =
    try {
        val cleanHex = hex.removePrefix("#")
        if (cleanHex.length == 6) {
            Color(
                cleanHex.substring(0, 2).toInt(16),
                cleanHex.substring(2, 4).toInt(16),
                cleanHex.substring(4, 6).toInt(16),
            )
        } else {
            null
        }
    } catch (e: NumberFormatException) {
        null
    }
