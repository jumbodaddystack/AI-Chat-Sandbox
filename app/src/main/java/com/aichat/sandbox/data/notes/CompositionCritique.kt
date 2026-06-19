package com.aichat.sandbox.data.notes

/**
 * Phase 3 (AI art-assist) — **guided composition / layout critique** contract.
 *
 * A [CompositionCritique] is the structured, non-mutating result the critique
 * assistant returns when the user asks "how can I improve this?": a one-line
 * [summary], 3–5 beginner-friendly [suggestions], and optional [safetyNotes].
 * Like the palette assistant (Phase 2) the critique never touches the canvas on
 * its own — the cards are purely advisory (Adoption principle 3).
 *
 * Each [CritiqueSuggestion] *may* carry a small list of [CritiqueSuggestion.ops]
 * (an "optional edit-op" payload). When present and non-empty, the UI offers a
 * "Preview fix" button that stages those ops through the same
 * `PendingEdit` → `AiEditDiffOverlay` accept/reject surface as every other AI
 * edit (Adoption principle 2). A suggestion with no ops is *prose-only* and
 * still useful — the feature must be valuable even when no op is returned.
 *
 * Two safety properties are enforced by [CritiqueParser]:
 *  - ops are validated and id-filtered through [EditOpsParser], so invented or
 *    locked-layer targets are dropped, and
 *  - ops are restricted to a *safe, non-destructive* subset
 *    ([CritiqueSuggestion.isSafeOp]) — align/scale (`transform`), `recolor`,
 *    `restyle`, `simplify`, `smooth`, and `replace_with_shape`. Destructive or
 *    broad-structure ops (`delete`, `add_path`, `add_shape`, `set_layer`,
 *    `merge_paths`, `group`) are never accepted, so the model cannot silently
 *    apply sweeping layout changes.
 */
data class CompositionCritique(
    /** One short, encouraging sentence framing the overall read. */
    val summary: String,
    /** 1–5 concrete suggestions, in priority order. */
    val suggestions: List<CritiqueSuggestion>,
    /** Optional caveats ("I couldn't see the colours well", etc.). May be blank. */
    val safetyNotes: String = "",
) {
    companion object {
        const val SCHEMA: Int = 1

        /** Cards the MVP renders. The prompt asks for 3–5; we tolerate 1–5. */
        const val MIN_SUGGESTIONS: Int = 1
        const val MAX_SUGGESTIONS: Int = 5
    }
}

/**
 * One critique card: a plain-language [title], the design [principle] it draws
 * on, a beginner-friendly [why] reason, [confidence] / [effort] labels, and an
 * optional [ops] payload that — when non-empty — powers a previewable "Preview
 * fix" action.
 */
data class CritiqueSuggestion(
    /** Short, action-oriented label, e.g. "Even out the spacing". */
    val title: String,
    /** The design principle, e.g. "Visual balance" or "Whitespace". */
    val principle: String,
    /** One or two jargon-free sentences explaining the issue. */
    val why: String,
    val confidence: CritiqueConfidence,
    val effort: CritiqueEffort,
    /**
     * Optional, already-validated edit-ops that realise this suggestion. Empty
     * for a prose-only suggestion. Ops reference items by the short id from the
     * serialized [VectorCanvasJson]; the applier resolves them via the
     * critique's id map before previewing.
     */
    val ops: List<EditOp> = emptyList(),
) {
    /** True when this suggestion has a previewable fix. */
    val hasFix: Boolean get() = ops.isNotEmpty()

    companion object {
        /**
         * The non-destructive op subset a critique fix may use. Keeps fixes to
         * tidying existing geometry (move/scale/recolor/restyle/simplify/smooth,
         * or cleaning a wobbly stroke into a shape) and never lets the model
         * delete, add new geometry, regroup, or relayer items.
         */
        fun isSafeOp(op: EditOp): Boolean = when (op) {
            is EditOp.Transform,
            is EditOp.Recolor,
            is EditOp.Restyle,
            is EditOp.Simplify,
            is EditOp.Smooth,
            is EditOp.ReplaceWithShape,
            is EditOp.ReplaceWithPath -> true
            is EditOp.Delete,
            is EditOp.AddPath,
            is EditOp.AddShape,
            is EditOp.SetLayer,
            is EditOp.MergePaths,
            is EditOp.Group -> false
        }
    }
}

/** How sure the assistant is about a suggestion. [label] is the chip text. */
enum class CritiqueConfidence(val label: String) {
    LOW("Low confidence"),
    MEDIUM("Medium confidence"),
    HIGH("High confidence");

    companion object {
        val DEFAULT: CritiqueConfidence = MEDIUM

        /** Map a loose model string ("high", "med", …) onto a level. */
        fun fromString(raw: String?): CritiqueConfidence = when (raw?.trim()?.lowercase()) {
            "high", "strong", "very" -> HIGH
            "low", "weak", "maybe" -> LOW
            "medium", "med", "moderate" -> MEDIUM
            else -> DEFAULT
        }
    }
}

/** Roughly how much work a suggestion is to act on. [label] is the chip text. */
enum class CritiqueEffort(val label: String) {
    QUICK("Quick"),
    MODERATE("Some work"),
    INVOLVED("Bigger change");

    companion object {
        val DEFAULT: CritiqueEffort = MODERATE

        /** Map a loose model string ("quick", "easy", "hard", …) onto a level. */
        fun fromString(raw: String?): CritiqueEffort = when (raw?.trim()?.lowercase()) {
            "quick", "easy", "low", "small", "trivial" -> QUICK
            "involved", "hard", "high", "big", "large", "major" -> INVOLVED
            "moderate", "medium", "med", "some" -> MODERATE
            else -> DEFAULT
        }
    }
}
