package com.aichat.sandbox.ui.screens.notes

/** Legend labels shown by the staged edit banner and covered by JVM tests. */
internal fun aiEditLegendLabels(sim: EditPreviewController.Simulation): List<String> = listOf(
    "${sim.added.size} added",
    "${sim.removed.size} removed",
    "${sim.modified.size} modified",
)

/** All non-applied reasons that should be visible in the staged edit banner. */
internal fun aiEditInvalidReasons(pending: PendingEdit): List<String> =
    pending.simulation.skipped + pending.doc.rejected.map { it.reason }

/** Pair of applied edit count to emitted edit count for the partial-failure message. */
internal fun aiEditAppliedOfEmitted(pending: PendingEdit): Pair<Int, Int> {
    val emitted = pending.doc.ops.size + pending.doc.rejected.size
    val applied = (emitted - aiEditInvalidReasons(pending).size).coerceAtLeast(0)
    return applied to emitted
}
