package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1 — parsing the `<vector>` structure, attributes, and warning paths. */
class AndroidVectorDrawableParserTest {

    private val minimal = """
        <?xml version="1.0" encoding="utf-8"?>
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="108dp"
            android:height="108dp"
            android:viewportWidth="108"
            android:viewportHeight="108">
            <path android:pathData="M0 0 L10 10" android:fillColor="#FF0000"/>
        </vector>
    """.trimIndent()

    @Test
    fun parseMinimalVector() {
        val doc = AndroidVectorDrawableParser.parse(minimal)
        assertEquals(108f, doc.viewport.widthDp)
        assertEquals(108f, doc.viewport.heightDp)
        assertEquals(108f, doc.viewport.viewportWidth)
        assertEquals(108f, doc.viewport.viewportHeight)
        val paths = doc.allPaths()
        assertEquals(1, paths.size)
        assertEquals("p_001", paths[0].id)
        assertEquals("#FF0000", paths[0].style.fillColor)
        assertNotNull(paths[0].commands)
        assertTrue(doc.warnings.isEmpty())
    }

    @Test
    fun parseStrokeAndFillPath() {
        val xml = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="24dp" android:height="24dp"
                android:viewportWidth="24" android:viewportHeight="24">
                <path
                    android:name="line"
                    android:pathData="M2 2 L22 22"
                    android:fillColor="#00000000"
                    android:fillAlpha="0.5"
                    android:fillType="evenOdd"
                    android:strokeColor="#202020"
                    android:strokeAlpha="0.8"
                    android:strokeWidth="1.5"
                    android:strokeLineCap="round"
                    android:strokeLineJoin="bevel"
                    android:strokeMiterLimit="4"/>
            </vector>
        """.trimIndent()
        val path = AndroidVectorDrawableParser.parse(xml).allPaths().single()
        assertEquals("line", path.name)
        val s = path.style
        assertEquals("#00000000", s.fillColor)
        assertEquals(0.5f, s.fillAlpha)
        assertEquals("evenOdd", s.fillType)
        assertEquals("#202020", s.strokeColor)
        assertEquals(0.8f, s.strokeAlpha)
        assertEquals(1.5f, s.strokeWidth)
        assertEquals("round", s.strokeLineCap)
        assertEquals("bevel", s.strokeLineJoin)
        assertEquals(4f, s.strokeMiterLimit)
    }

    @Test
    fun parseNestedGroup() {
        val xml = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="24dp" android:height="24dp"
                android:viewportWidth="24" android:viewportHeight="24">
                <group android:name="outer" android:translateX="2" android:translateY="3"
                       android:rotation="45" android:scaleX="2">
                    <path android:pathData="M0 0 L1 1" android:strokeColor="#111111"/>
                    <group android:name="inner">
                        <path android:pathData="M2 2 L3 3" android:strokeColor="#222222"/>
                    </group>
                </group>
            </vector>
        """.trimIndent()
        val doc = AndroidVectorDrawableParser.parse(xml)
        val groups = doc.allGroups()
        assertEquals(2, groups.size)
        val outer = groups[0]
        assertEquals("outer", outer.name)
        assertEquals("g_001", outer.id)
        assertEquals(2f, outer.translateX)
        assertEquals(3f, outer.translateY)
        assertEquals(45f, outer.rotation)
        assertEquals(2f, outer.scaleX)
        // Two paths total, one in each group.
        assertEquals(2, doc.allPaths().size)
        // Root holds exactly one child group.
        assertTrue(doc.root.children.single() is VectorNode.GroupNode)
    }

    @Test
    fun unknownTagProducesWarning() {
        val xml = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="24dp" android:height="24dp"
                android:viewportWidth="24" android:viewportHeight="24">
                <surprise android:foo="bar"/>
                <path android:pathData="M0 0 L1 1" android:strokeColor="#111111"/>
            </vector>
        """.trimIndent()
        val doc = AndroidVectorDrawableParser.parse(xml)
        assertTrue(doc.warnings.any { it.code == VectorWarning.Codes.UNSUPPORTED_TAG })
        assertEquals(1, doc.allPaths().size)
    }

    @Test
    fun malformedPathProducesWarning() {
        val xml = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="24dp" android:height="24dp"
                android:viewportWidth="24" android:viewportHeight="24">
                <path android:pathData="M0 0 ?!garbage" android:strokeColor="#111111"/>
            </vector>
        """.trimIndent()
        val doc = AndroidVectorDrawableParser.parse(xml)
        assertTrue(doc.warnings.any { it.code == VectorWarning.Codes.MALFORMED_PATH_DATA })
    }

    @Test
    fun missingViewportProducesWarningAndDefaults() {
        val xml = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="24dp" android:height="24dp">
                <path android:pathData="M0 0 L1 1" android:strokeColor="#111111"/>
            </vector>
        """.trimIndent()
        val doc = AndroidVectorDrawableParser.parse(xml)
        assertTrue(doc.warnings.any { it.code == VectorWarning.Codes.MISSING_VIEWPORT })
        // Falls back to the intrinsic dp size.
        assertEquals(24f, doc.viewport.viewportWidth)
        assertEquals(24f, doc.viewport.viewportHeight)
    }

    @Test
    fun completelyMalformedXmlReturnsSafeDocument() {
        val doc = AndroidVectorDrawableParser.parse("<vector <not xml")
        assertTrue(doc.warnings.any { it.code == VectorWarning.Codes.MALFORMED_XML })
        assertTrue(doc.allPaths().isEmpty())
    }

    @Test
    fun blankPathDataLeavesCommandsNull() {
        val xml = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="24dp" android:height="24dp"
                android:viewportWidth="24" android:viewportHeight="24">
                <path android:pathData="" android:strokeColor="#111111"/>
            </vector>
        """.trimIndent()
        val path = AndroidVectorDrawableParser.parse(xml).allPaths().single()
        assertNull(path.commands)
        assertFalse(path.pathData.isNotBlank())
    }
}
