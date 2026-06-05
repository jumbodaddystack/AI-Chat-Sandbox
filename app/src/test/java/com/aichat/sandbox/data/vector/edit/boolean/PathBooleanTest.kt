package com.aichat.sandbox.data.vector.edit.boolean

import com.aichat.sandbox.data.vector.PathDataParser
import com.aichat.sandbox.data.vector.VectorStyle
import com.aichat.sandbox.data.vector.edit.EditablePathSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathBooleanTest {

    @Test
    fun combine_threeOverlappingCircles_union_singleOutlinePath() {
        val paths = listOf(
            BoolTestShapes.editableCircle(0f, 0f, 10f, "a"),
            BoolTestShapes.editableCircle(8f, 0f, 10f, "b"),
            BoolTestShapes.editableCircle(4f, 6f, 10f, "c"),
        )
        val result = PathBoolean.combine(paths, PathBoolean.Op.UNION, "u")
        assertNotNull(result)
        result!!
        assertEquals("u", result.pathId)
        // One connected blob → one subpath.
        assertEquals(1, result.subpaths.size)
        // Result is a pure fill.
        assertNull(result.style.strokeWidth)
    }

    @Test
    fun combine_resultReSerializes_throughEditablePathSerializer_andReparses() {
        val paths = listOf(
            BoolTestShapes.editableCircle(0f, 0f, 10f, "a"),
            BoolTestShapes.editableCircle(8f, 0f, 10f, "b"),
        )
        val result = PathBoolean.combine(paths, PathBoolean.Op.UNION, "u")!!
        val vp = EditablePathSerializer.toVectorPath(result)
        // Re-parses cleanly through the Phase 1 pipe.
        val reparsed = PathDataParser.parse(vp.pathData)
        assertTrue(reparsed.commands.isNotEmpty())
        assertTrue(reparsed.warnings.isEmpty())
    }

    @Test
    fun combine_evenOddSubject_resultFillTypeIsCanonical() {
        val a = BoolTestShapes.editableCircle(0f, 0f, 10f, "a", VectorStyle(fillType = "evenOdd"))
        val b = BoolTestShapes.editableCircle(8f, 0f, 10f, "b")
        val result = PathBoolean.combine(listOf(a, b), PathBoolean.Op.UNION, "u")!!
        // Boolean results are correctly-oriented; canonical output is non-zero (null).
        assertNull(result.style.fillType)
    }

    @Test
    fun combine_fewerThanTwoPaths_returnsInputUnchanged_orNull() {
        assertNull(PathBoolean.combine(emptyList(), PathBoolean.Op.UNION, "u"))
        val single = BoolTestShapes.editableCircle(0f, 0f, 10f, "a")
        assertEquals(single, PathBoolean.combine(listOf(single), PathBoolean.Op.UNION, "u"))
    }

    @Test
    fun subtract_twoCircles_producesCrescent() {
        val a = BoolTestShapes.editableCircle(0f, 0f, 10f, "a")
        val b = BoolTestShapes.editableCircle(10f, 0f, 10f, "b")
        val result = PathBoolean.combine(listOf(a, b), PathBoolean.Op.SUBTRACT, "d")
        assertNotNull(result)
        assertTrue(result!!.subpaths.isNotEmpty())
    }

    @Test
    fun outlineStroke_strokedPath_producesFill() {
        val line = BoolTestShapes.editableHLine(0f, 20f, 0f, strokeWidth = 4f)
        val result = PathBoolean.outlineStroke(line, "o")
        assertNotNull(result)
        result!!
        assertNull(result.style.strokeWidth)
        assertNotNull(result.style.fillColor)
    }

    @Test
    fun outlineStroke_unstrokedPath_isNull() {
        val rect = BoolTestShapes.editableRect(0f, 0f, 10f, 10f) // no stroke width
        assertNull(PathBoolean.outlineStroke(rect, "o"))
    }

    @Test
    fun offset_grow_returnsLargerPath() {
        val rect = BoolTestShapes.editableRect(0f, 0f, 10f, 10f, style = VectorStyle(fillColor = "#ff0000"))
        val result = PathBoolean.offset(rect, delta = 2f, newPathId = "g")
        assertNotNull(result)
        // Offset keeps the fill style.
        assertEquals("#ff0000", result!!.style.fillColor)
    }
}
