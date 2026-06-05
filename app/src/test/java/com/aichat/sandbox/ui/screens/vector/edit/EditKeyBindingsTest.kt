package com.aichat.sandbox.ui.screens.vector.edit

import com.aichat.sandbox.ui.screens.vector.edit.EditKeyBindings.KeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 5 (sub-feature 4) — keyboard binding resolution (pure, no device). */
class EditKeyBindingsTest {

    @Test
    fun arrow_mapsToOneGridUnitNudge() {
        assertEquals(
            VectorEditAction.MoveSelection(0f, -1f),
            EditKeyBindings.resolve(KeySpec.ARROW_UP, shift = false, ctrl = false),
        )
        assertEquals(
            VectorEditAction.MoveSelection(0f, 1f),
            EditKeyBindings.resolve(KeySpec.ARROW_DOWN, shift = false, ctrl = false),
        )
        assertEquals(
            VectorEditAction.MoveSelection(-1f, 0f),
            EditKeyBindings.resolve(KeySpec.ARROW_LEFT, shift = false, ctrl = false),
        )
        assertEquals(
            VectorEditAction.MoveSelection(1f, 0f),
            EditKeyBindings.resolve(KeySpec.ARROW_RIGHT, shift = false, ctrl = false),
        )
    }

    @Test
    fun shiftArrow_mapsToTenUnitNudge() {
        assertEquals(
            VectorEditAction.MoveSelection(0f, -10f),
            EditKeyBindings.resolve(KeySpec.ARROW_UP, shift = true, ctrl = false),
        )
        assertEquals(
            VectorEditAction.MoveSelection(10f, 0f),
            EditKeyBindings.resolve(KeySpec.ARROW_RIGHT, shift = true, ctrl = false),
        )
    }

    @Test
    fun arrow_honorsCustomGridStep() {
        assertEquals(
            VectorEditAction.MoveSelection(0.5f, 0f),
            EditKeyBindings.resolve(KeySpec.ARROW_RIGHT, shift = false, ctrl = false, gridStep = 0.5f),
        )
        assertEquals(
            VectorEditAction.MoveSelection(5f, 0f),
            EditKeyBindings.resolve(KeySpec.ARROW_RIGHT, shift = true, ctrl = false, gridStep = 0.5f),
        )
    }

    @Test
    fun ctrlZ_mapsToUndo_ctrlShiftZ_mapsToRedo() {
        assertEquals(
            VectorEditAction.Undo,
            EditKeyBindings.resolve(KeySpec.Z, shift = false, ctrl = true),
        )
        assertEquals(
            VectorEditAction.Redo,
            EditKeyBindings.resolve(KeySpec.Z, shift = true, ctrl = true),
        )
        // Z without ctrl is unbound (so typing 'z' in a field never undoes).
        assertNull(EditKeyBindings.resolve(KeySpec.Z, shift = false, ctrl = false))
    }

    @Test
    fun deleteAndToolAndEscape_mapToActions() {
        assertEquals(
            VectorEditAction.DeleteSelected,
            EditKeyBindings.resolve(KeySpec.DELETE, shift = false, ctrl = false),
        )
        assertEquals(
            VectorEditAction.SetTool(EditTool.PEN),
            EditKeyBindings.resolve(KeySpec.P, shift = false, ctrl = false),
        )
        assertEquals(
            VectorEditAction.SetTool(EditTool.DIRECT_SELECT),
            EditKeyBindings.resolve(KeySpec.V, shift = false, ctrl = false),
        )
        assertEquals(
            VectorEditAction.ClearSelection,
            EditKeyBindings.resolve(KeySpec.ESCAPE, shift = false, ctrl = false),
        )
        // Tool keys don't fire with ctrl held (so they never shadow OS shortcuts).
        assertNull(EditKeyBindings.resolve(KeySpec.P, shift = false, ctrl = true))
    }
}
