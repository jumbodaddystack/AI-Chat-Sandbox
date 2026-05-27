package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 9 — SVG export wire format and determinism. */
class VectorSvgWriterTest {

    private fun path(
        id: String,
        data: String,
        style: VectorStyle,
        name: String? = null,
    ): VectorNode.PathNode = VectorNode.PathNode(
        VectorPath(
            id = id,
            name = name,
            pathData = data,
            commands = PathDataParser.parse(data).commands,
            style = style,
        ),
    )

    private fun doc(vararg children: VectorNode, viewport: VectorViewport = VectorViewport(24f, 24f, 48f, 48f)) =
        VectorDocument(viewport = viewport, root = VectorGroup(id = "root", children = children.toList()))

    @Test
    fun writesSvgRootWithViewBox() {
        val svg = VectorSvgWriter.write(doc(viewport = VectorViewport(24f, 24f, 108f, 108f)))
        assertTrue(svg.startsWith("<?xml version=\"1.0\""))
        assertTrue(svg.contains("xmlns=\"http://www.w3.org/2000/svg\""))
        assertTrue(svg.contains("width=\"24\""))
        assertTrue(svg.contains("height=\"24\""))
        assertTrue(svg.contains("viewBox=\"0 0 108 108\""))
        assertTrue(svg.trimEnd().endsWith("</svg>"))
    }

    @Test
    fun writesPathWithFillAndStroke() {
        val svg = VectorSvgWriter.write(
            doc(
                path(
                    "p_001", "M10,10 L20,20",
                    VectorStyle(
                        fillColor = "#FF0000",
                        strokeColor = "#109F5C",
                        strokeWidth = 1.2f,
                        strokeLineCap = "round",
                    ),
                    name = "leaf",
                ),
            ),
        )
        assertTrue(svg.contains("<path"))
        assertTrue(svg.contains("id=\"leaf\""))
        assertTrue(svg.contains("d=\"M10,10L20,20\""))
        assertTrue(svg.contains("fill=\"#FF0000\""))
        assertTrue(svg.contains("stroke=\"#109F5C\""))
        assertTrue(svg.contains("stroke-width=\"1.2\""))
        assertTrue(svg.contains("stroke-linecap=\"round\""))
    }

    @Test
    fun writesFillNoneForTransparentFill() {
        val transparent = VectorSvgWriter.write(
            doc(path("p_001", "M0,0 L1,1", VectorStyle(fillColor = "#00000000", strokeColor = "#000000"))),
        )
        assertTrue(transparent.contains("fill=\"none\""))

        val absent = VectorSvgWriter.write(
            doc(path("p_002", "M0,0 L1,1", VectorStyle(strokeColor = "#000000"))),
        )
        // No fill specified on an Android path => emit fill="none" (SVG default is black).
        assertTrue(absent.contains("fill=\"none\""))
    }

    @Test
    fun writesStrokeOpacityAndFillOpacity() {
        val svg = VectorSvgWriter.write(
            doc(
                path(
                    "p_001", "M0,0 L1,1",
                    VectorStyle(
                        fillColor = "#112233",
                        fillAlpha = 0.5f,
                        strokeColor = "#445566",
                        strokeAlpha = 0.25f,
                        strokeWidth = 2f,
                    ),
                ),
            ),
        )
        assertTrue(svg.contains("fill-opacity=\"0.5\""))
        assertTrue(svg.contains("stroke-opacity=\"0.25\""))
    }

    @Test
    fun splitsAlphaHexColorIntoOpacity() {
        // #80RRGGBB -> fill #RRGGBB + fill-opacity ~0.502
        val svg = VectorSvgWriter.write(
            doc(path("p_001", "M0,0 L1,1", VectorStyle(fillColor = "#80112233"))),
        )
        assertTrue(svg.contains("fill=\"#112233\""))
        assertTrue(svg.contains("fill-opacity=\""))
    }

    @Test
    fun writesEvenOddFillRule() {
        val svg = VectorSvgWriter.write(
            doc(path("p_001", "M0,0 L1,1", VectorStyle(fillColor = "#000000", fillType = "evenOdd"))),
        )
        assertTrue(svg.contains("fill-rule=\"evenodd\""))
    }

    @Test
    fun writesGroupsRecursively() {
        val inner = VectorNode.GroupNode(
            VectorGroup(
                id = "g_002",
                name = "inner",
                translateX = 5f,
                translateY = 6f,
                rotation = 30f,
                pivotX = 12f,
                pivotY = 12f,
                children = listOf(path("p_001", "M0,0 L1,1", VectorStyle(fillColor = "#000000"))),
            ),
        )
        val outer = VectorNode.GroupNode(
            VectorGroup(id = "g_001", name = "outer", children = listOf(inner)),
        )
        val svg = VectorSvgWriter.write(doc(outer))
        assertTrue(svg.contains("<g id=\"outer\">"))
        assertTrue(svg.contains("id=\"inner\""))
        assertTrue(svg.contains("translate(5,6)"))
        assertTrue(svg.contains("rotate(30 12 12)"))
        // Closes both groups.
        assertEquals(2, Regex("</g>").findAll(svg).count())
    }

    @Test
    fun omitsStrokeWhenAbsent() {
        val svg = VectorSvgWriter.write(
            doc(path("p_001", "M0,0 L1,1", VectorStyle(fillColor = "#000000"))),
        )
        assertFalse(svg.contains("stroke="))
    }

    @Test
    fun svgOutputIsDeterministic() {
        val document = doc(
            path(
                "p_001", "M0,0 L10,0 L10,10 Z",
                VectorStyle(fillColor = "#123456", strokeColor = "#abcdef", strokeWidth = 2f),
                name = "shape",
            ),
        )
        assertEquals(VectorSvgWriter.write(document), VectorSvgWriter.write(document))
    }

    @Test
    fun unparsedPathProducesExportWarning() {
        val node = VectorNode.PathNode(
            VectorPath(
                id = "p_001",
                pathData = "totally-unparseable",
                commands = null,
                style = VectorStyle(fillColor = "#000000"),
            ),
        )
        val result = VectorSvgWriter.writeWithWarnings(doc(node))
        assertTrue(result.svg.contains("d=\"totally-unparseable\""))
        assertTrue(result.warnings.any { it.code == VectorWarning.Codes.SVG_EXPORT_PARTIAL })
    }
}
