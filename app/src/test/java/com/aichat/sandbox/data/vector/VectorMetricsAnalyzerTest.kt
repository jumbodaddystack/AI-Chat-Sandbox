package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1 — structural counts, color usage, warnings, and approximate bounds. */
class VectorMetricsAnalyzerTest {

    @Test
    fun metricsCountsPathsGroupsCommandsAndColors() {
        val xml = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="100dp" android:height="100dp"
                android:viewportWidth="100" android:viewportHeight="100">
                <path android:pathData="M0 0 L10 0 L10 10" android:strokeColor="#109F5C"/>
                <path android:pathData="M20 20 L40 20 L40 40 Z"
                      android:fillColor="#2D2D2D" android:strokeColor="#109F5C"/>
                <group>
                    <path android:pathData="M50 50 C10 10 20 20 30 30" android:fillColor="#2D2D2D"/>
                </group>
            </vector>
        """.trimIndent()
        val doc = AndroidVectorDrawableParser.parse(xml)
        val m = VectorMetricsAnalyzer.analyze(doc, xml)

        assertEquals(3, m.pathCount)
        assertEquals(1, m.groupCount)
        assertEquals(9, m.commandCount)
        assertEquals(9, m.parsedCommandCount)
        assertEquals(0, m.unsupportedPathCount)
        assertEquals(2, m.strokePathCount)
        assertEquals(2, m.fillPathCount)
        assertEquals(2, m.colorCounts["#109F5C"])
        assertEquals(2, m.colorCounts["#2D2D2D"])
        assertEquals(xml.toByteArray(Charsets.UTF_8).size, m.xmlBytes)
    }

    @Test
    fun metricsIncludesWarnings() {
        val xml = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="24dp" android:height="24dp"
                android:viewportWidth="24" android:viewportHeight="24">
                <unsupported/>
                <path android:pathData="M0 0 L1 1" android:strokeColor="#111111"/>
            </vector>
        """.trimIndent()
        val doc = AndroidVectorDrawableParser.parse(xml)
        val m = VectorMetricsAnalyzer.analyze(doc, xml)
        assertTrue(m.warnings.any { it.code == VectorWarning.Codes.UNSUPPORTED_TAG })
    }

    @Test
    fun metricsEstimatesBoundsForBasicCommands() {
        val xml = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="100dp" android:height="100dp"
                android:viewportWidth="100" android:viewportHeight="100">
                <path android:pathData="M0 0 L10 0 L10 20 Z" android:fillColor="#000000"/>
            </vector>
        """.trimIndent()
        val doc = AndroidVectorDrawableParser.parse(xml)
        val bounds = VectorMetricsAnalyzer.analyze(doc, xml).bounds
        assertNotNull(bounds)
        bounds!!
        assertEquals(0f, bounds.minX)
        assertEquals(0f, bounds.minY)
        assertEquals(10f, bounds.maxX)
        assertEquals(20f, bounds.maxY)
    }

    @Test
    fun metricsCountsUnsupportedPathsAndZeroLength() {
        val xml = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="24dp" android:height="24dp"
                android:viewportWidth="24" android:viewportHeight="24">
                <path android:pathData="" android:strokeColor="#111111"/>
                <path android:pathData="M5 5 L5 5" android:strokeColor="#222222"/>
            </vector>
        """.trimIndent()
        val doc = AndroidVectorDrawableParser.parse(xml)
        val m = VectorMetricsAnalyzer.analyze(doc, xml)
        // Blank path data => commands null => counted as unsupported.
        assertEquals(1, m.unsupportedPathCount)
        // The degenerate move-to-same-point path has zero length.
        assertEquals(1, m.zeroLengthPathCount)
    }

    @Test
    fun analyzeEmptyDocumentHasNullBounds() {
        val doc = VectorDocument(
            viewport = VectorViewport(24f, 24f, 24f, 24f),
            root = VectorGroup(id = "root", children = emptyList()),
        )
        val m = VectorMetricsAnalyzer.analyze(doc)
        assertEquals(0, m.pathCount)
        assertEquals(null, m.bounds)
    }
}
