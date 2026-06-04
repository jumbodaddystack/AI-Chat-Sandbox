package com.aichat.sandbox.data.vector.edit.boolean

import com.aichat.sandbox.data.vector.VectorPoint
import kotlin.math.abs

/**
 * Phase 2 — the internal polygon representation the boolean module passes between
 * its stages. It is never exposed outside `data/vector/edit/boolean/`: the public
 * façade ([PathBoolean]) takes and returns the Phase 1 editable model and uses
 * these polygons only as the intermediate between "editable cubics" and "editable
 * cubics".
 *
 * Everything here is **pure Kotlin, no Android imports**, so the whole algebra is
 * JVM-unit-tested like [com.aichat.sandbox.data.vector.VectorPathSimplifier]. We
 * reuse [VectorPoint] for the vertex type so float-format/geometry helpers are
 * shared with the sampler/simplifier.
 */

/** Even-odd vs non-zero winding — derived from `VectorStyle.fillType`. */
internal enum class FillRule { NONZERO, EVENODD }

/**
 * A single closed ring of flattened points (absolute viewport coords). The sign of
 * [signedArea] encodes orientation (the clipper relies on it for winding): the
 * shoelace area is positive for counter-clockwise rings and negative for clockwise
 * ones in the coordinate sense used throughout the module.
 *
 * Rings are stored *without* an explicit repeated closing point — the closing edge
 * from the last point back to the first is implicit.
 */
internal data class Ring(val points: List<VectorPoint>) {
    val signedArea: Float get() = signedAreaOf(points)
    val area: Float get() = abs(signedArea)

    /** A ring needs at least three distinct vertices to enclose any area. */
    val degenerate: Boolean get() = points.size < 3 || area <= AREA_EPS

    fun reversed(): Ring = Ring(points.asReversed().toList())

    /** Orient the ring so its signed area has the requested sign (CCW = positive). */
    fun oriented(ccw: Boolean): Ring {
        val isCcw = signedArea > 0f
        return if (isCcw == ccw) this else reversed()
    }

    companion object {
        /** Rings with area at or below this are treated as empty slivers. */
        const val AREA_EPS = 1e-6f

        fun signedAreaOf(points: List<VectorPoint>): Float {
            if (points.size < 3) return 0f
            var sum = 0.0
            for (i in points.indices) {
                val a = points[i]
                val b = points[if (i + 1 == points.size) 0 else i + 1]
                sum += a.x.toDouble() * b.y - b.x.toDouble() * a.y
            }
            return (sum / 2.0).toFloat()
        }
    }
}

/**
 * One shape = one or more [rings] (outer contours plus holes) with the [fillRule]
 * that decides which regions the rings enclose. Boolean results are emitted with
 * correctly-oriented rings (outer CCW, holes CW), so [NONZERO] reproduces them
 * exactly; that is the module's canonical output rule.
 */
internal data class PolyShape(
    val rings: List<Ring>,
    val fillRule: FillRule,
) {
    val isEmpty: Boolean get() = rings.none { !it.degenerate }

    /**
     * Net enclosed area. For a correctly-oriented result (outer CCW positive, holes
     * CW negative) this is `outer − holes`; for tolerance-based assertions in tests
     * it is the meaningful number regardless of how the rings were produced.
     */
    val area: Float get() = abs(rings.sumOf { it.signedArea.toDouble() }).toFloat()

    companion object {
        val EMPTY = PolyShape(emptyList(), FillRule.NONZERO)
    }
}
