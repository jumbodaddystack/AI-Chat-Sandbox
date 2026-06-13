package com.aichat.sandbox.data.vector.notesbridge

import com.aichat.sandbox.data.vector.PathCommand
import com.aichat.sandbox.data.vector.VectorDocument
import com.aichat.sandbox.data.vector.VectorGroup
import com.aichat.sandbox.data.vector.VectorNode
import com.aichat.sandbox.data.vector.VectorPath
import com.aichat.sandbox.data.vector.VectorStyle
import com.aichat.sandbox.data.vector.VectorViewport
import com.aichat.sandbox.ui.components.notes.PathCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 16.2 — document → note items. A 24-viewport maps onto the 768-world
 * artboard at exactly ×32 with zero offset, so expectations stay readable.
 */
class DocumentToNoteItemsTest {

    private val viewport = VectorViewport(24f, 24f, 24f, 24f)

    private fun doc(vararg nodes: VectorNode) = VectorDocument(
        viewport = viewport,
        root = VectorGroup(id = "root", children = nodes.toList()),
    )

    private fun path(
        commands: List<PathCommand>?,
        style: VectorStyle = VectorStyle(fillColor = "#FF0000"),
        id: String = "p0",
    ) = VectorNode.PathNode(VectorPath(id = id, pathData = "d", commands = commands, style = style))

    private fun square24() = listOf(
        PathCommand.MoveTo(0f, 0f),
        PathCommand.LineTo(24f, 0f),
        PathCommand.LineTo(24f, 24f),
        PathCommand.LineTo(0f, 24f),
        PathCommand.Close(),
    )

    private fun decodeSingle(result: DocumentToNoteItems.Result): PathCodec.PathPayload {
        assertEquals(1, result.items.size)
        return PathCodec.decode(result.items[0].payload)
    }

    @Test
    fun filledSquareScalesOntoTheArtboard() {
        val result = DocumentToNoteItems.convert(doc(path(square24())), noteId = "n")
        val payload = decodeSingle(result)
        assertEquals(1, payload.subpaths.size)
        assertTrue(payload.subpaths[0].closed)
        assertEquals(0xFFFF0000.toInt(), payload.fillArgb)
        assertEquals(PathCodec.FILL_RULE_NON_ZERO, payload.fillRule)
        val b = PathCodec.boundsOf(payload)!!
        assertEquals(0f, b[0], 1e-2f)
        assertEquals(768f, b[2], 1e-2f)
        assertEquals(768f, b[3], 1e-2f)
        // Fill-only: hairline same-colour stroke convention.
        assertEquals(0xFFFF0000.toInt(), result.items[0].colorArgb)
        assertEquals(0f, result.items[0].baseWidthPx, 1e-4f)
    }

    @Test
    fun donutWithEvenOddKeepsBothSubpathsAndRule() {
        val commands = listOf(
            PathCommand.MoveTo(0f, 0f),
            PathCommand.HorizontalTo(24f),
            PathCommand.VerticalTo(24f),
            PathCommand.HorizontalTo(0f),
            PathCommand.Close(),
            PathCommand.MoveTo(6f, 6f),
            PathCommand.HorizontalTo(18f),
            PathCommand.VerticalTo(18f),
            PathCommand.HorizontalTo(6f),
            PathCommand.Close(),
        )
        val result = DocumentToNoteItems.convert(
            doc(path(commands, VectorStyle(fillColor = "#FF0000", fillType = "evenOdd"))),
            noteId = "n",
        )
        val payload = decodeSingle(result)
        assertEquals(2, payload.subpaths.size)
        assertTrue(payload.subpaths.all { it.closed })
        assertEquals(PathCodec.FILL_RULE_EVEN_ODD, payload.fillRule)
    }

    @Test
    fun groupTranslateAndScaleCompose() {
        val node = VectorNode.GroupNode(
            VectorGroup(
                id = "g",
                translateX = 6f,
                translateY = 6f,
                scaleX = 0.5f,
                scaleY = 0.5f,
                children = listOf(path(square24())),
            ),
        )
        val payload = decodeSingle(DocumentToNoteItems.convert(doc(node), noteId = "n"))
        // (0..24) × 0.5 + 6 → 6..18 in the viewport → 192..576 in world.
        val b = PathCodec.boundsOf(payload)!!
        assertEquals(192f, b[0], 1e-2f)
        assertEquals(576f, b[2], 1e-2f)
    }

    @Test
    fun relativeCommandsResolveAgainstTheCurrentPoint() {
        val commands = listOf(
            PathCommand.MoveTo(2f, 2f),
            PathCommand.LineTo(10f, 0f, relative = true),
            PathCommand.LineTo(0f, 10f, relative = true),
            PathCommand.Close(),
        )
        val payload = decodeSingle(DocumentToNoteItems.convert(doc(path(commands)), noteId = "n"))
        val b = PathCodec.boundsOf(payload)!!
        assertEquals(2f * 32f, b[0], 1e-2f)
        assertEquals(12f * 32f, b[2], 1e-2f)
        assertEquals(12f * 32f, b[3], 1e-2f)
    }

    @Test
    fun quadLiftsToExactCubic() {
        val commands = listOf(
            PathCommand.MoveTo(0f, 0f),
            PathCommand.QuadTo(12f, 24f, 24f, 0f),
        )
        val payload = decodeSingle(DocumentToNoteItems.convert(doc(path(commands)), noteId = "n"))
        val start = payload.subpaths[0].anchors[0]
        // c1 = start + 2/3·(q − start) = (8, 16) in viewport → ×32 world.
        assertEquals(8f * 32f, start.x + start.outDx, 1e-1f)
        assertEquals(16f * 32f, start.y + start.outDy, 1e-1f)
    }

    @Test
    fun arcCircleCoversTheViewport() {
        // Full circle as two semicircle arcs: M0,12 A12,12 … 24,12 A … 0,12 Z.
        val commands = listOf(
            PathCommand.MoveTo(0f, 12f),
            PathCommand.ArcTo(12f, 12f, 0f, largeArc = true, sweep = true, x = 24f, y = 12f),
            PathCommand.ArcTo(12f, 12f, 0f, largeArc = true, sweep = true, x = 0f, y = 12f),
            PathCommand.Close(),
        )
        val payload = decodeSingle(DocumentToNoteItems.convert(doc(path(commands)), noteId = "n"))
        val b = PathCodec.boundsOf(payload)!!
        // The circle spans the full viewport in both axes (±0.5% arc error).
        assertEquals(0f, b[0], 4f)
        assertEquals(0f, b[1], 4f)
        assertEquals(768f, b[2], 4f)
        assertEquals(768f, b[3], 4f)
    }

    @Test
    fun strokeStyleLandsOnTheItem() {
        val style = VectorStyle(
            strokeColor = "#112233",
            strokeWidth = 2f,
            strokeLineCap = "butt",
            strokeLineJoin = "bevel",
        )
        val result = DocumentToNoteItems.convert(doc(path(square24(), style)), noteId = "n")
        val item = result.items[0]
        assertEquals(0xFF112233.toInt(), item.colorArgb)
        assertEquals(64f, item.baseWidthPx, 1e-3f)
        val payload = PathCodec.decode(item.payload)
        assertEquals(0, payload.fillArgb)
        assertEquals(PathCodec.CAP_BUTT, PathCodec.cap(payload.capJoin))
        assertEquals(PathCodec.JOIN_BEVEL, PathCodec.join(payload.capJoin))
    }

    @Test
    fun unparsedPathIsSkippedWithWarning() {
        val result = DocumentToNoteItems.convert(
            doc(path(commands = null), path(square24(), id = "p1")),
            noteId = "n",
        )
        assertEquals(1, result.items.size)
        assertTrue(result.warnings.any { it.contains("unparsable") })
    }

    @Test
    fun closingEdgeFoldsOntoTheFirstAnchor() {
        // Path explicitly returns to its start before Z: no duplicate anchor.
        val commands = listOf(
            PathCommand.MoveTo(0f, 0f),
            PathCommand.LineTo(24f, 0f),
            PathCommand.LineTo(0f, 24f),
            PathCommand.LineTo(0f, 0f),
            PathCommand.Close(),
        )
        val payload = decodeSingle(DocumentToNoteItems.convert(doc(path(commands)), noteId = "n"))
        assertEquals(3, payload.subpaths[0].anchors.size)
        assertTrue(payload.subpaths[0].closed)
    }
}
