package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.BrushPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase I4 / N1 — validation tests for the AI brush designer's spec parser.
 *
 * These pin the "never trust the model" discipline the migration plan requires:
 * the parser clamps every numeric field, rejects unknown tool / curve / texture
 * strings down to safe defaults, tolerates fenced or bare JSON, and maps a valid
 * spec onto a **user-scope** [BrushPreset] (never app-scope, never a canvas op).
 */
class BrushSpecParserTest {

    @Test
    fun parsesWellFormedFencedSpec() {
        val raw = """
            Here's your brush:
            ```json
            {
              "schema": 1,
              "brush": {
                "name": "Dry Gouache",
                "tool": "marker",
                "color": "#3A2F2A",
                "width": 14,
                "opacity": 0.9,
                "taperStart": 0.1,
                "taperEnd": 0.2,
                "jitter": 0.3,
                "pressureCurve": "EASE_OUT",
                "texture": "charcoal"
              }
            }
            ```
        """.trimIndent()
        val spec = BrushSpecParser.parse(raw).getOrThrow()
        assertEquals("Dry Gouache", spec.name)
        assertEquals("marker", spec.tool)
        assertEquals(0xFF3A2F2A.toInt(), spec.colorArgb)
        assertEquals(14f, spec.baseWidthPx, 0f)
        assertEquals(0.9f, spec.opacity, 1e-4f)
        assertEquals(0.1f, spec.taperStart, 1e-4f)
        assertEquals(0.2f, spec.taperEnd, 1e-4f)
        assertEquals(0.3f, spec.jitter, 1e-4f)
        assertEquals(BrushPreset.CURVE_EASE_OUT, spec.pressureCurveId)
        assertEquals(BrushPreset.TEXTURE_CHARCOAL, spec.textureId)
    }

    @Test
    fun acceptsBareTopLevelObjectWithoutBrushWrapper() {
        val raw = """{ "tool": "pen", "color": "#112233", "width": 3 }"""
        val spec = BrushSpecParser.parse(raw).getOrThrow()
        assertEquals("pen", spec.tool)
        assertEquals(0xFF112233.toInt(), spec.colorArgb)
        assertEquals(3f, spec.baseWidthPx, 0f)
    }

    @Test
    fun clampsOutOfRangeNumbersAndDefaultsUnknownEnums() {
        val raw = """
            {
              "tool": "airbrush",
              "color": "not-a-color",
              "width": 9999,
              "opacity": 5,
              "taperStart": -1,
              "jitter": 2,
              "pressureCurve": "BOUNCE",
              "texture": "glitter"
            }
        """.trimIndent()
        val spec = BrushSpecParser.parse(raw).getOrThrow()
        // Unknown tool -> default pen; unknown enums -> safe defaults.
        assertEquals(BrushSpec.DEFAULT_TOOL, spec.tool)
        assertEquals(BrushPreset.CURVE_LINEAR, spec.pressureCurveId)
        assertEquals(BrushPreset.TEXTURE_SMOOTH, spec.textureId)
        // Numbers clamped into range.
        assertEquals(BrushSpec.MAX_WIDTH_PX, spec.baseWidthPx, 0f)
        assertEquals(1f, spec.opacity, 0f)
        assertEquals(0f, spec.taperStart, 0f)
        assertEquals(1f, spec.jitter, 0f)
        // Bad colour -> opaque black.
        assertEquals(0xFF000000.toInt(), spec.colorArgb)
    }

    @Test
    fun overlappingTapersAreNormalizedToFitTheStroke() {
        val raw = """{ "tool": "pen", "taperStart": 0.8, "taperEnd": 0.8 }"""
        val spec = BrushSpecParser.parse(raw).getOrThrow()
        assertTrue("taper sum must not exceed 1", spec.taperStart + spec.taperEnd <= 1f + 1e-4f)
        assertEquals(0.5f, spec.taperStart, 1e-4f)
        assertEquals(0.5f, spec.taperEnd, 1e-4f)
    }

    @Test
    fun aarrggbbColorKeepsAllChannels() {
        val raw = """{ "tool": "highlighter", "color": "#80FFEE00" }"""
        val spec = BrushSpecParser.parse(raw).getOrThrow()
        assertEquals(0x80FFEE00.toInt(), spec.colorArgb)
    }

    @Test
    fun emptyOrJsonlessReplyFails() {
        assertTrue(BrushSpecParser.parse("").isFailure)
        assertTrue(BrushSpecParser.parse("sorry, I can't do that").isFailure)
    }

    @Test
    fun toPresetIsUserScopeAndCarriesFields() {
        val spec = BrushSpecParser.parse(
            """{ "name": "Inky", "tool": "pen", "color": "#101010", "width": 5, "opacity": 0.8 }""",
        ).getOrThrow()
        val preset = spec.toPreset(ordinal = 3)
        assertEquals(BrushPreset.SCOPE_USER, preset.ownerScope)
        assertEquals("Inky", preset.name)
        assertEquals("pen", preset.tool)
        assertEquals(5f, preset.baseWidthPx, 0f)
        assertEquals(0.8f, preset.opacity, 1e-4f)
        assertEquals(3, preset.ordinal)
    }
}
