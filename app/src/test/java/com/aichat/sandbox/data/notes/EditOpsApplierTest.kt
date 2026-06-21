package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.screens.notes.EditPreviewController
import com.aichat.sandbox.ui.components.notes.PathCodec
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 19 (Stage 0) — coverage for [EditOpsApplier], the service-layer seam
 * the self-refine loop rasterizes off. Pure JVM (codecs only, no graphics).
 */
class EditOpsApplierTest {

    private fun strokeItem(id: String): NoteItem = NoteItem(
        id = id,
        noteId = "n",
        zIndex = 0,
        kind = "stroke",
        tool = "pen",
        colorArgb = 0xFF000000.toInt(),
        baseWidthPx = 2f,
        payload = StrokeCodec.encode(floatArrayOf(0f, 0f, 1f, 0f, 10f, 10f, 1f, 0f)),
    )

    private fun addPathDoc(): EditOpsDoc = EditOpsDoc(
        schema = 1,
        summary = "draw",
        ops = listOf(
            EditOp.AddPath(
                subpaths = listOf(
                    EditOp.SubpathSpec(
                        anchors = listOf(
                            EditOp.AnchorSpec(0f, 0f),
                            EditOp.AnchorSpec(10f, 0f),
                            EditOp.AnchorSpec(10f, 10f),
                        ),
                        closed = true,
                    ),
                ),
                colorArgb = 0xFF000000.toInt(),
                fillArgb = null,
                width = 2f,
            ),
        ),
    )

    @Test
    fun applyAuthorsNewGeometryOnBlankBaseline() {
        val out = EditOpsApplier.apply(
            baselineItems = emptyList(),
            doc = addPathDoc(),
            idMap = emptyMap(),
            layerMap = emptyMap(),
            layers = emptyList(),
            newItemNoteId = "n",
        )
        assertEquals(1, out.size)
        assertEquals(PathCodec.KIND, out.single().kind)
    }

    @Test
    fun applyRemovesDeletedItemFromBaseline() {
        val keep = strokeItem("keep")
        val drop = strokeItem("drop")
        val doc = EditOpsDoc(1, "del", listOf(EditOp.Delete(ids = listOf("s_002"))))
        val out = EditOpsApplier.apply(
            baselineItems = listOf(keep, drop),
            doc = doc,
            idMap = mapOf("s_001" to "keep", "s_002" to "drop"),
            layerMap = emptyMap(),
            layers = emptyList(),
        )
        assertEquals(listOf("keep"), out.map { it.id })
    }

    @Test
    fun materializeFoldsAddedRemovedModifiedOntoBase() {
        val a = strokeItem("a")
        val b = strokeItem("b")
        val bAfter = b.copy(colorArgb = 0xFFFF0000.toInt())
        val added = strokeItem("c")
        val sim = EditPreviewController.Simulation(
            added = listOf(added),
            removed = listOf(a),
            modified = listOf(b to bAfter),
            skipped = emptyList(),
        )
        val out = EditOpsApplier.materialize(listOf(a, b), sim)
        assertEquals(listOf("b", "c"), out.map { it.id })
        assertEquals(0xFFFF0000.toInt(), out.first { it.id == "b" }.colorArgb)
    }

    @Test
    fun materializeReturnsBaseUnchangedForEmptySimulation() {
        val base = listOf(strokeItem("a"))
        val empty = EditPreviewController.Simulation(emptyList(), emptyList(), emptyList(), emptyList())
        val out = EditOpsApplier.materialize(base, empty)
        assertSame(base, out)
    }

    @Test
    fun applyEmptyDocLeavesBaselineIntact() {
        val base = listOf(strokeItem("a"), strokeItem("b"))
        val out = EditOpsApplier.apply(
            baselineItems = base,
            doc = EditOpsDoc.EMPTY,
            idMap = emptyMap(),
            layerMap = emptyMap(),
            layers = emptyList(),
        )
        assertTrue(out.map { it.id } == listOf("a", "b"))
    }
}
