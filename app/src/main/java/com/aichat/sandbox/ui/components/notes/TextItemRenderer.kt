package com.aichat.sandbox.ui.components.notes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.aichat.sandbox.data.model.NoteItem

/**
 * Layout + draw helpers for text items (sub-phase 1.9).
 *
 * Text items are laid out at world-origin (0, 0) via [StaticLayout] and then
 * pushed through the item's stored affine matrix at render time — that's why
 * the payload carries a 9-float matrix instead of an `(x, y)` origin: rotation
 * and per-axis scale fall out naturally from selection transforms.
 *
 * Layouts are cached per item id keyed on `(body, fontSize, alignment)`. The
 * cache is bounded by manual eviction in [evictUnused] which the surface calls
 * whenever the committed-item list changes — this prevents the cache growing
 * unboundedly across erase/undo cycles, mirroring the stroke decode cache.
 *
 * Android-only because [StaticLayout] and [TextPaint] are framework APIs. The
 * pure-Kotlin [TextItemCodec] handles round-trip persistence so JVM tests
 * cover the payload format separately.
 */
object TextItemRenderer {

    private val cache: HashMap<String, CacheEntry> = HashMap()

    /**
     * Draw the text item under the active canvas transform. Caller is
     * responsible for setting up viewport translate/scale before invoking.
     *
     * The item's affine matrix is applied via `canvas.concat`, so a rotated /
     * scaled selection transforms render-time without re-encoding the payload
     * until the user commits the gesture.
     */
    fun draw(canvas: Canvas, item: NoteItem, scratchMatrix: Matrix = Matrix()) {
        val decoded = decoded(item) ?: return
        val layout = layoutFor(item.id, decoded, item.colorArgb)
        if (decoded.body.isEmpty()) return
        canvas.save()
        scratchMatrix.setValues(decoded.matrix)
        canvas.concat(scratchMatrix)
        layout.draw(canvas)
        canvas.restore()
    }

    /**
     * Axis-aligned bounding box of the rendered text in world units. Returns
     * `null` for empty bodies — those have no visible footprint, so the
     * selection / list / rasterizer all skip them.
     */
    fun boundsOf(item: NoteItem): FloatArray? {
        val decoded = decoded(item) ?: return null
        if (decoded.body.isEmpty()) return null
        val layout = layoutFor(item.id, decoded, item.colorArgb)
        val maxLineWidth = (0 until layout.lineCount)
            .maxOfOrNull { layout.getLineWidth(it) } ?: 0f
        val w = maxLineWidth
        val h = layout.height.toFloat()
        // Transform the four corners through the item's matrix and take the
        // envelope. After rotation the box is no longer axis-aligned, so we
        // can't just multiply (w, h) directly.
        val m = decoded.matrix
        val corners = floatArrayOf(
            0f, 0f,
            w, 0f,
            w, h,
            0f, h,
        )
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var i = 0
        while (i < corners.size) {
            val x = corners[i]
            val y = corners[i + 1]
            val tx = m[0] * x + m[1] * y + m[2]
            val ty = m[3] * x + m[4] * y + m[5]
            if (tx < minX) minX = tx
            if (tx > maxX) maxX = tx
            if (ty < minY) minY = ty
            if (ty > maxY) maxY = ty
            i += 2
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    /**
     * Lasso hit-test for text items — true if any of the four (possibly
     * rotated) corners falls inside the polygon. Mirrors the lenient
     * stroke hit-test in [com.aichat.sandbox.ui.screens.notes.LassoController]:
     * "any of the geometry inside the loop counts".
     */
    fun intersectsPolygon(
        item: NoteItem,
        polygon: FloatArray,
        vertexCount: Int,
        polygonBounds: FloatArray,
    ): Boolean {
        val bounds = boundsOf(item) ?: return false
        if (!com.aichat.sandbox.ui.screens.notes.LassoController.boundsOverlap(bounds, polygonBounds)) {
            return false
        }
        val decoded = decoded(item) ?: return false
        val layout = layoutFor(item.id, decoded, item.colorArgb)
        val maxLineWidth = (0 until layout.lineCount)
            .maxOfOrNull { layout.getLineWidth(it) } ?: 0f
        val w = maxLineWidth
        val h = layout.height.toFloat()
        val m = decoded.matrix
        val cornerXs = floatArrayOf(0f, w, w, 0f)
        val cornerYs = floatArrayOf(0f, 0f, h, h)
        for (i in 0..3) {
            val tx = m[0] * cornerXs[i] + m[1] * cornerYs[i] + m[2]
            val ty = m[3] * cornerXs[i] + m[4] * cornerYs[i] + m[5]
            if (com.aichat.sandbox.ui.screens.notes.LassoController.polygonContainsPoint(
                    polygon, vertexCount, tx, ty,
                )
            ) return true
        }
        // Also check whether the text origin (matrix translation) is inside —
        // catches the case where the loop sits entirely inside the text box.
        return com.aichat.sandbox.ui.screens.notes.LassoController.polygonContainsPoint(
            polygon, vertexCount, m[2], m[5],
        )
    }

    /**
     * Drop cache entries whose id isn't in [keepIds]. Mirrors the stroke
     * decode cache in `DrawingSurface` so erased / undone text items free
     * their `StaticLayout` instances.
     */
    fun evictUnused(keepIds: Set<String>) {
        if (cache.isEmpty()) return
        cache.keys.retainAll(keepIds)
    }

    /** Drop a single cached layout, e.g. when the body changes via UpdateText. */
    fun invalidate(id: String) {
        cache.remove(id)
    }

    private fun decoded(item: NoteItem): TextItemCodec.TextPayload? {
        if (item.kind != KIND_TEXT) return null
        return try {
            TextItemCodec.decode(item.payload)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun layoutFor(
        id: String,
        decoded: TextItemCodec.TextPayload,
        colorArgb: Int,
    ): StaticLayout {
        val existing = cache[id]
        if (existing != null &&
            existing.body == decoded.body &&
            existing.fontSize == decoded.fontSize &&
            existing.alignment == decoded.alignment &&
            existing.color == colorArgb
        ) {
            return existing.layout
        }
        val paint = TextPaint().apply {
            isAntiAlias = true
            color = if (colorArgb == 0) Color.BLACK else colorArgb
            textSize = decoded.fontSize
        }
        val layout = StaticLayout.Builder
            .obtain(decoded.body, 0, decoded.body.length, paint, TextItemCodec.DEFAULT_MAX_WIDTH_WORLD)
            .setAlignment(layoutAlignment(decoded.alignment))
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
        cache[id] = CacheEntry(
            body = decoded.body,
            fontSize = decoded.fontSize,
            alignment = decoded.alignment,
            color = colorArgb,
            layout = layout,
        )
        return layout
    }

    private fun layoutAlignment(alignment: Byte): Layout.Alignment = when (alignment) {
        TextItemCodec.ALIGN_CENTER -> Layout.Alignment.ALIGN_CENTER
        TextItemCodec.ALIGN_RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_NORMAL
    }

    private data class CacheEntry(
        val body: String,
        val fontSize: Float,
        val alignment: Byte,
        val color: Int,
        val layout: StaticLayout,
    )

    /** Mirrors [TextItemCodec.KIND] for callers that already depend on this object. */
    const val KIND_TEXT: String = TextItemCodec.KIND
}
