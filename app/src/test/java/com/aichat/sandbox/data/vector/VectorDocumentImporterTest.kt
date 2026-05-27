package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 9 — unified Android-XML / SVG import routing. */
class VectorDocumentImporterTest {

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
    fun importsAndroidVector() {
        val doc = VectorDocumentImporter.import(androidXml)
        assertEquals(1, doc.allPaths().size)
        assertTrue(doc.warnings.none { it.code == VectorWarning.Codes.MALFORMED_XML })
    }

    @Test
    fun importsSvgAsVectorDocument() {
        val doc = VectorDocumentImporter.import(svg)
        assertEquals(1, doc.allPaths().size)
        assertEquals("#FF0000", doc.allPaths().single().style.strokeColor)
    }

    @Test
    fun unknownInputReturnsMalformedWarning() {
        val doc = VectorDocumentImporter.import("not vector or svg")
        assertTrue(doc.warnings.any { it.code == VectorWarning.Codes.IMPORT_UNKNOWN_FORMAT })
        assertTrue(doc.allPaths().isEmpty())
    }

    @Test
    fun svgImportCanBeWrittenAsAndroidVectorXml() {
        val doc = VectorDocumentImporter.import(svg)
        val xml = AndroidVectorDrawableWriter.write(doc)
        val reparsed = AndroidVectorDrawableParser.parse(xml)
        assertTrue(reparsed.warnings.none { it.code == VectorWarning.Codes.MALFORMED_XML })
        assertEquals(1, reparsed.allPaths().size)
        assertEquals("#FF0000", reparsed.allPaths().single().style.strokeColor)
    }
}
