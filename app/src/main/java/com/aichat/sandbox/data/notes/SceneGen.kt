package com.aichat.sandbox.data.notes

/**
 * Phase 8 — prompt-to-vector **scene** generation.
 *
 * A scene broadens the single-icon GENERATE path ([AskRequest.generate]) into a
 * compact, *editable* multi-object drawing: the model authors several grouped
 * objects with `add_path` / `add_shape` ops (each op carrying a [EditOp.AddPath.group]
 * label), the applier stamps shared `groupId`s, and the whole group is fit into a
 * chosen target rect so it always lands on-canvas. Everything stays a previewable
 * [com.aichat.sandbox.ui.screens.notes.PendingEdit] — no raster output.
 *
 * This file holds the small, JVM-pure pieces (enums + the object-count cap) so
 * they're testable without Android or the view model.
 */

/**
 * Requested scene density. Drives a one-line prompt hint and the per-request
 * object cap ([maxObjects], always ≤ [SceneGen.MAX_SCENE_OBJECTS]). Surfaced as
 * "Simple / Detailed" chips in the scene panel.
 */
enum class SceneComplexity(
    val label: String,
    val promptHint: String,
    val maxObjects: Int,
) {
    SIMPLE(
        label = "Simple",
        promptHint = "Keep it simple: a few bold, clearly separated objects with minimal detail.",
        maxObjects = 6,
    ),
    DETAILED(
        label = "Detailed",
        promptHint = "Add more objects and finer detail, but keep each one legible and uncluttered.",
        maxObjects = 12,
    );

    companion object {
        val DEFAULT: SceneComplexity = SIMPLE
    }
}

/**
 * Where a generated scene lands. The view model resolves each to a world-space
 * target rect (then insets it for margin); the authored geometry is uniformly
 * fit into that rect so it's always bounded and visible.
 */
enum class ScenePlacement(val label: String) {
    /** The currently visible area of the canvas. */
    VIEWPORT("Visible area"),
    /** The active frame / icon artboard. */
    FRAME("Frame"),
    /** The bounds of whatever is already drawn (or a default box when empty). */
    CONTENT("Fit content");

    companion object {
        val DEFAULT: ScenePlacement = VIEWPORT
    }
}

object SceneGen {

    /**
     * Hard ceiling on authored objects in one scene, regardless of complexity.
     * Keeps a runaway reply compact (and the preview legible); extras are moved
     * to `rejected` by [capSceneAddOps].
     */
    const val MAX_SCENE_OBJECTS: Int = 12

    /**
     * Square scene artboard edge (world units) the model is told to author
     * against. The result is fit into the real placement rect afterwards, so
     * this only sets the model's internal coordinate scale.
     */
    const val SCENE_ARTBOARD_WORLD: Float = 1024f

    /**
     * Fraction of the placement rect left as margin on each side, so the scene
     * doesn't butt against the viewport / frame edge.
     */
    const val SCENE_PADDING_FRACTION: Float = 0.08f

    /**
     * Cap the number of *authoring* (`add_path` / `add_shape`) ops in [doc] to
     * [max], preserving document order. Ops beyond the cap are moved into
     * `rejected` (with a reason) rather than silently dropped, so a runaway
     * scene stays bounded but the user can still see what was trimmed. Non-add
     * ops (and the cap being non-positive → no cap) pass through untouched.
     */
    fun capSceneAddOps(doc: EditOpsDoc, max: Int = MAX_SCENE_OBJECTS): EditOpsDoc {
        if (max <= 0) return doc
        var keptAdds = 0
        val keptOps = ArrayList<EditOp>(doc.ops.size)
        val trimmed = ArrayList<EditOpsDoc.RejectedOp>()
        for (op in doc.ops) {
            val isAdd = op is EditOp.AddPath || op is EditOp.AddShape
            if (isAdd) {
                if (keptAdds < max) {
                    keptOps += op
                    keptAdds++
                } else {
                    trimmed += EditOpsDoc.RejectedOp(
                        raw = op.toString(),
                        reason = "scene object cap ($max) exceeded",
                    )
                }
            } else {
                keptOps += op
            }
        }
        if (trimmed.isEmpty()) return doc
        return doc.copy(ops = keptOps, rejected = doc.rejected + trimmed)
    }

    /**
     * Shrink [rect] (`[minX, minY, maxX, maxY]`) toward its centre by [fraction]
     * on each side, leaving margin around a placed scene. A degenerate or null
     * rect is returned unchanged; an over-large fraction is clamped so the rect
     * never inverts.
     */
    fun insetRect(rect: FloatArray?, fraction: Float = SCENE_PADDING_FRACTION): FloatArray? {
        if (rect == null || rect.size < 4) return rect
        val w = rect[2] - rect[0]
        val h = rect[3] - rect[1]
        if (w <= 0f || h <= 0f) return rect
        val f = fraction.coerceIn(0f, 0.45f)
        val dx = w * f
        val dy = h * f
        return floatArrayOf(rect[0] + dx, rect[1] + dy, rect[2] - dx, rect[3] - dy)
    }
}
