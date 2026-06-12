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

    // ── 16.1 — multi-subpath + fill rule ─────────────────────────────────

    @Test
    fun multiSubpathEvenOddEmitsFillTypeAndBothRuns() {
        fun rectSub(x0: Float, y0: Float, x1: Float, y1: Float) = PathCodec.Subpath(
            anchors = listOf(
                PathCodec.Anchor(x0, y0),
                PathCodec.Anchor(x1, y0),
                PathCodec.Anchor(x1, y1),
                PathCodec.Anchor(x0, y1),
            ),
            closed = true,
        )
        val donut = PathCodec.PathPayload(
            subpaths = listOf(rectSub(0f, 0f, 100f, 100f), rectSub(25f, 25f, 75f, 75f)),
            fillRule = PathCodec.FILL_RULE_EVEN_ODD,
            fillArgb = 0xFF2463EB.toInt(),
        )
        val rendered = NoteVectorDrawableExporter.render(
            items = listOf(pathItem(donut)),
            sizeDp = 24,
        )
        assertEquals(0, rendered.skippedCount)
        assertTrue(rendered.xml.contains("android:fillType=\"evenOdd\""))
        assertTrue(rendered.xml.contains("android:fillColor=\"#2463EB\""))
        // Both rings live in one android:pathData (two M runs, two Z).
        val pathData = Regex("android:pathData=\"([^\"]+)\"")
            .find(rendered.xml)!!.groupValues[1]
        assertEquals(2, Regex("M").findAll(pathData).count())
        assertEquals(2, Regex("Z").findAll(pathData).count())
    }

    @Test
    fun nonZeroPathEmitsNoFillType() {
        val rendered = NoteVectorDrawableExporter.render(
            items = listOf(pathItem(square(fill = 0xFF2463EB.toInt()))),
            sizeDp = 24,
        )
        assertTrue(!rendered.xml.contains("android:fillType"))
    }
}
