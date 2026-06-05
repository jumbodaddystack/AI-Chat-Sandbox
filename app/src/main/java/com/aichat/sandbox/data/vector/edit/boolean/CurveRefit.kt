package com.aichat.sandbox.data.vector.edit.boolean

import com.aichat.sandbox.data.vector.edit.AnchorType
import com.aichat.sandbox.data.vector.edit.ControlPoint
import com.aichat.sandbox.data.vector.edit.EditAnchor
import com.aichat.sandbox.data.vector.edit.EditSubpath
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Phase 2, stage 4 — refit a flattened polygon [Ring] back into the Phase 1
 * editable model as an [EditSubpath] of cubic anchors, closing the loop
 * (polyline → cubics → editable nodes) so boolean/outline/offset results re-enter
 * the node editor, undo/redo, and the exporters unchanged.
 *
 * The ring is split at **corners** (turn angle above [cornerAngleDeg]); each smooth
 * run between corners is fit with a **Schneider** least-squares cubic Bézier fit
 * that recursively subdivides until every fitted segment is within [maxError] of
 * the sampled points. Straight runs emit handle-less anchors (so they serialize as
 * `LineTo`); curved runs emit anchors with absolute in/out handles. Corner anchors
 * are [AnchorType.CORNER]; tangent-continuous joins are classified [AnchorType.SMOOTH].
 */
internal object CurveRefit {

    private const val REPARAM_ITERATIONS = 2

    fun refit(
        ring: Ring,
        maxError: Float,
        idPrefix: String,
        cornerAngleDeg: Float = 30f,
    ): EditSubpath {
        val pts = ring.points.map { V(it.x.toDouble(), it.y.toDouble()) }
        val n = pts.size
        val err = maxError.toDouble().coerceAtLeast(1e-4)

        if (n < 3) {
            // Degenerate: emit straight anchors as-is.
            val anchors = ring.points.mapIndexed { j, p -> EditAnchor("$idPrefix.a$j", p.x, p.y) }
            return EditSubpath(idPrefix, anchors, closed = true)
        }

        val cornerFlags = detectCorners(pts, cornerAngleDeg.toDouble())
        val cornerIdx = cornerFlags.indices.filter { cornerFlags[it] }

        val segs = ArrayList<Seg>()
        if (cornerIdx.isEmpty()) {
            // Fully smooth closed loop: fit the wrap-around run p0 → … → p0 with a
            // continuous seam tangent (central difference across p0), so the seam
            // anchor stays smooth instead of becoming a kink.
            val run = ArrayList(pts).apply { add(pts[0]) }
            val seam = (pts[1] - pts[n - 1]).normalized()
            segs += fitRun(run, err, seam, -seam)
        } else {
            for (i in cornerIdx.indices) {
                val start = cornerIdx[i]
                val end = cornerIdx[(i + 1) % cornerIdx.size]
                segs += fitRun(runPoints(pts, start, end), err)
            }
        }

        return segsToSubpath(segs, idPrefix)
    }

    // ---- corner detection ----

    private fun detectCorners(pts: List<V>, cornerAngleDeg: Double): BooleanArray {
        val n = pts.size
        val flags = BooleanArray(n)
        val threshold = Math.toRadians(cornerAngleDeg)
        for (i in 0 until n) {
            val prev = pts[(i - 1 + n) % n]
            val cur = pts[i]
            val next = pts[(i + 1) % n]
            val a = cur - prev
            val b = next - cur
            val la = a.len(); val lb = b.len()
            if (la < EPS || lb < EPS) continue
            val cosA = (a.dot(b) / (la * lb)).coerceIn(-1.0, 1.0)
            val turn = acos(cosA)
            if (turn > threshold) flags[i] = true
        }
        return flags
    }

    /** Inclusive run of points from [start] forward (wrapping) to [end]. */
    private fun runPoints(pts: List<V>, start: Int, end: Int): List<V> {
        val n = pts.size
        val run = ArrayList<V>()
        var i = start
        while (true) {
            run += pts[i]
            if (i == end && run.size > 1) break
            i = (i + 1) % n
            if (run.size > n + 1) break // safety
        }
        return run
    }

    // ---- per-run fitting ----

    private fun fitRun(run: List<V>, maxError: Double, t1: V? = null, t2: V? = null): List<Seg> {
        if (run.size < 2) return emptyList()
        if (run.size == 2 || (t1 == null && isStraight(run, maxError))) {
            return listOf(Seg(run.first(), null, null, run.last()))
        }
        val tHat1 = t1 ?: (run[1] - run[0]).normalized()
        val tHat2 = t2 ?: (run[run.size - 2] - run[run.size - 1]).normalized()
        val out = ArrayList<Array<V>>()
        fitCubic(run, tHat1, tHat2, maxError, out)
        return out.map { Seg(it[0], it[1], it[2], it[3]) }
    }

    private fun isStraight(run: List<V>, maxError: Double): Boolean {
        val a = run.first(); val b = run.last()
        for (i in 1 until run.size - 1) {
            if (perpDistance(run[i], a, b) > maxError) return false
        }
        return true
    }

    /** Recursive Schneider cubic fit (with a few Newton reparam passes). */
    private fun fitCubic(d: List<V>, tHat1: V, tHat2: V, error: Double, out: MutableList<Array<V>>) {
        if (d.size == 2) {
            val dist = (d[1] - d[0]).len() / 3.0
            out += arrayOf(d[0], d[0] + tHat1 * dist, d[1] + tHat2 * dist, d[1])
            return
        }
        var u = chordLengthParam(d)
        var bez = generateBezier(d, u, tHat1, tHat2)
        var (maxErr, split) = computeMaxError(d, bez, u)
        if (maxErr < error) {
            out += bez
            return
        }
        if (maxErr < error * error) {
            repeat(REPARAM_ITERATIONS) {
                u = reparameterize(d, u, bez)
                bez = generateBezier(d, u, tHat1, tHat2)
                val r = computeMaxError(d, bez, u)
                maxErr = r.first; split = r.second
                if (maxErr < error) {
                    out += bez
                    return
                }
            }
        }
        val center = computeCenterTangent(d, split)
        fitCubic(d.subList(0, split + 1), tHat1, center, error, out)
        fitCubic(d.subList(split, d.size), -center, tHat2, error, out)
    }

    private fun generateBezier(d: List<V>, u: DoubleArray, tHat1: V, tHat2: V): Array<V> {
        val n = d.size
        var c00 = 0.0; var c01 = 0.0; var c11 = 0.0; var x0 = 0.0; var x1 = 0.0
        val p0 = d[0]; val p3 = d[n - 1]
        for (i in 0 until n) {
            val b0 = bern0(u[i]); val b1 = bern1(u[i]); val b2 = bern2(u[i]); val b3 = bern3(u[i])
            val a0 = tHat1 * b1
            val a1 = tHat2 * b2
            c00 += a0.dot(a0); c01 += a0.dot(a1); c11 += a1.dot(a1)
            val tmp = d[i] - (p0 * (b0 + b1) + p3 * (b2 + b3))
            x0 += a0.dot(tmp); x1 += a1.dot(tmp)
        }
        val detC = c00 * c11 - c01 * c01
        val segLen = (p3 - p0).len()
        var alphaL: Double
        var alphaR: Double
        if (abs(detC) < 1e-12) {
            alphaL = segLen / 3.0; alphaR = segLen / 3.0
        } else {
            alphaL = (x0 * c11 - x1 * c01) / detC
            alphaR = (c00 * x1 - c01 * x0) / detC
        }
        val eps = 1e-6 * segLen
        if (alphaL < eps || alphaR < eps) {
            alphaL = segLen / 3.0; alphaR = segLen / 3.0
        }
        return arrayOf(p0, p0 + tHat1 * alphaL, p3 + tHat2 * alphaR, p3)
    }

    private fun computeMaxError(d: List<V>, bez: Array<V>, u: DoubleArray): Pair<Double, Int> {
        var maxDist = 0.0
        var split = d.size / 2
        for (i in 1 until d.size - 1) {
            val p = bezier(bez, u[i])
            val dist = (p - d[i]).lenSq()
            if (dist >= maxDist) {
                maxDist = dist
                split = i
            }
        }
        return sqrt(maxDist) to split
    }

    private fun reparameterize(d: List<V>, u: DoubleArray, bez: Array<V>): DoubleArray {
        val out = DoubleArray(d.size)
        for (i in d.indices) out[i] = newtonRoot(bez, d[i], u[i])
        return out
    }

    private fun newtonRoot(bez: Array<V>, point: V, u: Double): Double {
        val q = bezier(bez, u)
        // First/second derivatives via differenced control points.
        val q1 = arrayOf((bez[1] - bez[0]) * 3.0, (bez[2] - bez[1]) * 3.0, (bez[3] - bez[2]) * 3.0)
        val q2 = arrayOf((q1[1] - q1[0]) * 2.0, (q1[2] - q1[1]) * 2.0)
        val qu = q1[0] * ((1 - u) * (1 - u)) + q1[1] * (2 * (1 - u) * u) + q1[2] * (u * u)
        val quu = q2[0] * (1 - u) + q2[1] * u
        val num = (q - point).dot(qu)
        val den = qu.dot(qu) + (q - point).dot(quu)
        if (abs(den) < 1e-12) return u
        return u - num / den
    }

    private fun computeCenterTangent(d: List<V>, center: Int): V {
        val v1 = d[center - 1] - d[center]
        val v2 = d[center] - d[center + 1]
        return ((v1 + v2) * 0.5).normalized()
    }

    private fun chordLengthParam(d: List<V>): DoubleArray {
        val u = DoubleArray(d.size)
        for (i in 1 until d.size) u[i] = u[i - 1] + (d[i] - d[i - 1]).len()
        val total = u[d.size - 1]
        if (total > 0) for (i in 1 until d.size) u[i] /= total
        return u
    }

    // ---- assembly ----

    private fun segsToSubpath(segs: List<Seg>, idPrefix: String): EditSubpath {
        val m = segs.size
        if (m == 0) return EditSubpath(idPrefix, emptyList(), closed = true)
        val anchors = ArrayList<EditAnchor>(m)
        for (k in 0 until m) {
            val cur = segs[k]
            val prev = segs[(k - 1 + m) % m]
            val inHandle = prev.c2?.toCp()
            val outHandle = cur.c1?.toCp()
            anchors += EditAnchor(
                id = "$idPrefix.a$k",
                x = cur.p0.x.toFloat(),
                y = cur.p0.y.toFloat(),
                inHandle = inHandle,
                outHandle = outHandle,
                type = classify(cur.p0, inHandle, outHandle),
            )
        }
        return EditSubpath(idPrefix, anchors, closed = true)
    }

    private fun classify(anchor: V, inH: ControlPoint?, outH: ControlPoint?): AnchorType {
        if (inH == null || outH == null) return AnchorType.CORNER
        val ix = anchor.x - inH.x; val iy = anchor.y - inH.y
        val ox = outH.x - anchor.x; val oy = outH.y - anchor.y
        val li = hypot(ix, iy); val lo = hypot(ox, oy)
        if (li < EPS || lo < EPS) return AnchorType.CORNER
        val cross = ix * oy - iy * ox
        val dot = ix * ox + iy * oy
        if (abs(cross) > 1e-3 * li * lo || dot <= 0) return AnchorType.CORNER
        return AnchorType.SMOOTH
    }

    // ---- Bézier basis ----

    private fun bezier(b: Array<V>, t: Double): V {
        val mt = 1 - t
        return b[0] * (mt * mt * mt) +
            b[1] * (3 * mt * mt * t) +
            b[2] * (3 * mt * t * t) +
            b[3] * (t * t * t)
    }

    private fun bern0(t: Double): Double { val mt = 1 - t; return mt * mt * mt }
    private fun bern1(t: Double): Double { val mt = 1 - t; return 3 * mt * mt * t }
    private fun bern2(t: Double): Double { val mt = 1 - t; return 3 * mt * t * t }
    private fun bern3(t: Double): Double = t * t * t

    private fun perpDistance(p: V, a: V, b: V): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-12) return (p - a).len()
        val cross = dy * p.x - dx * p.y + b.x * a.y - b.y * a.x
        return abs(cross) / sqrt(lenSq)
    }

    private const val EPS = 1e-9

    // ---- small double vector + segment ----

    private data class V(val x: Double, val y: Double) {
        operator fun plus(o: V) = V(x + o.x, y + o.y)
        operator fun minus(o: V) = V(x - o.x, y - o.y)
        operator fun times(s: Double) = V(x * s, y * s)
        operator fun unaryMinus() = V(-x, -y)
        fun dot(o: V) = x * o.x + y * o.y
        fun len() = hypot(x, y)
        fun lenSq() = x * x + y * y
        fun normalized(): V { val l = len(); return if (l < EPS) V(0.0, 0.0) else V(x / l, y / l) }
        fun toCp() = ControlPoint(x.toFloat(), y.toFloat())
    }

    private data class Seg(val p0: V, val c1: V?, val c2: V?, val p3: V)
}
