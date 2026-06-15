package com.aichat.sandbox.ui.components.notes

/**
 * Phase I5 — pure-JVM **input smoothing** for the live-beautify flow.
 *
 * AndroidX Ink's authoring path (`InProgressStrokesView`) runs a real-time
 * input modeler that low-passes the raw S-Pen stream before it becomes wet
 * pixels — that smoothing is what makes ink's live line feel clean. That
 * modeler lives in `ink-authoring`, which is **Android-only** (it is not on the
 * unit-test classpath, see `app/build.gradle.kts`), and it only shapes the wet
 * rendering: the finished `Stroke.inputs` ink hands back on pen-lift are the
 * *raw* samples. So to feed "ink-style" smoothing into the [InkBeautifier]
 * clean-snap — on **both** the legacy and the ink-authoring commit paths, and
 * in a form the headless container can actually verify — we re-express the same
 * low-pass as a small, deterministic, pure-JVM pass here.
 *
 * The filter is a binomial (`0.25 / 0.5 / 0.25`) centerline smoother applied for
 * a few iterations. Properties the beautify pipeline and its tests rely on:
 *  - **Endpoint preserving.** Sample 0 and sample n-1 are copied through
 *    untouched every iteration, so the committed stroke still starts and ends
 *    exactly where the pen did.
 *  - **Stride-agnostic.** Every lane (x, y, pressure, tilt, and the v2 codec's
 *    per-sample timestamp) is averaged with the same kernel, so pressure/tilt
 *    de-noise alongside position and a monotone timestamp lane *stays* monotone
 *    (a non-negative convex kernel preserves monotonicity; the fixed endpoints
 *    can only sit at or inside the original range).
 *  - **Shape preserving.** Binomial smoothing only attenuates high-frequency
 *    jitter; it does not pull the path toward its chord, so corners and overall
 *    form survive for the downstream RDP + Chaikin passes to refine.
 *  - **Pure & deterministic.** No Android types, no randomness — the same input
 *    always yields the same output, which is what makes it headless-testable.
 */
object StrokeSmoothing {

    /** Default low-pass iterations used by [InkBeautifier]'s pre-pass. */
    const val DEFAULT_ITERATIONS = 3

    /** Strokes shorter than this have no interior to smooth. */
    private const val MIN_SAMPLES = 3

    /**
     * Low-pass [samples] (packed, [stride] floats per sample, x/y first) for
     * [iterations] binomial passes. Returns a fresh array; the input is never
     * mutated. Strokes with fewer than [MIN_SAMPLES] samples, a non-positive
     * iteration count, or a degenerate stride are returned copied-but-unchanged.
     */
    fun smooth(
        samples: FloatArray,
        stride: Int,
        iterations: Int = DEFAULT_ITERATIONS,
    ): FloatArray {
        require(stride >= 2) { "StrokeSmoothing: stride must include x and y" }
        val count = samples.size / stride
        if (count < MIN_SAMPLES || iterations <= 0) return samples.copyOf()

        var cur = samples.copyOf()
        var next = FloatArray(cur.size)
        repeat(iterations) {
            // Endpoints pass through unchanged.
            System.arraycopy(cur, 0, next, 0, stride)
            System.arraycopy(cur, cur.size - stride, next, next.size - stride, stride)
            for (i in 1 until count - 1) {
                val a = (i - 1) * stride
                val b = i * stride
                val c = (i + 1) * stride
                for (k in 0 until stride) {
                    next[b + k] = cur[a + k] * 0.25f + cur[b + k] * 0.5f + cur[c + k] * 0.25f
                }
            }
            // Swap the working buffers so the next pass reads the fresh result.
            val tmp = cur; cur = next; next = tmp
        }
        return cur
    }
}
