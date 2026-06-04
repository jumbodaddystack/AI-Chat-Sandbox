package com.aichat.sandbox.data.vector.edit.boolean

import com.aichat.sandbox.data.vector.PathCommand
import com.aichat.sandbox.data.vector.VectorPoint
import com.aichat.sandbox.data.vector.edit.AnchorType
import com.aichat.sandbox.data.vector.edit.EditSubpath
import com.aichat.sandbox.data.vector.edit.EditablePath
import com.aichat.sandbox.data.vector.edit.EditablePathSerializer
import com.aichat.sandbox.data.vector.VectorStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class CurveRefitTest {

    private fun maxDistanceToRing(sub: EditSubpath, ring: Ring): Float {
        // Sample the refit subpath densely and measure worst distance to the
        // original polygon's vertices (a proxy for fit error).
        val path = EditablePath("t", subpaths = listOf(sub), style = VectorStyle())
        val cmds = EditablePathSerializer.toCommands(path)
        val sampled = com.aichat.sandbox.data.vector.VectorPathSampler.sample(cmds, 64).points
        var worst = 0f
        for (p in ring.points) {
            var best = Float.MAX_VALUE
            for (q in sampled) {
                val d = hypot(p.x - q.x, p.y - q.y)
                if (d < best) best = d
            }
            if (best > worst) worst = best
        }
        return worst
    }

    @Test
    fun refit_ofCirclePolygon_isWithinMaxError_andUsesSmoothAnchors() {
        val ring = BoolTestShapes.polygonCircle(0f, 0f, 20f, n = 80)
        val sub = CurveRefit.refit(ring, maxError = 0.5f, idPrefix = "p")
        assertTrue("expected few smooth anchors, got ${sub.anchors.size}", sub.anchors.size in 3..12)
        assertTrue(sub.anchors.all { it.type == AnchorType.SMOOTH })
        assertTrue("fit error too large", maxDistanceToRing(sub, ring) < 1.0f)
        assertTrue(sub.closed)
    }

    @Test
    fun refit_ofRectanglePolygon_keepsFourCornerAnchors_noStrayHandles() {
        val ring = BoolTestShapes.square(0f, 0f, 10f)
        val sub = CurveRefit.refit(ring, maxError = 0.5f, idPrefix = "p")
        assertEquals(4, sub.anchors.size)
        assertTrue(sub.anchors.all { it.type == AnchorType.CORNER })
        assertTrue(sub.anchors.all { it.inHandle == null && it.outHandle == null })
    }

    @Test
    fun refit_straightRun_emitsHandlelessAnchors_serializeAsLineTo() {
        // A long-thin rectangle whose long edges are sampled into collinear runs.
        val pts = listOf(
            VectorPoint(0f, 0f), VectorPoint(5f, 0f), VectorPoint(10f, 0f),
            VectorPoint(10f, 2f),
            VectorPoint(5f, 2f), VectorPoint(0f, 2f),
        )
        val sub = CurveRefit.refit(Ring(pts), maxError = 0.25f, idPrefix = "p")
        val cmds = EditablePathSerializer.toCommands(
            EditablePath("t", subpaths = listOf(sub), style = VectorStyle()),
        )
        // No cubic commands — every surviving segment is a straight line.
        assertTrue(cmds.none { it is PathCommand.CubicTo })
        assertTrue(cmds.any { it is PathCommand.LineTo })
    }
}
