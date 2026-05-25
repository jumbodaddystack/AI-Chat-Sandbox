package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1 — writer output shape and parse → write → parse equivalence. */
class AndroidVectorDrawableWriterTest {

    @Test
    fun writeMinimalVector() {
        val doc = VectorDocument(
            viewport = VectorViewport(108f, 108f, 108f, 108f),
            root = VectorGroup(
                id = "root",
                children = listOf(
                    VectorNode.PathNode(
                        VectorPath(
                            id = "p_001",
                            pathData = "M0 0 L10 10",
                            commands = PathDataParser.parse("M0 0 L10 10").commands,
                            style = VectorStyle(fillColor = "#FF0000"),
                        ),
                    ),
                ),
            ),
        )
        val xml = AndroidVectorDrawableWriter.write(doc)
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"))
        assertTrue(xml.contains("xmlns:android=\"http://schemas.android.com/apk/res/android\""))
        assertTrue(xml.contains("android:width=\"108dp\""))
        assertTrue(xml.contains("android:height=\"108dp\""))
        assertTrue(xml.contains("android:viewportWidth=\"108\""))
        assertTrue(xml.contains("android:fillColor=\"#FF0000\""))
        assertTrue(xml.contains("android:pathData=\"M0,0L10,10\""))
    }

    @Test
    fun writerRoundTripPreservesSupportedFields() {
        val source = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="48dp" android:height="48dp"
                android:viewportWidth="48" android:viewportHeight="48">
                <group android:name="g" android:translateX="4" android:rotation="30">
                    <path
                        android:name="shape"
                        android:pathData="M2 2 L46 2 L46 46 Z"
                        android:fillColor="#3366CC"
                        android:fillAlpha="0.75"
                        android:strokeColor="#101010"
                        android:strokeWidth="2"
                        android:strokeLineCap="round"
                        android:strokeLineJoin="round"/>
                </group>
            </vector>
        """.trimIndent()

        val first = AndroidVectorDrawableParser.parse(source)
        val rewritten = AndroidVectorDrawableWriter.write(first)
        val second = AndroidVectorDrawableParser.parse(rewritten)

        assertEquals(first.viewport, second.viewport)

        val g1 = first.allGroups().single()
        val g2 = second.allGroups().single()
        assertEquals(g1.name, g2.name)
        assertEquals(g1.translateX, g2.translateX)
        assertEquals(g1.rotation, g2.rotation)

        val p1 = first.allPaths().single()
        val p2 = second.allPaths().single()
        assertEquals(p1.name, p2.name)
        assertEquals(p1.style, p2.style)
        assertEquals(p1.commands, p2.commands)
        assertTrue(second.warnings.isEmpty())
    }

    @Test
    fun writerOmitsNullStyleAttributes() {
        val doc = VectorDocument(
            viewport = VectorViewport(24f, 24f, 24f, 24f),
            root = VectorGroup(
                id = "root",
                children = listOf(
                    VectorNode.PathNode(
                        VectorPath(
                            id = "p_001",
                            pathData = "M0 0 L1 1",
                            commands = PathDataParser.parse("M0 0 L1 1").commands,
                            style = VectorStyle(strokeColor = "#000000", strokeWidth = 1f),
                        ),
                    ),
                ),
            ),
        )
        val xml = AndroidVectorDrawableWriter.write(doc)
        assertTrue(xml.contains("android:strokeColor=\"#000000\""))
        assertTrue(xml.contains("android:strokeWidth=\"1\""))
        assertFalse(xml.contains("android:fillColor"))
        assertFalse(xml.contains("android:fillAlpha"))
        assertFalse(xml.contains("android:name"))
        assertFalse(xml.contains("android:strokeLineCap"))
    }

    @Test
    fun writerFallsBackToOriginalPathDataWhenCommandsNull() {
        val doc = VectorDocument(
            viewport = VectorViewport(24f, 24f, 24f, 24f),
            root = VectorGroup(
                id = "root",
                children = listOf(
                    VectorNode.PathNode(
                        VectorPath(
                            id = "p_001",
                            pathData = "M0 0 weird-but-preserved",
                            commands = null,
                            style = VectorStyle(fillColor = "#FFFFFF"),
                        ),
                    ),
                ),
            ),
        )
        val xml = AndroidVectorDrawableWriter.write(doc)
        assertTrue(xml.contains("android:pathData=\"M0 0 weird-but-preserved\""))
    }

    @Test
    fun writerEmitsEmptyGroupAsSelfClosing() {
        val doc = VectorDocument(
            viewport = VectorViewport(24f, 24f, 24f, 24f),
            root = VectorGroup(
                id = "root",
                children = listOf(
                    VectorNode.GroupNode(VectorGroup(id = "g_001", name = "empty", children = emptyList())),
                ),
            ),
        )
        val xml = AndroidVectorDrawableWriter.write(doc)
        assertTrue(xml.contains("<group android:name=\"empty\"/>"))
    }
}
