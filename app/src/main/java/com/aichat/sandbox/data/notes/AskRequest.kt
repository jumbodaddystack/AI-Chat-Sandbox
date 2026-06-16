package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer

/**
 * Inputs for `NoteAiService.ask` (sub-phase 2.5 of
 * `docs/STYLUS_NOTES_PHASE_2.md`).
 *
 * The caller supplies `allItems` so the service doesn't need a [NoteDao]
 * dependency — `NoteEditorViewModel` already keeps the live item list in
 * memory and can pass it through unchanged. `selection == null` means "ask
 * about the whole note"; a non-null selection narrows the scope (lasso
 * Ask in 2.7). `baseUrl` / `apiKey` come from the same preferences the chat
 * pipeline reads, captured at request time so a setting change mid-stream
 * doesn't affect an in-flight ask.
 *
 * Phase 7.3 adds [mode]. [AskMode.ASK] is the original free-form question
 * pipeline. [AskMode.EDIT] adds the vector JSON of the in-scope items to the
 * request and instructs the model to reply with a typed `edit-ops` document
 * (see [EditOpsParser]). [layers] is consumed only by EDIT mode (to expose
 * the layer inventory in the serialised JSON and to filter locked layers).
 */
data class AskRequest(
    val note: Note,
    val allItems: List<NoteItem>,
    val selection: List<NoteItem>?,
    val userPrompt: String,
    val modelId: String,
    val baseUrl: String,
    val apiKey: String,
    val mode: AskMode = AskMode.ASK,
    val layers: List<NoteLayer> = emptyList(),
    /** Icon-mode notes get an icon-design-tuned EDIT system message. */
    val isIcon: Boolean = false,
    /**
     * Phase 17.5 #1 — when true (EDIT mode only), the model *generates* new
     * icon geometry from scratch in the style of [styleReferences] instead of
     * editing the in-scope items. Lands as `add_path` / `add_shape` ops.
     */
    val generate: Boolean = false,
    /**
     * Phase 17.5 #1 — up to three gallery icons serialized as
     * [VectorCanvasJson], embedded in the generation system prompt as style
     * reference. Ignored unless [generate] is true.
     */
    val styleReferences: List<String> = emptyList(),
    /**
     * Phase 17.5 #2 — annotate-and-iterate "Make real" refine. Implies
     * [generate] (the model authors a fresh, cleaned vector with `add_path` /
     * `add_shape`), but the request also rasterizes [selection] so a
     * vision-capable model redraws the sketch faithfully. The editor places
     * the result beside the original (a placement offset, not in-place).
     */
    val refine: Boolean = false,
)

/**
 * Top-level dispatch for [NoteAiService] — see [AskRequest.mode].
 *
 * - [ASK] — free-form question about the note.
 * - [EDIT] — reply with a typed `edit-ops` document ([EditOpsParser]).
 * - [DESIGN_BRUSH] — phase I4 / N1: reply with a small, validated brush-spec
 *   JSON ([BrushSpecParser]) that becomes a user-scope `BrushPreset`. No canvas
 *   mutation — only the user's brush library grows — so it ignores
 *   selection / layers entirely and reads only [userPrompt].
 * - [SUGGEST_PALETTE] — phase 2: reply with a validated palette JSON
 *   ([PaletteParser]) — a cohesive colour scheme plus an optional per-item
 *   colour plan. Non-mutating: the result is surfaced as swatches; applying it
 *   is a separate, previewable `recolor` step. [userPrompt] carries the chosen
 *   scheme hint; selection / layers scope the colours the model is shown.
 * - [CRITIQUE] — phase 3: reply with a validated composition-critique JSON
 *   ([CritiqueParser]) — 3–5 beginner-friendly suggestions, each with an
 *   optional, *safe* edit-op payload. Non-mutating: the suggestions are
 *   surfaced as cards; applying a suggestion's fix is a separate, previewable
 *   step. selection / layers scope the geometry the model is shown.
 */
enum class AskMode { ASK, EDIT, DESIGN_BRUSH, SUGGEST_PALETTE, CRITIQUE }
