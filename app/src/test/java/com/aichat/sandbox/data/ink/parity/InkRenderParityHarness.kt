package com.aichat.sandbox.data.ink.parity

import androidx.ink.brush.InputToolType
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.Stroke
import com.aichat.sandbox.data.ink.InkInterop
import com.aichat.sandbox.data.model.BrushPreset
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.aichat.sandbox.ui.components.notes.ToolDynamics
import kotlin.math.hypot

/**
 * Phase **I2 — rendering parity gate** (headless geometry slice).
 *
 * The default-on gate requires ink's mesh rendering to match
 * `StrokeRenderer`'s variable-width Bézier output, specifically the **taper**
 * (pressure/tilt-driven width modulation). The I0.7 spike answered the *overall
 * footprint* question (coverage / area IoU); this harness answers the
 * complementary **width-profile** question taper actually turns on: *does ink's
 * local stroke thickness rise and fall along the stroke the same way the current
 * engine's [ToolDynamics] curve does?* The measured answer (see
 * `InkRenderParityTest.pressureTaperIsCurrentEngineOnlyUntilI4`) is **no, not
 * yet**: ink's stable stock `pressurePen` holds a constant `size`-width tube,
 * because `InkInterop` maps only the stable brush *identity* (family + colour +
 * size) — pressure-driven taper needs a custom `BrushTip`/`BrushBehavior` and is
 * an explicit **I4** item. This harness exists to *measure* that gap precisely,
 * not to assert a parity that doesn't hold yet.
 *
 * Both profiles are sampled at the stroke's own centerline points:
 *  - **current:** the [ToolDynamics] half-width at that sample's pressure/tilt —
 *    exactly the radius `StrokeRenderer` / `StrokeOutliner` paint.
 *  - **ink:** the distance from that centerline point to the nearest vertex of
 *    ink's native mesh outline — a faithful proxy for ink's local half-width,
 *    read back through the same `PartitionedMesh` API the I0.7 spike used.
 *
 * The two profiles are compared by *trend*, not absolute width (ink's stock
 * brushes have their own response — see I0.7's per-tool notes). Unlike the
 * throwaway I0.7 spike, this harness is a **permanent** gate fixture.
 *
 * Jitter, procedural texture, anti-aliasing, and on-screen colour blending are
 * still out of scope here — those need the on-device `CanvasStrokeRenderer`
 * pixel diff documented in `docs/INK_I2_PARITY_GATE.md`.
 */
object InkRenderParityHarness {

    /** Representative on-screen base widths per tool (CSS-px world space). */
    val BASE_WIDTH = mapOf(
        "pen" to 4f,
        "pencil" to 6f,
        "highlighter" to 24f,
        "marker" to 10f,
    )

    /** A perpendicular cross-section read that landed outside the mesh. */
    const val NO_WIDTH = -1f

    /**
     * Per-sample local **width** the current engine paints, from the
     * [ToolDynamics] curve the live renderer and exporter both use.
     */
    fun widthProfileCurrent(samples: FloatArray, tool: String, baseWidth: Float): FloatArray {
        val s = StrokeCodec.FLOATS_PER_SAMPLE
        val n = samples.size / s
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = ToolDynamics.forTool(
                tool, baseWidth, samples[i * s + 2], samples[i * s + 3],
            ).widthPx
        }
        return out
    }

    /**
     * Per-sample local **width** of ink's native mesh, measured by casting a ray
     * perpendicular to the stroke tangent at each centerline sample and taking
     * the cross-section between the nearest outline crossings on either side.
     * This reads the true local thickness — robust to the mesh's outline
     * tessellation, unlike a nearest-vertex estimate. Builds the ink [Stroke]
     * through the same [InkInterop] seam the authoring path commits.
     *
     * Returns [NO_WIDTH] for any sample whose perpendicular doesn't bracket the
     * mesh (the stroke caps), so callers can restrict to interior samples.
     */
    fun widthProfileInk(samples: FloatArray, tool: String, baseWidth: Float): FloatArray {
        val brush = InkInterop.toBrush(presetFor(tool, baseWidth))
        val stroke = InkInterop.toStroke(StrokeCodec.encode(samples), brush, InputToolType.STYLUS)
        val loops = inkOutlineLoops(stroke)
        val s = StrokeCodec.FLOATS_PER_SAMPLE
        val n = samples.size / s
        val out = FloatArray(n)
        for (i in 0 until n) {
            val px = samples[i * s]
            val py = samples[i * s + 1]
            // Central-difference tangent; one-sided at the ends.
            val prev = (if (i == 0) 0 else i - 1) * s
            val next = (if (i == n - 1) n - 1 else i + 1) * s
            var tx = samples[next] - samples[prev]
            var ty = samples[next + 1] - samples[prev + 1]
            val len = hypot(tx, ty)
            if (len < 1e-4f) { out[i] = NO_WIDTH; continue }
            tx /= len; ty /= len
            // Normal = tangent rotated +90°.
            out[i] = crossSectionWidth(loops, px, py, -ty, tx)
        }
        return out
    }

    /** Read every outline loop of an ink stroke's mesh as packed `[x,y,…]`. */
    private fun inkOutlineLoops(stroke: Stroke): List<FloatArray> {
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
     * Width of the mesh along the line through (`px`,`py`) with unit normal
     * (`nx`,`ny`): the distance from the nearest outline crossing on the
     * positive side to the nearest on the negative side. [NO_WIDTH] if the line
     * doesn't cross on both sides (point outside the mesh / at a cap).
     */
    private fun crossSectionWidth(
        loops: List<FloatArray>, px: Float, py: Float, nx: Float, ny: Float,
    ): Float {
        var posMin = Float.MAX_VALUE   // smallest s > 0
        var negMin = Float.MAX_VALUE   // smallest |s| for s < 0
        for (poly in loops) {
            val m = poly.size / 2
            var j = m - 1
            for (k in 0 until m) {
                val ax = poly[k * 2]; val ay = poly[k * 2 + 1]
                val bx = poly[j * 2]; val by = poly[j * 2 + 1]
                val s = raySegmentParam(px, py, nx, ny, ax, ay, bx, by)
                if (!s.isNaN()) {
                    if (s > 0f && s < posMin) posMin = s
                    if (s < 0f && -s < negMin) negMin = -s
                }
                j = k
            }
        }
        return if (posMin == Float.MAX_VALUE || negMin == Float.MAX_VALUE) NO_WIDTH
        else posMin + negMin
    }

    /**
     * Parameter `s` such that (`px`,`py`) + s·(`nx`,`ny`) lies on segment
     * `A→B`, or `NaN` if the infinite ray misses the segment span.
     */
    private fun raySegmentParam(
        px: Float, py: Float, nx: Float, ny: Float,
        ax: Float, ay: Float, bx: Float, by: Float,
    ): Float {
        val ex = bx - ax
        val ey = by - ay
        // Solve P + s*N = A + u*E  →  [N -E][s u]^T = A - P.
        val det = nx * (-ey) - ny * (-ex)
        if (kotlin.math.abs(det) < 1e-9f) return Float.NaN
        val rx = ax - px
        val ry = ay - py
        val s = (rx * (-ey) - ry * (-ex)) / det
        val u = (nx * ry - ny * rx) / det
        return if (u in 0f..1f) s else Float.NaN
    }

    private fun presetFor(tool: String, baseWidth: Float) = BrushPreset(
        ownerScope = BrushPreset.SCOPE_APP,
        name = "parity-$tool",
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

    // ── Sample synthesis ──────────────────────────────────────────────────────

    /** Horizontal stroke with a per-sample pressure profile, as `[x,y,p,t]*`. */
    fun horizontalStroke(n: Int, pressure: (Float) -> Float, tilt: Float = 0.15f): FloatArray {
        val out = FloatArray(n * StrokeCodec.FLOATS_PER_SAMPLE)
        for (i in 0 until n) {
            val t = if (n <= 1) 0f else i.toFloat() / (n - 1)
            val b = i * StrokeCodec.FLOATS_PER_SAMPLE
            out[b] = 40f + 320f * t
            out[b + 1] = 200f
            out[b + 2] = pressure(t).coerceIn(0f, 1f)
            out[b + 3] = tilt
        }
        return out
    }
}
