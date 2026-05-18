package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.Usage

/**
 * Streaming primitive emitted by `NoteAiService.ask` (sub-phase 2.5 of
 * `docs/STYLUS_NOTES_PHASE_2.md`). Mirrors the subset of
 * [com.aichat.sandbox.data.remote.StreamEvent] that the note pipeline cares
 * about — text deltas, terminal completion, and terminal errors. Tool-call
 * deltas are intentionally absent: the note pipeline never asks for tools.
 */
sealed interface AiChunk {
    data class Delta(val text: String) : AiChunk
    data class Complete(val usage: Usage?) : AiChunk
    data class Error(val message: String) : AiChunk

    /**
     * Sub-phase 7.3 — terminal event emitted by `EDIT` mode in place of
     * [Complete]. Carries the parsed [doc] plus the short-id → on-disk-UUID
     * translation tables so the applier can resolve ids back to real items.
     */
    data class EditPreview(
        val doc: EditOpsDoc,
        val idMap: Map<String, String>,
        val layerMap: Map<String, String>,
        val usage: Usage? = null,
    ) : AiChunk
}
