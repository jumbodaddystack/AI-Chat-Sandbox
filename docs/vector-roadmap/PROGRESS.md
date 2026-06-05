# Vector Icon Editor — Progress & Handoff

**This is the single source of truth for where the vector-icon-editor work stands.**
Point a fresh session at this file (`docs/vector-roadmap/PROGRESS.md`) and it has
everything needed to pick up the next phase.

---

## 0. Orientation (read first)

**Goal:** grow the app from "icon sketchpad + import-only tune-up" into a true
editable vector icon editor. Full vision + gap analysis: [`00-overview.md`](00-overview.md).

**The core architectural bet:** promote the immutable `data/vector/VectorDocument`
into the *live editable model* every surface edits, by layering an editable node
view (`data/vector/edit/EditablePath`) on top of it and reusing the existing
parser / normalizer / writer / snapping / viewport / reducer machinery rather
than building a parallel stack.

**Per-phase plans** (each: scope, concrete files, reuse list, test plan, risks):
- Phase 1 — [`phase-1-editable-bezier-scene-graph.md`](phase-1-editable-bezier-scene-graph.md)
- Phase 2 — [`phase-2-boolean-path-ops.md`](phase-2-boolean-path-ops.md)
- Phase 3 — [`phase-3-pixel-perfect-pipeline.md`](phase-3-pixel-perfect-pipeline.md)
- Phase 4 — [`phase-4-unify-domains.md`](phase-4-unify-domains.md)
- Phase 5 — [`phase-5-production-polish.md`](phase-5-production-polish.md)

**Dependency order:** Phase 0→1 is the keystone. 2, 3, 4 each depend on the Phase 1
editable model; they can then proceed with some parallelism. 5 is post-core polish.

---

## 1. Working agreement (how to run a session here)

- **Branch:** `claude/vector-roadmap-next-phase-ceGqz` (carries all the Phase 0 +
  Phase 1a work). Develop, commit, and push here. Do **not** open a PR unless
  explicitly asked.
- **Build/test env:** Android SDK is installed per `CLAUDE.md`. In a fresh web
  container run the one-time SDK setup from `CLAUDE.md` first if `./gradlew` fails
  with "SDK location not found".
- **Run the edit tests:**
  ```
  ./gradlew :app:testDebugUnitTest --console=plain --tests "com.aichat.sandbox.*.edit.*"
  ```
  Full suite: `./gradlew :app:testDebugUnitTest --console=plain`.
- **Known pre-existing failures (NOT regressions):** ~22 failures in
  `NoteSvgExporterTest`, `NoteVectorDrawableExporterTest`, `NoteAiServiceTest`
  (`android.graphics.Color` / `android.util.Log` "not mocked"). Suite is "green"
  if these are the only failures. Ignore them; focus on `*.edit.*` and the tests
  for your phase.
- **Style:** pure, JVM-testable cores (no Android/Compose imports) wherever
  possible — mirror the existing `VectorTuneupReducer` / `Snap` / `ViewportController`
  pattern. Match surrounding code's idiom and comment density.
- **End every session by updating this file** (see §4 Handoff protocol).

---

## 2. Status checklist

Legend: `[x]` done · `[~]` in progress · `[ ]` not started

### Phase 0 — editable model foundation (UI-free) — ✅ COMPLETE
- [x] `data/vector/edit/EditablePath.kt` — `EditAnchor`/`ControlPoint`/`EditSubpath`/`EditablePath`, `AnchorType`
- [x] `data/vector/edit/EditablePathFactory.kt` — `VectorPath → EditablePath` (reuses `VectorPreviewPathNormalizer`; quad→cubic, closed-curve fold, anchor classify)
- [x] `data/vector/edit/EditablePathSerializer.kt` — `EditablePath → PathCommand[]/VectorPath` (reuses `PathDataFormatter`)
- [x] `VectorDocument.replacePath(id, newPath)` helper
- [x] Tests: `EditablePathRoundTripTest`, `VectorDocumentReplacePathTest` (14 tests, green)
- [x] Committed + pushed

### Phase 1 — node editor (the rest of Phase 1) — 🟢 NEARLY COMPLETE (1a–1f done → **only on-device manual verify left**)
Build in this order (each step is shippable + testable on its own):
- [x] **1a. Pure reducer core (no UI):** `ui/screens/vector/edit/VectorEditState.kt`,
  `VectorEditAction.kt`, `VectorEditReducer.kt`. Actions: `BeginEdit`, `SetTool`,
  `SetSnapMask`, `StartPath`, `PlaceAnchor`, `DragHandle`, `CommitPath`,
  `SelectAnchor`, `ClearSelection`, `MoveSelection`, `InsertAnchorOnSegment`
  (de Casteljau split), `DeleteSelected`, `SetAnchorType`, `ToggleSubpathClosed`,
  `Undo`, `Redo`, `ApplyToDocument`. Snapshot-based undo (cap 200). **Tests:**
  `VectorEditReducerTest` (21 tests, green) — pen-draws a triangle; grid snap;
  insert splits a cubic without changing the curve (sampled) and a line at its
  midpoint; closing-segment insert wraps to start; delete; corner→smooth→symmetric→
  corner; close/open; write-back; undo/redo inverts every action exactly + cap.
- [x] **1b. Hit-testing:** `ui/screens/vector/edit/EditHitTest.kt` (pure; world coords
  + world-space tolerance = screen-px ÷ viewport scale). Returns a `Hit` sum type
  (`Anchor` / `Handle{side: IN/OUT}` / `Segment{segmentIndex, t, x, y}`); `hitTest(...)`
  combines them with paint-order priority (handle of selected anchors → anchor →
  segment). Line projection + de Casteljau sample-and-refine for cubics; closing-segment
  index wraps to the start anchor to match the reducer. **Tests:** `EditHitTestTest`
  (14, green) — nearest anchor; zoom-dependent pick/miss; handle only for candidates +
  IN/OUT sides; line midpoint `t`; closing-segment wrap; open subpath has no closing
  segment; cubic point via de Casteljau; combined-priority; a returned `Segment` feeds
  `InsertAnchorOnSegment` and lands the new node exactly on the reported curve point;
  `worldTolerance` inverse-scales.
- [x] **1c. ViewModel:** `VectorEditViewModel.kt` (StateFlow host, Hilt — mirror `VectorTuneupViewModel`).
  Holds `MutableStateFlow<VectorEditState>`, `dispatch(action)` funnels through the reducer,
  owns the `ViewportController`, and maps gestures → actions (`onTap`/`onDragStart`/`onDrag`/
  `onDragEnd`, `pan`/`zoom`). Continuous drags are coalesced to a single undo step. Also added
  the missing reducer action **`MoveHandle(id, side, x, y)`** (corner=independent, smooth=colinear
  keep-length, symmetric=mirror) so `Hit.Handle` drags have a home. **Tests:** `VectorEditViewModelTest`
  (8) + 4 new `MoveHandle` reducer tests → `*.edit.*` now **59, green**.
- [x] **1d. Canvas + gestures:** `ui/screens/vector/edit/VectorEditCanvas.kt` — Compose
  `Canvas` that renders via the existing `VectorPreviewCanvas` internals
  (`preparePreviewPaths`/`buildComposePath`/`drawPreparedPath`) under the VM's
  `ViewportController` mapping (`screen = world*scale + offset`). Draws static doc paths
  (minus the path under edit), the **live** editing path's geometry + a constant-width
  skeleton outline (so an unpainted edit path is still visible), an artboard border, and
  the overlay: anchor knobs (selected = filled accent, idle = hollow), control handles
  **only for selected anchors** (matches `EditHitTest` candidates), and the in-progress
  pen draft. Custom `awaitEachGesture` loop classifies **1-finger tap → `onTap`**,
  **1-finger drag → `onDragStart`/`onDrag`/`onDragEnd`** (honest bracket incl. cancel,
  so the VM's drag-coalesce trims correctly), **2-finger → pan/zoom** via
  `calculatePan`/`calculateZoom`/`calculateCentroid`. Fits + bounds-clamps the viewport to
  the artboard on document/size change (the one place that frames the VM's viewport).
  Pure-UI (no new reducer logic); `*.edit.*` stays **59**.
- [x] **1e. Screen + toolbar:** `ui/screens/vector/edit/VectorEditScreen.kt` — a
  `Scaffold` that collects `vm.state` (`collectAsState`), hosts `VectorEditCanvas` in
  the body (passing `vm.onTap(x,y)` non-additive + the drag/pan/zoom pass-throughs),
  a `TopAppBar` with back / **Undo** (gated on `canUndo`) / **Redo** (`canRedo`) /
  **Done** (`applyToDocument` + `onDone`), and a two-row bottom toolbar of M3 chips:
  **Pen** (`setTool(PEN)` + `startPath()` when no draft) / **Select** (`setTool(DIRECT_SELECT)`) /
  **Finish** (`commitPath`, shown in pen mode, enabled at ≥2 draft anchors) /
  **Delete** (`deleteSelected`, enabled when selection non-empty) /
  **Corner·Smooth·Symmetric** (`setAnchorType` — enabled only when **exactly one**
  anchor is selected, so each retype is one undo step; chip reads the anchor's current
  type) / **Close** (`toggleClosed` on the active subpath = the one holding the
  selection, else the last subpath) / **Snap** Grid·Angle·Endpoint (toggle the
  `Snap.MASK_*` bits via `setSnapMask`). Added VM pass-through `setAnchorType(id, type)`.
  Pure-UI (no reducer logic); compiles clean, `*.edit.*` stays **59**.
- [x] **1f. Wire into Tune-Up:** the node editor opens as a **full-screen mode hosted
  inside `VectorTuneupScreen`** (not a NavHost route — no result-passing to invent, and the
  canvas gets the whole surface instead of fighting the scrolling EDIT tab). The EDIT tab's
  new **"Node editor"** section has **Edit nodes** (enabled when exactly one path is selected →
  opens on that path) and **Draw new path** (opens the pen on an empty canvas). On **Done** the
  edited `VectorDocument` is persisted as a new `MANUAL_EDIT` version through the *existing*
  `persistManualEdit` pipeline (`VectorTuneupViewModel.persistNodeEdit`), so history/diff/export
  all keep working. New plumbing: `VectorDocument.upsertPath` (replace-or-append, so a
  drawn-from-scratch path isn't dropped), reducer `applyToDocument` now upserts + new pen paths
  get a default `#000000` fill, `VectorEditViewModel.openForNewPath`. **Tests:** +2 `upsertPath`
  + 1 reducer new-path-apply → `*.edit.*` now **62, green**. Decision resolved with user:
  embedded mode + new-path drawing (see §5).
- [x] **Open choices (resolved):** (1) **embedded in-Tune-Up full-screen mode** (not a dedicated
  route); (2) handles stored as absolute `ControlPoint`s (locked in Phase 0).
- [ ] On-device manual verify (draw closed shape, snap, undo/redo, export round-trips) — the only
  remaining Phase 1 item; needs a device/emulator (the build env is headless).

### Phase 2 — boolean ops + outline-stroke + offset — 🟢 NEARLY COMPLETE (code-complete → only on-device manual verify left)
- [x] Pure `data/vector/edit/boolean/` module (no Android imports): `Polygon` (`Ring`/`PolyShape`/`FillRule`),
  `FillRuleResolver`, `PathFlattener` (cubics→polygons via the existing sampler/simplifier),
  `PolygonClipper` (UNION/INTERSECT/DIFFERENCE/XOR + `selfUnion`), `CurveRefit` (polygon→cubic Schneider fit),
  `StrokeOutliner`, `PathOffset`, and the `PathBoolean` façade (`combine`/`outlineStroke`/`offset`).
- [x] Clipper is an **arrangement + boundary-classification** clipper (not Martinez–Rueda — see §5 deviation):
  robust on shared/collinear/coincident edges and figure-eights (golden-geometry tests prove it).
- [x] Reducer actions `BooleanOp(kind)` / `OutlineStroke` / `OffsetPath(delta)` + `BoolOpKind`
  (each one undo entry), VM pass-throughs, and a new shape-ops toolbar row in `VectorEditScreen`.
- [x] Tests: `PolygonClipperTest` (7), `PathBooleanTest` (8), `StrokeOutlinerTest` (4), `PathOffsetTest` (4),
  `CurveRefitTest` (3), `FillRuleResolverTest` (2), `VectorBooleanReducerTest` (6) → **34 new, green**.
- [x] `:app:assembleDebug` clean; `*.edit.*` now **96, green** (62 Phase 1 + 34 Phase 2).
- [ ] On-device manual verify (select 2 subpaths → Union/Subtract/Intersect/Exclude; Outline a stroked path;
  Offset ±; undo/redo each; Done → new version exports + re-imports) — headless build env can't do this.

### Phase 3 — pixel-perfect pipeline — ⬜ NOT STARTED
- [ ] Per `phase-3-pixel-perfect-pipeline.md`: keyline grids, integer-grid snap
  (`EditSnap`), multi-size artboards, grid-quantized lossless + batch export, tests.

### Phase 4 — unify the two domains — ⬜ NOT STARTED
- [ ] Per `phase-4-unify-domains.md`: notes-bridge vectorization, single canonical
  editable `VectorDocument`, route AI (notes `EditOp` + tune-up plans/redraw), lossless export, tests.

### Phase 5 — production polish — ⬜ NOT STARTED
- [ ] Per `phase-5-production-polish.md`: stroke styling, gradients, vector symbols,
  keyboard ergonomics, AI auto-trace. (Independent sub-features; pick by priority.)

---

## 3. Latest handoff (update this each session)

**Last updated:** 2026-06-04 · **Last completed:** Phase 2 (boolean ops + outline-stroke + offset)

**State of the branch:** Phase 0 + 1a–1f are merged to main (through PR #95). Phase **2** is on
`claude/vector-roadmap-next-phase-HYi8t`. `:app:assembleDebug` builds clean; `*.edit.*` tests are
green at **96** (62 Phase 1 + 34 Phase 2). No PR open. **Shape algebra is now in:** a pure
`data/vector/edit/boolean/` module does union/subtract/intersect/exclude on selected subpaths,
outline-stroke, and inset/outset, all wired into the node editor reducer + toolbar and persisting
through the existing version pipeline. The only remaining Phase 2 item is on-device manual verify
(headless env can't do it). Phases 3/4/5 can now start (all build on the Phase 1 editable model).

**Phase 2 — what exists now (for the next session to build on):**
- *(NEW)* `data/vector/edit/boolean/` — **pure JVM, no Android imports** (unit-tested like the
  reducer):
  - `Polygon.kt` — internal `Ring` (shoelace `signedArea`/`oriented`), `PolyShape` (rings + `FillRule`,
    `area`), `FillRule`.
  - `FillRuleResolver.kt` — `VectorStyle.fillType` ⇄ `FillRule` (null/unknown ⇒ NONZERO; `"evenOdd"` token).
  - `PathFlattener.kt` — `EditablePath`→`PolyShape` and `flattenSubpath`/`flattenCenterline`; reuses
    `VectorPathSampler`/`VectorPathSimplifier`; world-tolerance→step-count heuristic.
  - `PolygonClipper.kt` — `clip(subject, clip, BoolOp)` for UNION/INTERSECT/DIFFERENCE/XOR **and**
    `selfUnion(shape)`. Approach = **arrangement + boundary-classification** (split all edges at
    intersections incl. collinear overlaps → classify each by sampling the operands' winding just off
    each side → orient kept-region-on-left → chain via DCEL "next = clockwise from twin"). All in
    `Double`. Output rings correctly oriented (outer CCW, holes CW), emitted as NONZERO.
  - `CurveRefit.kt` — `refit(ring, maxError, idPrefix, cornerAngleDeg)` → `EditSubpath`. Splits at
    corners (turn angle), Schneider least-squares cubic fit per smooth run (recursive subdivide +
    Newton reparam), straight runs → handle-less anchors (serialize as `LineTo`); closed smooth loops
    fit with a continuous seam tangent so the seam anchor stays SMOOTH.
  - `StrokeOutliner.kt` — `outline(centerline, closed, width, cap, join, miterLimit)` builds the outline
    as a **union of pieces** (segment quads + per-vertex join: round disk / miter wedge / bevel / + caps),
    fused by `selfUnion`. Closed centerline → annulus (outer + inner ring). `capOf`/`joinOf` map style strings.
  - `PathOffset.kt` — `offset(shape, delta, join)` via morphology: grow = `UNION(shape, band)`, shrink =
    `DIFFERENCE(shape, band)` where `band` = stroke outline of every contour at width `2·|delta|`
    (round joins = Minkowski-with-disk). Over-shrink → empty (caller declines).
  - `PathBoolean.kt` — **public façade** (`object PathBoolean`): `Op{UNION,SUBTRACT,INTERSECT,EXCLUDE}`,
    `Options(flattenTolerance=0.25, refitMaxError=0.5, cornerAngleDeg=30)`, `combine(paths, op, newPathId)`
    (≥2; folds pairwise; SUBTRACT = subject − union(rest)), `outlineStroke(path, newPathId)` (needs
    `strokeWidth>0`), `offset(path, delta, newPathId)`. Boolean/outline results are pure fills (stroke
    cleared, canonical `fillType`); offset keeps the input style. Returns null when the result is empty.
- *(MODIFIED)* `ui/screens/vector/edit/`:
  - `VectorEditAction.kt` — `BooleanOp(kind)` / `OutlineStroke` / `OffsetPath(delta)` + top-level
    `enum BoolOpKind`.
  - `VectorEditReducer.kt` — handles all three (each `pushingUndo()` = one undo step). **`BooleanOp`
    operates on the SUBPATHS of the editing path** that hold a selected anchor (≥2 required, else no-op);
    each selected subpath becomes a single-subpath operand, the result replaces them (kept subpaths
    preserved, ids reissued `${pathId}.bopN`), selection cleared. `OutlineStroke`/`OffsetPath` act on
    the whole editing path. Imports `PathBoolean`; reducer still otherwise pure.
  - `VectorEditViewModel.kt` — pass-throughs `booleanOp(kind)`/`outlineStroke()`/`offsetPath(delta)`.
  - `VectorEditScreen.kt` — new **shape-ops toolbar row**: Union/Subtract/Intersect/Exclude (enabled when
    ≥2 subpaths selected via `selectedSubpathCount`), Outline (enabled when `strokeWidth>0`), Offset +/−
    (`OFFSET_STEP = 1f`). Snap row moved to row 3.

**Phase 1 — what exists (still current, for context):**
- *(Phase 0)* `data/vector/edit/` — `EditablePath` node model (absolute `ControlPoint`
  handles, null ⇒ straight side, all-cubic), `EditablePathFactory.fromPath(...)` (enter),
  `EditablePathSerializer.toCommands/​toVectorPath(...)` (exit), `VectorDocument.replacePath(...)`.
- *(Phase 1a, NEW)* `ui/screens/vector/edit/`:
  - `VectorEditState.kt` — immutable state: `document`, `editing: EditablePath?`,
    `activeTool` (`EditTool.PEN`/`DIRECT_SELECT`), `selection: Selection` (anchor-id set),
    `pendingPen: PenDraft?`, `snapMask: Int`, `undoStack`/`redoStack: List<EditSnapshot>`.
  - `VectorEditAction.kt` — sealed action set (see 1a checklist for the full list).
  - `VectorEditReducer.kt` — pure `reduce(state, action): VectorEditState`. Snapshot-based
    undo (every geometry action snapshots first; restore = exact inverse; cap 200,
    `NEW_PATH_ID = "edit-path"`). Reuses `Snap` for grid/angle/endpoint snapping in
    `PlaceAnchor` + single-anchor `MoveSelection`. De Casteljau split in `splitSegment`.
  - *(Phase 1b, NEW)* `ui/screens/vector/edit/EditHitTest.kt` — pure `object`. Public:
    `hitTest(path, wx, wy, tolerance, selection, handleCandidates=selection.anchorIds)`
    (priority: handle → anchor → segment), plus the individual `hitAnchor` / `hitHandle`
    / `hitSegment`, the `Hit` sealed interface (`Anchor` / `Handle{subpathId, anchorId,
    side: HandleSide.IN|OUT}` / `Segment{subpathId, segmentIndex, t, x, y}`), and
    `worldTolerance(screenPx, scale) = screenPx/scale` (`DEFAULT_TOLERANCE_PX = 22f`).
    Tolerance is **world-space** (caller divides screen px by `viewport.scale`).
    Segment iteration mirrors the reducer exactly — closed subpaths add a closing span
    `n-1` that wraps to anchor 0 — so a `Hit.Segment` feeds `InsertAnchorOnSegment`
    unchanged. Cubic picking = coarse sample (24) + ternary refine (24); handles are
    only hit for `candidates` (i.e. selected) anchors.
- *(Phase 1c, NEW)* `ui/screens/vector/edit/`:
  - `VectorEditReducer` gained **`MoveHandle(id, side, x, y)`** (the handle-drag gap the
    prior handoff flagged): places the dragged `IN`/`OUT` handle and reconciles the
    opposite per `AnchorType` — `CORNER` independent, `SMOOTH` colinear keeping its own
    length, `SYMMETRIC` mirrored. `side` reuses `EditHitTest.HandleSide`. Pure; pushes one
    undo snapshot.
  - `VectorEditViewModel.kt` — first Android-coupled file in the package. `@HiltViewModel`,
    no-arg `@Inject` ctor, **no coroutine work** (so it's JVM-testable without Robolectric).
    Holds `MutableStateFlow<VectorEditState>` (`state`), `open(document, pathId?)` starts a
    session, `dispatch(action)` = `_state.update { reducer.reduce(it, action) }`. Owns
    `val viewport = ViewportController()`. Gesture API: `onTap(screenX, screenY, additive)`
    (pen→`PlaceAnchor`; else hitTest → `Anchor`=`SelectAnchor` / `Segment`=`InsertAnchorOnSegment`
    / empty=`ClearSelection`; handles ignored on tap), `onDragStart/onDrag/onDragEnd`
    (resolves a `Handle` or `MoveSelection` target at press, applies world-space deltas live,
    and **coalesces the drag to one undo step** by trimming `undoStack` back to its pre-drag
    baseline), plus `pan`/`zoom` and thin `setTool`/`setSnapMask`/`startPath`/`commitPath`/
    `deleteSelected`/`toggleClosed`/`undo`/`redo`/`applyToDocument` pass-throughs.
    `EMPTY_DOCUMENT` (24×24, no paths) is the placeholder before `open`.
- *(Phase 1d, NEW)* `ui/screens/vector/edit/VectorEditCanvas.kt`:
  - `@Composable fun VectorEditCanvas(state, viewport, modifier, onTap, onDragStart, onDrag,
    onDragEnd, onPan, onZoom)` — the screen (1e) passes `vm.state.value` + `vm.viewport` +
    `vm::onTap`/`onDragStart`/`onDrag`/`onDragEnd`/`pan`/`zoom`. `onTap` is `(x, y) -> Unit`
    (additive multi-select is deferred to 1e; canvas taps are always non-additive).
  - Renders through the **existing** preview pipeline (no parallel renderer): static doc
    paths via `preparePreviewPaths(VectorPreviewBuilder.build(document))` minus
    `state.editing?.pathId`; the live edit path via a private `buildEditingRender(editing)`
    that runs `EditablePathSerializer.toCommands → VectorPreviewPathNormalizer.normalize →
    buildComposePath` and resolves paints with `parseVectorColor`/`toStrokeCap`/`toStrokeJoin`.
  - World→screen is the VM's `ViewportController` (`withTransform { translate(off); scale }`),
    **not** the preview's fit transform — same mapping the VM hit-tests against. Overlay
    (knobs/handles/pen-draft/border) is drawn in screen space at constant px.
  - Gesture classification lives in a custom `awaitEachGesture` (NOT `detectTransformGestures`,
    which fires for one finger too): pointer-count gates tap/drag (1) vs pan/zoom (2).
- *(Phase 1e, NEW)* `ui/screens/vector/edit/VectorEditScreen.kt`:
  - `@Composable fun VectorEditScreen(onNavigateBack, onDone, viewModel = hiltViewModel())` —
    wraps content in `StudioTheme`, collects `vm.state`, hosts `VectorEditCanvas` in a
    `Scaffold` body and a chip toolbar in `bottomBar`. `const val ROUTE_VECTOR_EDIT = "vector-edit"`.
  - Top bar: back / Undo (`canUndo`) / Redo (`canRedo`) / Done (`applyToDocument()` then `onDone()`,
    enabled when `editing != null`). The host reads back the edited doc from `vm.state.document`
    after Done (no document is passed through `onDone` yet — 1f decides that contract).
  - Toolbar (two scrollable chip rows): Pen / Select / Finish / Delete / Corner·Smooth·Symmetric /
    Close / Snap Grid·Angle·Endpoint — see the 1e checklist for exact wiring + enablement.
  - Private helpers `singleSelectedAnchor(state)` / `activeSubpath(state)` derive the targets for
    the type/close chips (pure reads of state — no logic).
  - VM gained pass-through **`setAnchorType(id, type)`** (+ `AnchorType` import).
- *(Phase 1f, NEW)* node editor ↔ Tune-Up wiring:
  - `VectorTuneupScreen.kt` — the EDIT tab gained a **"Node editor"** section (**Edit nodes**,
    enabled when exactly one path is selected; **Draw new path**). A private `NodeEditTarget`
    (`ExistingPath(pathId)` / `NewPath`) hoisted in `VectorTuneupScreenContent` drives a
    full-screen takeover: when set, `NodeEditorHost` renders `VectorEditScreen` (its own
    `hiltViewModel`) and the normal `Scaffold` early-returns. `NodeEditorHost` parses
    `state.sourceVersion.xml → VectorDocument` and calls `editVm.open(doc, pathId)` /
    `editVm.openForNewPath(doc)`; on **Done** it persists `editVm.state.value.document` via
    `tuneupVm.persistNodeEdit(...)` (only if `canUndo`, so a no-edit glance saves nothing),
    on back it discards.
  - `VectorTuneupViewModel.persistNodeEdit(document, label="Edit nodes")` — serializes the
    edited doc (`AndroidVectorDrawableWriter.write`), re-analyzes metrics, and reuses the shared
    `persistManualEdit` pipeline → a new `MANUAL_EDIT` version branched from the source.
  - `VectorEditViewModel.openForNewPath(document)` — opens for drawing (pen tool + fresh draft).
  - `VectorDocument.upsertPath(pathId, newPath)` — replace if the id exists, else **append to
    root**. Reducer `applyToDocument` now upserts (so a drawn-from-scratch path isn't dropped),
    and `commitPath` gives a brand-new path a default `#000000` fill (`NEW_PATH_FILL_COLOR`) so
    it's visible after write-back/export.
- Reusable, confirmed APIs (still): `VectorPreviewPathNormalizer.normalize`,
  `PathDataFormatter.format`, `PathDataParser.parse`, `ViewportController` (world↔screen,
  bounded clamp), `Snap` (`MASK_GRID/ANGLE/ENDPOINT`, all pure — reducer imports it and
  stays JVM-clean), `VectorPreviewCanvas` internals.

**→ NEXT ACTION:** Phase 2 is code-complete. Options for the next session:
1. **On-device manual verify** of Phases 1 + 2 (the only open checkboxes) — needs an emulator/device
   (headless env can't). Phase-2 flow: Tune-Up → EDIT tab → **Edit nodes** on a path with 2 overlapping
   subpaths → **Select**, tap an anchor in each subpath → **Union / Subtract / Intersect / Exclude** →
   confirm one editable result whose anchors drag. Outline a stroked path; Offset ±. Undo/redo each.
   **Done** → new version → **Export** to VectorDrawable + SVG and re-import.
2. **Start Phase 3** (pixel-perfect pipeline) per `phase-3-pixel-perfect-pipeline.md`, or **Phase 4/5** —
   all build on the now-complete Phase 1 editable model + Phase 2 algebra.

**Watch-outs for next session (Phase 2):**
- **Clipper choice / deviation:** the plan recommended Martinez–Rueda; I shipped an **arrangement +
  boundary-classification** clipper instead (O(n²) all-pairs split, then per-edge winding sampling,
  then DCEL contour chaining). It is robust on the degenerate cases (shared/collinear/coincident edges,
  figure-eight — all tested) and far less subtle to get right than an in-place sweep. O(n²) is fine at
  icon flattening scale. `selfUnion` is the single-pass merge used by outline/offset. If a future need
  demands huge polygons, this is the spot to swap in a sweep-line.
- **Boolean operand model (deviation from plan):** the plan said "selected *paths*", but the Phase 1
  editor holds **one** `editing: EditablePath`. So `BooleanOp` combines the **selected subpaths** of that
  one path (subpaths containing a selected anchor). Outline/offset act on the whole editing path. If you
  later want cross-path booleans, the editor must hold/select multiple paths first.
- **Lossiness is bounded + intentional:** flatten→clip→refit. `PathBoolean.Options` carries the knobs
  (`flattenTolerance` 0.25, `refitMaxError` 0.5, `cornerAngleDeg` 30). Geometry tests assert *within
  tolerance* (area/winding invariants like `|A∪B|+|A∩B| = |A|+|B|`), never exact float equality.
- **Orientation matters for `selfUnion`:** it force-orients every input ring CCW (solids) before the
  non-zero pass — oppositely-wound overlaps would otherwise cancel. `clip()` operands don't need this
  (they're already single-orientation or prior clean output).
- **Result style:** boolean/outline results are pure fills (stroke cleared, `fillType` = canonical/null
  for non-zero, which is what correctly-oriented rings render as). Offset keeps the input style. A
  fill-less subject gets `#000000` so the result is visible. `OutlineStroke`/`OffsetPath` reducer
  handlers reuse the façade's fillOnly style but re-apply `editing.name`.
- **No-ops don't push undo:** `BooleanOp` (<2 selected subpaths), `OutlineStroke` (no stroke),
  `OffsetPath` (delta 0 or over-shrink→empty) all return state unchanged. Each successful op = exactly
  one undo entry (`VectorBooleanReducerTest` asserts this + exact undo/redo inversion).
- Re-run `*.edit.*` after each step; keep them green (currently **96**). The boolean module is pure JVM
  so it's the easy part to extend with tests; composables add no JVM tests.

**Watch-outs carried over (Phase 1, still true):**
- Node editor is an **embedded full-screen mode** in `VectorTuneupScreen`, not a NavHost route;
  `ROUTE_VECTOR_EDIT` is unused. The `VectorVersion ⇄ VectorDocument` bridge is `AndroidVectorDrawableParser`
  / `AndroidVectorDrawableWriter`. `persistNodeEdit` skips saving when nothing was edited (`canUndo==false`).
- New-path write-back uses `VectorDocument.upsertPath` (append when id absent); new pen paths default to
  `#000000` fill; new-path id is `VectorEditReducer.NEW_PATH_ID = "edit-path"`.
- Reducer + hit-test + the whole `boolean/` module stay pure (no Android imports); the VM/canvas/screen +
  `NodeEditorHost`/`persistNodeEdit` glue are the Android-coupled files. Keep new geometry in the
  reducer/boolean module.
- Drags coalesce to one undo step (`onDragEnd`); handle knobs draw/grab only for selected anchors.
  Tolerance is world-space, inverse-scales (22px ÷ scale).

---

## 4. Handoff protocol (instructions to future me)

At the **end of every session**, before stopping:

1. **Update §2 checklist** — flip `[ ]`→`[~]`/`[x]` for what you touched.
2. **Rewrite §3 "Latest handoff"** — set date + last completed, summarize branch
   state, list any NEW reusable code/APIs you added, and write a crisp **→ NEXT
   ACTION** plus watch-outs. Assume the next session has zero memory of this one;
   give it exactly what it needs to start in one read.
3. **Append a per-phase handoff note** under §5 when you finish a phase (what
   shipped, key decisions, deviations from the plan doc, test status).
4. **Commit this file** with your code changes, and **push** the branch.
5. If a phase's plan doc turned out wrong/incomplete, fix the doc too — keep docs
   and reality in sync.

Keep this file the *only* thing a fresh session must read to be oriented.

---

## 5. Per-phase completion notes

### Phase 0 — editable model foundation (2026-06-04)
- **Shipped:** `EditablePath` model + `EditablePathFactory` + `EditablePathSerializer`
  + `VectorDocument.replacePath`; round-trip + replace tests (14, green).
- **Key decisions:** handles stored as **absolute** `ControlPoint`s (match
  normalizer output + on-screen hit-testing); node model normalized to **all-cubic
  absolute** coords; quads degree-elevated (exact), arcs flattened via the
  normalizer (documented token-level lossiness on edit); closed curves whose
  closing segment lands on the start are **folded** into the start anchor for clean
  node counts and exact curved-close round-trip.
- **Verified:** M/L/C/Z round-trips token-exact; relative resolves to absolute;
  quad stays curve-equivalent (sampled); `replacePath` preserves tree position.
- **Deviation from plan:** none material. The Phase 1 doc sketched handles as
  `inX/inY/outX/outY` floats; implemented as a cleaner nullable `ControlPoint` —
  equivalent, avoids half-null states.

### Phase 1 — node editor (2026-06-04)
- **Shipped (1a–1f):** pure reducer core (`VectorEditState`/`Action`/`Reducer`), pure
  `EditHitTest`, Hilt `VectorEditViewModel` (gesture→action, drag-coalesced undo), Compose
  `VectorEditCanvas` (reuses the preview pipeline) + `VectorEditScreen` (chip toolbar), and the
  Tune-Up integration: an EDIT-tab "Node editor" section that opens a **full-screen embedded mode**
  to edit one path or draw a new one, saving the result as a new `MANUAL_EDIT` version. **62 `*.edit.*`
  tests green; `:app:assembleDebug` clean.**
- **Key decisions:** (1) **embedded full-screen mode**, not a NavHost route — maximal reuse of the
  existing `persistManualEdit` version pipeline, no nav-result plumbing, and the canvas owns the whole
  surface (a pan/zoom/drag editor can't share the scrolling EDIT tab). Confirmed with the user, who
  also asked to include **new-path drawing** now. (2) Write-back uses `upsertPath` (replace-or-append)
  so from-scratch paths persist; new pen paths default to `#000000` fill so they're visible. (3) Save
  is skipped when nothing was edited (`canUndo == false`).
- **Verified:** compile + assemble clean; 62 edit tests (incl. new `upsertPath` append + reducer
  new-path-apply). **Not yet verified:** on-device manual interaction (headless build env) — the one
  open Phase 1 checkbox.
- **Deviation from plan:** the plan doc floated "VectorEditScreen.kt (or an in-Tune-Up mode)"; we did
  the in-Tune-Up mode and `VectorEditScreen` is hosted inline (its `ROUTE_VECTOR_EDIT` is currently
  unused). Added `VectorDocument.upsertPath` + `VectorEditViewModel.openForNewPath` +
  `VectorTuneupViewModel.persistNodeEdit`, none of which the plan doc anticipated but all minimal.

### Phase 2 — boolean ops + outline-stroke + offset (2026-06-04)
- **Shipped:** a pure `data/vector/edit/boolean/` module (`Polygon`, `FillRuleResolver`, `PathFlattener`,
  `PolygonClipper` + `selfUnion`, `CurveRefit`, `StrokeOutliner`, `PathOffset`, `PathBoolean` façade) plus
  reducer actions `BooleanOp`/`OutlineStroke`/`OffsetPath` (+ `BoolOpKind`), VM pass-throughs, and a
  shape-ops toolbar row in `VectorEditScreen`. **34 new `*.edit.*` tests green (96 total); `:app:assembleDebug` clean.**
- **Key decisions:**
  1. **Clipper = arrangement + boundary-classification, NOT Martinez–Rueda.** Split every edge (both
     shapes + self) at all intersections incl. collinear overlaps → for each unique edge sample the
     operands' winding a hair off each side → keep edges where the op's predicate flips, oriented
     kept-region-on-left → chain into rings via the DCEL "next = clockwise-from-twin" rule. All in `Double`.
     This is robust on icon geometry's degeneracies (shared/collinear edges, figure-eight — all tested)
     and far easier to get provably right than an in-place sweep; O(n²) is fine at flattening scale.
  2. **Offset by morphology** reusing the clipper+outliner: grow = `UNION(shape, boundaryBand(2·δ))`,
     shrink = `DIFFERENCE(shape, boundaryBand(2·|δ|))`. Over-shrink → empty → reducer no-op.
  3. **Outline by union-of-pieces** (segment quads + per-vertex join + caps) fused by `selfUnion`, which
     force-orients pieces CCW so non-zero winding doesn't cancel overlaps. Closed centerline → annulus.
  4. **Boolean operands are the editing path's selected *subpaths*** (the editor holds one path), a
     necessary adaptation of the plan's "selected paths". Outline/offset act on the whole path.
  5. **Results are pure fills** (stroke cleared, canonical `fillType`); offset keeps style; flatten→refit
     lossiness is bounded by `PathBoolean.Options` and asserted within-tolerance in tests.
- **Verified:** 34 JVM tests — clipper golden geometry (union/subtract/intersect/xor area invariants,
  disjoint-empty, concentric, shared-collinear→one-ring, figure-eight valid rings), façade round-trip
  back through `EditablePathSerializer`→`PathDataParser`, outline rectangle/annulus/round-cap/miter-limit,
  offset grow/shrink/over-shrink/concave, refit circle/rectangle/straight, fill-rule mapping, and reducer
  single-undo / no-op / undo-redo-inversion. Compile + assemble clean.
- **Not yet verified:** on-device manual interaction (headless build env) — the one open Phase 2 checkbox.
- **Deviation from plan:** clipper algorithm (above) and boolean-operand model (subpaths, above). The plan
  named files `Polygon/PathFlattener/PolygonClipper/FillRuleResolver/CurveRefit/PathBoolean/StrokeOutliner/
  PathOffset` — all delivered; added `PolygonClipper.selfUnion` and `PathFlattener.flattenCenterline` (not
  named in the plan but minimal). `VectorEditState` needed no transient-message field (no-ops just return
  state unchanged), so it was left untouched.
