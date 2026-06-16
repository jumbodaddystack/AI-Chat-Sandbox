package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import com.aichat.sandbox.data.notes.EditOp
import com.aichat.sandbox.data.notes.EditOpsDoc
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.aichat.sandbox.ui.components.notes.StrokeTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditPreviewControllerPhase1Test {

    @Test
    fun simulationBucketsAddedRemovedModifiedAndSkippedEdits() {
        val live = listOf(stroke("s1"), stroke("s2"), stroke("locked", layerId = "locked-layer"))
        val layers = listOf(NoteLayer("locked-layer", "note", "Locked", 100, visible = true, locked = true, ordinal = 0))
        val doc = EditOpsDoc(
            schema = EditOpsDoc.SCHEMA,
            summary = "mixed edit",
            ops = listOf(
                EditOp.AddShape(
                    shape = EditOp.ShapeSpec.Rect(0f, 0f, 10f, 10f),
                    colorArgb = 0xFF00FF00.toInt(),
                    fillArgb = null,
                    width = 2f,
                ),
                EditOp.Delete(listOf("two")),
                EditOp.Recolor(listOf("one"), 0xFFFF0000.toInt()),
                EditOp.Transform(listOf("locked"), StrokeTransform.translation(5f, 0f)),
            ),
        )

        val sim = EditPreviewController.simulate(
            currentItems = live,
            doc = doc,
            idMap = mapOf("one" to "s1", "two" to "s2", "locked" to "locked"),
            layerMap = emptyMap(),
            layers = layers,
            newItemNoteId = "note",
        )

        assertEquals(1, sim.added.size)
        assertEquals(NoteItem.KIND_SHAPE, sim.added.single().kind)
        assertEquals(listOf("s2"), sim.removed.map { it.id })
        assertEquals("s1", sim.modified.single().first.id)
        assertEquals(0xFFFF0000.toInt(), sim.modified.single().second.colorArgb)
        assertEquals(listOf("transform locked (unknown/locked)"), sim.skipped)
        assertEquals(listOf("1 added", "1 removed", "1 modified"), aiEditLegendLabels(sim))
    }

    @Test
    fun bannerSummaryCountsParserRejectedAndSimulationSkippedReasons() {
        val sim = EditPreviewController.Simulation(
            added = listOf(stroke("new")),
            removed = emptyList(),
            modified = emptyList(),
            skipped = listOf("delete missing (unknown/locked)"),
        )
        val pending = PendingEdit(
            description = "preview",
            doc = EditOpsDoc(
                schema = EditOpsDoc.SCHEMA,
                summary = "preview",
                ops = listOf(EditOp.Delete(listOf("missing")), EditOp.Recolor(listOf("one"), 0xFF000000.toInt())),
                rejected = listOf(EditOpsDoc.RejectedOp(raw = "{}", reason = "bad op")),
            ),
            simulation = sim,
        )

        assertEquals(listOf("delete missing (unknown/locked)", "bad op"), aiEditInvalidReasons(pending))
        assertEquals(1 to 3, aiEditAppliedOfEmitted(pending))
    }

    @Test
    fun locallyComputedTidyDiffCanBeRepresentedAsPendingEditSimulation() {
        val before = stroke("s1")
        val after = before.copy(colorArgb = 0xFF123456.toInt())
        val pending = PendingEdit(
            description = "Tidy",
            doc = EditOpsDoc(EditOpsDoc.SCHEMA, "Tidy", emptyList()),
            simulation = EditPreviewController.Simulation(
                added = listOf(shape("added")),
                removed = listOf(stroke("removed")),
                modified = listOf(before to after),
                skipped = emptyList(),
            ),
        )

        assertTrue(pending.doc.ops.isEmpty())
        assertEquals(listOf("1 added", "1 removed", "1 modified"), aiEditLegendLabels(pending.simulation))
    }

    private fun stroke(id: String, layerId: String? = null): NoteItem = NoteItem(
        id = id,
        noteId = "note",
        zIndex = 0,
        kind = NoteItem.KIND_STROKE,
        tool = "pen",
        colorArgb = 0xFF000000.toInt(),
        baseWidthPx = 4f,
        payload = StrokeCodec.encode(floatArrayOf(0f, 0f, 1f, 0f, 10f, 10f, 1f, 0f)),
        layerId = layerId,
    )

    private fun shape(id: String): NoteItem = NoteItem(
        id = id,
        noteId = "note",
        zIndex = 1,
        kind = NoteItem.KIND_SHAPE,
        tool = null,
        colorArgb = 0xFF000000.toInt(),
        baseWidthPx = 2f,
        payload = ShapeCodec.encode(Shape.Rect(0f, 0f, 4f, 4f, 0f)),
    )
}
