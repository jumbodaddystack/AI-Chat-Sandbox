package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.Note
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9 — accessibility metadata in SVG exports. A note's optional alt text
 * becomes `<title>` (note title) + `<desc>` (alt text); absent alt text or a
 * disabled toggle leaves the output unchanged (no metadata block).
 */
class NoteSvgExporterMetadataTest {

    @Test
    fun emitsTitleAndDescWhenAltTextPresent() {
        val note = noteWith(title = "Blue circle", altText = "A blue circle on white.")
        val svg = NoteSvgExporter.renderSvg(note, emptyList())
        assertTrue(svg.contains("<title>Blue circle</title>"))
        assertTrue(svg.contains("<desc>A blue circle on white.</desc>"))
    }

    @Test
    fun escapesMetadataXml() {
        val note = noteWith(title = "A & B", altText = "<sketch> of \"x\"")
        val svg = NoteSvgExporter.renderSvg(note, emptyList())
        assertTrue(svg.contains("<title>A &amp; B</title>"))
        assertTrue(svg.contains("<desc>&lt;sketch&gt; of &quot;x&quot;</desc>"))
    }

    @Test
    fun noMetadataWhenAltTextBlank() {
        val note = noteWith(title = "Untitled", altText = null)
        val svg = NoteSvgExporter.renderSvg(note, emptyList())
        assertFalse(svg.contains("<desc>"))
        assertFalse(svg.contains("<title>"))
    }

    @Test
    fun noMetadataWhenDisabled() {
        val note = noteWith(title = "T", altText = "described")
        val svg = NoteSvgExporter.renderSvg(note, emptyList(), embedMetadata = false)
        assertFalse(svg.contains("<desc>"))
        assertFalse(svg.contains("<title>"))
    }

    private fun noteWith(title: String, altText: String?) = Note(
        id = "n",
        title = title,
        backgroundStyle = "plain",
        schemaVersion = 1,
        minX = 0f, minY = 0f, maxX = 100f, maxY = 100f,
        thumbnailPath = null,
        ocrText = null,
        altText = altText,
    )
}
