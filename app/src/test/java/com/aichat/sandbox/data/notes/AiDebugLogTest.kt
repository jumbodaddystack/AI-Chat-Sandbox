package com.aichat.sandbox.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ring-buffer + gate contract of [AiDebugLog]: disabled = no-op,
 * enabled = newest-first, capped at [AiDebugLog.CAP], oversized fields clamped,
 * and `clear()` empties it. Pure JVM — no Android framework involved.
 */
class AiDebugLogTest {

    private fun log() = AiDebugLog()

    private fun AiDebugLog.recordEdit(outcome: String, raw: String = "{}") =
        record(mode = "EDIT", modelId = "m", request = "req", rawReply = raw, outcome = outcome)

    @Test
    fun disabledIsNoOp() {
        val log = log()
        // enabled defaults to false
        assertFalse(log.enabled)
        log.recordEdit("ignored")
        assertTrue(log.traces.value.isEmpty())
    }

    @Test
    fun enabledRecordsNewestFirst() {
        val log = log().apply { enabled = true }
        log.recordEdit("first")
        log.recordEdit("second")
        val traces = log.traces.value
        assertEquals(2, traces.size)
        assertEquals("second", traces[0].outcome)
        assertEquals("first", traces[1].outcome)
    }

    @Test
    fun ringBufferCapsAtCap() {
        val log = log().apply { enabled = true }
        repeat(AiDebugLog.CAP + 10) { i -> log.recordEdit("n$i") }
        val traces = log.traces.value
        assertEquals(AiDebugLog.CAP, traces.size)
        // Newest survives, oldest evicted.
        assertEquals("n${AiDebugLog.CAP + 9}", traces.first().outcome)
    }

    @Test
    fun oversizedFieldsAreClamped() {
        val log = log().apply { enabled = true }
        val huge = "x".repeat(AiDebugLog.MAX_FIELD_BYTES + 5000)
        log.recordEdit("big", raw = huge)
        val stored = log.traces.value.single().rawReply
        assertTrue(stored.length < huge.length)
        assertTrue(stored.contains("truncated"))
    }

    @Test
    fun dataUrisAreRedactedBeforeStorage() {
        val log = log().apply { enabled = true }
        val dataUri = "data:image/png;base64," + "A".repeat(256)
        log.record(
            mode = "EDIT",
            modelId = "m",
            request = "{\"image\":\"$dataUri\"}",
            rawReply = "{\"echo\":\"$dataUri\"}",
            outcome = "ok",
        )

        val trace = log.traces.value.single()
        assertFalse(trace.request.contains(dataUri))
        assertFalse(trace.rawReply.contains(dataUri))
        assertTrue(trace.request.contains("[redacted sha256="))
        assertTrue(trace.rawReply.contains("[redacted sha256="))
    }

    @Test
    fun oversizedRawRepliesAreNotStoredVerbatim() {
        val log = log().apply { enabled = true }
        val raw = "{\"reply\":\"${"z".repeat(AiDebugLog.MAX_FIELD_BYTES * 2)}\"}"
        log.recordEdit("huge", raw = raw)

        val stored = log.traces.value.single().rawReply
        assertFalse(stored == raw)
        assertTrue(stored.toByteArray(Charsets.UTF_8).size < raw.toByteArray(Charsets.UTF_8).size)
        assertTrue(stored.contains("truncated"))
    }

    @Test
    fun canStripCanvasTextBodiesAndLabelsMode() {
        val log = log().apply { enabled = true }
        log.record(
            mode = "EDIT",
            modelId = "m",
            request = "{\"type\":\"text\",\"text\":\"private OCR words\"}",
            rawReply = "{}",
            outcome = "ok",
            containsUserCanvasText = true,
            stripTextItemBodies = true,
        )

        val trace = log.traces.value.single()
        assertEquals("Edit canvas", trace.modeLabel)
        assertTrue(trace.containsUserCanvasText)
        assertFalse(trace.request.contains("private OCR words"))
        assertTrue(trace.request.contains("[redacted canvas text body]"))
    }

    @Test
    fun clearEmptiesBuffer() {
        val log = log().apply { enabled = true }
        log.recordEdit("a")
        assertEquals(1, log.traces.value.size)
        log.clear()
        assertTrue(log.traces.value.isEmpty())
    }

    @Test
    fun shareTextIncludesReplyAndRejections() {
        val trace = AiDebugTrace(
            id = "1", epochMillis = 0L, mode = "EDIT", modeLabel = "Edit canvas",
            containsUserCanvasText = true, modelId = "m",
            request = "the-request", rawReply = "the-reply", outcome = "0 of 1",
            rejections = listOf("smooth p_004 (not a stroke)"),
        )
        val text = trace.toShareText()
        assertTrue(text.contains("the-reply"))
        assertTrue(text.contains("the-request"))
        assertTrue(text.contains("smooth p_004 (not a stroke)"))
    }
}
