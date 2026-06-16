package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 — tests for [PaletteRecolor], which turns a palette suggestion into
 * grouped, no-op-free `recolor` edit-ops. Covers explicit per-item assignments,
 * the swatch-distribution fallback, no-op dropping, grouping by target colour,
 * and short-id → UUID resolution.
 */
class PaletteRecolorTest {

    private fun item(id: String, color: Int): NoteItem = NoteItem(
        id = id,
        noteId = "n",
        zIndex = 0,
        kind = "stroke",
        tool = "pen",
        colorArgb = color,
        baseWidthPx = 2f,
        payload = ByteArray(0),
    )

    private fun suggestion(vararg swatches: Int, assignments: Map<String, Int> = emptyMap()) =
        PaletteSuggestion(
            schemeName = "Test",
            swatches = swatches.toList(),
            rationale = "",
            assignments = assignments,
        )

    @Test
    fun explicitAssignmentsWinAndGroupByColour() {
        val scope = listOf(
            item("a", 0xFF111111.toInt()),
            item("b", 0xFF222222.toInt()),
            item("c", 0xFF333333.toInt()),
        )
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val explicit = mapOf("a" to red, "b" to red, "c" to blue)
        val ops = PaletteRecolor.buildOps(scope, suggestion(red, blue, 0xFF00FF00.toInt()), explicit)
        assertEquals(2, ops.size)
        val byColor = ops.associateBy { it.colorArgb }
        assertEquals(listOf("a", "b"), byColor[red]!!.ids)
        assertEquals(listOf("c"), byColor[blue]!!.ids)
    }

    @Test
    fun dropsNoOpRecolours() {
        val red = 0xFFFF0000.toInt()
        // Item already red, explicitly assigned red → no op should be produced.
        val scope = listOf(item("a", red))
        val ops = PaletteRecolor.buildOps(scope, suggestion(red, 0xFF00FF00.toInt(), 0xFF0000FF.toInt()), mapOf("a" to red))
        assertTrue(ops.isEmpty())
    }

    @Test
    fun fallsBackToSwatchDistributionWithoutExplicitMap() {
        val scope = listOf(
            item("dark", 0xFF101010.toInt()),
            item("light", 0xFFEFEFEF.toInt()),
        )
        val swatches = listOf(0xFF000000.toInt(), 0xFF808080.toInt(), 0xFFFFFFFF.toInt())
        val ops = PaletteRecolor.buildOps(scope, suggestion(*swatches.toIntArray()))
        // Both items get recoloured to a swatch; targets come from the palette.
        val allIds = ops.flatMap { it.ids }
        assertTrue(allIds.contains("dark"))
        assertTrue(allIds.contains("light"))
        assertTrue(ops.all { it.colorArgb in swatches })
        // The dark item should not be assigned the same as the light item.
        val darkTarget = ops.first { "dark" in it.ids }.colorArgb
        val lightTarget = ops.first { "light" in it.ids }.colorArgb
        assertNotEquals(darkTarget, lightTarget)
    }

    @Test
    fun emptyScopeOrSwatchesYieldsNoOps() {
        assertTrue(PaletteRecolor.buildOps(emptyList(), suggestion(0xFF111111.toInt())).isEmpty())
        assertTrue(
            PaletteRecolor.buildOps(listOf(item("a", 0xFF111111.toInt())), suggestion()).isEmpty()
        )
    }

    @Test
    fun resolveAssignmentsTranslatesShortIdsToUuids() {
        val assignments = mapOf("s_001" to 0xFFFF0000.toInt(), "s_002" to 0xFF00FF00.toInt(), "ghost" to 0xFF0000FF.toInt())
        val idMap = mapOf("s_001" to "uuid-1", "s_002" to "uuid-2")
        val resolved = PaletteRecolor.resolveAssignments(assignments, idMap)
        assertEquals(2, resolved.size)
        assertEquals(0xFFFF0000.toInt(), resolved["uuid-1"])
        assertEquals(0xFF00FF00.toInt(), resolved["uuid-2"])
        // "ghost" had no idMap entry → dropped.
        assertTrue(resolved.values.none { it == 0xFF0000FF.toInt() })
    }

    @Test
    fun resolvedAssignmentsFeedBuildOpsViaUuid() {
        val scope = listOf(item("uuid-1", 0xFF111111.toInt()))
        val resolved = PaletteRecolor.resolveAssignments(
            mapOf("s_001" to 0xFFAB12CD.toInt()),
            mapOf("s_001" to "uuid-1"),
        )
        val ops = PaletteRecolor.buildOps(scope, suggestion(0xFFAB12CD.toInt(), 0xFF112233.toInt(), 0xFF445566.toInt()), resolved)
        assertEquals(1, ops.size)
        assertEquals(0xFFAB12CD.toInt(), ops[0].colorArgb)
        assertEquals(listOf("uuid-1"), ops[0].ids)
    }
}
