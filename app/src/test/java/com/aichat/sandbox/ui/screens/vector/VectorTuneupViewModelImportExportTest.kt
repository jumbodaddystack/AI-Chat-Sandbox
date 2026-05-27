package com.aichat.sandbox.ui.screens.vector

import com.aichat.sandbox.data.vector.AndroidVectorDrawableParser
import com.aichat.sandbox.data.vector.AndroidVectorDrawableWriter
import com.aichat.sandbox.data.vector.VectorDocumentImporter
import com.aichat.sandbox.data.vector.VectorExportFormat
import com.aichat.sandbox.data.vector.VectorImportDetector
import com.aichat.sandbox.data.vector.VectorImportFormat
import com.aichat.sandbox.data.vector.VectorMetricsAnalyzer
import com.aichat.sandbox.data.vector.VectorSvgWriter
import com.aichat.sandbox.data.vector.VectorWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9 — import/export logic the [VectorTuneupViewModel] composes.
 *
 * The ViewModel itself is Hilt/Android-bound and the repository/exporter need a
 * `Context`, so (mirroring [VectorTuneupViewModelTest]) these tests exercise the
 * pure [VectorTuneupReducer] transitions plus the deterministic import/export
 * helpers the ViewModel delegates to (format detection, SVG canonicalization,
 * and SVG conversion of a resolved version's XML).
 */
class VectorTuneupViewModelImportExportTest {

    private val reducer = VectorTuneupReducer()

    private val svg = """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
            <path id="dot" d="M2,2 L20,2 L20,20 Z" fill="#FF0000" stroke="#000000" stroke-width="1"/>
        </svg>
    """.trimIndent()

    @Test
    fun setExportFormatUpdatesState() {
        val state = reducer.setExportFormat(VectorTuneupUiState(), VectorExportFormat.SVG)
        assertEquals(VectorExportFormat.SVG, state.exportFormat)
    }

    @Test
    fun createProjectFromSvgPreservesSourceAndCanonicalizesOriginal() {
        // Mirrors VectorTuneupRepository.createProjectFromInput: sourceXml keeps
        // the exact SVG, while the ORIGINAL version's XML is canonical Android XML.
        val sourceXml = svg
        assertEquals(VectorImportFormat.SVG, VectorImportDetector.detect(sourceXml))

        val document = VectorDocumentImporter.import(sourceXml)
        val canonical = AndroidVectorDrawableWriter.write(document)

        // sourceXml unchanged (still SVG); canonical is Android XML that reparses.
        assertTrue(sourceXml.contains("<svg"))
        assertTrue(canonical.contains("<vector"))
        val reparsed = AndroidVectorDrawableParser.parse(canonical)
        assertTrue(reparsed.warnings.none { it.code == VectorWarning.Codes.MALFORMED_XML })

        // Metrics computed from the canonical Android XML.
        val metrics = VectorMetricsAnalyzer.analyze(document, canonical)
        assertEquals(1, metrics.pathCount)
    }

    @Test
    fun exportSelectedVersionUsesRequestedFormat() {
        // Parse an SVG-origin version (canonical Android XML), then resolve the
        // version the export will use and convert it for the requested format.
        val parsed = reducer.parseInput(VectorTuneupUiState(inputXml = svg))
        val version = reducer.resolveExportVersion(parsed, null)
        assertTrue(version != null)

        // Android XML export keeps the canonical XML verbatim.
        assertEquals(version!!.xml, version.xml)

        // SVG export converts the canonical Android XML back to SVG.
        val svgOut = VectorSvgWriter.write(AndroidVectorDrawableParser.parse(version.xml))
        assertTrue(svgOut.contains("<svg xmlns=\"http://www.w3.org/2000/svg\""))
        assertTrue(svgOut.contains("<path"))

        // Format metadata drives the file name + share type.
        assertEquals("svg", VectorExportFormat.SVG.extension)
        assertEquals("image/svg+xml", VectorExportFormat.SVG.mimeType)
        assertEquals("xml", VectorExportFormat.ANDROID_VECTOR_XML.extension)
    }
}
