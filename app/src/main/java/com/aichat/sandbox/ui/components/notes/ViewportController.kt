package com.aichat.sandbox.ui.components.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.max
import kotlin.math.min

/**
 * Pan / zoom state for the infinite notes canvas (sub-phase 1.5).
 *
 * Coordinates: stroke geometry is stored in **world** coordinates. The
 * viewport maps world → screen via `screen = world * scale + offset`.
 * `screenToWorld` inverts this mapping; both directions are needed because
 * input arrives in screen coords while strokes are persisted in world coords.
 *
 * Fields are backed by [mutableStateOf] so Compose-side observers (notably
 * the selection overlay in 1.8) recompose when the user pans or zooms.
 * The class itself is still Android-free, so [ViewportControllerTest] keeps
 * running on the JVM.
 */
class ViewportController(
    offsetX: Float = 0f,
    offsetY: Float = 0f,
    scale: Float = 1f,
) {

    var offsetX: Float by mutableStateOf(offsetX)
        private set
    var offsetY: Float by mutableStateOf(offsetY)
        private set
    var scale: Float by mutableStateOf(scale.coerceIn(MIN_SCALE, MAX_SCALE))
        private set

    /** Fired whenever offset or scale changes. Used by the surface to invalidate. */
    var onChanged: (() -> Unit)? = null

    // ── Bounded pan (icon artboard) ──────────────────────────────────────
    //
    // Icons are a *bounded* canvas: the artboard must never be flung
    // off-screen, otherwise reopened work looks "lost". Notes keep the
    // infinite canvas (these stay null/zero, so [clampOffsets] no-ops).
    //
    // [clampBounds] is the artboard's world-rect `[minX, minY, maxX, maxY]`;
    // when non-null every pan/zoom is followed by [clampOffsets] so the
    // artboard either stays centred (when it fits the viewport on that axis)
    // or keeps covering it (when zoomed in past the edges).
    private var clampBounds: FloatArray? = null
    private var clampCanvasW: Float = 0f
    private var clampCanvasH: Float = 0f

    /**
     * Enable bounded panning for [bounds] (artboard world-rect) inside a
     * viewport of [canvasSize] (`[width, height]` screen px). Passing `null`
     * bounds clears the clamp (equivalent to [clearPanBounds]). Re-clamps
     * immediately so a pre-existing illegal offset (e.g. after a screen
     * rotation that changed [canvasSize]) snaps back into range.
     */
    fun setPanBounds(bounds: FloatArray?, canvasSize: FloatArray) {
        if (bounds == null || bounds.size < 4 || canvasSize.size < 2) {
            clearPanBounds()
            return
        }
        clampBounds = bounds.copyOf(4)
        clampCanvasW = canvasSize[0]
        clampCanvasH = canvasSize[1]
        clampOffsets()
        onChanged?.invoke()
    }

    /** Disable bounded panning (the infinite-canvas default used by notes). */
    fun clearPanBounds() {
        if (clampBounds == null) return
        clampBounds = null
        clampCanvasW = 0f
        clampCanvasH = 0f
    }

    /**
     * Constrain [offsetX]/[offsetY] so the [clampBounds] artboard stays
     * visible. Per-axis and independent: a tall artboard can overflow a
     * short landscape viewport (pan allowed) while fitting it horizontally
     * (centred). No-op when no bounds are set, so notes are unaffected.
     */
    private fun clampOffsets() {
        val b = clampBounds ?: return
        if (clampCanvasW <= 0f || clampCanvasH <= 0f || scale <= 0f) return
        offsetX = clampAxis(offsetX, b[0], b[2], clampCanvasW)
        offsetY = clampAxis(offsetY, b[1], b[3], clampCanvasH)
    }

    private fun clampAxis(
        offset: Float,
        worldMin: Float,
        worldMax: Float,
        canvasLen: Float,
    ): Float {
        val artScreenLen = (worldMax - worldMin) * scale
        return if (artScreenLen <= canvasLen) {
            // Fits this axis → centre the artboard.
            val mid = (worldMin + worldMax) * 0.5f
            canvasLen * 0.5f - mid * scale
        } else {
            // Overflows → keep the artboard covering the viewport (no gap):
            //   top/left edge not past 0, bottom/right edge not before canvasLen.
            val maxOffset = -worldMin * scale
            val minOffset = canvasLen - worldMax * scale
            offset.coerceIn(minOffset, maxOffset)
        }
    }

    /**
     * Lowest scale allowed while [clampBounds] is set, so the artboard can't
     * be pinched down to a dot. Returns [MIN_SCALE] when unbounded (notes).
     */
    private fun iconMinScale(): Float {
        val b = clampBounds ?: return MIN_SCALE
        val maxWorldDim = max(b[2] - b[0], b[3] - b[1])
        if (maxWorldDim <= 0f) return MIN_SCALE
        val smallerCanvas = min(clampCanvasW, clampCanvasH)
        if (smallerCanvas <= 0f) return MIN_SCALE
        val fillFloor = ICON_MIN_FILL_FRACTION * smallerCanvas / maxWorldDim
        return max(MIN_SCALE, fillFloor).coerceAtMost(MAX_SCALE)
    }

    fun applyPan(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        offsetX += dx
        offsetY += dy
        clampOffsets()
        onChanged?.invoke()
    }

    /**
     * Multiply [scale] by [factor], keeping the world point currently under
     * (`focusScreenX`, `focusScreenY`) under the same screen point after the
     * change. The clamp on [scale] may reduce the effective factor.
     */
    fun applyZoom(focusScreenX: Float, focusScreenY: Float, factor: Float) {
        if (factor <= 0f) return
        // Icons floor the zoom-out so the artboard can't shrink into a dot;
        // notes keep the global MIN_SCALE (iconMinScale returns it when unbounded).
        val target = (scale * factor).coerceIn(iconMinScale(), MAX_SCALE)
        if (target == scale) return
        val worldX = (focusScreenX - offsetX) / scale
        val worldY = (focusScreenY - offsetY) / scale
        scale = target
        offsetX = focusScreenX - worldX * scale
        offsetY = focusScreenY - worldY * scale
        clampOffsets()
        onChanged?.invoke()
    }

    fun reset() {
        if (offsetX == 0f && offsetY == 0f && scale == 1f) return
        offsetX = 0f
        offsetY = 0f
        scale = 1f
        onChanged?.invoke()
    }

    /**
     * Phase 5.4 — frame [bounds] (a world-space `[minX, minY, maxX, maxY]`)
     * inside a viewport of size [canvasSize] (`[width, height]` in screen
     * pixels), leaving [marginPx] of padding on all sides. Pinch and pan are
     * unaffected; this is a one-shot teleport.
     *
     * Empty / degenerate bounds (width or height ≤ 0) and a zero-size canvas
     * are silently ignored — the chip's "Fit content" action is gated on a
     * non-empty note in the UI, but the guard keeps the controller honest
     * under racey screen-rotation events.
     */
    fun fitToContent(bounds: FloatArray, canvasSize: FloatArray, marginPx: Float = 24f) {
        if (bounds.size < 4 || canvasSize.size < 2) return
        val worldW = bounds[2] - bounds[0]
        val worldH = bounds[3] - bounds[1]
        if (worldW <= 0f || worldH <= 0f) return
        val canvasW = canvasSize[0]
        val canvasH = canvasSize[1]
        if (canvasW <= 0f || canvasH <= 0f) return
        val usableW = max(1f, canvasW - 2f * marginPx)
        val usableH = max(1f, canvasH - 2f * marginPx)
        val targetScale = min(usableW / worldW, usableH / worldH)
            .coerceIn(MIN_SCALE, MAX_SCALE)
        val cx = (bounds[0] + bounds[2]) * 0.5f
        val cy = (bounds[1] + bounds[3]) * 0.5f
        scale = targetScale
        offsetX = canvasW * 0.5f - cx * targetScale
        offsetY = canvasH * 0.5f - cy * targetScale
        clampOffsets()
        onChanged?.invoke()
    }

    /**
     * Centre [bounds] in the viewport without changing scale. Useful for the
     * "Center" action when the user is happily zoomed in and just wants the
     * content back under the cursor.
     */
    fun centerOnContent(bounds: FloatArray, canvasSize: FloatArray) {
        if (bounds.size < 4 || canvasSize.size < 2) return
        val canvasW = canvasSize[0]
        val canvasH = canvasSize[1]
        if (canvasW <= 0f || canvasH <= 0f) return
        val cx = (bounds[0] + bounds[2]) * 0.5f
        val cy = (bounds[1] + bounds[3]) * 0.5f
        offsetX = canvasW * 0.5f - cx * scale
        offsetY = canvasH * 0.5f - cy * scale
        clampOffsets()
        onChanged?.invoke()
    }

    /**
     * Reset to 100% zoom while keeping [canvasSize]'s centre under the
     * current viewport centre. Differs from [reset] in that it doesn't snap
     * the offset back to `(0, 0)` — the user's view "stays put" but the
     * scale snaps back to 1.0.
     */
    fun resetToOneHundred(canvasSize: FloatArray) {
        if (canvasSize.size < 2) return
        val canvasW = canvasSize[0]
        val canvasH = canvasSize[1]
        if (canvasW <= 0f || canvasH <= 0f) {
            reset()
            return
        }
        if (scale == 1f) return
        // Preserve the world point at the centre of the canvas across the
        // scale change.
        val worldCx = (canvasW * 0.5f - offsetX) / scale
        val worldCy = (canvasH * 0.5f - offsetY) / scale
        scale = 1f
        offsetX = canvasW * 0.5f - worldCx
        offsetY = canvasH * 0.5f - worldCy
        clampOffsets()
        onChanged?.invoke()
    }

    /**
     * Sub-phase 8.2 — fly the viewport to fit [bounds]. Identical to
     * [fitToContent] today (one-shot teleport); a future animated fly-to
     * will be plumbed through Compose's `animateFloatAsState` at the call
     * site (the frame navigator), since this class deliberately stays
     * Compose-free for testability.
     */
    fun flyTo(bounds: FloatArray, canvasSize: FloatArray, marginPx: Float = 24f) {
        fitToContent(bounds, canvasSize, marginPx)
    }

    /**
     * Sub-phase 8.2 — animation hook used by the frame navigator's fly-to.
     * Lets the call site drive an [animateFloatAsState] without touching
     * the private setters. The supplied values are not clamped here — the
     * navigator is expected to pass values produced from
     * [fitToContent]-like math.
     */
    fun setForAnimation(newOffsetX: Float, newOffsetY: Float, newScale: Float) {
        val clampedScale = newScale.coerceIn(MIN_SCALE, MAX_SCALE)
        if (offsetX == newOffsetX && offsetY == newOffsetY && scale == clampedScale) return
        offsetX = newOffsetX
        offsetY = newOffsetY
        scale = clampedScale
        clampOffsets()
        onChanged?.invoke()
    }

    fun screenToWorldX(screenX: Float): Float = (screenX - offsetX) / scale
    fun screenToWorldY(screenY: Float): Float = (screenY - offsetY) / scale
    fun worldToScreenX(worldX: Float): Float = worldX * scale + offsetX
    fun worldToScreenY(worldY: Float): Float = worldY * scale + offsetY

    companion object {
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 8f

        /**
         * For bounded (icon) canvases, the smallest the artboard may be
         * zoomed to: its longest edge must still cover this fraction of the
         * viewport's shorter dimension. Stops the artboard shrinking to a dot.
         */
        const val ICON_MIN_FILL_FRACTION = 0.4f
    }
}
