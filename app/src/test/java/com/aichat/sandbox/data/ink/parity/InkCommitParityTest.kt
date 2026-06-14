package com.aichat.sandbox.data.ink.parity

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.Stroke
import com.aichat.sandbox.data.ink.InkInterop
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase **I2 — commit-pipeline parity gate** (headless).
 *
 * The authoring path hands a finished ink [Stroke] to `onInkStrokesFinished`,
 * which converts it back to a canonical [StrokeCodec] payload via
 * [InkInterop.fromStroke] and commits it through the *same* listener pipeline a
 * hand-drawn stroke uses. The default-on checklist needs two things proven about
 * that conversion, both verifiable without a device:
 *
 *  1. **Audio timestamp sync** — the two-clock reconciliation must survive the
 *     authoring round-trip: ink sees stroke-relative time (starting at 0), and
 *     the committed payload restores recording-relative time so v2 audio sync
 *     and replay (N4) keep working.
 *  2. **`StrokeCodec` stays canonical / the AI edit pipeline never sees ink** —
 *     the committed payload must decode to the exact `[x,y,p,t]` lane layout the
 *     `edit-ops` pipeline reads, indistinguishable from a hand-drawn commit.
 *
 * These guard Adoption principle 2 (`StrokeCodec` canonical, AI pipeline
 * inviolable) at the commit seam.
 */
class InkCommitParityTest {

    private val tol = 1e-3f

    /** Build a v2 sample array `[x,y,p,t]*` with recording-relative times. */
    private fun v2Samples(n: Int, originMs: Int): FloatArray {
        val out = FloatArray(n * StrokeCodec.FLOATS_PER_SAMPLE_V2)
        var b = 0
        var t = originMs
        for (i in 0 until n) {
            out[b] = 40f + 8f * i
            out[b + 1] = 200f + (if (i % 2 == 0) 4f else -4f)
            out[b + 2] = 0.4f + 0.5f * (i.toFloat() / n)
            out[b + 3] = 0.15f
            out[b + 4] = t.toFloat()
            t += 8
            b += StrokeCodec.FLOATS_PER_SAMPLE_V2
        }
        return out
    }

    private fun strokeFrom(payload: ByteArray): Stroke {
        val brush = InkInterop.brushForTool("pen", 0xFF000000.toInt(), baseWidthPx = 4f)
        return InkInterop.toStroke(payload, brush, InputToolType.STYLUS)
    }

    /**
     * The authoring flow: a v2 stroke is fed to ink (where time becomes
     * stroke-relative), then committed back with the captured recording origin.
     * The restored timestamps must be recording-relative again and exact.
     */
    @Test
    fun audioTimestampSyncSurvivesAuthoringRoundTrip() {
        val originMs = 1500
        val samples = v2Samples(n = 40, originMs = originMs)
        val payload = StrokeCodec.encodeV2(samples)

        // toInputBatch is what feeds ink: stroke-relative time + captured origin.
        val conv = InkInterop.toInputBatch(payload)
        assertEquals(originMs.toLong(), conv.recordingOriginMillis)
        assertEquals("ink sees stroke-relative t0 = 0", 0L, conv.batch.get(0).elapsedTimeMillis)
        assertTrue("ink time advances", conv.batch.get(39).elapsedTimeMillis > 0L)

        // The committed payload (origin re-added) restores recording-relative time.
        val stroke = strokeFrom(payload)
        val committed = InkInterop.fromStroke(stroke, conv.recordingOriginMillis)
        assertTrue("committed payload is v2", StrokeCodec.isV2(committed))

        val out = StrokeCodec.decodeWithT(committed)
        var b = 0
        while (b < samples.size) {
            assertEquals("t recording-relative", samples[b + 4], out[b + 4], 0f)
            b += StrokeCodec.FLOATS_PER_SAMPLE_V2
        }
        // Timestamps strictly increasing and recording-relative (>= origin).
        assertEquals(originMs.toFloat(), out[4], 0f)
        assertTrue(out[out.size - 1] > out[4])
    }

    /**
     * Drawing without an active recording must commit a **v1** payload (no
     * timestamps), byte-for-byte like the legacy path — so notes drawn without
     * audio stay identical and the codec version is never spuriously upgraded.
     */
    @Test
    fun noRecordingCommitsV1() {
        val samples = v2Samples(n = 16, originMs = 0)
        // Same geometry, but committed with a null origin (no recording).
        val stroke = strokeFrom(StrokeCodec.encodeV2(samples))
        val committed = InkInterop.fromStroke(stroke, recordingOriginMillis = null)
        assertFalse("no-recording commit is v1", StrokeCodec.isV2(committed))
    }

    /**
     * The committed payload must decode to the canonical `[x,y,p,t]` lanes the
     * AI `edit-ops` pipeline reads — proving it never sees ink. We assert the
     * ink-committed bytes decode identically to a hand-encoded payload of the
     * same inputs.
     */
    @Test
    fun committedPayloadIsCanonicalForAiPipeline() {
        val samples = v2Samples(n = 24, originMs = 500)
        val payload = StrokeCodec.encodeV2(samples)

        val stroke = strokeFrom(payload)
        val committed = InkInterop.fromStroke(stroke, recordingOriginMillis = 500L)

        // Canonical decode (what EditOpsParser / VectorCanvasJson consume).
        val handEncoded = StrokeCodec.decodeWithT(payload)
        val inkCommitted = StrokeCodec.decodeWithT(committed)
        assertEquals(handEncoded.size, inkCommitted.size)
        for (i in handEncoded.indices) {
            assertEquals("lane $i", handEncoded[i], inkCommitted[i], tol)
        }
        // And the raw bytes match — ink is a pure passthrough at commit.
        assertEquals(payload.toList(), committed.toList())
    }
}
