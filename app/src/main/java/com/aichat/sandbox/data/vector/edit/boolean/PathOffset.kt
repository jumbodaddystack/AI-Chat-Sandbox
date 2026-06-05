package com.aichat.sandbox.data.vector.edit.boolean

import kotlin.math.abs

/**
 * Phase 2 — signed offset (inset/outset) of a filled [PolyShape].
 *
 * Implemented as morphology, reusing the clipper and outliner instead of
 * hand-rolling a self-intersecting offset polygon:
 *
 *  - **Grow** (`delta > 0`) = dilation = `shape ∪ band`, where `band` is the stroke
 *    outline of every contour at width `2·delta` (the outer half of the band is the
 *    grown rim; round joins give the Minkowski-with-disk result).
 *  - **Shrink** (`delta < 0`) = erosion = `shape − band` at width `2·|delta|` (remove
 *    the inner rim). A strong-enough negative delta removes everything and yields an
 *    empty shape, so the caller can decline the op rather than emit a degenerate path.
 *
 * The clipper's self-cleaning union/difference resolves any self-intersections that
 * appear on concave turns for free.
 */
internal object PathOffset {

    fun offset(shape: PolyShape, delta: Float, join: StrokeOutliner.LineJoin): PolyShape {
        if (delta == 0f) return shape
        if (shape.isEmpty) return PolyShape.EMPTY
        val band = boundaryBand(shape, abs(delta) * 2f, join)
        if (band.isEmpty) return shape
        return if (delta > 0f) {
            PolygonClipper.clip(shape, band, BoolOp.UNION)
        } else {
            PolygonClipper.clip(shape, band, BoolOp.DIFFERENCE)
        }
    }

    /** Union of the stroke outline of every contour, straddling the boundary. */
    private fun boundaryBand(shape: PolyShape, width: Float, join: StrokeOutliner.LineJoin): PolyShape {
        var acc: PolyShape = PolyShape.EMPTY
        for (ring in shape.rings) {
            if (ring.points.size < 3) continue
            val band = StrokeOutliner.outline(
                centerline = ring,
                closed = true,
                width = width,
                cap = StrokeOutliner.LineCap.BUTT,
                join = join,
                miterLimit = 4f,
            )
            acc = if (acc.isEmpty) band else PolygonClipper.clip(acc, band, BoolOp.UNION)
        }
        return acc
    }
}
