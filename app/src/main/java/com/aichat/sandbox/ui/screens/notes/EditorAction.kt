package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.model.NoteItem

/**
 * Reversible canvas mutations recorded in the editor's undo / redo stack
 * (sub-phase 1.7).
 *
 * Each variant carries the full data needed to invert itself — for example
 * [RemoveItems] holds the complete [NoteItem]s rather than their ids so an
 * undo can re-insert them byte-identical, preserving stroke payload, color,
 * width, and z-index. Later sub-phases append `TransformItems` (1.8) and
 * `UpdateText` (1.9) without changing this interface.
 *
 * Actions operate on a plain [MutableList] so the logic is testable on the
 * JVM; the editor's `SnapshotStateList<NoteItem>` satisfies that contract.
 */
sealed interface EditorAction {

    fun applyTo(items: MutableList<NoteItem>)

    fun invert(): EditorAction

    data class AddItems(val items: List<NoteItem>) : EditorAction {
        override fun applyTo(items: MutableList<NoteItem>) {
            items.addAll(this.items)
        }

        override fun invert(): EditorAction = RemoveItems(items)
    }

    data class RemoveItems(val items: List<NoteItem>) : EditorAction {
        override fun applyTo(items: MutableList<NoteItem>) {
            if (this.items.isEmpty()) return
            val ids = this.items.mapTo(HashSet(this.items.size)) { it.id }
            items.removeAll { it.id in ids }
        }

        override fun invert(): EditorAction = AddItems(items)
    }
}
