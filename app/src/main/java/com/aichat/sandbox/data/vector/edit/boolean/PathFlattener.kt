package com.aichat.sandbox.data.vector.edit.boolean

import com.aichat.sandbox.data.vector.VectorPathSampler
import com.aichat.sandbox.data.vector.VectorPathSimplifier
import com.aichat.sandbox.data.vector.VectorPoint
import com.aichat.sandbox.data.vector.VectorStyle
import com.aichat.sandbox.data.vector.edit.EditSubpath
import com.aichat.sandbox.data.vector.edit.EditablePath
import com.aichat.sandbox.data.vector.edit.EditablePathSerializer
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Phase 2, stage 1 — flatten the all-cubic editable model into [PolyShape]s the
 * clipper can chew on. We do **not** reimplement curve flattening: each subpath is
 * serialized back to `PathCommand`s with the Phase 1 [EditablePathSerializer] and
 * sampled with the existing [VectorPathSampler], then de-duplicated with
 * [VectorPathSimplifier].
 *
 * The sampler takes a fixed step count rather than a flatness tolerance, so we
 * translate a world-space [tolerance] into a per-subpath step count from the
 * control-polygon length (denser/longer curves get more samples), clamped to a
 * sane range. Open subpaths are closed implicitly for area ops — boolean/offset
 * treat every contour as a region boundary.
 */
internal object PathFlattener {

    /** Step-count clamp for the sampler (per curve command). */
    private const val MIN_STEPS = 6
    private const val MAX_STEPS = 64

    fun flatten(path: EditablePath, tolerance: Float): PolyShape {
        val rings = path.subpaths.mapNotNull { flattenSubpath(it, tolerance) }
        return PolyShape(rings, FillRuleResolver.ruleOf(path.style))
    }

    fun flattenSubpath(sub: EditSubpath, tolerance: Float): Ring? {
        if (sub.anchors.size < 2) return null
        // Re-serialize just this subpath (force-closed for area sampling) and sample
        // through the shared pipeline.
        val single = EditablePath(
            pathId = "flatten",
            subpaths = listOf(sub.copy(closed = true)),
            style = VectorStyle(),
        )
        val commands = EditablePathSerializer.toCommands(single)
        val steps = estimateSteps(sub, tolerance)
        val sampled = VectorPathSampler.sample(commands, steps)

        var pts = VectorPathSimplifier.removeConsecutiveDuplicates(sampled.points)
        // The sampler re-emits the start vertex when closing; drop a trailing point
        // coincident with the first so the ring carries an implicit closing edge.
        if (pts.size >= 2 && coincident(pts.first(), pts.last())) {
            pts = pts.subList(0, pts.size - 1).toList()
        }
        if (pts.size < 3) return null
        return Ring(pts)
    }

    /**
     * Flatten a subpath as a stroke **centerline** (not an area): the open/closed
     * flag is preserved, and a two-anchor straight line survives as a two-point
     * polyline (unlike [flattenSubpath], which force-closes for area ops).
     */
    fun flattenCenterline(sub: EditSubpath, tolerance: Float): Ring? {
        if (sub.anchors.size < 2) return null
        val single = EditablePath(
            pathId = "centerline",
            subpaths = listOf(sub),
            style = VectorStyle(),
        )
        val commands = EditablePathSerializer.toCommands(single)
        val steps = estimateSteps(sub, tolerance)
        val sampled = VectorPathSampler.sample(commands, steps)
        var pts = VectorPathSimplifier.removeConsecutiveDuplicates(sampled.points)
        if (sub.closed && pts.size >= 2 && coincident(pts.first(), pts.last())) {
            pts = pts.subList(0, pts.size - 1).toList()
        }
        if (pts.size < 2) return null
        return Ring(pts)
    }

    /**
     * Translate a world-space flatness [tolerance] into a sampler step count from
     * the subpath's worst-case control-polygon segment: more curvature / a longer
     * control polygon ⇒ more steps. Straight runs land at [MIN_STEPS] (harmless,
     * lines aren't subdivided by the sampler anyway).
     */
    private fun estimateSteps(sub: EditSubpath, tolerance: Float): Int {
        val tol = if (tolerance > 0f) tolerance else 0.25f
        var maxControl = 0f
        val anchors = sub.anchors
        val n = anchors.size
        val span = if (sub.closed) n else n - 1
        for (i in 0 until span) {
            val a = anchors[i]
            val b = anchors[(i + 1) % n]
            val out = a.outHandle
            val inc = b.inHandle
            if (out == null && inc == null) continue // straight segment
            val c1x = out?.x ?: a.x; val c1y = out?.y ?: a.y
            val c2x = inc?.x ?: b.x; val c2y = inc?.y ?: b.y
            val len = hypot(c1x - a.x, c1y - a.y) +
                hypot(c2x - c1x, c2y - c1y) +
                hypot(b.x - c2x, b.y - c2y)
            if (len > maxControl) maxControl = len
        }
        if (maxControl <= 0f) return MIN_STEPS
        return ceil(maxControl / tol).toInt().coerceIn(MIN_STEPS, MAX_STEPS)
    }

    private fun coincident(a: VectorPoint, b: VectorPoint): Boolean =
        hypot(a.x - b.x, a.y - b.y) <= 1e-4f
}
