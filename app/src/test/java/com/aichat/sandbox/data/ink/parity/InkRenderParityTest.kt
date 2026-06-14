package com.aichat.sandbox.data.ink.parity

import com.aichat.sandbox.data.ink.spike.RenderingFidelitySpike
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase **I2 — rendering parity gate** (headless geometry assertions).
 *
 * Two complementary checks, both reproducible on the headless JVM:
 *
 *  1. **Taper / width-profile parity** ([InkRenderParityHarness]) — ink's local
 *     mesh thickness must rise along a pressure-taper stroke the same way the
 *     current [com.aichat.sandbox.ui.components.notes.ToolDynamics] curve does.
 *     This is the part of "match `StrokeRenderer`'s variable-width Bézier
 *     output" that taper actually exercises, which the I0.7 footprint spike did
 *     not measure.
 *  2. **Footprint regression guard** — the I0.7 per-tool coverage verdicts are
 *     promoted from a throwaway spike to a *permanent* gate assertion: if the
 *     [com.aichat.sandbox.data.ink.InkInterop] brush mapping ever regresses (a
 *     stock family / size change that moves ink's footprint off the current
 *     engine), the verdicts drop and this test fails.
 *
 * Jitter, texture, anti-aliasing, and on-screen colour blending remain the
 * device-only half of the gate — see `docs/INK_I2_PARITY_GATE.md`.
 */
class InkRenderParityTest {

    private fun mean(a: List<Float>): Float = if (a.isEmpty()) 0f else a.sum() / a.size

    private fun stdOverMean(a: List<Float>): Float {
        if (a.isEmpty()) return 0f
        val m = mean(a)
        if (m == 0f) return 0f
        var v = 0.0
        for (x in a) v += (x - m).toDouble() * (x - m)
        return (kotlin.math.sqrt(v / a.size) / m).toFloat()
    }

    /**
     * **Documents the taper-parity gap that gates ink default-on.**
     *
     * The current engine renders a strong pressure taper — a linear 0.05→0.95
     * pressure ramp widens it ~2.5× along its length, exactly the variable-width
     * Bézier output the gate must match. ink's *stable stock* `pressurePen`,
     * mapped through the current [InkRenderParityHarness] / `InkInterop` seam,
     * does **not**: its mesh holds an essentially constant `size`-width tube.
     *
     * That is expected — `InkInterop` maps only the stable brush identity
     * (family + colour + size); pressure-curve/taper need a custom
     * `BrushTip`/`BrushBehavior` and are an explicit **I4** item. This test pins
     * the gap so it can't be mistaken for parity: until I4 closes it, ink's
     * pressure rendering is not at parity and the default stays off.
     */
    @Test
    fun pressureTaperIsCurrentEngineOnlyUntilI4() {
        val n = 48
        val samples = InkRenderParityHarness.horizontalStroke(n, pressure = { t -> 0.05f + 0.9f * t })
        val base = InkRenderParityHarness.BASE_WIDTH.getValue("pen")
        val cur = InkRenderParityHarness.widthProfileCurrent(samples, "pen", base)
        val ink = InkRenderParityHarness.widthProfileInk(samples, "pen", base)

        // Current engine: a strong, monotonically rising taper.
        for (i in 1 until n) {
            assertTrue("current width non-decreasing at $i", cur[i] >= cur[i - 1] - 1e-3f)
        }
        assertTrue("current taper ratio ${cur[n - 1] / cur[0]} should be ~2.5×", cur[n - 1] / cur[0] > 2f)

        // ink interior cross-sections (drop cap NO_WIDTH reads), excluding the
        // final fifth where ink's end-cap geometry flares.
        val inkV = ArrayList<Float>()
        for (i in 0 until (n * 4 / 5)) {
            if (ink[i] != InkRenderParityHarness.NO_WIDTH) inkV.add(ink[i])
        }
        assertTrue("enough interior cross-sections (${inkV.size})", inkV.size >= n / 2)

        // ink stock pen is essentially flat (no pressure taper) — the gap.
        val cv = stdOverMean(inkV)
        assertTrue("ink stock pen should be ~flat (cv=$cv), taper is an I4 item", cv < 0.05f)
        assertTrue("ink width ~brush size, not tapering", kotlin.math.abs(mean(inkV) - base) < base * 0.1f)
    }

    /**
     * A constant-pressure stroke should have an essentially flat width profile
     * in the current engine — the deterministic control for the taper test.
     */
    @Test
    fun constantPressureProfileIsFlat() {
        val n = 40
        val samples = InkRenderParityHarness.horizontalStroke(n, pressure = { 0.6f })
        val cur = InkRenderParityHarness.widthProfileCurrent(samples, "pen", 4f)
        val avg = cur.average().toFloat()
        for (i in 0 until n) {
            assertTrue("current width flat at $i", kotlin.math.abs(cur[i] - avg) < 1e-3f)
        }
    }

    /**
     * Permanent regression guard over the I0.7 coverage corpus: no tool may
     * regress to NO_GO, and the two tools the migration leads with (pen, marker)
     * must stay GO. Pins the *gate decision*, not ink's internal mesh.
     */
    @Test
    fun footprintVerdictsHoldAsPermanentGate() {
        val report = RenderingFidelitySpike.run()
        val text = RenderingFidelitySpike.format(report)
        println(text)
        runCatching {
            val out = File("build/reports/ink-i2/parity-coverage.txt")
            out.parentFile?.mkdirs()
            out.writeText(text)
        }

        for (v in report.verdicts) {
            assertTrue(
                "${v.tool} regressed to NO_GO (meanCov=${v.meanCoverageIoU}, meanBbox=${v.meanBboxIoU})",
                v.decision != RenderingFidelitySpike.Decision.NO_GO,
            )
        }
        for (tool in listOf("pen", "marker")) {
            val v = report.verdicts.first { it.tool == tool }
            assertTrue(
                "$tool must stay GO (was ${v.decision})",
                v.decision == RenderingFidelitySpike.Decision.GO,
            )
        }
    }
}
