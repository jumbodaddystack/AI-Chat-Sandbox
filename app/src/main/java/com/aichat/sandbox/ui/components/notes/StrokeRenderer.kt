package com.aichat.sandbox.ui.components.notes

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.random.Random

/**
 * Pure rendering math + shared stroke draw routine (sub-phase 1.4, extended
 * for the per-tool paint configuration in sub-phase 1.6).
 *
 * The math helpers ([pressureCurve], [tiltFactor], [widthAt], [alphaAt]) are
 * free of Android APIs so they can be unit-tested on the JVM.
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
    private const val HIGHLIGHTER_ALPHA = 76     // ~30%
    private const val PENCIL_BASE_ALPHA = 200    // grain handles the rest
    private const val PENCIL_TILT_ALPHA_DROP = 0.5f
    private val HALF_PI = (Math.PI / 2.0).toFloat()

    /** Lazily-built 64x64 tileable noise bitmap shared by every pencil paint. */
    @Volatile private var pencilShader: BitmapShader? = null

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
     * Per-sample alpha multiplier. Pencil fades as the pen tips over (the
     * mark widens via [tiltFactor] but with thinner pigment); other tools
     * use the caller's [baseAlpha] unchanged.
     */
    fun alphaAt(baseAlpha: Int, tool: String?, tiltRadians: Float): Int {
        if (tool != TOOL_PENCIL) return baseAlpha
        val normalized = (tiltRadians / HALF_PI).coerceIn(0f, 1f)
        val factor = 1f - PENCIL_TILT_ALPHA_DROP * normalized
        return (baseAlpha * factor).toInt().coerceIn(0, 255)
    }

    /**
     * Configure [paint] for a freshly issued stroke of [tool] with [colorArgb].
     * Sets cap, color, alpha, blend, and (for pencil) the shared grain shader.
     * Caller still owns `strokeWidth` — [drawStrokePath] sets that per segment.
     */
    fun configureToolPaint(paint: Paint, tool: String?, colorArgb: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.isAntiAlias = true
        paint.color = colorArgb
        when (tool) {
            TOOL_HIGHLIGHTER -> {
                paint.strokeCap = Paint.Cap.SQUARE
                paint.shader = null
                // Force ~30% alpha regardless of the colour's own alpha so the
                // user's underlying ink stays visible through a highlight.
                paint.alpha = HIGHLIGHTER_ALPHA
            }
            TOOL_PENCIL -> {
                paint.shader = obtainPencilShader()
                paint.alpha = PENCIL_BASE_ALPHA
            }
            else -> {
                paint.shader = null
                paint.alpha = Color.alpha(colorArgb)
            }
        }
    }

    private fun obtainPencilShader(): BitmapShader {
        pencilShader?.let { return it }
        synchronized(this) {
            pencilShader?.let { return it }
            val bmp = buildPencilNoiseBitmap()
            val shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            pencilShader = shader
            return shader
        }
    }

    /**
     * Deterministic 64×64 noise tile. A fixed seed keeps every pencil stroke
     * grain-stable across launches without committing a binary asset to res/.
     */
    private fun buildPencilNoiseBitmap(): Bitmap {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val pixels = ByteArray(size * size)
        val rng = Random(0xCAFEBABEL)
        for (i in pixels.indices) {
            // Bias toward "ink leaves grain" — most cells partially mask the stroke.
            val v = rng.nextInt(120, 256)
            pixels[i] = v.toByte()
        }
        val buffer = java.nio.ByteBuffer.wrap(pixels)
        bmp.copyPixelsFromBuffer(buffer)
        return bmp
    }

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
        // Save and restore alpha so pencil's per-segment modulation doesn't
        // leak into the caller's paint between strokes.
        val baseAlpha = paint.alpha
        try {
            if (sampleCount == 1) {
                paint.strokeWidth = widthAt(baseWidthPx, samples[2], samples[3], tool)
                paint.alpha = alphaAt(baseAlpha, tool, samples[3])
                canvas.drawPoint(samples[0], samples[1], paint)
                return
            }
            if (sampleCount == 2) {
                paint.strokeWidth = widthAt(baseWidthPx, samples[s + 2], samples[s + 3], tool)
                paint.alpha = alphaAt(baseAlpha, tool, samples[s + 3])
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
            paint.alpha = alphaAt(baseAlpha, tool, samples[s + 3])
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
                paint.alpha = alphaAt(baseAlpha, tool, samples[ci + 3])
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
            paint.alpha = alphaAt(baseAlpha, tool, samples[lastI + 3])
            canvas.drawPath(scratchPath, paint)
        } finally {
            paint.alpha = baseAlpha
        }
    }
}
