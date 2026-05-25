package com.aichat.sandbox.data.vector

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Aggregate diagnostics for a [VectorDocument]. */
data class VectorMetrics(
    val xmlBytes: Int,
    val pathCount: Int,
    val groupCount: Int,
    val commandCount: Int,
    val parsedCommandCount: Int,
    val unsupportedPathCount: Int,
    val estimatedPointCount: Int,
    val colorCounts: Map<String, Int>,
    val strokePathCount: Int,
    val fillPathCount: Int,
    val zeroLengthPathCount: Int,
    val tinySegmentEstimate: Int,
    val duplicateCoordinateEstimate: Int,
    val bounds: VectorBounds?,
    val warnings: List<VectorWarning>,
)

/** Axis-aligned bounds in viewport coordinates. */
data class VectorBounds(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
)

/**
 * Computes the diagnostics that drive the Vector Art Tune-Up workflow: size,
 * structure counts, color usage, geometry bounds, and noise indicators that
 * later phases (faithful optimizer, AI tune-up) use to decide what to simplify.
 *
 * Bounds and the noise estimates are intentionally approximate for Phase 1.
 * Line-like commands (`M/L/H/V`) are handled exactly; curve/arc control and end
 * points are folded into the bounds as a conservative over-estimate. "Tiny" and
 * "duplicate" counts are best-effort scans over consecutive endpoints.
 */
object VectorMetricsAnalyzer {

    /** Endpoints closer than this are treated as duplicate coordinates. */
    private const val DUPLICATE_EPS = 1e-3f

    /** Segments shorter than this (viewport units) are treated as tiny noise. */
    private const val TINY_SEGMENT = 0.5f

    fun analyze(document: VectorDocument, xml: String? = null): VectorMetrics {
        val paths = document.allPaths()
        val groups = document.allGroups()

        var commandCount = 0
        var parsedCommandCount = 0
        var unsupportedPathCount = 0
        var estimatedPointCount = 0
        var strokePathCount = 0
        var fillPathCount = 0
        var zeroLengthPathCount = 0
        var tinySegmentEstimate = 0
        var duplicateCoordinateEstimate = 0
        val colorCounts = LinkedHashMap<String, Int>()

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var sawPoint = false

        for (path in paths) {
            commandCount += countRawCommands(path.pathData)

            val commands = path.commands
            if (commands == null) {
                unsupportedPathCount++
            } else {
                parsedCommandCount += commands.size
                estimatedPointCount += commands.sumOf { pointsIn(it) }

                val traced = trace(commands)
                for (p in traced.allPoints) {
                    minX = min(minX, p.x); minY = min(minY, p.y)
                    maxX = max(maxX, p.x); maxY = max(maxY, p.y)
                    sawPoint = true
                }
                if (isZeroLength(traced.endpoints)) zeroLengthPathCount++
                for (k in 1 until traced.endpoints.size) {
                    val a = traced.endpoints[k - 1]
                    val b = traced.endpoints[k]
                    val d = hypot(b.x - a.x, b.y - a.y)
                    if (d < DUPLICATE_EPS) duplicateCoordinateEstimate++
                    else if (d < TINY_SEGMENT) tinySegmentEstimate++
                }
            }

            path.style.fillColor?.let { c ->
                colorCounts[c] = (colorCounts[c] ?: 0) + 1
                if (!isTransparent(c)) fillPathCount++
            }
            path.style.strokeColor?.let { c ->
                colorCounts[c] = (colorCounts[c] ?: 0) + 1
                if (!isTransparent(c)) strokePathCount++
            }
        }

        val bounds = if (sawPoint) VectorBounds(minX, minY, maxX, maxY) else null
        val xmlBytes = xml?.toByteArray(Charsets.UTF_8)?.size ?: document.originalXmlBytes ?: 0

        return VectorMetrics(
            xmlBytes = xmlBytes,
            pathCount = paths.size,
            groupCount = groups.size,
            commandCount = commandCount,
            parsedCommandCount = parsedCommandCount,
            unsupportedPathCount = unsupportedPathCount,
            estimatedPointCount = estimatedPointCount,
            colorCounts = colorCounts,
            strokePathCount = strokePathCount,
            fillPathCount = fillPathCount,
            zeroLengthPathCount = zeroLengthPathCount,
            tinySegmentEstimate = tinySegmentEstimate,
            duplicateCoordinateEstimate = duplicateCoordinateEstimate,
            bounds = bounds,
            warnings = document.warnings,
        )
    }

    // ---- geometry tracing ----

    private data class Pt(val x: Float, val y: Float)

    private class Traced(val endpoints: List<Pt>, val allPoints: List<Pt>)

    /**
     * Walks [commands] into absolute coordinate points. [endpoints] is the
     * sequence of vertices (segment endpoints) used for noise/length checks;
     * [allPoints] additionally includes curve control points for bounds.
     */
    private fun trace(commands: List<PathCommand>): Traced {
        val endpoints = ArrayList<Pt>()
        val all = ArrayList<Pt>()
        var cx = 0f; var cy = 0f
        var startX = 0f; var startY = 0f

        fun absX(v: Float, rel: Boolean) = if (rel) cx + v else v
        fun absY(v: Float, rel: Boolean) = if (rel) cy + v else v
        fun vertex(x: Float, y: Float) {
            endpoints += Pt(x, y); all += Pt(x, y); cx = x; cy = y
        }

        for (cmd in commands) {
            when (cmd) {
                is PathCommand.MoveTo -> {
                    val x = absX(cmd.x, cmd.relative); val y = absY(cmd.y, cmd.relative)
                    vertex(x, y); startX = x; startY = y
                }
                is PathCommand.LineTo ->
                    vertex(absX(cmd.x, cmd.relative), absY(cmd.y, cmd.relative))
                is PathCommand.HorizontalTo ->
                    vertex(absX(cmd.x, cmd.relative), cy)
                is PathCommand.VerticalTo ->
                    vertex(cx, absY(cmd.y, cmd.relative))
                is PathCommand.CubicTo -> {
                    all += Pt(absX(cmd.x1, cmd.relative), absY(cmd.y1, cmd.relative))
                    all += Pt(absX(cmd.x2, cmd.relative), absY(cmd.y2, cmd.relative))
                    vertex(absX(cmd.x, cmd.relative), absY(cmd.y, cmd.relative))
                }
                is PathCommand.SmoothCubicTo -> {
                    all += Pt(absX(cmd.x2, cmd.relative), absY(cmd.y2, cmd.relative))
                    vertex(absX(cmd.x, cmd.relative), absY(cmd.y, cmd.relative))
                }
                is PathCommand.QuadTo -> {
                    all += Pt(absX(cmd.x1, cmd.relative), absY(cmd.y1, cmd.relative))
                    vertex(absX(cmd.x, cmd.relative), absY(cmd.y, cmd.relative))
                }
                is PathCommand.SmoothQuadTo ->
                    vertex(absX(cmd.x, cmd.relative), absY(cmd.y, cmd.relative))
                is PathCommand.ArcTo ->
                    vertex(absX(cmd.x, cmd.relative), absY(cmd.y, cmd.relative))
                is PathCommand.Close -> {
                    cx = startX; cy = startY
                }
            }
        }
        return Traced(endpoints, all)
    }

    private fun pointsIn(cmd: PathCommand): Int = when (cmd) {
        is PathCommand.MoveTo, is PathCommand.LineTo,
        is PathCommand.HorizontalTo, is PathCommand.VerticalTo,
        is PathCommand.SmoothQuadTo, is PathCommand.ArcTo -> 1
        is PathCommand.SmoothCubicTo, is PathCommand.QuadTo -> 2
        is PathCommand.CubicTo -> 3
        is PathCommand.Close -> 0
    }

    private fun isZeroLength(endpoints: List<Pt>): Boolean {
        if (endpoints.size < 2) return true
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        for (p in endpoints) {
            minX = min(minX, p.x); minY = min(minY, p.y)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y)
        }
        return abs(maxX - minX) < DUPLICATE_EPS && abs(maxY - minY) < DUPLICATE_EPS
    }

    private fun countRawCommands(pathData: String): Int =
        pathData.count { it.isLetter() && it.lowercaseChar() in "mlhvcsqtaz" }

    private fun isTransparent(color: String): Boolean {
        val hex = color.trim().removePrefix("#")
        return when (hex.length) {
            8 -> hex.substring(0, 2).equals("00", ignoreCase = true)
            4 -> hex[0] == '0'
            else -> false
        }
    }
}
