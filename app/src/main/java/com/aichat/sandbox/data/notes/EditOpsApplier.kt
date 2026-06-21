package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import com.aichat.sandbox.ui.screens.notes.EditPreviewController

/**
 * Phase 19 (Stage 0) — service-layer seam over [EditPreviewController.simulate]
 * for the render-in-the-loop self-refine in [NoteAiService].
 *
 * The self-refine loop needs to apply an [EditOpsDoc] to an in-memory item
 * list and rasterize the result so the model can see its own output. The
 * actual op-application logic already lives in [EditPreviewController.simulate]
 * — a pure (no-graphics, no-repo) simulator. Rather than duplicate it or move
 * 600 lines (and ripple every existing call site / test), this object simply
 * delegates to it and adds [materialize], which folds the
 * (added / removed / modified) [EditPreviewController.Simulation] back into a
 * single flat post-edit `List<NoteItem>` that the rasterizer can render.
 *
 * Note on layering: `data.notes` already reaches into `ui.components.notes`
 * (the canonical codecs, via [VectorCanvasJson]); this is a single Gradle
 * module, so the additional hop to the equally-pure `ui.screens.notes`
 * simulator is a deliberate, contained seam rather than a build-level cycle.
 */
object EditOpsApplier {

    /** Apply [doc] against [baselineItems] and return the flat post-edit list. */
    fun apply(
        baselineItems: List<NoteItem>,
        doc: EditOpsDoc,
        idMap: Map<String, String>,
        layerMap: Map<String, String>,
        layers: List<NoteLayer>,
        newItemNoteId: String? = null,
        authoredOffset: FloatArray? = null,
        authoredFit: FloatArray? = null,
    ): List<NoteItem> = materialize(
        baselineItems,
        EditPreviewController.simulate(
            currentItems = baselineItems,
            doc = doc,
            idMap = idMap,
            layerMap = layerMap,
            layers = layers,
            newItemNoteId = newItemNoteId,
            authoredOffset = authoredOffset,
            authoredFit = authoredFit,
        ),
    )

    /**
     * Fold a [EditPreviewController.Simulation] back onto [base]:
     * `base − removed + modified(after, by id) + added`. The result is the
     * exact item list the canvas would show after accepting the edit, suitable
     * for rasterizing a candidate preview.
     */
    fun materialize(
        base: List<NoteItem>,
        sim: EditPreviewController.Simulation,
    ): List<NoteItem> {
        if (sim.isEmpty) return base
        val removedIds = sim.removed.mapTo(HashSet(sim.removed.size)) { it.id }
        val modifiedById = HashMap<String, NoteItem>(sim.modified.size)
        for ((before, after) in sim.modified) modifiedById[before.id] = after
        val out = ArrayList<NoteItem>(base.size + sim.added.size)
        for (item in base) {
            if (item.id in removedIds) continue
            out += modifiedById[item.id] ?: item
        }
        out += sim.added
        return out
    }
}
