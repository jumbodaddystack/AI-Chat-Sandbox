package com.aichat.sandbox.data.ink.parity

import com.aichat.sandbox.ui.components.notes.InkBeautifier
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.aichat.sandbox.ui.components.notes.StrokeSmoothing
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Phase **I5 — live beautify** (headless, ink-native slice).
 *
 * The migration plan's N3 promise is that ink's input smoothing makes the
 * one-tap clean "noticeably cleaner than today". The device-side input modeler
 * lives in the Android-only `ink-authoring` module (not on this classpath), so
 * I5 re-expresses it as the pure-JVM [StrokeSmoothing] pass [InkBeautifier]
 * runs. This test closes the loop with the *real* ink engine that **is**
 * available headless (`ink-*-jvm` + `libink.so`): it feeds the raw and the
 * beautified samples through `InkInterop` → an ink [androidx.ink.strokes.Stroke]
 * and measures the **native mesh outline** ink would render for each.
 *
 *  1. ink renders the beautified stroke with a **measurably smoother outline**
 *     (lower total turning) than the raw stroke — the headline N3 win, proven
 *     against ink's own geometry rather than ours.
 *  2. the clean stays **faithful** to the input (it de-noises, it doesn't
 *     redraw the stroke somewhere else).
 *  3. the ghost-preview **decision** offers on noise and declines on a clean
 *     stroke.
 *
 * On-screen appearance (the translucent ghost, the live wet-layer feel) remains
 * the device-only column documented in `docs/INK_I2_PARITY_GATE.md`.
 */
class InkSmoothParityTest {

    private val stride = StrokeCodec.FLOATS_PER_SAMPLE

    /** A gentle arc carrying high-frequency positional jitter, as `[x,y,p,t]*`. */
    private fun noisyArc(n: Int, jitter: Float): FloatArray {
        val out = FloatArray(n * stride)
        for (i in 0 until n) {
            val t = i.toFloat() / (n - 1)
            val baseX = 40f + 320f * t
            val baseY = 200f - 80f * sin(t * Math.PI.toFloat()) // shallow arc
            out[i * stride] = baseX + jitter * sin(i * 2.7f)
            out[i * stride + 1] = baseY + jitter * sin(i * 3.1f + 1.3f)
            out[i * stride + 2] = 0.6f
            out[i * stride + 3] = 0.15f
        }
        return out
    }

    private fun bboxDiag(samples: FloatArray): Float {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until samples.size / stride) {
            val x = samples[i * stride]; val y = samples[i * stride + 1]
            if (x < minX) minX = x; if (y < minY) minY = y
            if (x > maxX) maxX = x; if (y > maxY) maxY = y
        }
        return hypot(maxX - minX, maxY - minY)
    }

    private fun pointToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 1e-12f) return hypot(px - ax, py - ay)
        var u = ((px - ax) * dx + (py - ay) * dy) / lenSq
        if (u < 0f) u = 0f else if (u > 1f) u = 1f
        return hypot(px - (ax + u * dx), py - (ay + u * dy))
    }

    private fun maxDeviation(from: FloatArray, to: FloatArray): Float {
        val nTo = to.size / stride
        var worst = 0f
        for (i in 0 until from.size / stride) {
            val px = from[i * stride]; val py = from[i * stride + 1]
            var best = Float.MAX_VALUE
            for (j in 0 until nTo - 1) {
                val d = pointToSegment(
                    px, py,
                    to[j * stride], to[j * stride + 1],
                    to[(j + 1) * stride], to[(j + 1) * stride + 1],
                )
                if (d < best) best = d
            }
            if (best > worst) worst = best
        }
        return worst
    }

    /**
     * (1) ink renders the beautified stroke with a smoother native outline than
     * the raw stroke — the genuine "ink input smoothing makes it cleaner" claim,
     * measured on ink's own `PartitionedMesh`.
     */
    @Test
    fun beautifiedStrokeRendersSmootherInInk() {
        val raw = noisyArc(64, 8f)
        val beautified = InkBeautifier.beautify(raw, stride)
        val base = InkRenderParityHarness.BASE_WIDTH.getValue("pen")

        val rawTurn = InkRenderParityHarness.inkOutlineTurningSum(raw, "pen", base)
        val beautTurn = InkRenderParityHarness.inkOutlineTurningSum(beautified, "pen", base)
        println("[I5 ink-mesh turning] raw=$rawTurn beautified=$beautTurn ratio=${beautTurn / rawTurn}")

        assertTrue("ink should render a turning sum for both", rawTurn > 0f && beautTurn > 0f)
        assertTrue(
            "ink mesh outline should be smoother after beautify (raw=$rawTurn beautified=$beautTurn)",
            beautTurn < rawTurn * 0.8f,
        )
    }

    /**
     * (2) the clean de-noises without relocating the stroke: every original
     * sample stays close to the beautified centerline (bounded by the jitter
     * envelope, a small fraction of the stroke's size).
     */
    @Test
    fun beautifyStaysFaithfulToInput() {
        val raw = noisyArc(64, 8f)
        val beautified = InkBeautifier.beautify(raw, stride)
        val dev = maxDeviation(raw, beautified)
        val frac = dev / bboxDiag(raw)
        println("[I5 faithfulness] maxDev=$dev diagFrac=$frac")
        assertTrue("beautified should stay near the raw stroke (frac=$frac)", frac < 0.12f)
    }

    /**
     * (3) ghost-preview decision: offer on a jittery stroke, decline on a clean
     * arc the input smoothing barely touches.
     */
    @Test
    fun candidateOffersOnNoiseDeclinesOnClean() {
        assertTrue(
            "noisy stroke should be offered",
            InkBeautifier.candidate(noisyArc(64, 8f), stride).changed,
        )
        // A smooth arc (no jitter) is already clean: pre-smooth + de-noise leave
        // it essentially where it was, so no ghost.
        val clean = StrokeSmoothing.smooth(noisyArc(64, 0f), stride, 4)
        assertFalse(
            "clean arc should not be offered",
            InkBeautifier.candidate(clean, stride).changed,
        )
    }
}
