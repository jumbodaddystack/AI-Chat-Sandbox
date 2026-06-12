package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class StrokeOutlinerTest {

    /** Packed `[x,y,p,tilt]` samples along y=0 with the given pressures. */
    private fun rampSamples(vararg pressures: Float): FloatArray {
        val out = FloatArray(pressures.size * StrokeCodec.FLOATS_PER_SAMPLE)
        for ((i, p) in pressures.withIndex()) {
            out[i * 4] = i * 50f
            out[i * 4 + 1] = 0f
            out[i * 4 + 2] = p
            out[i * 4 + 3] = 0f
        }
        return out
    }

    @Test
    fun uniformPressureIsNotVariable() {
        assertFalse(
            StrokeOutliner.hasVariableWidth(rampSamples(1f, 1f, 1f), StrokeRenderer.TOOL_PEN, 4f)
        )
    }

    @Test
    fun varyingPressureIsVariable() {
        assertTrue(
            StrokeOutliner.hasVariableWidth(rampSamples(0.2f, 0.6f, 1f), StrokeRenderer.TOOL_PEN, 4f)
        )
    }

    @Test
    fun highlighterIsNeverVariable() {
        // Highlighter width ignores pressure by design (ToolDynamics).
        assertFalse(
            StrokeOutliner.hasVariableWidth(
                rampSamples(0.2f, 0.6f, 1f), StrokeRenderer.TOOL_HIGHLIGHTER, 12f,
            )
        )
    }

    @Test
    fun singleSampleEmitsCircleAroundPoint() {
        val samples = floatArrayOf(10f, 20f, 0.8f, 0f)
        val outline = StrokeOutliner.outline(samples, StrokeRenderer.TOOL_PEN, 4f)
        assertTrue(outline.size >= 6 * 2)
        val expectedR = ToolDynamics.pen(4f, 0.8f, 0f).widthPx / 2f
        var i = 0
        while (i < outline.size) {
            val r = hypot(outline[i] - 10f, outline[i + 1] - 20f)
            assertEquals(expectedR, r, 1e-3f)
            i += 2
        }
    }

    @Test
    fun outlineIsWiderWherePressureIsHigher() {
        // Pressure ramps up along +x, so the outline's |y| extent must grow.
        val samples = rampSamples(0.2f, 0.4f, 0.6f, 0.8f, 1f)
        val outline = StrokeOutliner.outline(samples, StrokeRenderer.TOOL_PEN, 6f)
        var startHalfWidth = 0f
        var endHalfWidth = 0f
        var i = 0
        while (i < outline.size) {
            val x = outline[i]
            val absY = abs(outline[i + 1])
            // Sample the rails away from the end caps.
            if (x in 40f..60f) startHalfWidth = maxOf(startHalfWidth, absY)
            if (x in 140f..160f) endHalfWidth = maxOf(endHalfWidth, absY)
            i += 2
        }
        assertTrue(
            "outline should thicken with pressure (start=$startHalfWidth end=$endHalfWidth)",
            endHalfWidth > startHalfWidth,
        )
    }

    @Test
    fun outlineHalfWidthMatchesToolDynamics() {
        val samples = rampSamples(0.5f, 0.5f, 0.5f)
        val outline = StrokeOutliner.outline(samples, StrokeRenderer.TOOL_PEN, 6f)
        val expected = ToolDynamics.pen(6f, 0.5f, 0f).widthPx / 2f
        // The middle sample sits at x=50; its rail offsets are pure ±y.
        var found = false
        var i = 0
        while (i < outline.size) {
            if (abs(outline[i] - 50f) < 1e-3f) {
                assertEquals(expected, abs(outline[i + 1]), 1e-3f)
                found = true
            }
            i += 2
        }
        assertTrue("expected rail points above/below the middle sample", found)
    }

    @Test
    fun pathDataIsSmoothedAndClosed() {
        val samples = rampSamples(0.2f, 0.6f, 1f)
        val outline = StrokeOutliner.outline(samples, StrokeRenderer.TOOL_PEN, 4f)
        val d = StrokeOutliner.pathData(outline) { it.toString() }
        assertTrue(d.startsWith("M"))
        assertTrue(d.contains("Q"))
        assertTrue(d.endsWith("Z"))
    }

    @Test
    fun emptyStrokeEmitsNothing() {
        assertEquals(0, StrokeOutliner.outline(FloatArray(0), StrokeRenderer.TOOL_PEN, 4f).size)
        assertEquals("", StrokeOutliner.pathData(FloatArray(0)) { it.toString() })
    }

    @Test
    fun coincidentSamplesCollapseToDot() {
        val samples = floatArrayOf(
            5f, 5f, 0.3f, 0f,
            5f, 5f, 0.9f, 0f,
        )
        val outline = StrokeOutliner.outline(samples, StrokeRenderer.TOOL_PEN, 4f)
        // Widest radius seen at the point wins.
        val expectedR = ToolDynamics.pen(4f, 0.9f, 0f).widthPx / 2f
        var i = 0
        while (i < outline.size) {
            assertEquals(expectedR, hypot(outline[i] - 5f, outline[i + 1] - 5f), 1e-3f)
            i += 2
        }
    }
}
