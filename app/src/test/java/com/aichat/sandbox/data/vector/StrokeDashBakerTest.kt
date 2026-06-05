package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** Phase 5 (sub-feature 1) — dash baking geometry (pure). */
class StrokeDashBakerTest {

    private val line = listOf(
        PathCommand.MoveTo(0f, 0f),
        PathCommand.LineTo(10f, 0f),
    )

    /** Total on-length of all runs (each run is M + L… along a straight line). */
    private fun totalOnLength(runs: List<List<PathCommand>>): Float {
        var total = 0f
        for (run in runs) {
            val pts = run.mapNotNull {
                when (it) {
                    is PathCommand.MoveTo -> it.x to it.y
                    is PathCommand.LineTo -> it.x to it.y
                    else -> null
                }
            }
            for (i in 1 until pts.size) {
                total += hypot(pts[i].first - pts[i - 1].first, pts[i].second - pts[i - 1].second)
            }
        }
        return total
    }

    @Test
    fun bake_simpleLine_producesAlternatingOnRunsOfExpectedTotalLength() {
        val runs = StrokeDashBaker.bake(line, dash = listOf(2f, 2f), offset = 0f)
        // On runs at [0,2], [4,6], [8,10] → 3 runs, total on-length 6.
        assertEquals(3, runs.size)
        assertEquals(6f, totalOnLength(runs), 1e-2f)
        // First run starts at the path origin.
        val first = runs.first()
        assertTrue(first.first() is PathCommand.MoveTo)
        val m = first.first() as PathCommand.MoveTo
        assertEquals(0f, m.x, 1e-3f)
    }

    @Test
    fun bake_respectsDashOffset_shiftsFirstRun() {
        val noOffset = StrokeDashBaker.bake(line, dash = listOf(2f, 2f), offset = 0f)
        val shifted = StrokeDashBaker.bake(line, dash = listOf(2f, 2f), offset = 1f)
        // Offset 1 lands us halfway into the first "on" dash, so the first run is shorter.
        assertEquals(2f, totalOnLength(listOf(noOffset.first())), 1e-2f)
        assertEquals(1f, totalOnLength(listOf(shifted.first())), 1e-2f)
    }

    @Test
    fun bake_zeroOffWidth_isContinuous() {
        val runs = StrokeDashBaker.bake(line, dash = listOf(5f, 0f), offset = 0f)
        // Every "off" gap is zero → one continuous run covering the whole 10-unit line.
        assertEquals(1, runs.size)
        assertEquals(10f, totalOnLength(runs), 1e-2f)
    }

    @Test
    fun bake_degeneratePattern_returnsInputUnchanged() {
        assertEquals(listOf(line), StrokeDashBaker.bake(line, dash = emptyList()))
        assertEquals(listOf(line), StrokeDashBaker.bake(line, dash = listOf(0f, 0f)))
    }
}
