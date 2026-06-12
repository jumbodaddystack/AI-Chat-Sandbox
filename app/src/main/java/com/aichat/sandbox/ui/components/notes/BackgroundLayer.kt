package com.aichat.sandbox.ui.components.notes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.floor

/**
 * Per-note paper background (sub-phase 1.5).
 *
 * Drawn in screen space (not under the viewport canvas transform) so line
 * weights stay perceptually constant across zoom levels. Grid spacing is in
 * **world units** — gridlines move/scale with the rest of the canvas as the
 * user pans and zooms.
 */
object BackgroundLayer {

    const val STYLE_PLAIN = "plain"
    const val STYLE_DOT = "dot"
    const val STYLE_LINE = "line"
    const val STYLE_GRAPH = "graph"

    /**
     * Grid spacing in world units. Public since phase 15.3: icon artboards
     * are sized at one graph cell per icon pixel (e.g. 24 px → 768 world),
     * so this constant doubles as the icon pixel-grid step.
     */
    const val SPACING_WORLD = 32f

    /** Below this on-screen spacing we skip the pattern entirely (too dense). */
    private const val MIN_SCREEN_SPACING_PX = 6f

    private const val DOT_RADIUS_PX = 1.4f
    private val GRID_COLOR = Color.argb(40, 0, 0, 0)
    private const val PAPER_COLOR = Color.WHITE

    private val paint = Paint().apply {
        isAntiAlias = true
        color = GRID_COLOR
        strokeWidth = 1f
    }

    fun draw(
        canvas: Canvas,
        viewport: ViewportController,
        style: String,
        viewWidthPx: Int,
        viewHeightPx: Int,
    ) {
        canvas.drawColor(PAPER_COLOR)
        if (style == STYLE_PLAIN || viewWidthPx <= 0 || viewHeightPx <= 0) return

        val screenSpacing = SPACING_WORLD * viewport.scale
        if (screenSpacing < MIN_SCREEN_SPACING_PX) return

        // First gridline at or above each visible edge, snapped to world grid.
        val worldLeft = viewport.screenToWorldX(0f)
        val worldTop = viewport.screenToWorldY(0f)
        val worldStartX = floor(worldLeft / SPACING_WORLD) * SPACING_WORLD
        val worldStartY = floor(worldTop / SPACING_WORLD) * SPACING_WORLD

        when (style) {
            STYLE_DOT -> drawDots(
                canvas, viewport, worldStartX, worldStartY,
                viewWidthPx, viewHeightPx,
            )
            STYLE_LINE -> drawHorizontalLines(
                canvas, viewport, worldStartY,
                viewWidthPx, viewHeightPx,
            )
            STYLE_GRAPH -> {
                drawHorizontalLines(
                    canvas, viewport, worldStartY,
                    viewWidthPx, viewHeightPx,
                )
                drawVerticalLines(
                    canvas, viewport, worldStartX,
                    viewWidthPx, viewHeightPx,
                )
            }
        }
    }

    private fun drawDots(
        canvas: Canvas,
        viewport: ViewportController,
        worldStartX: Float,
        worldStartY: Float,
        viewWidthPx: Int,
        viewHeightPx: Int,
    ) {
        paint.style = Paint.Style.FILL
        var wx = worldStartX
        while (true) {
            val sx = viewport.worldToScreenX(wx)
            if (sx > viewWidthPx) break
            if (sx >= -DOT_RADIUS_PX) {
                var wy = worldStartY
                while (true) {
                    val sy = viewport.worldToScreenY(wy)
                    if (sy > viewHeightPx) break
                    if (sy >= -DOT_RADIUS_PX) {
                        canvas.drawCircle(sx, sy, DOT_RADIUS_PX, paint)
                    }
                    wy += SPACING_WORLD
                }
            }
            wx += SPACING_WORLD
        }
    }

    private fun drawHorizontalLines(
        canvas: Canvas,
        viewport: ViewportController,
        worldStartY: Float,
        viewWidthPx: Int,
        viewHeightPx: Int,
    ) {
        paint.style = Paint.Style.STROKE
        val widthF = viewWidthPx.toFloat()
        var wy = worldStartY
        while (true) {
            val sy = viewport.worldToScreenY(wy)
            if (sy > viewHeightPx) break
            if (sy >= 0f) canvas.drawLine(0f, sy, widthF, sy, paint)
            wy += SPACING_WORLD
        }
    }

    private fun drawVerticalLines(
        canvas: Canvas,
        viewport: ViewportController,
        worldStartX: Float,
        viewWidthPx: Int,
        viewHeightPx: Int,
    ) {
        paint.style = Paint.Style.STROKE
        val heightF = viewHeightPx.toFloat()
        var wx = worldStartX
        while (true) {
            val sx = viewport.worldToScreenX(wx)
            if (sx > viewWidthPx) break
            if (sx >= 0f) canvas.drawLine(sx, 0f, sx, heightF, paint)
            wx += SPACING_WORLD
        }
    }
}
