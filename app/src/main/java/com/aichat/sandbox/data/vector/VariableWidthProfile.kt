package com.aichat.sandbox.data.vector

/**
 * Phase 5 (sub-feature 1) — a width-along-the-path profile.
 *
 * A [VariableWidthProfile] attaches to a [VectorStyle] (nullable) and describes
 * how the stroke width changes from the start of the path (`t = 0`) to its end
 * (`t = 1`). No single stroke attribute in Android VectorDrawable or SVG can
 * represent this, so [VariableWidthOutliner] bakes the profile into a filled
 * outline before serialization.
 */
data class WidthStop(
    /** Position along the path, clamped to [0, 1]. */
    val t: Float,
    /** Absolute stroke width in viewport units at this position. */
    val width: Float,
)

data class VariableWidthProfile(val stops: List<WidthStop>) {

    /**
     * Linearly-interpolated width at position [t] (clamped to the endpoints).
     * Stops are read in their natural list order; an empty profile yields
     * [fallback]. Returns the single stop's width when there is only one.
     */
    fun widthAt(t: Float, fallback: Float): Float {
        if (stops.isEmpty()) return fallback
        if (stops.size == 1) return stops.first().width
        val sorted = stops.sortedBy { it.t }
        val clamped = t.coerceIn(0f, 1f)
        if (clamped <= sorted.first().t) return sorted.first().width
        if (clamped >= sorted.last().t) return sorted.last().width
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]
            val b = sorted[i]
            if (clamped <= b.t) {
                val span = b.t - a.t
                if (span <= 1e-6f) return b.width
                val f = (clamped - a.t) / span
                return a.width + (b.width - a.width) * f
            }
        }
        return sorted.last().width
    }
}
