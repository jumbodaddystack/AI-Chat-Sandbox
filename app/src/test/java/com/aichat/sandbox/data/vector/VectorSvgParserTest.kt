package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 9 — SVG subset import into the shared [VectorDocument] foundation. */
class VectorSvgParserTest {

    @Test
    fun parseMinimalSvg() {
        val doc = VectorSvgParser.parse(
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"></svg>""",
        )
        assertEquals(24f, doc.viewport.viewportWidth)
        assertEquals(24f, doc.viewport.viewportHeight)
        assertTrue(doc.allPaths().isEmpty())
    }

    @Test
    fun parsePathElement() {
        val doc = VectorSvgParser.parse(
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                <path id="line" d="M2,2 L20,20" stroke="#109F5C" stroke-width="1.5"/>
            </svg>
            """.trimIndent(),
        )
        val path = doc.allPaths().single()
        assertEquals("line", path.name)
        assertEquals("M2,2 L20,20", path.pathData)
        assertEquals("#109F5C", path.style.strokeColor)
        assertEquals(1.5f, path.style.strokeWidth)
        assertNotNull(path.commands)
    }

    @Test
    fun parseRectCircleEllipseLinePolylinePolygon() {
        val doc = VectorSvgParser.parse(
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
                <rect x="1" y="2" width="10" height="20" fill="#FF0000"/>
                <circle cx="50" cy="50" r="5" fill="#00FF00"/>
                <ellipse cx="60" cy="60" rx="8" ry="4" fill="#0000FF"/>
                <line x1="0" y1="0" x2="10" y2="10" stroke="#000000"/>
                <polyline points="0,0 5,5 10,0" stroke="#000000"/>
                <polygon points="20,20 30,20 25,30" fill="#101010"/>
            </svg>
            """.trimIndent(),
        )
        val paths = doc.allPaths()
        assertEquals(6, paths.size)
        // Rect closes (Z) and uses line commands.
        assertTrue(paths[0].pathData.contains("M1,2"))
        assertTrue(paths[0].pathData.trimEnd().endsWith("Z"))
        // Circle/ellipse use arcs.
        assertTrue(paths[1].pathData.contains("A"))
        assertTrue(paths[2].pathData.contains("A"))
        // Line: open M..L
        assertEquals("M0,0 L10,10", paths[3].pathData)
        // Polyline open, polygon closed.
        assertFalse(paths[4].pathData.trimEnd().endsWith("Z"))
        assertTrue(paths[5].pathData.trimEnd().endsWith("Z"))
        // Every shape parsed into commands.
        assertTrue(paths.all { it.commands != null })
    }

    @Test
    fun parseRoundedRectUsesArcs() {
        val doc = VectorSvgParser.parse(
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
                <rect x="0" y="0" width="40" height="20" rx="4" ry="4" fill="#000000"/>
            </svg>
            """.trimIndent(),
        )
        assertTrue(doc.allPaths().single().pathData.contains("A"))
    }

    @Test
    fun parseViewBoxAndSize() {
        val withSize = VectorSvgParser.parse(
            """<svg xmlns="http://www.w3.org/2000/svg" width="48px" height="64px" viewBox="0 0 24 32"/>""",
        )
        assertEquals(24f, withSize.viewport.viewportWidth)
        assertEquals(32f, withSize.viewport.viewportHeight)
        assertEquals(48f, withSize.viewport.widthDp)
        assertEquals(64f, withSize.viewport.heightDp)
    }

    @Test
    fun missingViewBoxInfersFromSizeAndWarns() {
        val doc = VectorSvgParser.parse(
            """<svg xmlns="http://www.w3.org/2000/svg" width="50" height="40"/>""",
        )
        assertEquals(50f, doc.viewport.viewportWidth)
        assertEquals(40f, doc.viewport.viewportHeight)
        assertTrue(doc.warnings.any { it.code == VectorWarning.Codes.SVG_MISSING_VIEWBOX })
    }

    @Test
    fun missingViewBoxAndSizeDefaultsTo24() {
        val doc = VectorSvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg"/>""")
        assertEquals(24f, doc.viewport.viewportWidth)
        assertEquals(24f, doc.viewport.viewportHeight)
        assertTrue(doc.warnings.any { it.code == VectorWarning.Codes.SVG_MISSING_VIEWBOX })
    }

    @Test
    fun parseInlineStyle() {
        val doc = VectorSvgParser.parse(
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                <path d="M0,0 L1,1" style="fill:#fff;stroke:#000;stroke-width:2;fill-rule:evenodd"/>
            </svg>
            """.trimIndent(),
        )
        val style = doc.allPaths().single().style
        assertEquals("#FFFFFF", style.fillColor)
        assertEquals("#000000", style.strokeColor)
        assertEquals(2f, style.strokeWidth)
        assertEquals("evenOdd", style.fillType)
    }

    @Test
    fun fillNoneAndStrokeNoneRespected() {
        val doc = VectorSvgParser.parse(
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                <path d="M0,0 L1,1" fill="none" stroke="#123456"/>
                <path d="M0,0 L1,1" fill="#abcdef" stroke="none"/>
            </svg>
            """.trimIndent(),
        )
        val a = doc.allPaths()[0]
        assertNull(a.style.fillColor)
        assertEquals("#123456", a.style.strokeColor)
        val b = doc.allPaths()[1]
        assertEquals("#ABCDEF", b.style.fillColor)
        assertNull(b.style.strokeColor)
    }

    @Test
    fun parseGroupStyleInheritance() {
        val doc = VectorSvgParser.parse(
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                <g fill="#abcdef" stroke="#123456" stroke-width="3">
                    <path d="M0,0 L1,1"/>
                    <path d="M2,2 L3,3" fill="#FF0000"/>
                </g>
            </svg>
            """.trimIndent(),
        )
        val paths = doc.allPaths()
        assertEquals(2, paths.size)
        // First inherits group fill/stroke.
        assertEquals("#ABCDEF", paths[0].style.fillColor)
        assertEquals("#123456", paths[0].style.strokeColor)
        assertEquals(3f, paths[0].style.strokeWidth)
        // Second overrides fill, still inherits stroke.
        assertEquals("#FF0000", paths[1].style.fillColor)
        assertEquals("#123456", paths[1].style.strokeColor)
    }

    @Test
    fun defaultFillIsBlackWhenUnspecified() {
        val doc = VectorSvgParser.parse(
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                <path d="M0,0 L1,1"/>
            </svg>
            """.trimIndent(),
        )
        assertEquals("#000000", doc.allPaths().single().style.fillColor)
    }

    @Test
    fun parseSimpleTransforms() {
        val doc = VectorSvgParser.parse(
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                <g transform="translate(4 5)">
                    <path d="M0,0 L1,1"/>
                </g>
                <path d="M2,2 L3,3" transform="rotate(45 12 12)"/>
                <path d="M4,4 L5,5" transform="scale(2 3)"/>
            </svg>
            """.trimIndent(),
        )
        val groups = doc.allGroups()
        // translate group + 2 synthetic wrapper groups for transformed shapes.
        val translateGroup = groups.first { it.translateX == 4f }
        assertEquals(5f, translateGroup.translateY)

        val rotateGroup = groups.first { it.rotation == 45f }
        assertEquals(12f, rotateGroup.pivotX)
        assertEquals(12f, rotateGroup.pivotY)

        val scaleGroup = groups.first { it.scaleX == 2f }
        assertEquals(3f, scaleGroup.scaleY)
    }

    @Test
    fun unsupportedTransformWarns() {
        val doc = VectorSvgParser.parse(
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                <g transform="matrix(1 0 0 1 2 3)"><path d="M0,0 L1,1"/></g>
            </svg>
            """.trimIndent(),
        )
        assertTrue(doc.warnings.any { it.code == VectorWarning.Codes.SVG_TRANSFORM_UNSUPPORTED })
    }

    @Test
    fun unsupportedTagsProduceWarnings() {
        val doc = VectorSvgParser.parse(
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                <text x="0" y="0">hi</text>
                <filter id="f"/>
                <path d="M0,0 L1,1" fill="#000000"/>
            </svg>
            """.trimIndent(),
        )
        assertTrue(doc.warnings.any { it.code == VectorWarning.Codes.SVG_UNSUPPORTED_TAG })
        // The valid path still imports.
        assertEquals(1, doc.allPaths().size)
        // Partial-import summary appended.
        assertTrue(doc.warnings.any { it.code == VectorWarning.Codes.SVG_IMPORT_PARTIAL })
    }

    @Test
    fun gradientFillWarnsAndFallsBack() {
        val doc = VectorSvgParser.parse(
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                <defs><linearGradient id="g"/></defs>
                <path d="M0,0 L1,1" fill="url(#g)"/>
            </svg>
            """.trimIndent(),
        )
        assertTrue(doc.warnings.any { it.code == VectorWarning.Codes.SVG_GRADIENT_UNSUPPORTED })
        // Path still present; fill fell back to the inherited default.
        assertEquals(1, doc.allPaths().size)
    }

    @Test
    fun currentColorWarns() {
        val doc = VectorSvgParser.parse(
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                <path d="M0,0 L1,1" fill="currentColor"/>
            </svg>
            """.trimIndent(),
        )
        assertTrue(doc.warnings.any { it.code == VectorWarning.Codes.SVG_STYLE_UNSUPPORTED })
    }

    @Test
    fun malformedSvgReturnsWarningDocument() {
        val doc = VectorSvgParser.parse("<svg><path d= ")
        assertTrue(doc.warnings.any { it.code == VectorWarning.Codes.SVG_MALFORMED })
        assertTrue(doc.allPaths().isEmpty())
        assertEquals(24f, doc.viewport.viewportWidth)
    }

    @Test
    fun nonSvgRootReturnsWarningDocument() {
        val doc = VectorSvgParser.parse("<html></html>")
        assertTrue(doc.warnings.any { it.code == VectorWarning.Codes.SVG_MALFORMED })
    }

    @Test
    fun parsedSvgWritesToAndroidVector() {
        val doc = VectorSvgParser.parse(
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                <path id="dot" d="M2,2 L20,2 L20,20 Z" fill="#FF0000" stroke="#000000" stroke-width="1"/>
            </svg>
            """.trimIndent(),
        )
        val androidXml = AndroidVectorDrawableWriter.write(doc)
        // Round-trips back through the Android parser cleanly.
        val reparsed = AndroidVectorDrawableParser.parse(androidXml)
        assertTrue(reparsed.warnings.none { it.code == VectorWarning.Codes.MALFORMED_XML })
        val path = reparsed.allPaths().single()
        assertEquals("#FF0000", path.style.fillColor)
        assertEquals("#000000", path.style.strokeColor)
    }
}
