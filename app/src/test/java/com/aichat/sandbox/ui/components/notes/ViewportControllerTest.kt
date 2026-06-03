package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportControllerTest {

    private val tol = 1e-4f

    @Test
    fun defaultsAreIdentity() {
        val vp = ViewportController()
        assertEquals(0f, vp.offsetX, 0f)
        assertEquals(0f, vp.offsetY, 0f)
        assertEquals(1f, vp.scale, 0f)
        assertEquals(120f, vp.screenToWorldX(120f), 0f)
        assertEquals(80f, vp.worldToScreenY(80f), 0f)
    }

    @Test
    fun screenToWorldRoundTripsForArbitraryViewport() {
        val vp = ViewportController(offsetX = 47f, offsetY = -23f, scale = 1.75f)
        for (sx in -200..200 step 37) {
            for (sy in -120..280 step 43) {
                val wx = vp.screenToWorldX(sx.toFloat())
                val wy = vp.screenToWorldY(sy.toFloat())
                assertEquals(sx.toFloat(), vp.worldToScreenX(wx), tol)
                assertEquals(sy.toFloat(), vp.worldToScreenY(wy), tol)
            }
        }
    }

    @Test
    fun applyPanShiftsOffsetAndFiresListener() {
        var fired = 0
        val vp = ViewportController(offsetX = 10f, offsetY = 20f)
        vp.onChanged = { fired++ }
        vp.applyPan(5f, -3f)
        assertEquals(15f, vp.offsetX, 0f)
        assertEquals(17f, vp.offsetY, 0f)
        assertEquals(1, fired)
        // Zero-delta pan is a no-op.
        vp.applyPan(0f, 0f)
        assertEquals(1, fired)
    }

    @Test
    fun zoomKeepsFocalPointWorldCoordInvariant() {
        val vp = ViewportController(offsetX = 50f, offsetY = 30f, scale = 1f)
        val fx = 200f
        val fy = 150f
        val worldBeforeX = vp.screenToWorldX(fx)
        val worldBeforeY = vp.screenToWorldY(fy)
        vp.applyZoom(fx, fy, 1.5f)
        // World coord under the focus screen point is unchanged after zoom.
        assertEquals(worldBeforeX, vp.screenToWorldX(fx), tol)
        assertEquals(worldBeforeY, vp.screenToWorldY(fy), tol)
        assertEquals(1.5f, vp.scale, tol)
    }

    @Test
    fun zoomChainedKeepsFocalPointInvariant() {
        val vp = ViewportController(offsetX = -10f, offsetY = 80f, scale = 1f)
        val fx = 320f
        val fy = 240f
        val worldBeforeX = vp.screenToWorldX(fx)
        val worldBeforeY = vp.screenToWorldY(fy)
        vp.applyZoom(fx, fy, 1.25f)
        vp.applyZoom(fx, fy, 1.6f)
        vp.applyZoom(fx, fy, 0.5f)
        assertEquals(worldBeforeX, vp.screenToWorldX(fx), tol)
        assertEquals(worldBeforeY, vp.screenToWorldY(fy), tol)
    }

    @Test
    fun zoomIsClampedToMinAndMax() {
        val vp = ViewportController(scale = 1f)
        vp.applyZoom(0f, 0f, 100f)
        assertEquals(ViewportController.MAX_SCALE, vp.scale, 0f)
        vp.applyZoom(0f, 0f, 0.0001f)
        assertEquals(ViewportController.MIN_SCALE, vp.scale, 0f)
    }

    @Test
    fun zoomAtClampReportsNoChange() {
        var fired = 0
        val vp = ViewportController(scale = ViewportController.MAX_SCALE)
        vp.onChanged = { fired++ }
        vp.applyZoom(50f, 50f, 4f) // already at max
        assertEquals(0, fired)
    }

    @Test
    fun negativeOrZeroZoomFactorsAreIgnored() {
        val vp = ViewportController(scale = 2f)
        vp.applyZoom(0f, 0f, 0f)
        vp.applyZoom(0f, 0f, -1f)
        assertEquals(2f, vp.scale, 0f)
    }

    @Test
    fun resetClearsState() {
        val vp = ViewportController(offsetX = 11f, offsetY = -7f, scale = 2.5f)
        var fired = 0
        vp.onChanged = { fired++ }
        vp.reset()
        assertEquals(0f, vp.offsetX, 0f)
        assertEquals(0f, vp.offsetY, 0f)
        assertEquals(1f, vp.scale, 0f)
        assertEquals(1, fired)
        vp.reset()
        assertEquals(1, fired) // idempotent no-op
    }

    @Test
    fun fitToContentScalesAndCentersBounds() {
        val vp = ViewportController(offsetX = 0f, offsetY = 0f, scale = 1f)
        // 1000-wide content into a 500-wide canvas with 24-px margins on
        // each side → usable = 452. scale = 452/1000 = 0.452.
        val bounds = floatArrayOf(-500f, -200f, 500f, 200f) // 1000 × 400 world
        val canvas = floatArrayOf(500f, 300f)
        vp.fitToContent(bounds, canvas)
        assertTrue(
            "scale should fit horizontally: ${vp.scale}",
            vp.scale in 0.4f..0.46f,
        )
        // Content centre `(0, 0)` should land at canvas centre.
        assertEquals(250f, vp.worldToScreenX(0f), tol)
        assertEquals(150f, vp.worldToScreenY(0f), tol)
    }

    @Test
    fun fitToContentIgnoresEmptyBoundsOrCanvas() {
        val vp = ViewportController(offsetX = 11f, offsetY = 22f, scale = 1.5f)
        val canvas = floatArrayOf(500f, 300f)
        // Zero-width bounds.
        vp.fitToContent(floatArrayOf(0f, 0f, 0f, 100f), canvas)
        assertEquals(1.5f, vp.scale, 0f)
        assertEquals(11f, vp.offsetX, 0f)
        // Zero-size canvas.
        vp.fitToContent(floatArrayOf(0f, 0f, 100f, 100f), floatArrayOf(0f, 300f))
        assertEquals(1.5f, vp.scale, 0f)
    }

    @Test
    fun centerOnContentPansWithoutScaling() {
        val vp = ViewportController(offsetX = 0f, offsetY = 0f, scale = 2f)
        val bounds = floatArrayOf(50f, 60f, 150f, 200f) // centre (100, 130)
        val canvas = floatArrayOf(400f, 300f)
        vp.centerOnContent(bounds, canvas)
        assertEquals(2f, vp.scale, 0f)
        // World centre (100, 130) under canvas centre (200, 150) at scale 2.
        // offsetX = 200 - 100*2 = 0; offsetY = 150 - 130*2 = -110.
        assertEquals(0f, vp.offsetX, tol)
        assertEquals(-110f, vp.offsetY, tol)
    }

    @Test
    fun resetToOneHundredPreservesCanvasCentreWorldPoint() {
        val vp = ViewportController(offsetX = -250f, offsetY = -150f, scale = 2f)
        val canvas = floatArrayOf(500f, 400f)
        val worldCxBefore = vp.screenToWorldX(canvas[0] * 0.5f)
        val worldCyBefore = vp.screenToWorldY(canvas[1] * 0.5f)
        vp.resetToOneHundred(canvas)
        assertEquals(1f, vp.scale, 0f)
        // Same world point should still be at the canvas centre.
        assertEquals(worldCxBefore, vp.screenToWorldX(canvas[0] * 0.5f), tol)
        assertEquals(worldCyBefore, vp.screenToWorldY(canvas[1] * 0.5f), tol)
    }

    @Test
    fun resetToOneHundredIsNoOpAtCorrectScale() {
        val vp = ViewportController(offsetX = 7f, offsetY = -9f, scale = 1f)
        var fired = 0
        vp.onChanged = { fired++ }
        vp.resetToOneHundred(floatArrayOf(500f, 400f))
        assertEquals(0, fired)
        assertEquals(7f, vp.offsetX, 0f)
        assertEquals(-9f, vp.offsetY, 0f)
    }

    @Test
    fun pinchAroundFingertipKeepsItUnderFingertip() {
        // Simulates a pinch where the user is squeezing around (fx, fy) on
        // screen. The world point under their fingertips must stay under
        // their fingertips throughout the gesture.
        val vp = ViewportController(offsetX = 25f, offsetY = 40f, scale = 1f)
        val fx = 500f
        val fy = 700f
        val worldX = vp.screenToWorldX(fx)
        val worldY = vp.screenToWorldY(fy)
        // Many small factors approximating a slow squeeze in & out.
        listOf(1.05f, 1.05f, 1.05f, 0.9f, 0.9f, 1.2f, 0.8f).forEach {
            vp.applyZoom(fx, fy, it)
            assertEquals(worldX, vp.screenToWorldX(fx), tol)
            assertEquals(worldY, vp.screenToWorldY(fy), tol)
        }
        assertTrue(vp.scale in ViewportController.MIN_SCALE..ViewportController.MAX_SCALE)
    }

    // ── Bounded pan (icon artboard) ──────────────────────────────────────

    /** Square 768 artboard; a phone-ish portrait canvas. */
    private val artboard = floatArrayOf(0f, 0f, 768f, 768f)

    @Test
    fun panClampCentersArtboardWhenSmallerThanViewport() {
        val vp = ViewportController()
        val canvas = floatArrayOf(1080f, 1920f)
        // At scale 1, the 768 artboard fits both axes → must centre.
        vp.setPanBounds(artboard, canvas)
        // A large fling shouldn't move it: centring is absolute.
        vp.applyPan(5000f, -5000f)
        // Artboard midpoint (384, 384) must sit at canvas centre.
        assertEquals(540f, vp.worldToScreenX(384f), tol)
        assertEquals(960f, vp.worldToScreenY(384f), tol)
    }

    @Test
    fun panClampKeepsArtboardCoveringViewportWhenZoomedIn() {
        val vp = ViewportController(scale = 4f) // 768*4 = 3072 px, overflows 1080
        val canvas = floatArrayOf(1080f, 1080f)
        vp.setPanBounds(artboard, canvas)
        // Fling far right then far left; the artboard must always cover the
        // viewport — no gap at either edge.
        vp.applyPan(9000f, 0f)
        assertTrue("left edge must not leave a gap", vp.worldToScreenX(0f) <= 0f + tol)
        assertTrue("right edge must cover", vp.worldToScreenX(768f) >= 1080f - tol)
        vp.applyPan(-18000f, 0f)
        assertTrue(vp.worldToScreenX(0f) <= 0f + tol)
        assertTrue(vp.worldToScreenX(768f) >= 1080f - tol)
    }

    @Test
    fun panClampIsPerAxisIndependent() {
        // Landscape canvas: 768 fits horizontally (1600) but overflows
        // vertically (600) at scale 1.
        val vp = ViewportController(scale = 1f)
        val canvas = floatArrayOf(1600f, 600f)
        vp.setPanBounds(artboard, canvas)
        vp.applyPan(4000f, 4000f)
        // X centred: midpoint 384 at canvas centre 800.
        assertEquals(800f, vp.worldToScreenX(384f), tol)
        // Y panned but clamped so the artboard still covers the short axis.
        assertTrue(vp.worldToScreenY(0f) <= 0f + tol)
        assertTrue(vp.worldToScreenY(768f) >= 600f - tol)
    }

    @Test
    fun setPanBoundsReclampsExistingIllegalOffset() {
        // Start far off-screen (the "lost icon" state), then install bounds.
        val vp = ViewportController(offsetX = 99999f, offsetY = -99999f, scale = 1f)
        vp.setPanBounds(artboard, floatArrayOf(1080f, 1920f))
        // Snaps the artboard back to centred/visible.
        assertEquals(540f, vp.worldToScreenX(384f), tol)
        assertEquals(960f, vp.worldToScreenY(384f), tol)
    }

    @Test
    fun clearPanBoundsRestoresUnboundedPan() {
        val vp = ViewportController()
        vp.setPanBounds(artboard, floatArrayOf(1080f, 1920f))
        vp.clearPanBounds()
        // With the clamp gone, pan moves freely (notes parity): the full delta
        // applies on top of wherever the clamp last left the offset.
        val beforeX = vp.offsetX
        val beforeY = vp.offsetY
        vp.applyPan(5000f, 5000f)
        assertEquals(beforeX + 5000f, vp.offsetX, tol)
        assertEquals(beforeY + 5000f, vp.offsetY, tol)
    }

    @Test
    fun zoomOutFloorForIconsHonored() {
        val vp = ViewportController(scale = 1f)
        val canvas = floatArrayOf(1080f, 1920f)
        vp.setPanBounds(artboard, canvas)
        // Try to zoom way out; the floor keeps the artboard >= 40% of the
        // shorter canvas dim: floor = 0.4 * 1080 / 768 ≈ 0.5625.
        vp.applyZoom(540f, 960f, 0.0001f)
        val expectedFloor = ViewportController.ICON_MIN_FILL_FRACTION * 1080f / 768f
        assertEquals(expectedFloor, vp.scale, tol)
        assertTrue(vp.scale > ViewportController.MIN_SCALE)
    }

    @Test
    fun fitToContentStillWorksWithBoundsSet() {
        val vp = ViewportController()
        val canvas = floatArrayOf(1080f, 1920f)
        vp.setPanBounds(artboard, canvas)
        // Fit is exempt from the zoom floor and must centre the artboard.
        vp.fitToContent(artboard, canvas)
        assertEquals(540f, vp.worldToScreenX(384f), tol)
        assertEquals(960f, vp.worldToScreenY(384f), tol)
        assertTrue(vp.scale in ViewportController.MIN_SCALE..ViewportController.MAX_SCALE)
    }
}
