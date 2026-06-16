package com.aichat.sandbox.data.notes

/**
 * Phase 4 (N1) — name disambiguation for AI-designed brushes.
 *
 * `BrushPreset` rows key on a UUID, so two brushes can technically share a name —
 * but a library with three brushes all called "AI Brush" is unusable. When the
 * designer saves a spec whose name already exists, we append a numeric suffix
 * (`Inky Pen`, `Inky Pen (2)`, `Inky Pen (3)`, …) so every saved preset is
 * distinguishable in the palette. Pure (no Android, no Room) so it is unit
 * testable and reused by the view model's save path.
 */
object BrushPresetNaming {

    /**
     * Return [desired] if it is not already present in [existing], otherwise the
     * first `"<base> (n)"` (n ≥ 2) that is free. Blank input falls back to
     * [BrushSpec.DEFAULT_NAME]. Matching is exact (trimmed), case-sensitive —
     * the goal is to avoid identical labels, not near-duplicates.
     */
    fun uniqueName(desired: String, existing: Collection<String>): String {
        val base = desired.trim().ifEmpty { BrushSpec.DEFAULT_NAME }
        val taken = existing.toHashSet()
        if (base !in taken) return base
        var n = 2
        while ("$base ($n)" in taken) n++
        return "$base ($n)"
    }
}
