package com.aichat.sandbox.data.vector.edit.boolean

import com.aichat.sandbox.data.vector.edit.boolean.BoolTestShapes.polygonCircle
import com.aichat.sandbox.data.vector.edit.boolean.BoolTestShapes.shape
import com.aichat.sandbox.data.vector.edit.boolean.BoolTestShapes.square
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 — golden-geometry tests for the polygon clipper. Geometry is asserted by
 * area / winding invariants within tolerance (flatten→clip is intentionally lossy),
 * not by exact float equality.
 */
class PolygonClipperTest {

    @Test
    fun union_ofTwoOverlappingCircles_areaWithinTolerance() {
        val a = shape(polygonCircle(0f, 0f, 10f))
        val b = shape(polygonCircle(10f, 0f, 10f))
        val union = PolygonClipper.clip(a, b, BoolOp.UNION)
        val inter = PolygonClipper.clip(a, b, BoolOp.INTERSECT)
        // |A∪B| + |A∩B| = |A| + |B|
        assertEquals(a.area + b.area, union.area + inter.area, 2f)
        assertTrue(union.area > a.area)
    }

    @Test
    fun subtract_overlappingCircles_makesCrescent_areaLessThanSubject() {
        val a = shape(polygonCircle(0f, 0f, 10f))
        val b = shape(polygonCircle(10f, 0f, 10f))
        val diff = PolygonClipper.clip(a, b, BoolOp.DIFFERENCE)
        val inter = PolygonClipper.clip(a, b, BoolOp.INTERSECT)
        assertTrue(diff.area > 0f)
        assertTrue(diff.area < a.area)
        // |A − B| = |A| − |A∩B|
        assertEquals(a.area - inter.area, diff.area, 2f)
    }

    @Test
    fun intersect_ofDisjointShapes_isEmpty() {
        val a = shape(polygonCircle(0f, 0f, 5f))
        val b = shape(polygonCircle(100f, 0f, 5f))
        val inter = PolygonClipper.clip(a, b, BoolOp.INTERSECT)
        assertTrue(inter.isEmpty)
    }

    @Test
    fun intersect_ofConcentricCircles_equalsSmaller() {
        val big = shape(polygonCircle(0f, 0f, 10f))
        val small = shape(polygonCircle(0f, 0f, 5f))
        val inter = PolygonClipper.clip(big, small, BoolOp.INTERSECT)
        assertEquals(small.area, inter.area, 1f)
    }

    @Test
    fun xor_ofOverlappingSquares_equalsUnionMinusIntersection_area() {
        val a = shape(square(0f, 0f, 10f))
        val b = shape(square(5f, 5f, 10f))
        val xor = PolygonClipper.clip(a, b, BoolOp.XOR)
        val union = PolygonClipper.clip(a, b, BoolOp.UNION)
        val inter = PolygonClipper.clip(a, b, BoolOp.INTERSECT)
        assertEquals(union.area - inter.area, xor.area, 0.5f)
    }

    @Test
    fun clip_selfIntersectingFigureEight_doesNotThrow_andProducesValidRings() {
        val eight = shape(BoolTestShapes.figureEight())
        val cleaned = PolygonClipper.selfUnion(eight)
        assertTrue(cleaned.rings.isNotEmpty())
        cleaned.rings.forEach { assertTrue(it.area > 0f) }
    }

    @Test
    fun clip_sharedCollinearEdges_squaresTouchingOnAnEdge_unionIsOneRing() {
        val a = shape(square(0f, 0f, 10f))
        val b = shape(square(10f, 0f, 10f)) // shares the x=10 edge
        val union = PolygonClipper.clip(a, b, BoolOp.UNION)
        assertEquals(1, union.rings.size)
        assertEquals(200f, union.area, 0.5f)
    }
}
