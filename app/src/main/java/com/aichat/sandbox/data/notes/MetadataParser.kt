package com.aichat.sandbox.data.notes

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Phase 9 — parser/validator for the metadata assistant's model reply.
 *
 * Mirrors the "never trust the model" discipline of [PaletteParser] /
 * [CritiqueParser]: it tolerates fenced or bare JSON, trims and caps the
 * [NoteMetadataSuggestion.title] / [NoteMetadataSuggestion.description], and
 * routes every tag through [IconTags.normalize] (drop-blank, de-dupe, cap at
 * [IconTags.MAX_TAGS_PER_NOTE]) so the stored form lines up with the gallery's
 * chip-filter and count queries. The document fails only when there is no
 * usable JSON or the reply carries no usable field at all — a single bad tag or
 * an over-long title never sinks an otherwise-good suggestion.
 *
 * Expected reply shape:
 * ```
 * { "schema": 1, "title": "<short title>",
 *   "tags": ["nav", "settings", …],
 *   "description": "<one short sentence>" }
 * ```
 */
object MetadataParser {

    /** Parse [raw] into a validated [NoteMetadataSuggestion]. */
    fun parse(raw: String): Result<NoteMetadataSuggestion> {
        if (raw.isBlank()) return Result.failure(IllegalArgumentException("empty reply"))
        val jsonText = EditOpsParser.extractJson(raw)
            ?: return Result.failure(IllegalArgumentException("no JSON block found in reply"))
        return try {
            val root = JsonParser.parseString(jsonText) as? JsonObject
                ?: return Result.failure(IllegalArgumentException("top-level JSON is not an object"))
            val title = parseTitle(root)
            val tags = parseTags(root.get("tags"))
            val description = parseDescription(root)
            val suggestion = NoteMetadataSuggestion(
                title = title,
                tags = tags,
                description = description,
            )
            if (suggestion.isEmpty) {
                Result.failure(IllegalArgumentException("metadata: no usable title, tags, or description"))
            } else {
                Result.success(suggestion)
            }
        } catch (t: Throwable) {
            Result.failure(IllegalArgumentException("malformed JSON: ${t.message}"))
        }
    }

    private fun parseTitle(root: JsonObject): String {
        val raw = root.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: root.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            ?: return ""
        // Collapse inner whitespace/newlines — a title is a single label line.
        val collapsed = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        return collapsed.take(NoteMetadataSuggestion.MAX_TITLE_LENGTH)
    }

    private fun parseDescription(root: JsonObject): String {
        val raw = (root.get("description") ?: root.get("altText") ?: root.get("alt"))
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            ?: return ""
        val collapsed = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        return collapsed.take(NoteMetadataSuggestion.MAX_DESCRIPTION_LENGTH)
    }

    /**
     * Read the tag list. Accepts an array of strings or of `{ "tag": "…" }`
     * objects (some models wrap each tag). Each is normalized to its stored
     * form via [IconTags.normalize]; blanks/over-long tags drop out, the set is
     * de-duped (first-seen order preserved), and capped at
     * [IconTags.MAX_TAGS_PER_NOTE].
     */
    private fun parseTags(el: com.google.gson.JsonElement?): List<String> {
        val arr = el as? JsonArray ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (e in arr) {
            val rawTag = when {
                e.isJsonPrimitive -> e.asString
                e.isJsonObject -> (e.asJsonObject.get("tag") ?: e.asJsonObject.get("name"))
                    ?.takeIf { it.isJsonPrimitive }?.asString
                else -> null
            } ?: continue
            val normalized = IconTags.normalize(rawTag)
            if (normalized.isNotEmpty()) out.add(normalized)
            if (out.size >= IconTags.MAX_TAGS_PER_NOTE) break
        }
        return out.toList()
    }

    /**
     * System message for the metadata assistant. Pins the exact JSON contract so
     * a compliant reply parses cleanly. Asks for organization-friendly text:
     * short title, a few keyword tags, and one plain-language caption usable as
     * accessibility alt text.
     */
    const val SYSTEM_MESSAGE: String =
        "You label drawings for organization and accessibility. You receive a " +
            "drawing as a JSON description of every item by ID (and sometimes an " +
            "image and/or transcribed handwriting). Suggest concise metadata.\n\n" +
            "Reply with ONLY a fenced ```json block matching this schema:\n\n" +
            "{ \"schema\": 1,\n" +
            "  \"title\": \"<a short, specific title, ≤8 words>\",\n" +
            "  \"tags\": [ \"<keyword>\", … up to 8 lowercase keywords ],\n" +
            "  \"description\": \"<one short sentence describing the drawing, for screen readers>\" }\n\n" +
            "Rules:\n" +
            "- Keep the title short and specific; no quotes, no trailing punctuation.\n" +
            "- Tags are single concepts (1–2 words each), lowercase, no '#'.\n" +
            "- The description is one short, factual sentence — what the drawing shows, " +
            "not how it was made. It is read aloud as alt text, so keep it under 30 words.\n" +
            "- Never reply outside the fenced block."
}
