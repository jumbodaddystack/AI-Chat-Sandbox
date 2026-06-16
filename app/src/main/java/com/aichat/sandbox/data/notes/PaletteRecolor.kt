package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteItem

/**
 * Phase 2 — turn a [PaletteSuggestion] into previewable `recolor` edit-ops.
 *
 * Pure and deterministic so it can be unit-tested without the view model. Two
 * inputs decide each item's new colour, in priority order:
 *  1. An explicit per-item plan ([explicit], keyed by the item's live UUID,
 *     translated from a model's short-id [PaletteSuggestion.assignments]).
 *  2. Otherwise [ColorHarmony.assignments] distributes the [swatches] across the
 *     distinct colours already in scope, preserving relative light/dark order.
 *
 * Items whose colour wouldn't actually change are dropped (no-op recolours add
 * noise to the diff), and the resulting [EditOp.Recolor] ops are grouped by
 * target colour so the staged preview reads as a handful of clean buckets. The
 * ops reference item UUIDs directly; the view model stages them with an
 * identity id-map through the same simulator as a local snap/tidy edit, so
 * locked layers and unchanged items are respected by the existing backstop.
 */
object PaletteRecolor {

    /**
     * Build grouped recolor ops for [scope]. [explicit] (uuid → ARGB) wins
     * per item; everything else falls back to the swatch distribution. Returns
     * an empty list when nothing would visibly change.
     */
    fun buildOps(
        scope: List<NoteItem>,
        suggestion: PaletteSuggestion,
        explicit: Map<String, Int> = emptyMap(),
    ): List<EditOp.Recolor> {
        if (scope.isEmpty() || suggestion.swatches.isEmpty()) return emptyList()
        val distribution = ColorHarmony.assignments(scope.map { it.colorArgb }, suggestion.swatches)
        // Preserve scope order for the grouping so previews are stable.
        val grouped = LinkedHashMap<Int, MutableList<String>>()
        for (item in scope) {
            val current = ColorHarmony.opaque(item.colorArgb)
            val target = explicit[item.id]?.let { ColorHarmony.opaque(it) }
                ?: distribution[current]
                ?: continue
            if (target == current) continue
            grouped.getOrPut(target) { ArrayList() }.add(item.id)
        }
        return grouped.map { (color, ids) -> EditOp.Recolor(ids, color) }
    }

    /**
     * Translate a model's short-id → ARGB [assignments] into a live-UUID map
     * using the serialized canvas's [idMap] (short → UUID). Short ids the map
     * doesn't know are dropped.
     */
    fun resolveAssignments(
        assignments: Map<String, Int>,
        idMap: Map<String, String>,
    ): Map<String, Int> {
        if (assignments.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Int>(assignments.size)
        for ((shortId, color) in assignments) {
            val uuid = idMap[shortId] ?: continue
            out[uuid] = color
        }
        return out
    }
}
