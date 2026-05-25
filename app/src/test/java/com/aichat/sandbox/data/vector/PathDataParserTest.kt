package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Phase 1 — path grammar coverage, compact-syntax edge cases, and round trips. */
class PathDataParserTest {

    @Test
    fun parseMoveAndLineCommands() {
        val result = PathDataParser.parse("M10 20 L30 40")
        assertEquals(
            listOf(
                PathCommand.MoveTo(10f, 20f),
                PathCommand.LineTo(30f, 40f),
            ),
            result.commands,
        )
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun parseCompactNegativeNumbers() {
        // No separators; the minus signs delimit the numbers.
        val result = PathDataParser.parse("M10-5L20-10")
        assertEquals(
            listOf(
                PathCommand.MoveTo(10f, -5f),
                PathCommand.LineTo(20f, -10f),
            ),
            result.commands,
        )
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun parseDecimalsAndExponentNotation() {
        val result = PathDataParser.parse("M.5 1e-3 L-2.4E+1 .25")
        assertEquals(
            listOf(
                PathCommand.MoveTo(0.5f, 0.001f),
                PathCommand.LineTo(-24f, 0.25f),
            ),
            result.commands,
        )
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun parseRepeatedMoveToAsLineTo() {
        // Extra coordinate pairs after M become implicit line commands.
        val result = PathDataParser.parse("M0 0 10 10 20 0")
        assertEquals(
            listOf(
                PathCommand.MoveTo(0f, 0f),
                PathCommand.LineTo(10f, 10f),
                PathCommand.LineTo(20f, 0f),
            ),
            result.commands,
        )
    }

    @Test
    fun parseRepeatedLineToGroups() {
        val result = PathDataParser.parse("L0 0 10 10")
        assertEquals(
            listOf(
                PathCommand.LineTo(0f, 0f),
                PathCommand.LineTo(10f, 10f),
            ),
            result.commands,
        )
    }

    @Test
    fun parseAllCommandTypesIncludingArcsAndCurves() {
        val result = PathDataParser.parse(
            "M0 0 H10 V10 C1 2 3 4 5 6 S7 8 9 10 Q1 1 2 2 T3 3 A5 5 0 1 0 8 8 Z",
        )
        val kinds = result.commands.map { it::class }
        assertEquals(
            listOf(
                PathCommand.MoveTo::class,
                PathCommand.HorizontalTo::class,
                PathCommand.VerticalTo::class,
                PathCommand.CubicTo::class,
                PathCommand.SmoothCubicTo::class,
                PathCommand.QuadTo::class,
                PathCommand.SmoothQuadTo::class,
                PathCommand.ArcTo::class,
                PathCommand.Close::class,
            ),
            kinds,
        )
        val arc = result.commands.filterIsInstance<PathCommand.ArcTo>().single()
        assertTrue(arc.largeArc)
        assertEquals(false, arc.sweep)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun relativeCommandsPreserveCasing() {
        val result = PathDataParser.parse("m1 1 l2 2")
        assertTrue((result.commands[0] as PathCommand.MoveTo).relative)
        assertTrue((result.commands[1] as PathCommand.LineTo).relative)
    }

    @Test
    fun formatRoundTripParsesAgain() {
        val original = "M0 0 10 10 C1 2 3 4 5 6 Q7 8 9 10 A2 2 0 1 1 12 12 Z"
        val first = PathDataParser.parse(original).commands
        val formatted = PathDataFormatter.format(first)
        val second = PathDataParser.parse(formatted).commands
        assertEquals(first, second)
    }

    @Test
    fun malformedPathReturnsWarningNotException() {
        val result = PathDataParser.parse("M10 10 X?? L20")
        // The leading move is still recovered.
        assertEquals(PathCommand.MoveTo(10f, 10f), result.commands.first())
        assertTrue(result.warnings.any { it.code == VectorWarning.Codes.MALFORMED_PATH_DATA })
    }

    @Test
    fun blankPathReturnsNoCommands() {
        val result = PathDataParser.parse("   ")
        assertTrue(result.commands.isEmpty())
    }

    @Test
    fun fuzzNeverThrows() {
        val alphabet = "MmLlHhVvCcSsQqTtAaZz0123456789.,-+eE \t".toCharArray()
        val rnd = Random(42)
        repeat(2000) {
            val len = rnd.nextInt(0, 40)
            val sb = StringBuilder(len)
            repeat(len) { sb.append(alphabet[rnd.nextInt(alphabet.size)]) }
            // Must not throw for any input.
            PathDataParser.parse(sb.toString())
        }
    }
}
