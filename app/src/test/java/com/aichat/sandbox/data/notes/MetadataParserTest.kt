package com.aichat.sandbox.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9 — JVM coverage for the metadata assistant parser: title/tag/
 * description validation, duplicate-tag de-duplication, normalization, caps,
 * and the "no usable field" failure.
 */
class MetadataParserTest {

    @Test
    fun parsesWellFormedFencedReply() {
        val raw = """
            Here you go:
            ```json
            {
              "schema": 1,
              "title": "Settings gear",
              "tags": ["settings", "gear", "ui"],
              "description": "A grey gear icon representing settings."
            }
            ```
        """.trimIndent()
        val s = MetadataParser.parse(raw).getOrThrow()
        assertEquals("Settings gear", s.title)
        assertEquals(listOf("settings", "gear", "ui"), s.tags)
        assertEquals("A grey gear icon representing settings.", s.description)
    }

    @Test
    fun parsesBareJsonWithoutFence() {
        val raw = """{ "title": "Map", "tags": ["map"], "description": "A small map." }"""
        val s = MetadataParser.parse(raw).getOrThrow()
        assertEquals("Map", s.title)
        assertEquals(listOf("map"), s.tags)
    }

    @Test
    fun normalizesAndDeDupesTags() {
        // Mixed case + whitespace + an exact duplicate + an object-wrapped tag.
        val raw = """
            {
              "title": "Nav",
              "tags": ["  Settings ", "settings", "NAV BAR", {"tag": "nav bar"}, ""],
              "description": ""
            }
        """.trimIndent()
        val s = MetadataParser.parse(raw).getOrThrow()
        // "  Settings " and "settings" collapse to one; "NAV BAR" and the
        // object "nav bar" collapse to one; the blank drops out.
        assertEquals(listOf("settings", "nav bar"), s.tags)
    }

    @Test
    fun capsTagsTitleAndDescription() {
        val manyTags = (1..40).joinToString(",") { "\"tag$it\"" }
        val longTitle = "word ".repeat(60).trim()
        val longDesc = "sentence ".repeat(80).trim()
        val raw = """
            { "title": "$longTitle", "tags": [$manyTags], "description": "$longDesc" }
        """.trimIndent()
        val s = MetadataParser.parse(raw).getOrThrow()
        assertEquals(IconTags.MAX_TAGS_PER_NOTE, s.tags.size)
        assertTrue(s.title.length <= NoteMetadataSuggestion.MAX_TITLE_LENGTH)
        assertTrue(s.description.length <= NoteMetadataSuggestion.MAX_DESCRIPTION_LENGTH)
    }

    @Test
    fun acceptsTitleOnlyReply() {
        val raw = """{ "title": "Just a title" }"""
        val s = MetadataParser.parse(raw).getOrThrow()
        assertEquals("Just a title", s.title)
        assertTrue(s.tags.isEmpty())
        assertTrue(s.description.isEmpty())
    }

    @Test
    fun acceptsTagsOnlyReply() {
        val raw = """{ "tags": ["a", "b"] }"""
        val s = MetadataParser.parse(raw).getOrThrow()
        assertEquals(listOf("a", "b"), s.tags)
        assertTrue(s.title.isEmpty())
    }

    @Test
    fun readsAltTextAlias() {
        val raw = """{ "title": "X", "altText": "alias description" }"""
        val s = MetadataParser.parse(raw).getOrThrow()
        assertEquals("alias description", s.description)
    }

    @Test
    fun failsWhenNoUsableField() {
        assertTrue(MetadataParser.parse("""{ "title": "", "tags": [], "description": "" }""").isFailure)
    }

    @Test
    fun failsOnEmptyOrJsonlessReply() {
        assertTrue(MetadataParser.parse("").isFailure)
        assertTrue(MetadataParser.parse("just prose, no json here").isFailure)
    }

    @Test
    fun garbageDoesNotThrow() {
        // A malformed/garbage reply returns a failure rather than throwing.
        assertTrue(MetadataParser.parse("{ not valid json ]").isFailure)
    }
}
