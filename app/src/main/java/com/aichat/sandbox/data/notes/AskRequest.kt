package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import java.io.File

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
    /** App files directory used to resolve embedded note image payloads while rasterizing. */
    val filesDir: File? = null,
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
    /**
     * Phase 8 — when true (with [generate], never with [refine]) the model
     * authors a compact multi-object *scene* instead of a single icon: several
     * grouped `add_path` / `add_shape` objects with a layout, each op tagged
     * with a [EditOp.AddPath.group] label. Uses the scene system message and a
     * [sceneComplexity]-tuned prompt; the editor fits the result into a chosen
     * placement rect so it always lands on-canvas.
     */
    val scene: Boolean = false,
    /** Phase 8 — requested scene density. Ignored unless [scene] is true. */
    val sceneComplexity: SceneComplexity = SceneComplexity.DEFAULT,
    /** Explicit user opt-in for extreme note AI payload scopes. */
    val confirmLargeScope: Boolean = false,
    /**
     * Phase 19 (Stage 1) — render-in-the-loop self-refine passes for icon
     * authoring (GENERATE / "Make real" REFINE). After the model emits geometry,
     * the service renders it and sends a second vision turn asking the model to
     * critique its own rendered output and re-author. Default 1 ("always on"
     * for vision models); the service zeroes it for non-vision models and for
     * scene generation. Hard-capped at [NoteAiService.MAX_SELF_REFINE_ITERS].
     */
    val selfRefineIterations: Int = 1,
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
 * - [RESTYLE] — phase 7: reply with an `edit-ops` document that restyles the
 *   in-scope items into a named visual look ([StylePreset]). Like EDIT it
 *   produces a previewable [AiChunk.EditPreview], but [RestyleParser] keeps only
 *   the non-additive, non-moving op subset ([StylePreset.isRestyleOp]) so the
 *   subject stays the same — only its appearance changes. [userPrompt] carries
 *   the chosen preset's [StylePreset.buildInstruction]; selection / layers scope
 *   the geometry the model restyles.
 * - [SUGGEST_METADATA] — phase 9: reply with a validated metadata JSON
 *   ([MetadataParser]) — a short title, a few keyword tags, and a short
 *   description usable as export alt text. Non-mutating: the result is surfaced
 *   as editable suggestions and applied only to the note title, the `note_tags`
 *   table, and the export accessibility metadata — never to canvas geometry.
 *   selection / layers scope the geometry the model is shown.
 */
enum class AskMode { ASK, EDIT, DESIGN_BRUSH, SUGGEST_PALETTE, CRITIQUE, RESTYLE, SUGGEST_METADATA }
