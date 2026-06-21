package com.aichat.sandbox.data.ink

import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.aichat.sandbox.ui.components.notes.StrokeRenderer

/** Representative pressure/tilt samples used by ink parity tests. */
object InkToolSampleFixtures {
    data class Fixture(
        val tool: String,
        val baseWidthPx: Float,
        val colorArgb: Int,
        val opacity: Float,
        val samples: FloatArray,
    ) {
        val payload: ByteArray get() = StrokeCodec.encode(samples)
    }

    val pen = Fixture(
        tool = StrokeRenderer.TOOL_PEN,
        baseWidthPx = 4f,
        colorArgb = 0xFF202020.toInt(),
        opacity = 1f,
        samples = horizontal(48, pressure = { t -> 0.05f + 0.9f * t }, tilt = { 0.12f }),
    )

    val pencil = Fixture(
        tool = StrokeRenderer.TOOL_PENCIL,
        baseWidthPx = 6f,
        colorArgb = 0xFF333333.toInt(),
        opacity = 1f,
        samples = horizontal(
            48,
            pressure = { t -> 0.15f + 0.75f * (1f - kotlin.math.abs(2f * t - 1f)) },
            tilt = { t -> ((Math.PI / 2.0).toFloat() * 0.95f) * t },
        ),
    )

    val highlighter = Fixture(
        tool = StrokeRenderer.TOOL_HIGHLIGHTER,
        baseWidthPx = 24f,
        colorArgb = 0xFFFFFF00.toInt(),
        opacity = 0.35f,
        samples = horizontal(48, pressure = { t -> 0.1f + 0.8f * t }, tilt = { t -> ((Math.PI / 2.0).toFloat() * 0.8f) * t }),
    )

    val marker = Fixture(
        tool = StrokeRenderer.TOOL_MARKER,
        baseWidthPx = 10f,
        colorArgb = 0xFF111111.toInt(),
        opacity = 1f,
        samples = horizontal(48, pressure = { t -> 0.05f + 0.9f * t }, tilt = { 0.2f }),
    )

    val all: List<Fixture> = listOf(pen, pencil, highlighter, marker)

    private fun horizontal(n: Int, pressure: (Float) -> Float, tilt: (Float) -> Float): FloatArray {
        val out = FloatArray(n * StrokeCodec.FLOATS_PER_SAMPLE)
        for (i in 0 until n) {
            val t = if (n <= 1) 0f else i.toFloat() / (n - 1)
            val b = i * StrokeCodec.FLOATS_PER_SAMPLE
            out[b] = 40f + 320f * t
            out[b + 1] = 200f
            out[b + 2] = pressure(t).coerceIn(0f, 1f)
            out[b + 3] = tilt(t).coerceIn(0f, (Math.PI / 2.0).toFloat())
        }
        return out
    }
}
