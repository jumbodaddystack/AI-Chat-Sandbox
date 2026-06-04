package com.aichat.sandbox.data.vector.edit.boolean

import com.aichat.sandbox.data.vector.VectorPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeOutlinerTest {

    private fun hLine(x0: Float, x1: Float, y: Float) =
        Ring(listOf(VectorPoint(x0, y), VectorPoint(x1, y)))

    @Test
    fun outline_straightHorizontalStroke_isRectangle_withinTolerance() {
        val center = hLine(0f, 20f, 0f)
        val shape = StrokeOutliner.outline(
            center, closed = false, width = 4f,
            cap = StrokeOutliner.LineCap.BUTT,
            join = StrokeOutliner.LineJoin.MITER, miterLimit = 4f,
        )
        // length 20 × width 4 = 80
        assertEquals(80f, shape.area, 0.5f)
        assertEquals(1, shape.rings.size)
    }

    @Test
    fun outline_closedSquareStroke_producesAnnulus_outerAndInnerRing() {
        val center = BoolTestShapes.square(0f, 0f, 20f)
        val shape = StrokeOutliner.outline(
            center, closed = true, width = 2f,
            cap = StrokeOutliner.LineCap.BUTT,
            join = StrokeOutliner.LineJoin.MITER, miterLimit = 4f,
        )
        assertEquals(2, shape.rings.size)
        // band ≈ perimeter(80) × width(2) = 160 (corners add a little)
        assertTrue(shape.area in 150f..180f)
    }

    @Test
    fun outline_roundCap_addsAreaVersusButtCap() {
        val center = hLine(0f, 20f, 0f)
        val butt = StrokeOutliner.outline(
            center, false, 4f, StrokeOutliner.LineCap.BUTT, StrokeOutliner.LineJoin.MITER, 4f,
        )
        val round = StrokeOutliner.outline(
            center, false, 4f, StrokeOutliner.LineCap.ROUND, StrokeOutliner.LineJoin.MITER, 4f,
        )
        assertTrue(round.area > butt.area)
    }

    @Test
    fun outline_miterJoin_respectsMiterLimit_fallsBackToBevel() {
        // A 90° corner: miter ratio ≈ 1.41, so a high limit miters and a low one bevels.
        val center = Ring(
            listOf(VectorPoint(0f, 0f), VectorPoint(10f, 0f), VectorPoint(10f, 10f)),
        )
        val mitered = StrokeOutliner.outline(
            center, false, 2f, StrokeOutliner.LineCap.BUTT, StrokeOutliner.LineJoin.MITER, 10f,
        )
        val beveled = StrokeOutliner.outline(
            center, false, 2f, StrokeOutliner.LineCap.BUTT, StrokeOutliner.LineJoin.MITER, 1.05f,
        )
        // The miter spike adds area beyond the bevel.
        assertTrue(mitered.area > beveled.area)
    }
}
