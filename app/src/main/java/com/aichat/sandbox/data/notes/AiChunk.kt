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

    /**
     * Phase I4 / N1 — terminal event emitted by `DESIGN_BRUSH` mode. Carries the
     * validated [spec] the UI turns into a user-scope `BrushPreset` (via
     * [BrushSpec.toPreset]). No canvas edit is implied — only the brush library.
     */
    data class BrushDesign(
        val spec: BrushSpec,
        val usage: Usage? = null,
    ) : AiChunk

    /**
     * Phase 2 — terminal event emitted by `SUGGEST_PALETTE` mode. Carries the
     * validated [suggestion] (swatches + rationale + optional short-id colour
     * assignments) plus the short-id → on-disk-UUID [idMap] so the applier can
     * resolve any assignment ids back to live items for a previewable recolor.
     * No canvas mutation is implied — the swatches are surfaced as suggestions.
     */
    data class PaletteResult(
        val suggestion: PaletteSuggestion,
        val idMap: Map<String, String>,
        val usage: Usage? = null,
    ) : AiChunk

    /**
     * Phase 3 — terminal event emitted by `CRITIQUE` mode. Carries the validated
     * [critique] (summary + suggestions, each with an optional safe edit-op
     * payload) plus the short-id → on-disk-UUID [idMap] and short-layer-id →
     * UUID [layerMap] so the applier can resolve a suggestion's ops back to live
     * items for a previewable fix. No canvas mutation is implied — the
     * suggestions are surfaced as advisory cards.
     */
    data class CritiqueResult(
        val critique: CompositionCritique,
        val idMap: Map<String, String>,
        val layerMap: Map<String, String>,
        val usage: Usage? = null,
    ) : AiChunk
}
