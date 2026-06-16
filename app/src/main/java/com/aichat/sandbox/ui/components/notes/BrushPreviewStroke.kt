package com.aichat.sandbox.ui.components.notes

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import com.aichat.sandbox.data.model.BrushPreset
import com.aichat.sandbox.data.notes.BrushSpec
import kotlin.math.abs
import kotlin.math.sin

/**
 * Phase 4 (N1) — one sample point along the deterministic brush preview stroke:
 * a position plus the per-point [widthPx] and [alpha] the brush would paint
 * there. Pure data so the geometry can be unit tested without a Compose host.
 */
data class BrushPreviewSample(
    val x: Float,
    val y: Float,
    val widthPx: Float,
    val alpha: Float,
)

/**
 * Compute a **deterministic** sample stroke for [spec] inside a [width]×[height]
 * box. The path is a gentle left-to-right sine wave; per-point width folds the
 * base width together with the taper ramps, the pressure-curve profile, and a
 * seeded (index-derived, *not* time- or RNG-derived) jitter so the same spec
 * always renders the same swatch. [alpha] carries the brush opacity, dimmed at
 * the tapered ends so a fade reads even on a thin stroke.
 *
 * Kept free of Compose/Android types so it is JVM unit-testable.
 */
fun brushPreviewSamples(
    spec: BrushSpec,
    width: Float,
    height: Float,
    count: Int = DEFAULT_PREVIEW_SAMPLES,
): List<BrushPreviewSample> {
    if (count < 2 || width <= 0f || height <= 0f) return emptyList()
    val marginX = width * 0.08f
    val usableW = (width - marginX * 2f).coerceAtLeast(1f)
    val midY = height / 2f
    // Keep the wave inside the box even at the widest point of the stroke.
    val amplitude = ((height / 2f) - spec.baseWidthPx / 2f - 1f).coerceAtLeast(0f)
    val out = ArrayList<BrushPreviewSample>(count)
    for (i in 0 until count) {
        val t = i.toFloat() / (count - 1)
        val x = marginX + t * usableW
        val y = midY + amplitude * sin(t * TWO_PI * WAVES)
        val taper = taperFactor(t, spec.taperStart, spec.taperEnd)
        val pressure = pressureFactor(t, spec.pressureCurveId)
        val jitter = jitterFactor(i, spec.jitter)
        val w = (spec.baseWidthPx * taper * pressure * jitter).coerceAtLeast(0f)
        // Fade alpha at the ends too so taper is legible on hairline brushes.
        val a = (spec.opacity * (0.35f + 0.65f * taper)).coerceIn(0f, 1f)
        out += BrushPreviewSample(x = x, y = y, widthPx = w, alpha = a)
    }
    return out
}

/**
 * Ramp from 0→1 across the leading [start] fraction and 1→0 across the trailing
 * [end] fraction; flat at 1 in between. With both at 0 the factor is a constant 1.
 */
private fun taperFactor(t: Float, start: Float, end: Float): Float {
    val rampIn = if (start > 0f) (t / start).coerceIn(0f, 1f) else 1f
    val rampOut = if (end > 0f) ((1f - t) / end).coerceIn(0f, 1f) else 1f
    return rampIn * rampOut
}

/**
 * A subtle width modulation (∈ [0.55, 1.0]) that makes the four pressure curves
 * visibly different in the swatch without overpowering the taper: linear is
 * flat, ease-in swells toward the end, ease-out toward the start, and
 * ease-in-out bulges in the middle.
 */
private fun pressureFactor(t: Float, curveId: String): Float {
    val p = when (curveId) {
        BrushPreset.CURVE_EASE_IN -> t
        BrushPreset.CURVE_EASE_OUT -> 1f - t
        BrushPreset.CURVE_EASE_IN_OUT -> 1f - abs(2f * t - 1f)
        else -> 1f // LINEAR / unknown
    }
    return 0.55f + 0.45f * p
}

/**
 * Deterministic per-sample width wobble in ±([amount]·0.5) around 1, derived
 * from the sample [index] via a cheap integer hash. No [java.util.Random] so the
 * swatch is stable across recompositions and reproducible in tests.
 */
private fun jitterFactor(index: Int, amount: Float): Float {
    if (amount <= 0f) return 1f
    // Hash the index into [0,1) deterministically.
    val h = (index * 2654435761.toInt()) and 0x7FFFFFFF
    val unit = (h % 1000) / 1000f // [0,1)
    return 1f + amount * (unit - 0.5f)
}

/**
 * Render [spec] as a deterministic sample stroke. Consecutive [brushPreviewSamples]
 * are drawn as round-capped segments whose width/alpha is the average of their
 * endpoints, giving a smooth variable-width line. Texture is not simulated here
 * (shaders live in the live renderer); the panel labels the texture separately.
 */
@Composable
fun BrushPreviewStroke(
    spec: BrushSpec,
    modifier: Modifier = Modifier,
) {
    val color = Color(spec.colorArgb)
    Canvas(modifier = modifier) {
        val samples = brushPreviewSamples(spec, size.width, size.height)
        if (samples.size < 2) return@Canvas
        for (i in 0 until samples.size - 1) {
            val a = samples[i]
            val b = samples[i + 1]
            val w = ((a.widthPx + b.widthPx) / 2f).coerceAtLeast(0.4f)
            val alpha = ((a.alpha + b.alpha) / 2f).coerceIn(0f, 1f)
            drawLine(
                color = color.copy(alpha = alpha),
                start = Offset(a.x, a.y),
                end = Offset(b.x, b.y),
                strokeWidth = w,
                cap = StrokeCap.Round,
            )
        }
    }
}

private const val DEFAULT_PREVIEW_SAMPLES = 64
private const val WAVES = 1.25f
private const val TWO_PI = (2.0 * Math.PI).toFloat()
