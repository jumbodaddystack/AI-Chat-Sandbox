package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 16.1 — multi-subpath wire format. The cardinal rule under test: payloads
 * that were representable before 16.1 (one subpath, non-zero fill rule)
 * must still encode as byte-identical v1 streams; everything else rides
 * the v2 stream and round-trips losslessly.
 */
class PathCodecV2Test {

    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float, closed: Boolean = true) =
        PathCodec.Subpath(
            anchors = listOf(
                PathCodec.Anchor(x0, y0),
                PathCodec.Anchor(x1, y0),
                PathCodec.Anchor(x1, y1),
                PathCodec.Anchor(x0, y1),
            ),
            closed = closed,
        )

    private fun donut(fillRule: Byte = PathCodec.FILL_RULE_EVEN_ODD) = PathCodec.PathPayload(
        subpaths = listOf(rect(0f, 0f, 100f, 100f), rect(25f, 25f, 75f, 75f)),
        fillRule = fillRule,
        fillArgb = 0xFF2463EB.toInt(),
        strokeStyle = ShapeCodec.STROKE_STYLE_DASHED,
        capJoin = PathCodec.capJoinOf(PathCodec.CAP_SQUARE, PathCodec.JOIN_BEVEL),
        gradient = FillStyle.linear(0xFFFF0000.toInt(), 0xFF00FF00.toInt()),
    )

    @Test
    fun singleSubpathNonZeroStillEncodesAsV1() {
        val payload = PathCodec.PathPayload(
            anchors = listOf(
                PathCodec.Anchor(0f, 0f, outDx = 5f, outDy = 2f, type = PathCodec.TYPE_SMOOTH),
                PathCodec.Anchor(10f, 10f, inDx = -5f, inDy = -2f),
            ),
            closed = true,
            fillArgb = 0xFF112233.toInt(),
        )
        val bytes = PathCodec.encode(payload)
        assertEquals(PathCodec.VERSION, bytes[0])
        assertEquals(payload, PathCodec.decode(bytes))
    }

    @Test
    fun multiSubpathRoundTripsThroughV2() {
        val payload = donut()
        val bytes = PathCodec.encode(payload)
        assertEquals(PathCodec.VERSION_2, bytes[0])
        assertEquals(payload, PathCodec.decode(bytes))
    }

    @Test
    fun singleSubpathEvenOddEncodesAsV2() {
        val payload = PathCodec.PathPayload(
            subpaths = listOf(rect(0f, 0f, 10f, 10f)),
            fillRule = PathCodec.FILL_RULE_EVEN_ODD,
        )
        val bytes = PathCodec.encode(payload)
        assertEquals(PathCodec.VERSION_2, bytes[0])
        assertEquals(payload, PathCodec.decode(bytes))
    }

    @Test
    fun v2TruncatedAfterAnchorsDecodesWithDefaults() {
        val payload = PathCodec.PathPayload(
            subpaths = listOf(rect(0f, 0f, 10f, 10f), rect(2f, 2f, 8f, 8f)),
            fillRule = PathCodec.FILL_RULE_EVEN_ODD,
        )
        val full = PathCodec.encode(payload)
        // Header (1+1+2) + 2 × (subFlags 1 + count 2 + 4 anchors × 25).
        val geometryBytes = 4 + 2 * (3 + 4 * 25)
        val truncated = full.copyOf(geometryBytes)
        val decoded = PathCodec.decode(truncated)
        assertEquals(payload.subpaths, decoded.subpaths)
        assertEquals(PathCodec.FILL_RULE_EVEN_ODD, decoded.fillRule)
        assertEquals(0, decoded.fillArgb)
        assertEquals(ShapeCodec.STROKE_STYLE_SOLID, decoded.strokeStyle)
        assertEquals(PathCodec.DEFAULT_CAP_JOIN, decoded.capJoin)
        assertEquals(null, decoded.gradient)
    }

    @Test
    fun transformMovesEverySubpath() {
        // The #1 corruption regression: a subpath-0-only transform would
        // leave the hole behind on every selection move.
        val payload = donut()
        val moved = PathCodec.transform(payload, floatArrayOf(1f, 0f, 30f, 0f, 1f, 40f))
        assertEquals(2, moved.subpaths.size)
        assertEquals(55f, moved.subpaths[1].anchors[0].x, 1e-4f)
        assertEquals(65f, moved.subpaths[1].anchors[0].y, 1e-4f)
        // Style + rule survive the rebuild.
        assertEquals(payload.fillRule, moved.fillRule)
        assertEquals(payload.gradient, moved.gradient)
    }

    @Test
    fun boundsUnionAllSubpathsAndFlattenAllSeesEveryRing() {
        val payload = PathCodec.PathPayload(
            subpaths = listOf(rect(0f, 0f, 10f, 10f), rect(90f, 90f, 100f, 100f)),
        )
        val b = PathCodec.boundsOf(payload)!!
        assertEquals(0f, b[0], 1e-4f)
        assertEquals(100f, b[2], 1e-4f)
        assertEquals(2, PathCodec.flattenAll(payload).size)
    }

    @Test
    fun evenOddDonutHitTestExcludesTheHole() {
        // Same-wound rings under even-odd parity: hole punches through.
        val payload = donut()
        assertTrue(HitTest.pathContainsPoint(payload, 10f, 50f, radius = 0.5f))
        assertFalse(HitTest.pathContainsPoint(payload, 50f, 50f, radius = 0.5f))
    }

    @Test
    fun legacyAccessorsReadSubpathZero() {
        val payload = donut()
        assertEquals(payload.subpaths[0].anchors, payload.anchors)
        assertTrue(payload.closed)
        assertTrue(payload.anyClosed)
        assertEquals(4, payload.segmentCount)
    }
}
