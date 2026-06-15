package com.aichat.sandbox.data.ink.parity

import com.aichat.sandbox.data.ink.spike.RenderingFidelitySpike
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase **I4 — brush-richness parity** (headless geometry assertions), formerly
 * the I2 taper *gap* fixture.
 *
 * The I2 gate measured ink's stable stock brushes against the current engine and
 * pinned three rendering gaps as explicit I4 work: the pen **pressure taper**
 * (stock `pressurePen` held a constant-width tube), the pencil **tilt-width**
 * (mapped to the plain round marker family, so tilt did nothing), and the
 * highlighter **width** (~0.71× our footprint). I4's stable
 * [com.aichat.sandbox.data.ink.InkBrushFamilies] adapter closes all three with
 * custom `BrushTip` / `BrushBehavior` families, and these tests flip the former
 * "gap" assertions into **parity** assertions:
 *
 *  1. **Pressure taper** now rises with pressure in step with
 *     [com.aichat.sandbox.ui.components.notes.ToolDynamics.pen] (was: provably
 *     flat).
 *  2. **Pencil tilt-width** now broadens with tilt like
 *     [com.aichat.sandbox.ui.components.notes.ToolDynamics.pencil] (was: flat).
 *  3. **Highlighter footprint** now lands on ~1.0× the current engine's area
 *     (was: ~0.71×), and is GO.
 *  4. The per-tool footprint **regression guard** still holds (no NO_GO; the
 *     two lead tools pen/marker stay GO).
 *
 * Jitter, procedural texture, anti-aliasing, and on-screen colour blending
 * remain the device-only half of the gate — see `docs/INK_I2_PARITY_GATE.md`.
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

    /** Interior ink cross-sections (drop cap [InkRenderParityHarness.NO_WIDTH]
     *  reads and the final fifth where ink's end-cap geometry flares). */
    private fun interiorInk(ink: FloatArray): List<Float> {
        val n = ink.size
        val out = ArrayList<Float>()
        for (i in 0 until (n * 4 / 5)) {
            if (ink[i] != InkRenderParityHarness.NO_WIDTH) out.add(ink[i])
        }
        return out
    }

    /** Pearson correlation between two equal-length series. */
    private fun correlation(a: List<Float>, b: List<Float>): Float {
        require(a.size == b.size && a.isNotEmpty())
        val ma = mean(a); val mb = mean(b)
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in a.indices) {
            val xa = (a[i] - ma).toDouble(); val xb = (b[i] - mb).toDouble()
            num += xa * xb; da += xa * xa; db += xb * xb
        }
        return if (da == 0.0 || db == 0.0) 0f else (num / kotlin.math.sqrt(da * db)).toFloat()
    }

    /**
     * **Pen pressure taper is now at parity** (closes I2 gate item 6).
     *
     * Over a 0.05→0.95 pressure ramp the current engine widens the pen ~2.5×;
     * the I4 custom pen family (`NORMALIZED_PRESSURE → SIZE_MULTIPLIER`, range
     * `0.35×–1.15×`, `pressure^0.7` response) now tracks it: ink's local mesh
     * width rises monotonically, ratios ~2.5×, and the two width profiles
     * correlate tightly. (Pre-I4 this test asserted ink was *flat*.)
     */
    @Test
    fun pressureTaperMatchesCurrentEngineAfterI4() {
        val n = 48
        val samples = InkRenderParityHarness.horizontalStroke(n, pressure = { t -> 0.05f + 0.9f * t })
        val base = InkRenderParityHarness.BASE_WIDTH.getValue("pen")
        val cur = InkRenderParityHarness.widthProfileCurrent(samples, "pen", base)
        val ink = InkRenderParityHarness.widthProfileInk(samples, "pen", base)

        // Current engine: a strong, monotonically rising taper (the control).
        assertTrue("current taper ratio ${cur[n - 1] / cur[0]} should be ~2.5×", cur[n - 1] / cur[0] > 2f)

        val inkV = interiorInk(ink)
        assertTrue("enough interior cross-sections (${inkV.size})", inkV.size >= n / 2)

        // ink now tapers: the profile is no longer flat (the I2 gap), it rises.
        val cv = stdOverMean(inkV)
        assertTrue("ink should now taper (cv=$cv), not be flat", cv > 0.15f)
        val inkRatio = inkV.last() / inkV.first()
        assertTrue("ink taper ratio $inkRatio should be ~2.5×, matching current", inkRatio in 1.9f..3.2f)

        // ink width tracks the ToolDynamics curve at the same centerline samples.
        val curV = cur.copyOfRange(0, inkV.size).toList()
        val r = correlation(curV, inkV)
        assertTrue("ink/current taper correlation $r should be high", r > 0.95f)
        println("[I4 pen taper] curRatio=${cur[n - 1] / cur[0]} inkRatio=$inkRatio corr=$r cv=$cv")
    }

    /**
     * **Pencil tilt-width is now at parity** (closes the I0.7 pencil item).
     *
     * Over a 0→~85° tilt ramp the current engine broadens the pencil ~2.3×
     * (`ToolDynamics.pencil` `0.7×–1.6×` via a sine ease). Pre-I4 the pencil was
     * mapped to the round marker family and stayed flat; the I4 pencil family
     * (`TILT_IN_RADIANS → SIZE_MULTIPLIER`) now broadens with tilt and correlates
     * with the current profile.
     */
    @Test
    fun pencilTiltWidthMatchesCurrentEngineAfterI4() {
        val n = 48
        val maxTilt = (Math.PI / 2).toFloat() * 0.95f
        val samples = InkRenderParityHarness.horizontalStroke(
            n, pressure = { 0.6f }, tilt = { t -> maxTilt * t },
        )
        val base = InkRenderParityHarness.BASE_WIDTH.getValue("pencil")
        val cur = InkRenderParityHarness.widthProfileCurrent(samples, "pencil", base)
        val ink = InkRenderParityHarness.widthProfileInk(samples, "pencil", base)

        assertTrue("current pencil broadens with tilt", cur[n - 1] / cur[0] > 1.8f)

        val inkV = interiorInk(ink)
        assertTrue("enough interior cross-sections (${inkV.size})", inkV.size >= n / 2)

        val cv = stdOverMean(inkV)
        assertTrue("ink pencil should now broaden with tilt (cv=$cv), not be flat", cv > 0.1f)
        val inkRatio = inkV.last() / inkV.first()
        assertTrue("ink pencil tilt ratio $inkRatio should rise (>1.6×)", inkRatio > 1.6f)

        val curV = cur.copyOfRange(0, inkV.size).toList()
        val r = correlation(curV, inkV)
        assertTrue("ink/current pencil-tilt correlation $r should be high", r > 0.9f)
        println("[I4 pencil tilt] curRatio=${cur[n - 1] / cur[0]} inkRatio=$inkRatio corr=$r cv=$cv")
    }

    /**
     * **Constant-pressure control** — a flat-pressure stroke stays flat in the
     * current engine (the deterministic baseline the taper test rises above).
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
     * **Highlighter footprint is now calibrated** (closes the I0.7 highlighter
     * item). The stock highlighter covered ~0.71× our area; the I4 round
     * highlighter family lands on ~1.0×, and its per-tool verdict is GO.
     */
    @Test
    fun highlighterFootprintMatchesAfterI4() {
        val report = RenderingFidelitySpike.run()
        val hl = report.verdicts.first { it.tool == "highlighter" }
        println("[I4 highlighter] meanAreaRatio=${hl.meanAreaRatio} meanCov=${hl.meanCoverageIoU} -> ${hl.decision}")
        assertTrue(
            "highlighter area ratio ${hl.meanAreaRatio} should be ~1.0 (was ~0.71)",
            hl.meanAreaRatio in 0.85f..1.20f,
        )
        assertTrue(
            "highlighter should be GO after I4 calibration (was ${hl.decision})",
            hl.decision == RenderingFidelitySpike.Decision.GO,
        )
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
