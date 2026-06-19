package com.aichat.sandbox.data.notes

/**
 * Phase 7 (AI art-assist) — **named style preset restyling** contract.
 *
 * A [StylePreset] is a small, curated recipe for restyling an *existing*
 * selection into a recognisable visual look ("Flat icon", "Line art",
 * "Isometric", "Sticker"). Unlike GENERATE (which authors new icon geometry
 * from a reference) or the local `StyleTransfer` clipboard (which copies the
 * exact style of one item onto another), a preset asks the model to re-dress
 * the geometry the user already drew — it never invents a new subject.
 *
 * Each preset carries:
 *  - [promptText] — a plain-language description of the target look, and
 *  - [constraints] — short bullet rules that keep the model honest.
 *
 * [buildInstruction] composes those into the user-message body sent in
 * [AskMode.RESTYLE]. The reply is validated by [RestyleParser], which keeps
 * only the non-additive op subset ([isRestyleOp]) so a restyle can never add
 * new subject matter, delete, regroup, relayer, or move/scale items.
 */
data class StylePreset(
    /** Stable id used by the UI chips and to resolve a preset by tap. */
    val id: String,
    /** Short, human label, e.g. "Flat icon". */
    val displayName: String,
    /** One-line UI subtitle describing the look. */
    val tagline: String,
    /** Plain-language description of the target style for the model. */
    val promptText: String,
    /** Short bullet constraints that keep the restyle on-look and on-subject. */
    val constraints: List<String>,
) {
    /**
     * Compose the restyle instruction sent to the model as the user-message
     * body. Pure (no Android deps) so a unit test can pin the wire format. The
     * style description leads, the curated constraints follow, and a fixed
     * subject-preservation guardrail closes it out (also pinned in
     * [RestyleParser.SYSTEM_MESSAGE] as defence in depth).
     */
    fun buildInstruction(): String = buildString {
        append("Restyle the existing items into a \"")
        append(displayName)
        append("\" look. ")
        append(promptText)
        append("\n\nConstraints:")
        constraints.forEach { c ->
            append("\n- ")
            append(c)
        }
        append("\n- ")
        append(SUBJECT_GUARD)
        append("\n- ")
        append(NO_ADD_GUARD)
    }

    companion object {
        /** Closing guardrail: keep the drawing the same subject. */
        const val SUBJECT_GUARD: String =
            "Keep the same subject and overall shapes recognizable — only restyle what is already there."

        /** Closing guardrail: never add or remove subject matter. */
        const val NO_ADD_GUARD: String =
            "Do not add new objects or remove existing ones."

        /**
         * The op subset a named restyle may use. Restricted to **non-additive,
         * non-moving** ops so the subject's geometry stays put and recognisable:
         * [EditOp.Recolor], [EditOp.Restyle] (width/opacity), [EditOp.Smooth],
         * [EditOp.Simplify], and [EditOp.ReplaceWithShape] (cleaning a wobbly
         * stroke into a crisp primitive). Notably this excludes
         * [EditOp.Transform] (would move/scale the subject) as well as every
         * additive / destructive / structural op.
         */
        fun isRestyleOp(op: EditOp): Boolean = when (op) {
            is EditOp.Recolor,
            is EditOp.Restyle,
            is EditOp.Smooth,
            is EditOp.Simplify,
            is EditOp.ReplaceWithShape,
            is EditOp.ReplaceWithPath -> true
            is EditOp.Transform,
            is EditOp.Delete,
            is EditOp.AddPath,
            is EditOp.AddShape,
            is EditOp.SetLayer,
            is EditOp.MergePaths,
            is EditOp.Group -> false
        }
    }
}

/**
 * Phase 7 — the curated catalog of named style presets surfaced as chips in the
 * "Restyle" panel. Intentionally small and opinionated; extend it here rather
 * than scattering style prompts across the UI.
 */
object StylePresetCatalog {

    val FLAT_ICON: StylePreset = StylePreset(
        id = "flat_icon",
        displayName = "Flat icon",
        tagline = "Solid fills, minimal outlines",
        promptText = "Make it read like a modern flat icon: bold, simple silhouettes " +
            "with solid flat colour fills and little or no outline.",
        constraints = listOf(
            "Use solid flat fills; drop gradients and shading.",
            "Keep a small, cohesive palette.",
            "Smooth out wobble and clean wobbly strokes into crisp shapes where it helps.",
        ),
    )

    val LINE_ART: StylePreset = StylePreset(
        id = "line_art",
        displayName = "Line art",
        tagline = "Clean uniform outlines, no fill",
        promptText = "Make it read like clean line art: even-weight outlines and no " +
            "fills, like a single-pass pen drawing.",
        constraints = listOf(
            "Use a single, uniform stroke width across the items.",
            "Remove fills; keep outlines only.",
            "Keep it monochrome.",
            "Straighten and smooth wobbly strokes for a confident line.",
        ),
    )

    val ISOMETRIC: StylePreset = StylePreset(
        id = "isometric",
        displayName = "Isometric",
        tagline = "Crisp, technical, faceted look",
        promptText = "Give it a crisp isometric / technical look: clean confident edges " +
            "and flat faceted colour that reads like a 3D-ish diagram.",
        constraints = listOf(
            "Use clean, even outlines and flat faceted fills.",
            "Avoid soft gradients; prefer a few distinct flat tones for depth.",
            "Tidy wobbly strokes into straight, crisp edges.",
        ),
    )

    val STICKER: StylePreset = StylePreset(
        id = "sticker",
        displayName = "Sticker",
        tagline = "Thick die-cut outline, bright fills",
        promptText = "Make it look like a die-cut sticker: a thick, bold outline around " +
            "bright, cheerful flat fills.",
        constraints = listOf(
            "Use a thick, uniform outline weight.",
            "Use bright, saturated flat fills.",
            "Clean up wobble so the outline reads as a confident die-cut edge.",
        ),
    )

    /** All presets, in display order. */
    val PRESETS: List<StylePreset> = listOf(FLAT_ICON, LINE_ART, ISOMETRIC, STICKER)

    /** Resolve a preset by its [StylePreset.id]; null when unknown. */
    fun byId(id: String): StylePreset? = PRESETS.firstOrNull { it.id == id }
}
