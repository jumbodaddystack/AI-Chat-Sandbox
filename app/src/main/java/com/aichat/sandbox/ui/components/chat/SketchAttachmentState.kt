package com.aichat.sandbox.ui.components.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.Tool
import com.aichat.sandbox.ui.components.notes.ToolPaletteState
import com.aichat.sandbox.ui.screens.notes.EditorAction

/**
 * State holder for the chat composer's sketch attachment sheet
 * (sub-phase 3.4).
 *
 * Owns the minimum slice of editor state needed by a fixed-size,
 * no-pan/zoom drawing surface: a [SnapshotStateList] of stroke items, a
 * pen / eraser palette, and an undo stack of [EditorAction]s. Items are
 * stamped with a monotonically increasing `zIndex` at insertion so the
 * eraser sees deterministic ordering.
 *
 * Lives in the [ChatScreen] composition — created with `remember`,
 * cleared on [close] so reopening the sheet starts blank.
 */
class SketchAttachmentState {

    val items: SnapshotStateList<NoteItem> = mutableStateListOf()

    val palette: ToolPaletteState = ToolPaletteState()

    var isOpen: Boolean by mutableStateOf(false)
        private set

    private val undoStack: MutableList<EditorAction> = mutableListOf()
    private var nextZIndex: Int = 0

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    /** Confirm is enabled only when there is something to rasterize. */
    val canConfirm: Boolean get() = items.isNotEmpty()

    fun open() {
        isOpen = true
    }

    /** Dismisses the sheet and discards the canvas state. */
    fun close() {
        isOpen = false
        items.clear()
        undoStack.clear()
        nextZIndex = 0
        palette.select(Tool.PEN)
    }

    /** Append a freshly committed stroke (DrawingSurface emits items with no z-index). */
    fun addItem(item: NoteItem) {
        val prepared = item.copy(zIndex = nextZIndex++)
        val action = EditorAction.AddItems(listOf(prepared))
        action.applyTo(items)
        undoStack.add(action)
    }

    /** Remove items the eraser swipe matched, with undo support. */
    fun removeItems(ids: List<String>) {
        if (ids.isEmpty()) return
        val set = ids.toHashSet()
        val matched = items.filter { it.id in set }
        if (matched.isEmpty()) return
        val action = EditorAction.RemoveItems(matched)
        action.applyTo(items)
        undoStack.add(action)
    }

    fun undo() {
        val last = undoStack.removeLastOrNull() ?: return
        last.invert().applyTo(items)
    }

    /** Clear the canvas — recorded as a bulk remove so undo restores everything. */
    fun clearCanvas() {
        if (items.isEmpty()) return
        val action = EditorAction.RemoveItems(items.toList())
        action.applyTo(items)
        undoStack.add(action)
    }
}
