package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.notes.BrushSpec
import com.aichat.sandbox.data.notes.CompositionCritique
import com.aichat.sandbox.data.notes.PaletteScheme
import com.aichat.sandbox.data.notes.PaletteSource
import com.aichat.sandbox.data.notes.PaletteSuggestion

/**
 * In-memory state for the editor's AI side sheet (sub-phase 2.6 of
 * `docs/STYLUS_NOTES_PHASE_2.md`).
 *
 * The sheet shows a list of [AskTurn]s — each turn is one user prompt plus
 * the (possibly still-streaming) model reply. Turns are intentionally one-
 * shot: prior turns are NOT packed into subsequent requests; multi-turn
 * context packing is a deliberate follow-up (see 2.6 risks in the phase doc).
 *
 * Lives in [NoteEditorViewModel] so it survives configuration changes and is
 * cleared when the editor is left. Persisting across editor exit is out of
 * scope for v1.
 */
data class AiSideSheetState(
    val isOpen: Boolean = false,
    /**
     * Selection captured at [openSheet] time. Frozen for the lifetime of the
     * sheet so the displayed scope chip doesn't drift if the user lasso-
     * clears in the background. Cleared mid-sheet only via the explicit
     * scope chip tap (see sub-phase 2.7).
     */
    val pendingSelection: List<NoteItem>? = null,
    val turns: List<AskTurn> = emptyList(),
    val inputText: String = "",
    val activeModelId: String = "",
    /**
     * Whether the footer's text box submits a question (ASK, prose reply) or
     * an edit instruction (EDIT, staged preview). Icons default to EDIT since
     * they are design surfaces; notes default to ASK.
     */
    val footerMode: AiFooterMode = AiFooterMode.ASK,
    /**
     * True when the edited resource is an icon (`Note.isIcon`). Selects the
     * design-oriented quick actions and edit-first copy instead of the
     * note-centric ask prompts. Kept in sync with the note in the view model.
     */
    val isIcon: Boolean = false,
) {
    /** True while any turn is in flight. Drives the Send / Cancel buttons. */
    val isStreaming: Boolean get() = turns.any { it.state is TurnState.Streaming }

    /**
     * Human-readable scope description rendered above the canned-prompt row
     * (sub-phase 2.7). Either the frozen selection summary, "Whole note", or
     * "Whole icon" for icon resources.
     */
    val scopeLabel: String
        get() = pendingSelection?.let { summarizeSelectionForScope(it) }
            ?: if (isIcon) "Whole icon" else "Whole note"
}

/** Footer submit mode for the AI side sheet — see [AiSideSheetState.footerMode]. */
enum class AiFooterMode { ASK, EDIT }

/**
 * One-tap design actions shown in the icon variant of the canned-action row.
 * Each maps to an edit in the view model: the first four route through the
 * model-backed EDIT pipeline (see [com.aichat.sandbox.data.notes.CannedEditAction]);
 * [RECOLOR] opens the colour picker and applies an AI recolor with the chosen
 * colour.
 */
enum class IconQuickAction(val label: String) {
    MAKE_REAL("Make real"),
    SIMPLIFY("Simplify"),
    FLAT_STYLE("Flat style"),
    ADD_DETAIL("Add detail"),
    AUTO_SHAPE("Auto-shape"),
    RECOLOR("Recolor"),
}

/**
 * Phase 2 — state for the palette & colour-harmony panel hosted inside the AI
 * side sheet. Opened from the "Palette help" chip; [scope] freezes the items
 * the suggestion is computed from (the sheet's selection scope or the whole
 * note) so background selection changes don't drift it.
 *
 * The panel always has a [suggestion] once open (a local [PaletteScheme]
 * palette is generated instantly); [source] notes whether the current swatches
 * came from local colour theory or the model, and [explicit] holds any
 * model-proposed per-item colours already resolved to live item UUIDs so
 * "Preview recolor" can stage them.
 */
data class PaletteUiState(
    val isOpen: Boolean = false,
    val scope: List<NoteItem> = emptyList(),
    val scheme: PaletteScheme = PaletteScheme.DEFAULT,
    val suggestion: PaletteSuggestion? = null,
    val source: PaletteSource = PaletteSource.LOCAL,
    /** Resolved per-item colour plan (item UUID → ARGB), AI source only. */
    val explicit: Map<String, Int> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    /** Colours currently in scope, used to seed the local fallback. */
    val scopeColors: List<Int> get() = scope.map { it.colorArgb }
}

/**
 * Phase 3 — state for the guided composition / layout critique panel hosted
 * inside the AI side sheet. Opened from the "Critique" chip; [scope] freezes the
 * items the critique was computed from.
 *
 * Unlike the palette panel there is no local fallback — a critique requires the
 * model — so the panel opens in a [loading] state and renders the [critique]
 * cards once the reply lands (or an [error] if it failed). [idMap] / [layerMap]
 * are the short-id → live-UUID tables captured at request time so a suggestion's
 * "Preview fix" can resolve its ops back to real items.
 */
data class CritiqueUiState(
    val isOpen: Boolean = false,
    val scope: List<NoteItem> = emptyList(),
    val critique: CompositionCritique? = null,
    /** short-id → on-disk item UUID, captured with the critique for previewing fixes. */
    val idMap: Map<String, String> = emptyMap(),
    /** short-layer-id → on-disk layer UUID. */
    val layerMap: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Phase 4 (N1) — state for the AI brush designer panel hosted inside the AI side
 * sheet. Opened from the "Brush designer" chip. The flow is deliberately
 * **preview-then-save**: the user types a prompt (or taps an example), the model
 * returns a validated [BrushSpec], the panel renders a deterministic sample
 * stroke, and only an explicit "Save brush" tap persists it as a user-scope
 * preset. Nothing about the canvas is touched.
 *
 * [spec] is the previewed-but-unsaved brush; [savedName] is non-null once the
 * preset has been written to the brush library (drives the confirmation copy).
 * [error] surfaces a parser failure or a missing-credentials problem without
 * leaving a partial preset behind.
 */
data class BrushDesignUiState(
    val isOpen: Boolean = false,
    val prompt: String = "",
    val loading: Boolean = false,
    /** The previewed, not-yet-saved brush. Null until a design lands. */
    val spec: BrushSpec? = null,
    /** Non-null once [spec] has been saved as a user preset. */
    val savedName: String? = null,
    val error: String? = null,
) {
    /** A spec is previewable (and savable) once it has landed and isn't already saved. */
    val canSave: Boolean get() = spec != null && savedName == null && !loading
}

/**
 * Phase 6 (N4 / idea #7) — state for the "Draw with me" launcher panel hosted
 * inside the AI side sheet. This panel only *starts* a tutor: the user describes
 * what they want to learn to draw, and the prompt routes through the unchanged
 * GENERATE pipeline so the construction strokes preview as a normal staged edit
 * before they land on a ghosted guide layer.
 *
 * The stepped Next / Back / Skip / Redo controls deliberately do NOT live here —
 * they are a canvas overlay driven by the view model's `tutorSession`, because
 * they stay useful after the sheet is closed and the user is tracing on the
 * canvas. Likewise the replay playhead is its own overlay.
 *
 * The whole feature is gated behind the experimental ink engine (Adoption
 * principle 4): the launch button is disabled with explanatory copy when ink is
 * off, mirroring Phase 5's "Smart select" gating.
 */
data class DrawWithMeUiState(
    val isOpen: Boolean = false,
    val prompt: String = "",
    val error: String? = null,
)

/**
 * Phase 6 (N4 / idea #7, sub-phase a) — state for the interactive replay
 * playhead. A scrub over the note's draw-order timeline that reveals each mark
 * at its draw time; [hiddenIds] are the ids the canvas suppresses at the current
 * [positionMs] (fed through the same channel as the tutor's hidden items).
 *
 * The timeline itself lives in the view model (it holds resolved [NoteItem]s);
 * this is just the playback chrome. Video/GIF *encoding* is the device-only
 * export path and is intentionally out of scope.
 */
data class ReplayUiState(
    val isOpen: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playing: Boolean = false,
    val hiddenIds: Set<String> = emptySet(),
) {
    /** True when the note has no timeline to scrub (empty note). */
    val isEmpty: Boolean get() = durationMs <= 0L
}

data class AskTurn(
    val id: String,
    val prompt: String,
    /** Short label like "3 strokes selected" or null for whole-note scope. */
    val selectionSummary: String?,
    val replyBuffer: String,
    val state: TurnState,
    /**
     * Marker for the Convert-to-text fast path. Convert-to-text bypasses
     * [NoteAiService] and runs ML Kit Digital Ink directly; sub-phase 2.7
     * surfaces a one-off "Insert as text box" action on these turns as a
     * preview of the general reply-action row that lands in sub-phase 2.8.
     */
    val isConvertResult: Boolean = false,
    /**
     * A8 fix — convert-to-text now drops the recognized text onto the canvas
     * automatically the moment OCR succeeds (one tap instead of two). This
     * flag records that the auto-placement happened so the bubble shows a
     * "Added as a text box" confirmation plus an "Insert again" affordance
     * rather than the old single "Insert as text box" call to action.
     */
    val convertInserted: Boolean = false,
)

sealed interface TurnState {
    /** Stream is in flight; buffer may still grow. */
    data object Streaming : TurnState

    /** Stream completed successfully. Reply buffer is final. */
    data object Done : TurnState

    /** Stream failed. [message] is the surfaced reason. */
    data class Error(val message: String) : TurnState
}

/**
 * Canned prompt templates surfaced as `AssistChip`s above the input field
 * (sub-phase 2.7). Each is a one-tap, single-line template — tapping fires
 * immediately rather than only populating the input.
 *
 * `Convert to text` is special: it bypasses [NoteAiService] entirely and
 * runs handwriting OCR on the in-scope strokes, then renders the recognized
 * text as a finished `Done` turn (no API spend). Available only when a
 * selection is active.
 */
enum class CannedPrompt(val label: String, val template: String) {
    EXPLAIN("Explain", "Explain this in plain English."),
    EXPAND("Expand", "Expand on the ideas in this note. Suggest additional points."),
    CONVERT_TO_TEXT("Convert to text", ""),
    SUMMARIZE("Summarize", "Summarize this note in 3–5 bullet points."),
    CONTINUE("Continue this", "Continue the thought naturally from where it leaves off.");

    companion object {
        /** The four prompts that route through the standard ask pipeline. */
        val ASK_PROMPTS: List<CannedPrompt> = entries - CONVERT_TO_TEXT
    }
}

internal fun summarizeSelectionForScope(selection: List<NoteItem>): String {
    val strokes = selection.count { it.kind == "stroke" }
    val texts = selection.count { it.kind == "text" }
    return when {
        strokes > 0 && texts > 0 -> "$strokes strokes, $texts text selected"
        strokes > 0 -> if (strokes == 1) "1 stroke selected" else "$strokes strokes selected"
        texts > 0 -> if (texts == 1) "1 text selected" else "$texts text items selected"
        else -> "${selection.size} items selected"
    }
}
