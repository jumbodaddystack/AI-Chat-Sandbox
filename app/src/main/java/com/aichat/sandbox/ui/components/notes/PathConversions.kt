package com.aichat.sandbox.ui.components.notes

import com.aichat.sandbox.data.vector.PathCommand
import com.aichat.sandbox.data.vector.VectorPoint
import com.aichat.sandbox.data.vector.notesbridge.PolylineSimplify
import com.aichat.sandbox.data.vector.trace.CurveFitter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Sub-phase 12.4 — shape→path and stroke→path conversion. Pure JVM.
 *
 * Shapes convert with **exact** anchors (the only approximation is the
 * standard 4-cubic circle, kappa ≈ 0.5523, for ellipses and rounded-rect
 * corners — the same convention every vector tool uses). Strokes run the
 * shared RDP ([PolylineSimplify]) and the Schneider least-squares cubic
 * fit ([CurveFitter]) from the vector lane, then land anchors at the cubic
 * joins — smooth where adjacent tangents agree, corner at real kinks.
 */
object PathConversions {

    /** Circular-arc cubic handle factor (4-segment circle approximation). */
    const val KAPPA: Float = 0.5522848f

    /** RDP tolerance for stroke conversion, in world units. */
    const val STROKE_SIMPLIFY_TOLERANCE: Float = 1.25f

    /** Max least-squares fit error for stroke conversion, in world units. */
    const val STROKE_FIT_MAX_ERROR: Float = 2.0f

    /** Adjacent fitted tangents within this angle merge into a smooth anchor. */
    private const val SMOOTH_ANGLE_RAD: Float = 0.26f // ~15°

    /** Start/end gap below this fraction of the bbox diagonal closes the path. */
    private const val CLOSE_GAP_FRACTION: Float = 0.1f

    /**
     * Convert a decoded shape into path payloads. Every shape maps to one
     * payload except arrows, which yield the shaft plus a closed, filled
     * head (the codec is single-subpath); [strokeColorArgb] fills the head
     * so the converted arrow renders like the original.
     */
    fun fromShape(decoded: ShapeCodec.DecodedShape, strokeColorArgb: Int): List<PathCodec.PathPayload> {
        val fill = decoded.fillArgb
        val style = decoded.strokeStyle
        return when (val shape = decoded.shape) {
            is Shape.Line -> listOf(
                PathCodec.PathPayload(
                    anchors = listOf(
                        PathCodec.Anchor(shape.x0, shape.y0),
                        PathCodec.Anchor(shape.x1, shape.y1),
                    ),
                    closed = false,
                    fillArgb = 0,
                    strokeStyle = style,
                ),
            )
            is Shape.Rect -> listOf(rectToPath(shape, fill, style))
            is Shape.Ellipse -> listOf(ellipseToPath(shape, fill, style))
            is Shape.Polygon -> listOf(
                PathCodec.PathPayload(
                    anchors = buildList {
                        var i = 0
                        while (i < shape.points.size - 1) {
                            add(PathCodec.Anchor(shape.points[i], shape.points[i + 1]))
                            i += 2
                        }
                    },
                    closed = shape.closed,
                    fillArgb = if (shape.closed) fill else 0,
                    strokeStyle = style,
                ),
            )
            is Shape.Arrow -> arrowToPaths(shape, strokeColorArgb, style)
        }
    }

    private fun rectToPath(shape: Shape.Rect, fill: Int, style: Byte): PathCodec.PathPayload {
        val minX = shape.minX; val minY = shape.minY
        val maxX = shape.maxX; val maxY = shape.maxY
        val r = shape.cornerRadius.coerceIn(0f, minOf(maxX - minX, maxY - minY) / 2f)
        if (r <= 0f) {
            return PathCodec.PathPayload(
                anchors = listOf(
                    PathCodec.Anchor(minX, minY),
                    PathCodec.Anchor(maxX, minY),
                    PathCodec.Anchor(maxX, maxY),
                    PathCodec.Anchor(minX, maxY),
                ),
                closed = true,
                fillArgb = fill,
                strokeStyle = style,
            )
        }
        // Rounded rect: two anchors per corner; the corner arc is one cubic
        // with kappa-scaled handles, the straight edges have zero handles.
        val k = KAPPA * r
        val anchors = listOf(
            // Top edge, then clockwise. Arc-entry anchors carry the out
            // handle into the corner; arc-exit anchors carry the in handle.
            PathCodec.Anchor(maxX - r, minY, outDx = k, outDy = 0f, type = PathCodec.TYPE_SMOOTH),
            PathCodec.Anchor(maxX, minY + r, inDx = 0f, inDy = -k, type = PathCodec.TYPE_SMOOTH),
            PathCodec.Anchor(maxX, maxY - r, outDx = 0f, outDy = k, type = PathCodec.TYPE_SMOOTH),
            PathCodec.Anchor(maxX - r, maxY, inDx = k, inDy = 0f, type = PathCodec.TYPE_SMOOTH),
            PathCodec.Anchor(minX + r, maxY, outDx = -k, outDy = 0f, type = PathCodec.TYPE_SMOOTH),
            PathCodec.Anchor(minX, maxY - r, inDx = 0f, inDy = k, type = PathCodec.TYPE_SMOOTH),
            PathCodec.Anchor(minX, minY + r, outDx = 0f, outDy = -k, type = PathCodec.TYPE_SMOOTH),
            PathCodec.Anchor(minX + r, minY, inDx = -k, inDy = 0f, type = PathCodec.TYPE_SMOOTH),
        )
        return PathCodec.PathPayload(anchors, closed = true, fillArgb = fill, strokeStyle = style)
    }

    private fun ellipseToPath(shape: Shape.Ellipse, fill: Int, style: Byte): PathCodec.PathPayload {
        val rx = abs(shape.rx)
        val ry = abs(shape.ry)
        val kx = KAPPA * rx
        val ky = KAPPA * ry
        // Axis-aligned 4-anchor circle approximation, then rotate the whole
        // frame (anchors + handles) and translate to the centre.
        val local = listOf(
            PathCodec.Anchor(rx, 0f, inDx = 0f, inDy = -ky, outDx = 0f, outDy = ky, type = PathCodec.TYPE_SYMMETRIC),
            PathCodec.Anchor(0f, ry, inDx = kx, inDy = 0f, outDx = -kx, outDy = 0f, type = PathCodec.TYPE_SYMMETRIC),
            PathCodec.Anchor(-rx, 0f, inDx = 0f, inDy = ky, outDx = 0f, outDy = -ky, type = PathCodec.TYPE_SYMMETRIC),
            PathCodec.Anchor(0f, -ry, inDx = -kx, inDy = 0f, outDx = kx, outDy = 0f, type = PathCodec.TYPE_SYMMETRIC),
        )
        val c = cos(shape.rotationRad)
        val s = sin(shape.rotationRad)
        val anchors = local.map { a ->
            PathCodec.Anchor(
                x = shape.cx + c * a.x - s * a.y,
                y = shape.cy + s * a.x + c * a.y,
                inDx = c * a.inDx - s * a.inDy,
                inDy = s * a.inDx + c * a.inDy,
                outDx = c * a.outDx - s * a.outDy,
                outDy = s * a.outDx + c * a.outDy,
                type = a.type,
            )
        }
        return PathCodec.PathPayload(anchors, closed = true, fillArgb = fill, strokeStyle = style)
    }

    private fun arrowToPaths(
        shape: Shape.Arrow,
        strokeColorArgb: Int,
        style: Byte,
    ): List<PathCodec.PathPayload> {
        val shaft = PathCodec.PathPayload(
            anchors = listOf(
                PathCodec.Anchor(shape.x0, shape.y0),
                PathCodec.Anchor(shape.x1, shape.y1),
            ),
            closed = false,
            fillArgb = 0,
            strokeStyle = style,
        )
        val dx = shape.x1 - shape.x0
        val dy = shape.y1 - shape.y0
        if (hypot(dx, dy) < 1e-3f) return listOf(shaft)
        // Head triangle mirrors ShapeRenderer.drawArrowhead's 30° wings.
        val angle = kotlin.math.atan2(dy, dx)
        val headSize = shape.headSize.coerceAtLeast(1e-3f)
        val headAngle = (Math.PI / 6.0).toFloat()
        val head = PathCodec.PathPayload(
            anchors = listOf(
                PathCodec.Anchor(shape.x1, shape.y1),
                PathCodec.Anchor(
                    shape.x1 - headSize * cos(angle - headAngle),
                    shape.y1 - headSize * sin(angle - headAngle),
                ),
                PathCodec.Anchor(
                    shape.x1 - headSize * cos(angle + headAngle),
                    shape.y1 - headSize * sin(angle + headAngle),
                ),
            ),
            closed = true,
            fillArgb = strokeColorArgb,
            strokeStyle = ShapeCodec.STROKE_STYLE_SOLID,
        )
        return listOf(shaft, head)
    }

    /**
     * Fit a freehand stroke's packed `[x, y, p, t]` samples to a bezier
     * path. Returns null for strokes too short to fit (< 2 distinct
     * points). [fillArgb] / [strokeStyle] are carried into the payload so
     * a closed conversion can pick up the palette fill if a caller wants.
     */
    fun fromStroke(
        samples: FloatArray,
        sampleCount: Int,
        simplifyTolerance: Float = STROKE_SIMPLIFY_TOLERANCE,
        fitMaxError: Float = STROKE_FIT_MAX_ERROR,
    ): PathCodec.PathPayload? {
        val stride = StrokeCodec.FLOATS_PER_SAMPLE
        if (sampleCount < 2) return null
        val centerline = ArrayList<VectorPoint>(sampleCount)
        for (i in 0 until sampleCount) {
            centerline += VectorPoint(samples[i * stride], samples[i * stride + 1])
        }
        // Closed-loop detection runs on the raw stroke, before any
        // simplification can shrink the gap.
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        for (p in centerline) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        val diagonal = hypot(maxX - minX, maxY - minY)
        val gap = hypot(
            centerline.first().x - centerline.last().x,
            centerline.first().y - centerline.last().y,
        )
        val closed = diagonal > 1e-3f && gap < diagonal * CLOSE_GAP_FRACTION

        var simplified = PolylineSimplify.simplify(centerline, simplifyTolerance)
        if (closed && simplified.size >= 2) {
            // Snap the seam: drop the trailing point so the wrap-around
            // segment closes the loop instead of a duplicated vertex.
            val first = simplified.first()
            val last = simplified.last()
            if (hypot(first.x - last.x, first.y - last.y) < simplifyTolerance * 2f) {
                simplified = simplified.dropLast(1)
            }
        }
        if (simplified.size < 2) return null

        val cubics = CurveFitter.fit(simplified, fitMaxError)
        if (cubics.isEmpty()) return null
        return cubicsToPayload(
            startX = simplified.first().x,
            startY = simplified.first().y,
            cubics = cubics,
            closed = closed,
        )
    }

    /**
     * Phase 15.2 — outline-stroke conversion ("outline stroke" in Figma /
     * Illustrator terms). Builds the pressure-faithful closed outline via
     * [StrokeOutliner], then runs the same RDP + Schneider cubic fit as
     * [fromStroke] so the result is a compact, node-editable path rather
     * than one anchor per outline vertex. Tolerances scale with the stroke
     * width: outline features (end caps, pressure swells) live at the
     * radius scale, so the fixed centerline tolerances would flatten them
     * on thin strokes. Returns null when the stroke is too short.
     */
    fun fromStrokeOutline(
        samples: FloatArray,
        tool: String?,
        baseWidthPx: Float,
    ): PathCodec.PathPayload? {
        val outline = StrokeOutliner.outline(samples, tool, baseWidthPx)
        if (outline.size < 3 * 2) return null
        val points = ArrayList<VectorPoint>(outline.size / 2 + 1)
        var i = 0
        while (i < outline.size) {
            points += VectorPoint(outline[i], outline[i + 1])
            i += 2
        }
        // Close the seam explicitly so the fitted cubic chain ends exactly
        // at the start point — cubicsToPayload(closed = true) folds the
        // final in-handle onto the first anchor.
        points += points.first()
        val tolerance = (baseWidthPx * 0.15f).coerceIn(0.2f, STROKE_SIMPLIFY_TOLERANCE)
        val simplified = PolylineSimplify.simplify(points, tolerance)
        if (simplified.size < 4) return null
        val cubics = CurveFitter.fit(simplified, tolerance * 1.6f)
        if (cubics.isEmpty()) return null
        return cubicsToPayload(
            startX = simplified.first().x,
            startY = simplified.first().y,
            cubics = cubics,
            closed = true,
        )
    }

    /**
     * Convert a fitted `CubicTo` chain into anchors: one anchor per join,
     * smooth where the incoming and outgoing tangents agree within
     * [SMOOTH_ANGLE_RAD], corner otherwise.
     */
    internal fun cubicsToPayload(
        startX: Float,
        startY: Float,
        cubics: List<PathCommand>,
        closed: Boolean,
    ): PathCodec.PathPayload? {
        val segs = cubics.filterIsInstance<PathCommand.CubicTo>()
        if (segs.isEmpty()) return null
        val anchors = ArrayList<PathCodec.Anchor>(segs.size + 1)
        // First anchor: out handle from the first cubic's c1.
        anchors += PathCodec.Anchor(
            x = startX, y = startY,
            outDx = segs[0].x1 - startX, outDy = segs[0].y1 - startY,
        )
        var prevC2x = segs[0].x2
        var prevC2y = segs[0].y2
        var prevEndX = segs[0].x
        var prevEndY = segs[0].y
        for (i in 1 until segs.size) {
            val next = segs[i]
            anchors += joinAnchor(
                x = prevEndX, y = prevEndY,
                inDx = prevC2x - prevEndX, inDy = prevC2y - prevEndY,
                outDx = next.x1 - prevEndX, outDy = next.y1 - prevEndY,
            )
            prevC2x = next.x2; prevC2y = next.y2
            prevEndX = next.x; prevEndY = next.y
        }
        if (closed) {
            // The last cubic ends back at the start; fold its in-handle onto
            // the first anchor instead of appending a duplicate.
            val first = anchors[0]
            anchors[0] = joinAnchor(
                x = first.x, y = first.y,
                inDx = prevC2x - first.x, inDy = prevC2y - first.y,
                outDx = first.outDx, outDy = first.outDy,
            )
        } else {
            anchors += PathCodec.Anchor(
                x = prevEndX, y = prevEndY,
                inDx = prevC2x - prevEndX, inDy = prevC2y - prevEndY,
            )
        }
        return PathCodec.PathPayload(anchors = anchors, closed = closed)
    }

    private fun joinAnchor(
        x: Float, y: Float,
        inDx: Float, inDy: Float,
        outDx: Float, outDy: Float,
    ): PathCodec.Anchor {
        val inLen = hypot(inDx, inDy)
        val outLen = hypot(outDx, outDy)
        val type = if (inLen > 1e-6f && outLen > 1e-6f) {
            // Angle between -in and out: 0 = perfectly smooth.
            val dot = (-inDx * outDx - inDy * outDy) / (inLen * outLen)
            val angle = kotlin.math.acos(dot.coerceIn(-1f, 1f))
            if (angle <= SMOOTH_ANGLE_RAD) PathCodec.TYPE_SMOOTH else PathCodec.TYPE_CORNER
        } else {
            PathCodec.TYPE_CORNER
        }
        return PathCodec.Anchor(x, y, inDx, inDy, outDx, outDy, type)
    }
}
