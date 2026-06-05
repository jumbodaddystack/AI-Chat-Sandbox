package com.aichat.sandbox.ui.screens.vector.edit

import com.aichat.sandbox.data.vector.VectorStyle
import com.aichat.sandbox.data.vector.edit.EditAnchor
import com.aichat.sandbox.data.vector.edit.EditSubpath
import com.aichat.sandbox.data.vector.edit.EditablePath
import com.aichat.sandbox.data.vector.VectorDocument
import com.aichat.sandbox.data.vector.VectorGroup
import com.aichat.sandbox.data.vector.VectorViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 — reducer-level tests for the boolean / outline / offset actions: each is
 * a single undo entry, no-ops cleanly when prerequisites aren't met, and undo/redo
 * round-trips it exactly.
 */
class VectorBooleanReducerTest {

    private val reducer = VectorEditReducer()

    private fun square(idPrefix: String, x: Float, y: Float, size: Float): EditSubpath =
        EditSubpath(
            id = idPrefix,
            anchors = listOf(
                EditAnchor("$idPrefix.a0", x, y),
                EditAnchor("$idPrefix.a1", x + size, y),
                EditAnchor("$idPrefix.a2", x + size, y + size),
                EditAnchor("$idPrefix.a3", x, y + size),
            ),
            closed = true,
        )

    private fun stateWith(path: EditablePath, selected: Set<String> = emptySet()): VectorEditState {
        val doc = VectorDocument(
            viewport = VectorViewport(24f, 24f, 24f, 24f),
            root = VectorGroup(id = "root", children = emptyList()),
        )
        return VectorEditState(document = doc, editing = path, selection = Selection(selected))
    }

    private fun twoSquarePath(): EditablePath = EditablePath(
        pathId = "p",
        subpaths = listOf(square("p.s0", 0f, 0f, 10f), square("p.s1", 5f, 5f, 10f)),
        style = VectorStyle(fillColor = "#000000"),
    )

    @Test
    fun booleanUnion_twoSelected_replacesWithOneResult_singleUndoEntry() {
        val state = stateWith(twoSquarePath(), selected = setOf("p.s0.a0", "p.s1.a0"))
        val after = reducer.reduce(state, VectorEditAction.BooleanOp(BoolOpKind.UNION))
        assertEquals(1, after.editing!!.subpaths.size)
        assertEquals(1, after.undoStack.size)
        // Undo restores the original two subpaths.
        val undone = reducer.reduce(after, VectorEditAction.Undo)
        assertEquals(2, undone.editing!!.subpaths.size)
    }

    @Test
    fun booleanOp_lessThanTwoSelected_isNoOp() {
        val state = stateWith(twoSquarePath(), selected = setOf("p.s0.a0"))
        val after = reducer.reduce(state, VectorEditAction.BooleanOp(BoolOpKind.UNION))
        assertEquals(state, after)
    }

    @Test
    fun outlineStroke_strokedPath_producesFill_undoRestores() {
        val line = EditablePath(
            pathId = "p",
            subpaths = listOf(
                EditSubpath(
                    "p.s0",
                    listOf(EditAnchor("p.s0.a0", 0f, 0f), EditAnchor("p.s0.a1", 20f, 0f)),
                    closed = false,
                ),
            ),
            style = VectorStyle(strokeColor = "#000000", strokeWidth = 4f),
        )
        val state = stateWith(line)
        val after = reducer.reduce(state, VectorEditAction.OutlineStroke)
        assertNull(after.editing!!.style.strokeWidth)
        assertTrue(after.editing!!.subpaths.isNotEmpty())
        assertEquals(1, after.undoStack.size)
        val undone = reducer.reduce(after, VectorEditAction.Undo)
        assertEquals(4f, undone.editing!!.style.strokeWidth)
    }

    @Test
    fun outlineStroke_unstrokedPath_isNoOp() {
        val state = stateWith(
            EditablePath("p", subpaths = listOf(square("p.s0", 0f, 0f, 10f)), style = VectorStyle(fillColor = "#000000")),
        )
        val after = reducer.reduce(state, VectorEditAction.OutlineStroke)
        assertEquals(state, after)
    }

    @Test
    fun offsetPath_undoRedo_invertsExactly() {
        val path = EditablePath("p", subpaths = listOf(square("p.s0", 0f, 0f, 10f)), style = VectorStyle(fillColor = "#000000"))
        val state = stateWith(path)
        val after = reducer.reduce(state, VectorEditAction.OffsetPath(2f))
        assertEquals(1, after.undoStack.size)
        val undone = reducer.reduce(after, VectorEditAction.Undo)
        assertEquals(path, undone.editing)
        val redone = reducer.reduce(undone, VectorEditAction.Redo)
        assertEquals(after.editing, redone.editing)
    }

    @Test
    fun offsetPath_overShrink_isNoOp() {
        val path = EditablePath("p", subpaths = listOf(square("p.s0", 0f, 0f, 4f)), style = VectorStyle(fillColor = "#000000"))
        val state = stateWith(path)
        val after = reducer.reduce(state, VectorEditAction.OffsetPath(-50f))
        assertEquals(state, after)
    }
}
