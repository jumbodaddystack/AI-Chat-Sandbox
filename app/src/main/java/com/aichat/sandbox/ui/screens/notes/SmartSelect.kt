package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.ink.SelectSimilar
import com.aichat.sandbox.data.ink.StrokeSimilarity
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer

/**
 * Phase 5 — smart-select policy (N2 / idea #8).
 *
 * Pure, ViewModel-free glue that turns a tapped stroke into a "select similar"
 * result. It mirrors exactly what [NoteEditorViewModel.selectSimilarTo] does on
 * the ink-on path:
 *
 *  1. build [SelectSimilar.Candidate]s from every stroke on an *unlocked* layer,
 *  2. rank/keep the ones at or above the similarity threshold, then
 *  3. expand the matches to whole groups with [expandSelectionToGroups], reusing
 *     the same locked-layer gate the lasso uses.
 *
 * Extracted so the locked-layer filtering + group expansion policy is unit
 * testable without standing up the ViewModel (Hilt / Room / Android deps).
 */
object SmartSelect {

    private const val STROKE_KIND = NoteItem.KIND_STROKE

    /** True when a layer exists and is locked (null layer = default, unlocked). */
    private fun isLocked(layerId: String?, layers: List<NoteLayer>): Boolean {
        val layer = layers.firstOrNull { it.id == layerId }
        return layer != null && layer.locked
    }

    /**
     * Ids that are geometrically + stylistically similar to [targetId], ready to
     * become the active selection (the target is always included). Returns just
     * the target when it isn't a stroke, has no features, or nothing else clears
     * [threshold]. Items on locked layers never join — not as direct matches and
     * not by group expansion.
     */
    fun selectSimilarIds(
        targetId: String,
        items: List<NoteItem>,
        layers: List<NoteLayer>,
        threshold: Float = SelectSimilar.DEFAULT_THRESHOLD,
    ): Set<String> {
        val target = items.firstOrNull { it.id == targetId } ?: return emptySet()
        if (target.kind != STROKE_KIND) return setOf(targetId)

        val candidates = ArrayList<SelectSimilar.Candidate>()
        for (item in items) {
            if (item.kind != STROKE_KIND) continue
            if (isLocked(item.layerId, layers)) continue
            val features = StrokeSimilarity.featuresOf(
                item.payload, item.tool ?: "", item.colorArgb, item.baseWidthPx,
            ) ?: continue
            candidates.add(SelectSimilar.Candidate(item.id, features))
        }
        val matched = SelectSimilar.selectSimilar(targetId, candidates, threshold).toHashSet()
        return expandSelectionToGroups(matched, items) { item ->
            !isLocked(item.layerId, layers)
        }
    }
}
