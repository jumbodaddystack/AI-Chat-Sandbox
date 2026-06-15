package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.BrushPreset
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/**
 * Phase **I4 / N1 — AI brush designer** (text → brush spec).
 *
 * A [BrushSpec] is the small, **validated** JSON contract the `DESIGN_BRUSH`
 * AI mode emits ("make me a dry-gouache brush with taper" → these fields). It is
 * validated exactly like an `edit-ops` document ([EditOpsParser]): the parser
 * never trusts the model, clamping every numeric field to its renderable range
 * and rejecting unknown tools / curve ids / texture ids. A valid spec maps to a
 * **user-scope** [BrushPreset] via [toPreset] — a reusable, editable brush, not
 * a one-off render — so nothing about the canvas or the `StrokeCodec` pipeline is
 * touched (Adoption principle 2). The preset then renders through the stable I4
 * [com.aichat.sandbox.data.ink.InkBrushFamilies] adapter like any other.
 *
 * The field set mirrors the renderable, user-facing semantics of [BrushPreset]
 * (tool / colour / width / opacity / taper / jitter / pressure-curve / texture);
 * it deliberately does **not** expose ink's internal `BrushTip`/`BrushBehavior`
 * graph — that programmatic form is the isolated 1.1-alpha experiment (see
 * [com.aichat.sandbox.data.ink.experimental.InkProgrammableBrush]).
 */
data class BrushSpec(
    val name: String,
    val tool: String,
    val colorArgb: Int,
    val baseWidthPx: Float,
    val opacity: Float,
    val taperStart: Float,
    val taperEnd: Float,
    val jitter: Float,
    val pressureCurveId: String,
    val textureId: String,
) {
    /**
     * Materialize this spec as a persistable **user-scope** [BrushPreset].
     * [ordinal] is the row's sort position (the caller assigns it from the
     * existing preset count); [id] defaults to a fresh UUID.
     */
    fun toPreset(ordinal: Int): BrushPreset = BrushPreset(
        ownerScope = BrushPreset.SCOPE_USER,
        name = name,
        tool = tool,
        colorArgb = colorArgb,
        baseWidthPx = baseWidthPx,
        opacity = opacity,
        taperStart = taperStart,
        taperEnd = taperEnd,
        jitter = jitter,
        pressureCurveId = pressureCurveId,
        textureId = textureId,
        ordinal = ordinal,
    )

    companion object {
        const val SCHEMA = 1

        /** Width clamp mirrors the live tool dynamics' defensive bounds. */
        const val MIN_WIDTH_PX = 0.5f
        const val MAX_WIDTH_PX = 64f

        val VALID_TOOLS = setOf("pen", "pencil", "highlighter", "marker")

        val VALID_CURVES = setOf(
            BrushPreset.CURVE_LINEAR,
            BrushPreset.CURVE_EASE_IN,
            BrushPreset.CURVE_EASE_OUT,
            BrushPreset.CURVE_EASE_IN_OUT,
        )

        val VALID_TEXTURES = setOf(
            BrushPreset.TEXTURE_SMOOTH,
            BrushPreset.TEXTURE_CHARCOAL,
            BrushPreset.TEXTURE_WATERCOLOR,
            BrushPreset.TEXTURE_MARKER,
        )

        const val DEFAULT_NAME = "AI Brush"
        const val DEFAULT_TOOL = "pen"
        const val DEFAULT_WIDTH = 4f
    }
}

/**
 * Parses + validates a `DESIGN_BRUSH` reply into a [BrushSpec]. Shares the
 * JSON-extraction tolerance of [EditOpsParser] (fenced or bare object) and
 * applies the same "never trust the model" discipline: out-of-range numbers are
 * clamped, unknown enum strings fall back to safe defaults, and a structurally
 * broken reply fails with a [Result].
 */
object BrushSpecParser {

    /**
     * Parse [raw] into a validated [BrushSpec], or a failure if no usable JSON
     * object is present. Note: an invalid *field* never fails the parse — it is
     * clamped / defaulted — so a well-formed reply with a goofy width still
     * yields a usable brush, exactly like `edit-ops` drops bad ops rather than
     * failing the whole document.
     */
    fun parse(raw: String): Result<BrushSpec> {
        if (raw.isBlank()) return Result.failure(IllegalArgumentException("empty reply"))
        val json = EditOpsParser.extractJson(raw)
            ?: return Result.failure(IllegalArgumentException("no JSON block found in reply"))
        return try {
            val root = JsonParser.parseString(json) as? JsonObject
                ?: return Result.failure(IllegalArgumentException("top-level JSON is not an object"))
            // Accept either a top-level brush object or a {"brush": {...}} wrapper.
            val brush = (root.get("brush") as? JsonObject) ?: root
            Result.success(fromObject(brush))
        } catch (t: Throwable) {
            Result.failure(IllegalArgumentException("malformed JSON: ${t.message}"))
        }
    }

    private fun fromObject(o: JsonObject): BrushSpec {
        val tool = str(o, "tool")?.lowercase(Locale.ROOT)
            ?.takeIf { it in BrushSpec.VALID_TOOLS } ?: BrushSpec.DEFAULT_TOOL
        val name = str(o, "name")?.trim()?.take(64)?.takeIf { it.isNotEmpty() }
            ?: BrushSpec.DEFAULT_NAME
        val color = parseColor(str(o, "color"))
        val width = num(o, "width", "baseWidthPx", "size")
            ?.coerceIn(BrushSpec.MIN_WIDTH_PX, BrushSpec.MAX_WIDTH_PX) ?: BrushSpec.DEFAULT_WIDTH
        val opacity = num(o, "opacity")?.coerceIn(0f, 1f) ?: 1f
        val taperStart = num(o, "taperStart", "taper_start")?.coerceIn(0f, 1f) ?: 0f
        val taperEnd = num(o, "taperEnd", "taper_end")?.coerceIn(0f, 1f) ?: 0f
        val jitter = num(o, "jitter")?.coerceIn(0f, 1f) ?: 0f
        val curve = str(o, "pressureCurve", "pressure_curve", "pressureCurveId")
            ?.uppercase(Locale.ROOT)?.takeIf { it in BrushSpec.VALID_CURVES }
            ?: BrushPreset.CURVE_LINEAR
        val texture = str(o, "texture", "textureId")
            ?.lowercase(Locale.ROOT)?.takeIf { it in BrushSpec.VALID_TEXTURES }
            ?: BrushPreset.TEXTURE_SMOOTH
        // taperStart + taperEnd can't consume more than the whole stroke.
        val (ts, te) = clampTaper(taperStart, taperEnd)
        return BrushSpec(
            name = name,
            tool = tool,
            colorArgb = color,
            baseWidthPx = width,
            opacity = opacity,
            taperStart = ts,
            taperEnd = te,
            jitter = jitter,
            pressureCurveId = curve,
            textureId = texture,
        )
    }

    /** Two fades can't overlap; scale them down proportionally if they'd exceed 1. */
    private fun clampTaper(start: Float, end: Float): Pair<Float, Float> {
        val sum = start + end
        if (sum <= 1f) return start to end
        return (start / sum) to (end / sum)
    }

    private fun str(o: JsonObject, vararg keys: String): String? {
        for (k in keys) {
            val el = o.get(k) ?: continue
            if (el.isJsonPrimitive) return el.asString
        }
        return null
    }

    private fun num(o: JsonObject, vararg keys: String): Float? {
        for (k in keys) {
            val el = o.get(k) ?: continue
            if (el.isJsonPrimitive) {
                runCatching { return el.asFloat }
            }
        }
        return null
    }

    /**
     * Parse a `#RRGGBB` / `#AARRGGBB` (or bare-hex) colour into an opaque-by
     * default ARGB int. Opacity is carried separately on [BrushSpec.opacity]
     * (folded into alpha at paint time), so a 6-digit hex defaults to full alpha.
     * Falls back to opaque black on anything unparseable.
     */
    internal fun parseColor(hex: String?): Int {
        val h = hex?.trim()?.removePrefix("#") ?: return 0xFF000000.toInt()
        return try {
            when (h.length) {
                6 -> (0xFF000000.toInt()) or (h.toLong(16).toInt() and 0x00FFFFFF)
                8 -> h.toLong(16).toInt()
                else -> 0xFF000000.toInt()
            }
        } catch (_: NumberFormatException) {
            0xFF000000.toInt()
        }
    }
}
