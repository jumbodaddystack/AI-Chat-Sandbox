package com.aichat.sandbox.data.vector.edit.boolean

import com.aichat.sandbox.data.vector.VectorPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The four boolean operations the clipper supports. */
internal enum class BoolOp { UNION, INTERSECT, DIFFERENCE, XOR }

/**
 * Phase 2, stage 2 — the load-bearing polygon clipper.
 *
 * Rather than an in-place sweep (Martinez–Rueda), this uses an **arrangement +
 * boundary-classification** approach that is robust on exactly the degenerate
 * geometry icons produce (axis-aligned, coincident, collinear, shared edges):
 *
 *  1. Throw every edge of both shapes (and self-edges) into one set and split them
 *     at all pairwise intersections — including collinear overlaps.
 *  2. Snap-merge near-coincident vertices so shared/coincident geometry collapses.
 *  3. For each unique undirected edge, sample the original shapes just off each
 *     side and decide, per the op, whether the kept region differs across the edge.
 *     If it does, the edge is on the result boundary; orient it so the kept region
 *     lies on its left.
 *  4. Chain the directed boundary edges into closed rings (DCEL "next = clockwise
 *     from twin" rule), which come out correctly oriented (outer CCW, holes CW).
 *
 * All arithmetic is done in [Double]; only the final rings are converted back to
 * the module's float [Ring]s. O(n²) intersection is fine at icon flattening scale.
 */
internal object PolygonClipper {

    fun clip(subject: PolyShape, clip: PolyShape, op: BoolOp): PolyShape {
        val subjRings = subject.rings.filter { it.points.size >= 3 }.map { it.toDoubles() }
        val clipRings = clip.rings.filter { it.points.size >= 3 }.map { it.toDoubles() }
        if (subjRings.isEmpty() && clipRings.isEmpty()) return PolyShape.EMPTY

        // Fast structural outs that also keep the arrangement small.
        when (op) {
            BoolOp.UNION -> {
                if (subjRings.isEmpty()) return clip
                if (clipRings.isEmpty()) return subject
            }
            BoolOp.INTERSECT -> if (subjRings.isEmpty() || clipRings.isEmpty()) return PolyShape.EMPTY
            BoolOp.DIFFERENCE -> {
                if (subjRings.isEmpty()) return PolyShape.EMPTY
                if (clipRings.isEmpty()) return subject
            }
            BoolOp.XOR -> {
                if (subjRings.isEmpty()) return clip
                if (clipRings.isEmpty()) return subject
            }
        }

        return arrange(subjRings, clipRings) { inA, inB -> predicate(op, inA, inB) }
    }

    /**
     * Merge all (possibly overlapping) rings of a single shape into clean,
     * correctly-oriented outer+hole rings, using non-zero winding. Used by the
     * stroke outliner and offset to fuse a pile of per-segment pieces in one pass.
     */
    fun selfUnion(shape: PolyShape): PolyShape {
        // Every input ring is a solid piece, so force a common (CCW) orientation:
        // non-zero winding would otherwise cancel oppositely-wound overlaps.
        val rings = shape.rings
            .filter { it.points.size >= 3 }
            .map { it.oriented(ccw = true).toDoubles() }
        if (rings.isEmpty()) return PolyShape.EMPTY
        return arrange(rings, emptyList()) { inA, _ -> inA }
    }

    /** Shared arrangement core: split → classify by [keep] → chain into rings. */
    private fun arrange(
        subjRings: List<List<DPoint>>,
        clipRings: List<List<DPoint>>,
        keep: (Boolean, Boolean) -> Boolean,
    ): PolyShape {
        val merge = mergeEpsilon(subjRings, clipRings)
        val segments = collectSegments(subjRings) + collectSegments(clipRings)
        val pieces = splitAtIntersections(segments)
        val verts = VertexTable(merge)
        val edges = LinkedHashMap<Long, Edge>()
        for ((a, b) in pieces) {
            val ia = verts.id(a)
            val ib = verts.id(b)
            if (ia == ib) continue
            val key = edgeKey(ia, ib)
            edges.getOrPut(key) { Edge(min(ia, ib), max(ia, ib)) }
        }

        val directed = ArrayList<DirectedEdge>()
        for (edge in edges.values) {
            val a = verts.point(edge.lo)
            val b = verts.point(edge.hi)
            when (classify(a, b, subjRings, clipRings, keep)) {
                Keep.FORWARD -> directed += DirectedEdge(edge.lo, edge.hi)
                Keep.BACKWARD -> directed += DirectedEdge(edge.hi, edge.lo)
                Keep.NONE -> {}
            }
        }
        return PolyShape(chainRings(directed, verts), FillRule.NONZERO)
    }

    // ---- edge classification ----

    private enum class Keep { FORWARD, BACKWARD, NONE }

    /**
     * Decide whether the undirected edge a→b survives the op and, if so, which
     * direction puts the kept region on its left. We sample the original shapes a
     * hair off each side of the midpoint along the left normal `(-dy, dx)`.
     */
    private fun classify(
        a: DPoint,
        b: DPoint,
        subj: List<List<DPoint>>,
        clip: List<List<DPoint>>,
        keep: (Boolean, Boolean) -> Boolean,
    ): Keep {
        val mx = (a.x + b.x) * 0.5
        val my = (a.y + b.y) * 0.5
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len <= 0.0) return Keep.NONE
        val nx = -dy / len
        val ny = dx / len
        val off = max(len * 1e-3, 1e-7)
        val lx = mx + nx * off; val ly = my + ny * off
        val rx = mx - nx * off; val ry = my - ny * off

        val keepLeft = keep(inside(subj, lx, ly), inside(clip, lx, ly))
        val keepRight = keep(inside(subj, rx, ry), inside(clip, rx, ry))
        return when {
            keepLeft == keepRight -> Keep.NONE
            keepLeft -> Keep.FORWARD
            else -> Keep.BACKWARD
        }
    }

    private fun predicate(op: BoolOp, inA: Boolean, inB: Boolean): Boolean = when (op) {
        BoolOp.UNION -> inA || inB
        BoolOp.INTERSECT -> inA && inB
        BoolOp.DIFFERENCE -> inA && !inB
        BoolOp.XOR -> inA != inB
    }

    /** Point membership in a multi-ring shape; rings carry their own [FillRule]. */
    private fun inside(rings: List<List<DPoint>>, px: Double, py: Double): Boolean {
        // Boolean operands are always evaluated with non-zero winding: the operands
        // we feed the clipper are either freshly flattened input rings (single
        // orientation) or prior clipper output (correctly oriented). Non-zero is
        // exact for both and avoids orientation-sensitivity of even-odd here.
        var wn = 0
        for (ring in rings) {
            val n = ring.size
            for (i in 0 until n) {
                val a = ring[i]
                val b = ring[if (i + 1 == n) 0 else i + 1]
                if (a.y <= py) {
                    if (b.y > py && isLeft(a, b, px, py) > 0.0) wn++
                } else {
                    if (b.y <= py && isLeft(a, b, px, py) < 0.0) wn--
                }
            }
        }
        return wn != 0
    }

    private fun isLeft(a: DPoint, b: DPoint, px: Double, py: Double): Double =
        (b.x - a.x) * (py - a.y) - (px - a.x) * (b.y - a.y)

    // ---- ring chaining ----

    private fun chainRings(directed: List<DirectedEdge>, verts: VertexTable): List<Ring> {
        if (directed.isEmpty()) return emptyList()
        val adj = HashMap<Int, MutableList<Int>>()
        directed.forEachIndexed { idx, e ->
            adj.getOrPut(e.from) { ArrayList() }.add(idx)
        }
        val used = BooleanArray(directed.size)
        val rings = ArrayList<Ring>()

        for (startIdx in directed.indices) {
            if (used[startIdx]) continue
            val loop = ArrayList<Int>()
            var cur = startIdx
            var guard = 0
            val limit = directed.size + 4
            while (!used[cur] && guard++ <= limit) {
                used[cur] = true
                loop += cur
                val e = directed[cur]
                val next = chooseNext(e, directed, adj, used, verts) ?: break
                if (next == startIdx) break
                cur = next
            }
            if (loop.size < 3) continue
            val pts = loop.map { verts.point(directed[it].from).toFloat() }
            val ring = Ring(pts)
            if (!ring.degenerate) rings += ring
        }
        return rings
    }

    /**
     * Pick the next outgoing edge at the destination of [e] using the DCEL rule:
     * the first edge encountered rotating clockwise from the reverse-incoming
     * direction. That keeps the kept region (which sits to the left of every
     * directed edge) on the left through the vertex.
     */
    private fun chooseNext(
        e: DirectedEdge,
        directed: List<DirectedEdge>,
        adj: Map<Int, MutableList<Int>>,
        used: BooleanArray,
        verts: VertexTable,
    ): Int? {
        val candidates = adj[e.to] ?: return null
        val v = verts.point(e.to)
        val from = verts.point(e.from)
        val refX = from.x - v.x
        val refY = from.y - v.y
        var best = -1
        var bestAngle = Double.MAX_VALUE
        for (idx in candidates) {
            if (used[idx]) continue
            val w = verts.point(directed[idx].to)
            val dx = w.x - v.x
            val dy = w.y - v.y
            if (dx == 0.0 && dy == 0.0) continue
            var ang = clockwiseAngle(refX, refY, dx, dy)
            // The immediate reverse (going back the way we came) is a last resort.
            if (ang <= ANG_EPS) ang += TWO_PI
            if (ang < bestAngle) {
                bestAngle = ang
                best = idx
            }
        }
        return if (best >= 0) best else null
    }

    /** Clockwise angle in [0, 2π) to rotate vector (ux,uy) onto (vx,vy). */
    private fun clockwiseAngle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
        val cross = ux * vy - uy * vx
        val dot = ux * vx + uy * vy
        val ccw = atan2(cross, dot) // (-π, π]
        val cw = -ccw
        return if (cw < 0.0) cw + TWO_PI else cw
    }

    // ---- arrangement construction ----

    private fun collectSegments(rings: List<List<DPoint>>): List<Pair<DPoint, DPoint>> {
        val out = ArrayList<Pair<DPoint, DPoint>>()
        for (ring in rings) {
            val n = ring.size
            for (i in 0 until n) {
                val a = ring[i]
                val b = ring[if (i + 1 == n) 0 else i + 1]
                if (a.x != b.x || a.y != b.y) out += a to b
            }
        }
        return out
    }

    /**
     * Split every segment at all intersection parameters with every other segment
     * (proper crossings, T-junctions, and collinear-overlap endpoints).
     */
    private fun splitAtIntersections(
        segs: List<Pair<DPoint, DPoint>>,
    ): List<Pair<DPoint, DPoint>> {
        val n = segs.size
        val params = Array(n) { sortedSetOf(0.0, 1.0) }
        for (i in 0 until n) {
            val (p1, p2) = segs[i]
            for (j in i + 1 until n) {
                val (p3, p4) = segs[j]
                intersect(p1, p2, p3, p4, params[i], params[j])
            }
        }
        val out = ArrayList<Pair<DPoint, DPoint>>()
        for (i in 0 until n) {
            val (a, b) = segs[i]
            val ts = params[i].toList()
            for (k in 0 until ts.size - 1) {
                val t0 = ts[k]; val t1 = ts[k + 1]
                if (t1 - t0 < 1e-12) continue
                val pa = DPoint(a.x + (b.x - a.x) * t0, a.y + (b.y - a.y) * t0)
                val pb = DPoint(a.x + (b.x - a.x) * t1, a.y + (b.y - a.y) * t1)
                out += pa to pb
            }
        }
        return out
    }

    private fun intersect(
        p1: DPoint, p2: DPoint, p3: DPoint, p4: DPoint,
        out1: MutableSet<Double>, out2: MutableSet<Double>,
    ) {
        val rx = p2.x - p1.x; val ry = p2.y - p1.y
        val sx = p4.x - p3.x; val sy = p4.y - p3.y
        val denom = rx * sy - ry * sx
        val qx = p3.x - p1.x; val qy = p3.y - p1.y
        val rLen = hypot(rx, ry); val sLen = hypot(sx, sy)
        if (rLen <= 0.0 || sLen <= 0.0) return

        if (abs(denom) > 1e-12 * rLen * sLen) {
            val t = (qx * sy - qy * sx) / denom
            val u = (qx * ry - qy * rx) / denom
            if (t in -PARAM_EPS..1.0 + PARAM_EPS && u in -PARAM_EPS..1.0 + PARAM_EPS) {
                out1 += t.coerceIn(0.0, 1.0)
                out2 += u.coerceIn(0.0, 1.0)
            }
            return
        }
        // Parallel — record collinear overlap endpoints on both segments.
        val perp = qx * ry - qy * rx // = cross(q, r); |perp|/rLen = distance of p3 to line1
        if (abs(perp) > COLLINEAR_EPS * rLen) return
        val r2 = rx * rx + ry * ry
        addProjection(out1, ((p3.x - p1.x) * rx + (p3.y - p1.y) * ry) / r2)
        addProjection(out1, ((p4.x - p1.x) * rx + (p4.y - p1.y) * ry) / r2)
        val s2 = sx * sx + sy * sy
        addProjection(out2, ((p1.x - p3.x) * sx + (p1.y - p3.y) * sy) / s2)
        addProjection(out2, ((p2.x - p3.x) * sx + (p2.y - p3.y) * sy) / s2)
    }

    private fun addProjection(set: MutableSet<Double>, t: Double) {
        if (t in -PARAM_EPS..1.0 + PARAM_EPS) set += t.coerceIn(0.0, 1.0)
    }

    private fun mergeEpsilon(a: List<List<DPoint>>, b: List<List<DPoint>>): Double {
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (rings in listOf(a, b)) for (ring in rings) for (p in ring) {
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
        }
        if (minX > maxX) return 1e-7
        val diag = hypot(maxX - minX, maxY - minY)
        return max(diag * 1e-7, 1e-9)
    }

    // ---- small value types ----

    private data class DPoint(val x: Double, val y: Double) {
        fun toFloat() = VectorPoint(x.toFloat(), y.toFloat())
    }

    private data class Edge(val lo: Int, val hi: Int)
    private data class DirectedEdge(val from: Int, val to: Int)

    private fun edgeKey(a: Int, b: Int): Long {
        val lo = min(a, b).toLong()
        val hi = max(a, b).toLong()
        return (lo shl 32) or (hi and 0xffffffffL)
    }

    /** Snap-merges near-coincident points to a shared integer id. */
    private class VertexTable(private val eps: Double) {
        private val map = HashMap<Long, Int>()
        private val pts = ArrayList<DPoint>()
        private val inv = if (eps > 0) 1.0 / eps else 0.0

        fun id(p: DPoint): Int {
            val kx = Math.round(p.x * inv)
            val ky = Math.round(p.y * inv)
            val key = (kx shl 32) xor (ky and 0xffffffffL)
            map[key]?.let { return it }
            val id = pts.size
            map[key] = id
            pts += DPoint(kx * eps, ky * eps)
            return id
        }

        fun point(id: Int): DPoint = pts[id]
    }

    private fun Ring.toDoubles(): List<DPoint> = points.map { DPoint(it.x.toDouble(), it.y.toDouble()) }

    private const val PARAM_EPS = 1e-9
    private const val COLLINEAR_EPS = 1e-7
    private const val ANG_EPS = 1e-9
    private const val TWO_PI = 2.0 * Math.PI
}
