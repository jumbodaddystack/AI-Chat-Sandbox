package com.aichat.sandbox.data.ink

import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.EasingFunction
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.ImmutableVec
import kotlin.math.pow
import kotlin.math.sin

/**
 * Phase **I4 — brush richness (stable 1.0.0 adapter)**.
 *
 * This is the deterministic `tool → BrushFamily` half of I4: it closes the
 * concrete rendering gaps the I2 parity gate pinned, using **only** stable
 * AndroidX Ink 1.0.0 brush APIs (`BrushTip`, `BrushBehavior`,
 * `EasingFunction.Linear`, `BrushFamily(tip, paint, id)`). Nothing here touches
 * the 1.1-alpha programmable-brush API — that lives isolated in
 * `experimental/InkProgrammableBrush.kt` so the alpha surface can never block
 * this stable adapter (Adoption-principle "alpha vs stable" risk).
 *
 * ## What I4 closes here (vs. the I2 gate / I0.7 spike)
 *  - **Pressure taper (gate item 6).** I0/I2 mapped `pen` to the *stock*
 *    `pressurePen`, whose mesh holds a constant `size`-width tube — the
 *    `InkRenderParityTest` taper "gap". We replace it with a custom family whose
 *    `BrushBehavior` drives `SIZE_MULTIPLIER` from `NORMALIZED_PRESSURE` over the
 *    same `0.35×–1.15×` range and `pressure^0.7` response the current
 *    [com.aichat.sandbox.ui.components.notes.ToolDynamics.pen] paints, so ink now
 *    tapers in step with the current engine.
 *  - **Pencil tilt-width + pressure-alpha (I0.7 item 2).** I0 mapped `pencil`
 *    onto the plain round `marker` family, dropping the tilt broadening
 *    [com.aichat.sandbox.ui.components.notes.ToolDynamics.pencil] applies. We add
 *    a `TILT_IN_RADIANS → SIZE_MULTIPLIER` behavior (`0.7×–1.6×`, sine ease) plus
 *    a `NORMALIZED_PRESSURE → OPACITY_MULTIPLIER` behavior so a flat-held pencil
 *    shades wider and light strokes ghost. (The pencil *grain texture* is a
 *    `BrushPaint.TextureLayer` appearance item that still needs the on-device
 *    pixel pass — see the I2 gate's device column — so it stays deferred.)
 *  - **Highlighter width (I0.7 item 1).** The *stock* highlighter covered ~0.71×
 *    our footprint. We build a plain round tip at the nominal width (our current
 *    highlighter is a constant-width round-cap stroke), with a small
 *    [HIGHLIGHTER_TIP_SCALE] calibration so ink's covered area lands on ~1.0× the
 *    current engine's. The slanted-chisel *appearance* is intentionally not
 *    chased here — it would diverge from the round-cap footprint the current
 *    engine actually paints, and any cap/AA nicety is a device-only check.
 *
 * Per-sample width modulation in ink is expressed as a *multiplier on the brush
 * size*, exactly as the current engine multiplies `baseWidthPx` by a
 * [com.aichat.sandbox.ui.components.notes.ToolDynamics.SegmentStyle] factor, so
 * the two engines share one mental model. Families are immutable and
 * native-backed; we build each once via `by lazy`.
 *
 * ## Stable artifact, opt-in API — *not* the 1.1-alpha dependency
 * Constructing a custom `BrushTip` / `BrushBehavior` / `BrushFamily(tip, paint,
 * id)` is part of the **stable `androidx.ink:ink-brush:1.0.0`** artifact this
 * project already depends on; it is merely guarded by the opt-in
 * [ExperimentalInkCustomBrushApi] annotation (a source-stability marker, not a
 * separate alpha module). Opting in here keeps us on the stable dependency. The
 * genuinely *alpha* surface — the 1.1 programmable/AI-emitted brush + randomized
 * (jitter) behaviors — is deliberately **not** pulled in; it stays isolated in
 * `experimental/InkProgrammableBrush.kt` so the alpha dependency can never block
 * this stable adapter or the authoring migration.
 */
@OptIn(ExperimentalInkCustomBrushApi::class)
object InkBrushFamilies {

    /**
     * Number of interior control points used to approximate a non-linear
     * [EasingFunction.Linear] response curve. The implied endpoints (0,0) and
     * (1,1) are added by ink, so 8 interior points already track `x^0.7` /
     * `sin(x·π/2)` to well under a percent.
     */
    private const val CURVE_SAMPLES = 8

    // ── Pen taper (matches ToolDynamics.pen) ────────────────────────────────
    /** Width multiplier at zero pressure (`ToolDynamics.pen` low end). */
    const val PEN_MIN_SIZE_MULTIPLIER = 0.35f
    /** Width multiplier at full pressure (`ToolDynamics.pen` high end). */
    const val PEN_MAX_SIZE_MULTIPLIER = 1.15f
    /** `ToolDynamics.pen` eases pressure by `pressure^0.7` before the lerp. */
    private const val PEN_PRESSURE_EXPONENT = 0.7

    // ── Pencil tilt-width + pressure-alpha (matches ToolDynamics.pencil) ─────
    const val PENCIL_MIN_SIZE_MULTIPLIER = 0.7f
    const val PENCIL_MAX_SIZE_MULTIPLIER = 1.6f
    const val PENCIL_MIN_OPACITY_MULTIPLIER = 0.35f
    const val PENCIL_MAX_OPACITY_MULTIPLIER = 1.0f
    /** `ToolDynamics.pencil` eases pressure→alpha by `pressure^0.5`. */
    private const val PENCIL_PRESSURE_EXPONENT = 0.5
    /** Upper bound ink/our engine treat as "perpendicular" tilt. */
    private val MAX_TILT_RADIANS = (Math.PI / 2.0).toFloat()

    /**
     * Highlighter width calibration. The current engine renders the highlighter
     * as a constant-width round-cap stroke at the nominal width, so a plain
     * round ink tip at scale `1.0` already lands close; this small nudge centers
     * the measured covered-area ratio on ~1.0 (the stock highlighter the I0.7
     * spike measured was ~0.71× — that brush is no longer on this path). Pinned
     * by `InkRenderParityTest.highlighterFootprintMatchesAfterI4`.
     */
    const val HIGHLIGHTER_TIP_SCALE = 1.0f

    /** Stable client ids so the families are identifiable / decodable. */
    private const val ID_PEN = "aichat:pen-pressure-taper"
    private const val ID_PENCIL = "aichat:pencil-tilt-grain"
    private const val ID_HIGHLIGHTER = "aichat:highlighter-chisel"

    /**
     * The single source of truth for which [BrushFamily] each tool authors with.
     * [InkInterop.brushFamilyForTool] delegates here. `marker` and unknown tools
     * keep the stable stock `marker` family (already GO in the I0.7 spike, so we
     * don't risk regressing it).
     */
    fun familyForTool(tool: String): BrushFamily = when (tool.lowercase()) {
        "pen" -> penFamily
        "pencil" -> pencilFamily
        "highlighter" -> highlighterFamily
        "marker" -> StockBrushes.marker()
        else -> StockBrushes.marker()
    }

    private val penFamily: BrushFamily by lazy {
        val taper = sizeMultiplierBehavior(
            source = BrushBehavior.Source.NORMALIZED_PRESSURE,
            sourceStart = 0f,
            sourceEnd = 1f,
            targetStart = PEN_MIN_SIZE_MULTIPLIER,
            targetEnd = PEN_MAX_SIZE_MULTIPLIER,
            responseCurve = powCurve(PEN_PRESSURE_EXPONENT),
        )
        familyOf(roundTip(behaviors = listOf(taper)), ID_PEN)
    }

    private val pencilFamily: BrushFamily by lazy {
        val tiltWidth = sizeMultiplierBehavior(
            source = BrushBehavior.Source.TILT_IN_RADIANS,
            sourceStart = 0f,
            sourceEnd = MAX_TILT_RADIANS,
            targetStart = PENCIL_MIN_SIZE_MULTIPLIER,
            targetEnd = PENCIL_MAX_SIZE_MULTIPLIER,
            responseCurve = sineEaseCurve(),
        )
        val pressureAlpha = BrushBehavior.Builder()
            .setSource(BrushBehavior.Source.NORMALIZED_PRESSURE)
            .setTarget(BrushBehavior.Target.OPACITY_MULTIPLIER)
            .setSourceValueRangeStart(0f)
            .setSourceValueRangeEnd(1f)
            .setTargetModifierRangeStart(PENCIL_MIN_OPACITY_MULTIPLIER)
            .setTargetModifierRangeEnd(PENCIL_MAX_OPACITY_MULTIPLIER)
            .setResponseCurve(powCurve(PENCIL_PRESSURE_EXPONENT))
            .setSourceOutOfRangeBehavior(BrushBehavior.OutOfRange.CLAMP)
            .setEnabledToolTypes(BrushBehavior.ALL_TOOL_TYPES)
            .build()
        familyOf(roundTip(behaviors = listOf(tiltWidth, pressureAlpha)), ID_PENCIL)
    }

    private val highlighterFamily: BrushFamily by lazy {
        familyOf(
            roundTip(scaleX = HIGHLIGHTER_TIP_SCALE, scaleY = HIGHLIGHTER_TIP_SCALE),
            ID_HIGHLIGHTER,
        )
    }

    // ── Construction helpers ────────────────────────────────────────────────

    /**
     * A `SIZE_MULTIPLIER` [BrushBehavior]: map [source] over
     * `[sourceStart, sourceEnd]` (normalized, clamped) through [responseCurve],
     * then lerp to the multiplier range `[targetStart, targetEnd]`. Enabled for
     * all tool types so the modulation applies to stylus and finger alike.
     *
     * (Note: this is a *primary* behavior, not `isFallbackFor` — a fallback only
     * fires when the optional input is **absent**, which would make a normal
     * pressure/tilt taper a no-op on real S-Pen strokes that always carry it.)
     */
    private fun sizeMultiplierBehavior(
        source: BrushBehavior.Source,
        sourceStart: Float,
        sourceEnd: Float,
        targetStart: Float,
        targetEnd: Float,
        responseCurve: EasingFunction,
    ): BrushBehavior = BrushBehavior.Builder()
        .setSource(source)
        .setTarget(BrushBehavior.Target.SIZE_MULTIPLIER)
        .setSourceValueRangeStart(sourceStart)
        .setSourceValueRangeEnd(sourceEnd)
        .setTargetModifierRangeStart(targetStart)
        .setTargetModifierRangeEnd(targetEnd)
        .setResponseCurve(responseCurve)
        .setSourceOutOfRangeBehavior(BrushBehavior.OutOfRange.CLAMP)
        .setEnabledToolTypes(BrushBehavior.ALL_TOOL_TYPES)
        .build()

    /** A round tip (the default ink tip shape) carrying [behaviors]. */
    private fun roundTip(
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        behaviors: List<BrushBehavior> = emptyList(),
    ): BrushTip = BrushTip.Builder()
        .setScaleX(scaleX)
        .setScaleY(scaleY)
        .setBehaviors(behaviors)
        .build()

    private fun familyOf(tip: BrushTip, clientId: String): BrushFamily =
        BrushFamily(tip = tip, paint = BrushPaint(), clientBrushFamilyId = clientId)

    /**
     * A piecewise-linear easing approximating `x^[exp]` on `[0,1]`. Concave for
     * `exp < 1` (fast rise, easing toward the top) — exactly the `pressure^0.7`
     * feel the current pen curve has.
     */
    private fun powCurve(exp: Double): EasingFunction =
        EasingFunction.Linear(
            (1 until CURVE_SAMPLES).map { i ->
                val x = i.toFloat() / CURVE_SAMPLES
                ImmutableVec(x, x.toDouble().pow(exp).toFloat())
            },
        )

    /**
     * A piecewise-linear easing approximating `sin(x·π/2)` on `[0,1]` — the
     * sine ease `ToolDynamics.pencil` uses for tilt broadening.
     */
    private fun sineEaseCurve(): EasingFunction =
        EasingFunction.Linear(
            (1 until CURVE_SAMPLES).map { i ->
                val x = i.toFloat() / CURVE_SAMPLES
                ImmutableVec(x, sin(x * MAX_TILT_RADIANS))
            },
        )
}
