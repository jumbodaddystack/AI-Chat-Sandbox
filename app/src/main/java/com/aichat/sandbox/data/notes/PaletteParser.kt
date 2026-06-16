package com.aichat.sandbox.data.notes

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Phase 2 — parser/validator for the palette assistant's model reply.
 *
 * Mirrors the "never trust the model" discipline of [EditOpsParser] and
 * [BrushSpecParser]: it tolerates fenced or bare JSON, clamps the swatch list
 * to the [PaletteSuggestion.MIN_SWATCHES]..[PaletteSuggestion.MAX_SWATCHES]
 * range, drops malformed colours and assignment rows, and only keeps
 * assignment ids that appear in [knownIds]. The whole document fails only when
 * there is no usable JSON or no valid swatch survives — a bad assignment row
 * never sinks an otherwise-good palette.
 *
 * Expected reply shape:
 * ```
 * { "schema": 1, "scheme": "complementary",
 *   "rationale": "<one or two sentences>",
 *   "swatches": ["#RRGGBB", …],
 *   "assignments": [ { "id": "s_001", "color": "#RRGGBB" }, … ] }
 * ```
 */
object PaletteParser {

    /**
     * Parse [raw] into a validated [PaletteSuggestion]. Pass the serialized
     * canvas's short ids as [knownIds] so invented assignment targets are
     * dropped; `null` accepts any id (handy for unit tests).
     */
    fun parse(raw: String, knownIds: Set<String>? = null): Result<PaletteSuggestion> {
        if (raw.isBlank()) return Result.failure(IllegalArgumentException("empty reply"))
        val jsonText = EditOpsParser.extractJson(raw)
            ?: return Result.failure(IllegalArgumentException("no JSON block found in reply"))
        return try {
            val root = JsonParser.parseString(jsonText) as? JsonObject
                ?: return Result.failure(IllegalArgumentException("top-level JSON is not an object"))
            val swatches = parseSwatches(root.get("swatches"))
            if (swatches.size < PaletteSuggestion.MIN_SWATCHES) {
                return Result.failure(IllegalArgumentException("palette: need ≥${PaletteSuggestion.MIN_SWATCHES} swatches"))
            }
            val schemeName = root.get("scheme")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "Custom palette"
            val rationale = root.get("rationale")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                ?: root.get("summary")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                ?: ""
            Result.success(
                PaletteSuggestion(
                    schemeName = schemeName,
                    swatches = swatches,
                    rationale = rationale,
                    assignments = parseAssignments(root.get("assignments"), swatches, knownIds),
                )
            )
        } catch (t: Throwable) {
            Result.failure(IllegalArgumentException("malformed JSON: ${t.message}"))
        }
    }

    /**
     * Read the swatch list. Accepts an array of hex strings or of
     * `{ "color": "#…" }` objects (some models wrap each swatch). De-dupes while
     * preserving order and caps at [PaletteSuggestion.MAX_SWATCHES].
     */
    private fun parseSwatches(el: com.google.gson.JsonElement?): List<Int> {
        val arr = el as? JsonArray ?: return emptyList()
        val out = LinkedHashSet<Int>()
        for (e in arr) {
            val hex = when {
                e.isJsonPrimitive -> e.asString
                e.isJsonObject -> (e.asJsonObject.get("color") ?: e.asJsonObject.get("hex"))
                    ?.takeIf { it.isJsonPrimitive }?.asString
                else -> null
            } ?: continue
            parseColorOrNull(hex)?.let { out.add(it) }
            if (out.size >= PaletteSuggestion.MAX_SWATCHES) break
        }
        return out.toList()
    }

    /**
     * Read the optional per-item assignment plan. Each row needs an `id` (kept
     * only when in [knownIds]) and a parseable `color`; the colour is snapped to
     * the nearest [swatches] entry so the applier can never introduce a colour
     * outside the proposed palette. Malformed rows are skipped silently.
     */
    private fun parseAssignments(
        el: com.google.gson.JsonElement?,
        swatches: List<Int>,
        knownIds: Set<String>?,
    ): Map<String, Int> {
        val arr = el as? JsonArray ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (e in arr) {
            val obj = e as? JsonObject ?: continue
            val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                ?: obj.get("itemId")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                ?: continue
            if (id.isEmpty()) continue
            if (knownIds != null && id !in knownIds) continue
            val color = (obj.get("color") ?: obj.get("hex"))?.takeIf { it.isJsonPrimitive }?.asString
                ?.let { parseColorOrNull(it) } ?: continue
            out[id] = nearestSwatch(color, swatches)
        }
        return out
    }

    /** Snap [color] to whichever [swatches] entry is closest in RGB space. */
    private fun nearestSwatch(color: Int, swatches: List<Int>): Int {
        if (swatches.isEmpty()) return color
        if (color in swatches) return color
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return swatches.minByOrNull { s ->
            val dr = ((s shr 16) and 0xFF) - r
            val dg = ((s shr 8) and 0xFF) - g
            val db = (s and 0xFF) - b
            dr * dr + dg * dg + db * db
        } ?: color
    }

    /**
     * Parse `#RGB` / `#RRGGBB` / `#RRGGBBAA` (alpha forced opaque) into an ARGB
     * int, or null when unparseable. Swatches are always shown opaque — alpha is
     * not part of palette harmony — so a model that supplies it is ignored.
     */
    internal fun parseColorOrNull(hex: String?): Int? {
        val raw = hex?.trim()?.removePrefix("#") ?: return null
        return try {
            val rgb = when (raw.length) {
                6 -> raw.toLong(16).toInt() and 0x00FFFFFF
                8 -> (raw.toLong(16).toInt() shr 8) and 0x00FFFFFF // RRGGBBAA → drop AA
                3 -> {
                    val r = raw[0].digitToInt(16) * 17
                    val g = raw[1].digitToInt(16) * 17
                    val b = raw[2].digitToInt(16) * 17
                    (r shl 16) or (g shl 8) or b
                }
                else -> return null
            }
            (0xFF shl 24) or rgb
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * System message for the palette assistant. Pins the exact JSON contract so
     * a compliant reply parses cleanly; [knownIds] reach the model through the
     * embedded canvas JSON, so its assignment ids line up with the applier.
     */
    const val SYSTEM_MESSAGE: String =
        "You are a colour-harmony assistant for a beginner artist. You receive a " +
            "drawing as a JSON description of every item by ID (and sometimes an " +
            "image). Suggest one cohesive palette.\n\n" +
            "Reply with ONLY a fenced ```json block matching this schema:\n\n" +
            "{ \"schema\": 1, \"scheme\": \"<scheme name>\",\n" +
            "  \"rationale\": \"<one or two friendly sentences>\",\n" +
            "  \"swatches\": [ \"#RRGGBB\", … 3 to 6 colours ],\n" +
            "  \"assignments\": [ { \"id\": \"<item id from the JSON>\", \"color\": \"#RRGGBB\" }, … ] }\n\n" +
            "Rules:\n" +
            "- Give 3 to 6 swatches drawn from the requested harmony family.\n" +
            "- Each assignment colour MUST be one of your swatches.\n" +
            "- Only reference item ids that appear in the provided JSON; omit " +
            "assignments if you're unsure.\n" +
            "- Keep the rationale short and jargon-free. Never reply outside the fenced block."
}
