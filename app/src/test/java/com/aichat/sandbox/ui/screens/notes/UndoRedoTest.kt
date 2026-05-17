package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.StrokeTransform
import com.aichat.sandbox.ui.components.notes.TextItemCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the undo / redo machinery for sub-phase 1.7.
 *
 * The [EditorAction] sealed interface owns the apply / invert logic — the
 * ViewModel is a thin scheduler on top of two `ArrayDeque`s, so testing the
 * actions against a plain `MutableList` covers the user-visible behaviour
 * without dragging Hilt or Compose state into a JVM test.
 */
class UndoRedoTest {

    @Test
    fun addItemsActionAppendsAndInvertsToRemoval() {
        val items = mutableListOf<NoteItem>()
        val a = stroke("a")
        val b = stroke("b")

        val add = EditorAction.AddItems(listOf(a, b))
        add.applyTo(items)
        assertEquals(listOf("a", "b"), items.map { it.id })

        add.invert().applyTo(items)
        assertEquals(emptyList<String>(), items.map { it.id })
    }

    @Test
    fun removeItemsActionStripsMatchingIdsAndInvertsToReAdd() {
        val a = stroke("a")
        val b = stroke("b")
        val c = stroke("c")
        val items = mutableListOf(a, b, c)

        val remove = EditorAction.RemoveItems(listOf(a, c))
        remove.applyTo(items)
        assertEquals(listOf("b"), items.map { it.id })

        remove.invert().applyTo(items)
        // Re-added items are appended; ordering is rebuilt from z-index at
        // render time, so set-equality is what we care about here.
        assertEquals(setOf("a", "b", "c"), items.mapTo(HashSet()) { it.id })
    }

    @Test
    fun removeItemsRestoresByteIdenticalPayload() {
        val original = NoteItem(
            id = "x",
            noteId = "note",
            zIndex = 5,
            kind = "stroke",
            tool = "pen",
            colorArgb = 0xFF112233.toInt(),
            baseWidthPx = 4.5f,
            payload = byteArrayOf(1, 2, 3, 4),
        )
        val items = mutableListOf<NoteItem>(original)

        val remove = EditorAction.RemoveItems(listOf(original))
        remove.applyTo(items)
        remove.invert().applyTo(items)

        assertEquals(1, items.size)
        val restored = items[0]
        // Action holds the original reference, so inversion brings back the
        // same instance — no codec round-trip, no precision loss.
        assertSame(original, restored)
        assertEquals(original, restored)
    }

    @Test
    fun threeAddsThenThreeUndosClearsCanvas() {
        val stack = FakeEditorStack()
        stack.apply(EditorAction.AddItems(listOf(stroke("a"))))
        stack.apply(EditorAction.AddItems(listOf(stroke("b"))))
        stack.apply(EditorAction.AddItems(listOf(stroke("c"))))
        assertEquals(listOf("a", "b", "c"), stack.ids())
        assertTrue(stack.canUndo)
        assertFalse(stack.canRedo)

        stack.undo(); stack.undo(); stack.undo()
        assertEquals(emptyList<String>(), stack.ids())
        assertFalse(stack.canUndo)
        assertTrue(stack.canRedo)
    }

    @Test
    fun redoReapplyiesActionsInOrder() {
        val stack = FakeEditorStack()
        stack.apply(EditorAction.AddItems(listOf(stroke("a"))))
        stack.apply(EditorAction.AddItems(listOf(stroke("b"))))
        stack.apply(EditorAction.AddItems(listOf(stroke("c"))))
        repeat(3) { stack.undo() }

        stack.redo(); stack.redo(); stack.redo()
        assertEquals(listOf("a", "b", "c"), stack.ids())
        assertFalse(stack.canRedo)
    }

    @Test
    fun eraseThenUndoRestoresStroke() {
        val stack = FakeEditorStack()
        val pen = stroke("a")
        stack.apply(EditorAction.AddItems(listOf(pen)))
        stack.apply(EditorAction.RemoveItems(listOf(pen)))
        assertEquals(emptyList<String>(), stack.ids())

        stack.undo()
        assertEquals(listOf("a"), stack.ids())
        assertSame(pen, stack.items[0])
    }

    @Test
    fun newActionAfterUndoClearsRedoBranch() {
        val stack = FakeEditorStack()
        stack.apply(EditorAction.AddItems(listOf(stroke("a"))))
        stack.apply(EditorAction.AddItems(listOf(stroke("b"))))
        stack.undo()
        assertTrue(stack.canRedo)

        stack.apply(EditorAction.AddItems(listOf(stroke("c"))))
        assertFalse(stack.canRedo)
        assertEquals(listOf("a", "c"), stack.ids())
    }

    @Test
    fun undoRedoOnEmptyStacksIsNoOp() {
        val stack = FakeEditorStack()
        stack.undo()
        stack.redo()
        assertEquals(emptyList<String>(), stack.ids())
        assertFalse(stack.canUndo)
        assertFalse(stack.canRedo)
    }

    @Test
    fun stackHonoursCapByDroppingOldestAction() {
        val stack = FakeEditorStack(cap = 3)
        stack.apply(EditorAction.AddItems(listOf(stroke("a"))))
        stack.apply(EditorAction.AddItems(listOf(stroke("b"))))
        stack.apply(EditorAction.AddItems(listOf(stroke("c"))))
        stack.apply(EditorAction.AddItems(listOf(stroke("d"))))

        // 4 applies, cap = 3 → oldest ("a") evicted but its effect on items remains.
        assertEquals(listOf("a", "b", "c", "d"), stack.ids())

        // Three undos pop the three retained actions; the evicted "a" cannot be undone.
        stack.undo(); stack.undo(); stack.undo()
        assertEquals(listOf("a"), stack.ids())
        assertFalse(stack.canUndo)
    }

    @Test
    fun updateTextActionRewritesBodyAndIsReversible() {
        val original = TextItemCodec.encode(
            TextItemCodec.newAt(worldX = 10f, worldY = 20f, body = "hello"),
        )
        val item = NoteItem(
            id = "t",
            noteId = "note",
            zIndex = 0,
            kind = TextItemCodec.KIND,
            tool = null,
            colorArgb = 0xFF000000.toInt(),
            baseWidthPx = 0f,
            payload = original,
        )
        val items = mutableListOf(item)

        EditorAction.UpdateText("t", "hello", "hello world").applyTo(items)
        val afterApply = TextItemCodec.decode(items[0].payload)
        assertEquals("hello world", afterApply.body)
        // Matrix is preserved — only the body changes.
        assertEquals(10f, afterApply.matrix[2], 0f)
        assertEquals(20f, afterApply.matrix[5], 0f)

        EditorAction.UpdateText("t", "hello", "hello world").invert().applyTo(items)
        val afterInvert = TextItemCodec.decode(items[0].payload)
        assertEquals("hello", afterInvert.body)
    }

    @Test
    fun updateTextSkipsItemsWithStaleId() {
        val item = NoteItem(
            id = "stroke",
            noteId = "note",
            zIndex = 0,
            kind = "stroke",
            tool = "pen",
            colorArgb = 0,
            baseWidthPx = 4f,
            payload = ByteArray(0),
        )
        val items = mutableListOf(item)
        // Action targets a text item id that doesn't exist; must be a safe
        // no-op (the redo branch could legitimately point at pruned ids).
        EditorAction.UpdateText("ghost", "a", "b").applyTo(items)
        assertEquals(1, items.size)
        assertSame(item, items[0])
    }

    @Test
    fun transformItemsAppliesAffineToTextMatrix() {
        val source = TextItemCodec.newAt(0f, 0f, "x")
        val item = NoteItem(
            id = "t",
            noteId = "note",
            zIndex = 0,
            kind = TextItemCodec.KIND,
            tool = null,
            colorArgb = 0,
            baseWidthPx = 0f,
            payload = TextItemCodec.encode(source),
        )
        val items = mutableListOf(item)

        val translate = StrokeTransform.translation(7f, -3f)
        EditorAction.TransformItems(listOf("t"), translate).applyTo(items)
        val moved = TextItemCodec.decode(items[0].payload)
        assertEquals(7f, moved.matrix[2], 0f)
        assertEquals(-3f, moved.matrix[5], 0f)

        // Invert by the same matrix returns to origin.
        EditorAction.TransformItems(listOf("t"), translate).invert().applyTo(items)
        val back = TextItemCodec.decode(items[0].payload)
        assertEquals(0f, back.matrix[2], 1e-4f)
        assertEquals(0f, back.matrix[5], 1e-4f)
    }

    private fun stroke(id: String): NoteItem = NoteItem(
        id = id,
        noteId = "note",
        zIndex = 0,
        kind = "stroke",
        tool = "pen",
        colorArgb = 0xFF000000.toInt(),
        baseWidthPx = 4f,
        payload = ByteArray(0),
    )

    /**
     * Mirrors [NoteEditorViewModel]'s undo / redo bookkeeping without the
     * Hilt / Compose / coroutine wiring so the deque + cap logic is unit
     * testable on the JVM.
     */
    private class FakeEditorStack(private val cap: Int = 200) {
        val items: MutableList<NoteItem> = mutableListOf()
        private val past = ArrayDeque<EditorAction>()
        private val future = ArrayDeque<EditorAction>()

        val canUndo: Boolean get() = past.isNotEmpty()
        val canRedo: Boolean get() = future.isNotEmpty()

        fun ids(): List<String> = items.map { it.id }

        fun apply(action: EditorAction) {
            action.applyTo(items)
            past.addLast(action)
            while (past.size > cap) past.removeFirst()
            future.clear()
        }

        fun undo() {
            val action = past.removeLastOrNull() ?: return
            action.invert().applyTo(items)
            future.addLast(action)
        }

        fun redo() {
            val action = future.removeLastOrNull() ?: return
            action.applyTo(items)
            past.addLast(action)
        }
    }
}
