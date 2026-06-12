package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class PathConversionsTest {

    private fun decoded(shape: Shape, fill: Int = 0, style: Byte = ShapeCodec.STROKE_STYLE_SOLID) =
        ShapeCodec.DecodedShape(shape, fill, style)

    // ── shapes ───────────────────────────────────────────────────────────

    @Test
    fun lineConvertsToTwoCornerAnchors() {
        val paths = PathConversions.fromShape(
            decoded(Shape.Line(1f, 2f, 30f, 40f), style = ShapeCodec.STROKE_STYLE_DASHED),
            strokeColorArgb = 0xFF000000.toInt(),
        )
        assertEquals(1, paths.size)
        val p = paths[0]
        assertFalse(p.closed)
        assertEquals(2, p.anchors.size)
        assertEquals(1f, p.anchors[0].x, 1e-4f)
        assertEquals(40f, p.anchors[1].y, 1e-4f)
        assertEquals(ShapeCodec.STROKE_STYLE_DASHED, p.strokeStyle)
    }

    @Test
    fun rectConvertsToExactCorners() {
        val fill = 0x40109F5C
        val paths = PathConversions.fromShape(
            decoded(Shape.Rect(10f, 20f, 110f, 80f), fill = fill),
            strokeColorArgb = 0xFF000000.toInt(),
        )
        val p = paths.single()
        assertTrue(p.closed)
        assertEquals(4, p.anchors.size)
        assertEquals(fill, p.fillArgb)
        val xs = p.anchors.map { it.x }.sorted()
        val ys = p.anchors.map { it.y }.sorted()
        assertEquals(listOf(10f, 10f, 110f, 110f), xs)
        assertEquals(listOf(20f, 20f, 80f, 80f), ys)
        // Plain rect corners carry no handles.
        assertTrue(p.anchors.all { it.inDx == 0f && it.outDy == 0f })
    }

    @Test
    fun roundedRectCornersAreCircularArcs() {
        val r = 12f
        val paths = PathConversions.fromShape(
            decoded(Shape.Rect(0f, 0f, 100f, 60f, cornerRadius = r)),
            strokeColorArgb = 0xFF000000.toInt(),
        )
        val p = paths.single()
        assertEquals(8, p.anchors.size)
        assertTrue(p.closed)
        // Flattened path must stay inside the rect and reach the straight
        // edge midpoints exactly.
        val pts = PathCodec.flatten(p, stepsPerSegment = 32)
        var i = 0
        while (i < pts.size) {
            assertTrue(pts[i] >= -0.01f && pts[i] <= 100.01f)
            assertTrue(pts[i + 1] >= -0.01f && pts[i + 1] <= 60.01f)
            i += 2
        }
        val b = PathCodec.boundsOf(p)!!
        assertEquals(0f, b[0], 0.05f)
        assertEquals(0f, b[1], 0.05f)
        assertEquals(100f, b[2], 0.05f)
        assertEquals(60f, b[3], 0.05f)
    }

    @Test
    fun ellipseConvertsWithinKappaTolerance() {
        val cx = 50f; val cy = 30f; val rx = 40f; val ry = 25f
        val paths = PathConversions.fromShape(
            decoded(Shape.Ellipse(cx, cy, rx, ry)),
            strokeColorArgb = 0xFF000000.toInt(),
        )
        val p = paths.single()
        assertTrue(p.closed)
        assertEquals(4, p.anchors.size)
        // Every flattened point must satisfy the ellipse equation within the
        // documented kappa error (< 0.03 % of the radius — use 0.5 % slack).
        val pts = PathCodec.flatten(p, stepsPerSegment = 32)
        var i = 0
        while (i < pts.size) {
            val ex = (pts[i] - cx) / rx
            val ey = (pts[i + 1] - cy) / ry
            assertEquals(1f, ex * ex + ey * ey, 0.005f)
            i += 2
        }
    }

    @Test
    fun rotatedEllipseRotatesAnchorsAndHandles() {
        val rot = (PI / 6).toFloat()
        val paths = PathConversions.fromShape(
            decoded(Shape.Ellipse(0f, 0f, 40f, 20f, rotationRad = rot)),
            strokeColorArgb = 0xFF000000.toInt(),
        )
        val p = paths.single()
        // First anchor is the rotated (rx, 0) point.
        assertEquals(40f * cos(rot), p.anchors[0].x, 1e-3f)
        assertEquals(40f * sin(rot), p.anchors[0].y, 1e-3f)
        // Points still satisfy the rotated-frame ellipse equation.
        val pts = PathCodec.flatten(p, stepsPerSegment = 32)
        var i = 0
        while (i < pts.size) {
            val lx = cos(-rot) * pts[i] - sin(-rot) * pts[i + 1]
            val ly = sin(-rot) * pts[i] + cos(-rot) * pts[i + 1]
            assertEquals(1f, (lx / 40f) * (lx / 40f) + (ly / 20f) * (ly / 20f), 0.005f)
            i += 2
        }
    }

    @Test
    fun polygonConvertsVerbatim() {
        val pts = floatArrayOf(0f, 0f, 50f, 10f, 30f, 60f)
        val paths = PathConversions.fromShape(
            decoded(Shape.Polygon(pts, closed = true), fill = 0x20FF0000),
            strokeColorArgb = 0xFF000000.toInt(),
        )
        val p = paths.single()
        assertTrue(p.closed)
        assertEquals(0x20FF0000, p.fillArgb)
        assertEquals(3, p.anchors.size)
        assertEquals(50f, p.anchors[1].x, 1e-4f)
        assertEquals(60f, p.anchors[2].y, 1e-4f)
    }

    @Test
    fun openPolylineDropsFill() {
        val paths = PathConversions.fromShape(
            decoded(Shape.Polygon(floatArrayOf(0f, 0f, 50f, 10f, 30f, 60f), closed = false), fill = 0x20FF0000),
            strokeColorArgb = 0xFF000000.toInt(),
        )
        assertEquals(0, paths.single().fillArgb)
        assertFalse(paths.single().closed)
    }

    @Test
    fun arrowConvertsToShaftPlusFilledHead() {
        val color = 0xFFD62828.toInt()
        val paths = PathConversions.fromShape(
            decoded(Shape.Arrow(0f, 0f, 100f, 0f, headSize = 24f)),
            strokeColorArgb = color,
        )
        assertEquals(2, paths.size)
        val (shaft, head) = paths
        assertFalse(shaft.closed)
        assertEquals(2, shaft.anchors.size)
        assertTrue(head.closed)
        assertEquals(3, head.anchors.size)
        assertEquals(color, head.fillArgb)
        // Head tip sits on the arrow tip.
        assertEquals(100f, head.anchors[0].x, 1e-4f)
        assertEquals(0f, head.anchors[0].y, 1e-4f)
    }

    // ── strokes ──────────────────────────────────────────────────────────

    private fun packedSamples(points: List<Pair<Float, Float>>): FloatArray {
        val s = StrokeCodec.FLOATS_PER_SAMPLE
        val out = FloatArray(points.size * s)
        for ((i, p) in points.withIndex()) {
            out[i * s] = p.first
            out[i * s + 1] = p.second
            out[i * s + 2] = 0.5f
            out[i * s + 3] = 0f
        }
        return out
    }

    @Test
    fun smoothStrokeFitsWithFewAnchorsAndLowError() {
        // Dense sine wave: 200 samples.
        val pts = (0 until 200).map { i ->
            val x = i * 2f
            x to (40f * sin(x / 60f))
        }
        val samples = packedSamples(pts)
        val payload = PathConversions.fromStroke(samples, pts.size)
        assertNotNull(payload)
        assertFalse(payload!!.closed)
        // Massive reduction: a smooth curve needs a handful of anchors.
        assertTrue("anchors=${payload.anchors.size}", payload.anchors.size <= 20)
        // Max deviation of the original samples from the fitted curve.
        val flat = PathCodec.flatten(payload, stepsPerSegment = 32)
        var maxDev = 0f
        for (p in pts) {
            var best = Float.MAX_VALUE
            var j = 0
            while (j < flat.size) {
                val d = hypot(p.first - flat[j], p.second - flat[j + 1])
                if (d < best) best = d
                j += 2
            }
            if (best > maxDev) maxDev = best
        }
        assertTrue("max deviation $maxDev", maxDev < PathConversions.STROKE_FIT_MAX_ERROR * 2f)
    }

    @Test
    fun nearClosedStrokeClosesThePath() {
        // A circle drawn with a small start/end gap.
        val pts = (0 until 72).map { i ->
            val a = i / 72f * 2f * PI.toFloat() * 0.98f
            (100f + 50f * cos(a)) to (100f + 50f * sin(a))
        }
        val payload = PathConversions.fromStroke(packedSamples(pts), pts.size)
        assertNotNull(payload)
        assertTrue(payload!!.closed)
    }

    @Test
    fun openStrokeStaysOpen() {
        val pts = (0 until 50).map { i -> (i * 4f) to (i * 2f) }
        val payload = PathConversions.fromStroke(packedSamples(pts), pts.size)
        assertNotNull(payload)
        assertFalse(payload!!.closed)
        // A straight stroke collapses to very few anchors.
        assertTrue(payload.anchors.size <= 4)
    }

    @Test
    fun tooShortStrokeReturnsNull() {
        val payload = PathConversions.fromStroke(packedSamples(listOf(1f to 1f)), 1)
        assertEquals(null, payload)
    }

    @Test
    fun zigZagKeepsCornerAnchors() {
        // Sharp zig-zag: corners must be classified TYPE_CORNER.
        val pts = buildList {
            for (i in 0..20) add(i * 10f to 0f)
            for (i in 1..20) add(200f to i * 10f)
        }
        val payload = PathConversions.fromStroke(packedSamples(pts), pts.size)
        assertNotNull(payload)
        assertTrue(payload!!.anchors.any { it.type == PathCodec.TYPE_CORNER })
    }

    // ── outline stroke (phase 15.2) ──────────────────────────────────────

    /** Packed samples along y=0 with a pressure ramp. */
    private fun rampSamples(count: Int, baseX: Float = 0f): FloatArray {
        val s = StrokeCodec.FLOATS_PER_SAMPLE
        val out = FloatArray(count * s)
        for (i in 0 until count) {
            out[i * s] = baseX + i * 10f
            out[i * s + 1] = 0f
            out[i * s + 2] = 0.2f + 0.8f * i / (count - 1).coerceAtLeast(1)
            out[i * s + 3] = 0f
        }
        return out
    }

    @Test
    fun strokeOutlineIsClosedAndCompact() {
        val payload = PathConversions.fromStrokeOutline(
            rampSamples(30), StrokeRenderer.TOOL_PEN, baseWidthPx = 8f,
        )
        assertNotNull(payload)
        assertTrue(payload!!.closed)
        // The raw outline has 2·30 + 14 vertices; the fit must shrink it.
        assertTrue("anchors=${payload.anchors.size}", payload.anchors.size < 40)
    }

    @Test
    fun strokeOutlineTracksPressureWidth() {
        val payload = PathConversions.fromStrokeOutline(
            rampSamples(30), StrokeRenderer.TOOL_PEN, baseWidthPx = 8f,
        )!!
        val flat = PathCodec.flatten(payload, stepsPerSegment = 16)
        // The stroke runs along y=0 with pressure ramping up in +x; the
        // outline's |y| extent must grow with x. Sample two x-windows away
        // from the caps.
        var startHalf = 0f
        var endHalf = 0f
        var j = 0
        while (j < flat.size) {
            val x = flat[j]
            val absY = abs(flat[j + 1])
            if (x in 40f..80f) startHalf = maxOf(startHalf, absY)
            if (x in 210f..250f) endHalf = maxOf(endHalf, absY)
            j += 2
        }
        assertTrue("start=$startHalf end=$endHalf", endHalf > startHalf)
    }

    @Test
    fun strokeOutlineOfEmptyStrokeIsNull() {
        assertEquals(
            null,
            PathConversions.fromStrokeOutline(FloatArray(0), StrokeRenderer.TOOL_PEN, 4f),
        )
    }
}
