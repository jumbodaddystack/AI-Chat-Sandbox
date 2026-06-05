package com.aichat.sandbox.data.vector

import kotlin.math.hypot

/**
 * Phase 5 (sub-feature 1) — bakes a dash pattern into chopped path geometry.
 *
 * SVG can express dashes natively (`stroke-dasharray`), but Android
 * VectorDrawable has **no** dash attribute, so a dashed stroke must be cut into
 * its "on" sub-segments on export. This baker flattens each subpath into a
 * polyline (reusing [VectorPathSampler]), walks it by arc length against the
 * dash pattern + phase offset, and returns one open `M…L…` run per drawn
 * interval. Pure and deterministic — no Android imports.
 *
 * The returned runs share the original stroke style at the call site; the writer
 * concatenates them into one stroked `<path>` (multiple subpaths) rather than
 * emitting one element per run, which keeps ids unique and is visually identical.
 */
object StrokeDashBaker {

    private const val EPS = 1e-4f

    /**
     * Cut [commands] into the "on" runs dictated by [dash] (alternating on/off
     * lengths in viewport units) shifted by [offset]. An odd-length pattern is
     * doubled (SVG semantics). A degenerate pattern (empty, all-zero, or with a
     * negative entry) returns the input as a single run — nothing to bake.
     *
     * The pattern position at path distance `d` is `(d + offset) mod total`, so a
     * positive [offset] shifts the dashes backward along the path.
     */
    fun bake(
        commands: List<PathCommand>,
        dash: List<Float>,
        offset: Float = 0f,
        curveSteps: Int = 16,
    ): List<List<PathCommand>> {
        val pattern = normalize(dash) ?: return listOf(commands)
        val total = pattern.sum()
        if (total <= EPS) return listOf(commands)

        val out = ArrayList<List<PathCommand>>()
        for (sub in splitSubpaths(commands)) {
            val sampled = VectorPathSampler.sample(sub, curveSteps)
            var pts = VectorPathSimplifier.removeConsecutiveDuplicates(sampled.points)
            if (sampled.closed && pts.size >= 2) pts = pts + pts.first()
            if (pts.size < 2) continue
            dashPolyline(pts, pattern, total, offset, out)
        }
        return out
    }

    /** Filter/validate the pattern: non-negative entries, doubled when odd. */
    private fun normalize(dash: List<Float>): List<Float>? {
        if (dash.isEmpty()) return null
        if (dash.any { it < 0f || it.isNaN() }) return null
        return if (dash.size % 2 == 1) dash + dash else dash
    }

    /** Split a command list into per-subpath command lists at each MoveTo. */
    private fun splitSubpaths(commands: List<PathCommand>): List<List<PathCommand>> {
        val subs = ArrayList<List<PathCommand>>()
        var current = ArrayList<PathCommand>()
        for (cmd in commands) {
            if (cmd is PathCommand.MoveTo && current.isNotEmpty()) {
                subs += current
                current = ArrayList()
            }
            current += cmd
        }
        if (current.isNotEmpty()) subs += current
        return subs
    }

    private fun dashPolyline(
        pts: List<VectorPoint>,
        pattern: List<Float>,
        total: Float,
        offset: Float,
        out: MutableList<List<PathCommand>>,
    ) {
        // Cumulative arc length per vertex.
        val cum = FloatArray(pts.size)
        for (i in 1 until pts.size) {
            cum[i] = cum[i - 1] + hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        }
        val length = cum.last()
        if (length <= EPS) return

        for ((a, b) in onIntervals(pattern, total, offset, length)) {
            val run = cut(pts, cum, a, b)
            if (run.size >= 2) {
                val cmds = ArrayList<PathCommand>(run.size)
                cmds += PathCommand.MoveTo(run[0].x, run[0].y)
                for (k in 1 until run.size) cmds += PathCommand.LineTo(run[k].x, run[k].y)
                out += cmds
            }
        }
    }

    /** Distance ranges along `[0, length]` that fall on an "on" dash, merged across zero gaps. */
    private fun onIntervals(
        pattern: List<Float>,
        total: Float,
        offset: Float,
        length: Float,
    ): List<Pair<Float, Float>> {
        // Pattern position at d=0, and which dash index it lands in.
        var p = ((offset % total) + total) % total
        var idx = 0
        while (idx < pattern.size && p >= pattern[idx]) {
            p -= pattern[idx]
            idx++
        }
        if (idx >= pattern.size) idx = 0
        var remaining = pattern[idx] - p

        val raw = ArrayList<Pair<Float, Float>>()
        var d = 0f
        var guard = 0
        val maxGuard = (length / (smallestPositive(pattern).coerceAtLeast(EPS))).toInt() + pattern.size + 16
        while (d < length - EPS && guard++ < maxGuard * 4) {
            if (remaining <= EPS) {
                idx = (idx + 1) % pattern.size
                remaining = pattern[idx]
                continue
            }
            val step = minOf(remaining, length - d)
            if (idx % 2 == 0 && step > EPS) raw += d to (d + step)
            d += step
            remaining -= step
        }
        return merge(raw)
    }

    private fun smallestPositive(pattern: List<Float>): Float =
        pattern.filter { it > EPS }.minOrNull() ?: 0f

    /** Merge intervals whose endpoints touch (a zero-length "off" gap). */
    private fun merge(intervals: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (intervals.isEmpty()) return intervals
        val out = ArrayList<Pair<Float, Float>>()
        var (curA, curB) = intervals[0]
        for (i in 1 until intervals.size) {
            val (a, b) = intervals[i]
            if (a - curB <= EPS) {
                curB = maxOf(curB, b)
            } else {
                out += curA to curB
                curA = a
                curB = b
            }
        }
        out += curA to curB
        return out
    }

    /** Sub-polyline of [pts] between arc-length [a] and [b] (interpolating the ends). */
    private fun cut(pts: List<VectorPoint>, cum: FloatArray, a: Float, b: Float): List<VectorPoint> {
        val out = ArrayList<VectorPoint>()
        out += at(pts, cum, a)
        for (i in pts.indices) {
            if (cum[i] > a + EPS && cum[i] < b - EPS) out += pts[i]
        }
        out += at(pts, cum, b)
        return out
    }

    /** Point at arc-length [dist] along the polyline. */
    private fun at(pts: List<VectorPoint>, cum: FloatArray, dist: Float): VectorPoint {
        val d = dist.coerceIn(0f, cum.last())
        var i = 1
        while (i < cum.size && cum[i] < d) i++
        if (i >= cum.size) return pts.last()
        val segLen = cum[i] - cum[i - 1]
        if (segLen <= EPS) return pts[i]
        val f = (d - cum[i - 1]) / segLen
        val p0 = pts[i - 1]
        val p1 = pts[i]
        return VectorPoint(p0.x + (p1.x - p0.x) * f, p0.y + (p1.y - p0.y) * f)
    }
}
