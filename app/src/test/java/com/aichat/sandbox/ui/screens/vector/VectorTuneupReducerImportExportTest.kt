package com.aichat.sandbox.ui.screens.vector

import com.aichat.sandbox.data.vector.VectorExportFormat
import com.aichat.sandbox.data.vector.VectorImportFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 9 — reducer coverage for SVG import detection and export-format state. */
class VectorTuneupReducerImportExportTest {

    private val reducer = VectorTuneupReducer()

    private val androidXml = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="24dp" android:height="24dp"
            android:viewportWidth="24" android:viewportHeight="24">
            <path android:name="a" android:pathData="M0,0 L10,10" android:strokeColor="#FF0000" android:strokeWidth="1"/>
        </vector>
    """.trimIndent()

    private val svg = """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
            <path id="a" d="M0,0 L10,10" stroke="#FF0000" stroke-width="1"/>
        </svg>
    """.trimIndent()

    @Test
    fun onXmlChangedUpdatesDetectedImportFormat() {
        val androidState = reducer.onXmlChanged(VectorTuneupUiState(), androidXml)
        assertEquals(VectorImportFormat.ANDROID_VECTOR, androidState.detectedImportFormat)

        val svgState = reducer.onXmlChanged(VectorTuneupUiState(), svg)
        assertEquals(VectorImportFormat.SVG, svgState.detectedImportFormat)

        val unknownState = reducer.onXmlChanged(VectorTuneupUiState(), "hello")
        assertEquals(VectorImportFormat.UNKNOWN, unknownState.detectedImportFormat)
    }

    @Test
    fun parseSvgInputCreatesOriginalVersion() {
        val parsed = reducer.parseInput(VectorTuneupUiState(inputXml = svg))
        assertNull(parsed.errorMessage)
        val original = parsed.original
        assertNotNull(original)
        // SVG is canonicalized to Android VectorDrawable XML for the original.
        assertTrue(original!!.xml.contains("<vector"))
        assertTrue(original.xml.contains("android:pathData"))
        assertEquals(1, original.metrics.pathCount)
        assertEquals(VectorTuneupTab.DIAGNOSTICS, parsed.selectedTab)
    }

    @Test
    fun parseAndroidInputStillWorksAndKeepsXmlVerbatim() {
        val parsed = reducer.parseInput(VectorTuneupUiState(inputXml = androidXml))
        assertNull(parsed.errorMessage)
        // Android XML is kept exactly as pasted (no reformatting).
        assertEquals(androidXml, parsed.original!!.xml)
    }

    @Test
    fun unknownInputShowsParseError() {
        val parsed = reducer.parseInput(VectorTuneupUiState(inputXml = "<html></html>"))
        assertEquals(VectorTuneupReducer.ERROR_PARSE, parsed.errorMessage)
        assertNull(parsed.original)
    }

    @Test
    fun setExportFormatUpdatesState() {
        val state = VectorTuneupUiState()
        assertEquals(VectorExportFormat.ANDROID_VECTOR_XML, state.exportFormat)

        val svgFmt = reducer.setExportFormat(state, VectorExportFormat.SVG)
        assertEquals(VectorExportFormat.SVG, svgFmt.exportFormat)

        val bundle = reducer.setExportFormat(svgFmt, VectorExportFormat.PROJECT_BUNDLE)
        assertEquals(VectorExportFormat.PROJECT_BUNDLE, bundle.exportFormat)
    }

    @Test
    fun setExportFormatPreservesEverythingElse() {
        val parsed = reducer.parseInput(VectorTuneupUiState(inputXml = androidXml))
        val switched = reducer.setExportFormat(parsed, VectorExportFormat.SVG)
        assertEquals(parsed.original, switched.original)
        assertEquals(parsed.inputXml, switched.inputXml)
    }
}
