package com.aichat.sandbox.data.ink.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase **I0.7** — drives the throwaway [RenderingFidelitySpike] over its
 * representative corpus, on the headless JVM against ink's `-jvm` artifacts
 * (same setup as [com.aichat.sandbox.data.ink.InkInteropTest]).
 *
 * The real deliverable of this spike is the **printed report + per-tool
 * go/no-go** (captured in `docs/INK_I07_RENDERING_FIDELITY_SPIKE.md`). The
 * assertions here only guard that the harness runs end-to-end — i.e. ink mesh
 * generation succeeds for every corpus stroke and the comparison produces
 * sane, in-range numbers — without pinning ink's internal mesh output, which we
 * intentionally do not own.
 */
class RenderingFidelitySpikeTest {

    @Test
    fun spikeRunsAndEmitsPerToolVerdicts() {
        val report = RenderingFidelitySpike.run()
        val text = RenderingFidelitySpike.format(report)

        // Surface the table in the test log and persist it for the report write-up.
        println(text)
        runCatching {
            val out = File("build/reports/ink-i07/fidelity-report.txt")
            out.parentFile?.mkdirs()
            out.writeText(text)
        }

        // Corpus is in the plan's 50–100 band across exactly the four tools.
        assertEquals(RenderingFidelitySpike.TOOLS.size, report.verdicts.size)
        assertTrue("corpus size ${report.strokes.size}", report.strokes.size in 50..100)

        // Every stroke produced finite, in-range fidelity metrics — proof ink
        // mesh generation succeeded headless for the whole corpus (no crash,
        // no empty/NaN coverage).
        for (r in report.strokes) {
            assertTrue("coverageIoU ${r.shape}/${r.tool}", r.coverageIoU in 0f..1f)
            assertTrue("bboxIoU ${r.shape}/${r.tool}", r.bboxIoU in 0f..1f)
            assertTrue("centroidDrift ${r.shape}/${r.tool}", r.centroidDrift in 0f..2f)
        }

        // Sanity floor: the pen — the simplest round stock brush — must land its
        // footprint broadly on the current engine's, confirming the InkInterop
        // size/family mapping is fundamentally aligned (not a brush mismatch).
        val pen = report.verdicts.first { it.tool == "pen" }
        assertTrue("pen meanCoverageIoU ${pen.meanCoverageIoU}", pen.meanCoverageIoU > 0.3f)
        assertTrue("pen meanBboxIoU ${pen.meanBboxIoU}", pen.meanBboxIoU > 0.6f)
    }
}
