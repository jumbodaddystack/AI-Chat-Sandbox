package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * Phase I5 — pins the pure-JVM input-smoothing low-pass that feeds the beautify
 * clean-snap: endpoint preservation, jitter attenuation, sample-count and
 * monotone-time invariance, the short-stroke no-op, and determinism.
 */
class StrokeSmoothingTest {

    private val stride = StrokeCodec.FLOATS_PER_SAMPLE

    private fun noisyLine(count: Int, jitter: Float): FloatArray {
        val out = FloatArray(count * stride)
        for (i in 0 until count) {
            out[i * stride] = i * 10f
            out[i * stride + 1] = jitter * sin(i * 2.4f)
            out[i * stride + 2] = 0.5f
            out[i * stride + 3] = 0.1f
        }
        return out
    }

    private fun maxAbsY(samples: FloatArray, str: Int): Float {
        var worst = 0f
        for (i in 0 until samples.size / str) worst = maxOf(worst, abs(samples[i * str + 1]))
        return worst
    }

    /** Max |y| over interior samples only — endpoints are fixed, so including
     *  them masks how much the smoother attenuates the jitter it can touch. */
    private fun maxAbsYInterior(samples: FloatArray, str: Int): Float {
        var worst = 0f
        for (i in 1 until samples.size / str - 1) worst = maxOf(worst, abs(samples[i * str + 1]))
        return worst
    }

    @Test
    fun endpointsArePreserved() {
        val input = noisyLine(40, 3f)
        val out = StrokeSmoothing.smooth(input, stride)
        assertEquals(input[0], out[0], 0f)
        assertEquals(input[1], out[1], 0f)
        assertEquals(input[input.size - stride], out[out.size - stride], 0f)
        assertEquals(input[input.size - stride + 1], out[out.size - stride + 1], 0f)
    }

    @Test
    fun sampleCountIsUnchanged() {
        val input = noisyLine(40, 3f)
        val out = StrokeSmoothing.smooth(input, stride)
        assertEquals(input.size, out.size)
    }

    @Test
    fun jitterIsAttenuated() {
        val input = noisyLine(60, 4f)
        val out = StrokeSmoothing.smooth(input, stride)
        val before = maxAbsY(input, stride)
        val after = maxAbsY(out, stride)
        assertTrue("expected jitter to shrink (before=$before after=$after)", after < before * 0.6f)
    }

    @Test
    fun moreIterationsSmoothMore() {
        val input = noisyLine(60, 4f)
        val light = maxAbsYInterior(StrokeSmoothing.smooth(input, stride, 1), stride)
        val heavy = maxAbsYInterior(StrokeSmoothing.smooth(input, stride, 6), stride)
        assertTrue("more passes should attenuate further (1=$light 6=$heavy)", heavy < light)
    }

    @Test
    fun doesNotMutateInput() {
        val input = noisyLine(40, 3f)
        val copy = input.copyOf()
        StrokeSmoothing.smooth(input, stride)
        assertArrayEquals("input must not be mutated", copy, input, 0f)
    }

    @Test
    fun strideFiveKeepsTimestampsMonotone() {
        val str = StrokeCodec.FLOATS_PER_SAMPLE_V2
        val count = 50
        val input = FloatArray(count * str)
        for (i in 0 until count) {
            input[i * str] = i * 8f
            input[i * str + 1] = 2.5f * sin(i * 1.9f)
            input[i * str + 2] = 0.6f
            input[i * str + 3] = 0.2f
            input[i * str + 4] = i * 4f // strictly increasing t (ms)
        }
        val out = StrokeSmoothing.smooth(input, str)
        var prevT = -Float.MAX_VALUE
        for (i in 0 until out.size / str) {
            val t = out[i * str + 4]
            assertTrue("t lane must stay monotone at sample $i (t=$t prev=$prevT)", t >= prevT)
            prevT = t
        }
        // Endpoints pass through, so the time span is identical.
        assertEquals(0f, out[4], 0f)
        assertEquals((count - 1) * 4f, out[out.size - str + 4], 0f)
    }

    @Test
    fun shortStrokeReturnsCopyUnchanged() {
        val input = noisyLine(2, 2f)
        val out = StrokeSmoothing.smooth(input, stride)
        assertArrayEquals(input, out, 0f)
        assertTrue("must return a copy, not the same instance", out !== input)
    }

    @Test
    fun isDeterministic() {
        val input = noisyLine(70, 3f)
        val a = StrokeSmoothing.smooth(input.copyOf(), stride)
        val b = StrokeSmoothing.smooth(input.copyOf(), stride)
        assertArrayEquals(a, b, 0f)
    }
}
