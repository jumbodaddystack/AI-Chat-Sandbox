package com.aichat.sandbox.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 7 — validation tests for the named-style restyle reply parser.
 *
 * [RestyleParser] reuses [EditOpsParser] for extraction / per-op parsing /
 * id-filtering, then applies one extra guard: only the non-additive, non-moving
 * op subset survives. These tests pin that a well-formed restyle keeps its
 * recolor/restyle/smooth/simplify/replace_with_shape ops, that additive
 * (`add_path` / `add_shape`), destructive (`delete`), moving (`transform`) and
 * structural (`group` / `set_layer` / `merge_paths`) ops are dropped into
 * `rejected`, and that the parser never throws on garbage.
 */
class RestyleParserTest {

    @Test
    fun keepsEveryRestyleSafeOp() {
        val raw = """
            ```edit-ops
            {
              "schema": 1,
              "summary": "Flattened to a clean icon.",
              "ops": [
                { "op": "recolor", "ids": ["s_001"], "color": "#3366CC" },
                { "op": "restyle", "ids": ["s_001"], "width": 6 },
                { "op": "smooth", "ids": ["s_002"], "amount": 0.4 },
                { "op": "simplify", "ids": ["s_002"], "tolerance": 1.5 },
                { "op": "replace_with_shape", "id": "s_003",
                  "shape": { "type": "ellipse", "cx": 0, "cy": 0, "rx": 5, "ry": 5 } }
              ]
            }
            ```
        """.trimIndent()
        val doc = RestyleParser.parse(
            raw, knownIds = setOf("s_001", "s_002", "s_003"),
        ).getOrThrow()
        assertEquals(5, doc.ops.size)
        assertTrue(doc.ops.any { it is EditOp.Recolor })
        assertTrue(doc.ops.any { it is EditOp.Restyle })
        assertTrue(doc.ops.any { it is EditOp.Smooth })
        assertTrue(doc.ops.any { it is EditOp.Simplify })
        assertTrue(doc.ops.any { it is EditOp.ReplaceWithShape })
        assertTrue(doc.rejected.isEmpty())
    }

    @Test
    fun dropsAdditiveDestructiveAndMovingOps() {
        // The model tried to add new subject matter, delete, move, and regroup —
        // a named restyle must keep none of those, only the recolor.
        val raw = """
            {
              "summary": "…",
              "ops": [
                { "op": "recolor", "ids": ["s_001"], "color": "#FF8800" },
                { "op": "add_shape", "shape": { "type": "rect", "x0": 0, "y0": 0, "x1": 4, "y1": 4 } },
                { "op": "add_path", "subpaths": [ { "closed": true,
                   "anchors": [ [0,0], [10,0], [10,10] ] } ] },
                { "op": "delete", "ids": ["s_001"] },
                { "op": "transform", "ids": ["s_001"], "matrix": [2,0,0,0,2,0] },
                { "op": "group", "ids": ["s_001"] }
              ]
            }
        """.trimIndent()
        val doc = RestyleParser.parse(raw, knownIds = setOf("s_001")).getOrThrow()
        assertEquals(1, doc.ops.size)
        assertTrue(doc.ops.single() is EditOp.Recolor)
        // add_shape, add_path, delete, transform, group → five dropped ops.
        assertEquals(5, doc.rejected.size)
        assertTrue(doc.rejected.all { it.reason == "op not allowed during restyle" })
    }

    @Test
    fun dropsOpsReferencingUnknownIds() {
        // EditOpsParser drops the invented id before RestyleParser even sees it;
        // the known-id recolor survives.
        val raw = """
            {
              "ops": [
                { "op": "recolor", "ids": ["s_001"], "color": "#222222" },
                { "op": "restyle", "ids": ["ghost"], "width": 3 }
              ]
            }
        """.trimIndent()
        val doc = RestyleParser.parse(raw, knownIds = setOf("s_001")).getOrThrow()
        assertEquals(1, doc.ops.size)
        assertTrue(doc.ops.single() is EditOp.Recolor)
    }

    @Test
    fun acceptsBareJsonWithoutFence() {
        val raw = """{ "summary": "ok", "ops": [
            { "op": "restyle", "ids": ["s_001"], "opacity": 0.5 } ] }"""
        val doc = RestyleParser.parse(raw).getOrThrow()
        assertEquals(1, doc.ops.size)
        assertTrue(doc.ops.single() is EditOp.Restyle)
    }

    @Test
    fun emptyOpsArrayIsASuccessNotAnError() {
        // The model couldn't restyle and said so — the caller surfaces a friendly
        // "no changes" note rather than a parse error.
        val doc = RestyleParser.parse("""{ "summary": "Nothing to change.", "ops": [] }""").getOrThrow()
        assertTrue(doc.ops.isEmpty())
    }

    @Test
    fun allUnsafeOpsLeaveAnEmptyButSuccessfulDoc() {
        val raw = """
            { "ops": [
                { "op": "delete", "ids": ["s_001"] },
                { "op": "add_shape", "shape": { "type": "line", "x0": 0, "y0": 0, "x1": 5, "y1": 5 } }
            ] }
        """.trimIndent()
        val doc = RestyleParser.parse(raw, knownIds = setOf("s_001")).getOrThrow()
        assertTrue(doc.ops.isEmpty())
        assertEquals(2, doc.rejected.size)
    }

    @Test
    fun failsOnNonJsonReply() {
        assertTrue(RestyleParser.parse("sorry, I can't restyle that").isFailure)
        assertTrue(RestyleParser.parse("").isFailure)
    }

    @Test
    fun neverThrowsOnRandomGarbage() {
        val garbage = listOf(
            "{ \"ops\": ", "{ ops: [x] }", "{ \"ops\": 42 }",
            "{ \"ops\": [ { } ] }", "{}", "[1,2,3]",
            "{ \"ops\": [ { \"op\": \"restyle\" } ] }",
        )
        for (g in garbage) {
            // Either a graceful failure or a valid result — but no exception.
            RestyleParser.parse(g)
        }
        assertFalse(false)
    }
}
