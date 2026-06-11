package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.PathCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 12.5 — VectorDrawable export parity for `"path"` items (pure JVM). */
class NoteVectorDrawableExporterPathTest {

    private fun pathItem(payload: PathCodec.PathPayload) = NoteItem(
        noteId = "test",
        zIndex = 0,
        kind = PathCodec.KIND,
        tool = "path_pen",
        colorArgb = 0xFFD62828.toInt(),
        baseWidthPx = 2f,
        payload = PathCodec.encode(payload),
    )

    private fun square(fill: Int = 0) = PathCodec.PathPayload(
        anchors = listOf(
            PathCodec.Anchor(0f, 0f),
            PathCodec.Anchor(100f, 0f),
            PathCodec.Anchor(100f, 100f),
            PathCodec.Anchor(0f, 100f),
        ),
        closed = true,
        fillArgb = fill,
    )

    @Test
    fun pathEmitsCubicPathDataAndIsNotSkipped() {
        val rendered = NoteVectorDrawableExporter.render(
            items = listOf(pathItem(square())),
            sizeDp = 24,
        )
        assertEquals(0, rendered.skippedCount)
        assertTrue(rendered.xml.contains("android:pathData=\"M"))
        assertTrue(rendered.xml.contains("C"))
        assertTrue(rendered.xml.contains("Z\""))
        assertTrue(rendered.xml.contains("android:strokeColor=\"#D62828\""))
    }

    @Test
    fun closedFilledPathEmitsFillColor() {
        val rendered = NoteVectorDrawableExporter.render(
            items = listOf(pathItem(square(fill = 0xFF2463EB.toInt()))),
            sizeDp = 48,
        )
        assertTrue(rendered.xml.contains("android:fillColor=\"#2463EB\""))
    }

    @Test
    fun capJoinAttributesFollowTheCodecByte() {
        val payload = square().copy(
            capJoin = PathCodec.capJoinOf(PathCodec.CAP_BUTT, PathCodec.JOIN_BEVEL),
        )
        val rendered = NoteVectorDrawableExporter.render(
            items = listOf(pathItem(payload)),
            sizeDp = 24,
        )
        assertTrue(rendered.xml.contains("android:strokeLineCap=\"butt\""))
        assertTrue(rendered.xml.contains("android:strokeLineJoin=\"bevel\""))
    }
}
