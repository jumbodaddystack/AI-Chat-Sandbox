package com.aichat.sandbox.data.vector

/** Outcome of [PathDataParser.parse]: the commands understood plus any warnings. */
data class PathParseResult(
    val commands: List<PathCommand>,
    val warnings: List<VectorWarning> = emptyList(),
)

/**
 * Tolerant parser for `android:pathData` / SVG path strings.
 *
 * Feeds the Vector Art Tune-Up pipeline: turns a raw path string into typed
 * [PathCommand]s so later phases can measure, simplify, and rewrite geometry.
 * It is deliberately forgiving — malformed input never throws; instead the
 * recognizable prefix is parsed and the rest reported as [VectorWarning]s.
 *
 * Grammar notes handled here:
 *  - All common commands: `M L H V C S Q T A Z` in both absolute (upper) and
 *    relative (lower) forms.
 *  - Comma and/or whitespace separators, or none at all between a command and
 *    its first number.
 *  - Compact negatives that double as separators, e.g. `M10-5`.
 *  - Decimals, leading-dot decimals (`.5`), and exponents (`1e-3`, `-2.4E+5`).
 *  - Repeated coordinate groups after one command letter; for `M`/`m` the extra
 *    groups become implicit `L`/`l` line commands, matching the SVG spec.
 */
object PathDataParser {

    private const val COMMAND_CHARS = "mlhvcsqtaz"

    fun parse(pathData: String, nodeId: String? = null): PathParseResult {
        val commands = ArrayList<PathCommand>()
        val warnings = ArrayList<VectorWarning>()
        val s = pathData
        val n = s.length
        var i = 0

        fun skipSeparators() {
            while (i < n && (s[i] == ',' || s[i].isWhitespace())) i++
        }

        // Reads a float starting at the current cursor, advancing past it.
        // Returns null (leaving the cursor where it found no number) otherwise.
        fun readNumber(): Float? {
            skipSeparators()
            if (i >= n) return null
            val start = i
            if (s[i] == '+' || s[i] == '-') i++
            var sawDigit = false
            var sawDot = false
            while (i < n) {
                val c = s[i]
                if (c in '0'..'9') {
                    sawDigit = true; i++
                } else if (c == '.' && !sawDot) {
                    sawDot = true; i++
                } else break
            }
            if (sawDigit && i < n && (s[i] == 'e' || s[i] == 'E')) {
                val expStart = i
                i++
                if (i < n && (s[i] == '+' || s[i] == '-')) i++
                var expDigit = false
                while (i < n && s[i] in '0'..'9') { expDigit = true; i++ }
                if (!expDigit) i = expStart // malformed exponent: keep the mantissa
            }
            if (!sawDigit) {
                i = start
                return null
            }
            return s.substring(start, i).toFloatOrNull().also { if (it == null) i = start }
        }

        // Arc flags are single 0/1 digits and may be packed with no separators
        // (e.g. "01"). Read one such digit, falling back to a full number parse.
        fun readFlag(): Float? {
            skipSeparators()
            if (i >= n) return null
            return when (s[i]) {
                '0' -> { i++; 0f }
                '1' -> { i++; 1f }
                else -> readNumber()
            }
        }

        fun warnIncomplete(cmd: Char) {
            warnings += VectorWarning(
                VectorWarning.Codes.MALFORMED_PATH_DATA,
                "Incomplete coordinate group for command '$cmd'",
                nodeId,
            )
        }

        fun warnEmpty(cmd: Char) {
            warnings += VectorWarning(
                VectorWarning.Codes.MALFORMED_PATH_DATA,
                "Command '$cmd' had no coordinates",
                nodeId,
            )
        }

        // Reads `count` numbers; returns null if the group can't be completed.
        // A null result on the very first value is a clean end-of-group; a null
        // partway through is a malformed group and is reported.
        fun readGroup(cmd: Char, count: Int): FloatArray? {
            val out = FloatArray(count)
            for (k in 0 until count) {
                val v = readNumber()
                if (v == null) {
                    if (k != 0) warnIncomplete(cmd)
                    return null
                }
                out[k] = v
            }
            return out
        }

        while (i < n) {
            skipSeparators()
            if (i >= n) break
            val c = s[i]
            val lower = c.lowercaseChar()
            if (!c.isLetter() || lower !in COMMAND_CHARS) {
                warnings += VectorWarning(
                    VectorWarning.Codes.MALFORMED_PATH_DATA,
                    "Unexpected character '$c' at index $i",
                    nodeId,
                )
                i++
                continue
            }
            i++ // consume the command letter
            val rel = c.isLowerCase()
            when (lower) {
                'z' -> commands += PathCommand.Close(rel)
                'm' -> {
                    var first = true
                    var any = false
                    while (true) {
                        val g = readGroup(c, 2) ?: break
                        if (first) {
                            commands += PathCommand.MoveTo(g[0], g[1], rel)
                            first = false
                        } else {
                            commands += PathCommand.LineTo(g[0], g[1], rel)
                        }
                        any = true
                    }
                    if (!any) warnEmpty(c)
                }
                'l' -> {
                    var any = false
                    while (true) {
                        val g = readGroup(c, 2) ?: break
                        commands += PathCommand.LineTo(g[0], g[1], rel); any = true
                    }
                    if (!any) warnEmpty(c)
                }
                'h' -> {
                    var any = false
                    while (true) {
                        val v = readNumber() ?: break
                        commands += PathCommand.HorizontalTo(v, rel); any = true
                    }
                    if (!any) warnEmpty(c)
                }
                'v' -> {
                    var any = false
                    while (true) {
                        val v = readNumber() ?: break
                        commands += PathCommand.VerticalTo(v, rel); any = true
                    }
                    if (!any) warnEmpty(c)
                }
                'c' -> {
                    var any = false
                    while (true) {
                        val g = readGroup(c, 6) ?: break
                        commands += PathCommand.CubicTo(g[0], g[1], g[2], g[3], g[4], g[5], rel); any = true
                    }
                    if (!any) warnEmpty(c)
                }
                's' -> {
                    var any = false
                    while (true) {
                        val g = readGroup(c, 4) ?: break
                        commands += PathCommand.SmoothCubicTo(g[0], g[1], g[2], g[3], rel); any = true
                    }
                    if (!any) warnEmpty(c)
                }
                'q' -> {
                    var any = false
                    while (true) {
                        val g = readGroup(c, 4) ?: break
                        commands += PathCommand.QuadTo(g[0], g[1], g[2], g[3], rel); any = true
                    }
                    if (!any) warnEmpty(c)
                }
                't' -> {
                    var any = false
                    while (true) {
                        val g = readGroup(c, 2) ?: break
                        commands += PathCommand.SmoothQuadTo(g[0], g[1], rel); any = true
                    }
                    if (!any) warnEmpty(c)
                }
                'a' -> {
                    var any = false
                    while (true) {
                        val rx = readNumber() ?: break
                        val ry = readNumber()
                        val rot = if (ry != null) readNumber() else null
                        val laf = if (rot != null) readFlag() else null
                        val swf = if (laf != null) readFlag() else null
                        val x = if (swf != null) readNumber() else null
                        val y = if (x != null) readNumber() else null
                        if (ry == null || rot == null || laf == null || swf == null || x == null || y == null) {
                            warnIncomplete(c)
                            break
                        }
                        commands += PathCommand.ArcTo(rx, ry, rot, laf != 0f, swf != 0f, x, y, rel)
                        any = true
                    }
                    if (!any) warnEmpty(c)
                }
            }
        }

        return PathParseResult(commands, warnings)
    }
}
