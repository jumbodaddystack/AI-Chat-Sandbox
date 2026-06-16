package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.ink.ConstraintSnap
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import com.aichat.sandbox.data.notes.EditOp
import com.aichat.sandbox.data.notes.EditOpsDoc
import com.aichat.sandbox.ui.components.notes.HitTest
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.aichat.sandbox.ui.components.notes.StrokeTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 — smart-select (N2 / idea #8) policy + staged-snap behaviour.
 *
 * [SmartSelect.selectSimilarIds] is the pure extraction of the ViewModel's
 * ink-on "select similar" path, so these cover selection expansion to groups
 * and locked-layer exclusion without standing up the ViewModel. The snap test
 * pins the other half: a [ConstraintSnap] proposal, staged the way
 * `proposeSnaps` does via `stageLocalEdit`, previews through the shared
 * [EditPreviewController] simulator and respects locked layers there.
 */
class SmartSelectTest {

    private val stride = StrokeCodec.FLOATS_PER_SAMPLE

    private fun encode(points: List<Pair<Float, Float>>): ByteArray {
        val out = FloatArray(points.size * stride)
        points.forEachIndexed { i, (x, y) ->
            out[i * stride] = x
            out[i * stride + 1] = y
            out[i * stride + 2] = 0.6f
            out[i * stride + 3] = 0.1f
        }
        return StrokeCodec.encode(out)
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 16): ByteArray {
        val pts = ArrayList<Pair<Float, Float>>(n)
        for (i in 0 until n) {
            val t = i.toFloat() / (n - 1)
            pts.add((x0 + (x1 - x0) * t) to (y0 + (y1 - y0) * t))
        }
        return encode(pts)
    }

    private fun square(x: Float, y: Float, size: Float): ByteArray =
        encode(listOf(x to y, x + size to y, x + size to y + size, x to y + size, x to y))

    private fun stroke(
        id: String,
        payload: ByteArray,
        layerId: String? = null,
        groupId: String? = null,
    ): NoteItem = NoteItem(
        id = id,
        noteId = "note",
        zIndex = 0,
        kind = NoteItem.KIND_STROKE,
        tool = "pen",
        colorArgb = 0xFF000000.toInt(),
        baseWidthPx = 4f,
        payload = payload,
        layerId = layerId,
        groupId = groupId,
    )

    private fun lockedLayer(id: String) =
        NoteLayer(id, "note", "Locked", 100, visible = true, locked = true, ordinal = 0)

    private fun boundsOf(item: NoteItem): FloatArray {
        val s = StrokeCodec.decode(item.payload)
        return HitTest.boundsOf(s, s.size / stride)!!
    }

    @Test
    fun selectsSimilarStrokesAndExcludesDissimilar() {
        val items = listOf(
            stroke("target", line(0f, 0f, 100f, 0f)),
            stroke("line2", line(200f, 50f, 320f, 50f)),
            stroke("loop", square(0f, 0f, 100f)),
        )
        val selected = SmartSelect.selectSimilarIds("target", items, emptyList())
        assertTrue("includes the tapped stroke", "target" in selected)
        assertTrue("includes a similar line", "line2" in selected)
        assertFalse("excludes a dissimilar loop", "loop" in selected)
    }

    @Test
    fun expandsMatchesToWholeGroups() {
        // line2 matches the target by shape; loop does not, but shares a group
        // with line2, so group expansion pulls it into the selection too.
        val items = listOf(
            stroke("target", line(0f, 0f, 100f, 0f)),
            stroke("line2", line(200f, 50f, 320f, 50f), groupId = "g1"),
            stroke("loop", square(0f, 0f, 100f), groupId = "g1"),
        )
        val selected = SmartSelect.selectSimilarIds("target", items, emptyList())
        assertTrue("group sibling that matched is selected", "line2" in selected)
        assertTrue("dissimilar group sibling joins via expansion", "loop" in selected)
    }

    @Test
    fun excludesStrokesOnLockedLayers() {
        // A similar line on a locked layer is never a candidate, and a locked
        // group sibling can't be pulled in by expansion either.
        val items = listOf(
            stroke("target", line(0f, 0f, 100f, 0f)),
            stroke("lockedLine", line(200f, 50f, 320f, 50f), layerId = "locked"),
            stroke("line2", line(10f, 10f, 110f, 10f), groupId = "g1"),
            stroke("lockedSibling", square(0f, 0f, 80f), layerId = "locked", groupId = "g1"),
        )
        val selected = SmartSelect.selectSimilarIds("target", items, listOf(lockedLayer("locked")))
        assertTrue("target stays selected", "target" in selected)
        assertTrue("unlocked similar line is selected", "line2" in selected)
        assertFalse("locked similar line is excluded", "lockedLine" in selected)
        assertFalse("locked group sibling is excluded", "lockedSibling" in selected)
    }

    @Test
    fun nonStrokeTargetSelectsOnlyItself() {
        val shapeItem = stroke("shape", line(0f, 0f, 10f, 0f)).copy(kind = NoteItem.KIND_SHAPE)
        val items = listOf(shapeItem, stroke("line", line(0f, 0f, 100f, 0f)))
        assertEquals(setOf("shape"), SmartSelect.selectSimilarIds("shape", items, emptyList()))
    }

    @Test
    fun missingTargetSelectsNothing() {
        val items = listOf(stroke("a", line(0f, 0f, 10f, 0f)))
        assertTrue(SmartSelect.selectSimilarIds("ghost", items, emptyList()).isEmpty())
    }

    @Test
    fun stagedSnapPreviewModifiesItemsAndSkipsLockedLayers() {
        // Two near-left-aligned strokes (left 10 vs 12, differing widths so only
        // the left edge clusters). B sits on a locked layer — the snap proposal
        // still nudges both, but the shared simulator must drop B's transform.
        val a = stroke("A", line(10f, 0f, 30f, 20f))
        val b = stroke("B", line(12f, 40f, 60f, 60f), layerId = "locked")
        val items = listOf(a, b)

        val snapItems = listOf(
            ConstraintSnap.Item("A", boundsOf(a)),
            ConstraintSnap.Item("B", boundsOf(b)),
        )
        val constraints = ConstraintSnap.detect(snapItems)
        val adjustments = ConstraintSnap.resolve(constraints, snapItems.map { it.id })
        assertTrue("a snap nudge was proposed for both", adjustments.size == 2)

        // Build the ops exactly as proposeSnaps does, then stage via the shared
        // simulator with identity id/layer maps (stageLocalEdit's contract).
        val ops = adjustments.map { adj ->
            EditOp.Transform(listOf(adj.id), StrokeTransform.translation(adj.dx, adj.dy))
        }
        val doc = EditOpsDoc(EditOpsDoc.SCHEMA, "Snap", ops)
        val sim = EditPreviewController.simulate(
            currentItems = items,
            doc = doc,
            idMap = items.associate { it.id to it.id },
            layerMap = emptyMap(),
            layers = listOf(lockedLayer("locked")),
            newItemNoteId = "note",
        )

        assertEquals("only the unlocked stroke is modified", listOf("A"), sim.modified.map { it.first.id })
        assertEquals("the locked stroke's nudge is skipped", listOf("transform B (unknown/locked)"), sim.skipped)
    }
}
