# AI Art-Assist Ideas — features to help non-artists make excellent art

Status: **backlog / ideation** (not yet scheduled). Captured from the
pen-size-zoom-scaling planning session, then expanded (2026-06) with an
**AndroidX Ink (`androidx.ink`) evaluation** and a set of ink-enabled feature
ideas. These build on the AI integration that already ships, so each is
incremental rather than a from-scratch system.

## Existing stack these build on

- `NoteAiService` — ASK / EDIT / GENERATE / REFINE pipelines.
- `edit-ops` schema via `EditOpsParser` — `transform`, `recolor`, `restyle`,
  `replace_with_shape`, `smooth`, `simplify`, `merge_paths`, `add_path`,
  `add_shape`, `delete`, `set_layer`, `group`.
- `VectorCanvasJson` — compact JSON view of the canvas the model can read.
- Vision input via `NoteRasterizer` (PNG); OCR fallback for non-vision models.
- Multi-provider routing (`ApiClient` + OpenAI/Anthropic/Gemini adapters),
  capability flags in `ModelCapabilities`.
- Existing canned actions (`CannedEditPrompts`): AI clean-up, auto-shape,
  simplify, flat-style, add-detail, recolor; plus "Make real" sketch refine.

### Current inking engine (custom, mature — what ink would touch)

The drawing core is hand-built and production-grade. Any ink adoption has to
respect these as the incumbents:

- **Input capture** — `DrawingSurface` (plain `View`): `MotionEvent` history
  iteration for S-Pen oversampling, screen↔world transform via
  `ViewportController`, one-frame look-ahead from
  `androidx.input:motionprediction`.
- **Rendering** — `StrokeRenderer.drawStrokePath` (quadratic-Bézier spline
  between sample midpoints, per-segment variable width/alpha); off-screen
  baking via `NoteRasterizer`.
- **Tool dynamics** — `ToolDynamics` maps pressure/tilt → width/alpha per tool
  (pen / pencil / highlighter / marker).
- **Brushes** — `BrushPreset` (color, width, opacity, taper, jitter,
  pressure-curve, texture) + procedural `TextureRegistry`
  (smooth/charcoal/watercolor/marker ALPHA_8 tiles).
- **Geometry** — `HitTest` (point-to-segment, AABB), `LassoController`
  (polygon containment), area/stroke eraser, `ShapeRecognizer` (line / rect /
  ellipse / polygon fit on hold-to-snap), `InkBeautifier` (RDP + Chaikin),
  `StrokeOutliner` (variable-width outline for SVG export).
- **Storage** — `StrokeCodec` v1 `[x,y,pressure,tilt]` / v2
  `[x,y,pressure,tilt,timestamp]` little-endian binary in `NoteItem.payload`
  (Room). Timestamps (v2) sync strokes to audio recordings.

## Ideas (roughly easiest → most ambitious)

1. **"Beautify my stroke" live assist** — as the pen lifts, offer a one-tap
   cleanup that snaps wobbly lines to clean shapes. Extends the existing
   `smooth` / `AUTO_SHAPE` / `replace_with_shape` ops. Highest non-artist payoff
   for the least new code.
2. **Guided composition / layout critique (ASK + vision)** — "How can I improve
   this?" returns concrete, beginner-friendly suggestions (balance, spacing,
   contrast), optionally surfaced as applicable edit-ops.
3. **Reference-driven style presets** — extend GENERATE's style-reference gallery
   so the user picks a style ("flat", "line-art", "isometric") and the AI
   restyles the selection to match.
4. **Text-to-vector scene/icon from a prompt** — broaden GENERATE beyond single
   icons to small multi-element scenes via `add_path` / `add_shape`. No
   image-generation dependency; output stays editable vectors.
5. **Palette & color-harmony assistant** — AI suggests a cohesive palette and
   applies it via `recolor`. Big win for non-artists who struggle with color.
6. **Auto-vectorize a photo (AI-guided trace)** — combine the existing
   `AiBitmapTracer` with an AI pass that picks tracing parameters and cleans the
   result into editable strokes.
7. **Step-by-step "draw with me" tutor** — AI breaks a subject into construction
   shapes and ghosts them on a guide layer for the user to trace (`add_shape` on
   a dedicated layer).
8. **Smart constraints / snapping suggestions** — AI proposes alignment/symmetry
   it can enforce (align edges, mirror), surfaced as edit-ops the user accepts.

## Sequencing notes

- **Items 1–5** are the highest value-to-effort and lean entirely on existing
  infrastructure — good candidates for the next round.
- **Items 6–8** are larger (new tracing/tutor/constraint flows) and warrant their
  own phase docs.

---

# AndroidX Ink (`androidx.ink`) evaluation

> **Bottom line:** ink reached **stable 1.0.0 (Dec 2025)** and supports
> **minSdk 21** — well below our minSdk 26, so there is **no API-level barrier**
> (an earlier worry that ink needed API 29 was wrong; API 29 only unlocks
> *optional* low-latency front-buffering, API 34 *optional* richer rendering).
> Our custom engine is mature, so the right move is **targeted / incremental
> adoption** of the modules where ink clearly out-classes hand-rolled code,
> behind a feature flag, **with the v1/v2 `StrokeCodec` staying canonical**.

## Module map (what ships, and our interest level)

| Module | Provides | Interest |
|---|---|---|
| `ink-strokes` | `Stroke` (immutable), `StrokeInput`, `StrokeInputBatch` | High — interchange + geometry input |
| `ink-authoring` | `InProgressStrokesView`, low-latency front buffer, input smoothing/prediction | High — drawing feel + beautify |
| `ink-brush` | `Brush`, `Brush.Builder`, `BrushFamily` (programmable in 1.1), `StockBrushes`, `BrushPaint.TextureLayer` | High — brush richness |
| `ink-geometry` | `PartitionedMesh`, `Box`, `Parallelogram`, intersection / coverage / hit-testing, lasso→mesh | High — selection + snapping |
| `ink-rendering` | `CanvasStrokeRenderer`, `ViewStrokeRenderer` | Medium — only if we render ink strokes |
| `ink-storage` | `StrokeInputBatch` (de)serialization, version-compatible `BrushFamily.decode()` | Medium — interop, not our source of truth |
| `*-compose` | Compose interop for each module | Low now (our surface is a `View`) |
| `ink-nativeloader` | JNI loader for the native geometry/rendering core | Transitive |

Key properties worth noting for our pipelines:
- Runs on **server-side JVM (Linux x86_64)**, not just Android — geometry/format
  code could run in a backend or test harness without an emulator.
- `StrokeInput` carries **position, timestamp, pressure, tilt, orientation** —
  a superset of our v2 lane set (we don't currently capture orientation).
- `Stroke` = `ImmutableStrokeInputBatch` (inputs) + `Brush` (style) +
  `PartitionedMesh` (geometry) — clean separation that maps onto our
  payload / `BrushPreset` / derived-bounds split.

## Adoption principles (decided)

1. **Targeted / incremental, flag-gated.** Add ink modules where they win;
   keep `DrawingSurface` + `StrokeRenderer` as the default path. Each ink
   integration sits behind a build/runtime flag so it can be A/B'd and reverted.
2. **`StrokeCodec` v1/v2 stays the source of truth.** No data migration. We
   convert our samples → `StrokeInputBatch` *at runtime* only where an ink
   module is invoked (geometry queries, authoring, ink rendering). This keeps
   existing notes byte-identical and the change fully reversible.
3. **`BrushPreset` stays user-facing; map it onto `BrushFamily` on demand.**
   A `BrushPreset → Brush/BrushFamily` adapter preserves color / width /
   opacity / taper / jitter / pressure-curve / texture semantics. ink's brush
   model becomes a *rendering/encoding target*, not a replacement for presets.

### Sample ↔ ink conversion seam (the one new primitive everything shares)

A small bidirectional adapter is the linchpin for every ink integration:

```
StrokeCodec floats  ── toInputBatch() ──▶  StrokeInputBatch / Stroke
[x,y,pressure,tilt,(t)]                     (+ Brush from BrushPreset)
        ▲                                          │
        └────────────  fromStroke()  ◀─────────────┘
```

Build this first (call it `InkInterop`), unit-test the round-trip on JVM
(ink runs headless), and the rest of the work composes on top of it.

## Where ink helps each of the four capability areas

### A. Programmable brushes + textures  (`ink-brush`)
- Map `BrushPreset` → `Brush`/`BrushFamily`; render via `CanvasStrokeRenderer`
  behind the brush flag. Gains: `StockBrushes` (pressure pen, pencil, laser,
  highlighter with `SelfOverlap`), multi-`TextureLayer` brushes, seed-based
  randomized behaviors — richer than our single-shader `TextureRegistry`.
- 1.1's **public programmatic brush API** means an AI can emit brush parameters
  directly (see "AI brush designer" below).
- Keep our procedural textures as `TextureBitmapStore` inputs so existing
  presets render unchanged.

### B. Mesh geometry — selection / snapping  (`ink-geometry`)
- Convert committed strokes → `PartitionedMesh` for **robust intersection /
  coverage / hit-testing**, replacing the point-to-segment loops in `HitTest`
  for the cases that need accuracy (lasso of overlapping strokes, partial
  erase, "what's inside this region").
- ink already ships **lasso → mesh** conversion, directly upgrading
  `LassoController`.
- Mesh coverage/intersection is the enabler for the **constraint/snap engine**
  (idea #8): detect near-alignment, shared edges, near-symmetry → propose snaps.

### C. Low-latency authoring + smoothing  (`ink-authoring`)
- `InProgressStrokesView` gives a true **front-buffered** low-latency stroke
  layer (API 29+) plus **built-in input smoothing/prediction**, an alternative
  to our `motionprediction` + manual history iteration.
- Cleanest as a **parallel authoring path** behind a flag: ink owns the live
  layer, and on `InProgressStrokesFinishedListener` we convert the finished
  `Stroke` back to `StrokeCodec` bytes via `InkInterop` and commit through the
  existing pipeline. Renderer/storage/undo stay untouched.
- ink's smoothing also feeds the **live beautify** flow (idea #1) for free.

### D. Standard stroke serialization  (`ink-strokes` / `ink-storage`)
- `StrokeInputBatch` is a compact, portable, **cross-platform** stroke format.
  Use it as an **interchange/export** format (AI round-trips, share, future
  web/desktop), *not* as on-disk truth — `StrokeCodec` stays canonical.
- Bonus: a stable interchange format makes it easier to hand stroke geometry to
  the model and get edits back as strokes rather than only edit-ops.

---

# New ink-enabled AI features

These are the four new directions selected for fleshing out. Each names the ink
modules it leans on and how it threads through the existing AI pipeline.

### N1. AI brush designer (text → brush)
- **What:** "Make me a dry-gouache brush" / "an inky brush pen with taper" →
  the model emits brush parameters; we build a `Brush`/`BrushFamily` and save it
  as a new user-scope `BrushPreset`. Output is a **reusable, editable brush**,
  not a one-off render.
- **Ink:** `ink-brush` 1.1 programmatic `Brush.Builder` / `BrushFamily`,
  `BrushPaint.TextureLayer`, randomized behaviors; `BrushFamily.decode()` for
  shareable brush files.
- **Pipeline:** new `NoteAiService` mode (`DESIGN_BRUSH`) returning a small
  brush-spec JSON (validated like `edit-ops`); adapter maps spec → ink brush →
  `BrushPreset`. No canvas mutation, so it's low-risk to ship.
- **Why now:** the public brush API (1.1) is what makes text→brush tractable
  without reverse-engineering a proto.

### N2. Magic-wand "select similar" + constraint/snap engine
- **What:** (a) tap a stroke → find geometrically/stylistically similar strokes
  for batch `recolor` / `restyle` / `delete`; (b) AI proposes
  alignment / symmetry / even-spacing and the engine **enforces** it (idea #8).
- **Ink:** `ink-geometry` `PartitionedMesh` intersection/coverage for "similar
  shape / overlapping / inside region"; lasso→mesh for the selection itself.
- **Pipeline:** geometry runs locally (fast, offline); AI optionally ranks
  "which of these belong together" and emits `group` / `transform` / `recolor`
  edit-ops the user accepts. Snapping surfaces as accept/decline chips, same UX
  as existing AI edit suggestions.

### N3. Live beautify via ink smoothing  (upgrade of idea #1)
- **What:** on pen-lift, offer a one-tap clean snap. ink's input smoothing +
  our `InkBeautifier` (RDP + Chaikin) + `ShapeRecognizer` combine for a
  noticeably cleaner result than today.
- **Ink:** `ink-authoring` input smoothing on the live batch; optionally render
  the candidate via `CanvasStrokeRenderer` for a crisp preview.
- **Pipeline:** purely local for the geometric clean; AI only consulted for the
  ambiguous "did you mean this shape?" cases (reuses `AUTO_SHAPE` /
  `replace_with_shape`). Ghost-preview the beautified stroke; tap to accept.

### N4. Stroke replay / "draw with me"  (supports ideas #7)
- **What:** replay drawing order as an animation — timelapse export, and a
  **tutor** mode that ghosts construction strokes for the user to trace.
- **Ink:** `StrokeInputBatch` **timestamps** drive ordered playback; ink
  rendering animates partial strokes; ties into our v2 codec timestamps already
  synced to audio.
- **Pipeline:** AI (GENERATE) produces the construction strokes on a dedicated
  guide layer; replay plays them back at teaching pace. Export reuses
  `NoteRasterizer` frames → video/GIF.

---

# Proposed phasing

| Phase | Scope | Ink modules | Risk |
|---|---|---|---|
| **I0 — `InkInterop` seam** | Bidirectional `StrokeCodec ↔ StrokeInputBatch/Stroke`; `BrushPreset → Brush` adapter; JVM round-trip tests | strokes, brush, geometry | Low — no UI, no migration |
| **I1 — Geometry adoption** | Back `HitTest`/`LassoController` with `PartitionedMesh` behind a flag; A/B accuracy & perf | geometry | Low/Med |
| **I2 — Brush richness + N1** | `BrushPreset → BrushFamily` rendering path; AI brush designer (`DESIGN_BRUSH`) | brush, rendering | Med |
| **I3 — Live beautify (N3)** | ink smoothing into the pen-lift beautify flow | authoring, rendering | Med |
| **I4 — Authoring path** | Optional `InProgressStrokesView` live layer behind a flag; finish→convert→commit | authoring | Med/High |
| **I5 — Select-similar + snapping (N2, idea #8)** | mesh-based similarity + constraint engine + AI ranking | geometry | Med |
| **I6 — Replay / draw-with-me (N4, idea #7)** | timestamp-driven replay, tutor guide layer, timelapse export | strokes, rendering | Med |

Sequencing logic: **I0 is a hard prerequisite** for everything else. I1/I2 are
the lowest-risk, highest-confidence wins (no live-input or migration changes).
I4 (authoring swap) is deliberately late — it touches the most sensitive,
well-tuned path (`DrawingSurface`) and should only land once the seam and
rendering parity are proven.

# Risks & open questions

- **APK size / native libs.** ink bundles a native core (`ink-nativeloader`);
  measure the size delta and per-ABI impact before committing.
- **Rendering parity.** ink's mesh rendering must visually match
  `StrokeRenderer`'s variable-width Bézier output (taper/jitter/texture) before
  it can become default — keep both paths until parity is demonstrated.
- **Orientation lane.** ink captures stylus *orientation*; our codec doesn't.
  Decide whether N1/brush behaviors need it (would imply a future v3 lane —
  out of scope under the "codec stays canonical" decision).
- **API 29 / 34 gating.** Front-buffered low-latency (29) and richer rendering
  (34) are optional; the authoring path must degrade gracefully on 26–28.
- **Two geometry engines.** Until I1 fully replaces `HitTest`, we maintain both;
  guard against behavioral drift with shared test fixtures.
- **Alpha vs stable.** Programmable brushes (N1) rely on **1.1.0-alpha**; pin a
  version and isolate behind the brush flag until 1.1 stabilizes.
