package com.aichat.sandbox.data.vector

import kotlin.math.hypot

/**
 * Phase 5 (sub-feature 1) — bakes a variable-width stroke into a filled outline.
 *
 * A width-along-the-path profile can't be expressed by any single stroke
 * attribute in either target format, so export replaces a profiled stroke with a
 * closed fill whose boundary is the centerline offset left/right by
 * `width(t) / 2`. Pure and deterministic; reuses [VectorPathSampler] for
 * flattening. (Named distinctly from the Phase-2
 * `data/vector/edit/boolean/StrokeOutliner` — that one fuses constant-width
 * stroke pieces with the boolean clipper; this one offsets a single centerline by
 * a varying width for lossy export baking.)
 *
 * Caps are butt-modeled: the band closes straight across each endpoint. Round /
 * square cap rendering is a visual refinement deferred to a later pass.
 */
object VariableWidthOutliner {

    private const val EPS = 1e-4f

    /** Max centerline segment length before a point is inserted, so width varies smoothly. */
    private const val DENSIFY_STEP = 1f

    /**
     * Build the filled-outline commands for [commands] stroked with [profile].
     * [baseWidth] is the width used when the profile is empty. Returns an empty
     * list when the centerline degenerates to under two distinct points.
     */
    fun outline(
        commands: List<PathCommand>,
        profile: VariableWidthProfile,
        baseWidth: Float,
        curveSteps: Int = 24,
    ): List<PathCommand> {
        val sampled = VectorPathSampler.sample(commands, curveSteps)
        val deduped = VectorPathSimplifier.removeConsecutiveDuplicates(sampled.points)
        if (deduped.size < 2) return emptyList()
        // Insert points along long segments so the width profile is captured smoothly
        // (the sampler leaves straight LineTos un-subdivided).
        val pts = densify(deduped)

        val cum = FloatArray(pts.size)
        for (i in 1 until pts.size) {
            cum[i] = cum[i - 1] + hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        }
        val total = cum.last()
        if (total <= EPS) return emptyList()

        val left = ArrayList<VectorPoint>(pts.size)
        val right = ArrayList<VectorPoint>(pts.size)
        for (i in pts.indices) {
            val (nx, ny) = normalAt(pts, i)
            val halfW = profile.widthAt(cum[i] / total, baseWidth) / 2f
            left += VectorPoint(pts[i].x + nx * halfW, pts[i].y + ny * halfW)
            right += VectorPoint(pts[i].x - nx * halfW, pts[i].y - ny * halfW)
        }

        val out = ArrayList<PathCommand>(left.size + right.size + 2)
        out += PathCommand.MoveTo(left[0].x, left[0].y)
        for (i in 1 until left.size) out += PathCommand.LineTo(left[i].x, left[i].y)
        for (i in right.indices.reversed()) out += PathCommand.LineTo(right[i].x, right[i].y)
        out += PathCommand.Close()
        return out
    }

    /** Subdivide segments longer than [DENSIFY_STEP], preserving original vertices. */
    private fun densify(pts: List<VectorPoint>): List<VectorPoint> {
        val out = ArrayList<VectorPoint>(pts.size * 2)
        out += pts[0]
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val len = hypot(b.x - a.x, b.y - a.y)
            val steps = (len / DENSIFY_STEP).toInt()
            for (k in 1 until steps) {
                val f = k.toFloat() / steps
                out += VectorPoint(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
            }
            out += b
        }
        return out
    }

    /** Unit normal at vertex [i]: perpendicular to the averaged adjacent tangent. */
    private fun normalAt(pts: List<VectorPoint>, i: Int): Pair<Float, Float> {
        val prev = pts[maxOf(0, i - 1)]
        val next = pts[minOf(pts.size - 1, i + 1)]
        var tx = next.x - prev.x
        var ty = next.y - prev.y
        val len = hypot(tx, ty)
        if (len <= EPS) return 0f to 0f
        tx /= len
        ty /= len
        // Left normal of tangent (tx,ty) is (-ty, tx).
        return -ty to tx
    }
}
