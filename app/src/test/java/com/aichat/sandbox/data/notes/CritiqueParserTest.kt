package com.aichat.sandbox.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 — validation tests for the composition-critique reply parser.
 *
 * Pins the "never trust the model" discipline: fenced or bare JSON is accepted,
 * prose-only suggestions survive, optional `ops` are re-validated through
 * [EditOpsParser] (so invented / locked-layer ids drop) and then through the
 * safe-op subset (so destructive or broad-structure ops never previewable), and
 * a suggestion whose ops all fail degrades to prose-only instead of sinking the
 * whole reply.
 */
class CritiqueParserTest {

    @Test
    fun parsesWellFormedFencedReplyWithMixedCards() {
        val raw = """
            Here's my read:
            ```json
            {
              "schema": 1,
              "summary": "Lively sketch — a little crowded on the right.",
              "safetyNotes": "I couldn't see fine colour detail.",
              "suggestions": [
                {
                  "title": "Even out the spacing",
                  "principle": "Visual balance",
                  "why": "Most shapes sit on the right; nudging one left balances it.",
                  "confidence": "high",
                  "effort": "quick",
                  "ops": [ { "op": "transform", "ids": ["s_001"], "matrix": [1,0,-40,0,1,0] } ]
                },
                {
                  "title": "Add breathing room",
                  "principle": "Whitespace",
                  "why": "The title text is cramped against the border.",
                  "confidence": "medium",
                  "effort": "moderate"
                }
              ]
            }
            ```
        """.trimIndent()
        val critique = CritiqueParser.parse(raw, knownIds = setOf("s_001")).getOrThrow()
        assertEquals("Lively sketch — a little crowded on the right.", critique.summary)
        assertEquals("I couldn't see fine colour detail.", critique.safetyNotes)
        assertEquals(2, critique.suggestions.size)

        val first = critique.suggestions[0]
        assertEquals("Even out the spacing", first.title)
        assertEquals("Visual balance", first.principle)
        assertEquals(CritiqueConfidence.HIGH, first.confidence)
        assertEquals(CritiqueEffort.QUICK, first.effort)
        assertTrue(first.hasFix)
        assertEquals(1, first.ops.size)
        assertTrue(first.ops[0] is EditOp.Transform)

        // Second card is prose-only (no ops block).
        val second = critique.suggestions[1]
        assertFalse(second.hasFix)
        assertTrue(second.ops.isEmpty())
        assertEquals(CritiqueConfidence.MEDIUM, second.confidence)
        assertEquals(CritiqueEffort.MODERATE, second.effort)
    }

    @Test
    fun acceptsBareObjectWithoutFence() {
        val raw = """{ "summary": "Nice start.",
            "suggestions": [ { "title": "Center it", "why": "It drifts left." } ] }"""
        val critique = CritiqueParser.parse(raw).getOrThrow()
        assertEquals("Nice start.", critique.summary)
        assertEquals(1, critique.suggestions.size)
        assertFalse(critique.suggestions[0].hasFix)
    }

    @Test
    fun proseOnlyCritiqueIsValid() {
        // No ops anywhere — the feature must still be useful.
        val raw = """
            {
              "summary": "Good composition overall.",
              "suggestions": [
                { "title": "Vary line weight", "principle": "Contrast",
                  "why": "Everything is the same thickness, so nothing stands out." },
                { "title": "Group related marks", "principle": "Proximity",
                  "why": "Items that belong together are spread apart." }
              ]
            }
        """.trimIndent()
        val critique = CritiqueParser.parse(raw).getOrThrow()
        assertEquals(2, critique.suggestions.size)
        assertTrue(critique.suggestions.none { it.hasFix })
    }

    @Test
    fun unsafeOpsAreStrippedLeavingProseOnlyCard() {
        // delete + add_path are destructive/broad — they must never survive, so
        // this card degrades to prose-only rather than offering a fix.
        val raw = """
            {
              "summary": "…",
              "suggestions": [
                {
                  "title": "Declutter",
                  "why": "Too many stray marks.",
                  "ops": [
                    { "op": "delete", "ids": ["s_001"] },
                    { "op": "add_path", "subpaths": [ { "closed": true,
                       "anchors": [ [0,0], [10,0], [10,10] ] } ] }
                  ]
                }
              ]
            }
        """.trimIndent()
        val critique = CritiqueParser.parse(raw, knownIds = setOf("s_001")).getOrThrow()
        assertEquals(1, critique.suggestions.size)
        assertFalse("destructive ops must not become a fix", critique.suggestions[0].hasFix)
    }

    @Test
    fun mixedValidAndInvalidOpsKeepsOnlySafeAndKnownOnes() {
        val raw = """
            {
              "suggestions": [
                {
                  "title": "Tidy the box",
                  "why": "Wonky and off-colour.",
                  "ops": [
                    { "op": "recolor", "ids": ["s_001"], "color": "#3366CC" },
                    { "op": "recolor", "ids": ["ghost"], "color": "#FF0000" },
                    { "op": "delete", "ids": ["s_001"] },
                    { "op": "simplify", "ids": ["s_001"], "tolerance": 1.5 }
                  ]
                }
              ]
            }
        """.trimIndent()
        val critique = CritiqueParser.parse(raw, knownIds = setOf("s_001")).getOrThrow()
        val ops = critique.suggestions[0].ops
        // recolor(ghost) dropped (unknown id), delete dropped (unsafe) →
        // recolor(s_001) + simplify(s_001) remain.
        assertEquals(2, ops.size)
        assertTrue(ops.any { it is EditOp.Recolor && it.ids == listOf("s_001") })
        assertTrue(ops.any { it is EditOp.Simplify })
        assertFalse(ops.any { it is EditOp.Delete })
    }

    @Test
    fun opsReferencingUnknownLayerAreDropped() {
        val raw = """
            {
              "suggestions": [
                { "title": "Move to background", "why": "It should sit behind.",
                  "ops": [ { "op": "set_layer", "ids": ["s_001"], "layer": "L9" } ] }
              ]
            }
        """.trimIndent()
        // set_layer is unsafe anyway, but also references an unknown layer; the
        // card survives as prose-only.
        val critique = CritiqueParser.parse(
            raw, knownIds = setOf("s_001"), knownLayers = setOf("L1"),
        ).getOrThrow()
        assertFalse(critique.suggestions[0].hasFix)
    }

    @Test
    fun dropsCardsWithNeitherTitleNorWhy() {
        val raw = """
            {
              "suggestions": [
                { "principle": "Balance" },
                { "title": "Real one", "why": "Has content." }
              ]
            }
        """.trimIndent()
        val critique = CritiqueParser.parse(raw).getOrThrow()
        // The bare-principle card has no explicit title or why, so it's dropped;
        // only "Real one" survives.
        assertEquals(1, critique.suggestions.size)
        assertEquals("Real one", critique.suggestions[0].title)
    }

    @Test
    fun clampsToFiveSuggestions() {
        val cards = (1..8).joinToString(",") {
            """{ "title": "S$it", "why": "reason $it" }"""
        }
        val raw = """{ "suggestions": [ $cards ] }"""
        val critique = CritiqueParser.parse(raw).getOrThrow()
        assertEquals(CompositionCritique.MAX_SUGGESTIONS, critique.suggestions.size)
    }

    @Test
    fun failsWhenNoSuggestions() {
        assertTrue(CritiqueParser.parse("""{ "summary": "ok", "suggestions": [] }""").isFailure)
        assertTrue(CritiqueParser.parse("""{ "summary": "ok" }""").isFailure)
    }

    @Test
    fun failsOnNonJsonReply() {
        assertTrue(CritiqueParser.parse("sorry, I can't help with that").isFailure)
        assertTrue(CritiqueParser.parse("").isFailure)
    }

    @Test
    fun neverThrowsOnRandomGarbage() {
        val garbage = listOf(
            "{ \"suggestions\": ", "{ suggestions: [x] }", "{ \"suggestions\": 42 }",
            "{ \"suggestions\": [ { } ] }", "{}", "[1,2,3]",
            "{ \"suggestions\": [ { \"title\": 5, \"ops\": \"nope\" } ] }",
        )
        for (g in garbage) {
            // Either a graceful failure or a valid result — but no exception.
            CritiqueParser.parse(g)
        }
    }
}
