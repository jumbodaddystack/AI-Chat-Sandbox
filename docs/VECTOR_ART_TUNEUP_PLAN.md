# Vector Art Tune-Up Plan

## Purpose

Build a repeatable workflow for importing, diagnosing, optimizing, redrawing, previewing, iterating on, and exporting Android VectorDrawable artwork.

The immediate target is the workflow proven in the chat session: take a bloated or rough Android vector XML, produce a faithful optimized version, optionally produce a polished redraw, render previews, compare results, and let the user iterate. The implementation should reuse the existing notes/canvas infrastructure where it helps, but it should not cram arbitrary imported vector XML into the note editor too early.

This is a sibling pipeline to the existing Artist Canvas AI edit pipeline:

```text
Current Artist Canvas AI edit pipeline:
NoteItem list -> raster PNG + compact VectorCanvasJson -> edit-ops -> preview -> accept -> undo entry

New Vector Art Tune-Up pipeline:
VectorDrawable XML -> VectorDocument -> render + metrics -> deterministic optimize and/or AI plan -> candidate versions -> compare -> export
```

## Current repo context

Relevant existing pieces:

- `data/notes/VectorCanvasJson.kt` already serializes note items into compact model-addressable JSON with short IDs, point downsampling, locked-layer exclusion, and a JSON size cap.
- `data/notes/EditProtocol.kt` and `EditOpsParser.kt` already define and parse a conservative edit protocol for model output.
- `ui/screens/notes/EditPreviewController.kt` already applies deterministic stroke transforms, Chaikin smoothing, Ramer-Douglas-Peucker simplification, shape replacement, and preview simulation.
- `data/notes/NoteVectorDrawableExporter.kt` already exports internal note items as Android VectorDrawable XML.
- `data/notes/NoteSvgExporter.kt` already emits SVG for note items.
- `data/remote/OpenAiAdapter.kt` currently targets `chat/completions` and supports multimodal chat messages through text and image content parts.

Important gap:

The app has export and in-app note editing support, but it does not yet have a first-class import, parse, metric, optimize, version, and preview pipeline for arbitrary external Android VectorDrawable XML.

## Goals

1. Import external Android VectorDrawable XML.
2. Parse it into a source-of-truth `VectorDocument` model.
3. Preserve enough structure and attributes to round-trip safely.
4. Render or preview imported vectors in the app.
5. Compute useful diagnostics: file size, path count, command count, color usage, bounds, noisy path indicators, and unsupported feature warnings.
6. Deterministically produce a faithful optimized version using path simplification and float formatting.
7. Add model-guided tune-up using strict, machine-readable edit plans.
8. Add semantic redraw mode where the model can propose a clean scene representation and the app compiles it to VectorDrawable XML.
9. Support iterative versioning with preview, compare, accept, revise, and export.

## Non-goals for the first phase

- No AI calls.
- No semantic redraw.
- No full UI.
- No image import or raster-to-vector conversion.
- No handwritten note integration beyond reusing utilities where appropriate.
- No attempt to support every SVG feature.
- No editing of app `NoteItem`s from imported XML.

## Core design principles

### 1. Keep source of truth deterministic

The app should own the canonical vector representation. The model should not be the sole source of truth.

Bad pattern:

```text
User prompt -> model returns entire XML -> app trusts it
```

Preferred pattern:

```text
User prompt -> model returns typed edit plan -> app validates -> app applies deterministic transforms -> app renders -> user accepts
```

### 2. Maintain two representations

Use a full internal model for editing/export and a compact model summary for AI calls.

```text
VectorDocument
  Full source of truth, exact enough to round-trip XML.

VectorSummaryJson
  Compact, lossy, model-friendly description used for diagnosis and planning.
```

### 3. Separate faithful optimization from semantic redraw

Faithful optimization should preserve appearance and reduce path noise.

Semantic redraw may change geometry more aggressively to create clean icon-like output.

Users should choose the mode explicitly:

```text
Optimize: shrink and simplify without changing visual intent.
Tune up: improve readability while preserving original style.
Redraw: reconstruct as clean vector primitives.
```

### 4. Version everything

Every generated candidate should be a version with a parent, metrics, preview, instruction, and generated XML.

The user should be able to compare, branch, revise, and export without losing earlier results.

## Proposed package layout

```text
app/src/main/java/com/aichat/sandbox/data/vector/
  VectorDocument.kt
  VectorPath.kt
  VectorGroup.kt
  VectorStyle.kt
  VectorViewport.kt
  VectorWarning.kt
  AndroidVectorDrawableParser.kt
  AndroidVectorDrawableWriter.kt
  PathDataParser.kt
  PathDataFormatter.kt
  PathCommand.kt
  VectorMetrics.kt
  VectorMetricsAnalyzer.kt
  VectorDocumentValidator.kt
  VectorPathSimplifier.kt
  VectorDrawableOptimizer.kt
  VectorSummaryJson.kt
  VectorScene.kt
  VectorSceneCompiler.kt

app/src/test/java/com/aichat/sandbox/data/vector/
  AndroidVectorDrawableParserTest.kt
  AndroidVectorDrawableWriterTest.kt
  PathDataParserTest.kt
  VectorMetricsAnalyzerTest.kt
  VectorDrawableOptimizerTest.kt
```

UI and repository classes should come later after the core model is stable.

## Internal model

### VectorDocument

```kotlin
data class VectorDocument(
    val viewport: VectorViewport,
    val root: VectorGroup,
    val warnings: List<VectorWarning> = emptyList(),
    val originalXmlBytes: Int? = null,
)
```

### VectorViewport

```kotlin
data class VectorViewport(
    val widthDp: Float,
    val heightDp: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
)
```

### VectorGroup

```kotlin
data class VectorGroup(
    val id: String,
    val name: String? = null,
    val rotation: Float? = null,
    val pivotX: Float? = null,
    val pivotY: Float? = null,
    val scaleX: Float? = null,
    val scaleY: Float? = null,
    val translateX: Float? = null,
    val translateY: Float? = null,
    val children: List<VectorNode>,
)
```

### VectorNode

```kotlin
sealed interface VectorNode {
    val id: String

    data class GroupNode(val group: VectorGroup) : VectorNode {
        override val id: String get() = group.id
    }

    data class PathNode(val path: VectorPath) : VectorNode {
        override val id: String get() = path.id
    }
}
```

### VectorPath

```kotlin
data class VectorPath(
    val id: String,
    val name: String? = null,
    val pathData: String,
    val commands: List<PathCommand>? = null,
    val style: VectorStyle,
)
```

During Phase 1, `commands` may be nullable so the parser can preserve unknown or partially supported paths. Later phases should prefer parsed commands when available.

### VectorStyle

```kotlin
data class VectorStyle(
    val fillColor: String? = null,
    val fillAlpha: Float? = null,
    val fillType: String? = null,
    val strokeColor: String? = null,
    val strokeAlpha: Float? = null,
    val strokeWidth: Float? = null,
    val strokeLineCap: String? = null,
    val strokeLineJoin: String? = null,
    val strokeMiterLimit: Float? = null,
)
```

### VectorWarning

```kotlin
data class VectorWarning(
    val code: String,
    val message: String,
    val nodeId: String? = null,
)
```

Examples:

```text
UNSUPPORTED_TAG
UNSUPPORTED_ATTRIBUTE
MALFORMED_PATH_DATA
MISSING_VIEWPORT
MISSING_PATH_DATA
GRADIENT_NOT_SUPPORTED
CLIP_PATH_NOT_SUPPORTED
```

## Path command support

Phase 1 should parse and format the most common Android path commands:

```text
M, m
L, l
H, h
V, v
C, c
S, s
Q, q
T, t
A, a
Z, z
```

Phase 1 does not need to normalize relative commands to absolute commands, but the parser should represent whether each command is relative or absolute. Later phases can add normalization.

```kotlin
sealed interface PathCommand {
    val relative: Boolean

    data class MoveTo(val x: Float, val y: Float, override val relative: Boolean = false) : PathCommand
    data class LineTo(val x: Float, val y: Float, override val relative: Boolean = false) : PathCommand
    data class HorizontalTo(val x: Float, override val relative: Boolean = false) : PathCommand
    data class VerticalTo(val y: Float, override val relative: Boolean = false) : PathCommand
    data class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float, override val relative: Boolean = false) : PathCommand
    data class SmoothCubicTo(val x2: Float, val y2: Float, val x: Float, val y: Float, override val relative: Boolean = false) : PathCommand
    data class QuadTo(val x1: Float, val y1: Float, val x: Float, val y: Float, override val relative: Boolean = false) : PathCommand
    data class SmoothQuadTo(val x: Float, val y: Float, override val relative: Boolean = false) : PathCommand
    data class ArcTo(val rx: Float, val ry: Float, val xAxisRotation: Float, val largeArc: Boolean, val sweep: Boolean, val x: Float, val y: Float, override val relative: Boolean = false) : PathCommand
    data class Close(override val relative: Boolean = false) : PathCommand
}
```

Path parser requirements:

- Accept compact Android/SVG path syntax with or without spaces.
- Accept commas and whitespace as separators.
- Accept negative numbers adjacent to previous values, for example `M10-5`.
- Accept decimals and exponent notation, for example `1e-3`.
- Support repeated coordinate groups after a command, for example `M0 0 10 10`.
- Return a recoverable parse result with warnings rather than crashing.

## Metrics model

```kotlin
data class VectorMetrics(
    val xmlBytes: Int,
    val pathCount: Int,
    val groupCount: Int,
    val commandCount: Int,
    val parsedCommandCount: Int,
    val unsupportedPathCount: Int,
    val estimatedPointCount: Int,
    val colorCounts: Map<String, Int>,
    val strokePathCount: Int,
    val fillPathCount: Int,
    val zeroLengthPathCount: Int,
    val tinySegmentEstimate: Int,
    val duplicateCoordinateEstimate: Int,
    val bounds: VectorBounds?,
    val warnings: List<VectorWarning>,
)
```

```kotlin
data class VectorBounds(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
)
```

Phase 1 metrics may estimate bounds for M/L/H/V/Q/C/A commands conservatively. Exact curve bounds can wait until later. A useful estimate is better than no signal.

## Model-facing summary JSON

Later phases should add `VectorSummaryJson`, similar in spirit to `VectorCanvasJson`.

Example:

```json
{
  "schema": 1,
  "format": "android_vector_drawable",
  "viewport": { "width": 108, "height": 108, "viewportWidth": 108, "viewportHeight": 108 },
  "metrics": {
    "pathCount": 38,
    "commandCount": 70000,
    "xmlBytes": 1900000,
    "colors": { "#109F5C": 15, "#2D2D2D": 4 }
  },
  "paths": [
    {
      "id": "p_001",
      "style": { "stroke": "#109F5C", "width": 1.2, "fill": null },
      "bounds": [16.4, 54.5, 42.1, 106.7],
      "commandCount": 2450,
      "sampledPoints": [[16.4,67.5],[17.2,66.1],[18.0,65.0]],
      "noise": { "tinySegments": 400, "duplicatePoints": 120 }
    }
  ]
}
```

## AI edit plan schema

A future model-guided tune-up should return operations that the app applies, not raw XML.

```json
{
  "schema": 1,
  "mode": "faithful_cleanup",
  "summary": "Simplify noisy stroke paths and normalize stroke widths.",
  "operations": [
    {
      "op": "simplify_paths",
      "target": { "pathIds": ["p_001", "p_002"] },
      "tolerance": 0.25,
      "preserveEndpoints": true
    },
    {
      "op": "restyle_paths",
      "target": { "colors": ["#109F5C"] },
      "strokeWidth": 1.2,
      "lineCap": "round",
      "lineJoin": "round"
    }
  ]
}
```

Supported operations for the first model-guided phase:

```text
simplify_paths
remove_paths
restyle_paths
recolor_paths
transform_paths
normalize_viewport
```

Later semantic redraw should use a separate scene schema rather than overloading path operations.

## Semantic redraw scene schema

For polished redraw mode, the model should return a clean scene, and the app should compile it to VectorDrawable XML.

```json
{
  "schema": 1,
  "viewport": { "width": 108, "height": 108, "viewportWidth": 108, "viewportHeight": 108 },
  "styleIntent": "clean hand-drawn icon",
  "objects": [
    {
      "id": "roof",
      "type": "polygon",
      "points": [[27, 23], [39, 6], [52, 23]],
      "stroke": "#2D2D2D",
      "fill": "#F8F8F8",
      "strokeWidth": 1.2
    },
    {
      "id": "flower_center",
      "type": "ellipse",
      "cx": 78,
      "cy": 28,
      "rx": 3,
      "ry": 3,
      "stroke": "#D62828",
      "fill": "#FF9F1C",
      "strokeWidth": 1
    }
  ]
}
```

## Versioning model

```kotlin
data class VectorTuneupProject(
    val id: String,
    val title: String,
    val sourceXml: String,
    val versions: List<VectorVersion>,
    val createdAt: Long,
    val updatedAt: Long,
)

data class VectorVersion(
    val id: String,
    val parentId: String?,
    val label: String,
    val instruction: String,
    val mode: VectorTuneupMode,
    val xml: String,
    val metrics: VectorMetrics,
    val previewPngUri: String?,
    val editPlanJson: String?,
    val warnings: List<VectorWarning>,
    val createdAt: Long,
)

enum class VectorTuneupMode {
    ORIGINAL,
    OPTIMIZE,
    TUNE_UP,
    REDRAW,
    MANUAL_EDIT,
}
```

Versioning can start in memory for the first UI iteration, then move to Room once the workflow is stable.

## Preview strategy

The app needs consistent preview generation for original and candidate versions.

Possible preview approaches:

1. Use Android `VectorDrawable` inflation from XML into a drawable, then render to `Bitmap`.
2. Convert `VectorDocument` to Compose drawing commands for on-canvas preview.
3. Convert to SVG and render via a library.

Recommended first Android-native approach:

```text
VectorDrawable XML string -> temporary file or in-memory XML parser -> Drawable -> Bitmap PNG
```

If direct XML inflation from string is awkward, Phase 1 can defer raster preview and focus on parser/writer/metrics. Phase 3 should add preview.

## Phased implementation plan

### Phase 1: External Vector Foundation

Goal:

Create a robust internal representation for Android VectorDrawable XML and prove it can parse, inspect, and round-trip simple and moderately complex vector files.

Scope:

- New `data/vector` package.
- Parse `<vector>`, `<group>`, and `<path>`.
- Preserve essential Android vector attributes.
- Parse common `android:pathData` commands.
- Emit XML back to a VectorDrawable string.
- Compute basic metrics and warnings.
- Add JVM unit tests.

Deliverables:

```text
VectorDocument.kt
AndroidVectorDrawableParser.kt
AndroidVectorDrawableWriter.kt
PathDataParser.kt
PathDataFormatter.kt
VectorMetricsAnalyzer.kt
VectorDocumentValidator.kt
Unit tests
```

Acceptance criteria:

- A minimal VectorDrawable parses and writes successfully.
- Width, height, viewport, groups, paths, path data, fill color, stroke color, stroke width, line cap, line join, alpha, and fill type are preserved.
- Common path syntax including compact negative numbers parses.
- Unsupported tags or malformed paths produce warnings, not crashes.
- Writer output can be parsed again into an equivalent `VectorDocument` for supported fields.
- Tests run with plain JVM unit tests where possible.
- No UI changes.
- No AI/network calls.

Suggested tests:

```text
parseMinimalVector()
parseStrokeAndFillPath()
parseNestedGroup()
parseCompactPathData()
pathDataRoundTripPreservesCommands()
writerRoundTripPreservesSupportedFields()
malformedPathProducesWarning()
metricsCountsPathsCommandsColorsAndWarnings()
```

### Phase 2: Faithful Deterministic Optimizer

Goal:

Produce the first useful tune-up result without AI: reduce XML size and path noise while preserving visual intent.

Scope:

- Normalize path commands where needed.
- Convert path segments to sampled points for simplification.
- Remove duplicate/near-duplicate points.
- Remove zero-length and tiny paths.
- Apply Ramer-Douglas-Peucker simplification.
- Rebuild pathData with rounded floats.
- Preserve style attributes.
- Add before/after metrics.

Deliverables:

```text
VectorPathSampler.kt
VectorPathSimplifier.kt
VectorDrawableOptimizer.kt
VectorOptimizeOptions.kt
VectorOptimizationReport.kt
Unit tests with golden XML snippets
```

Acceptance criteria:

- Optimizer can shrink noisy path XML substantially.
- Optimizer preserves viewport and styling.
- Tolerance controls strength.
- Optimizer report includes bytes before/after, command count before/after, path count before/after, removed path count, and warnings.
- Optimized XML parses again.

### Phase 3: Tune-Up Workspace UI

Goal:

Give users an end-to-end local workflow for importing XML, seeing diagnostics, generating optimized candidates, comparing, and exporting.

Scope:

- File import for `.xml` VectorDrawable files.
- Vector project screen or sheet.
- Metrics panel.
- Optimize controls.
- Original/candidate preview.
- Export candidate XML.
- In-memory version list.

Deliverables:

```text
VectorTuneupViewModel.kt
VectorTuneupScreen.kt
VectorVersion state model
Import action
Optimize action
Export action
Preview renderer
```

Acceptance criteria:

- User can import XML and see metrics.
- User can generate at least one optimized candidate.
- User can compare original and candidate.
- User can export candidate XML.
- Errors and warnings are visible and non-fatal.

### Phase 4: Model-Guided Tune-Up Plans

Goal:

Let AI diagnose a vector and propose safe machine-readable edit plans, while the app remains responsible for validation and application.

Scope:

- Add `VectorSummaryJson`.
- Add `VectorEditPlan` schema and parser.
- Add AI request path for vector tune-up.
- Use image preview plus vector summary.
- Return operations, not XML.
- Apply operations deterministically.
- Stage candidate version for user review.

Deliverables:

```text
VectorSummaryJson.kt
VectorEditPlan.kt
VectorEditPlanParser.kt
VectorEditPlanApplier.kt
VectorTuneupAiService.kt
Prompt constants
Tests for parser/applier
```

Acceptance criteria:

- Model response cannot directly mutate files or app state.
- Unknown path IDs and invalid operations are dropped with warnings.
- Empty/no-op plans are handled gracefully.
- Candidate version includes summary and operation report.

### Phase 5: Semantic Redraw Mode

Goal:

Support the polished redraw workflow where the model reconstructs rough vector art as a clean scene of primitives.

Scope:

- Define `VectorScene` schema.
- Compile scene primitives to VectorDrawable XML.
- Add prompt and parser for semantic redraw.
- Render candidate and compare against original.
- Support user prompts like `make it cleaner`, `more faithful`, `make it icon-ready`, and `restore original colors`.

Deliverables:

```text
VectorScene.kt
VectorSceneParser.kt
VectorSceneCompiler.kt
VectorRedrawAiService.kt
Scene compiler tests
Prompt tests or fixtures
```

Acceptance criteria:

- Scene output is bounded to viewport.
- Invalid objects are skipped with warnings.
- Compiler output parses as VectorDrawable XML.
- Candidate can be versioned and exported.

### Phase 6: Persistent Projects and Iteration History

Goal:

Turn the workflow into a durable workspace with branching and revision history.

Scope:

- Room entities for projects and versions.
- Preview cache management.
- Branching from prior versions.
- Version labels and notes.
- Undo-like version navigation.

Deliverables:

```text
VectorTuneupProjectEntity
VectorVersionEntity
VectorTuneupDao
VectorTuneupRepository
Migration
Version history UI
```

Acceptance criteria:

- Projects survive app restart.
- Versions preserve parent/child lineage.
- User can export any version.
- Old previews are pruned safely.

### Phase 7: Advanced Editing and Quality Scoring

Goal:

Add polish, scoring, and deeper controls.

Scope:

- Visual diff overlays.
- Quality scores: cleanliness, faithfulness, icon readiness, file size.
- Manual per-path editing controls.
- Batch restyle by color/path group.
- Optional SVG import/export.
- Optional path-to-shape detection.

Acceptance criteria:

- Users can understand tradeoffs between faithful and polished outputs.
- Advanced edits remain reversible through versioning.

## Safety and validation

Every AI-related phase must obey these constraints:

- The model returns plans, not app mutations.
- App validates every ID, path, color, transform, and numeric bound.
- App rejects or clamps geometry outside reasonable viewport limits.
- App keeps the original source XML unchanged.
- Every candidate is previewed before export or replacement.
- Malformed model output becomes a warning, not a crash.

## Testing strategy

### JVM unit tests

Focus on parser, formatter, metrics, optimizer, plan parser, and plan applier. These should avoid Android framework dependencies where possible.

### Golden tests

Keep small XML fixtures for:

- Minimal vector.
- Stroke-only vector.
- Fill-only vector.
- Nested group vector.
- Noisy path vector.
- Malformed path vector.

### Property-style tests

Useful for path parsing:

```text
format(parse(pathData)) parses again
parser never throws on malformed random strings
optimizer output parses again
```

### Android/instrumented tests

Use later for preview rendering if the renderer depends on Android drawable inflation.

## Phase 1 Claude Code prompt

See `docs/VECTOR_ART_TUNEUP_PHASE_1_CLAUDE_PROMPT.md` for the ready-to-paste implementation prompt.
