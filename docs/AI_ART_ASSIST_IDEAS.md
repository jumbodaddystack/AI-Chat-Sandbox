# AI Art-Assist Implementation Plan

Status: **Phases 1–9 implemented and verified as of 2026-06-17**.

This document supersedes the older brainstorm-style notes in this file. It keeps
only the AI art-assist work that still makes sense for the app as it exists now,
then breaks the roadmap into phased tasks that can be implemented over multiple
sessions.

## 0. Codebase reality check

### Already shipped and user-reachable

- **Live beautify on pen lift** is wired into the editor. `DrawingSurface` exposes
  `onStrokeBeautifyAccepted`, and `NoteEditorScreen` forwards that callback to
  `NoteEditorViewModel.onStrokeBeautifyAccepted`.
- **On-canvas AI edit diff preview is already built.** The previous plan treated
  the UX audit's A1/P0 visual-diff gap as a blocker. That is now fixed: staged AI
  edits render through `AiEditDiffOverlay` before Accept/Reject, with green added
  items, red removed items, and amber modified items. The banner also shows the
  same legend and partial-failure summary.
- **Single-icon GENERATE and Make-real REFINE exist.** They are not separate
  service modes; they are EDIT requests with `AskRequest.generate` and
  `AskRequest.refine` flags.
- **Auto-vectorize photo / AI trace exists.** `AiBitmapTracer` is present with an
  AI-guided path and deterministic local fallback.

### Built headless but not yet productized

- **AI brush designer (N1)** is now productized (Phase 4). The headless data
  path (`AskMode.DESIGN_BRUSH`, brush-spec parsing, preset persistence) is fronted
  by a "Brush designer" panel in the AI sheet: a prompt field with example chips,
  a deterministic sample-stroke preview, and an explicit preview-then-save flow
  (`designBrushPreview()` / `saveBrushDesign()`) that writes a uniquely-named
  user-scope `BrushPreset` and makes it the active brush.
- **Select-similar + snap suggestions (N2 / idea #8)** are now productized
  (Phase 5). The headless ViewModel paths (`selectSimilarTo`, `proposeSnaps`,
  `aiRankSelection`) are fronted by a "Smart select" section in the selection
  overflow menu: "Select similar" (magic wand) for a single stroke, plus "Snap
  layout" and "AI group" for a multi-selection. They stay gated behind the
  experimental ink engine (`inkAuthoring`); when it's off the controls render
  disabled under a line of copy explaining how to turn it on. The locked-layer +
  group-expansion policy was extracted into the pure, testable `SmartSelect`.
- **Draw-with-me tutor + replay (N4 / idea #7)** is now productized (Phase 6).
  The headless paths (`startDrawWithMe`, `buildReplayTimeline`, `TutorGuide`,
  `TutorSession`, `tutorHiddenIds`) are fronted by a "Draw with me" panel in the
  AI sheet (a prompt + example subjects that route through the unchanged GENERATE
  → staged-preview → guide-layer path, plus a "Replay drawing" launcher), an
  on-canvas `TutorControlsBanner` (Back / Skip / Redo / Next / End over the pure
  `TutorSession`), and an interactive `ReplayControlsBanner` playhead (play/pause
  + scrub) that reveals marks in draw order via `ReplayTimeline.hiddenItemIdsAt`.
  It stays gated behind the experimental ink engine (`inkAuthoring`): the launch
  controls render disabled under a line of copy when it's off. Video/GIF export
  remains the deferred device-only path.

### Still missing or only partially covered

- **Composition critique** shipped in Phase 3. A "Critique" chip in the AI sheet
  asks the model "how can I improve this?" and renders 3–5 beginner-friendly
  suggestion cards (`CompositionCritique` / `CritiqueParser`), each with a
  plain-language reason, confidence/effort labels, and — when a *safe*,
  validated edit-op payload is present — a "Preview fix" that stages through the
  shared `PendingEdit` / `AiEditDiffOverlay` accept/reject surface. (Previously
  noted: `VectorQualityScorer` was not a substitute — it scores vector XML
  readiness, not a hand-drawn note via vision.)
- **Palette / color-harmony assistant** shipped in Phase 2. A "Palette help"
  panel in the AI sheet proposes 3–6 cohesive swatches (local color theory via
  `ColorHarmony`, optionally refined by the model through `AskMode.SUGGEST_PALETTE`
  / `PaletteParser`) and stages a previewable `recolor` batch through the shared
  `PendingEdit` surface via `PaletteRecolor`.
- **Named style preset restyling** shipped in Phase 7. A "Restyle" panel in the
  AI sheet offers a curated catalog of named looks (`StylePresetCatalog`: Flat
  icon / Line art / Isometric / Sticker); tapping one routes through a new
  `AskMode.RESTYLE` (selection-scoped EDIT) whose reply is validated by
  `RestyleParser` down to the non-additive, non-moving op subset
  (`StylePreset.isRestyleOp`: `recolor` / `restyle` / `smooth` / `simplify` /
  `replace_with_shape`) and staged through the shared `PendingEdit` /
  `AiEditDiffOverlay` accept/reject surface. The local `StyleTransfer` clipboard
  (copy the exact style of one item onto another) is kept separate, and the panel
  copy spells out the difference. (Previously: local `StyleTransfer` copied style
  and GENERATE used style-reference icons, but there was no user-facing
  "restyle this selection as flat / line-art / isometric" flow.)
- **Prompt-to-vector scene generation** shipped in Phase 8. A "Make a scene"
  panel in the AI sheet broadens the single-icon GENERATE path into a compact,
  editable multi-object scene: a prompt routes through the scene-flagged GENERATE
  path (`AskRequest.scene` + `EditOpsParser.SCENE_GENERATE_SYSTEM_MESSAGE`), the
  model authors several grouped `add_path` / `add_shape` objects (each op tagged
  with a `"group"` label), and `EditPreviewController` stamps a shared
  `NoteItem.groupId` per object so the scene's parts select/move together. The
  whole group is *fit* into a chosen placement rect (visible area / frame / fit
  content) so it always lands on-canvas, the object count is capped per
  complexity (`SceneGen.capSceneAddOps`), and the result stages through the
  shared `PendingEdit` / `AiEditDiffOverlay` accept/reject surface — it stays an
  editable vector, never a raster.
- **Conversational editing context** remains limited. The AI sheet presents a
  conversation and lets the user re-scope subsequent turns, but edit requests are
  still effectively one-shot unless explicit history packing is added.

## 1. Implementation principles

1. **Prefer surfacing existing headless work before building new engines.** N1,
   N2, and N4 already paid most of their data-path cost.
2. **Every canvas mutation must stage as `PendingEdit`.** New feature work should
   use the existing `EditPreviewController` + `AiEditDiffOverlay` +
   `AiEditPreviewBanner` accept/reject path instead of committing directly.
3. **Keep non-mutating AI separate from edit-ops.** Critiques, titles, tags, and
   alt text should return structured prose/metadata and should not pretend to be
   edits unless there is a concrete op the user can preview.
4. **Do not make AndroidX Ink default-on from an art-assist feature.** Features
   already gated by `inkAuthoring` can be surfaced as experimental, or receive a
   non-ink fallback in a separate task.
5. **Design for multiple short sessions.** Each phase below has a shippable end
   state and a task tracker.

## 2. Phased roadmap and task tracker

Legend: `[ ]` not started, `[~]` in progress, `[x]` done, `[!]` blocked/needs a
product decision.

---

## Phase 1 — Re-baseline and harden the shared AI edit surface

**Goal:** make sure all future AI art-assist features use the already-shipped
preview/diff infrastructure consistently.

**Why first:** the old plan incorrectly listed the visual diff as missing. Before
adding more buttons, confirm the current diff works for AI-authored edits,
locally-authored snap/tidy edits, and generated geometry.

### Tasks

- [x] Add/verify JVM coverage for `AiEditDiffOverlay`-adjacent behavior at the
  simulation layer: added, removed, modified, and skipped edit buckets.
  - Completed in `EditPreviewControllerPhase1Test`, which exercises the shared
    `EditPreviewController.Simulation` buckets used by the overlay.
- [x] Add a small UI smoke test or screenshot test for the banner legend counts
  and partial-failure message, if the project test stack supports it.
  - Completed as a JVM smoke test over extracted banner summary helpers
    (`aiEditLegendLabels`, `aiEditInvalidReasons`, and
    `aiEditAppliedOfEmitted`) because the project has broad JVM test coverage
    but no existing Compose screenshot test stack for this screen.
- [x] Audit every existing art-assist entry point and document whether it stages
  `PendingEdit`, mutates locally, or only returns prose.
  - Audit result: AI EDIT (`submitAiEdit`) stages `PendingEdit` via
    `stagePendingEdit`; GENERATE and REFINE are EDIT requests with generate /
    refine flags and also stage via `stagePendingEdit`; locally-authored snap
    suggestions (`proposeSnaps`) stage via `stageLocalEdit`; one-tap tidy now
    stages a locally computed `PendingEdit` simulation before accept; AI ranking
    (`aiRankSelection`) routes through AI EDIT; select-similar mutates selection
    only; brush design returns/persists brush presets and does not mutate canvas;
    draw-with-me tutor stages guide generation through the GENERATE edit-ops
    path; replay/tutor guide metadata is non-mutating prose/control data.
- [x] Ensure locally-authored snap/tidy docs use `stageLocalEdit` so they receive
  the same visual diff as model-authored edits.
  - Snap already used `stageLocalEdit`. Tidy now uses a locally computed
    `PendingEdit` simulation because its net add/remove/modify diff is produced
    outside the edit-op protocol; it still previews through the same overlay,
    banner, and accept/reject path.
- [x] Update stale comments in `NoteEditorScreen` that still describe the visual
  diff as a future follow-up.
  - Updated comments in the banner/overlay path to describe the current shared
    staged edit surface rather than a future follow-up.

### Acceptance criteria

- [x] AI EDIT, GENERATE, REFINE, and locally-staged snap/tidy all preview through the
  same accept/reject surface.
- [x] The user can tell what will be added, removed, and modified before accepting.
- [x] No new AI art-assist feature bypasses `PendingEdit` for canvas mutation.

---

## Phase 2 — Ship palette & color-harmony assistant

**Goal:** help non-artists choose and apply cohesive colors.

**MVP behavior:** user selects strokes/shapes or uses whole note scope, taps
“Palette help”, chooses a scheme type, and receives 3–6 swatches plus an optional
previewable `recolor` batch.

### Tasks

- [x] Add a structured palette response contract, e.g. `PaletteSuggestion` with
  scheme name, swatches, rationale, and optional id-to-color assignments.
  - Implemented as `PaletteSuggestion` (`data/notes/PaletteSuggestion.kt`):
    `schemeName`, `swatches` (3–6 opaque ARGB), `rationale`, and an optional
    short-id → ARGB `assignments` map. Includes a `PaletteScheme` enum
    (analogous / complementary / triadic / monochromatic) and a `PaletteSource`
    (LOCAL vs AI) marker.
- [x] Add prompt text to ask the model for beginner-friendly color harmony using
  current canvas colors from `VectorCanvasJson` and, when available, the raster
  preview.
  - `AskMode.SUGGEST_PALETTE` + `NoteAiService.collectPalette` serialize the
    in-scope items via `VectorCanvasJson` (so assignment ids line up) and attach
    the rasterized preview for vision-capable models. `PaletteParser.SYSTEM_MESSAGE`
    pins a beginner-friendly, jargon-free JSON contract;
    `buildPalettePromptBody` embeds the canvas JSON and scheme hint.
- [x] Add a local color-theory fallback for simple analogous/complementary/triadic
  palettes so the feature can still suggest swatches when AI is unavailable.
  - `ColorHarmony` (pure JVM HSV maths, no `android.graphics.Color`) derives a
    seed from the dominant canvas color and rotates/tints it per scheme; the
    panel fills in a local palette instantly on open and on every scheme change.
- [x] Add a ViewModel entry point such as `suggestPalette(scope, scheme)` that can
  either return swatches only or stage `recolor` edit-ops.
  - `NoteEditorViewModel` exposes `openPalette()`, `setPaletteScheme(scheme)`
    (local, swatches-only), `suggestPaletteWithAi()` (model refinement), and
    `previewPaletteRecolor()` (stages `recolor` ops). State rides a
    `paletteState: StateFlow<PaletteUiState?>`.
- [x] Add UI in the AI sheet or a compact art-assist menu: scheme chips,
  swatches, “Preview recolor”, “Apply”, and “Copy palette”.
  - `PalettePanel` in `AiSideSheet.kt`, opened by a "Palette help" chip: scheme
    `FilterChip`s, swatch chips with hex a11y labels, rationale, source badge,
    and an action row with "Preview recolor" (stages → accept via the canvas
    banner), "Copy palette" (clipboard), and "Ask AI" (with a progress spinner).
- [x] Ensure recolor preview goes through `PendingEdit` and `AiEditDiffOverlay`.
  - `previewPaletteRecolor` builds grouped `EditOp.Recolor` ops via
    `PaletteRecolor` and stages them with the existing `stageLocalEdit` path, so
    they preview through the same `AiEditDiffOverlay` + banner accept/reject.
- [x] Add tests for parser validation, fallback palette generation, and recolor
  op construction.
  - `PaletteParserTest`, `ColorHarmonyTest`, and `PaletteRecolorTest` (all green).

### Acceptance criteria

- [x] A user can get a palette suggestion without changing the canvas.
- [x] Applying the palette is previewable and rejectable.
- [x] Existing colors and locked layers are respected.
  - The recolor ops route through `EditPreviewController.simulate`, which drops
    locked-layer items and no-op (same-color) recolors as a hard backstop.

---

## Phase 3 — Ship guided composition / layout critique

**Goal:** answer “How can I improve this?” with concrete, beginner-friendly
feedback and optional previewable actions.

**MVP behavior:** the feature returns 3–5 suggestions. Each suggestion has a
plain-language reason, confidence/effort label, and optionally an action button
that stages edit-ops such as align, scale, simplify, restyle, or recolor.

### Tasks

- [x] Define a structured critique schema: `summary`, `suggestions[]`,
  `principle`, `why`, `optionalEditOps`, and `safetyNotes`.
  - Implemented as `CompositionCritique` (`data/notes/CompositionCritique.kt`):
    `summary`, `suggestions` (1–5), and `safetyNotes`. Each `CritiqueSuggestion`
    has `title`, `principle`, `why`, a `CritiqueConfidence` and `CritiqueEffort`
    label, and an optional `ops: List<EditOp>` (the "optionalEditOps" payload).
- [x] Add a critique prompt that uses vision when available and falls back to
  `VectorCanvasJson`/OCR when not.
  - `AskMode.CRITIQUE` + `NoteAiService.collectCritique` serialize the in-scope
    items via `VectorCanvasJson` (so op ids line up) and attach the rasterized
    preview for vision-capable models; non-vision models get OCR text +
    `VectorCanvasJson` via `buildCritiquePromptBody`. `CritiqueParser.SYSTEM_MESSAGE`
    pins a beginner-friendly, jargon-free JSON contract and the safe op vocabulary.
- [x] Add parser/validator logic that tolerates prose-only suggestions and rejects
  unsafe or unparseable edit-op payloads.
  - `CritiqueParser` keeps prose-only suggestions, re-validates each suggestion's
    `ops` through `EditOpsParser` (dropping invented / locked-layer ids), then
    restricts survivors to a non-destructive subset (`CritiqueSuggestion.isSafeOp`:
    `transform`/`recolor`/`restyle`/`simplify`/`smooth`/`replace_with_shape`).
    Destructive/broad ops (`delete`, `add_*`, `set_layer`, `merge_paths`, `group`)
    never survive, so a card with only unsafe ops degrades to prose-only rather
    than sinking the reply.
- [x] Add a ViewModel entry point such as `requestCompositionCritique(scope)`.
  - `NoteEditorViewModel.requestCompositionCritique()` (freezes scope, fires the
    `CRITIQUE` request, fills `critiqueState`), `previewCritiqueFix(index)`
    (stages a suggestion's fix), and `dismissCritique()`. State rides a
    `critiqueState: StateFlow<CritiqueUiState?>`.
- [x] Add UI to display suggestions as cards with “Preview fix” only when an
  edit-op is valid.
  - `CritiquePanel` + `CritiqueCard` in `AiSideSheet.kt`, opened by a "Critique"
    chip: a loading state, summary, scrollable suggestion cards (title, principle,
    why, confidence/effort), and a "Preview fix" action shown only when
    `suggestion.hasFix` (prose-only cards show a quiet "Tip only" marker). Includes
    a re-run control and `safetyNotes` footer.
- [x] Route “Preview fix” through the existing staged edit surface.
  - `previewCritiqueFix` resolves the suggestion's ops through the captured
    short-id ↔ uuid maps and `EditPreviewController.simulate`, then stages a
    `PendingEdit`, so it previews through the same `AiEditDiffOverlay` + banner
    accept/reject as every other AI edit.
- [x] Add tests for prose-only critique, mixed valid/invalid actions, and locked
  layer handling.
  - `CritiqueParserTest` (all green): prose-only validity, mixed valid/invalid
    ops (unknown-id + unsafe ops dropped), unsafe-ops-strip-to-prose, unknown
    layer drop, card clamping, fenced/bare JSON, and garbage no-throw.

### Acceptance criteria

- [x] The feature is useful even when no edit-op is returned.
  - Prose-only suggestions are first-class; every card explains its `why` in
    plain language and renders without a fix button.
- [x] Suggested edits are previewed on canvas and can be rejected.
  - "Preview fix" stages a `PendingEdit` through the shared diff overlay + banner.
- [x] The model cannot silently apply broad layout changes.
  - Fixes are restricted to a non-destructive op subset at parse time, and the
    shared simulator drops locked-layer items and no-ops as a backstop; nothing
    is ever applied without an explicit accept on the canvas banner.

---

## Phase 4 — Surface AI brush designer (N1)

**Goal:** turn the existing `DESIGN_BRUSH` data path into a user-facing brush
creation flow.

**MVP behavior:** user opens a brush designer sheet, types “dry gouache with soft
edges”, sees a sample stroke, saves the generated brush as a user preset, and can
select it from the brush palette.

### Tasks

- [x] Add a Brush Designer entry point from the brush palette or AI sheet.
  - A "Brush designer" `AssistChip` in `AiSideSheet`'s `ScopeAndCannedPromptRow`
    (alongside "Palette help" and "Critique"), available for both notes and icons
    and disabled while the panel is already open. Opens `BrushDesignerPanel`.
- [x] Add prompt input with example chips: “inky brush pen”, “dry gouache”,
  “soft marker”, “scratchy pencil”.
  - `BrushDesignerPanel` has an `OutlinedTextField` plus a `LazyRow` of those
    four example chips (`BRUSH_EXAMPLES`); tapping a chip fills the field (via
    `setBrushDesignPrompt`) without firing the request, leaving room to tweak.
- [x] Wire the UI to the existing `DESIGN_BRUSH` data path.
  - `NoteEditorViewModel.designBrushPreview()` collects `AskMode.DESIGN_BRUSH`
    directly (like the palette/critique panels) instead of through the
    auto-saving `designBrush(prompt, turnId)` conversation turn, so the spec can
    be previewed before it is persisted. Same service mode + `BrushSpecParser`.
- [x] Show streaming/progress state in the sheet.
  - `BrushDesignUiState.loading` drives a spinner + "Designing your brush…" row;
    the prompt field and example chips disable while a request is in flight.
- [x] Render a deterministic preview stroke for the returned `BrushPreset`.
  - `BrushPreviewStroke` (`ui/components/notes/BrushPreviewStroke.kt`) draws a
    sine-wave sample stroke from the `BrushSpec`. The geometry
    (`brushPreviewSamples`) is pure/JVM-testable: width folds base width × taper
    ramps × pressure-curve profile × **index-seeded** (not RNG) jitter, and alpha
    carries opacity dimmed at the tapered ends — so the same spec always renders
    the same swatch.
- [x] Add save/cancel controls and confirm the preset appears in the palette.
  - "Save brush" (`saveBrushDesign()`) writes a user-scope `BrushPreset` and sets
    it as the active brush; the panel flips to a "Saved … — it's now your active
    brush" confirmation with "Design another". "Re-roll" re-runs the prompt, and
    the header close / "Design another" (`clearBrushDesign`) reset cleanly.
- [x] Add validation UX for unsupported spec fields or parser failure.
  - The parser already clamps/defaults bad *fields* (never fails on them); a
    structurally broken reply surfaces `AiChunk.Error` → `BrushDesignUiState.error`
    (red text) and a blank prompt is rejected with an inline message. No preset is
    written on failure (save is a separate, explicit step).
- [x] Add tests for brush-spec parsing, preset persistence, and duplicate names.
  - Brush-spec parsing: existing `BrushSpecParserTest` (incl.
    `toPresetIsUserScopeAndCarriesFields` for the persistence mapping) +
    `NoteAiServiceTest.designBrushModeEmitsValidatedBrushDesign`. Duplicate names:
    new `BrushPresetNamingTest` over `BrushPresetNaming.uniqueName` (the
    disambiguator the save path uses). Preview geometry: new
    `BrushPreviewStrokeTest` (determinism, taper, opacity, non-negative widths,
    pressure-curve shaping).

### Acceptance criteria

- [x] No canvas mutation occurs during brush design.
  - The flow only ever writes to the brush library (`BrushPresetRepository`); it
    never stages a `PendingEdit` or touches `items`. The request runs with
    `selection = null` and no raster.
- [x] Saved brushes are reusable, editable presets.
  - A save produces a user-scope `BrushPreset` (UUID-keyed) that shows in the
    brush sheet's preset chips and is editable via the existing BrushSheet
    sliders / "Save as preset" like any other user preset.
- [x] Failure states are understandable and do not leave partial presets behind.
  - Preview-then-save means a parser failure or cancelled/closed panel writes
    nothing; errors render inline, and duplicate names are disambiguated rather
    than silently colliding.

---

## Phase 5 — Surface select-similar and snap suggestions (N2 / idea #8)

**Goal:** expose the existing magic-wand and constraint-snap engines safely.

**MVP behavior:** with the experimental ink engine enabled, the user can tap a
magic-wand action for a selected stroke to select similar strokes, then preview
snap/alignment suggestions as normal staged edits.

### Tasks

- [x] Decide product gating: experimental-only while `inkAuthoring` is off by
  default, or add a non-ink fallback for simple bounds-based selection/snapping.
  - Decision: **experimental-only**. The select-similar (mesh-backed) and snap
    engines stay gated behind the experimental ink engine (`inkAuthoring`,
    default-off), honouring principle 4 ("don't make AndroidX Ink default-on
    from an art-assist feature"). Rather than building a separate non-ink
    fallback engine, the controls are *surfaced* in every selection but render
    disabled, under a line of copy, when ink is off — so the capability is
    discoverable without flipping the default. (`selectSimilarTo` still degrades
    to a plain single tap-select with ink off, so no gesture is ever lost.)
- [x] Add a visible Magic Wand action when a single stroke is selected and
  `inkSelectionToolsEnabled()` is true.
  - A "Select similar" item (✦ `AutoAwesome`) under a new "Smart select" section
    in the selection overflow (`SelectionOverlay` ▸ "More"), shown only when
    `selectionIsSingleStroke` and enabled only when `inkSelectionToolsEnabled()`.
- [x] Wire the action to `selectSimilarTo(itemId)` and update selection chrome.
  - `onSelectSimilar = viewModel::selectSimilarToSelection`, which resolves the
    single selected stroke and calls `selectSimilarTo`. That routes through
    `selectExactly`, which rewrites `_selection` + recomputes
    `_selectionWorldBounds`, so the overlay chrome snaps to the new set.
- [~] Add threshold/strictness UI only after the one-tap default feels good.
  - **Intentionally deferred** per the task's own condition. The one-tap default
    uses `SelectSimilar.DEFAULT_THRESHOLD`; `selectSimilarTo` /
    `selectSimilarToSelection` already accept a `threshold` parameter, so a
    strictness slider can be layered on later without touching the engine.
- [x] Add “Snap selection” or “Align suggestion” action for multi-selection when
  `inkSelectionToolsEnabled()` is true.
  - A "Snap layout" item (`Straighten`) in the same "Smart select" section,
    shown when `selectionIsMulti` (≥2) and enabled only with ink on.
- [x] Wire snap action to `proposeSnaps(selection)`.
  - `onSnapSelection = { viewModel.proposeSnaps() }` (defaults to the live
    selection), which stages the constraint nudges via `stageLocalEdit`.
- [x] Optionally add “Ask AI to group/refine selection” wired to
  `aiRankSelection(extraInstruction)`.
  - An "AI group" item (`GroupWork`) in the same section for multi-selections,
    wired to `viewModel.aiRankSelection()`.
- [x] Add UI copy explaining why the controls are unavailable when ink is off.
  - When `inkSelectionToolsEnabled()` is false the "Smart select" section still
    appears (whenever a smart action is contextually possible) with the actions
    disabled and a `bodySmall` caption: "Turn on the experimental ink engine
    (More ▸ Ink engine) to select similar strokes and snap layouts."
- [x] Add tests for selection expansion, locked layers, and staged snap previews.
  - `SmartSelectTest` (all green): similar-vs-dissimilar selection, group
    expansion of matches, locked-layer exclusion (as candidate and group
    sibling), non-stroke/missing-target degradation, and a staged-snap preview
    that modifies the unlocked item while the shared simulator drops the locked
    one's transform.

### Acceptance criteria

- [x] Select-similar changes selection only; it does not mutate the canvas.
  - `selectSimilarTo` only calls `selectExactly`; it never stages a
    `PendingEdit` or touches `items`.
- [x] Snap suggestions stage as previewable `PendingEdit` transforms.
  - `proposeSnaps` emits `EditOp.Transform`s and stages them via the shared
    `stageLocalEdit` → `AiEditDiffOverlay` + banner accept/reject path.
- [x] Hidden experimental gating is visible and understandable to users.
  - The "Smart select" controls are present (not hidden) for any qualifying
    selection, disabled with explanatory copy when the ink engine is off.

---

## Phase 6 — Surface draw-with-me tutor and replay (N4 / idea #7)

**Goal:** let users learn from generated construction guides and replay existing
strokes.

**MVP behavior:** user enters “draw a fox”, reviews generated guide strokes in the
normal diff preview, accepts them onto a ghost guide layer, and steps through the
construction with Next/Back/Skip/Redo controls.

### Tasks

- [x] Decide product gating: experimental-only behind `inkAuthoring`, or split a
  non-ink guide-layer MVP from ink replay/export work.
  - Decision: **experimental-only**, matching Phase 5. The tutor (guide-layer
    reveal) and replay both stay gated behind the experimental ink engine
    (`inkAuthoring`, default-off), honouring principle 4. Rather than building a
    separate non-ink MVP, the "Draw with me" panel is *surfaced* for every note
    but its launch controls (Start / Replay drawing) render disabled, under a
    line of copy, when ink is off — discoverable without flipping the default.
- [x] Add a “Draw with me” entry point in the AI sheet or art-assist menu.
  - A "Draw with me" `AssistChip` in `AiSideSheet`'s `ScopeAndCannedPromptRow`
    (alongside "Palette help", "Critique", "Brush designer"), available for both
    notes and icons and disabled while the panel is already open. Opens
    `DrawWithMePanel`.
- [x] Wire prompt submission to `startDrawWithMe(prompt)`.
  - `DrawWithMePanel` has a prompt field + example subjects (`a fox`, `a simple
    house`, …). `NoteEditorViewModel.submitDrawWithMe()` validates the ink gate +
    non-blank prompt and hands off to the existing `startDrawWithMe(prompt)`,
    which routes through the unchanged GENERATE → staged `PendingEdit` →
    `acceptTutorEdit` (guide-layer reparenting) path. The launcher closes on
    submit; progress lives in the conversation turn + on-canvas diff preview.
- [x] Add tutor controls bound to `tutorNext`, `tutorBack`, `tutorSkip`,
  `tutorRedo`, and `endTutor`.
  - `TutorControlsBanner` (a canvas overlay, not buried in the AI sheet, so it
    survives the sheet being closed while tracing) shows the current step
    instruction, a progress bar (`TutorSession.progress`), and Back / Skip /
    Redo / Next / End buttons wired to `tutorBack` / `tutorSkip` / `tutorRedo` /
    `tutorNext` / `endTutor`. Each button enables/disables off the pure
    `TutorSession` state (`cursor`, `isComplete`, `currentStep`).
- [x] Ensure `tutorHiddenIds` is observed by the canvas so unrevealed guide items
  stay hidden.
  - Already wired: `NoteEditorScreen` collects `viewModel.tutorHiddenIds` and
    passes it to `DrawingSurfaceView`, which suppresses those ids during
    rasterization (`DrawingSurface.setTutorHidden`). Phase 6 verified this and
    additionally **unions** the replay playhead's hidden ids into the same canvas
    channel so the two N4 reveal surfaces share one suppression path.
- [x] Add a replay playhead UI for `buildReplayTimeline()`.
  - A new `ReplayTimeline.hiddenItemIdsAt(positionMs)` exposes the not-yet-drawn
    ids for a given playhead position. `NoteEditorViewModel` adds a `replayState`
    (`ReplayUiState`) + `replayHiddenIds` flow driven by `openReplay` /
    `seekReplay` / `setReplayPlaying` / `closeReplay` (a wall-clock play loop
    reveals marks in draw order). `ReplayControlsBanner` is the play/pause +
    scrub overlay. Starting replay ends any live tutor session so the two reveal
    channels don't fight.
- [x] Defer video/GIF export until replay playback is shippable.
  - Intentionally deferred. Replay is interactive on-canvas playback only;
    partial-stroke clipping (`ReplayTimeline.itemsAt`) and frame encoding stay
    the device-only export path, untouched here.
- [x] Add tests for guide-layer creation, accept flow, step transitions, and
  hidden-item filtering.
  - Guide-layer creation, the step / skip / back / redo state machine, and
    session-level hidden-item filtering are covered by the existing
    `TutorGuideTest` (ghosted/editable/on-top guide layer, `assignToGuide`
    reparenting, `planSteps` ordering + clutter cap, and `TutorSession`
    transitions incl. `hiddenItemIds`). The accept flow's pure pieces
    (reparent + `planSteps` + canonical payload) are covered there and in
    `ReplayTutorParityTest`. Phase 6 adds replay-playhead reveal coverage to
    `ReplayTimelineTest` (`hiddenItemIdsAt`: only the first mark at the start,
    draw-order reveal, and an empty hidden set at the end).

### Acceptance criteria

- [x] Generated tutor geometry is previewed before it becomes a guide layer.
  - `startDrawWithMe` stages the authored construction strokes as a `PendingEdit`
    that renders through the shared `AiEditDiffOverlay` + banner; only an explicit
    Accept runs `acceptTutorEdit`, which reparents them onto the guide layer.
- [x] Guide strokes are editable and visually distinct from user strokes.
  - `TutorGuide.buildGuideLayer` is a ghosted (45% opacity) but unlocked + visible
    layer on top; the reparented strokes stay canonical `StrokeCodec` payloads, so
    they read as a trace-over guide yet erase / edit like any other layer.
- [x] Tutor controls are recoverable: Back/Skip/Redo/End never corrupt the note.
  - Every control routes through the immutable `TutorSession` (each transition
    returns a new value), so they only change *what is revealed*, never the
    geometry; `endTutor` just clears the session and leaves the guide layer and
    whatever the user traced intact on the note.

---

## Phase 7 — Finish named style preset restyling

**Goal:** let users restyle existing selections into named visual styles, not just
copy local style or generate new icons from references.

**MVP behavior:** user selects items, chooses “Flat icon”, “Line art”,
“Isometric”, or “Sticker”, previews restyle ops, and accepts/rejects.

### Tasks

- [x] Define a small curated preset catalog with prompt text and constraints for
  each style.
  - `StylePresetCatalog` (`data/notes/StylePreset.kt`) holds the four MVP looks
    (Flat icon / Line art / Isometric / Sticker). Each `StylePreset` carries an
    `id`, `displayName`, UI `tagline`, a plain-language `promptText`, and a list
    of curated `constraints`. `buildInstruction()` composes those into the
    user-message body, closing with two fixed guardrails
    (`StylePreset.SUBJECT_GUARD` / `NO_ADD_GUARD`).
- [x] Add style preset chips to the AI sheet, selection toolbar, or art-assist
  menu.
  - A "Restyle" `AssistChip` in `AiSideSheet`'s `ScopeAndCannedPromptRow`
    (alongside Palette help / Critique / Brush designer / Draw with me), available
    for both notes and icons and disabled while the panel is open. Opens
    `RestylePanel`, which renders one `StylePresetCard` per catalog preset with an
    "Apply" action (a spinner marks the preset in flight).
- [x] Route presets through EDIT mode with selection scope and `restyle`,
  `recolor`, `smooth`, `simplify`, and `replace_with_shape` ops allowed.
  - New `AskMode.RESTYLE` + `NoteAiService.collectRestyle` serialize the
    selection-scoped items via `VectorCanvasJson`, attach the rasterized preview
    for vision models, and emit a standard `AiChunk.EditPreview`.
    `NoteEditorViewModel.applyStylePreset` fires the request and stages the result
    as a `PendingEdit` through `EditPreviewController.simulate`, so it previews
    through the same `AiEditDiffOverlay` + banner as every other AI edit.
- [x] Keep `StyleTransfer` as a separate local “copy style from selection” tool;
  do not conflate it with named AI presets.
  - `StyleTransfer` / `StyleClipboard` are untouched. The restyle path never
    references them, and `RestylePanel` carries a footnote distinguishing the
    AI named-look restyle from "copy/paste the exact style of one item onto
    another".
- [x] Add validation to discourage adding new subject matter during restyle.
  - `RestyleParser.parse` runs the reply through `EditOpsParser` (dropping
    invented / locked-layer ids) and then keeps only `StylePreset.isRestyleOp`
    survivors — the non-additive, non-moving subset. `add_path` / `add_shape`
    (new subject matter), `transform` (moving/scaling), `delete`, `group`,
    `set_layer`, and `merge_paths` are moved to `rejected` and never applied.
    The `RESTYLE` system message pins the same rules as defence in depth.
- [x] Add tests for preset prompt construction and valid/invalid restyle docs.
  - `StylePresetTest` (catalog shape + unique ids, `byId`, `buildInstruction`
    weaves name/description/every constraint + the subject guardrails,
    `isRestyleOp` subset) and `RestyleParserTest` (keeps the five safe ops, drops
    additive/destructive/moving/structural ops into `rejected`, drops unknown-id
    ops, fenced + bare JSON, empty-ops success, all-unsafe → empty success,
    non-JSON failure, garbage no-throw). All green.

### Acceptance criteria

- [x] Existing geometry remains recognizably the same subject.
  - The op whitelist excludes every additive op and `transform`, so a restyle
    only changes colour / width / opacity / smoothing or cleans a wobbly stroke
    into a crisp shape — the geometry and subject stay put. The shared simulator
    drops locked-layer items and no-op restyles as a backstop.
- [x] Presets are previewable and rejectable.
  - Applying a preset stages a `PendingEdit` through the shared diff overlay +
    banner; nothing lands until an explicit Accept on the canvas.
- [x] Style-copy and AI named presets are clearly different in UI copy.
  - The restyle panel header reads "Restyle into a look" and a footnote points
    users at copy/paste style for exact style transfer, keeping the two tools
    distinct.

---

## Phase 8 — Expand prompt-to-vector from icons to small scenes

**Goal:** broaden GENERATE from a single icon to a compact editable scene made of
multiple grouped elements.

**MVP behavior:** user prompts “small campsite at night”; the model returns a
scene plan with groups/layers and editable `add_path`/`add_shape` geometry that
lands inside the current viewport or selected frame.

### Tasks

- [x] Add a `generateScene` request variant or prompt flag over existing EDIT +
  `generate=true` plumbing.
  - A scene is an EDIT + `generate=true` request with the new `AskRequest.scene`
    flag (+ `sceneComplexity`). `NoteAiService.collectGenerate` branches on it
    (never combined with `refine`): scene → `SCENE_GENERATE_SYSTEM_MESSAGE` +
    `buildScenePromptBody`. The ViewModel's `submitScene()` routes through the
    existing `submitAiEdit(... generate = true, scene = true, sceneFit = …)` →
    `runStream` → staged-`PendingEdit` path, so the whole preview/diff/accept
    pipeline is reused unchanged.
- [x] Extend generation prompt constraints for multi-object layout, grouping,
  relative placement, and layer names.
  - `EditOpsParser.SCENE_GENERATE_SYSTEM_MESSAGE` asks for a small set of
    distinct objects with believable relative placement on a square artboard,
    and requires **every** authoring op to carry a `"group"` label naming the
    object it belongs to (parts of one object share a group; "layer" is accepted
    as an alias). `buildScenePromptBody` states the artboard size, the per-
    complexity object cap, and the simple/detailed hint.
- [x] Ensure generated object ids map cleanly to groups/layers in the edit-op
  parser and preview simulation.
  - `EditOp.AddPath` / `EditOp.AddShape` gained an optional `group` field;
    `EditOpsParser.parseGroupLabel` reads `"group"`/`"layer"`. In
    `EditPreviewController.simulate`, each distinct authored group label is
    mapped to one shared `NoteItem.groupId` (a generated UUID) and stamped on the
    object's parts *before* placement, so they survive the fit transform and
    select/move together on the flat-group model (per the product decision —
    real named-layer creation inside a staged edit is out of scope).
- [x] Add placement UI: current viewport, selected frame, or icon bounds.
  - A `ScenePlacement` enum (Visible area / Frame / Fit content) with chips in
    `ScenePanel`. `NoteEditorViewModel.sceneFitTarget` resolves the choice to an
    inset world rect (each choice falling back through the others, then a default
    box), passed as `authoredFit` so the whole scene is uniformly fit into it.
    The screen reports the live visible world rect via a new
    `setVisibleWorldRect` bridge (the VM doesn't own the `ViewportController`).
- [x] Add optional “simple / detailed” complexity chips.
  - A `SceneComplexity` enum (SIMPLE = ≤6 objects, DETAILED = ≤12) with chips in
    `ScenePanel`; it drives both a one-line prompt hint and the per-request
    object cap.
- [x] Add tests for grouped add ops, placement transforms, and scene-size limits.
  - `SceneGenTest` (cap trims excess adds into `rejected`, ignores non-add ops,
    no-op fast path, inset maths, complexity bounds); `EditOpsParserTest`
    (group/layer label parsing, blank→ungrouped, scene system message shape);
    `EditPreviewControllerTest` (shared groupId per object, ungrouped→null, and
    groups surviving `authoredFit` placement while landing inside the target
    rect); `NoteAiServiceTest` (scene mode uses the scene system message + scene
    prompt body and caps objects). All green.

### Acceptance criteria

- [x] Scenes remain editable vectors, not raster images.
  - The model authors `add_path` / `add_shape` ops only; they become normal
    `kind=path` / `kind=shape` `NoteItem`s through the same builders as single-
    icon generation — there is no raster output anywhere in the scene path.
- [x] Generated geometry is bounded and does not appear far off-canvas.
  - A scene always passes an `authoredFit` target (the inset placement rect), so
    `EditPreviewController.placeAuthored` uniformly scales/centres the union of
    all objects into it regardless of the model's raw coordinates; the per-
    complexity object cap keeps the scene compact.
- [x] The preview clearly shows all added elements before acceptance.
  - The grouped result stages as a `PendingEdit` and renders through the shared
    `AiEditDiffOverlay` + banner (every object in the green "added" bucket);
    nothing lands until an explicit Accept on the canvas, after which the whole
    scene is auto-selected.

---

## Phase 9 — Metadata and accessibility helpers

**Goal:** add low-risk, non-mutating AI helpers that improve organization and
exports.

**Status: implemented and verified (unit-test green) as of 2026-06-17.** A
"Title & tags" panel in the AI sheet asks the model for a short title, a few
keyword tags, and a one-sentence description, then surfaces each as an editable
field the user accepts / edits / discards. Applying a field writes only the note
title, the `note_tags` table, or the note's export alt text — never canvas
geometry. The description rides into PNG / SVG exports as accessibility metadata.

### Candidate tasks

- [x] Auto-title and auto-tag notes/icons from OCR, `VectorCanvasJson`, and/or
  raster preview.
  - New `AskMode.SUGGEST_METADATA` + `NoteAiService.collectMetadata` serialize
    the in-scope items via `VectorCanvasJson`, attach the rasterized preview for
    vision models, and fall back to OCR text (`resolveOcrText`) for non-vision
    models (`buildMetadataPromptBody`). `MetadataParser.SYSTEM_MESSAGE` pins a
    jargon-free JSON contract; the reply becomes a validated
    `NoteMetadataSuggestion` (`data/notes/NoteMetadataSuggestion.kt`) and a
    terminal `AiChunk.MetadataResult`.
- [x] Generate alt text / description for PNG and SVG export.
  - The suggestion's `description` is applied to the new nullable
    `Note.altText`. `NoteSvgExporter.renderSvg` emits `<title>` (note title) +
    `<desc>` (alt text) right after the opening `<svg>` when alt text is present;
    `NoteExporter.exportPng` embeds it as a PNG `tEXt` "Description" chunk via the
    pure-JVM `PngMetadata.withDescription`. Both are no-ops without alt text, so
    un-described exports stay byte-identical to before.
- [x] Add settings for auto-suggest vs manual-trigger behavior.
  - `PreferencesManager.noteMetadataAutoSuggest` (default off → manual; when on,
    `openMetadata()` fires the AI suggestion as the panel opens) and
    `exportEmbedMetadata` (default on; gates the export alt-text embedding).
    Both are exposed as Settings switches ("Auto-suggest title & tags" /
    "Embed alt text in exports") via `SettingsViewModel` / `SettingsScreen`.
- [x] Store generated metadata in existing note/tag/export metadata structures;
  add migrations only if no suitable fields exist.
  - Title reuses the existing `Note.title`; tags reuse the existing `note_tags`
    junction table (`NoteRepository.setTags`, merged + de-duped via `IconTags`).
    Only alt text had no home, so `MIGRATION_23_24` adds the nullable
    `notes.altText` column (DB version 23 → 24, schema `24.json` exported,
    migration registered in `AppModule`).
- [x] Add tests for title/tag validation, duplicate tags, and export metadata.
  - `MetadataParserTest` (title/tag/description validation, duplicate-tag
    de-dup + normalization, caps, alias keys, prose-only / garbage failure),
    `PngMetadataTest` (well-formed `tEXt` chunk + CRC, blank/non-PNG no-op),
    `NoteSvgExporterMetadataTest` (`<title>`/`<desc>` emission, XML escaping,
    absent-alt-text + disabled no-op), and two new `NoteAiServiceTest` cases
    (vision happy path + non-vision OCR inlining). `Migration_23_24_Test`
    (androidTest) mirrors the existing migration-test convention. All JVM tests
    green (only the two pre-existing, documented framework-mock failures remain).

### Acceptance criteria

- [x] Metadata suggestions never alter canvas geometry.
  - The flow only ever writes the note title, the tag table, or `Note.altText`;
    it never stages a `PendingEdit` or touches `items`. The request runs
    read-only over the serialized scope.
- [x] Users can accept, edit, or discard suggested text.
  - `MetadataPanel` renders the title and description in editable text fields and
    the tags as toggleable chips; nothing is applied until an explicit "Use
    title" / "Add tags" / "Save description" tap. Closing the panel discards
    everything unapplied.
- [x] Exported alt text is short, accurate, and optional.
  - The description is capped at `NoteMetadataSuggestion.MAX_DESCRIPTION_LENGTH`
    (240 chars) and the system message asks for one ≤30-word sentence. Embedding
    is optional twice over: it is skipped unless the note carries alt text *and*
    the `exportEmbedMetadata` setting is on, so exports never gain metadata the
    user didn't opt into.

---

## Phase 10 — Longer-horizon AI art-assist ideas

Defer these until the core roadmap above is stable:

- **Conversational multi-turn editing:** pack previous turns and edit outcomes so
  “make it bigger”, “no, just the circle”, and “now blue” resolve correctly.
- **Diagram cleanup:** recognize boxes/arrows/text and use existing snapping,
  connector routing, and restyle ops to normalize diagrams.
- **Style consistency across an icon set:** detect inconsistent stroke weights,
  corner radii, and palettes across selected icons.
- **AI flat-fill / color-this-in:** detect enclosed regions in line art and add
  filled shapes behind strokes.
- **Narrated timelapse:** align audio transcript/captions to replay timelines.
- **Semantic art search:** embeddings for OCR text and visual thumbnails.
- **Reference-image palette/style:** extract palette and style descriptors from a
  photo to feed palette assistance and style presets.
- **Variations / show me three:** orchestrate multiple GENERATE/REFINE candidates
  side-by-side for user selection.

## 3. Suggested session slicing

Use this sequence for multi-session implementation:

1. **Session A:** Phase 1 audit/comment fixes and tests.
2. **Session B:** Phase 2 palette response contract + local fallback + parser
   tests.
3. **Session C:** Phase 2 palette UI and staged recolor preview.
4. **Session D:** Phase 3 critique schema + prompt/parser tests.
5. **Session E:** Phase 3 critique UI and preview-action cards.
6. **Session F:** Phase 4 brush designer UI over existing `designBrush` path.
7. **Session G:** Phase 5 magic-wand/snap UI over existing headless N2 path.
8. **Session H:** Phase 6 tutor start/step UI over existing headless N4 path.
9. **Session I:** Phase 7 named style presets.
10. **Session J:** Phase 8 scene generation.
11. **Session K:** Phase 9 metadata/accessibility helpers.

## 4. Out-of-scope for this plan

- Replacing the drawing engine or flipping AndroidX Ink default-on.
- Raster image generation as the primary output for these features.
- Any feature that commits canvas changes without a previewable edit-op or an
  explicit non-canvas metadata confirmation.
