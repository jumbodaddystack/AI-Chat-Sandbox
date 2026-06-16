package com.aichat.sandbox.data.notes

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Phase 2 — **local colour-theory fallback** for the palette assistant.
 *
 * Pure JVM (no `android.graphics.Color`, which isn't mocked in unit tests):
 * all conversions are hand-rolled HSV maths on plain ints/floats. Given the
 * colours already on the canvas it derives a seed hue and rotates/tints it
 * into a small cohesive palette so the feature still works with no model
 * configured or reachable.
 *
 * The same helper also proposes a deterministic per-colour [assignments] map
 * (existing distinct colour → harmonised swatch) so "Preview recolor" has
 * something to apply even when the model returned swatches only.
 */
object ColorHarmony {

    /** A calm default seed (muted blue) when the canvas has no usable colour. */
    private const val DEFAULT_SEED: Int = 0xFF3F6FB3.toInt()

    /**
     * Build a [PaletteSuggestion] for [scheme] from the [canvasColors] currently
     * in scope. Always returns [PaletteSuggestion.MIN_SWATCHES]..[PaletteSuggestion.MAX_SWATCHES]
     * distinct opaque swatches plus a deterministic assignment plan.
     */
    fun suggest(scheme: PaletteScheme, canvasColors: List<Int>): PaletteSuggestion {
        val opaque = canvasColors.filter { !isTransparent(it) }.map { opaque(it) }
        val seed = seedFrom(opaque)
        val swatches = generate(scheme, seed)
        return PaletteSuggestion(
            schemeName = scheme.label,
            swatches = swatches,
            rationale = rationaleFor(scheme),
            assignments = emptyMap(),
        )
    }

    /**
     * Pick a seed colour from [colors]: the most frequent one, ties broken by
     * first appearance. Falls back to [DEFAULT_SEED] when there's nothing usable.
     */
    fun seedFrom(colors: List<Int>): Int {
        if (colors.isEmpty()) return DEFAULT_SEED
        val counts = LinkedHashMap<Int, Int>()
        for (c in colors) counts[c] = (counts[c] ?: 0) + 1
        return counts.entries.maxByOrNull { it.value }?.key ?: DEFAULT_SEED
    }

    /**
     * Produce 5 cohesive swatches for [scheme] from [seed]. Always 5 so the
     * chip row has a consistent width; de-duplicated and clamped to the
     * 3..6 contract by [dedupeAndClamp].
     */
    fun generate(scheme: PaletteScheme, seed: Int): List<Int> {
        val hsv = rgbToHsv(seed)
        val h = hsv[0]
        // Floor saturation/value so a near-black or near-white seed still yields
        // a visibly coloured palette rather than five identical greys.
        val s = hsv[1].coerceIn(0.35f, 1f)
        val v = hsv[2].coerceIn(0.45f, 1f)
        val raw = when (scheme) {
            PaletteScheme.ANALOGOUS -> listOf(
                hsvToRgb(h - 60f, s, v),
                hsvToRgb(h - 30f, s, v),
                hsvToRgb(h, s, v),
                hsvToRgb(h + 30f, s, v),
                hsvToRgb(h + 60f, s, v),
            )
            PaletteScheme.COMPLEMENTARY -> listOf(
                hsvToRgb(h, s, v),
                hsvToRgb(h, s * 0.55f, (v * 1.15f).coerceAtMost(1f)),
                hsvToRgb(h, s, v * 0.7f),
                hsvToRgb(h + 180f, s, v),
                hsvToRgb(h + 180f, s * 0.6f, (v * 1.1f).coerceAtMost(1f)),
            )
            PaletteScheme.TRIADIC -> listOf(
                hsvToRgb(h, s, v),
                hsvToRgb(h, s * 0.6f, (v * 1.12f).coerceAtMost(1f)),
                hsvToRgb(h + 120f, s, v),
                hsvToRgb(h + 240f, s, v),
                hsvToRgb(h + 120f, s, v * 0.72f),
            )
            PaletteScheme.MONOCHROMATIC -> listOf(
                hsvToRgb(h, s, v * 0.45f),
                hsvToRgb(h, s, v * 0.7f),
                hsvToRgb(h, s, v),
                hsvToRgb(h, s * 0.7f, (v * 1.12f).coerceAtMost(1f)),
                hsvToRgb(h, s * 0.45f, (v * 1.25f).coerceAtMost(1f)),
            )
        }
        return dedupeAndClamp(raw)
    }

    /**
     * Deterministic per-colour plan: map each distinct colour in [currentColors]
     * to a [swatches] entry, preserving relative light/dark order (darker
     * originals land on darker swatches). Returns a `currentArgb -> newArgb` map;
     * colours that would map to themselves are still included (the op-builder
     * drops no-ops). Empty when there's nothing to colour.
     */
    fun assignments(currentColors: List<Int>, swatches: List<Int>): Map<Int, Int> {
        if (swatches.isEmpty()) return emptyMap()
        val distinct = currentColors.filter { !isTransparent(it) }.map { opaque(it) }.distinct()
        if (distinct.isEmpty()) return emptyMap()
        val sortedSwatches = swatches.sortedBy { luminance(it) }
        val sortedDistinct = distinct.sortedBy { luminance(it) }
        val out = LinkedHashMap<Int, Int>(distinct.size)
        val denom = (sortedDistinct.size - 1).coerceAtLeast(1)
        sortedDistinct.forEachIndexed { i, c ->
            val idx = if (sortedDistinct.size == 1) {
                sortedSwatches.lastIndex // a single colour takes the lightest accent
            } else {
                (i.toFloat() * (sortedSwatches.size - 1) / denom).roundToInt()
                    .coerceIn(0, sortedSwatches.lastIndex)
            }
            out[c] = sortedSwatches[idx]
        }
        return out
    }

    // ── colour maths (pure) ──────────────────────────────────────────────

    /** Relative luminance 0..1 (Rec. 601 weights) of an ARGB colour. */
    fun luminance(argb: Int): Float {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /** Force full alpha so swatches and recolours are always visible. */
    fun opaque(argb: Int): Int = (argb and 0x00FFFFFF) or (0xFF shl 24)

    private fun isTransparent(argb: Int): Boolean = (argb ushr 24) == 0

    /** ARGB → `[hueDegrees, saturation, value]`, hue in 0..360. */
    fun rgbToHsv(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val h = when {
            delta < 1e-6f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        val hue = ((h % 360f) + 360f) % 360f
        val sat = if (max <= 0f) 0f else delta / max
        return floatArrayOf(hue, sat, max)
    }

    /** `[hueDegrees, saturation, value]` → opaque ARGB. Hue wraps; s/v clamp. */
    fun hsvToRgb(hueDegrees: Float, saturation: Float, value: Float): Int {
        val h = ((hueDegrees % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - abs(((h / 60f) % 2f) - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Drop duplicates (keeping order) and clamp to the 3..6 swatch contract. */
    private fun dedupeAndClamp(colors: List<Int>): List<Int> {
        val unique = LinkedHashSet<Int>()
        for (c in colors) unique.add(opaque(c))
        val list = unique.toMutableList()
        // Pad a degenerate (all-identical) palette by nudging value so we never
        // fall below the 3-swatch minimum.
        var nudge = 1
        while (list.size < PaletteSuggestion.MIN_SWATCHES && list.isNotEmpty()) {
            val base = rgbToHsv(list[0])
            val candidate = hsvToRgb(base[0], base[1], (base[2] * (1f - 0.18f * nudge)).coerceIn(0f, 1f))
            if (unique.add(candidate)) list.add(candidate)
            nudge++
            if (nudge > 6) break
        }
        return list.take(PaletteSuggestion.MAX_SWATCHES)
    }

    private fun rationaleFor(scheme: PaletteScheme): String = when (scheme) {
        PaletteScheme.ANALOGOUS ->
            "Analogous colours sit next to each other on the wheel, so they feel calm and cohesive."
        PaletteScheme.COMPLEMENTARY ->
            "A base hue paired with its opposite gives strong, balanced contrast for accents."
        PaletteScheme.TRIADIC ->
            "Three evenly spaced hues stay vibrant while keeping a balanced overall feel."
        PaletteScheme.MONOCHROMATIC ->
            "One hue in lighter and darker steps reads as clean and unified."
    }
}
