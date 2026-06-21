package com.aichat.sandbox.data.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.BackgroundLayer
import com.aichat.sandbox.ui.components.notes.ImageItemCodec
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Bitmap-level checks for [NoteRasterizer]. Lives in androidTest because
 * `android.graphics.Bitmap` isn't stub-able on the host JVM.
 */
@RunWith(AndroidJUnit4::class)
class NoteRasterizerInstrumentedTest {

    @Test
    fun renderSelectionProducesNonTransparentInkPixels() {
        val stroke = strokeItem(
            points = floatArrayOf(
                10f, 10f, 1f, 0f,
                50f, 10f, 1f, 0f,
                90f, 10f, 1f, 0f,
            ),
            colorArgb = Color.BLACK,
        )
        val bitmap = NoteRasterizer.renderSelection(listOf(stroke), maxEdgePx = 256)
        assertNotNull("render of a single stroke must succeed", bitmap)
        val nonTransparent = countNonTransparentPixels(bitmap!!)
        assertTrue("expected stroke pixels on bitmap, got $nonTransparent", nonTransparent > 0)
        // Paper background is opaque white, so we also expect non-white ink
        // pixels somewhere on the canvas.
        val inkPixels = countNonPaperPixels(bitmap, paper = Color.WHITE)
        assertTrue("expected ink pixels distinct from paper, got $inkPixels", inkPixels > 0)
        bitmap.recycle()
    }

    @Test
    fun renderSelectionReturnsNullForEmptyInput() {
        assertNull(NoteRasterizer.renderSelection(emptyList()))
    }

    @Test
    fun renderNoteHonorsBackgroundStyle() {
        val stroke = strokeItem(
            points = floatArrayOf(0f, 0f, 1f, 0f, 100f, 100f, 1f, 0f),
            colorArgb = Color.BLACK,
        )
        val dotNote = makeNote(style = BackgroundLayer.STYLE_DOT)
        val plainNote = makeNote(style = BackgroundLayer.STYLE_PLAIN)
        val dotBmp = NoteRasterizer.renderNote(dotNote, listOf(stroke), maxEdgePx = 512)!!
        val plainBmp = NoteRasterizer.renderNote(plainNote, listOf(stroke), maxEdgePx = 512)!!
        // Plain has only ink + white. Dotted should add darker pixels from the
        // grid; we don't pin the exact count, just that the styles differ.
        val dotInk = countNonPaperPixels(dotBmp, paper = Color.WHITE)
        val plainInk = countNonPaperPixels(plainBmp, paper = Color.WHITE)
        assertTrue(
            "dotted bitmap must contain at least as many non-paper pixels as plain " +
                "(dot=$dotInk, plain=$plainInk)",
            dotInk > plainInk,
        )
        dotBmp.recycle()
        plainBmp.recycle()
    }


    @Test
    fun aiImageRendererDrawsEmbeddedImageWhenFilesDirProvided() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(context.filesDir, "note-images").apply { mkdirs() }
        val imageFile = File(dir, "rasterizer-test.png")
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        FileOutputStream(imageFile).use { out ->
            source.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        source.recycle()

        val image = imageItem("note-images/${imageFile.name}")
        val pngBytes = NoteRasterizerImageRenderer.renderToPng(
            items = listOf(image),
            backgroundStyle = BackgroundLayer.STYLE_PLAIN,
            maxEdgePx = 128,
            filesDir = context.filesDir,
        )

        assertNotNull("AI rasterizer should produce PNG bytes for an embedded image", pngBytes)
        val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes!!.size)
        assertNotNull("rendered PNG should decode", bitmap)
        val redPixels = countPixelsMatching(bitmap!!) { pixel ->
            Color.red(pixel) > 200 && Color.green(pixel) < 80 && Color.blue(pixel) < 80
        }
        assertTrue("expected ImageRenderer.draw output to include red image pixels", redPixels > 0)
        bitmap.recycle()
        imageFile.delete()
    }

    @Test
    fun toPngRoundTripsToNonEmptyByteArray() {
        val stroke = strokeItem(
            points = floatArrayOf(0f, 0f, 1f, 0f, 32f, 32f, 1f, 0f),
            colorArgb = Color.BLACK,
        )
        val bitmap = NoteRasterizer.renderSelection(listOf(stroke), maxEdgePx = 128)!!
        val bytes = NoteRasterizer.toPng(bitmap)
        assertTrue("png bytes should be non-empty", bytes.isNotEmpty())
        // PNG magic number.
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals('P'.code.toByte(), bytes[1])
        assertEquals('N'.code.toByte(), bytes[2])
        assertEquals('G'.code.toByte(), bytes[3])
        bitmap.recycle()
    }

    private fun countNonTransparentPixels(bitmap: android.graphics.Bitmap): Int {
        var count = 0
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (p in pixels) if (Color.alpha(p) != 0) count++
        return count
    }

    private fun countNonPaperPixels(bitmap: android.graphics.Bitmap, paper: Int): Int {
        var count = 0
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (p in pixels) if (p != paper) count++
        return count
    }

    private fun countPixelsMatching(bitmap: android.graphics.Bitmap, predicate: (Int) -> Boolean): Int {
        var count = 0
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (p in pixels) if (predicate(p)) count++
        return count
    }

    private fun imageItem(relativePath: String): NoteItem = NoteItem(
        noteId = "n",
        zIndex = 0,
        kind = NoteItem.KIND_IMAGE,
        tool = null,
        colorArgb = Color.BLACK,
        baseWidthPx = 1f,
        payload = ImageItemCodec.encode(
            ImageItemCodec.ImagePayload(
                relativePath = relativePath,
                naturalWidth = 4f,
                naturalHeight = 4f,
                minX = 0f,
                minY = 0f,
                maxX = 40f,
                maxY = 40f,
            ),
        ),
    )

    private fun strokeItem(points: FloatArray, colorArgb: Int): NoteItem = NoteItem(
        noteId = "n",
        zIndex = 0,
        kind = "stroke",
        tool = "pen",
        colorArgb = colorArgb,
        baseWidthPx = 4f,
        payload = StrokeCodec.encode(points),
    )

    private fun makeNote(style: String): Note = Note(
        id = "n",
        title = "t",
        backgroundStyle = style,
        schemaVersion = 1,
        minX = 0f,
        minY = 0f,
        maxX = 0f,
        maxY = 0f,
        thumbnailPath = null,
        ocrText = null,
    )
}
