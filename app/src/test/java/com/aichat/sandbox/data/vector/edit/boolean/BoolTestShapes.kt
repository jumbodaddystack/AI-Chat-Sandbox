package com.aichat.sandbox.data.vector.edit.boolean

import com.aichat.sandbox.data.vector.VectorPoint
import com.aichat.sandbox.data.vector.VectorStyle
import com.aichat.sandbox.data.vector.edit.ControlPoint
import com.aichat.sandbox.data.vector.edit.EditAnchor
import com.aichat.sandbox.data.vector.edit.EditSubpath
import com.aichat.sandbox.data.vector.edit.EditablePath
import kotlin.math.cos
import kotlin.math.sin

/** Shared geometry builders for the Phase 2 boolean-module tests. */
internal object BoolTestShapes {

    /** Cubic-circle handle ratio (4-segment circle approximation). */
    private const val KAPPA = 0.5522847498307936f

    fun polygonCircle(cx: Float, cy: Float, r: Float, n: Int = 64): Ring {
        val pts = ArrayList<VectorPoint>(n)
        for (i in 0 until n) {
            val a = 2.0 * Math.PI * i / n
            pts += VectorPoint((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat())
        }
        return Ring(pts)
    }

    /** Axis-aligned square with corner at ([x],[y]) and side [size] (CCW). */
    fun square(x: Float, y: Float, size: Float): Ring = Ring(
        listOf(
            VectorPoint(x, y),
            VectorPoint(x + size, y),
            VectorPoint(x + size, y + size),
            VectorPoint(x, y + size),
        ),
    )

    fun shape(vararg rings: Ring, rule: FillRule = FillRule.NONZERO): PolyShape =
        PolyShape(rings.toList(), rule)

    /** A figure-eight as one self-intersecting closed ring. */
    fun figureEight(): Ring = Ring(
        listOf(
            VectorPoint(0f, 0f),
            VectorPoint(10f, 10f),
            VectorPoint(0f, 10f),
            VectorPoint(10f, 0f),
        ),
    )

    fun editableCircle(cx: Float, cy: Float, r: Float, id: String = "c", style: VectorStyle = VectorStyle()): EditablePath {
        val k = KAPPA * r
        fun cp(px: Float, py: Float) = ControlPoint(px, py)
        val a = EditAnchor("$id.s0.a0", cx + r, cy, inHandle = cp(cx + r, cy - k), outHandle = cp(cx + r, cy + k))
        val b = EditAnchor("$id.s0.a1", cx, cy + r, inHandle = cp(cx + k, cy + r), outHandle = cp(cx - k, cy + r))
        val c = EditAnchor("$id.s0.a2", cx - r, cy, inHandle = cp(cx - r, cy + k), outHandle = cp(cx - r, cy - k))
        val d = EditAnchor("$id.s0.a3", cx, cy - r, inHandle = cp(cx - k, cy - r), outHandle = cp(cx + k, cy - r))
        return EditablePath(
            pathId = id,
            subpaths = listOf(EditSubpath("$id.s0", listOf(a, b, c, d), closed = true)),
            style = style,
        )
    }

    fun editableRect(x: Float, y: Float, w: Float, h: Float, id: String = "r", style: VectorStyle = VectorStyle()): EditablePath {
        val anchors = listOf(
            EditAnchor("$id.s0.a0", x, y),
            EditAnchor("$id.s0.a1", x + w, y),
            EditAnchor("$id.s0.a2", x + w, y + h),
            EditAnchor("$id.s0.a3", x, y + h),
        )
        return EditablePath(
            pathId = id,
            subpaths = listOf(EditSubpath("$id.s0", anchors, closed = true)),
            style = style,
        )
    }

    /** An open horizontal stroked centerline from (x0,y) to (x1,y). */
    fun editableHLine(x0: Float, x1: Float, y: Float, strokeWidth: Float, id: String = "l"): EditablePath {
        val anchors = listOf(
            EditAnchor("$id.s0.a0", x0, y),
            EditAnchor("$id.s0.a1", x1, y),
        )
        return EditablePath(
            pathId = id,
            subpaths = listOf(EditSubpath("$id.s0", anchors, closed = false)),
            style = VectorStyle(strokeColor = "#000000", strokeWidth = strokeWidth),
        )
    }
}
