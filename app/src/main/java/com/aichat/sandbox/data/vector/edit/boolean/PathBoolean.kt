package com.aichat.sandbox.data.vector.edit.boolean

import com.aichat.sandbox.data.vector.VectorStyle
import com.aichat.sandbox.data.vector.edit.EditSubpath
import com.aichat.sandbox.data.vector.edit.EditablePath

/**
 * Phase 2, stage 5 — the public façade for shape algebra on the Phase 1 editable
 * model. Everything below composes the internal flatten → clip/outline/offset →
 * refit stages and returns a fresh [EditablePath] that re-enters the editor,
 * undo/redo, version history, and the exporters for free.
 *
 * The pipeline is deliberately **flatten → operate → refit**: cubics are sampled to
 * dense polygons, the algebra runs on polygons (robust), and the result is refit to
 * cubic anchors. Fidelity is a tolerance knob ([Options]); the lossiness is bounded
 * and documented rather than hidden.
 */
object PathBoolean {

    /** Which boolean combination to perform; mirrors [BoolOp] at the public edge. */
    enum class Op { UNION, SUBTRACT, INTERSECT, EXCLUDE }

    data class Options(
        /** Curve→polygon flatness, in world (viewport) units. */
        val flattenTolerance: Float = 0.25f,
        /** Max polygon→cubic refit error, in world units. */
        val refitMaxError: Float = 0.5f,
        /** Turn angle (degrees) above which a refit vertex becomes a corner. */
        val cornerAngleDeg: Float = 30f,
    )

    /**
     * Combine [paths] (≥2, in op-defined order) under [op] into a single result
     * path with id [newPathId]. Returns the lone input unchanged for a single path,
     * or null if the inputs reduce to nothing (e.g. an empty intersection). The
     * result is a pure fill: it inherits the **subject** (first) path's style but
     * with stroke cleared and a canonical `fillType`.
     */
    fun combine(
        paths: List<EditablePath>,
        op: Op,
        newPathId: String,
        opts: Options = Options(),
    ): EditablePath? {
        if (paths.isEmpty()) return null
        if (paths.size == 1) return paths.first()

        val shapes = paths.map { PathFlattener.flatten(it, opts.flattenTolerance) }
        val boolOp = op.toBoolOp()
        val result: PolyShape = when (op) {
            PathBoolean.Op.SUBTRACT -> {
                // subject − union(rest)
                val rest = shapes.drop(1).reduce { acc, s -> PolygonClipper.clip(acc, s, BoolOp.UNION) }
                PolygonClipper.clip(shapes.first(), rest, BoolOp.DIFFERENCE)
            }
            else -> shapes.reduce { acc, s -> PolygonClipper.clip(acc, s, boolOp) }
        }

        return shapeToPath(result, paths.first().style, newPathId, opts, fillOnly = true)
    }

    /**
     * Convert a stroked [path] (centerlines + `style.strokeWidth`) into a filled
     * outline. Returns null when the path has no positive stroke width.
     */
    fun outlineStroke(path: EditablePath, newPathId: String, opts: Options = Options()): EditablePath? {
        val width = path.style.strokeWidth ?: return null
        if (width <= 0f) return null

        val cap = StrokeOutliner.capOf(path.style.strokeLineCap)
        val join = StrokeOutliner.joinOf(path.style.strokeLineJoin)
        val miter = path.style.strokeMiterLimit ?: 4f

        var acc: PolyShape = PolyShape.EMPTY
        for (sub in path.subpaths) {
            val ring = PathFlattener.flattenCenterline(sub, opts.flattenTolerance) ?: continue
            val outlined = StrokeOutliner.outline(ring, sub.closed, width, cap, join, miter)
            acc = if (acc.isEmpty) outlined else PolygonClipper.clip(acc, outlined, BoolOp.UNION)
        }
        if (acc.isEmpty) return null
        return shapeToPath(acc, path.style, newPathId, opts, fillOnly = true)
    }

    /**
     * Grow ([delta] > 0) or shrink ([delta] < 0) the filled [path] by a signed
     * distance. Returns null when the shape is erased (over-shrink).
     */
    fun offset(path: EditablePath, delta: Float, newPathId: String, opts: Options = Options()): EditablePath? {
        if (delta == 0f) return path.copy(pathId = newPathId)
        val shape = PathFlattener.flatten(path, opts.flattenTolerance)
        if (shape.isEmpty) return null
        val join = StrokeOutliner.joinOf(path.style.strokeLineJoin)
        val result = PathOffset.offset(shape, delta, join)
        if (result.isEmpty) return null
        // Offset keeps the input style (it is still the same fill).
        return shapeToPath(result, path.style, newPathId, opts, fillOnly = false)
    }

    // ---- shared tail: PolyShape → EditablePath ----

    private fun shapeToPath(
        shape: PolyShape,
        baseStyle: VectorStyle,
        newPathId: String,
        opts: Options,
        fillOnly: Boolean,
    ): EditablePath? {
        val rings = shape.rings.filterNot { it.degenerate }
        if (rings.isEmpty()) return null
        val subpaths: List<EditSubpath> = rings.mapIndexed { i, ring ->
            CurveRefit.refit(ring, opts.refitMaxError, "$newPathId.s$i", opts.cornerAngleDeg)
        }.filter { it.anchors.size >= 2 }
        if (subpaths.isEmpty()) return null

        val style = if (fillOnly) {
            baseStyle.copy(
                strokeColor = null,
                strokeWidth = null,
                strokeAlpha = null,
                fillColor = baseStyle.fillColor ?: DEFAULT_FILL,
                fillType = FillRuleResolver.fillTypeFor(shape.fillRule),
            )
        } else {
            baseStyle.copy(fillType = FillRuleResolver.fillTypeFor(shape.fillRule))
        }
        return EditablePath(pathId = newPathId, subpaths = subpaths, style = style)
    }

    private fun Op.toBoolOp(): BoolOp = when (this) {
        PathBoolean.Op.UNION -> BoolOp.UNION
        PathBoolean.Op.SUBTRACT -> BoolOp.DIFFERENCE
        PathBoolean.Op.INTERSECT -> BoolOp.INTERSECT
        PathBoolean.Op.EXCLUDE -> BoolOp.XOR
    }

    /** Default fill for a result whose subject carried none (so it's visible). */
    const val DEFAULT_FILL = "#000000"
}
