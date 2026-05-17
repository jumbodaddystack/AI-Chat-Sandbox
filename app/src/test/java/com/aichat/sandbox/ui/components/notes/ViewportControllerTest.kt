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
}
