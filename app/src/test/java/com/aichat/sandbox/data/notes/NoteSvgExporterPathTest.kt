package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.PathCodec
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 12.5 — SVG export parity for `"path"` items. Path emission uses pure int
 * math (unlike strokes, which touch `android.graphics.Color`), so these
 * tests run on the plain JVM.
 */
class NoteSvgExporterPathTest {

    private fun emptyNote() = Note(
        id = "test",
        title = "Test",
        backgroundStyle = "plain",
        schemaVersion = 1,
        minX = 0f, minY = 0f, maxX = 0f, maxY = 0f,
        thumbnailPath = null,
        ocrText = null,
    )

    private fun pathItem(payload: PathCodec.PathPayload, color: Int = 0xFF000000.toInt()) =
        NoteItem(
            noteId = "test",
            zIndex = 0,
            kind = PathCodec.KIND,
            tool = "path_pen",
            colorArgb = color,
            baseWidthPx = 3f,
            payload = PathCodec.encode(payload),
        )

    private fun curved(closed: Boolean = false, fill: Int = 0, style: Byte = ShapeCodec.STROKE_STYLE_SOLID) =
        PathCodec.PathPayload(
            anchors = listOf(
                PathCodec.Anchor(0f, 0f, outDx = 20f, outDy = 0f),
                PathCodec.Anchor(100f, 50f, inDx = -20f, inDy = 0f, outDx = 20f, outDy = 0f),
                PathCodec.Anchor(200f, 0f, inDx = -20f, inDy = 0f),
            ),
            closed = closed,
            fillArgb = fill,
            strokeStyle = style,
        )

    @Test
    fun openPathEmitsCubicDataNoFill() {
        val svg = NoteSvgExporter.renderSvg(emptyNote(), listOf(pathItem(curved())))
        assertTrue(svg.contains("<path d=\"M0 0"))
        assertTrue(svg.contains("C20 0 80 50 100 50"))
        assertTrue(svg.contains("C120 50 180 0 200 0"))
        assertTrue(svg.contains("fill=\"none\""))
        assertTrue(svg.contains("stroke=\"#000000\""))
        assertTrue(svg.contains("stroke-linecap=\"round\""))
        assertTrue(svg.contains("stroke-linejoin=\"round\""))
        assertFalse(svg.contains("Z\""))
    }

    @Test
    fun closedFilledPathEmitsZAndFill() {
        val svg = NoteSvgExporter.renderSvg(
            emptyNote(),
            listOf(pathItem(curved(closed = true, fill = 0x802463EB.toInt()))),
        )
        assertTrue(svg.contains("Z\""))
        assertTrue(svg.contains("fill=\"#2463EB\""))
    }

    @Test
    fun dashedPathEmitsDashArrayScaledByWidth() {
        val svg = NoteSvgExporter.renderSvg(
            emptyNote(),
            listOf(pathItem(curved(style = ShapeCodec.STROKE_STYLE_DASHED))),
        )
        // width 3 × (3, 2) factors → "9 6", mirroring the shape exporter.
        assertTrue(svg.contains("stroke-dasharray=\"9 6\""))
    }

    @Test
    fun capJoinNamesFollowTheCodecByte() {
        val payload = curved().copy(
            capJoin = PathCodec.capJoinOf(PathCodec.CAP_SQUARE, PathCodec.JOIN_MITER),
        )
        val svg = NoteSvgExporter.renderSvg(emptyNote(), listOf(pathItem(payload)))
        assertTrue(svg.contains("stroke-linecap=\"square\""))
        assertTrue(svg.contains("stroke-linejoin=\"miter\""))
    }

    @Test
    fun openPathNeverFillsEvenWithFillBytes() {
        val svg = NoteSvgExporter.renderSvg(
            emptyNote(),
            listOf(pathItem(curved(closed = false, fill = 0x80FF0000.toInt()))),
        )
        assertTrue(svg.contains("fill=\"none\""))
    }
}
