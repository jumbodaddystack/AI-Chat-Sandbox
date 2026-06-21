package com.aichat.sandbox.data.notes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.MessageDigest
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
    /** Human-readable mode label for UI warnings, e.g. "Edit canvas". */
    val modeLabel: String,
    /** True when the retained request may include text authored on the canvas. */
    val containsUserCanvasText: Boolean,
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
        append("mode: ").append(mode).append(" (").append(modeLabel).append(")\n")
        append("contains user canvas text: ").append(containsUserCanvasText).append('\n')
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
     * reply payloads are redacted and clamped so data URIs, OCR/text bodies,
     * and runaway model replies cannot grow the buffer without bound. Newest
     * entries sort first; the list is capped at [CAP] and [MAX_RETAINED_BYTES].
     */
    fun record(
        mode: String,
        modelId: String,
        request: String,
        rawReply: String,
        outcome: String,
        rejections: List<String> = emptyList(),
        modeLabel: String = labelForMode(mode),
        containsUserCanvasText: Boolean = false,
        stripTextItemBodies: Boolean = false,
    ) {
        if (!enabled) return
        val trace = AiDebugTrace(
            id = UUID.randomUUID().toString(),
            epochMillis = System.currentTimeMillis(),
            mode = mode,
            modeLabel = modeLabel,
            containsUserCanvasText = containsUserCanvasText,
            modelId = modelId,
            request = request.redactForLog(stripTextItemBodies),
            rawReply = rawReply.redactForLog(stripTextItemBodies = false),
            outcome = outcome,
            rejections = rejections,
        )
        _traces.update { old -> trimToRetentionLimits(listOf(trace) + old) }
    }

    /** Drop every captured exchange (the log screen's "Clear" action). */
    fun clear() {
        _traces.value = emptyList()
    }

    private fun trimToRetentionLimits(candidates: List<AiDebugTrace>): List<AiDebugTrace> {
        val kept = ArrayList<AiDebugTrace>(CAP)
        var bytes = 0
        for (trace in candidates.take(CAP)) {
            val traceBytes = trace.retainedBytes()
            if (kept.isNotEmpty() && bytes + traceBytes > MAX_RETAINED_BYTES) break
            kept += trace
            bytes += traceBytes
        }
        return kept
    }

    private fun AiDebugTrace.retainedBytes(): Int =
        request.toByteArray(Charsets.UTF_8).size +
            rawReply.toByteArray(Charsets.UTF_8).size +
            outcome.toByteArray(Charsets.UTF_8).size +
            rejections.sumOf { it.toByteArray(Charsets.UTF_8).size }

    private fun String.redactForLog(stripTextItemBodies: Boolean): String {
        val withoutImages = DATA_URI_REGEX.replace(this) { match ->
            val value = match.value
            "data:${match.groupValues[1]};base64,[redacted sha256=${value.sha256Prefix()} bytes=${value.toByteArray(Charsets.UTF_8).size}]"
        }
        val withoutText = if (stripTextItemBodies) {
            TEXT_BODY_REGEX.replace(withoutImages) { match ->
                "${match.groupValues[1]}[redacted canvas text body]${match.groupValues[2]}"
            }
        } else {
            withoutImages
        }
        return withoutText.clampForLog()
    }

    private fun String.clampForLog(): String =
        if (toByteArray(Charsets.UTF_8).size <= MAX_FIELD_BYTES) this
        else {
            var end = minOf(length, MAX_FIELD_CHARS)
            var candidate = substring(0, end)
            while (candidate.toByteArray(Charsets.UTF_8).size > MAX_FIELD_BYTES && end > 0) {
                end = (end * 0.9).toInt().coerceAtLeast(end - 1024).coerceAtLeast(0)
                candidate = substring(0, end)
            }
            candidate + "\n…[truncated ${length - end} chars]"
        }

    private fun String.sha256Prefix(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)

    companion object {
        private val DATA_URI_REGEX = Regex("data:([a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+);base64,[A-Za-z0-9+/=_-]+")
        private val TEXT_BODY_REGEX = Regex("(\\\"(?:text|body|ocrText|recognizedText|transcript|content)\\\"\\s*:\\s*\\\")[^\\\"]*?(\\\")")

        fun labelForMode(mode: String): String = when (mode) {
            "EDIT" -> "Edit canvas"
            "ICON_EDIT" -> "Edit icon"
            "PALETTE" -> "Palette suggestions"
            "CRITIQUE" -> "Canvas critique"
            "METADATA" -> "Note metadata"
            "RESTYLE" -> "Restyle canvas"
            "REFINE" -> "Refine generated art"
            "SCENE" -> "Generate scene"
            "GENERATE" -> "Generate art"
            "DESIGN_BRUSH" -> "Design brush"
            "VECTOR_TUNEUP" -> "Vector tune-up"
            "VECTOR_REDRAW" -> "Vector redraw"
            else -> mode.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() }
        }


        /** Most recent exchanges retained. Plenty for "what just happened?". */
        const val CAP: Int = 25

        /** Per-field character clamp so one huge reply can't bloat memory. */
        const val MAX_FIELD_CHARS: Int = 64 * 1024

        /** Per-field UTF-8 byte clamp used before persistence. */
        const val MAX_FIELD_BYTES: Int = 64 * 1024

        /** Whole-buffer retained byte cap across request/reply/outcome/rejections. */
        const val MAX_RETAINED_BYTES: Int = 512 * 1024
    }
}
