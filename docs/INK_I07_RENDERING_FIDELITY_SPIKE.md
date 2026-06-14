# Ink I0.7 — Rendering Fidelity Spike (results)

Status: **done — throwaway spike, results recorded here.** This is the cheap
go/no-go gate the migration plan ([`ANDROIDX_INK_MIGRATION_PLAN.md`](ANDROIDX_INK_MIGRATION_PLAN.md),
phase I0.7) requires **before** any `InProgressStrokesView` authoring work (I1).

> **Bottom line:** **pen, pencil, and marker are GO** — ink's stock-brush mesh
> already lands on essentially the same footprint, at the same width, as the
> engine we ship today. **Highlighter is GO-with-brush-work**: ink's stock
> highlighter covers a consistently *narrower* region (~0.71× our area), so it
> needs the I4 brush-width/shape mapping pass before it matches. No tool is a
> NO-GO. Nothing here blocks building the I1 authoring path.

---

## What was measured (and why it's a coverage diff, not a screen-pixel diff)

The plan asks for "50–100 representative strokes rendered through both
`StrokeRenderer` and `CanvasStrokeRenderer`, pixel/visual diff, per-tool
go/no-go." A literal *screen-pixel* diff needs both `CanvasStrokeRenderer`
(`ink-rendering`) and our `StrokeRenderer` to draw onto an
`android.graphics.Canvas` — Android-only classes that don't exist in the
headless JVM unit-test host this repo's CI runs on (and `ink-rendering` isn't
even on the classpath — only `ink-strokes` / `ink-brush` / `ink-geometry` are,
as `compileOnly` + `testImplementation`; see `app/build.gradle.kts`).

So the spike compares the two engines at the layer that **is** reproducible
headless and that actually gates the decision — the **filled region each engine
covers**:

- **Engine A (current):** `StrokeOutliner.outline(samples, tool, baseWidth)`
  builds the exact pressure/tilt-following coverage polygon the live renderer
  fills. It uses the *same* `ToolDynamics` width curve `StrokeRenderer` paints,
  so it is a faithful stand-in for "what the current engine puts on screen."
- **Engine B (ink):** `InkInterop.toStroke(payload, brush)` → `Stroke.shape`
  (a native `PartitionedMesh`); we read its outline loops back via
  `getOutlineCount` / `getOutlineVertexCount` / `populateOutlinePosition`.

Both polygons are rasterized onto **one shared grid** (96 cells on the long
edge — these cells are the "pixels") with the same even-odd point-in-polygon
test, and we report, per stroke:

| Metric | Meaning |
|---|---|
| **coverageIoU** | `|A∩B| / |A∪B|` over the grid — the headline "do they fill the same pixels" number |
| **bboxIoU** | IoU of the two analytic bounding boxes — does the stroke land in the same place/extent |
| **areaRatio** | ink covered cells ÷ current covered cells (1.0 = identical footprint; <1 ink thinner, >1 ink fatter) |
| **centroidDrift** | centroid offset as a fraction of the union-bbox diagonal — positional bias |

The harness and corpus live in
`app/src/test/java/com/aichat/sandbox/data/ink/spike/` (`RenderingFidelitySpike`
+ `RenderingFidelitySpikeTest`) — under `src/test` on purpose, since the spike
is throwaway and never ships in the APK. Re-run it with:

```bash
./gradlew :app:testDebugUnitTest --tests "com.aichat.sandbox.data.ink.spike.*"
# full table written to app/build/reports/ink-i07/fidelity-report.txt
```

### Explicitly out of scope (needs the on-device pixel diff)

Colour, opacity blending, procedural **texture** (pencil grain, marker/
watercolor tiles via `TextureRegistry`), and anti-aliased edge quality are *not*
measured here — those are exactly the parts that need a real
`CanvasStrokeRenderer` pass on the **target S25 Ultra panel**. This spike
answers the *geometry/coverage* question only; the colour/texture/AA pixel diff
remains a complementary on-device check, folded into the I2 parity gate.

---

## Corpus

64 strokes = **16 representative shapes × 4 tools** (pen, pencil, highlighter,
marker), within the plan's 50–100 band. The shapes stress the cases that
actually differ between engines: straight legs, gentle and tight curves, full
loops, a sharp right-angle corner, a spiral, a long sine wave, plus
pressure-driven (`taper-ramp`, `pressure-dip`, `heavy-line`) and tilt-driven
(`tilt-sweep`, `tilt-curve`) strokes — and the degenerate `dot-1pt` /
`stub-2pt` edge cases.

---

## Per-tool verdict (measured)

| Tool | Strokes | mean coverageIoU (min) | mean bboxIoU | mean areaRatio | mean drift | **Decision** |
|---|---|---|---|---|---|---|
| **pen** | 16 | **0.811** (0.505) | 0.925 | 1.15 | 0.014 | **GO** |
| **pencil** | 16 | **0.836** (0.422) | 0.892 | 1.06 | 0.011 | **GO** |
| **highlighter** | 16 | **0.694** (0.308) | 0.887 | 0.71 | 0.012 | **GO_WITH_BRUSH_WORK** |
| **marker** | 16 | **0.885** (0.599) | 0.939 | 1.12 | 0.006 | **GO** |

Thresholds (spike-grade judgment, documented in `RenderingFidelitySpike.verdict`):
**GO** when mean coverageIoU ≥ 0.72 **and** mean bboxIoU ≥ 0.88;
**GO_WITH_BRUSH_WORK** when mean coverageIoU ≥ 0.50 (footprint in the right
place, width/stock-brush shape needs the I4 mapping pass);
**NO_GO** otherwise. These bound the *geometry* decision only.

### Reading the results

- **pen — GO.** Stock `pressurePen` tracks our pen closely. Straight and
  taper strokes are ~1.0 coverage; curves sit at 0.81–0.88 (ink's mesh is
  marginally fatter on the outside of bends, areaRatio ~1.15–1.20). The one low
  point is `heavy-line` (cov 0.505, areaRatio ~1.98): at full pressure ink's
  pressurePen widens roughly 2× more than our `0.35×–1.15×` pen curve. That's a
  width-curve gap, not a shape gap — tunable when we map the pressure response.

- **pencil — GO.** Overall the best-aligned footprint (mean 0.836). **Caveat:**
  `InkInterop` currently maps `pencil` onto the round `StockBrushes.marker`
  family (the textured pencil is deferred to I4), so ink's pencil **ignores the
  tilt-broadening** our `ToolDynamics.pencil` applies. That shows up cleanly in
  `tilt-sweep` (cov 0.422, areaRatio 0.42 — ink stays thin while ours fans out
  with tilt). Acceptable for I1 (a uniform-width pencil is a fine starting
  authoring brush); the tilt-width + grain texture are an explicit I4 item.

- **marker — GO.** Best headline number (mean 0.885). `line-diagonal` is the
  outlier (cov 0.599, areaRatio 1.67) — ink's square-ish marker cap fattens a
  thin diagonal more than our round-cap dynamics; cosmetic, not gating.

- **highlighter — GO_WITH_BRUSH_WORK.** The clear signal of the spike: across
  almost every shape ink's stock highlighter covers a **narrower** region than
  ours (areaRatio clustered ~0.55–0.85, mean 0.71), and bboxIoU stays high
  (0.887) — i.e. it's in the *right place* but *too thin*. Our highlighter is a
  wide constant-width square-cap chisel; ink's `StockBrushes.highlighter`
  renders thinner at the same brush `size`. Before highlighter goes through the
  ink authoring path it needs a width/`size` calibration (and possibly a
  chisel-shaped brush tip) in the I4 brush-mapping work. Not a blocker for I1,
  which can lead with pen/marker.

---

## Decision

**Proceed to I1 (authoring prototype) leading with pen and marker**, which match
today's output most closely, with **pencil** close behind (uniform-width to
start). **Highlighter** rides the same authoring plumbing but its stock-brush
width needs the I4 calibration before it reaches visual parity. Carry two
concrete items into **I4 (brush richness)**:

1. **Highlighter `size`/tip calibration** — make ink's highlighter footprint
   match our wide chisel (areaRatio → ~1.0).
2. **Pencil tilt-width + grain** — restore tilt broadening and pencil texture
   (currently mapped to the plain round marker family).

And one item for the **I2 parity gate**: the on-device **colour / opacity /
texture / anti-aliasing** pixel diff that this headless geometry spike
deliberately does not cover.
