package com.aichat.sandbox.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Phase 19 (Stage 4) — unit coverage for [AuthoredNormalizer]. Pure data
 * transforms over [EditOpsDoc]; no graphics, JVM-safe.
 */
class AuthoredNormalizerTest {

    private fun addPath(width: Float?): EditOp.AddPath = EditOp.AddPath(
        subpaths = listOf(
            EditOp.SubpathSpec(
                anchors = listOf(EditOp.AnchorSpec(0f, 0f), EditOp.AnchorSpec(10f, 0f)),
                closed = false,
            ),
        ),
        colorArgb = 0xFF000000.toInt(),
        fillArgb = null,
        width = width,
    )

    private fun addShape(width: Float?): EditOp.AddShape = EditOp.AddShape(
        shape = EditOp.ShapeSpec.Ellipse(5f, 5f, 4f, 4f),
        colorArgb = 0xFF000000.toInt(),
        fillArgb = null,
        width = width,
    )

    private fun docOf(vararg ops: EditOp) = EditOpsDoc(schema = 1, summary = "s", ops = ops.toList())

    @Test
    fun unifiesNearEqualWidthsToTheirMedian() {
        val doc = docOf(addPath(2.0f), addPath(2.4f), addShape(2.6f))
        val out = AuthoredNormalizer.unifyAuthoredStrokeWidths(doc)
        val widths = out.ops.map {
            when (it) {
                is EditOp.AddPath -> it.width
                is EditOp.AddShape -> it.width
                else -> null
            }
        }
        // Median of [2.0, 2.4, 2.6] = 2.4 → all three collapse to it.
        assertEquals(listOf(2.4f, 2.4f, 2.4f), widths)
    }

    @Test
    fun leavesDeliberateContrastUntouched() {
        // 6.0 / 1.0 = 6× spread → assumed intentional (thick frame + thin glyph).
        val doc = docOf(addPath(6.0f), addPath(1.0f))
        val out = AuthoredNormalizer.unifyAuthoredStrokeWidths(doc)
        assertSame(doc, out)
    }

    @Test
    fun treatsMissingWidthAsTheAuthoredDefault() {
        // null → default 2.0; explicit 2.4 is close, so both unify to the median.
        val doc = docOf(addPath(null), addPath(2.4f))
        val out = AuthoredNormalizer.unifyAuthoredStrokeWidths(doc)
        val widths = out.ops.map { (it as EditOp.AddPath).width }
        assertEquals(2.2f, widths[0]!!, 1e-4f)
        assertEquals(2.2f, widths[1]!!, 1e-4f)
        assertNotEquals(null, widths[0])
    }

    @Test
    fun noOpWhenFewerThanTwoAuthoredOutlines() {
        val doc = docOf(addPath(3.0f))
        assertSame(doc, AuthoredNormalizer.unifyAuthoredStrokeWidths(doc))
    }

    @Test
    fun ignoresNonAuthoringOps() {
        // Recolor isn't an authored outline; it must be left exactly as-is.
        val recolor = EditOp.Recolor(ids = listOf("s_001"), colorArgb = 0xFFFF0000.toInt())
        val doc = docOf(recolor)
        assertSame(doc, AuthoredNormalizer.unifyAuthoredStrokeWidths(doc))
    }
}
