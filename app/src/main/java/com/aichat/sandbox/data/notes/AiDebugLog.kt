package com.aichat.sandbox.data.notes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One captured AI exchange for the canvas/AI debug log. Holds exactly what you
 * need to see *why* an edit did or didn't apply: the payload sent to the model,
 * the raw reply the model returned, a one-line [outcome], and the
 * parser/applier [rejections] (each a short "op (reason)" string).
 *
 * Everything here is plain data — the recorder builds it, the log screen renders
 * it, and a unit test pins the ring-buffer behaviour. The [rawReply] is the
 * field that matters most: today it is parsed and thrown away, so when the model
 * invents a shape type or hallucinates an id there is no way to inspect it.
 */
data class AiDebugTrace(
    val id: String,
    val epochMillis: Long,
    /** Short mode tag, e.g. "EDIT", "GENERATE", "PALETTE", "VECTOR_TUNEUP". */
    val mode: String,
    val modelId: String,
    /** The prompt / canvas-JSON payload sent to the model (best-effort). */
    val request: String,
    /** Exactly what the model streamed back, concatenated. */
    val rawReply: String,
    /** One-line result, e.g. "3 ops accepted, 2 rejected" or "parse failed: …". */
    val outcome: String,
    /** Per-op rejection / skip reasons surfaced to the user, if any. */
    val rejections: List<String> = emptyList(),
) {
    /** A copy-friendly plain-text dump of the whole exchange. */
    fun toShareText(): String = buildString {
        append("mode: ").append(mode).append('\n')
        append("model: ").append(modelId).append('\n')
        append("outcome: ").append(outcome).append('\n')
        if (rejections.isNotEmpty()) {
            append("rejections:\n")
            rejections.forEach { append("  - ").append(it).append('\n') }
        }
        append("\n--- request sent ---\n").append(request)
        append("\n\n--- raw model reply ---\n").append(rawReply)
    }
}

/**
 * Process-wide ring buffer of recent AI exchanges, behind a runtime [enabled]
 * gate (wired from the "Capture AI debug log" setting). When disabled,
 * [record] is a cheap no-op so there is zero cost in normal use. When enabled,
 * the last [CAP] exchanges are kept newest-first and exposed as a [StateFlow]
 * so both the in-editor inline view and the full log screen observe one source.
 *
 * Singleton (one buffer for the whole app); dependency-free so it is trivially
 * constructible in unit tests and injectable by Hilt.
 */
@Singleton
class AiDebugLog @Inject constructor() {

    /**
     * Runtime gate, mirrored from the persisted setting at startup and whenever
     * the toggle flips. `@Volatile` because services on background dispatchers
     * read it while the settings coroutine writes it.
     */
    @Volatile
    var enabled: Boolean = false

    private val _traces = MutableStateFlow<List<AiDebugTrace>>(emptyList())
    val traces: StateFlow<List<AiDebugTrace>> = _traces.asStateFlow()

    /**
     * Append one exchange. No-op when [enabled] is false. Oversized request /
     * reply payloads are clamped so a runaway model reply can't grow the buffer
     * without bound. Newest entries sort first; the list is capped at [CAP].
     */
    fun record(
        mode: String,
        modelId: String,
        request: String,
        rawReply: String,
        outcome: String,
        rejections: List<String> = emptyList(),
    ) {
        if (!enabled) return
        val trace = AiDebugTrace(
            id = UUID.randomUUID().toString(),
            epochMillis = System.currentTimeMillis(),
            mode = mode,
            modelId = modelId,
            request = request.clampForLog(),
            rawReply = rawReply.clampForLog(),
            outcome = outcome,
            rejections = rejections,
        )
        _traces.update { old -> (listOf(trace) + old).take(CAP) }
    }

    /** Drop every captured exchange (the log screen's "Clear" action). */
    fun clear() {
        _traces.value = emptyList()
    }

    private fun String.clampForLog(): String =
        if (length <= MAX_FIELD_CHARS) this
        else substring(0, MAX_FIELD_CHARS) + "\n…[truncated ${length - MAX_FIELD_CHARS} chars]"

    companion object {
        /** Most recent exchanges retained. Plenty for "what just happened?". */
        const val CAP: Int = 25

        /** Per-field character clamp so one huge reply can't bloat memory. */
        const val MAX_FIELD_CHARS: Int = 64 * 1024
    }
}
