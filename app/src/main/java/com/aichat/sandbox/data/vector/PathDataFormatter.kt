package com.aichat.sandbox.data.vector

import kotlin.math.roundToInt

/**
 * Renders parsed [PathCommand]s back into a compact `android:pathData` string.
 *
 * The inverse of [PathDataParser] in the Vector Art Tune-Up pipeline: the
 * writer uses it so a parsed-then-rewritten document round-trips. Floats are
 * formatted without scientific notation and with trailing zeros trimmed
 * (mirroring `NoteSvgExporter.fmt`), and the relative/absolute casing of each
 * command is preserved. Coordinate pairs are comma-separated; the command
 * letters themselves delimit groups, so no implicit-repetition ambiguity can
 * arise on re-parse.
 */
object PathDataFormatter {

    fun format(commands: List<PathCommand>): String {
        val sb = StringBuilder(commands.size * 6)
        for (cmd in commands) {
            when (cmd) {
                is PathCommand.MoveTo ->
                    sb.append(letter('M', cmd.relative)).append(pair(cmd.x, cmd.y))
                is PathCommand.LineTo ->
                    sb.append(letter('L', cmd.relative)).append(pair(cmd.x, cmd.y))
                is PathCommand.HorizontalTo ->
                    sb.append(letter('H', cmd.relative)).append(num(cmd.x))
                is PathCommand.VerticalTo ->
                    sb.append(letter('V', cmd.relative)).append(num(cmd.y))
                is PathCommand.CubicTo ->
                    sb.append(letter('C', cmd.relative))
                        .append(pair(cmd.x1, cmd.y1)).append(' ')
                        .append(pair(cmd.x2, cmd.y2)).append(' ')
                        .append(pair(cmd.x, cmd.y))
                is PathCommand.SmoothCubicTo ->
                    sb.append(letter('S', cmd.relative))
                        .append(pair(cmd.x2, cmd.y2)).append(' ')
                        .append(pair(cmd.x, cmd.y))
                is PathCommand.QuadTo ->
                    sb.append(letter('Q', cmd.relative))
                        .append(pair(cmd.x1, cmd.y1)).append(' ')
                        .append(pair(cmd.x, cmd.y))
                is PathCommand.SmoothQuadTo ->
                    sb.append(letter('T', cmd.relative)).append(pair(cmd.x, cmd.y))
                is PathCommand.ArcTo ->
                    sb.append(letter('A', cmd.relative))
                        .append(pair(cmd.rx, cmd.ry)).append(' ')
                        .append(num(cmd.xAxisRotation)).append(' ')
                        .append(if (cmd.largeArc) '1' else '0').append(' ')
                        .append(if (cmd.sweep) '1' else '0').append(' ')
                        .append(pair(cmd.x, cmd.y))
                is PathCommand.Close ->
                    sb.append(letter('Z', cmd.relative))
            }
        }
        return sb.toString()
    }

    private fun letter(base: Char, relative: Boolean): Char =
        if (relative) base.lowercaseChar() else base

    private fun pair(a: Float, b: Float): String = num(a) + "," + num(b)

    /** Float formatting with no scientific notation, trailing zeros trimmed. */
    internal fun num(value: Float): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        val rounded = (value * 1000f).roundToInt() / 1000f
        return if (rounded == rounded.toInt().toFloat()) {
            rounded.toInt().toString()
        } else {
            rounded.toString()
        }
    }
}
