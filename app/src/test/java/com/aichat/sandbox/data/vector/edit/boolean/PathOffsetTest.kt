package com.aichat.sandbox.data.vector.edit.boolean

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class PathOffsetTest {

    private val round = StrokeOutliner.LineJoin.ROUND

    @Test
    fun offset_positiveDelta_growsArea_predictably() {
        val r = 20f
        val circle = BoolTestShapes.shape(BoolTestShapes.polygonCircle(0f, 0f, r))
        val grown = PathOffset.offset(circle, delta = 5f, join = round)
        val expected = (PI * (r + 5f) * (r + 5f)).toFloat()
        // Within ~5% (flatten + round-join approximation).
        assertTrue("grown area ${grown.area} vs ~$expected", grown.area in expected * 0.93f..expected * 1.05f)
        assertTrue(grown.area > circle.area)
    }

    @Test
    fun offset_negativeDelta_shrinksArea() {
        val circle = BoolTestShapes.shape(BoolTestShapes.polygonCircle(0f, 0f, 20f))
        val shrunk = PathOffset.offset(circle, delta = -5f, join = round)
        assertTrue(!shrunk.isEmpty)
        assertTrue(shrunk.area < circle.area)
    }

    @Test
    fun offset_overShrink_returnsEmptyShape_noCrash() {
        val circle = BoolTestShapes.shape(BoolTestShapes.polygonCircle(0f, 0f, 5f))
        val shrunk = PathOffset.offset(circle, delta = -50f, join = round)
        assertTrue(shrunk.isEmpty)
    }

    @Test
    fun offset_concavePolygon_selfUnionCleansSelfIntersections() {
        // An L-shaped (concave) polygon; growing must not throw or invert.
        val l = BoolTestShapes.shape(
            Ring(
                listOf(
                    com.aichat.sandbox.data.vector.VectorPoint(0f, 0f),
                    com.aichat.sandbox.data.vector.VectorPoint(10f, 0f),
                    com.aichat.sandbox.data.vector.VectorPoint(10f, 4f),
                    com.aichat.sandbox.data.vector.VectorPoint(4f, 4f),
                    com.aichat.sandbox.data.vector.VectorPoint(4f, 10f),
                    com.aichat.sandbox.data.vector.VectorPoint(0f, 10f),
                ),
            ),
        )
        val grown = PathOffset.offset(l, delta = 2f, join = round)
        assertTrue(!grown.isEmpty)
        assertTrue(grown.area > l.area)
    }
}
