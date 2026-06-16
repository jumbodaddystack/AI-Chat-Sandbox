package com.aichat.sandbox.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8 — unit tests for the JVM-pure scene-generation helpers: the object
 * cap that keeps a runaway reply compact, the placement-rect inset, and the
 * complexity enum's per-mode object limits.
 */
class SceneGenTest {

    private fun addShape(g: String? = null): EditOp.AddShape =
        EditOp.AddShape(EditOp.ShapeSpec.Rect(0f, 0f, 1f, 1f), null, null, null, group = g)

    private fun addPath(g: String? = null): EditOp.AddPath =
        EditOp.AddPath(
            subpaths = listOf(EditOp.SubpathSpec(listOf(EditOp.AnchorSpec(0f, 0f), EditOp.AnchorSpec(1f, 1f)), false)),
            colorArgb = null, fillArgb = null, width = null, group = g,
        )

    @Test
    fun capTrimsExcessAddOpsIntoRejected() {
        val ops = (1..5).map { addShape("g$it") }
        val doc = EditOpsDoc(1, "scene", ops)
        val capped = SceneGen.capSceneAddOps(doc, max = 3)
        assertEquals(3, capped.ops.size)
        // The first three survive (document order preserved).
        assertEquals("g1", (capped.ops[0] as EditOp.AddShape).group)
        assertEquals("g3", (capped.ops[2] as EditOp.AddShape).group)
        // The trimmed two land in rejected with a reason mentioning the cap.
        assertEquals(2, capped.rejected.size)
        assertTrue(capped.rejected.all { it.reason.contains("cap") })
    }

    @Test
    fun capLeavesNonAddOpsUntouchedAndCountsOnlyAdds() {
        // Two adds + a recolor + two more adds, cap 3 → keeps the recolor plus
        // the first three adds; trims the rest. Non-add ops never count toward
        // the cap and are never trimmed.
        val doc = EditOpsDoc(
            1, "mixed",
            listOf(addShape("a"), addShape("b"), EditOp.Recolor(listOf("s_1"), 0xFF112233.toInt()), addPath("c"), addShape("d")),
        )
        val capped = SceneGen.capSceneAddOps(doc, max = 3)
        assertEquals(4, capped.ops.size) // 3 adds + the recolor
        assertEquals(3, capped.ops.count { it is EditOp.AddShape || it is EditOp.AddPath })
        assertTrue(capped.ops.any { it is EditOp.Recolor })
        assertEquals(1, capped.rejected.size)
    }

    @Test
    fun capUnderLimitReturnsSameDocInstance() {
        val doc = EditOpsDoc(1, "small", listOf(addShape("a"), addShape("b")))
        // Nothing trimmed → identical instance returned (no needless copy).
        assertSame(doc, SceneGen.capSceneAddOps(doc, max = 12))
    }

    @Test
    fun capNonPositiveIsNoCap() {
        val doc = EditOpsDoc(1, "x", (1..20).map { addShape() })
        assertSame(doc, SceneGen.capSceneAddOps(doc, max = 0))
    }

    @Test
    fun insetShrinksRectTowardCentre() {
        val r = SceneGen.insetRect(floatArrayOf(0f, 0f, 100f, 100f), fraction = 0.1f)!!
        assertEquals(10f, r[0], 1e-3f)
        assertEquals(10f, r[1], 1e-3f)
        assertEquals(90f, r[2], 1e-3f)
        assertEquals(90f, r[3], 1e-3f)
    }

    @Test
    fun insetLeavesDegenerateRectUnchanged() {
        // Zero-area rect can't be inset without inverting — returned as-is.
        val degenerate = floatArrayOf(5f, 5f, 5f, 5f)
        assertSame(degenerate, SceneGen.insetRect(degenerate))
        assertNull(SceneGen.insetRect(null))
    }

    @Test
    fun complexityLimitsAreOrderedAndBounded() {
        assertTrue(SceneComplexity.SIMPLE.maxObjects < SceneComplexity.DETAILED.maxObjects)
        assertTrue(SceneComplexity.DETAILED.maxObjects <= SceneGen.MAX_SCENE_OBJECTS)
        assertNotNull(SceneComplexity.DEFAULT)
        assertNotNull(ScenePlacement.DEFAULT)
    }
}
