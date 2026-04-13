package dev.sweep.assistant.views

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.utils.hasAnyFocusedDescendant
import java.awt.*
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

open class RoundedPanel(
    private val cornerRadius: Int = 5,
    var dottedBorder: Boolean = false,
    var clipChildren: Boolean = false, // Will add clipping mask to children so that they respect the rounded corners
    var roundTopLeft: Boolean = true,
    var roundTopRight: Boolean = true,
    var roundBottomLeft: Boolean = true,
    var roundBottomRight: Boolean = true,
    parentDisposable: Disposable? = null,
) : JPanel(),
    Disposable,
    Hoverable {
    open var borderColor: Color? = SweepColors.borderColor
    open var activeBorderColor: Color? = SweepColors.borderColor
    open var margin: Insets = JBUI.emptyInsets() // Add margin property
    private val STROKE_WIDTH = 1.0f

    private var isDisposed = false

    // Hoverable interface implementation
    override var isHovered = false
    override var hoverEnabled: Boolean = false
    var hoverBackgroundColor: Color = SweepColors.createHoverColor(SweepColors.backgroundColor, 0.05f)

    private val registration by lazy {
        parentDisposable?.let {
            Disposer.register(it, this)
        }
    }

    private fun createRoundedArea(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        radius: Double,
    ): Area {
        // If all corners are rounded, use the simple approach
        if (roundTopLeft && roundTopRight && roundBottomLeft && roundBottomRight) {
            return Area(RoundRectangle2D.Double(x, y, width, height, radius * 2, radius * 2))
        }

        // For partial rounding, build the shape by combining rectangles and quarter circles
        val area = Area()

        // Start with the main rectangle (excluding corner areas)
        val mainRect = Rectangle2D.Double(x + radius, y + radius, width - radius * 2, height - radius * 2)
        area.add(Area(mainRect))

        // Add edge rectangles
        // Top edge
        val topRect = Rectangle2D.Double(x + radius, y, width - radius * 2, radius)
        area.add(Area(topRect))

        // Bottom edge
        val bottomRect = Rectangle2D.Double(x + radius, y + height - radius, width - radius * 2, radius)
        area.add(Area(bottomRect))

        // Left edge
        val leftRect = Rectangle2D.Double(x, y + radius, radius, height - radius * 2)
        area.add(Area(leftRect))

        // Right edge
        val rightRect = Rectangle2D.Double(x + width - radius, y + radius, radius, height - radius * 2)
        area.add(Area(rightRect))

        // Add corners (either rounded or square)
        // Top-left corner
        if (roundTopLeft) {
            val cornerCircle = Area(RoundRectangle2D.Double(x, y, radius * 2, radius * 2, radius * 2, radius * 2))
            val cornerQuadrant = Area(Rectangle2D.Double(x, y, radius, radius))
            cornerCircle.intersect(cornerQuadrant)
            area.add(cornerCircle)
        } else {
            area.add(Area(Rectangle2D.Double(x, y, radius, radius)))
        }

        // Top-right corner
        if (roundTopRight) {
            val cornerCircle = Area(RoundRectangle2D.Double(x + width - radius * 2, y, radius * 2, radius * 2, radius * 2, radius * 2))
            val cornerQuadrant = Area(Rectangle2D.Double(x + width - radius, y, radius, radius))
            cornerCircle.intersect(cornerQuadrant)
            area.add(cornerCircle)
        } else {
            area.add(Area(Rectangle2D.Double(x + width - radius, y, radius, radius)))
        }

        // Bottom-left corner
        if (roundBottomLeft) {
            val cornerCircle = Area(RoundRectangle2D.Double(x, y + height - radius * 2, radius * 2, radius * 2, radius * 2, radius * 2))
            val cornerQuadrant = Area(Rectangle2D.Double(x, y + height - radius, radius, radius))
            cornerCircle.intersect(cornerQuadrant)
            area.add(cornerCircle)
        } else {
            area.add(Area(Rectangle2D.Double(x, y + height - radius, radius, radius)))
        }

        // Bottom-right corner
        if (roundBottomRight) {
            val cornerCircle =
                Area(
                    RoundRectangle2D.Double(
                        x + width - radius * 2,
                        y + height - radius * 2,
                        radius * 2,
                        radius * 2,
                        radius * 2,
                        radius * 2,
                    ),
                )
            val cornerQuadrant = Area(Rectangle2D.Double(x + width - radius, y + height - radius, radius, radius))
            cornerCircle.intersect(cornerQuadrant)
            area.add(cornerCircle)
        } else {
            area.add(Area(Rectangle2D.Double(x + width - radius, y + height - radius, radius, radius)))
        }

        return area
    }

    init {
        isOpaque = false
        setupHoverListener()
    }

    constructor(layout: LayoutManager, parentDisposable: Disposable? = null) : this(
        cornerRadius = 5,
        parentDisposable = parentDisposable,
    ) {
        this.layout = layout
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val strokeWidth = STROKE_WIDTH
        val area =
            createRoundedArea(
                margin.left + strokeWidth.toDouble() / 2,
                margin.top + strokeWidth.toDouble() / 2,
                width.toDouble() - strokeWidth - margin.left - margin.right,
                height.toDouble() - strokeWidth - margin.top - margin.bottom,
                cornerRadius.toDouble(),
            )

        // Use transparent background if background is null, and show hover color when hovered
        val currentBackground =
            if (isHovered && hoverEnabled) {
                hoverBackgroundColor
            } else {
                background ?: Color(0, 0, 0, 0) // Transparent if background is null
            }

        // Only fill if we have a visible color (not fully transparent)
        if (currentBackground.alpha > 0) {
            g2.color = currentBackground
            g2.fill(area)
        }

        borderColor?.let { color ->
            g2.color =
                if (hasAnyFocusedDescendant()) {
                    activeBorderColor ?: color
                } else {
                    color
                }

            if (dottedBorder) {
                g2.stroke =
                    BasicStroke(
                        strokeWidth,
                        BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER,
                        10.0f,
                        floatArrayOf(2f, 2f),
                        0.0f,
                    )
            } else {
                g2.stroke = BasicStroke(strokeWidth)
            }
            g2.draw(area)
        }

        g2.dispose()
        super.paintComponent(g)

        // Trigger lazy registration to prevent leaking "this"
        registration
    }

    override fun paintChildren(g: Graphics) {
        // Very important that children have isOpaque = false. If not, they will try to paint everything in bounds and potentially cover the border
        if (clipChildren) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            // Use the same stroke width as paintComponent for consistency
            val strokeWidth = STROKE_WIDTH
            // Create clipping area that excludes the border stroke area
            val clippingArea =
                createRoundedArea(
                    margin.left + strokeWidth.toDouble(),
                    margin.top + strokeWidth.toDouble(),
                    width.toDouble() - (strokeWidth * 2) - margin.left - margin.right,
                    height.toDouble() - (strokeWidth * 2) - margin.top - margin.bottom,
                    cornerRadius.toDouble(),
                )

            // Get existing clip and intersect with our rounded area
            val existingClip = g2.clip
            if (existingClip != null) {
                // Create intersection of existing clip and our rounded area
                val combinedClip = Area(existingClip)
                combinedClip.intersect(clippingArea)
                g2.clip = combinedClip
            } else {
                // No existing clip, just use our rounded area
                g2.clip = clippingArea
            }

            super.paintChildren(g2)
            g2.dispose()
        } else {
            super.paintChildren(g)
        }
    }

    override fun repaint(
        tm: Long,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        if (clipChildren) {
            // When clipping is enabled, redirect child repaint requests to parent
            // to ensure clipping is always applied
            super.repaint(tm, 0, 0, getWidth(), getHeight())
        } else {
            super.repaint(tm, x, y, width, height)
        }
    }

    override fun repaint(r: Rectangle?) {
        if (clipChildren && r != null) {
            // When clipping is enabled, repaint the entire component
            super.repaint(Rectangle(0, 0, width, height))
        } else {
            super.repaint(r)
        }
    }

    override fun repaint() {
        super.repaint()
    }

    override fun dispose() {
        isDisposed = true
    }

    // Manually invoke hover effect, on custom hover parents
    fun applyHoverEffect() {
        if (hoverEnabled && !isHovered) {
            isHovered = true
            repaint()
        }
    }

    fun removeHoverEffect() {
        if (hoverEnabled && isHovered) {
            isHovered = false
            repaint()
        }
    }
}
