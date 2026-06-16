package com.aichat.sandbox.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 — validation tests for the palette assistant's reply parser.
 *
 * Pins the "never trust the model" discipline: fenced or bare JSON is accepted,
 * the swatch list is clamped to the 3..6 contract, malformed colours and
 * assignment rows are dropped, and assignment ids are filtered to the supplied
 * known-id set and snapped to a real swatch.
 */
class PaletteParserTest {

    @Test
    fun parsesWellFormedFencedReply() {
        val raw = """
            Here's a palette:
            ```json
            {
              "schema": 1,
              "scheme": "Complementary",
              "rationale": "Blue with a warm orange accent.",
              "swatches": ["#1A3C6E", "#2E5AA8", "#E08A3C"],
              "assignments": [
                { "id": "s_001", "color": "#1A3C6E" },
                { "id": "s_002", "color": "#E08A3C" }
              ]
            }
            ```
        """.trimIndent()
        val suggestion = PaletteParser.parse(raw, knownIds = setOf("s_001", "s_002")).getOrThrow()
        assertEquals("Complementary", suggestion.schemeName)
        assertEquals(3, suggestion.swatches.size)
        assertEquals(0xFF1A3C6E.toInt(), suggestion.swatches[0])
        assertEquals(0xFFE08A3C.toInt(), suggestion.swatches[2])
        assertEquals(2, suggestion.assignments.size)
        assertEquals(0xFF1A3C6E.toInt(), suggestion.assignments["s_001"])
        assertEquals(0xFFE08A3C.toInt(), suggestion.assignments["s_002"])
    }

    @Test
    fun acceptsBareObjectWithoutFence() {
        val raw = """{ "scheme": "Triadic", "swatches": ["#FF0000", "#00FF00", "#0000FF"] }"""
        val suggestion = PaletteParser.parse(raw).getOrThrow()
        assertEquals("Triadic", suggestion.schemeName)
        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()), suggestion.swatches)
        assertTrue(suggestion.assignments.isEmpty())
    }

    @Test
    fun clampsSwatchesToSixAndDropsMalformedColours() {
        val raw = """
            {
              "swatches": ["#111111", "not-a-color", "#222", "#333333", "#444444",
                           "#555555", "#666666", "#777777"]
            }
        """.trimIndent()
        val suggestion = PaletteParser.parse(raw).getOrThrow()
        // 8 supplied, one malformed dropped, capped at 6.
        assertEquals(PaletteSuggestion.MAX_SWATCHES, suggestion.swatches.size)
        // "#222" three-digit shorthand expands to #222222.
        assertTrue(suggestion.swatches.contains(0xFF222222.toInt()))
    }

    @Test
    fun failsWhenTooFewSwatches() {
        val raw = """{ "swatches": ["#111111", "#222222"] }"""
        assertTrue(PaletteParser.parse(raw).isFailure)
    }

    @Test
    fun failsOnNonJsonReply() {
        assertTrue(PaletteParser.parse("sorry, I can't help with that").isFailure)
        assertTrue(PaletteParser.parse("").isFailure)
    }

    @Test
    fun dropsAssignmentsForUnknownIdsButKeepsThePalette() {
        val raw = """
            {
              "swatches": ["#111111", "#222222", "#333333"],
              "assignments": [
                { "id": "s_001", "color": "#111111" },
                { "id": "ghost", "color": "#222222" }
              ]
            }
        """.trimIndent()
        val suggestion = PaletteParser.parse(raw, knownIds = setOf("s_001")).getOrThrow()
        assertEquals(3, suggestion.swatches.size)
        assertEquals(1, suggestion.assignments.size)
        assertTrue(suggestion.assignments.containsKey("s_001"))
        assertFalse(suggestion.assignments.containsKey("ghost"))
    }

    @Test
    fun snapsAssignmentColourToNearestSwatch() {
        val raw = """
            {
              "swatches": ["#000000", "#FFFFFF"],
              "assignments": [ { "id": "s_001", "color": "#0A0A0A" } ]
            }
        """.trimIndent()
        // Two swatches is below the MIN, so add a third to keep the doc valid.
        val raw3 = raw.replace("\"#FFFFFF\"", "\"#FFFFFF\", \"#808080\"")
        val suggestion = PaletteParser.parse(raw3, knownIds = setOf("s_001")).getOrThrow()
        // #0A0A0A is closest to black, not to white/grey.
        assertEquals(0xFF000000.toInt(), suggestion.assignments["s_001"])
    }

    @Test
    fun dropsAlphaFromEightDigitHex() {
        // RRGGBBAA → keep RRGGBB, force opaque.
        assertEquals(0xFF112233.toInt(), PaletteParser.parseColorOrNull("#11223380"))
    }

    @Test
    fun neverThrowsOnRandomGarbage() {
        // A handful of malformations must all fail gracefully, never throw.
        val garbage = listOf(
            "{ \"swatches\": ", "{ swatches: [#fff] }", "{ \"swatches\": 42 }",
            "{ \"swatches\": [ { } ] }", "{}", "[1,2,3]",
        )
        for (g in garbage) {
            // Either a graceful failure or a valid result — but no exception.
            PaletteParser.parse(g)
        }
    }
}
