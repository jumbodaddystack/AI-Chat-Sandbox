package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 5 (sub-feature 1) — dash + variable-width baking through the writers. */
class StrokeStyleExportTest {

    private fun docWith(style: VectorStyle, commands: List<PathCommand>): VectorDocument =
        VectorDocument(
            viewport = VectorViewport(24f, 24f, 24f, 24f),
            root = VectorGroup(
                id = "root",
                children = listOf(
                    VectorNode.PathNode(
                        VectorPath(id = "p0", pathData = PathDataFormatter.format(commands), commands = commands, style = style),
                    ),
                ),
            ),
        )

    private val line = listOf(PathCommand.MoveTo(0f, 0f), PathCommand.LineTo(10f, 0f))

    @Test
    fun svg_emitsNativeDashArrayAndOffset() {
        val doc = docWith(
            VectorStyle(strokeColor = "#FF0000", strokeWidth = 2f, strokeDashArray = listOf(2f, 2f), strokeDashOffset = 1f),
            line,
        )
        val svg = VectorSvgWriter.write(doc)
        assertTrue(svg.contains("stroke-dasharray=\"2,2\""))
        assertTrue(svg.contains("stroke-dashoffset=\"1\""))
    }

    @Test
    fun android_bakesDashIntoChoppedGeometryWithWarning() {
        val doc = docWith(
            VectorStyle(strokeColor = "#FF0000", strokeWidth = 2f, strokeDashArray = listOf(2f, 2f)),
            line,
        )
        // The baker reports the bake; VectorDrawable has no dash attribute.
        val (_, warnings) = StrokeExportBaker.bakeDashes(doc)
        assertEquals(1, warnings.size)
        assertEquals(VectorWarning.Codes.STROKE_DASH_BAKED, warnings.single().code)

        val xml = AndroidVectorDrawableWriter.write(doc)
        assertFalse(xml.contains("dash"))
        // Three "on" runs → three M commands in the single baked path.
        val pathData = Regex("android:pathData=\"([^\"]*)\"").find(xml)!!.groupValues[1]
        assertEquals(3, pathData.count { it == 'M' })
    }

    @Test
    fun variableWidth_bakesToFilledOutlineInBothWriters() {
        val profile = VariableWidthProfile(listOf(WidthStop(0f, 2f), WidthStop(1f, 6f)))
        val doc = docWith(
            VectorStyle(strokeColor = "#3366CC", strokeWidth = 2f, variableWidth = profile),
            line,
        )
        val svg = VectorSvgWriter.write(doc)
        // The stroke became a fill; no stroke attribute, fill carries the stroke color.
        assertTrue(svg.contains("fill=\"#3366CC\""))
        assertFalse(svg.contains("stroke=\"#3366CC\""))

        val xml = AndroidVectorDrawableWriter.write(doc)
        assertTrue(xml.contains("android:fillColor=\"#3366CC\""))
        assertFalse(xml.contains("android:strokeColor"))
    }

    @Test
    fun plainStroke_unaffected_noBaking() {
        val doc = docWith(VectorStyle(strokeColor = "#000000", strokeWidth = 1f), line)
        val xml = AndroidVectorDrawableWriter.write(doc)
        assertTrue(xml.contains("android:strokeColor=\"#000000\""))
        val (baked, warnings) = StrokeExportBaker.bakeDashes(doc)
        assertTrue(warnings.isEmpty())
        assertEquals(doc, baked)
    }
}
