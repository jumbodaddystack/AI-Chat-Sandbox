package com.aichat.sandbox.ui.screens.vector.edit

/**
 * Phase 5 (sub-feature 4) — keyboard ergonomics for the node editor.
 *
 * A pure mapping from a synthetic key descriptor + modifier flags to a
 * [VectorEditAction] (or null when the chord is unbound). Keeping the input a
 * plain [KeySpec] enum — rather than a Compose `Key` — makes the whole binding
 * table JVM-testable without a device; the Compose layer is a thin adapter that
 * translates `androidx.compose.ui.input.key.Key` → [KeySpec] and forwards the
 * resolved action to the view-model.
 *
 * All geometry produced here flows through the **existing** reducer cases
 * ([VectorEditAction.MoveSelection] for nudges, [VectorEditAction.Undo] /
 * [VectorEditAction.Redo], etc.), so there is no new edit math to maintain.
 */
object EditKeyBindings {

    /** The subset of physical keys the editor binds. The Compose layer maps to these. */
    enum class KeySpec {
        ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
        DELETE, Z, P, V, ESCAPE,
    }

    /** Base nudge in viewport units for an un-modified arrow press. */
    const val NUDGE_UNIT: Float = 1f

    /** Shift multiplies the nudge for coarse movement. */
    const val SHIFT_NUDGE_FACTOR: Float = 10f

    /**
     * Resolve a key chord into an edit action, or null when nothing is bound.
     *
     * - Arrows → [VectorEditAction.MoveSelection] by [gridStep] (×[SHIFT_NUDGE_FACTOR] with shift).
     * - Ctrl+Z → [VectorEditAction.Undo]; Ctrl+Shift+Z → [VectorEditAction.Redo].
     * - Delete/Backspace → [VectorEditAction.DeleteSelected].
     * - P → pen tool; V → direct-select tool.
     * - Escape → [VectorEditAction.ClearSelection].
     *
     * Arrow nudges ignore the ctrl modifier (so a stray ctrl+arrow still nudges);
     * the tool/escape bindings only fire without ctrl so they never shadow OS
     * shortcuts.
     */
    fun resolve(
        key: KeySpec,
        shift: Boolean,
        ctrl: Boolean,
        gridStep: Float = NUDGE_UNIT,
    ): VectorEditAction? {
        val step = gridStep * if (shift) SHIFT_NUDGE_FACTOR else 1f
        return when (key) {
            KeySpec.ARROW_UP -> VectorEditAction.MoveSelection(0f, -step)
            KeySpec.ARROW_DOWN -> VectorEditAction.MoveSelection(0f, step)
            KeySpec.ARROW_LEFT -> VectorEditAction.MoveSelection(-step, 0f)
            KeySpec.ARROW_RIGHT -> VectorEditAction.MoveSelection(step, 0f)
            KeySpec.DELETE -> VectorEditAction.DeleteSelected
            KeySpec.Z -> when {
                ctrl && shift -> VectorEditAction.Redo
                ctrl -> VectorEditAction.Undo
                else -> null
            }
            KeySpec.P -> if (!ctrl) VectorEditAction.SetTool(EditTool.PEN) else null
            KeySpec.V -> if (!ctrl) VectorEditAction.SetTool(EditTool.DIRECT_SELECT) else null
            KeySpec.ESCAPE -> if (!ctrl) VectorEditAction.ClearSelection else null
        }
    }
}
