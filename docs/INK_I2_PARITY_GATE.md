# Ink I2 — Rendering + Behaviour Parity Gate

Status: **in progress — headless slice landed; device-only items open. Ink stays
default-OFF.** This is the default-on gate the migration plan
([`ANDROIDX_INK_MIGRATION_PLAN.md`](ANDROIDX_INK_MIGRATION_PLAN.md), phase **I2**)
requires before the "Ink engine (experimental)" switch
(`ToolPalettePrefsStore.inkAuthoring`) can flip from opt-in fallback to the
default live-drawing engine.

> **Bottom line:** every part of the gate that can be proven on the headless
> cloud container **is** proven — eraser parity across all `NoteItem` kinds,
> commit-pipeline + audio-timestamp correctness, and the per-tool footprint
> geometry — and these now ride as permanent JVM regression tests. The same
> harness also surfaced a real **rendering gap**: ink's stable stock
> `pressurePen` does *not* reproduce `StrokeRenderer`'s pressure taper (it holds
> a constant `size`-width tube), so taper joins jitter/texture as **I4
> brush-richness** work. The remaining items genuinely need the **S25 Ultra**
> (on-device latency feel, front-buffer compositing, the live
> colour/opacity/texture/AA pixel diff, and overlay touch pass-through). Until
> the device harness passes **and** I4 closes the taper/jitter/texture gap,
> **ink is not default-on** and the switch stays off — exactly as Adoption
> principle 1 requires.

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
| 6 | **Rendering taper parity** — ink local width follows the `ToolDynamics` pressure curve | ❌ **gap found → I4** | `InkRenderParityTest.pressureTaperIsCurrentEngineOnlyUntilI4` measures it: the current engine tapers ~2.5× over a pressure ramp; ink's *stable stock* `pressurePen` holds a constant `size`-width tube. Pressure taper needs a custom `BrushTip`/`BrushBehavior` (I4). The gap is pinned by a permanent test so it can't be mistaken for parity. |
| 7 | **Footprint parity** per tool (pen/pencil/highlighter/marker) | ✅ headless (geometry) | `InkRenderParityTest.footprintVerdictsHoldAsPermanentGate` (promotes the I0.7 coverage spike to a permanent assertion) |
| 8 | **Brush colour / opacity mapping** matches the preset semantics | ✅ headless (analytic) | `InkInteropTest.toBrushFoldsOpacityIntoAlpha`, `applyOpacityToArgbClampsAndPreservesRgb` |
| 9 | **Undo/redo + layer commit** go through the *same* listener pipeline as a hand-drawn stroke | ◑ headless reasoning, device feel open | ink commits via `buildStrokeItem` → `strokeListener` (shared with `commitLiveStroke`); the committed item is a normal `STROKE_KIND` `NoteItem` (#3/#5). Behavioural undo/redo/layer feel needs the device. |
| 10 | **Shape recognition** (hold-to-recognize) preserved on the ink path | ◑ headless reasoning, device feel open | `finishInkStroke` carries the hold decision into `onInkStrokesFinished`, which fires `strokeHoldRecognizeListener` exactly like `commitLiveStroke`. The gesture itself needs the device. |
| 11 | **Colour / opacity / texture / anti-aliasing pixel diff** on the target panel | ⬜ device-only | `CanvasStrokeRenderer` on the S25 Ultra — see "On-device harness". Brush-identity colour/alpha is #8; *blending/texture/AA* are not headless-reproducible. |
| 12 | **On-device latency feel + front-buffer compositing** | ⬜ device-only | High-refresh LTPO panel; not reproducible headless. |
| 13 | **Overlay touch pass-through** (the `InProgressStrokesView` sibling doesn't steal/duplicate input) | ⬜ device-only | Needs real `MotionEvent` dispatch through the `FrameLayout` stack. |
| 14 | **Fallback recovers without data loss** (toggle off mid-stroke; ink call failure drops one stroke) | ◑ logic in place, device feel open | `detachInkAuthoring`/`cancelInkStroke` guard every ink call; the in-flight stroke is abandoned cleanly. Verified by reading the lifecycle; the *felt* recovery needs the device. |
| 15 | **Jitter + procedural texture parity** (pen grain, marker/watercolor tiles, highlighter chisel) | ⬜ deferred to **I4** | `InkInterop` maps only the stable brush identity (family + colour + size); taper/jitter/texture/pressure-curve are an explicit I4 item. Ink **cannot** match these today. |

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
3. Diff per-pixel (ΔE on colour, alpha delta on opacity). **Expect divergence on
   highlighter width** (~0.71× per I0.7) and on **textured tools** until I4 lands
   — record the magnitude; it gates the I4 brush calibration, not just I2.

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
JVM tests. The default-on flip is blocked on:

- **Device-only items 11–13** (pixel diff, latency/front-buffer, touch
  pass-through), which this environment cannot execute, and
- **I4 brush richness (items 6 + 15)** — the **pressure taper** gap this gate
  measured (ink's stock pen renders constant width), plus jitter, procedural
  texture, and the highlighter-width / pencil-tilt calibration I0.7 flagged —
  without which rendering parity is incomplete.

Per Adoption principle 1 ("ink is the intended primary, the flag exists to fall
*back*, not to opt *in* — but it still needs a checklist before default-on"), the
switch is **not** flipped until the on-device harness above passes and I4 closes
the texture/jitter gap. This document is the live record of what remains.
