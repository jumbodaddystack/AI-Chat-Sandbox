package com.aichat.sandbox.data.notes

/**
 * Phase 19 (Stage 4) — deterministic geometric normalization of model-authored
 * icon geometry, applied after parsing (and after self-refine) and before the
 * edit is staged. Conservative by design: it only *tidies* what is already
 * nearly regular and leaves deliberate variation alone, mirroring the
 * philosophy of [com.aichat.sandbox.data.ink.ConstraintSnap].
 *
 * ## Why width unification
 * Inconsistent stroke weight is the most visible give-away of a "blindly"
 * authored icon — the model emits a handful of `add_path` / `add_shape` ops
 * with slightly different widths (2.0, 2.4, 1.8…) that the eye reads as sloppy.
 * Real icon sets use **one** outline weight. This pass detects authored outline
 * widths that are *already close* (within [Config.maxWidthRatio]) and snaps
 * them all to their median, producing a crisp, uniform weight. When the spread
 * is large it assumes the contrast is intentional (e.g. a bold frame around a
 * thin glyph) and makes no change.
 *
 * ## Scope & safety
 * - Operates only on **authored** ops ([EditOp.AddPath] / [EditOp.AddShape]) —
 *   never on edits to the user's own strokes.
 * - Pure data transform over the [EditOpsDoc]; no graphics, fully JVM-testable.
 * - Stays inside the canonical edit-ops pipeline (no ink, no codec changes).
 *
 * Multi-part alignment / symmetry and within-path anchor snapping are the
 * riskier, finickier sub-levers (they fight the model's intent more readily and
 * need per-item world bounds the authored ops don't carry as addressable ids);
 * they are intentionally left out here. [com.aichat.sandbox.data.ink.ConstraintSnap]
 * already covers item-level alignment for *placed* geometry on the canvas.
 */
object AuthoredNormalizer {

    /** Default authored outline width, mirroring `EditPreviewController.DEFAULT_AUTHORED_WIDTH`. */
    private const val DEFAULT_AUTHORED_WIDTH: Float = 2f

    /**
     * @property maxWidthRatio largest max/min authored-width ratio still treated
     *   as "meant to be one weight". Above it, the contrast is assumed
     *   deliberate and left untouched.
     * @property minOutlines fewest authored outline ops worth unifying.
     */
    data class Config(
        val maxWidthRatio: Float = 1.6f,
        val minOutlines: Int = 2,
    )

    /**
     * Return [doc] with authored outline widths unified to their median when
     * they are already close, otherwise [doc] unchanged.
     */
    fun unifyAuthoredStrokeWidths(doc: EditOpsDoc, config: Config = Config()): EditOpsDoc {
        // Effective widths of every authored op (null → the applier's default).
        val effective = ArrayList<Float>()
        for (op in doc.ops) {
            when (op) {
                is EditOp.AddPath -> effective += (op.width ?: DEFAULT_AUTHORED_WIDTH)
                is EditOp.AddShape -> effective += (op.width ?: DEFAULT_AUTHORED_WIDTH)
                else -> {}
            }
        }
        if (effective.size < config.minOutlines) return doc
        val mn = effective.min()
        val mx = effective.max()
        if (mn <= 0f) return doc
        // Already uniform → nothing to do; too varied → assume intentional.
        if (mx == mn) return doc
        if (mx / mn > config.maxWidthRatio) return doc

        val target = median(effective)
        var changed = false
        val ops = doc.ops.map { op ->
            when (op) {
                is EditOp.AddPath ->
                    if (op.width != target) { changed = true; op.copy(width = target) } else op
                is EditOp.AddShape ->
                    if (op.width != target) { changed = true; op.copy(width = target) } else op
                else -> op
            }
        }
        return if (changed) doc.copy(ops = ops) else doc
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) * 0.5f
    }
}
