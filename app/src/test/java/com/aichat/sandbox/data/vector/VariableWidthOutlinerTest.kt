package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** Phase 5 (sub-feature 1) — variable-width outline-to-fill baking (pure). */
class VariableWidthOutlinerTest {

    private val line = listOf(
        PathCommand.MoveTo(0f, 0f),
        PathCommand.LineTo(10f, 0f),
    )

    /** All absolute vertices of the outline, in order. */
    private fun vertices(cmds: List<PathCommand>): List<VectorPoint> =
        cmds.mapNotNull {
            when (it) {
                is PathCommand.MoveTo -> VectorPoint(it.x, it.y)
                is PathCommand.LineTo -> VectorPoint(it.x, it.y)
                else -> null
            }
        }

    /** Width across the band at the centerline point nearest x. */
    private fun bandWidthAtX(cmds: List<PathCommand>, x: Float): Float {
        val pts = vertices(cmds)
        // The band is left[0..n-1] then right[n-1..0]; matching indices straddle the centerline.
        val n = pts.size / 2
        var best = Float.MAX_VALUE
        var width = 0f
        for (i in 0 until n) {
            val left = pts[i]
            val right = pts[pts.size - 1 - i]
            val midX = (left.x + right.x) / 2f
            val d = kotlin.math.abs(midX - x)
            if (d < best) {
                best = d
                width = hypot(left.x - right.x, left.y - right.y)
            }
        }
        return width
    }

    @Test
    fun outline_constantProfile_matchesPlainStrokeWithinTolerance() {
        val profile = VariableWidthProfile(listOf(WidthStop(0f, 4f), WidthStop(1f, 4f)))
        val outline = VariableWidthOutliner.outline(line, profile, baseWidth = 4f)
        assertTrue(outline.isNotEmpty())
        // A constant 4-unit profile is a uniform 4-wide band around the centerline.
        assertEquals(4f, bandWidthAtX(outline, 5f), 1e-2f)
        assertEquals(4f, bandWidthAtX(outline, 0f), 1e-2f)
    }

    @Test
    fun outline_taperedProfile_widthAtMidpointEqualsInterpolatedStop() {
        // 2 → 6 linearly: at the midpoint (t=0.5) width should be 4.
        val profile = VariableWidthProfile(listOf(WidthStop(0f, 2f), WidthStop(1f, 6f)))
        val outline = VariableWidthOutliner.outline(line, profile, baseWidth = 1f)
        assertEquals(4f, bandWidthAtX(outline, 5f), 2e-1f)
    }

    @Test
    fun outline_isClosed() {
        val profile = VariableWidthProfile(listOf(WidthStop(0f, 3f), WidthStop(1f, 3f)))
        val outline = VariableWidthOutliner.outline(line, profile, baseWidth = 3f)
        assertTrue(outline.last() is PathCommand.Close)
    }

    @Test
    fun outline_degenerateCenterline_returnsEmpty() {
        val point = listOf(PathCommand.MoveTo(1f, 1f))
        assertTrue(VariableWidthOutliner.outline(point, VariableWidthProfile(emptyList()), 2f).isEmpty())
    }
}
