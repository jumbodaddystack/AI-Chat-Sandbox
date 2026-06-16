package com.aichat.sandbox.data.notes

import org.junit.Assert.assertEquals
import org.junit.Test

/** Phase 4 (N1) — duplicate-name disambiguation for saved AI brushes. */
class BrushPresetNamingTest {

    @Test
    fun returnsDesiredNameWhenFree() {
        assertEquals("Inky Pen", BrushPresetNaming.uniqueName("Inky Pen", listOf("Soft Marker")))
    }

    @Test
    fun appendsTwoOnFirstCollision() {
        assertEquals("Inky Pen (2)", BrushPresetNaming.uniqueName("Inky Pen", listOf("Inky Pen")))
    }

    @Test
    fun skipsToNextFreeSuffix() {
        assertEquals(
            "Inky Pen (3)",
            BrushPresetNaming.uniqueName("Inky Pen", listOf("Inky Pen", "Inky Pen (2)")),
        )
    }

    @Test
    fun trimsBeforeComparing() {
        assertEquals("Inky Pen (2)", BrushPresetNaming.uniqueName("  Inky Pen  ", listOf("Inky Pen")))
    }

    @Test
    fun blankFallsBackToDefaultName() {
        assertEquals(BrushSpec.DEFAULT_NAME, BrushPresetNaming.uniqueName("   ", emptyList()))
    }

    @Test
    fun blankFallbackAlsoDisambiguates() {
        assertEquals(
            "${BrushSpec.DEFAULT_NAME} (2)",
            BrushPresetNaming.uniqueName("", listOf(BrushSpec.DEFAULT_NAME)),
        )
    }
}
