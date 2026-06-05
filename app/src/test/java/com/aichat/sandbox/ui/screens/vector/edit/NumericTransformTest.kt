package com.aichat.sandbox.ui.screens.vector.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 5 (sub-feature 4) — numeric transform parsing (pure). */
class NumericTransformTest {

    @Test
    fun parse_validFields_buildsTransformEntry() {
        val entry = NumericTransform.parse("3", "-2.5", "2", "90")
        assertEquals(TransformEntry(dx = 3f, dy = -2.5f, scale = 2f, rotateDeg = 90f), entry)
    }

    @Test
    fun parse_blankOrGarbage_returnsNullField() {
        val entry = NumericTransform.parse("4", "", "abc", "  ")
        requireNotNull(entry)
        assertEquals(4f, entry.dx)
        assertNull(entry.dy)
        assertNull(entry.scale)
        assertNull(entry.rotateDeg)
    }

    @Test
    fun parse_allEmpty_returnsNull() {
        assertNull(NumericTransform.parse("", "  ", "", "x"))
    }

    @Test
    fun toMoveSelection_buildsActionFromTranslationFields() {
        val entry = NumericTransform.parse("3", "", "", "")!!
        assertEquals(VectorEditAction.MoveSelection(3f, 0f), entry.toMoveSelection())

        // A scale-only entry has no translation to apply.
        val scaleOnly = NumericTransform.parse("", "", "2", "")!!
        assertNull(scaleOnly.toMoveSelection())
    }
}
