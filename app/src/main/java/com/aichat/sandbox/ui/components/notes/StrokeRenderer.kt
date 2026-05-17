package com.aichat.sandbox.ui.components.notes

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * Pure rendering math + shared stroke draw routine (sub-phase 1.4).
 *
 * The math helpers ([pressureCurve], [tiltFactor], [widthAt]) are free of
 * Android APIs so they can be unit-tested on the JVM.
 *
 * [drawStrokePath] is shared by live, predicted, and replay rendering so a
 * stroke looks identical on commit as it did while being drawn.
 */
object StrokeRenderer {

    const val TOOL_PEN = "pen"
    const val TOOL_HIGHLIGHTER = "highlighter"
    const val TOOL_PENCIL = "pencil"

    private const val MIN_PRESSURE_GAIN = 0.4f
    private const val PRESSURE_GAIN_RANGE = 0.6f
    private const val PENCIL_TILT_GAIN = 1.5f
    private val HALF_PI = (Math.PI / 2.0).toFloat()

    /**
     * Maps raw stylus pressure (0..1, clamped) to a width multiplier. The floor
     * keeps very light strokes visible; growth is linear from there.
     */
    fun pressureCurve(pressure: Float): Float {
        val clamped = pressure.coerceIn(0f, 1f)
        return MIN_PRESSURE_GAIN + PRESSURE_GAIN_RANGE * clamped
    }

    /**
     * Tilt multiplier. Pencil flattens to a thicker, softer mark as the pen
     * tips over (radians, 0 = upright, π/2 = flat against the screen). Other
     * tools ignore tilt and return 1.0.
     */
    fun tiltFactor(tool: String?, tiltRadians: Float): Float {
        if (tool != TOOL_PENCIL) return 1.0f
        val normalized = (tiltRadians / HALF_PI).coerceIn(0f, 1f)
        return 1.0f + PENCIL_TILT_GAIN * normalized
    }

    /** Final per-sample width: `base * pressureGain * tiltGain`. */
    fun widthAt(baseWidthPx: Float, pressure: Float, tiltRadians: Float, tool: String?): Float =
        baseWidthPx * pressureCurve(pressure) * tiltFactor(tool, tiltRadians)

    /**
     * Draws a stroke with per-segment variable width and quadratic-Bezier
     * smoothing between sample midpoints.
     *
     * `samples` is the packed `[x,y,p,t, x,y,p,t, …]` layout used everywhere
     * else in the notes module; only the first `sampleCount` samples are
     * read. The caller controls paint color / cap / alpha; this function only
     * sets `strokeWidth` per segment.
     */
    fun drawStrokePath(
        canvas: Canvas,
        paint: Paint,
        samples: FloatArray,
        sampleCount: Int,
        baseWidthPx: Float,
        tool: String?,
        scratchPath: Path = Path(),
    ) {
        if (sampleCount < 1) return
        val s = StrokeCodec.FLOATS_PER_SAMPLE
        if (sampleCount == 1) {
            paint.strokeWidth = widthAt(baseWidthPx, samples[2], samples[3], tool)
            canvas.drawPoint(samples[0], samples[1], paint)
            return
        }
        if (sampleCount == 2) {
            paint.strokeWidth = widthAt(baseWidthPx, samples[s + 2], samples[s + 3], tool)
            canvas.drawLine(samples[0], samples[1], samples[s], samples[s + 1], paint)
            return
        }

        // Opening leg: s0 → mid(s0, s1), width from s1.
        scratchPath.reset()
        scratchPath.moveTo(samples[0], samples[1])
        val mid01x = (samples[0] + samples[s]) * 0.5f
        val mid01y = (samples[1] + samples[s + 1]) * 0.5f
        scratchPath.lineTo(mid01x, mid01y)
        paint.strokeWidth = widthAt(baseWidthPx, samples[s + 2], samples[s + 3], tool)
        canvas.drawPath(scratchPath, paint)

        // Middle segments: mid(s_{i-1}, s_i) → quadTo(s_i) → mid(s_i, s_{i+1}).
        for (i in 1 until sampleCount - 1) {
            val pi = (i - 1) * s
            val ci = i * s
            val ni = (i + 1) * s
            val startX = (samples[pi] + samples[ci]) * 0.5f
            val startY = (samples[pi + 1] + samples[ci + 1]) * 0.5f
            val endX = (samples[ci] + samples[ni]) * 0.5f
            val endY = (samples[ci + 1] + samples[ni + 1]) * 0.5f
            scratchPath.reset()
            scratchPath.moveTo(startX, startY)
            scratchPath.quadTo(samples[ci], samples[ci + 1], endX, endY)
            paint.strokeWidth = widthAt(baseWidthPx, samples[ci + 2], samples[ci + 3], tool)
            canvas.drawPath(scratchPath, paint)
        }

        // Closing leg: mid(s_{n-2}, s_{n-1}) → s_{n-1}, width from s_{n-1}.
        val lastI = (sampleCount - 1) * s
        val prevI = (sampleCount - 2) * s
        scratchPath.reset()
        scratchPath.moveTo(
            (samples[prevI] + samples[lastI]) * 0.5f,
            (samples[prevI + 1] + samples[lastI + 1]) * 0.5f,
        )
        scratchPath.lineTo(samples[lastI], samples[lastI + 1])
        paint.strokeWidth = widthAt(baseWidthPx, samples[lastI + 2], samples[lastI + 3], tool)
        canvas.drawPath(scratchPath, paint)
    }
}
