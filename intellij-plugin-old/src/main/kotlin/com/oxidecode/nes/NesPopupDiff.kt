package com.oxidecode.nes

import com.intellij.ui.awt.RelativePoint
import java.awt.Component
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Toolkit
import javax.swing.JComponent

data class DiffGroup(
    val deletions: String,
    val additions: String,
    val index: Int,
) {
    val hasAdditions: Boolean get() = additions.isNotEmpty()
    val hasDeletions: Boolean get() = deletions.isNotEmpty()
}

fun computeDiffGroups(oldContent: String, newContent: String): List<DiffGroup> {
    if (newContent.isEmpty() && oldContent.isNotEmpty()) return listOf(DiffGroup(oldContent, "", 0))
    if (oldContent == newContent) return emptyList()

    var commonPrefixLen = 0
    while (
        commonPrefixLen < oldContent.length &&
        commonPrefixLen < newContent.length &&
        oldContent[commonPrefixLen] == newContent[commonPrefixLen]
    ) {
        commonPrefixLen += 1
    }

    // de

    var commonSuffixLen = 0
    while (
        commonSuffixLen < oldContent.length - commonPrefixLen &&
        commonSuffixLen < newContent.length - commonPrefixLen &&
        oldContent[oldContent.length - 1 - commonSuffixLen] == newContent[newContent.length - 1 - commonSuffixLen]
    ) {
        commonSuffixLen += 1
    }

    val deletions = oldContent.substring(commonPrefixLen, oldContent.length - commonSuffixLen)
    val additions = newContent.substring(commonPrefixLen, newContent.length - commonSuffixLen)
    if (deletions.isEmpty() && additions.isEmpty()) return emptyList()
    return listOf(DiffGroup(deletions, additions, commonPrefixLen))
}

fun mergeDiffHunksWithSmallGaps(hunks: List<DiffGroup>, maxGapSize: Int = 2): List<DiffGroup> {
    if (hunks.isEmpty()) return hunks
    val merged = mutableListOf<DiffGroup>()
    var current = hunks.first()
    for (next in hunks.drop(1)) {
        val currentEnd = current.index + current.deletions.length
        val gapSize = next.index - currentEnd
        if (gapSize < maxGapSize) {
            val gap = if (gapSize > 0) " ".repeat(gapSize) else ""
            current = DiffGroup(
                deletions = current.deletions + gap + next.deletions,
                additions = current.additions + gap + next.additions,
                index = current.index,
            )
        } else {
            merged += current
            current = next
        }
    }
    merged += current
    return merged
}

fun stripCodeBlockIndentation(oldCode: String, code: String): Pair<String, Map<Int, Int>> {
    val oldIndent = commonIndent(oldCode) ?: return code to (0..code.length).associateWith { it }
    val newIndent = commonIndent(code) ?: return code to (0..code.length).associateWith { it }
    val indent = oldIndent.commonPrefixWith(newIndent)
    if (indent.isEmpty()) return code to (0..code.length).associateWith { it }

    val positionMap = linkedMapOf<Int, Int>()
    val dedented = StringBuilder()
    var originalPos = 0
    var dedentedPos = 0
    val lines = code.lines()

    lines.forEachIndexed { index, line ->
        if (line.isNotEmpty() && line.startsWith(indent)) {
            originalPos += indent.length
            val dedentedLine = line.substring(indent.length)
            dedented.append(dedentedLine)
            repeat(dedentedLine.length) { offset -> positionMap[originalPos + offset] = dedentedPos + offset }
            originalPos += dedentedLine.length
            dedentedPos += dedentedLine.length
        } else {
            dedented.append(line)
            repeat(line.length) { offset -> positionMap[originalPos + offset] = dedentedPos + offset }
            originalPos += line.length
            dedentedPos += line.length
        }
        if (index < lines.size - 1 || code.endsWith('\n')) {
            dedented.append('\n')
            positionMap[originalPos] = dedentedPos
            originalPos += 1
            dedentedPos += 1
        }
    }

    var lastMapped = 0
    for (i in 0 until code.length) {
        lastMapped = positionMap.getOrDefault(i, lastMapped)
        positionMap.putIfAbsent(i, lastMapped)
    }
    positionMap[0] = 0
    positionMap[code.length] = dedented.length
    return dedented.toString() to positionMap
}

private fun commonIndent(code: String): String? {
    val nonEmptyLines = code.lines().filter { it.isNotEmpty() }
    if (nonEmptyLines.isEmpty()) return null
    val indentations = nonEmptyLines.map { line -> line.takeWhile { it.isWhitespace() } }
    if (indentations.any { it.isEmpty() }) return null
    return indentations.reduce { acc, indent -> acc.commonPrefixWith(indent) }.takeIf { it.isNotEmpty() }
}

fun adjustPopupPositionForLongCompletion(
    point: Point,
    popupWidth: Int,
    popupHeight: Int,
    lineHeight: Int,
    indentWidth: Int,
    parentComponent: JComponent,
): Point {
    val adjustedPoint = Point(point.x, point.y)
    val relativePoint = RelativePoint(parentComponent as Component, adjustedPoint)
    val xEnd = relativePoint.screenPoint.x + popupWidth
    val screenDevice = getScreenDeviceForPoint(relativePoint.screenPoint)
    val isVeryLongCompletion = screenDevice == null || xEnd > screenDevice.defaultConfiguration.bounds.x + screenDevice.defaultConfiguration.bounds.width
    if (isVeryLongCompletion) {
        val spaceAbove = relativePoint.screenPoint.y
        val spaceBelow = Toolkit.getDefaultToolkit().screenSize.height - relativePoint.screenPoint.y
        if (spaceBelow > popupHeight + lineHeight) {
            adjustedPoint.y += lineHeight + 4
            adjustedPoint.x = indentWidth - 6
        } else if (spaceAbove > popupHeight + lineHeight) {
            adjustedPoint.y -= popupHeight + 4
            adjustedPoint.x = indentWidth - 6
        }
    }
    return adjustedPoint
}

private fun getScreenDeviceForPoint(point: Point): GraphicsDevice? {
    return GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.firstOrNull { device ->
        val bounds = device.defaultConfiguration.bounds
        point.x >= bounds.x && point.x < bounds.x + bounds.width && point.y >= bounds.y && point.y < bounds.y + bounds.height
    }
}
