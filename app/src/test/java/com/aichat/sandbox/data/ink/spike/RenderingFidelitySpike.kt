package com.aichat.sandbox.data.ink.spike

import androidx.ink.brush.InputToolType
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.Stroke
import com.aichat.sandbox.data.ink.InkInterop
import com.aichat.sandbox.data.model.BrushPreset
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.aichat.sandbox.ui.components.notes.StrokeOutliner
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Phase **I0.7 — rendering fidelity spike** (throwaway; see
 * `docs/ANDROIDX_INK_MIGRATION_PLAN.md` and
 * `docs/INK_I07_RENDERING_FIDELITY_SPIKE.md`).
 *
 * The migration plan gates the ink-authoring work (I1) on a cheap, throwaway
 * comparison of "the same strokes rendered through the current engine vs. ink",
 * yielding a **per-tool go/no-go** for pen, pencil, highlighter, and marker
 * *before* any `InProgressStrokesView` wiring is built.
 *
 * ## Why this is a *coverage* diff, not a literal pixel diff
 * A literal screen-pixel diff needs `CanvasStrokeRenderer` (ink-rendering) and
 * our [com.aichat.sandbox.ui.components.notes.StrokeRenderer] to both draw onto
 * an `android.graphics.Canvas` — Android-only classes that don't exist in the
 * headless JVM unit-test host this project's CI runs (ink-rendering isn't even
 * on the classpath; only strokes/brush/geometry are). So this spike compares
 * the two engines at the layer that *is* reproducible headless and that
 * actually gates the decision: the **filled region each engine covers**.
 *
 *  - **Engine A (current):** [StrokeOutliner.outline] builds the exact
 *    pressure/tilt-following coverage polygon the renderer fills (it uses the
 *    very same [com.aichat.sandbox.ui.components.notes.ToolDynamics] width
 *    curve the live `StrokeRenderer` paints, which is why it's a faithful proxy
 *    for "what the current engine puts on screen").
 *  - **Engine B (ink):** [InkInterop.toStroke] → [Stroke.shape] (a native
 *    `PartitionedMesh`); we read back its outline loop(s).
 *
 * Both polygons are rasterized onto one shared grid with the *same* even-odd
 * routine (the grid cells are the "pixels"), and we report coverage IoU, bbox
 * IoU, area ratio (ink/current), and centroid drift per stroke, aggregated to a
 * per-tool verdict.
 *
 * Colour, opacity, texture, and anti-aliased edges are deliberately **out of
 * scope** here — those are the parts that genuinely need an on-device pixel
 * diff on the target S25 Ultra panel, and the spike report calls that out as
 * the complementary check. What this harness answers is the gating geometry
 * question: *does ink's stock-brush mesh land on the same footprint, at the
 * same width, as the engine we ship today?*
 *
 * This file lives under `src/test` on purpose: it is throwaway spike
 * scaffolding, never shipped in the APK.
 */
object RenderingFidelitySpike {

    /** The four tools the migration plan demands an explicit verdict for. */
    val TOOLS = listOf("pen", "pencil", "highlighter", "marker")

    /** Representative on-screen base widths per tool (CSS-px world space). */
    private val BASE_WIDTH = mapOf(
        "pen" to 4f,
        "pencil" to 6f,
        "highlighter" to 24f,
        "marker" to 10f,
    )

    /** Long-edge resolution of the shared rasterization grid (the "pixels"). */
    private const val GRID = 96

    // ── Public result types ────────────────────────────────────────────────

    enum class Decision { GO, GO_WITH_BRUSH_WORK, NO_GO }

    /** One representative stroke compared through both engines. */
    data class StrokeResult(
        val shape: String,
        val tool: String,
        val sampleCount: Int,
        val coverageIoU: Float,
        val bboxIoU: Float,
        /** Ink covered-area ÷ current covered-area (1.0 = identical footprint). */
        val areaRatio: Float,
        /** Centroid drift as a fraction of the union-bbox diagonal. */
        val centroidDrift: Float,
    )

    /** Aggregated go/no-go for one tool across all corpus shapes. */
    data class ToolVerdict(
        val tool: String,
        val strokeCount: Int,
        val meanCoverageIoU: Float,
        val minCoverageIoU: Float,
        val meanBboxIoU: Float,
        val meanAreaRatio: Float,
        val meanCentroidDrift: Float,
        val decision: Decision,
    )

    data class Report(
        val strokes: List<StrokeResult>,
        val verdicts: List<ToolVerdict>,
    )

    // ── Entry point ────────────────────────────────────────────────────────

    /** Run the full corpus through both engines and aggregate per-tool. */
    fun run(): Report {
        val corpus = buildCorpus()
        val results = corpus.map { compare(it) }
        val verdicts = TOOLS.map { tool -> verdict(tool, results.filter { it.tool == tool }) }
        return Report(results, verdicts)
    }

    // ── Per-stroke comparison ──────────────────────────────────────────────

    private fun compare(spec: StrokeSpec): StrokeResult {
        val samples = spec.samples
        val sampleCount = samples.size / StrokeCodec.FLOATS_PER_SAMPLE
        val baseWidth = BASE_WIDTH.getValue(spec.tool)

        // Engine A: the polygon the current renderer fills.
        val outlineA = StrokeOutliner.outline(samples, spec.tool, baseWidth)
        val polysA = if (outlineA.size >= 6) listOf(outlineA) else emptyList()

        // Engine B: ink's native mesh outline(s).
        val preset = presetFor(spec.tool, baseWidth)
        val brush = InkInterop.toBrush(preset)
        val stroke: Stroke = InkInterop.toStroke(
            StrokeCodec.encode(samples), brush, InputToolType.STYLUS,
        )
        val polysB = inkOutlines(stroke)

        return rasterCompare(spec, sampleCount, polysA, polysB)
    }

    /** Read every outline loop of an ink stroke's mesh as packed [x,y,…]. */
    private fun inkOutlines(stroke: Stroke): List<FloatArray> {
        val mesh = stroke.shape
        val out = ArrayList<FloatArray>()
        val v = MutableVec()
        for (g in 0 until mesh.getRenderGroupCount()) {
            for (o in 0 until mesh.getOutlineCount(g)) {
                val vc = mesh.getOutlineVertexCount(g, o)
                if (vc < 3) continue
                val poly = FloatArray(vc * 2)
                for (i in 0 until vc) {
                    mesh.populateOutlinePosition(g, o, i, v)
                    poly[i * 2] = v.x
                    poly[i * 2 + 1] = v.y
                }
                out.add(poly)
            }
        }
        return out
    }

    /**
     * Rasterize both engines' polygons onto one shared grid and derive the
     * coverage metrics. The grid spans the union of both footprints so neither
     * engine is clipped; cell centers are sampled with an even-odd parity test.
     */
    private fun rasterCompare(
        spec: StrokeSpec,
        sampleCount: Int,
        polysA: List<FloatArray>,
        polysB: List<FloatArray>,
    ): StrokeResult {
        val boundsA = bounds(polysA)
        val boundsB = bounds(polysB)
        val union = unionBounds(boundsA, boundsB)
            ?: return StrokeResult(spec.shape, spec.tool, sampleCount, 1f, 1f, 1f, 0f)

        val (xMin, yMin, xMax, yMax) = union
        val w = xMax - xMin
        val h = yMax - yMin
        val cell = max(w, h) / GRID
        if (cell <= 0f) {
            return StrokeResult(spec.shape, spec.tool, sampleCount, 1f, 1f, 1f, 0f)
        }
        val cols = max(1, (w / cell).toInt() + 1)
        val rows = max(1, (h / cell).toInt() + 1)

        var both = 0
        var onlyA = 0
        var onlyB = 0
        var cxA = 0.0; var cyA = 0.0; var nA = 0
        var cxB = 0.0; var cyB = 0.0; var nB = 0
        for (r in 0 until rows) {
            val y = yMin + (r + 0.5f) * cell
            for (c in 0 until cols) {
                val x = xMin + (c + 0.5f) * cell
                val inA = insideEvenOdd(polysA, x, y)
                val inB = insideEvenOdd(polysB, x, y)
                if (inA) { cxA += x; cyA += y; nA++ }
                if (inB) { cxB += x; cyB += y; nB++ }
                when {
                    inA && inB -> both++
                    inA -> onlyA++
                    inB -> onlyB++
                }
            }
        }

        val either = both + onlyA + onlyB
        val coverageIoU = if (either == 0) 1f else both.toFloat() / either
        val areaRatio = when {
            nA == 0 && nB == 0 -> 1f
            nA == 0 -> Float.POSITIVE_INFINITY
            else -> nB.toFloat() / nA
        }
        val centroidDrift = if (nA == 0 || nB == 0) {
            if (nA == 0 && nB == 0) 0f else 1f
        } else {
            val dx = (cxA / nA) - (cxB / nB)
            val dy = (cyA / nA) - (cyB / nB)
            val diag = hypot(w.toDouble(), h.toDouble()).toFloat()
            if (diag <= 0f) 0f else (hypot(dx, dy).toFloat() / diag)
        }

        return StrokeResult(
            shape = spec.shape,
            tool = spec.tool,
            sampleCount = sampleCount,
            coverageIoU = coverageIoU,
            bboxIoU = bboxIoU(boundsA, boundsB),
            areaRatio = areaRatio,
            centroidDrift = centroidDrift,
        )
    }

    // ── Per-tool verdict ───────────────────────────────────────────────────

    /**
     * Spike-grade go/no-go thresholds (judgment calls, documented in the report):
     *  - **GO** — ink's footprint already tracks the current engine closely
     *    (coverage IoU ≥ 0.72 *and* bbox IoU ≥ 0.88): safe to build authoring on.
     *  - **GO_WITH_BRUSH_WORK** — the footprint is in the right place but width /
     *    stock-brush shape needs the I4 brush-mapping pass (coverage IoU ≥ 0.50).
     *  - **NO_GO** — ink's stock-brush geometry diverges materially; revisit the
     *    brush mapping before committing this tool to the ink path.
     *
     * These bound the *geometry* decision only; the on-device colour/texture
     * pixel diff is the separate gate the report flags.
     */
    private fun verdict(tool: String, rows: List<StrokeResult>): ToolVerdict {
        val finite = rows.filter { it.areaRatio.isFinite() }
        val meanCov = rows.map { it.coverageIoU }.average().toFloat()
        val minCov = rows.minOfOrNull { it.coverageIoU } ?: 0f
        val meanBbox = rows.map { it.bboxIoU }.average().toFloat()
        val meanArea = if (finite.isEmpty()) Float.NaN else finite.map { it.areaRatio }.average().toFloat()
        val meanDrift = rows.map { it.centroidDrift }.average().toFloat()

        val decision = when {
            meanCov >= 0.72f && meanBbox >= 0.88f -> Decision.GO
            meanCov >= 0.50f -> Decision.GO_WITH_BRUSH_WORK
            else -> Decision.NO_GO
        }
        return ToolVerdict(tool, rows.size, meanCov, minCov, meanBbox, meanArea, meanDrift, decision)
    }

    // ── Geometry helpers ───────────────────────────────────────────────────

    private data class Bounds(val xMin: Float, val yMin: Float, val xMax: Float, val yMax: Float)

    private operator fun Bounds.component1() = xMin
    private operator fun Bounds.component2() = yMin
    private operator fun Bounds.component3() = xMax
    private operator fun Bounds.component4() = yMax

    private fun bounds(polys: List<FloatArray>): Bounds? {
        var xMin = Float.MAX_VALUE; var yMin = Float.MAX_VALUE
        var xMax = -Float.MAX_VALUE; var yMax = -Float.MAX_VALUE
        var any = false
        for (poly in polys) {
            var i = 0
            while (i < poly.size) {
                val x = poly[i]; val y = poly[i + 1]
                xMin = min(xMin, x); yMin = min(yMin, y)
                xMax = max(xMax, x); yMax = max(yMax, y)
                any = true
                i += 2
            }
        }
        return if (any) Bounds(xMin, yMin, xMax, yMax) else null
    }

    private fun unionBounds(a: Bounds?, b: Bounds?): Bounds? = when {
        a == null && b == null -> null
        a == null -> b
        b == null -> a
        else -> Bounds(
            min(a.xMin, b.xMin), min(a.yMin, b.yMin),
            max(a.xMax, b.xMax), max(a.yMax, b.yMax),
        )
    }

    private fun bboxIoU(a: Bounds?, b: Bounds?): Float {
        if (a == null || b == null) return if (a == null && b == null) 1f else 0f
        val ix = max(0f, min(a.xMax, b.xMax) - max(a.xMin, b.xMin))
        val iy = max(0f, min(a.yMax, b.yMax) - max(a.yMin, b.yMin))
        val inter = ix * iy
        val areaA = (a.xMax - a.xMin) * (a.yMax - a.yMin)
        val areaB = (b.xMax - b.xMin) * (b.yMax - b.yMin)
        val union = areaA + areaB - inter
        return if (union <= 0f) 1f else inter / union
    }

    /** Even-odd point-in-polygon across every loop (handles unions and holes). */
    private fun insideEvenOdd(polys: List<FloatArray>, x: Float, y: Float): Boolean {
        var inside = false
        for (poly in polys) {
            val n = poly.size / 2
            if (n < 3) continue
            var j = n - 1
            for (i in 0 until n) {
                val xi = poly[i * 2]; val yi = poly[i * 2 + 1]
                val xj = poly[j * 2]; val yj = poly[j * 2 + 1]
                if (((yi > y) != (yj > y)) &&
                    (x < (xj - xi) * (y - yi) / ((yj - yi).takeIf { abs(it) > 1e-9f } ?: 1e-9f) + xi)
                ) {
                    inside = !inside
                }
                j = i
            }
        }
        return inside
    }

    // ── Representative stroke corpus ───────────────────────────────────────

    private data class StrokeSpec(val shape: String, val tool: String, val samples: FloatArray)

    /**
     * 16 representative shapes × 4 tools = 64 strokes, within the plan's 50–100
     * band. Shapes span the cases that stress stroke rendering: straight legs,
     * gentle and tight curves, loops, sharp corners, pressure tapers, pressure
     * dips, tilt sweeps (the pencil's defining input), dots, and 2-point stubs.
     */
    private fun buildCorpus(): List<StrokeSpec> {
        val shapes = shapeGenerators()
        return TOOLS.flatMap { tool ->
            shapes.map { (name, gen) -> StrokeSpec(name, tool, gen()) }
        }
    }

    private fun shapeGenerators(): List<Pair<String, () -> FloatArray>> = listOf(
        "line-horizontal" to { polyline(40) { t -> pt(lx(t), 200f) } },
        "line-diagonal" to { polyline(40) { t -> pt(lx(t), 40f + 320f * t.toFloat()) } },
        "arc-quarter" to { polyline(48) { t -> arc(t, 0.0, Math.PI / 2) } },
        "s-curve" to { polyline(60) { t -> pt(lx(t), 200f + 120f * sinDeg(t * 2.0)) } },
        "circle-loop" to { polyline(64) { t -> arc(t, 0.0, 2 * Math.PI) } },
        "zigzag-sharp" to { polyline(60) { t -> zigzag(t, 8) } },
        "corner-right" to { polyline(40) { t -> corner(t) } },
        "wave-long" to { polyline(80) { t -> pt(lx(t), 200f + 80f * sinDeg(t * 4.0)) } },
        "spiral" to { polyline(72) { t -> spiral(t) } },
        "stub-2pt" to { polyline(2) { t -> pt(180f + 40f * t.toFloat(), 200f) } },
        "dot-1pt" to { polyline(1) { pt(200f, 200f) } },
        // Pressure-driven variants (width modulation is where pen/pencil/marker differ).
        "taper-ramp" to { polyline(48, pressure = { t -> 0.05f + 0.9f * t.toFloat() }) { t -> pt(lx(t), 200f) } },
        "pressure-dip" to {
            polyline(48, pressure = { t -> 0.2f + 0.75f * abs(sinDeg(t)) }) { t -> pt(lx(t), 180f) }
        },
        "heavy-line" to { polyline(40, pressure = { 0.95f }) { t -> pt(lx(t), 220f) } },
        // Tilt-driven variants (the pencil's broadening cue; ignored by pen).
        "tilt-sweep" to {
            polyline(48, tilt = { t -> (0.1 + 1.3 * t).toFloat() }) { t -> pt(lx(t), 200f) }
        },
        "tilt-curve" to {
            polyline(56, tilt = { t -> (0.2 + 1.0 * t).toFloat() }) { t -> arc(t, 0.0, Math.PI) }
        },
    )

    /** Standard left-to-right x ramp across the 40..360 working canvas. */
    private fun lx(t: Double): Float = 40f + 320f * t.toFloat()

    // ── Sample synthesis ───────────────────────────────────────────────────

    private fun sinDeg(t: Double): Float = kotlin.math.sin(t * Math.PI).toFloat()

    private fun pt(x: Float, y: Float): FloatArray = floatArrayOf(x, y)

    private fun arc(t: Double, from: Double, to: Double): FloatArray {
        val a = from + (to - from) * t
        val r = 140f
        return pt((220f + r * kotlin.math.cos(a)).toFloat(), (200f + r * kotlin.math.sin(a)).toFloat())
    }

    private fun zigzag(t: Double, teeth: Int): FloatArray {
        val x = 40f + 320f * t.toFloat()
        val phase = (t * teeth) % 1.0
        val y = 160f + 80f * (if (phase < 0.5) phase * 2 else (1 - phase) * 2).toFloat()
        return pt(x, y)
    }

    private fun corner(t: Double): FloatArray = if (t < 0.5) {
        pt(60f + 280f * (t.toFloat() * 2f), 120f)
    } else {
        pt(340f, 120f + 200f * ((t.toFloat() - 0.5f) * 2f))
    }

    private fun spiral(t: Double): FloatArray {
        val a = t * 4 * Math.PI
        val r = 20f + 120f * t.toFloat()
        return pt((220f + r * kotlin.math.cos(a)).toFloat(), (200f + r * kotlin.math.sin(a)).toFloat())
    }

    /**
     * Build a v1 `[x,y,pressure,tilt]*` sample array from a parametric position
     * function plus optional pressure/tilt profiles (defaults: mid pressure,
     * near-vertical pen).
     */
    private fun polyline(
        count: Int,
        pressure: (Double) -> Float = { 0.6f },
        tilt: (Double) -> Float = { 0.15f },
        position: (Double) -> FloatArray,
    ): FloatArray {
        val out = FloatArray(count * StrokeCodec.FLOATS_PER_SAMPLE)
        var b = 0
        for (i in 0 until count) {
            val t = if (count <= 1) 0.0 else i.toDouble() / (count - 1)
            val p = position(t)
            out[b] = p[0]
            out[b + 1] = p[1]
            out[b + 2] = pressure(t).coerceIn(0f, 1f)
            out[b + 3] = tilt(t).coerceIn(0f, (Math.PI / 2).toFloat())
            b += StrokeCodec.FLOATS_PER_SAMPLE
        }
        return out
    }

    private fun presetFor(tool: String, baseWidth: Float) = BrushPreset(
        ownerScope = BrushPreset.SCOPE_APP,
        name = "spike-$tool",
        tool = tool,
        colorArgb = 0xFF202020.toInt(),
        baseWidthPx = baseWidth,
        opacity = if (tool == "highlighter") 0.35f else 1f,
        taperStart = 0f,
        taperEnd = 0f,
        jitter = 0f,
        pressureCurveId = BrushPreset.CURVE_LINEAR,
        textureId = BrushPreset.TEXTURE_SMOOTH,
        ordinal = 0,
    )

    // ── Report formatting ──────────────────────────────────────────────────

    /** Human-readable table for the test log and the committed spike report. */
    fun format(report: Report): String = buildString {
        appendLine("Ink I0.7 — rendering fidelity spike (coverage diff)")
        appendLine("=".repeat(72))
        appendLine("Per-stroke (coverageIoU / bboxIoU / areaRatio[ink÷cur] / centroidDrift):")
        appendLine("-".repeat(72))
        for (tool in TOOLS) {
            appendLine("[$tool]")
            for (r in report.strokes.filter { it.tool == tool }) {
                appendLine(
                    "  %-16s n=%-3d  cov=%.3f  bbox=%.3f  area=%s  drift=%.3f".format(
                        r.shape, r.sampleCount, r.coverageIoU, r.bboxIoU,
                        if (r.areaRatio.isFinite()) "%.2f".format(r.areaRatio) else "inf",
                        r.centroidDrift,
                    ),
                )
            }
        }
        appendLine("-".repeat(72))
        appendLine("Per-tool verdict:")
        for (v in report.verdicts) {
            appendLine(
                "  %-12s strokes=%-3d  meanCov=%.3f (min %.3f)  meanBbox=%.3f  meanArea=%s  meanDrift=%.3f  -> %s".format(
                    v.tool, v.strokeCount, v.meanCoverageIoU, v.minCoverageIoU,
                    v.meanBboxIoU, if (v.meanAreaRatio.isFinite()) "%.2f".format(v.meanAreaRatio) else "inf",
                    v.meanCentroidDrift, v.decision,
                ),
            )
        }
        appendLine("=".repeat(72))
    }
}
