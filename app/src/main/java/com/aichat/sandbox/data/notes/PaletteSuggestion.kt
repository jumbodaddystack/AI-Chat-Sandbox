package com.aichat.sandbox.data.notes

/**
 * Phase 2 (AI art-assist) — **palette & colour-harmony assistant** contract.
 *
 * A [PaletteSuggestion] is the structured, non-mutating result the palette
 * assistant returns: a named scheme, 3–6 cohesive [swatches] (opaque ARGB),
 * a beginner-friendly [rationale], and an *optional* set of per-item colour
 * [assignments] the model proposed. The suggestion never touches the canvas on
 * its own — surfacing swatches is purely informational (Adoption principle 3).
 *
 * Applying it is a separate, previewable step: [PaletteRecolor] turns the
 * swatches / assignments into `recolor` edit-ops that stage through the same
 * `PendingEdit` → `AiEditDiffOverlay` accept/reject surface as every other AI
 * edit (Adoption principle 2).
 *
 * Two producers fill this in:
 *  - [ColorHarmony] — a local colour-theory fallback that works with no model.
 *  - [PaletteParser] — validates a model's JSON reply (AI-assigned [assignments]
 *    reference items by short id from the [VectorCanvasJson] block).
 */
data class PaletteSuggestion(
    /** Human label for the scheme, e.g. "Complementary" or a model phrase. */
    val schemeName: String,
    /** 3–6 cohesive colours as opaque ARGB ints. */
    val swatches: List<Int>,
    /** One or two plain-language sentences explaining the choice. */
    val rationale: String,
    /**
     * Optional per-item colour plan keyed by the item's *short id* (`s_001`,
     * `h_001`, …) from the serialized canvas. Empty when the producer only
     * suggested swatches (the local fallback, or a model that skipped the
     * assignment block) — the applier then distributes swatches itself.
     */
    val assignments: Map<String, Int> = emptyMap(),
) {
    companion object {
        const val SCHEMA: Int = 1

        /** Swatch-count bounds the MVP renders (3–6 chips). */
        const val MIN_SWATCHES: Int = 3
        const val MAX_SWATCHES: Int = 6
    }
}

/**
 * The colour-harmony schemes the assistant can produce. [label] is the chip
 * text; [aiHint] is woven into the model prompt so an AI reply biases toward
 * the same family the user picked. Order matches the chip row.
 */
enum class PaletteScheme(val label: String, val aiHint: String) {
    ANALOGOUS("Analogous", "analogous (neighbouring hues on the colour wheel)"),
    COMPLEMENTARY("Complementary", "complementary (a base hue plus its opposite)"),
    TRIADIC("Triadic", "triadic (three hues evenly spaced around the wheel)"),
    MONOCHROMATIC("Monochromatic", "monochromatic (one hue in varied tints and shades)");

    companion object {
        /** The scheme the panel opens on. */
        val DEFAULT: PaletteScheme = COMPLEMENTARY
    }
}

/** Where a [PaletteSuggestion] came from — drives a small UI source label. */
enum class PaletteSource { LOCAL, AI }
