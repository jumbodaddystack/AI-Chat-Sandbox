package com.aichat.sandbox.data.vector.edit.boolean

import com.aichat.sandbox.data.vector.VectorPoint
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Phase 2 — convert a stroked centerline into a filled outline [PolyShape].
 *
 * Rather than offsetting the centerline and hand-stitching joins (error-prone on
 * concave turns), we build the outline as the **union of simple pieces**: a quad
 * per segment, a join piece per interior vertex (round disk / miter wedge / bevel
 * triangle), and a cap piece per open end (round disk / square extension / nothing
 * for butt). The pieces are fused in one pass by [PolygonClipper.selfUnion], which
 * cleans every overlap and emits correctly-oriented rings — a closed centerline
 * naturally yields an annulus (outer ring + inner hole).
 */
internal object StrokeOutliner {

    enum class LineCap { BUTT, ROUND, SQUARE }
    enum class LineJoin { MITER, ROUND, BEVEL }

    private const val ARC_SEGMENTS = 16

    fun capOf(value: String?): LineCap = when (value?.lowercase()) {
        "round" -> LineCap.ROUND
        "square" -> LineCap.SQUARE
        else -> LineCap.BUTT
    }

    fun joinOf(value: String?): LineJoin = when (value?.lowercase()) {
        "round" -> LineJoin.ROUND
        "bevel" -> LineJoin.BEVEL
        else -> LineJoin.MITER
    }

    fun outline(
        centerline: Ring,
        closed: Boolean,
        width: Float,
        cap: LineCap,
        join: LineJoin,
        miterLimit: Float,
    ): PolyShape {
        val pts = centerline.points.map { V(it.x.toDouble(), it.y.toDouble()) }
        if (pts.size < 2) return PolyShape.EMPTY
        val half = width / 2.0
        if (half <= 0.0) return PolyShape.EMPTY

        val pieces = ArrayList<Ring>()
        val n = pts.size
        val segCount = if (closed) n else n - 1

        // 1) one quad per segment.
        for (i in 0 until segCount) {
            val a = pts[i]
            val b = pts[(i + 1) % n]
            quad(a, b, half)?.let { pieces += it }
        }

        // 2) join piece per interior vertex (all vertices when closed).
        val joinRange = if (closed) 0 until n else 1 until n - 1
        for (i in joinRange) {
            val prev = pts[(i - 1 + n) % n]
            val cur = pts[i]
            val next = pts[(i + 1) % n]
            joinPiece(prev, cur, next, half, join, miterLimit.toDouble())?.let { pieces += it }
        }

        // 3) caps for open ends.
        if (!closed) {
            endCap(pts[0], pts[1], half, cap, atStart = true)?.let { pieces += it }
            endCap(pts[n - 1], pts[n - 2], half, cap, atStart = false)?.let { pieces += it }
        }

        if (pieces.isEmpty()) return PolyShape.EMPTY
        return PolygonClipper.selfUnion(PolyShape(pieces, FillRule.NONZERO))
    }

    /** A rectangle covering the segment a→b offset ±half. */
    private fun quad(a: V, b: V, half: Double): Ring? {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < EPS) return null
        val nx = -dy / len * half; val ny = dx / len * half
        return ring(
            V(a.x + nx, a.y + ny),
            V(b.x + nx, b.y + ny),
            V(b.x - nx, b.y - ny),
            V(a.x - nx, a.y - ny),
        )
    }

    private fun joinPiece(
        prev: V, cur: V, next: V, half: Double, join: LineJoin, miterLimit: Double,
    ): Ring? {
        val dIn = (cur - prev).norm() ?: return null
        val dOut = (next - cur).norm() ?: return null
        val cross = dIn.x * dOut.y - dIn.y * dOut.x
        if (kotlin.math.abs(cross) < 1e-9) return null // collinear, nothing to fill

        val perpIn = V(-dIn.y, dIn.x) * half
        val perpOut = V(-dOut.y, dOut.x) * half

        return when (join) {
            LineJoin.ROUND -> disk(cur, half)
            LineJoin.BEVEL -> bevelBoth(cur, perpIn, perpOut)
            LineJoin.MITER -> {
                val outerS = if (cross > 0) -1.0 else 1.0 // convex side opposite the turn
                val inC = cur + perpIn * outerS
                val outC = cur + perpOut * outerS
                val miter = lineIntersect(inC, dIn, outC, dOut)
                if (miter != null) {
                    val ratio = (miter - cur).len() / half
                    if (ratio <= miterLimit) {
                        return ring(inC, miter, outC, cur)
                    }
                }
                bevelBoth(cur, perpIn, perpOut)
            }
        }
    }

    /** Bevel triangles on both sides; the inner one is harmlessly inside the band. */
    private fun bevelBoth(cur: V, perpIn: V, perpOut: V): Ring {
        // Two triangles share the vertex; encode as a single 5-point fan that
        // selfUnion will resolve. (Returning the outer triangle alone is enough on
        // convex turns, but both keeps it side-agnostic.)
        return ring(cur + perpIn, cur + perpOut, cur - perpIn, cur - perpOut)
    }

    private fun endCap(end: V, toward: V, half: Double, cap: LineCap, atStart: Boolean): Ring? {
        val dir = (end - toward).norm() ?: return null // outward direction
        val perp = V(-dir.y, dir.x) * half
        return when (cap) {
            LineCap.BUTT -> null
            LineCap.ROUND -> disk(end, half)
            LineCap.SQUARE -> {
                val ext = dir * half
                ring(end + perp, end + perp + ext, end - perp + ext, end - perp)
            }
        }
    }

    private fun disk(center: V, radius: Double): Ring {
        val pts = ArrayList<VectorPoint>(ARC_SEGMENTS)
        for (k in 0 until ARC_SEGMENTS) {
            val a = 2.0 * Math.PI * k / ARC_SEGMENTS
            pts += VectorPoint((center.x + radius * cos(a)).toFloat(), (center.y + radius * sin(a)).toFloat())
        }
        return Ring(pts)
    }

    private fun lineIntersect(p1: V, d1: V, p2: V, d2: V): V? {
        val denom = d1.x * d2.y - d1.y * d2.x
        if (kotlin.math.abs(denom) < 1e-12) return null
        val t = ((p2.x - p1.x) * d2.y - (p2.y - p1.y) * d2.x) / denom
        return V(p1.x + d1.x * t, p1.y + d1.y * t)
    }

    private fun ring(vararg vs: V): Ring = Ring(vs.map { VectorPoint(it.x.toFloat(), it.y.toFloat()) })

    private const val EPS = 1e-9

    private data class V(val x: Double, val y: Double) {
        operator fun plus(o: V) = V(x + o.x, y + o.y)
        operator fun minus(o: V) = V(x - o.x, y - o.y)
        operator fun times(s: Double) = V(x * s, y * s)
        fun len() = hypot(x, y)
        fun norm(): V? { val l = len(); return if (l < EPS) null else V(x / l, y / l) }
    }
}
