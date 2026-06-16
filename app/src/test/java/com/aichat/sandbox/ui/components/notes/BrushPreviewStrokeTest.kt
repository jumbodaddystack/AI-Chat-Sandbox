package com.aichat.sandbox.ui.components.notes

import com.aichat.sandbox.data.model.BrushPreset
import com.aichat.sandbox.data.notes.BrushSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4 (N1) — deterministic geometry behind the brush preview swatch. */
class BrushPreviewStrokeTest {

    private fun spec(
        baseWidthPx: Float = 10f,
        opacity: Float = 1f,
        taperStart: Float = 0f,
        taperEnd: Float = 0f,
        jitter: Float = 0f,
        curve: String = BrushPreset.CURVE_LINEAR,
    ) = BrushSpec(
        name = "Test",
        tool = "pen",
        colorArgb = 0xFF112233.toInt(),
        baseWidthPx = baseWidthPx,
        opacity = opacity,
        taperStart = taperStart,
        taperEnd = taperEnd,
        jitter = jitter,
        pressureCurveId = curve,
        textureId = BrushPreset.TEXTURE_SMOOTH,
    )

    @Test
    fun sampleCountMatchesRequest() {
        assertEquals(32, brushPreviewSamples(spec(), width = 200f, height = 80f, count = 32).size)
    }

    @Test
    fun degenerateInputsYieldNoSamples() {
        assertTrue(brushPreviewSamples(spec(), width = 0f, height = 80f).isEmpty())
        assertTrue(brushPreviewSamples(spec(), width = 200f, height = 0f).isEmpty())
        assertTrue(brushPreviewSamples(spec(), width = 200f, height = 80f, count = 1).isEmpty())
    }

    @Test
    fun isDeterministicAcrossCalls() {
        val a = brushPreviewSamples(spec(jitter = 0.8f), width = 200f, height = 80f)
        val b = brushPreviewSamples(spec(jitter = 0.8f), width = 200f, height = 80f)
        assertEquals(a, b)
    }

    @Test
    fun taperThinsBothEnds() {
        val s = brushPreviewSamples(
            spec(taperStart = 0.3f, taperEnd = 0.3f),
            width = 200f,
            height = 80f,
        )
        val mid = s[s.size / 2].widthPx
        assertEquals(0f, s.first().widthPx, 1e-3f)
        assertEquals(0f, s.last().widthPx, 1e-3f)
        assertTrue("mid should be wider than the tapered ends", mid > s.first().widthPx)
    }

    @Test
    fun noTaperKeepsConstantWidthForLinearCurve() {
        val s = brushPreviewSamples(spec(baseWidthPx = 10f), width = 200f, height = 80f)
        // Linear curve + no taper + no jitter ⇒ every point paints the base width.
        s.forEach { assertEquals(10f, it.widthPx, 1e-3f) }
    }

    @Test
    fun alphaCarriesOpacityWithoutTaper() {
        val s = brushPreviewSamples(spec(opacity = 0.5f), width = 200f, height = 80f)
        s.forEach { assertEquals(0.5f, it.alpha, 1e-3f) }
    }

    @Test
    fun widthsAreNeverNegative() {
        val s = brushPreviewSamples(
            spec(baseWidthPx = 2f, taperStart = 0.5f, taperEnd = 0.5f, jitter = 1f),
            width = 200f,
            height = 80f,
        )
        assertTrue(s.all { it.widthPx >= 0f })
    }

    @Test
    fun pressureCurveShiftsTheWidestPoint() {
        // ease-in swells toward the end; ease-out toward the start. Compare the
        // heavier half of each profile (no taper, no jitter so only the curve moves).
        val easeIn = brushPreviewSamples(
            spec(curve = BrushPreset.CURVE_EASE_IN), width = 200f, height = 80f,
        )
        val easeOut = brushPreviewSamples(
            spec(curve = BrushPreset.CURVE_EASE_OUT), width = 200f, height = 80f,
        )
        assertTrue(easeIn.last().widthPx > easeIn.first().widthPx)
        assertTrue(easeOut.first().widthPx > easeOut.last().widthPx)
    }
}
