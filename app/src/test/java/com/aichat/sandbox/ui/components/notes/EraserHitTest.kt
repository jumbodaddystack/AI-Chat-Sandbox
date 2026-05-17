package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EraserHitTest {

    /** Builds a packed sample buffer from `(x, y)` pairs — pressure/tilt default to 1.0/0.0. */
    private fun stroke(vararg xy: Float): FloatArray {
        require(xy.size % 2 == 0)
        val count = xy.size / 2
        val out = FloatArray(count * StrokeCodec.FLOATS_PER_SAMPLE)
        for (i in 0 until count) {
            val src = i * 2
            val dst = i * StrokeCodec.FLOATS_PER_SAMPLE
            out[dst] = xy[src]
            out[dst + 1] = xy[src + 1]
            out[dst + 2] = 1f
            out[dst + 3] = 0f
        }
        return out
    }

    @Test
    fun emptyStrokeHasNoBounds() {
        assertNull(HitTest.boundsOf(FloatArray(0), 0))
    }

    @Test
    fun boundsOfSinglePointIsThatPoint() {
        val bounds = HitTest.boundsOf(stroke(10f, 20f), 1)!!
        assertEquals(10f, bounds[0], 0f)
        assertEquals(20f, bounds[1], 0f)
        assertEquals(10f, bounds[2], 0f)
        assertEquals(20f, bounds[3], 0f)
    }

    @Test
    fun boundsOfMultiSampleStrokeCoversExtents() {
        val s = stroke(0f, 0f, 50f, 10f, 25f, 40f, -5f, 5f)
        val bounds = HitTest.boundsOf(s, 4)!!
        assertEquals(-5f, bounds[0], 0f)
        assertEquals(0f, bounds[1], 0f)
        assertEquals(50f, bounds[2], 0f)
        assertEquals(40f, bounds[3], 0f)
    }

    @Test
    fun bboxFilterAcceptsPointsInsideExpandedBox() {
        val bounds = floatArrayOf(0f, 0f, 100f, 100f)
        assertTrue(HitTest.bboxContainsPoint(bounds, 50f, 50f, 6f))
        assertTrue(HitTest.bboxContainsPoint(bounds, -3f, 50f, 6f))   // within expansion
        assertFalse(HitTest.bboxContainsPoint(bounds, -10f, 50f, 6f)) // outside expansion
        assertFalse(HitTest.bboxContainsPoint(bounds, 50f, 200f, 6f))
    }

    @Test
    fun pointOnStrokeMatchesWithinRadius() {
        // Horizontal line from (0,0) to (100,0).
        val s = stroke(0f, 0f, 100f, 0f)
        // Eraser dead-centre on the line.
        assertTrue(HitTest.pointWithinStroke(s, 2, 50f, 0f, 4f))
        // Just above by 3px — within a 4px radius.
        assertTrue(HitTest.pointWithinStroke(s, 2, 50f, 3f, 4f))
        // 5px above — outside a 4px radius.
        assertFalse(HitTest.pointWithinStroke(s, 2, 50f, 5f, 4f))
    }

    @Test
    fun pointBeyondSegmentEndsIsRejected() {
        val s = stroke(0f, 0f, 100f, 0f)
        // Past the right end-cap.
        assertFalse(HitTest.pointWithinStroke(s, 2, 110f, 0f, 4f))
        // Within end-cap radius.
        assertTrue(HitTest.pointWithinStroke(s, 2, 102f, 0f, 4f))
    }

    @Test
    fun multiSegmentStrokeMatchesOnMiddleSegment() {
        val s = stroke(0f, 0f, 50f, 0f, 50f, 50f, 100f, 50f)
        // Sits next to the vertical middle segment.
        assertTrue(HitTest.pointWithinStroke(s, 4, 53f, 25f, 4f))
        // Outside all segments.
        assertFalse(HitTest.pointWithinStroke(s, 4, 25f, 25f, 4f))
    }

    @Test
    fun singleSamplePointActsAsCircle() {
        val s = stroke(10f, 10f)
        assertTrue(HitTest.pointWithinStroke(s, 1, 12f, 11f, 3f))
        assertFalse(HitTest.pointWithinStroke(s, 1, 20f, 10f, 3f))
    }

    @Test
    fun areaEraserRadiusCatchesMoreSegments() {
        val s = stroke(0f, 0f, 100f, 0f)
        // 15px off-axis: stroke eraser misses, area eraser hits.
        assertFalse(HitTest.pointWithinStroke(s, 2, 50f, 15f, 6f))
        assertTrue(HitTest.pointWithinStroke(s, 2, 50f, 15f, 24f))
    }
}
