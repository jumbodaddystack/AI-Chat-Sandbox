package com.aichat.sandbox.ui.components.notes

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Phase 15.1 — pressure-preserving stroke outlines.
 *
 * Converts a sampled stroke (`[x,y,p,tilt, …]`, the layout produced by
 * [StrokeCodec.decode]) into a single **closed outline polygon** whose local
 * thickness follows the same [ToolDynamics] width curve the renderer uses.
 * Because the result is plain filled geometry it survives SVG and
 * VectorDrawable export losslessly — the formats that forced the historical
 * "flatten to mean width" compromise only lack *per-segment stroke widths*,
 * not filled paths. (Same idea as tldraw's perfect-freehand: offset each
 * sample left/right along its normal by the local radius, then close the loop
 * with round end caps.)
 *
 * Pure Kotlin, no Android imports, so the geometry is pinnable in plain JVM
 * unit tests like the rest of the notes math.
 */
object StrokeOutliner {

    /** Points used to approximate each round end cap. */
    private const val CAP_STEPS = 8

    /** Points used for the circle emitted for a single-sample "dot" stroke. */
    private const val DOT_STEPS = 16

    /** Consecutive samples closer than this collapse into one outline point. */
    private const val DEDUP_EPSILON = 1e-3f

    /**
     * Width spread below this fraction of the base width counts as uniform —
     * outlining a constant-width stroke would only inflate the file, so
     * exporters fall back to the plain stroked path.
     */
    private const val UNIFORM_SPREAD_FRACTION = 0.05f

    /**
     * True when the [ToolDynamics] width actually varies across [samples] —
     * i.e. when outlining buys fidelity over a single stroke width. Constant
     * tools (highlighter) and uniform-pressure strokes return false.
     */
    fun hasVariableWidth(samples: FloatArray, tool: String?, baseWidthPx: Float): Boolean {
        val s = StrokeCodec.FLOATS_PER_SAMPLE
        val count = samples.size / s
        if (count < 2) return false
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (i in 0 until count) {
            val w = ToolDynamics.forTool(tool, baseWidthPx, samples[i * s + 2], samples[i * s + 3]).widthPx
            if (w < min) min = w
            if (w > max) max = w
        }
        return max - min > baseWidthPx * UNIFORM_SPREAD_FRACTION
    }

    /**
     * Build the closed outline polygon as packed `[x0,y0, x1,y1, …]` world
     * coordinates. Empty array for an empty stroke; a [DOT_STEPS]-gon circle
     * for a single (or fully coincident) sample.
     */
    fun outline(samples: FloatArray, tool: String?, baseWidthPx: Float): FloatArray {
        val s = StrokeCodec.FLOATS_PER_SAMPLE
        val count = samples.size / s
        if (count < 1) return FloatArray(0)

        // Dedup coincident samples; keep the widest radius seen at a point so
        // a pressure spike on a stationary pen still reads.
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        val rs = FloatArray(count)
        var n = 0
        for (i in 0 until count) {
            val x = samples[i * s]
            val y = samples[i * s + 1]
            val r = max(
                ToolDynamics.MIN_WIDTH_PX,
                ToolDynamics.forTool(tool, baseWidthPx, samples[i * s + 2], samples[i * s + 3]).widthPx,
            ) / 2f
            if (n > 0 && hypot(x - xs[n - 1], y - ys[n - 1]) < DEDUP_EPSILON) {
                rs[n - 1] = max(rs[n - 1], r)
                continue
            }
            xs[n] = x; ys[n] = y; rs[n] = r
            n++
        }

        if (n == 1) return circle(xs[0], ys[0], rs[0])

        // Per-point unit tangents (central difference; one-sided at the ends).
        val tx = FloatArray(n)
        val ty = FloatArray(n)
        for (i in 0 until n) {
            val prev = if (i == 0) 0 else i - 1
            val next = if (i == n - 1) n - 1 else i + 1
            var dx = xs[next] - xs[prev]
            var dy = ys[next] - ys[prev]
            var len = hypot(dx, dy)
            if (len < DEDUP_EPSILON) {
                // Degenerate central diff (sharp back-and-forth) — fall back
                // to the forward segment, then to the previous tangent.
                if (i + 1 < n) { dx = xs[i + 1] - xs[i]; dy = ys[i + 1] - ys[i]; len = hypot(dx, dy) }
                if (len < DEDUP_EPSILON && i > 0) { dx = tx[i - 1]; dy = ty[i - 1]; len = hypot(dx, dy) }
                if (len < DEDUP_EPSILON) { dx = 1f; dy = 0f; len = 1f }
            }
            tx[i] = dx / len
            ty[i] = dy / len
        }

        // Normal = tangent rotated +90°: n = (-ty, tx). Left rail forward,
        // round end cap, right rail backward, round start cap, close.
        val out = FloatArray((2 * n + 2 * (CAP_STEPS - 1)) * 2)
        var o = 0
        for (i in 0 until n) {
            out[o++] = xs[i] - ty[i] * rs[i]
            out[o++] = ys[i] + tx[i] * rs[i]
        }
        o = appendCap(out, o, xs[n - 1], ys[n - 1], -ty[n - 1] * rs[n - 1], tx[n - 1] * rs[n - 1])
        for (i in n - 1 downTo 0) {
            out[o++] = xs[i] + ty[i] * rs[i]
            out[o++] = ys[i] - tx[i] * rs[i]
        }
        o = appendCap(out, o, xs[0], ys[0], ty[0] * rs[0], -tx[0] * rs[0])
        return out
    }

    /**
     * Append [CAP_STEPS]−1 intermediate arc points sweeping the offset vector
     * `(vx,vy)` half a turn around `(cx,cy)`. Rotating by −90° carries the
     * left-rail normal through the tangent direction, so the cap bulges past
     * the stroke end the way the renderer's round cap does.
     */
    private fun appendCap(out: FloatArray, offset: Int, cx: Float, cy: Float, vx: Float, vy: Float): Int {
        var o = offset
        for (j in 1 until CAP_STEPS) {
            val a = -(Math.PI * j / CAP_STEPS)
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            out[o++] = cx + vx * c - vy * s
            out[o++] = cy + vx * s + vy * c
        }
        return o
    }

    private fun circle(cx: Float, cy: Float, r: Float): FloatArray {
        val out = FloatArray(DOT_STEPS * 2)
        for (j in 0 until DOT_STEPS) {
            val a = 2.0 * Math.PI * j / DOT_STEPS
            out[j * 2] = cx + r * cos(a).toFloat()
            out[j * 2 + 1] = cy + r * sin(a).toFloat()
        }
        return out
    }

    /**
     * Closed path data (`M … Q … Z`) through the outline's segment midpoints —
     * the same midpoint-quadratic smoothing the stroke renderer uses, applied
     * to the loop, so the filled result reads as smooth ink rather than a
     * faceted polygon. The grammar is the shared SVG-path subset, valid in
     * both `<path d>` and `android:pathData`.
     */
    fun pathData(points: FloatArray, fmt: (Float) -> String): String {
        val n = points.size / 2
        if (n == 0) return ""
        val sb = StringBuilder(n * 24)
        if (n < 3) {
            sb.append('M').append(fmt(points[0])).append(' ').append(fmt(points[1]))
            for (i in 1 until n) {
                sb.append('L').append(fmt(points[i * 2])).append(' ').append(fmt(points[i * 2 + 1]))
            }
            sb.append('Z')
            return sb.toString()
        }
        fun px(i: Int) = points[(i % n) * 2]
        fun py(i: Int) = points[(i % n) * 2 + 1]
        sb.append('M').append(fmt((px(0) + px(1)) * 0.5f)).append(' ')
            .append(fmt((py(0) + py(1)) * 0.5f))
        for (i in 1..n) {
            sb.append('Q').append(fmt(px(i))).append(' ').append(fmt(py(i)))
                .append(' ').append(fmt((px(i) + px(i + 1)) * 0.5f))
                .append(' ').append(fmt((py(i) + py(i + 1)) * 0.5f))
        }
        sb.append('Z')
        return sb.toString()
    }
}
