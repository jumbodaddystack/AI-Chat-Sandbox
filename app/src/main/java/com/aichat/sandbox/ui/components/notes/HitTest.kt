package com.aichat.sandbox.ui.components.notes

import kotlin.math.max
import kotlin.math.min

/**
 * Pure hit-test helpers for stroke geometry (sub-phase 1.6).
 *
 * Operates directly on the packed `[x, y, p, t, …]` sample buffers used by
 * [StrokeCodec] so it can be unit-tested on the JVM without an Android
 * runtime. Callers are expected to decode payloads once and reuse the
 * resulting [FloatArray] for repeated queries (e.g. during an eraser swipe).
 */
object HitTest {

    /**
     * 4-element axis-aligned bounding box: `[minX, minY, maxX, maxY]`.
     * Returns `null` for empty sample sets.
     */
    fun boundsOf(samples: FloatArray, sampleCount: Int): FloatArray? {
        if (sampleCount < 1) return null
        val s = StrokeCodec.FLOATS_PER_SAMPLE
        var minX = samples[0]
        var minY = samples[1]
        var maxX = minX
        var maxY = minY
        for (i in 1 until sampleCount) {
            val base = i * s
            val x = samples[base]
            val y = samples[base + 1]
            if (x < minX) minX = x else if (x > maxX) maxX = x
            if (y < minY) minY = y else if (y > maxY) maxY = y
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    /**
     * Cheap pre-filter: does the eraser tip at (`px`,`py`) with `radius` overlap
     * the stroke's bounding box (expanded by the same radius)?
     */
    fun bboxContainsPoint(bounds: FloatArray, px: Float, py: Float, radius: Float): Boolean {
        return px >= bounds[0] - radius && px <= bounds[2] + radius &&
            py >= bounds[1] - radius && py <= bounds[3] + radius
    }

    /**
     * True if (`px`, `py`) is within `radius` of any segment of the stroke.
     *
     * Single-sample strokes are tested as points. Early-outs at the first
     * matching segment.
     */
    fun pointWithinStroke(
        samples: FloatArray,
        sampleCount: Int,
        px: Float,
        py: Float,
        radius: Float,
    ): Boolean {
        if (sampleCount < 1) return false
        val s = StrokeCodec.FLOATS_PER_SAMPLE
        val r2 = radius * radius
        if (sampleCount == 1) {
            val dx = samples[0] - px
            val dy = samples[1] - py
            return dx * dx + dy * dy <= r2
        }
        for (i in 1 until sampleCount) {
            val a = (i - 1) * s
            val b = i * s
            if (pointToSegmentDistanceSquared(
                    px, py,
                    samples[a], samples[a + 1],
                    samples[b], samples[b + 1],
                ) <= r2
            ) {
                return true
            }
        }
        return false
    }

    /** Squared distance from (`px`,`py`) to the closest point on segment `a→b`. */
    private fun pointToSegmentDistanceSquared(
        px: Float, py: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float,
    ): Float {
        val abx = bx - ax
        val aby = by - ay
        val lenSq = abx * abx + aby * aby
        if (lenSq == 0f) {
            val dx = px - ax
            val dy = py - ay
            return dx * dx + dy * dy
        }
        // Project (px,py) onto AB, clamped to [0,1].
        val t = ((px - ax) * abx + (py - ay) * aby) / lenSq
        val tc = min(1f, max(0f, t))
        val cx = ax + tc * abx
        val cy = ay + tc * aby
        val dx = px - cx
        val dy = py - cy
        return dx * dx + dy * dy
    }
}
