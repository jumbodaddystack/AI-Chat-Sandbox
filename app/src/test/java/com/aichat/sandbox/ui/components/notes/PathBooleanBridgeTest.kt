package com.aichat.sandbox.ui.components.notes

import com.aichat.sandbox.data.vector.edit.boolean.PathBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 13.1 — boolean ops over [PathCodec.PathPayload]s via the pure
 * flatten → clip → refit pipeline. 16.1 — results come back as ONE
 * multi-subpath payload so holes survive. Areas are checked by shoelace
 * over the flattened rings, with a tolerance covering the refit error
 * budget.
 */
class PathBooleanBridgeTest {

    private fun rectPath(x0: Float, y0: Float, x1: Float, y1: Float, closed: Boolean = true) =
        PathCodec.PathPayload(
            anchors = listOf(
                PathCodec.Anchor(x0, y0),
                PathCodec.Anchor(x1, y0),
                PathCodec.Anchor(x1, y1),
                PathCodec.Anchor(x0, y1),
            ),
            closed = closed,
        )

    private fun ringArea(pts: FloatArray): Float {
        var sum = 0.0
        var i = 0
        while (i + 3 < pts.size) {
            sum += pts[i].toDouble() * pts[i + 3] - pts[i + 2].toDouble() * pts[i + 1]
            i += 2
        }
        sum += pts[pts.size - 2].toDouble() * pts[1] - pts[0].toDouble() * pts[pts.size - 1]
        return abs(sum / 2.0).toFloat()
    }

    /** Sum of per-ring |areas| — for crescents; holes count positive too. */
    private fun totalRingArea(payload: PathCodec.PathPayload): Float =
        payload.subpaths.map { ringArea(PathCodec.flatten(it)) }.sum()

    private fun combine(op: PathBoolean.Op, vararg payloads: PathCodec.PathPayload) =
        PathBooleanBridge.combine(payloads.map { listOf(it) }, op)

    private fun assertBoundsClose(expected: FloatArray, actual: FloatArray, eps: Float = 1.5f) {
        for (i in 0..3) {
            assertTrue(
                "bounds[$i] expected ${expected[i]} got ${actual[i]}",
                abs(expected[i] - actual[i]) <= eps,
            )
        }
    }

    @Test
    fun unionOfOverlappingRectsIsOneRing() {
        val result = combine(
            PathBoolean.Op.UNION,
            rectPath(0f, 0f, 100f, 100f),
            rectPath(50f, 50f, 150f, 150f),
        )
        assertNotNull(result)
        assertEquals(1, result!!.subpaths.size)
        assertTrue(result.closed)
        assertBoundsClose(floatArrayOf(0f, 0f, 150f, 150f), PathCodec.boundsOf(result)!!)
        // 2 × 100² − 50² overlap.
        assertEquals(17500f, totalRingArea(result), 350f)
    }

    @Test
    fun subtractRemovesTheTopItemFromTheSubject() {
        val result = combine(
            PathBoolean.Op.SUBTRACT,
            rectPath(0f, 0f, 100f, 100f),
            rectPath(50f, 50f, 150f, 150f),
        )
        assertNotNull(result)
        assertBoundsClose(floatArrayOf(0f, 0f, 100f, 100f), PathCodec.boundsOf(result!!)!!)
        assertEquals(7500f, totalRingArea(result), 150f)
    }

    @Test
    fun intersectKeepsTheOverlap() {
        val result = combine(
            PathBoolean.Op.INTERSECT,
            rectPath(0f, 0f, 100f, 100f),
            rectPath(50f, 50f, 150f, 150f),
        )
        assertNotNull(result)
        assertEquals(1, result!!.subpaths.size)
        assertBoundsClose(floatArrayOf(50f, 50f, 100f, 100f), PathCodec.boundsOf(result)!!)
        assertEquals(2500f, totalRingArea(result), 50f)
    }

    @Test
    fun excludeYieldsOnePayloadWithBothCrescents() {
        // 16.1 — was two separate payloads; now one payload, two subpaths.
        val result = combine(
            PathBoolean.Op.EXCLUDE,
            rectPath(0f, 0f, 100f, 100f),
            rectPath(50f, 50f, 150f, 150f),
        )
        assertNotNull(result)
        assertEquals(2, result!!.subpaths.size)
        assertTrue(result.subpaths.all { it.closed })
        assertEquals(15000f, totalRingArea(result), 300f)
    }

    @Test
    fun subtractConcentricRectsKeepsHoleAsSubpath() {
        // 16.1 — the donut case the pre-16.1 bridge couldn't represent.
        val result = combine(
            PathBoolean.Op.SUBTRACT,
            rectPath(0f, 0f, 100f, 100f),
            rectPath(25f, 25f, 75f, 75f),
        )
        assertNotNull(result)
        assertEquals(2, result!!.subpaths.size)
        // |outer| + |hole| ring areas: 100² + 50².
        assertEquals(12500f, totalRingArea(result), 300f)
        // The hole actually punches through: a point in the ring hits, a
        // point inside the hole does not.
        assertTrue(HitTest.pathContainsPoint(result, 10f, 50f, radius = 0.5f))
        assertFalse(HitTest.pathContainsPoint(result, 50f, 50f, radius = 0.5f))
    }

    @Test
    fun recombiningAHoleResultKeepsTheHole() {
        // 16.1 input-side expansion: a multi-subpath payload fed back into
        // combine must contribute all its rings, not just subpath 0.
        val donut = combine(
            PathBoolean.Op.SUBTRACT,
            rectPath(0f, 0f, 100f, 100f),
            rectPath(25f, 25f, 75f, 75f),
        )!!
        val result = PathBooleanBridge.combine(
            listOf(listOf(donut), listOf(rectPath(200f, 0f, 250f, 50f))),
            PathBoolean.Op.UNION,
        )
        assertNotNull(result)
        // Donut ring + hole + the disjoint rect.
        assertEquals(3, result!!.subpaths.size)
        assertFalse(HitTest.pathContainsPoint(result, 50f, 50f, radius = 0.5f))
        assertTrue(HitTest.pathContainsPoint(result, 225f, 25f, radius = 0.5f))
    }

    @Test
    fun disjointIntersectIsEmpty() {
        assertNull(
            combine(
                PathBoolean.Op.INTERSECT,
                rectPath(0f, 0f, 10f, 10f),
                rectPath(500f, 500f, 510f, 510f),
            ),
        )
    }

    @Test
    fun openInputsAreImplicitlyClosed() {
        val result = combine(
            PathBoolean.Op.UNION,
            rectPath(0f, 0f, 100f, 100f, closed = false),
            rectPath(50f, 50f, 150f, 150f),
        )
        assertNotNull(result)
        assertEquals(17500f, totalRingArea(result!!), 350f)
    }

    @Test
    fun fewerThanTwoUsableInputsIsEmpty() {
        assertNull(PathBooleanBridge.combine(emptyList(), PathBoolean.Op.UNION))
        assertNull(
            PathBooleanBridge.combine(
                listOf(listOf(rectPath(0f, 0f, 10f, 10f))),
                PathBoolean.Op.UNION,
            ),
        )
        // A degenerate (single-anchor) second input drops out.
        val degenerate = PathCodec.PathPayload(
            anchors = listOf(PathCodec.Anchor(0f, 0f)),
            closed = false,
        )
        assertNull(
            PathBooleanBridge.combine(
                listOf(listOf(rectPath(0f, 0f, 10f, 10f)), listOf(degenerate)),
                PathBoolean.Op.UNION,
            ),
        )
    }

    @Test
    fun subpathRoundTripPreservesHandlesAndTypes() {
        val payload = PathCodec.PathPayload(
            anchors = listOf(
                PathCodec.Anchor(0f, 0f, outDx = 10f, outDy = 5f, type = PathCodec.TYPE_SMOOTH),
                PathCodec.Anchor(
                    100f, 50f,
                    inDx = -10f, inDy = -5f, outDx = 10f, outDy = 5f,
                    type = PathCodec.TYPE_SYMMETRIC,
                ),
                PathCodec.Anchor(200f, 0f, inDx = -10f, inDy = 5f),
            ),
            closed = true,
        )
        val sub = PathBooleanBridge.toSubpath(payload.subpaths[0], "t")
        val back = PathBooleanBridge.subpathOf(sub)
        assertEquals(payload.anchors.size, back.anchors.size)
        for ((a, b) in payload.anchors.zip(back.anchors)) {
            assertEquals(a.x, b.x, 1e-4f)
            assertEquals(a.y, b.y, 1e-4f)
            assertEquals(a.inDx, b.inDx, 1e-4f)
            assertEquals(a.inDy, b.inDy, 1e-4f)
            assertEquals(a.outDx, b.outDx, 1e-4f)
            assertEquals(a.outDy, b.outDy, 1e-4f)
            assertEquals(a.type, b.type)
        }
        assertTrue(back.closed)
    }
}
