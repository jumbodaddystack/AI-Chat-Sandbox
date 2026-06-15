package com.aichat.sandbox.ui.components.notes

import com.aichat.sandbox.data.vector.VectorPoint
import com.aichat.sandbox.data.vector.notesbridge.PolylineSimplify
import kotlin.math.hypot

/**
 * Sub-phase 14.1 / phase **I5** — ink beautification on pen lift.
 *
 * The clean-snap pipeline is now three stages:
 *  1. an **ink-style input-smoothing low-pass** ([StrokeSmoothing]) that
 *     attenuates S-Pen jitter the way ink-authoring's device-side input modeler
 *     does (phase I5 — see that class for why the modeler itself can't run
 *     headless), so the de-noise that follows works on a clean centerline;
 *  2. an RDP de-noise step that drops the now-redundant interior samples; and
 *  3. the same Chaikin corner-cutting "Clean up" applies.
 *
 * Pure and stride-agnostic: samples arrive as a packed float array whose
 * first two lanes are x/y and whose remaining lanes (pressure, tilt, and the
 * v2 codec's per-sample timestamp) interpolate alongside them, so both
 * [StrokeCodec] layouts beautify identically.
 *
 * Phase I5 also makes beautify a **candidate**, not an in-place mutation: the
 * pen-lift commits the raw stroke and [candidate] produces a ghost the user can
 * tap to accept (the accept swaps raw → beautified as one undoable edit). The
 * [Candidate.changed] flag lets the surface skip offering a ghost for strokes
 * the clean wouldn't visibly alter (already-straight lines, dots, ticks), so
 * the preview only appears when it earns its place. Whether accepted via the
 * ghost or applied directly, the result is always a canonical [StrokeCodec]
 * payload — nothing downstream ever sees ink (Adoption principle 2).
 */
object InkBeautifier {

    /** Chaikin iterations — matches the local Clean-up pass. */
    private const val SMOOTH_ITERATIONS = 2

    /** Input-smoothing low-pass iterations run before de-noise (phase I5). */
    private const val PRE_SMOOTH_ITERATIONS = StrokeSmoothing.DEFAULT_ITERATIONS

    /** RDP tolerance as a fraction of the stroke's bbox diagonal. */
    private const val DENOISE_TOLERANCE_FRACTION = 0.005f

    /** Strokes shorter than this keep their raw samples (dots, ticks). */
    private const val MIN_SAMPLES = 6

    /** Output cap — mirrors [EditPreviewController]'s smooth cap. */
    private const val MAX_SAMPLES = 1024

    /**
     * Below this max-deviation (as a fraction of the stroke's bbox diagonal)
     * the beautified candidate is judged indistinguishable from the raw stroke,
     * so the ghost preview is not offered ([Candidate.changed] = false). Roughly
     * "the clean moved no point by more than 1.5% of the stroke's size".
     */
    private const val OFFER_THRESHOLD_FRACTION = 0.015f

    /**
     * The result of beautifying a stroke: the cleaned [samples] (packed, same
     * stride as the input) and whether the clean is visibly [changed] enough to
     * be worth offering as a ghost preview.
     */
    data class Candidate(val samples: FloatArray, val changed: Boolean) {
        override fun equals(other: Any?): Boolean =
            other is Candidate && changed == other.changed && samples.contentEquals(other.samples)

        override fun hashCode(): Int = 31 * samples.contentHashCode() + changed.hashCode()
    }

    /**
     * Beautify [samples] (packed, [stride] floats per sample, x/y first).
     * Returns the input array unchanged when the stroke is too short to
     * meaningfully smooth. Equivalent to `candidate(samples, stride).samples`.
     */
    fun beautify(samples: FloatArray, stride: Int): FloatArray =
        candidate(samples, stride).samples

    /**
     * Beautify [samples] and report whether the result differs enough from the
     * raw stroke to surface as a ghost preview. Short strokes (dots, ticks)
     * return the input untouched and `changed = false`.
     */
    fun candidate(samples: FloatArray, stride: Int): Candidate {
        require(stride >= 2) { "InkBeautifier: stride must include x and y" }
        val count = samples.size / stride
        if (count < MIN_SAMPLES) return Candidate(samples, changed = false)
        // 1 — ink-style input smoothing, then 2 — RDP de-noise, then 3 — Chaikin.
        val smoothed = StrokeSmoothing.smooth(samples, stride, PRE_SMOOTH_ITERATIONS)
        var out = denoise(smoothed, stride, smoothed.size / stride)
        repeat(SMOOTH_ITERATIONS) {
            if (out.size / stride >= MAX_SAMPLES) return@repeat
            out = chaikin(out, stride)
        }
        return Candidate(out, changed = isWorthOffering(samples, out, stride))
    }

    /**
     * Decide whether [beautified] visibly departs from [raw]: the largest
     * distance from any original sample to the nearest point on the beautified
     * polyline, normalised by the raw stroke's bbox diagonal, must clear
     * [OFFER_THRESHOLD_FRACTION]. This keeps the ghost from nagging on strokes
     * the clean barely touches.
     */
    private fun isWorthOffering(raw: FloatArray, beautified: FloatArray, stride: Int): Boolean {
        val rawCount = raw.size / stride
        val beautCount = beautified.size / stride
        if (rawCount < 2 || beautCount < 2) return false
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until rawCount) {
            val x = raw[i * stride]; val y = raw[i * stride + 1]
            if (x < minX) minX = x; if (y < minY) minY = y
            if (x > maxX) maxX = x; if (y > maxY) maxY = y
        }
        val diag = hypot(maxX - minX, maxY - minY)
        if (diag <= 0f) return false
        var maxDev = 0f
        for (i in 0 until rawCount) {
            val d = distanceToPolyline(raw[i * stride], raw[i * stride + 1], beautified, stride, beautCount)
            if (d > maxDev) maxDev = d
        }
        return maxDev / diag > OFFER_THRESHOLD_FRACTION
    }

    /** Shortest distance from (`px`,`py`) to the polyline packed in [poly]. */
    private fun distanceToPolyline(px: Float, py: Float, poly: FloatArray, stride: Int, count: Int): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until count - 1) {
            val d = pointToSegment(
                px, py,
                poly[i * stride], poly[i * stride + 1],
                poly[(i + 1) * stride], poly[(i + 1) * stride + 1],
            )
            if (d < best) best = d
        }
        return best
    }

    private fun pointToSegment(
        px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float,
    ): Float {
        val dx = bx - ax; val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 1e-12f) return hypot(px - ax, py - ay)
        var t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        if (t < 0f) t = 0f else if (t > 1f) t = 1f
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /**
     * RDP on the x/y centerline, keeping every lane of the surviving
     * samples — the same shape as [EditPreviewController.simplifyStroke],
     * with tolerance scaled to the stroke's own bbox diagonal so zoom level
     * and stroke size don't change the outcome.
     */
    private fun denoise(samples: FloatArray, stride: Int, count: Int): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until count) {
            val x = samples[i * stride]
            val y = samples[i * stride + 1]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        val tolerance = hypot(maxX - minX, maxY - minY) * DENOISE_TOLERANCE_FRACTION
        if (tolerance <= 0f) return samples
        val centerline = ArrayList<VectorPoint>(count)
        for (i in 0 until count) {
            centerline += VectorPoint(samples[i * stride], samples[i * stride + 1])
        }
        val keep = PolylineSimplify.keepMask(centerline, tolerance)
        val kept = (0 until count).count { keep[it] }
        if (kept == count) return samples
        val out = FloatArray(kept * stride)
        var w = 0
        for (i in 0 until count) {
            if (!keep[i]) continue
            System.arraycopy(samples, i * stride, out, w * stride, stride)
            w++
        }
        return out
    }

    /**
     * One Chaikin corner-cutting pass — numerically identical to the
     * Clean-up implementation: interior segments split 0.75/0.25, original
     * endpoints preserved, every lane interpolated uniformly (which keeps
     * monotone timestamp lanes monotone).
     */
    private fun chaikin(samples: FloatArray, stride: Int): FloatArray {
        val count = samples.size / stride
        if (count < 2) return samples
        val out = FloatArray((2 * (count - 1)) * stride)
        var write = 0
        for (i in 0 until count - 1) {
            val a = i * stride
            val b = (i + 1) * stride
            for (k in 0 until stride) {
                out[write + k] = samples[a + k] * 0.75f + samples[b + k] * 0.25f
                out[write + stride + k] = samples[a + k] * 0.25f + samples[b + k] * 0.75f
            }
            write += 2 * stride
        }
        for (k in 0 until stride) {
            out[k] = samples[k]
            out[out.size - stride + k] = samples[samples.size - stride + k]
        }
        return out
    }
}
