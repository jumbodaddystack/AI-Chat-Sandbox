package com.aichat.sandbox.data.vector.notesbridge

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.vector.PathCommand
import com.aichat.sandbox.data.vector.VectorDocument
import com.aichat.sandbox.data.vector.VectorFill
import com.aichat.sandbox.data.vector.VectorGroup
import com.aichat.sandbox.data.vector.VectorNode
import com.aichat.sandbox.data.vector.VectorPath
import com.aichat.sandbox.ui.components.notes.FillStyle
import com.aichat.sandbox.ui.components.notes.PathCodec
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phase 16.2 — the reverse bridge: a parsed [VectorDocument] (VectorDrawable
 * XML or SVG, via the tune-up lane's parsers) → editable notes path items.
 *
 * [NoteVectorBridge] goes notes → document; this goes the other way for the
 * Icons gallery's import flow. The conversion is **exact** for the common
 * icon grammar (M/L/H/V/C/S/Q/T/Z — quads lift to cubics, arcs convert via
 * the standard endpoint→center parameterization in ≤ 90° cubic spans), and
 * group transforms compose into an affine baked into each path's anchors.
 * Multi-subpath path data lands as one multi-subpath [PathCodec.PathPayload]
 * (16.1) with the source's fill rule, so icons with holes import correctly.
 *
 * Pure Kotlin (no Android imports) — the whole conversion is JVM-testable.
 */
object DocumentToNoteItems {

    /** Matches the editor's icon artboard seed (24-px grid × 32 world/px). */
    const val DEFAULT_ARTBOARD_WORLD = 768f

    data class Result(
        val items: List<NoteItem>,
        /** Human-readable conversion warnings (skipped paths, fallbacks). */
        val warnings: List<String>,
    )

    /**
     * Convert every path in [document] into note path items for [noteId],
     * uniformly scaled and centered into an [artboardWorld]² artboard.
     * Parser warnings on the document are NOT repeated here — the caller
     * already has them; [Result.warnings] covers conversion-time losses.
     */
    fun convert(
        document: VectorDocument,
        noteId: String,
        artboardWorld: Float = DEFAULT_ARTBOARD_WORLD,
    ): Result {
        val warnings = ArrayList<String>()
        val vw = document.viewport.viewportWidth.takeIf { it > 0f } ?: 24f
        val vh = document.viewport.viewportHeight.takeIf { it > 0f } ?: 24f
        val scale = artboardWorld / max(vw, vh)
        // Viewport → artboard: uniform scale, centered.
        val viewportMap = floatArrayOf(
            scale, 0f, (artboardWorld - vw * scale) / 2f,
            0f, scale, (artboardWorld - vh * scale) / 2f,
        )

        val items = ArrayList<NoteItem>()
        var z = 0
        fun walk(group: VectorGroup, parent: FloatArray) {
            val m = multiply(parent, groupMatrix(group))
            for (child in group.children) {
                when (child) {
                    is VectorNode.GroupNode -> walk(child.group, m)
                    is VectorNode.PathNode -> {
                        val item = convertPath(child.path, m, noteId, z, scale, warnings)
                        if (item != null) {
                            items += item
                            z++
                        }
                    }
                    is VectorNode.InstanceNode ->
                        warnings += "Unresolved symbol instance '${child.id}' skipped"
                }
            }
        }
        walk(document.root, viewportMap)
        return Result(items, warnings)
    }

    // ── per-path conversion ────────────────────────────────────────────

    private fun convertPath(
        path: VectorPath,
        m: FloatArray,
        noteId: String,
        zIndex: Int,
        scale: Float,
        warnings: MutableList<String>,
    ): NoteItem? {
        val commands = path.commands
        if (commands.isNullOrEmpty()) {
            warnings += "Path '${path.id}' has unparsable data and was skipped"
            return null
        }
        val subpaths = normalize(commands).mapNotNull { sub -> sub.toSubpath(m) }
        if (subpaths.isEmpty()) return null

        val style = path.style
        val fillArgb: Int
        var gradient: FillStyle.Gradient? = null
        when (val fill = style.fill) {
            is VectorFill.Solid -> fillArgb = parseColor(fill.color, fill.alpha, warnings, path.id)
            is VectorFill.Linear, is VectorFill.Radial -> {
                fillArgb = 0
                gradient = gradientOf(fill, m, subpaths, warnings, path.id)
            }
            is VectorFill.Sweep -> {
                warnings += "Path '${path.id}': sweep gradient flattened to its first stop"
                fillArgb = parseColor(
                    fill.stops.firstOrNull()?.color ?: "#FF000000", null, warnings, path.id,
                )
            }
            null -> fillArgb = style.fillColor
                ?.let { parseColor(it, style.fillAlpha, warnings, path.id) } ?: 0
        }
        // Gradient fills need a non-zero fill flag for the renderer's filled
        // branch — FillStyle gradients ride alongside fillArgb in the codec.
        val effectiveFill = if (gradient != null && fillArgb == 0) 0x01000000 else fillArgb

        val strokeColor = style.strokeColor
            ?.let { parseColor(it, style.strokeAlpha, warnings, path.id) }
        val payload = PathCodec.PathPayload(
            subpaths = subpaths,
            fillRule = if (style.fillType.equals("evenOdd", ignoreCase = true)) {
                PathCodec.FILL_RULE_EVEN_ODD
            } else {
                PathCodec.FILL_RULE_NON_ZERO
            },
            fillArgb = effectiveFill,
            capJoin = PathCodec.capJoinOf(
                when (style.strokeLineCap?.lowercase()) {
                    "butt" -> PathCodec.CAP_BUTT
                    "square" -> PathCodec.CAP_SQUARE
                    else -> PathCodec.CAP_ROUND
                },
                when (style.strokeLineJoin?.lowercase()) {
                    "miter" -> PathCodec.JOIN_MITER
                    "bevel" -> PathCodec.JOIN_BEVEL
                    else -> PathCodec.JOIN_ROUND
                },
            ),
            gradient = gradient,
        )
        return NoteItem(
            noteId = noteId,
            zIndex = zIndex,
            kind = PathCodec.KIND,
            tool = null,
            // Fill-only paths stroke hairline in their own fill colour
            // (invisible), mirroring the outline-ink convention.
            colorArgb = strokeColor ?: effectiveFill,
            baseWidthPx = if (strokeColor != null) {
                max(0f, (style.strokeWidth ?: 1f) * scale)
            } else {
                0f
            },
            payload = PathCodec.encode(payload),
        )
    }

    // ── command normalization → absolute cubics ────────────────────────

    private class NormSubpath(val startX: Float, val startY: Float) {
        /** Packed cubics: `[c1x,c1y, c2x,c2y, x,y]` per segment. */
        val cubics = ArrayList<Float>()
        var closed = false

        fun add(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) {
            cubics.add(c1x); cubics.add(c1y)
            cubics.add(c2x); cubics.add(c2y)
            cubics.add(x); cubics.add(y)
        }

        fun lineTo(fromX: Float, fromY: Float, x: Float, y: Float) =
            add(fromX, fromY, x, y, x, y) // degenerate cubic = zero handles

        /** Map through [m] and land as a codec subpath (null when degenerate). */
        fun toSubpath(m: FloatArray): PathCodec.Subpath? {
            fun mx(x: Float, y: Float) = m[0] * x + m[1] * y + m[2]
            fun my(x: Float, y: Float) = m[3] * x + m[4] * y + m[5]
            val segCount = cubics.size / 6
            if (segCount == 0) return null
            val anchors = ArrayList<PathCodec.Anchor>(segCount + 1)
            var px = mx(startX, startY)
            var py = my(startX, startY)
            anchors += PathCodec.Anchor(
                x = px, y = py,
                outDx = mx(cubics[0], cubics[1]) - px,
                outDy = my(cubics[0], cubics[1]) - py,
            )
            for (i in 0 until segCount) {
                val base = i * 6
                val c2x = mx(cubics[base + 2], cubics[base + 3])
                val c2y = my(cubics[base + 2], cubics[base + 3])
                val ex = mx(cubics[base + 4], cubics[base + 5])
                val ey = my(cubics[base + 4], cubics[base + 5])
                if (i == segCount - 1) {
                    val first = anchors[0]
                    if (closed && hypot(ex - first.x, ey - first.y) < 1e-3f) {
                        // Final segment returns to the start: fold its
                        // in-handle onto the first anchor instead of
                        // appending a duplicate.
                        anchors[0] = first.copy(inDx = c2x - first.x, inDy = c2y - first.y)
                    } else {
                        anchors += PathCodec.Anchor(
                            x = ex, y = ey, inDx = c2x - ex, inDy = c2y - ey,
                        )
                    }
                } else {
                    val next = (i + 1) * 6
                    val n1x = mx(cubics[next], cubics[next + 1])
                    val n1y = my(cubics[next], cubics[next + 1])
                    anchors += PathCodec.Anchor(
                        x = ex, y = ey,
                        inDx = c2x - ex, inDy = c2y - ey,
                        outDx = n1x - ex, outDy = n1y - ey,
                    )
                }
                px = ex
                py = ey
            }
            if (anchors.size < 2) return null
            return PathCodec.Subpath(anchors = anchors, closed = closed)
        }
    }

    /**
     * Resolve relative coordinates, H/V shorthands, smooth-reflection
     * controls, quads (2/3 lift) and arcs (≤ 90° cubic spans) into absolute
     * cubic chains, split at MoveTos.
     */
    private fun normalize(commands: List<PathCommand>): List<NormSubpath> {
        val out = ArrayList<NormSubpath>()
        var sub: NormSubpath? = null
        var cx = 0f
        var cy = 0f
        var startX = 0f
        var startY = 0f
        // Reflection state for S/T.
        var prevC2x = 0f; var prevC2y = 0f; var prevWasCubic = false
        var prevQx = 0f; var prevQy = 0f; var prevWasQuad = false

        fun open(x: Float, y: Float) {
            sub?.takeIf { it.cubics.isNotEmpty() }?.let { out += it }
            sub = NormSubpath(x, y)
            startX = x; startY = y
            cx = x; cy = y
        }

        fun require(): NormSubpath {
            if (sub == null) open(cx, cy)
            return sub!!
        }

        for (cmd in commands) {
            var wasCubic = false
            var wasQuad = false
            when (cmd) {
                is PathCommand.MoveTo -> {
                    val x = if (cmd.relative) cx + cmd.x else cmd.x
                    val y = if (cmd.relative) cy + cmd.y else cmd.y
                    open(x, y)
                }
                is PathCommand.LineTo -> {
                    val x = if (cmd.relative) cx + cmd.x else cmd.x
                    val y = if (cmd.relative) cy + cmd.y else cmd.y
                    require().lineTo(cx, cy, x, y)
                    cx = x; cy = y
                }
                is PathCommand.HorizontalTo -> {
                    val x = if (cmd.relative) cx + cmd.x else cmd.x
                    require().lineTo(cx, cy, x, cy)
                    cx = x
                }
                is PathCommand.VerticalTo -> {
                    val y = if (cmd.relative) cy + cmd.y else cmd.y
                    require().lineTo(cx, cy, cx, y)
                    cy = y
                }
                is PathCommand.CubicTo -> {
                    val c1x = if (cmd.relative) cx + cmd.x1 else cmd.x1
                    val c1y = if (cmd.relative) cy + cmd.y1 else cmd.y1
                    val c2x = if (cmd.relative) cx + cmd.x2 else cmd.x2
                    val c2y = if (cmd.relative) cy + cmd.y2 else cmd.y2
                    val x = if (cmd.relative) cx + cmd.x else cmd.x
                    val y = if (cmd.relative) cy + cmd.y else cmd.y
                    require().add(c1x, c1y, c2x, c2y, x, y)
                    prevC2x = c2x; prevC2y = c2y; wasCubic = true
                    cx = x; cy = y
                }
                is PathCommand.SmoothCubicTo -> {
                    val c1x = if (prevWasCubic) 2 * cx - prevC2x else cx
                    val c1y = if (prevWasCubic) 2 * cy - prevC2y else cy
                    val c2x = if (cmd.relative) cx + cmd.x2 else cmd.x2
                    val c2y = if (cmd.relative) cy + cmd.y2 else cmd.y2
                    val x = if (cmd.relative) cx + cmd.x else cmd.x
                    val y = if (cmd.relative) cy + cmd.y else cmd.y
                    require().add(c1x, c1y, c2x, c2y, x, y)
                    prevC2x = c2x; prevC2y = c2y; wasCubic = true
                    cx = x; cy = y
                }
                is PathCommand.QuadTo -> {
                    val qx = if (cmd.relative) cx + cmd.x1 else cmd.x1
                    val qy = if (cmd.relative) cy + cmd.y1 else cmd.y1
                    val x = if (cmd.relative) cx + cmd.x else cmd.x
                    val y = if (cmd.relative) cy + cmd.y else cmd.y
                    addQuadAsCubic(require(), cx, cy, qx, qy, x, y)
                    prevQx = qx; prevQy = qy; wasQuad = true
                    cx = x; cy = y
                }
                is PathCommand.SmoothQuadTo -> {
                    val qx = if (prevWasQuad) 2 * cx - prevQx else cx
                    val qy = if (prevWasQuad) 2 * cy - prevQy else cy
                    val x = if (cmd.relative) cx + cmd.x else cmd.x
                    val y = if (cmd.relative) cy + cmd.y else cmd.y
                    addQuadAsCubic(require(), cx, cy, qx, qy, x, y)
                    prevQx = qx; prevQy = qy; wasQuad = true
                    cx = x; cy = y
                }
                is PathCommand.ArcTo -> {
                    val x = if (cmd.relative) cx + cmd.x else cmd.x
                    val y = if (cmd.relative) cy + cmd.y else cmd.y
                    arcToCubics(
                        require(), cx, cy, x, y,
                        cmd.rx, cmd.ry, cmd.xAxisRotation, cmd.largeArc, cmd.sweep,
                    )
                    cx = x; cy = y
                }
                is PathCommand.Close -> {
                    sub?.let {
                        if (hypot(cx - startX, cy - startY) > 1e-3f) {
                            // Z's implicit straight closing edge, made
                            // explicit so the wrap-around segment matches.
                            it.lineTo(cx, cy, startX, startY)
                        }
                        it.closed = true
                        out += it
                    }
                    sub = null
                    cx = startX; cy = startY
                }
            }
            prevWasCubic = wasCubic
            prevWasQuad = wasQuad
        }
        sub?.takeIf { it.cubics.isNotEmpty() }?.let { out += it }
        return out
    }

    /** Quadratic → cubic, exact (the standard 2/3 control lift). */
    private fun addQuadAsCubic(
        sub: NormSubpath,
        x0: Float, y0: Float,
        qx: Float, qy: Float,
        x: Float, y: Float,
    ) {
        sub.add(
            x0 + 2f / 3f * (qx - x0), y0 + 2f / 3f * (qy - y0),
            x + 2f / 3f * (qx - x), y + 2f / 3f * (qy - y),
            x, y,
        )
    }

    /**
     * SVG/VectorDrawable elliptical arc → cubic spans (≤ 90° each) via the
     * spec's endpoint → center conversion (F.6.5/F.6.6), including the
     * radius scale-up for unreachable endpoints.
     */
    private fun arcToCubics(
        sub: NormSubpath,
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        rxIn: Float, ryIn: Float,
        xAxisRotationDeg: Float,
        largeArc: Boolean,
        sweep: Boolean,
    ) {
        if (hypot(x1 - x0, y1 - y0) < 1e-6f) return
        var rx = abs(rxIn)
        var ry = abs(ryIn)
        if (rx < 1e-6f || ry < 1e-6f) {
            sub.lineTo(x0, y0, x1, y1)
            return
        }
        val phi = Math.toRadians(xAxisRotationDeg.toDouble())
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)
        // F.6.5 step 1: midpoint frame.
        val dx2 = (x0 - x1) / 2.0
        val dy2 = (y0 - y1) / 2.0
        val x1p = cosPhi * dx2 + sinPhi * dy2
        val y1p = -sinPhi * dx2 + cosPhi * dy2
        // F.6.6: scale radii up if the endpoints are unreachable.
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1.0) {
            val s = sqrt(lambda).toFloat()
            rx *= s
            ry *= s
        }
        // F.6.5 step 2: center in the prime frame.
        val rxSq = rx.toDouble() * rx
        val rySq = ry.toDouble() * ry
        var radicand = (rxSq * rySq - rxSq * y1p * y1p - rySq * x1p * x1p) /
            (rxSq * y1p * y1p + rySq * x1p * x1p)
        if (radicand < 0.0) radicand = 0.0
        val coef = (if (largeArc != sweep) 1.0 else -1.0) * sqrt(radicand)
        val cxp = coef * (rx * y1p / ry)
        val cyp = coef * (-ry * x1p / rx)
        // Step 3: back to the user frame.
        val cxA = cosPhi * cxp - sinPhi * cyp + (x0 + x1) / 2.0
        val cyA = sinPhi * cxp + cosPhi * cyp + (y0 + y1) / 2.0
        // Step 4: start and sweep angles.
        fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy
            val len = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
            var a = acos((dot / len).coerceIn(-1.0, 1.0))
            if (ux * vy - uy * vx < 0) a = -a
            return a
        }
        val startAngle = angle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var sweepAngle = angle(
            (x1p - cxp) / rx, (y1p - cyp) / ry,
            (-x1p - cxp) / rx, (-y1p - cyp) / ry,
        )
        if (!sweep && sweepAngle > 0) sweepAngle -= 2 * Math.PI
        if (sweep && sweepAngle < 0) sweepAngle += 2 * Math.PI
        // Split into ≤ 90° cubic spans (standard tangent-length formula).
        val spans = max(1, ceil(abs(sweepAngle) / (Math.PI / 2.0)).toInt())
        val delta = sweepAngle / spans
        val t = 4.0 / 3.0 * Math.tan(delta / 4.0)
        var theta = startAngle
        var px = x0.toDouble()
        var py = y0.toDouble()
        for (i in 0 until spans) {
            val theta2 = theta + delta
            fun pointAt(a: Double): Pair<Double, Double> {
                val ex = cxA + rx * cos(a) * cosPhi - ry * sin(a) * sinPhi
                val ey = cyA + rx * cos(a) * sinPhi + ry * sin(a) * cosPhi
                return ex to ey
            }
            fun derivAt(a: Double): Pair<Double, Double> {
                val dx = -rx * sin(a) * cosPhi - ry * cos(a) * sinPhi
                val dy = -rx * sin(a) * sinPhi + ry * cos(a) * cosPhi
                return dx to dy
            }
            val (ex, ey) = pointAt(theta2)
            val (d1x, d1y) = derivAt(theta)
            val (d2x, d2y) = derivAt(theta2)
            sub.add(
                (px + t * d1x).toFloat(), (py + t * d1y).toFloat(),
                (ex - t * d2x).toFloat(), (ey - t * d2y).toFloat(),
                ex.toFloat(), ey.toFloat(),
            )
            px = ex
            py = ey
            theta = theta2
        }
    }

    // ── transforms ─────────────────────────────────────────────────────

    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)

    /**
     * VectorDrawable group matrix:
     * `T(translate + pivot) · R(rotation) · S(scale) · T(−pivot)` —
     * matching the framework's `VGroup.updateLocalMatrix` order.
     */
    private fun groupMatrix(group: VectorGroup): FloatArray {
        val px = group.pivotX ?: 0f
        val py = group.pivotY ?: 0f
        val sx = group.scaleX ?: 1f
        val sy = group.scaleY ?: 1f
        val tx = group.translateX ?: 0f
        val ty = group.translateY ?: 0f
        val rot = Math.toRadians((group.rotation ?: 0f).toDouble())
        val c = cos(rot).toFloat()
        val s = sin(rot).toFloat()
        if (px == 0f && py == 0f && sx == 1f && sy == 1f && tx == 0f && ty == 0f &&
            group.rotation == null
        ) {
            return IDENTITY
        }
        var m = floatArrayOf(1f, 0f, -px, 0f, 1f, -py)
        m = multiply(floatArrayOf(sx, 0f, 0f, 0f, sy, 0f), m)
        m = multiply(floatArrayOf(c, -s, 0f, s, c, 0f), m)
        m = multiply(floatArrayOf(1f, 0f, tx + px, 0f, 1f, ty + py), m)
        return m
    }

    /** `a · b` for `[m0,m1,tx, m3,m4,ty]` affines (apply [b] first). */
    private fun multiply(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[0] * b[0] + a[1] * b[3],
        a[0] * b[1] + a[1] * b[4],
        a[0] * b[2] + a[1] * b[5] + a[2],
        a[3] * b[0] + a[4] * b[3],
        a[3] * b[1] + a[4] * b[4],
        a[3] * b[2] + a[4] * b[5] + a[5],
    )

    // ── style helpers ──────────────────────────────────────────────────

    /** `#RGB` / `#ARGB` / `#RRGGBB` / `#AARRGGBB` → ARGB int ([alpha] multiplies). */
    private fun parseColor(
        color: String,
        alpha: Float?,
        warnings: MutableList<String>,
        pathId: String,
    ): Int {
        val hex = color.trim().removePrefix("#")
        val argb = when (hex.length) {
            3 -> {
                val r = hex[0].digitToIntOrNull(16) ?: return warnAndBlack(warnings, pathId, color)
                val g = hex[1].digitToIntOrNull(16) ?: return warnAndBlack(warnings, pathId, color)
                val b = hex[2].digitToIntOrNull(16) ?: return warnAndBlack(warnings, pathId, color)
                0xFF000000.toInt() or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
            }
            4 -> {
                val a = hex[0].digitToIntOrNull(16) ?: return warnAndBlack(warnings, pathId, color)
                val r = hex[1].digitToIntOrNull(16) ?: return warnAndBlack(warnings, pathId, color)
                val g = hex[2].digitToIntOrNull(16) ?: return warnAndBlack(warnings, pathId, color)
                val b = hex[3].digitToIntOrNull(16) ?: return warnAndBlack(warnings, pathId, color)
                (a * 17 shl 24) or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
            }
            6 -> hex.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
                ?: return warnAndBlack(warnings, pathId, color)
            8 -> hex.toLongOrNull(16)?.toInt()
                ?: return warnAndBlack(warnings, pathId, color)
            else -> return warnAndBlack(warnings, pathId, color)
        }
        if (alpha == null) return argb
        val a = (((argb ushr 24) and 0xFF) * alpha.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0xFFFFFF)
    }

    private fun warnAndBlack(warnings: MutableList<String>, pathId: String, color: String): Int {
        warnings += "Path '$pathId': unsupported colour '$color', using black"
        return 0xFF000000.toInt()
    }

    /**
     * [VectorFill] gradient (viewport user-space units) → the notes
     * [FillStyle.Gradient] (bounds-normalized): map the gradient points
     * through [m], then normalize against the converted geometry's bounds.
     */
    private fun gradientOf(
        fill: VectorFill,
        m: FloatArray,
        subpaths: List<PathCodec.Subpath>,
        warnings: MutableList<String>,
        pathId: String,
    ): FillStyle.Gradient? {
        val bounds = PathCodec.boundsOf(
            PathCodec.PathPayload(subpaths = subpaths),
        ) ?: return null
        val w = max(1e-3f, bounds[2] - bounds[0])
        val h = max(1e-3f, bounds[3] - bounds[1])
        fun nx(x: Float, y: Float) = ((m[0] * x + m[1] * y + m[2]) - bounds[0]) / w
        fun ny(x: Float, y: Float) = ((m[3] * x + m[4] * y + m[5]) - bounds[1]) / h
        fun stops(raw: List<com.aichat.sandbox.data.vector.GradientStop>) = raw.map {
            FillStyle.Stop(it.offset.coerceIn(0f, 1f), parseColor(it.color, null, warnings, pathId))
        }
        return when (fill) {
            is VectorFill.Linear -> FillStyle.Gradient(
                type = FillStyle.TYPE_LINEAR,
                x0 = nx(fill.x1, fill.y1), y0 = ny(fill.x1, fill.y1),
                x1 = nx(fill.x2, fill.y2), y1 = ny(fill.x2, fill.y2),
                stops = stops(fill.stops),
            )
            is VectorFill.Radial -> FillStyle.Gradient(
                type = FillStyle.TYPE_RADIAL,
                x0 = nx(fill.cx, fill.cy), y0 = ny(fill.cx, fill.cy),
                // Notes radial gradients store the radius in x1 (bounds
                // fraction); scale by the larger axis.
                x1 = (fill.radius * abs(m[0])) / max(w, h),
                y1 = 0f,
                stops = stops(fill.stops),
            )
            else -> null
        }
    }
}
