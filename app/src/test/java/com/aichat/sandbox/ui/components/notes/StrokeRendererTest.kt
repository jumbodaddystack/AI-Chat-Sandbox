package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeRendererTest {

    private val halfPi = (Math.PI / 2.0).toFloat()

    @Test
    fun pressureCurveAtZeroIsMinGain() {
        assertEquals(0.4f, StrokeRenderer.pressureCurve(0f), 1e-6f)
    }

    @Test
    fun pressureCurveAtOneIsFull() {
        assertEquals(1.0f, StrokeRenderer.pressureCurve(1f), 1e-6f)
    }

    @Test
    fun pressureCurveClampsBothEnds() {
        assertEquals(0.4f, StrokeRenderer.pressureCurve(-1f), 1e-6f)
        assertEquals(1.0f, StrokeRenderer.pressureCurve(2f), 1e-6f)
    }

    @Test
    fun pressureCurveIsMonotonic() {
        var prev = StrokeRenderer.pressureCurve(0f)
        for (i in 1..10) {
            val current = StrokeRenderer.pressureCurve(i / 10f)
            assertTrue("non-monotonic at $i: prev=$prev cur=$current", current >= prev)
            prev = current
        }
    }

    @Test
    fun tiltFactorIsOneForNonPencilTools() {
        assertEquals(1f, StrokeRenderer.tiltFactor("pen", 0f), 0f)
        assertEquals(1f, StrokeRenderer.tiltFactor("pen", halfPi), 0f)
        assertEquals(1f, StrokeRenderer.tiltFactor("highlighter", halfPi), 0f)
        assertEquals(1f, StrokeRenderer.tiltFactor("eraser", halfPi), 0f)
        assertEquals(1f, StrokeRenderer.tiltFactor(null, halfPi), 0f)
    }

    @Test
    fun tiltFactorIsOneForUprightPencil() {
        assertEquals(1f, StrokeRenderer.tiltFactor("pencil", 0f), 1e-6f)
    }

    @Test
    fun tiltFactorGrowsWithPencilTilt() {
        val mid = StrokeRenderer.tiltFactor("pencil", halfPi / 2f)
        val flat = StrokeRenderer.tiltFactor("pencil", halfPi)
        assertTrue("midtilt should exceed upright", mid > 1f)
        assertTrue("flat should exceed midtilt", flat > mid)
        assertEquals(2.5f, flat, 1e-5f)
    }

    @Test
    fun tiltFactorClampsAboveHalfPi() {
        assertEquals(2.5f, StrokeRenderer.tiltFactor("pencil", Math.PI.toFloat()), 1e-5f)
        assertEquals(1.0f, StrokeRenderer.tiltFactor("pencil", -1f), 1e-5f)
    }

    @Test
    fun widthAtCombinesBaseAndModulation() {
        // pen, full pressure, no tilt → base.
        assertEquals(4f, StrokeRenderer.widthAt(4f, 1f, 0f, "pen"), 1e-6f)
        // pen, zero pressure → base * 0.4.
        assertEquals(1.6f, StrokeRenderer.widthAt(4f, 0f, 0f, "pen"), 1e-6f)
        // pencil, half pressure, flat tilt → base * (0.4 + 0.3) * 2.5 = 7.0.
        assertEquals(7.0f, StrokeRenderer.widthAt(4f, 0.5f, halfPi, "pencil"), 1e-5f)
    }

    @Test
    fun alphaAtPassesThroughForNonPencil() {
        assertEquals(255, StrokeRenderer.alphaAt(255, "pen", halfPi))
        assertEquals(76, StrokeRenderer.alphaAt(76, "highlighter", halfPi))
        assertEquals(200, StrokeRenderer.alphaAt(200, null, halfPi))
    }

    @Test
    fun alphaAtFadesPencilOnTilt() {
        // Upright pencil — full base alpha.
        assertEquals(200, StrokeRenderer.alphaAt(200, "pencil", 0f))
        // Flat pencil — down 50%.
        assertEquals(100, StrokeRenderer.alphaAt(200, "pencil", halfPi))
        // Half-tilted — between the two endpoints.
        val mid = StrokeRenderer.alphaAt(200, "pencil", halfPi / 2f)
        assertTrue("mid alpha $mid should be between 100 and 200", mid in 101..199)
    }

    @Test
    fun alphaAtClampsTiltOutsideRange() {
        assertEquals(200, StrokeRenderer.alphaAt(200, "pencil", -1f))
        assertEquals(100, StrokeRenderer.alphaAt(200, "pencil", Math.PI.toFloat()))
    }
}
