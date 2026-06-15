# Ink I2 — Rendering + Behaviour Parity Gate

Status: **in progress — headless slice landed; I4 brush gaps now closed;
device-only items open. Ink stays default-OFF.** This is the default-on gate the
migration plan
([`ANDROIDX_INK_MIGRATION_PLAN.md`](ANDROIDX_INK_MIGRATION_PLAN.md), phase **I2**)
requires before the "Ink engine (experimental)" switch
(`ToolPalettePrefsStore.inkAuthoring`) can flip from opt-in fallback to the
default live-drawing engine.

> **Bottom line:** every part of the gate that can be proven on the headless
> cloud container **is** proven — eraser parity across all `NoteItem` kinds,
> commit-pipeline + audio-timestamp correctness, and the per-tool footprint
> geometry — and these now ride as permanent JVM regression tests. The
> **rendering gap this gate originally surfaced** (ink's stable stock
> `pressurePen` rendered a constant-width tube, no pressure taper) has now been
> **closed by phase I4**: a stable custom `BrushTip`/`BrushBehavior` adapter
> ([`InkBrushFamilies`](../app/src/main/java/com/aichat/sandbox/data/ink/InkBrushFamilies.kt))
> makes ink track `StrokeRenderer`'s pressure taper (pen), tilt-width (pencil),
> and highlighter width — all measured at parity headless (correlation > 0.999;
> highlighter areaRatio 0.71×→~1.0×; every tool now **GO**). What remains for the
> default-on flip genuinely needs the **S25 Ultra** (on-device latency feel,
> front-buffer compositing, the live colour/opacity/**texture**/AA pixel diff,
> and overlay touch pass-through) plus the two brush items that are *not*
> headless-expressible — **procedural texture** (a `BrushPaint.TextureLayer`
> device-pixel item) and **jitter** (no stable randomized-source exists; it lives
> in the isolated 1.1-alpha path). Until the device harness passes, **ink is not
> default-on** and the switch stays off — exactly as Adoption principle 1
> requires.

This repo's build/test environment is a **headless container with no Android
device or emulator** (per `CLAUDE.md`). So this gate is split deliberately into
two columns: what we *verified headless* (with the test that proves it), and what
remains a *documented, reproducible on-device checklist* that a maintainer runs
on the target hardware before flipping the default. Nothing below claims a
device-only item passed.

---

## How to run the headless gate

```bash
# The full I2 headless parity suite (eraser + commit + render parity):
./gradlew :app:testDebugUnitTest --console=plain \
  --tests "com.aichat.sandbox.data.ink.parity.*"

# Plus the I0 seam + I0.7 coverage spike it builds on:
./gradlew :app:testDebugUnitTest --console=plain \
  --tests "com.aichat.sandbox.data.ink.*"
```

Reports written by the run:
- `app/build/reports/ink-i2/parity-coverage.txt` — per-tool footprint verdicts
  (the I0.7 corpus, re-asserted as a permanent gate).

---

## Checklist — verified headless vs. device-only

| # | Gate item | Status | Evidence / how |
|---|---|---|---|
| 1 | **Eraser — stroke kind** still hit-tests via `HitTest` | ✅ headless | `EraserHitTestParityTest.strokeKindHitsAndMisses` |
| 2 | **Eraser — no regression for non-stroke kinds** (shapes, stickies, connectors, paths) | ✅ headless | `EraserHitTestParityTest` per-kind tests + `nonStrokeKindsDoNotDependOnStrokeGeometry` |
| 3 | **Eraser — ink-authored stroke erases identically** to a hand-drawn one | ✅ headless | `EraserHitTestParityTest.inkAuthoredStrokeErasesIdenticallyToHandDrawn` |
| 4 | **Audio timestamp sync** (two-clock reconciliation survives the authoring round-trip) | ✅ headless | `InkCommitParityTest.audioTimestampSyncSurvivesAuthoringRoundTrip` |
| 5 | **`StrokeCodec` canonical / AI pipeline never sees ink** at the commit seam | ✅ headless | `InkCommitParityTest.committedPayloadIsCanonicalForAiPipeline`, `noRecordingCommitsV1` |
| 6 | **Rendering taper parity** — ink local width follows the `ToolDynamics` pressure curve | ✅ **closed by I4** | `InkRenderParityTest.pressureTaperMatchesCurrentEngineAfterI4` (was `…IsCurrentEngineOnlyUntilI4`): the I4 custom pen family drives `SIZE_MULTIPLIER` from `NORMALIZED_PRESSURE` (`0.35×–1.15×`, `pressure^0.7`), so ink now tapers ~2.4× over the ramp vs the engine's ~2.5×, **correlation 0.9997**. Pencil tilt-width is closed the same way (`pencilTiltWidthMatchesCurrentEngineAfterI4`, corr 0.99997). |
| 7 | **Footprint parity** per tool (pen/pencil/highlighter/marker) | ✅ headless (geometry) | `InkRenderParityTest.footprintVerdictsHoldAsPermanentGate`. After I4 **all four tools are GO** (pen cov 0.81→0.92, pencil 0.84→0.94, highlighter 0.69→0.97, marker 0.89); highlighter areaRatio 0.71×→1.01× via `highlighterFootprintMatchesAfterI4`. |
| 8 | **Brush colour / opacity mapping** matches the preset semantics | ✅ headless (analytic) | `InkInteropTest.toBrushFoldsOpacityIntoAlpha`, `applyOpacityToArgbClampsAndPreservesRgb` |
| 9 | **Undo/redo + layer commit** go through the *same* listener pipeline as a hand-drawn stroke | ◑ headless reasoning, device feel open | ink commits via `buildStrokeItem` → `strokeListener` (shared with `commitLiveStroke`); the committed item is a normal `STROKE_KIND` `NoteItem` (#3/#5). Behavioural undo/redo/layer feel needs the device. |
| 10 | **Shape recognition** (hold-to-recognize) preserved on the ink path | ◑ headless reasoning, device feel open | `finishInkStroke` carries the hold decision into `onInkStrokesFinished`, which fires `strokeHoldRecognizeListener` exactly like `commitLiveStroke`. The gesture itself needs the device. |
| 11 | **Colour / opacity / texture / anti-aliasing pixel diff** on the target panel | ⬜ device-only | `CanvasStrokeRenderer` on the S25 Ultra — see "On-device harness". Brush-identity colour/alpha is #8; *blending/texture/AA* are not headless-reproducible. |
| 12 | **On-device latency feel + front-buffer compositing** | ⬜ device-only | High-refresh LTPO panel; not reproducible headless. |
| 13 | **Overlay touch pass-through** (the `InProgressStrokesView` sibling doesn't steal/duplicate input) | ⬜ device-only | Needs real `MotionEvent` dispatch through the `FrameLayout` stack. |
| 14 | **Fallback recovers without data loss** (toggle off mid-stroke; ink call failure drops one stroke) | ◑ logic in place, device feel open | `detachInkAuthoring`/`cancelInkStroke` guard every ink call; the in-flight stroke is abandoned cleanly. Verified by reading the lifecycle; the *felt* recovery needs the device. |
| 15 | **Jitter + procedural texture parity** (pen grain, marker/watercolor tiles) | ◑ **partially closed by I4** | I4 closed the *geometry* brush gaps (taper, tilt-width, highlighter width — items 6/7). **Procedural texture** stays deferred: it's a `BrushPaint.TextureLayer` appearance item that needs the on-device `CanvasStrokeRenderer` pixel pass (item 11). **Jitter** stays deferred: stable 1.0.0 exposes no randomized/noise `BrushBehavior.Source`, so a faithful jitter brush is only expressible on the isolated 1.1-alpha path (`data.ink.experimental.InkProgrammableBrush`) — never on the stable seam. |

Legend: ✅ verified headless · ◑ logic verified headless, on-device confirmation still owed · ⬜ device-only / deferred.

---

## On-device harness (reproducible, run on the S25 Ultra)

These steps are the device column of the gate. Run them on the target hardware
with the ink switch **temporarily** forced on (overflow menu → "Ink engine
(experimental)"). Record pass/fail per row before considering the default flip.

### A. Colour / opacity / texture / AA pixel diff (item 11)
The I0.7 spike compared *coverage geometry* headless and explicitly deferred the
pixel-level appearance. To close it:
1. Draw the I0.7 corpus (16 shapes × pen/pencil/highlighter/marker) once with the
   switch **off** (current `StrokeRenderer`) and once **on** (ink +
   `CanvasStrokeRenderer`), at the same colour/opacity/texture per tool.
2. Capture each layer to a bitmap (`NoteRasterizer` already renders committed
   strokes; add an equivalent capture of the ink wet layer, or screenshot).
3. Diff per-pixel (ΔE on colour, alpha delta on opacity). I4 calibrated the
   highlighter width (the headless footprint is now ~1.0× — no longer the ~0.71×
   I0.7 saw), so expect width parity there; **expect remaining divergence on
   textured tools** (pencil grain, marker/watercolor tiles) — that procedural
   texture is the `BrushPaint.TextureLayer` work still deferred past I4. Record
   the magnitude.

### B. Latency + front-buffer compositing (item 12)
4. With the switch on, draw fast loops/zigzags; confirm the wet line tracks the
   S-Pen with the front-buffered low-latency feel and no visible lag step at
   pen-down. Compare side-by-side against the switch-off path.

### C. Overlay touch pass-through (item 13)
5. Confirm a single stylus stroke produces exactly one committed `NoteItem` (no
   doubling between `DrawingSurface` and the overlay), that eraser/lasso/shape/
   text/connector tools still reach `DrawingSurface` with the overlay attached,
   and that finger pan/zoom is unaffected.

### D. Behavioural sweeps (items 9, 10, 14)
6. Undo/redo a mix of ink-authored and hand-drawn strokes; confirm one undo
   removes one stroke and order is preserved.
7. Commit ink strokes onto named/locked/hidden layers; confirm layer assignment,
   z-order, and that locked/hidden layers behave as before.
8. Hold-to-recognize: draw a rough shape on the ink path and hold before lifting;
   confirm the shape replaces the stroke as one undoable edit.
9. Toggle the switch off mid-stroke and force an ink failure (e.g. detach); confirm
   no half-drawn or lost stroke and a clean fall back to the quad-Bézier path.

---

## Decision

**Ink remains default-OFF.** The headless half of the gate (items 1–8, plus the
shared-pipeline reasoning for 9–10 and 14) is complete and locked in by permanent
JVM tests, and **phase I4 has now closed the brush-geometry gaps** (items 6 + 7,
and the geometry half of 15): pressure taper, pencil tilt-width, and highlighter
width are all measured at parity headless. The default-on flip is now blocked
only on:

- **Device-only items 11–13** (the colour/opacity/**texture**/AA pixel diff,
  latency/front-buffer feel, and overlay touch pass-through), which this
  environment cannot execute, and
- **The two non-geometry brush items** that I4 deliberately left deferred:
  **procedural texture** (a `BrushPaint.TextureLayer` appearance item folded into
  the device pixel diff, item 11) and **jitter** (no stable randomized source;
  isolated to the 1.1-alpha path). Neither is a headless-expressible regression.

Per Adoption principle 1 ("ink is the intended primary, the flag exists to fall
*back*, not to opt *in* — but it still needs a checklist before default-on"), the
switch is **not** flipped until the on-device harness above passes. This document
is the live record of what remains.
