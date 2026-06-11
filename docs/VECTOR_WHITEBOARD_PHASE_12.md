# Phase 12 — Vector pen & node editing

> A first-class bezier **path** item on the existing notes canvas: a `"path"`
> `NoteItem.kind` + binary codec, a pen tool that places anchors and pulls
> handles, a node-edit overlay for a selected path, shape→path and
> stroke→path conversion, and stroke-styling/export parity. Everything
> follows the master plan's cross-cutting decisions: new kind + codec (no
> new tables), all mutations as `EditorAction`s, trailing optional payload
> fields decoded via `buf.hasRemaining()`.

## Sub-phase 12.1 — `"path"` item kind + `PathCodec` + `PathRenderer`

### Schema

New item kind `"path"`, codec `PathCodec` (little-endian):

```
[version:u8=1]
[flags:u8]                 bit0 = closed
[count:u16]                anchor count
per anchor (25 bytes):
  [x:f] [y:f]              anchor point (world)
  [inDx:f] [inDy:f]        incoming handle, relative to the anchor
  [outDx:f] [outDy:f]      outgoing handle, relative to the anchor
  [type:u8]                0 = corner, 1 = smooth, 2 = symmetric
[fillArgb:i32]?            trailing optional; 0 = no fill
[strokeStyle:u8]?          ShapeCodec STROKE_STYLE_* value
[capJoin:u8]?              12.5: low nibble cap (0 butt / 1 round / 2 square),
                           high nibble join (0 miter / 1 round / 2 bevel)
```

Segment *i* runs anchor *i* → anchor *i+1* as a cubic with
`c1 = a_i + out_i`, `c2 = a_{i+1} + in_{i+1}`; a closed path adds the
wrap-around segment. Zero handles degrade the cubic to a straight line, so
polygon-like paths cost nothing extra. Trailing fields follow the
ShapeCodec convention — every shorter payload decodes with defaults
(no fill, solid, round/round).

### Semantics

- Handles are **relative deltas**: `transform` maps anchors through the full
  affine and handles through the linear part only, so translation never
  distorts curvature and rotation/scale behave exactly.
- `boundsOf` solves the cubic-extrema quadratic per segment per axis —
  exact bounds, not the control-point envelope, so selection rectangles hug
  the curve.
- `flatten` (uniform-t, 16 steps/segment) feeds hit-testing:
  `HitTest.pathContainsPoint` (closed+filled = interior + edge proximity,
  open = segment distance) and `HitTest.pathIntersectsPolygon` (lasso).
- Stroke colour / width travel on the `NoteItem`, mirroring shapes.

### Files

- `ui/components/notes/PathCodec.kt` — **new**, pure (JVM-testable).
- `ui/components/notes/PathRenderer.kt` — **new**, Android (`Path.cubicTo`),
  fill pass mirrors `ShapeRenderer` (pathEffect nulled during fill).
- `HitTest.kt` — `pathContainsPoint` / `pathIntersectsPolygon`.
- Seventh branch in `ItemTransformer`; `EditPreviewController.transformItem`
  routes the kind through it (AI transform/recolor/delete work by id).
- `DrawingSurface` — render + eraser branches; `NoteEditorViewModel` —
  `itemBounds`, lasso, duplicate, stamp-rebuild, insert-anchor branches;
  `NoteRasterizer` — bounds + draw branches (thumbnails, PNG/PDF export).

### Definition of done

- A path item round-trips save/reload, transforms, duplicates, erases,
  lassos, and exports to PNG. `PathCodecTest` pins round-trip, trailing-field
  defaults, transform (translate/scale/rotate, handle-vs-anchor), exact
  bounds, flatten, hit-tests.

## Sub-phase 12.2 — Pen tool

- `Tool.PATH_PEN` joins the palette's shapes group. Config rows reuse the
  ink colour/width row plus the Phase-10 fill/line-style row (paths encode
  the palette fill + stroke style, gated on the pen tool exactly like
  shapes).
- `DrawingSurface.handlePathPenEvent`: tap = corner anchor; press-drag past
  the touch slop pulls **symmetric** handles out of the new anchor; tap
  within the grab radius of the first anchor (≥ 3 anchors) closes the path
  and commits; a second finger pinch-zooms (the in-progress path survives —
  multi-tap tools need viewport moves between anchors).
- Commit paths: closing tap; switching away from the pen tool
  (`setToolConfig` detects the change); `ACTION_CANCEL` keeps the
  in-progress anchors (a stray palm shouldn't eat the path). ≥ 2 anchors
  commit; fewer are dropped.
- Front-buffer preview: committed segments + rubber segment to the cursor,
  anchor dots, a highlight ring on the first anchor when closing is armed,
  live handle lines during a pull.

### Definition of done

- Tap-tap-tap → open polyline path; tap-drag anchors → curved path;
  tap first anchor → closed path; tool switch commits; undo removes the
  whole path (one `AddItems`).

## Sub-phase 12.3 — Node editor

- Selecting exactly one path surfaces **Edit nodes** in the selection menu →
  `NoteEditorViewModel.nodeEditTarget` (item id). The editor screen swaps
  `SelectionOverlay` for a `PathNodeEditor` overlay; `clearSelection()`
  exits node-edit mode (so a stray canvas stroke backs out cleanly).
- `PathNodeEditor` renders anchors (squares = corner, circles =
  smooth/symmetric) + handle dots/lines in screen space and supports:
  - drag anchor → moves the anchor (handles ride along, they're relative);
  - drag handle dot → retargets `out`/`in`; smooth mirrors direction
    (lengths independent), symmetric mirrors direction + length, corner
    moves one side only;
  - double-tap anchor → corner ⇄ smooth toggle;
  - tap on a segment → insert an anchor at the nearest curve point
    (de Casteljau split — geometry is preserved exactly);
  - long-press anchor → delete (floor of 2 anchors).
- Gesture lifecycle: drags live-mutate the item payload (no undo entries);
  on release one `CompositeEdit("Move anchor"/…)` lands with the
  gesture-start payload as `before` — exactly one undo entry per gesture.
  Tap-like edits (toggle/insert/delete) commit immediately.
- Pure math (`PathNodeMath`): nearest-point-on-path, cubic split,
  insert/delete/toggle/move — all JVM-tested.

### Definition of done

- Each gesture is one undo entry; undo restores the payload byte-identical;
  inserting an anchor doesn't visibly change the curve.

## Sub-phase 12.4 — Convert to path

- `PathConversions` (**new**, pure):
  - `fromShape`: rect → 4 corner anchors (rounded rect → 8 anchors with
    circular-arc cubics, kappa = 0.5523); ellipse → 4 smooth anchors with
    kappa handles (rotation baked through the anchor frame); polygon →
    corner anchors; line → 2 corner anchors; arrow → line path **plus** a
    closed filled head path (two items — the codec is single-subpath).
  - `fromStroke`: RDP (`PolylineSimplify`, shared Phase-4 core) →
    least-squares cubic fit (`CurveFitter`, the Schneider fit from the
    vector lane) → anchors at the cubic joins, smooth where adjacent
    tangents agree (≤ ~15° kink), corner otherwise; near-closed strokes
    (gap < 10 % of the bbox diagonal) close the path.
- `NoteEditorViewModel.convertSelectionToPaths()` — one
  `CompositeEdit("Convert to path")` removing the originals and adding the
  paths (colour / width / layer / z / group preserved; shape fill +
  strokeStyle carried into the payload). Selection moves to the new items.
- Selection menu gains **To path** when the selection contains shapes or
  strokes.

### Definition of done

- Rect/ellipse/line/polygon convert with exact geometry (ellipse within
  kappa tolerance); a smooth pen stroke converts to ≤ a tenth of its sample
  count in anchors with max deviation under the fit tolerance;
  `PathConversionsTest` pins all of it.

## Sub-phase 12.5 — Stroke styling completeness + export parity

- `PathCodec` appends the optional `capJoin:u8` (defaults round/round);
  `PathRenderer` applies cap/join and the Phase-10 dash patterns; the pen
  tool's palette row gains cap (Round/Square/Flat) and join (Round/Miter/
  Bevel) chips.
- Selection **Style** popover now restyles paths too (fill + line style
  share the shapes path; `selectionHasShapes` counts paths).
- Export parity:
  - `NoteSvgExporter` — `<path d="M … C … (Z)">` with fill, dash,
    `stroke-linecap` / `stroke-linejoin` (pure-JVM int math, no
    `android.graphics.Color`, so the wire format is pinnable in tests).
  - `NoteVectorDrawableExporter` — paths emit `android:pathData` cubics
    with fill/stroke/cap/join (no longer counted as skipped).
  - `VectorCanvasJson` — `kind:"path"`, short ids `p_001`, anchors +
    `closed` + fill, so the AI EDIT pipeline can address paths
    (transform / recolor / restyle / delete all work by id —
    `EditOpsParser` is id-agnostic and `EditPreviewController` routes the
    kind through `ItemTransformer`).

### Definition of done

- A dashed, filled, closed path survives SVG → external viewer and
  VectorDrawable import; canvas JSON lists paths; AI recolor on a path
  works. Export tests pin the `d=` / `pathData` output.

## JVM test roster

| Test | Pins |
| --- | --- |
| `PathCodecTest` | round-trip (open/closed/types/fill/style/capJoin), short-payload defaults, transform anchor-vs-handle, exact bounds, flatten, hit-tests |
| `PathNodeMathTest` | nearest-on-path, split preserves geometry, insert/delete/toggle/move handle mirroring |
| `PathConversionsTest` | every shape kind incl. rounded rect + rotated ellipse + arrow two-item output; stroke fit error, anchor reduction, closed-loop detection |
| `NoteSvgExporterPathTest` | `d=` output, dash array, cap/join, fill on/off |
| `NoteVectorDrawableExporterPathTest` | `pathData` output, fill/stroke attrs, paths not counted skipped |
| `VectorCanvasJsonPathTest` | `p_` short ids, anchors + closed + fill in JSON, idMap round-trip |
