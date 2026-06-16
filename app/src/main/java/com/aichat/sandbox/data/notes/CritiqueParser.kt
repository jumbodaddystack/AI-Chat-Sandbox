package com.aichat.sandbox.data.notes

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Phase 3 — parser/validator for the composition-critique assistant's reply.
 *
 * Mirrors the "never trust the model" discipline of [EditOpsParser] and
 * [PaletteParser]: it tolerates fenced or bare JSON, keeps prose-only
 * suggestions, and — crucially — runs every suggestion's optional `ops` payload
 * back through [EditOpsParser] (so invented ids / locked-layer targets are
 * dropped) and then through [CritiqueSuggestion.isSafeOp] (so destructive or
 * broad-structure ops never survive). A suggestion whose ops all fail
 * validation degrades to prose-only rather than sinking the whole reply.
 *
 * The document fails only when there is no usable JSON or no suggestion
 * survives.
 *
 * Expected reply shape:
 * ```
 * { "schema": 1,
 *   "summary": "<one short sentence>",
 *   "safetyNotes": "<optional caveats>",
 *   "suggestions": [
 *     { "title": "…", "principle": "…", "why": "…",
 *       "confidence": "high|medium|low", "effort": "quick|moderate|involved",
 *       "ops": [ { "op": "transform", "ids": ["s_001"], "matrix": [...] }, … ] },
 *     …
 *   ] }
 * ```
 */
object CritiqueParser {

    /**
     * Parse [raw] into a validated [CompositionCritique]. Pass the serialized
     * canvas's short ids / layer ids as [knownIds] / [knownLayers] so any op
     * referencing an item or layer the model invented is dropped; `null`
     * accepts anything (handy for unit tests).
     */
    fun parse(
        raw: String,
        knownIds: Set<String>? = null,
        knownLayers: Set<String>? = null,
    ): Result<CompositionCritique> {
        if (raw.isBlank()) return Result.failure(IllegalArgumentException("empty reply"))
        val jsonText = EditOpsParser.extractJson(raw)
            ?: return Result.failure(IllegalArgumentException("no JSON block found in reply"))
        return try {
            val root = JsonParser.parseString(jsonText) as? JsonObject
                ?: return Result.failure(IllegalArgumentException("top-level JSON is not an object"))
            val suggestions = parseSuggestions(root.get("suggestions"), knownIds, knownLayers)
            if (suggestions.size < CompositionCritique.MIN_SUGGESTIONS) {
                return Result.failure(IllegalArgumentException("critique: no usable suggestions"))
            }
            val summary = stringOrNull(root, "summary")
                ?: stringOrNull(root, "overview")
                ?: ""
            val safetyNotes = stringOrNull(root, "safetyNotes")
                ?: stringOrNull(root, "safety_notes")
                ?: stringOrNull(root, "caveats")
                ?: ""
            Result.success(
                CompositionCritique(
                    summary = summary,
                    suggestions = suggestions,
                    safetyNotes = safetyNotes,
                )
            )
        } catch (t: Throwable) {
            Result.failure(IllegalArgumentException("malformed JSON: ${t.message}"))
        }
    }

    /**
     * Read the suggestion list. Each row needs at least a [CritiqueSuggestion.why]
     * or [CritiqueSuggestion.title] to be worth showing; rows with neither are
     * skipped. Caps at [CompositionCritique.MAX_SUGGESTIONS].
     */
    private fun parseSuggestions(
        el: com.google.gson.JsonElement?,
        knownIds: Set<String>?,
        knownLayers: Set<String>?,
    ): List<CritiqueSuggestion> {
        val arr = el as? JsonArray ?: return emptyList()
        val out = ArrayList<CritiqueSuggestion>(arr.size())
        for (e in arr) {
            val obj = e as? JsonObject ?: continue
            val title = stringOrNull(obj, "title")
                ?: stringOrNull(obj, "name")
            val why = stringOrNull(obj, "why")
                ?: stringOrNull(obj, "reason")
                ?: stringOrNull(obj, "detail")
                ?: stringOrNull(obj, "description")
                ?: ""
            // A card with neither an explicit label nor a reason is noise — drop
            // it (a bare `principle` isn't enough to render a useful card).
            if (title.isNullOrBlank() && why.isBlank()) continue
            val principle = stringOrNull(obj, "principle")
                ?: stringOrNull(obj, "category")
                ?: ""
            val ops = parseSafeOps(obj.get("ops"), knownIds, knownLayers)
            out += CritiqueSuggestion(
                title = title ?: principle.ifBlank { "Suggestion" },
                principle = principle,
                why = why,
                confidence = CritiqueConfidence.fromString(stringOrNull(obj, "confidence")),
                effort = CritiqueEffort.fromString(stringOrNull(obj, "effort")),
                ops = ops,
            )
            if (out.size >= CompositionCritique.MAX_SUGGESTIONS) break
        }
        return out
    }

    /**
     * Validate a suggestion's optional `ops` array. Reuses [EditOpsParser]'s
     * per-op parsing + id/layer filtering (by wrapping the array in a synthetic
     * `{ "ops": [...] }` doc), then keeps only the non-destructive subset
     * [CritiqueSuggestion.isSafeOp] allows. Any failure yields an empty list, so
     * the suggestion simply becomes prose-only instead of erroring.
     */
    private fun parseSafeOps(
        el: com.google.gson.JsonElement?,
        knownIds: Set<String>?,
        knownLayers: Set<String>?,
    ): List<EditOp> {
        val arr = el as? JsonArray ?: return emptyList()
        if (arr.size() == 0) return emptyList()
        val wrapper = JsonObject().apply { add("ops", arr) }
        val doc = EditOpsParser.parse(wrapper.toString(), knownIds, knownLayers).getOrNull()
            ?: return emptyList()
        return doc.ops.filter { CritiqueSuggestion.isSafeOp(it) }
    }

    private fun stringOrNull(obj: JsonObject, key: String): String? =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * System message for the composition-critique assistant. Pins the exact JSON
     * contract and the safe op vocabulary so a compliant reply parses cleanly.
     * [knownIds] reach the model through the embedded canvas JSON, so any op ids
     * it proposes line up with the applier.
     */
    const val SYSTEM_MESSAGE: String =
        "You are a friendly composition coach for a beginner artist. You receive " +
            "a drawing as a JSON description of every item by ID (and usually an " +
            "image). Give warm, concrete, jargon-free feedback on how to improve " +
            "the layout and composition.\n\n" +
            "Reply with ONLY a fenced ```json block matching this schema:\n\n" +
            "{ \"schema\": 1,\n" +
            "  \"summary\": \"<one short, encouraging sentence>\",\n" +
            "  \"safetyNotes\": \"<optional caveats, or empty>\",\n" +
            "  \"suggestions\": [\n" +
            "    { \"title\": \"<short action-oriented label>\",\n" +
            "      \"principle\": \"<design principle, e.g. balance, contrast, spacing>\",\n" +
            "      \"why\": \"<one or two plain sentences>\",\n" +
            "      \"confidence\": \"high|medium|low\",\n" +
            "      \"effort\": \"quick|moderate|involved\",\n" +
            "      \"ops\": [ /* OPTIONAL edit-ops realising this suggestion */ ] }\n" +
            "  ] }\n\n" +
            "Rules:\n" +
            "- Give 3 to 5 suggestions, most impactful first.\n" +
            "- Always explain the `why` in beginner-friendly language. The advice " +
            "must stand on its own even with no `ops`.\n" +
            "- `ops` is optional. Include it ONLY when a concrete, safe tidy is " +
            "obvious. Reference items by the `id`s in the provided JSON.\n" +
            "- Allowed ops only: `transform` (to align/scale/nudge), `recolor`, " +
            "`restyle`, `simplify`, `smooth`, `replace_with_shape`. NEVER delete, " +
            "add new geometry, regroup, or move items between layers.\n" +
            "- Do not target items on locked or hidden layers.\n" +
            "- Never reply outside the fenced block."
}
