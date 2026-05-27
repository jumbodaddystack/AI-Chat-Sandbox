package com.aichat.sandbox.data.vector

/** Outcome of [VectorSvgWriter.writeWithWarnings]: the SVG plus export notes. */
data class VectorSvgWriteResult(
    val svg: String,
    val warnings: List<VectorWarning>,
)

/**
 * Serializes a [VectorDocument] into a standalone SVG 1.1 string (Phase 9).
 *
 * The SVG export side of the Vector Art Tune-Up pipeline. Output is
 * deterministic — attributes are emitted in a fixed order, only when present —
 * so tests can pin the wire format. It writes from the parsed
 * [VectorDocument] (never by rewriting stored Android XML text), walks groups
 * recursively into `<g transform="…">`, emits each path's data (formatted from
 * parsed commands, or the raw [VectorPath.pathData] verbatim when unparsed),
 * and maps the Android fill/stroke style onto the equivalent SVG presentation
 * attributes. Android colors carrying an alpha channel are split into a 6-digit
 * `#RRGGBB` value plus a `fill-opacity`/`stroke-opacity`.
 *
 * Group transforms (translate / rotate-with-pivot / scale-with-pivot) are
 * represented faithfully. Paths whose data could not be parsed are emitted
 * as-is and surfaced as a single [VectorWarning.Codes.SVG_EXPORT_PARTIAL] note
 * by [writeWithWarnings] so the caller can tell the user the export may be
 * approximate.
 */
object VectorSvgWriter {

    private const val INDENT = "  "
    private const val SVG_NS = "http://www.w3.org/2000/svg"

    fun write(document: VectorDocument): String = writeWithWarnings(document).svg

    fun writeWithWarnings(document: VectorDocument): VectorSvgWriteResult {
        val warnings = ArrayList<VectorWarning>()
        val sb = StringBuilder(1024)
        val vp = document.viewport

        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n")
        sb.append("<svg xmlns=\"").append(SVG_NS).append("\"")
            .append(" width=\"").append(num(vp.widthDp)).append('"')
            .append(" height=\"").append(num(vp.heightDp)).append('"')
            .append(" viewBox=\"0 0 ").append(num(vp.viewportWidth)).append(' ')
            .append(num(vp.viewportHeight)).append("\">\n")

        for (child in document.root.children) {
            writeNode(sb, child, depth = 1, warnings = warnings)
        }

        sb.append("</svg>\n")
        return VectorSvgWriteResult(sb.toString(), warnings)
    }

    private fun writeNode(
        sb: StringBuilder,
        node: VectorNode,
        depth: Int,
        warnings: MutableList<VectorWarning>,
    ) {
        when (node) {
            is VectorNode.GroupNode -> writeGroup(sb, node.group, depth, warnings)
            is VectorNode.PathNode -> writePath(sb, node.path, depth, warnings)
        }
    }

    private fun writeGroup(
        sb: StringBuilder,
        group: VectorGroup,
        depth: Int,
        warnings: MutableList<VectorWarning>,
    ) {
        val pad = INDENT.repeat(depth)
        sb.append(pad).append("<g")
        group.name?.let { sb.append(" id=\"").append(escapeXml(it)).append('"') }
        transformOf(group)?.let { sb.append(" transform=\"").append(it).append('"') }
        if (group.children.isEmpty()) {
            sb.append("/>\n")
            return
        }
        sb.append(">\n")
        for (child in group.children) writeNode(sb, child, depth + 1, warnings)
        sb.append(pad).append("</g>\n")
    }

    private fun writePath(
        sb: StringBuilder,
        path: VectorPath,
        depth: Int,
        warnings: MutableList<VectorWarning>,
    ) {
        val pad = INDENT.repeat(depth)
        val data = path.commands?.takeIf { it.isNotEmpty() }
            ?.let { PathDataFormatter.format(it) }
            ?: path.pathData
        if (path.commands == null && path.pathData.isNotBlank()) {
            warnings += VectorWarning(
                VectorWarning.Codes.SVG_EXPORT_PARTIAL,
                "Path data could not be parsed; exported verbatim and may not render identically.",
                path.id,
            )
        }
        val style = path.style

        sb.append(pad).append("<path")
        (path.name ?: path.id).let { sb.append(" id=\"").append(escapeXml(it)).append('"') }
        sb.append(" d=\"").append(escapeXml(data)).append('"')

        // Fill: Android paths default to no fill, so emit fill="none" when absent
        // to stop SVG renderers from defaulting to solid black.
        val fill = resolveColor(style.fillColor)
        if (fill == null) {
            sb.append(" fill=\"none\"")
        } else {
            sb.append(" fill=\"").append(fill.hex).append('"')
            val opacity = fill.opacity * (style.fillAlpha ?: 1f)
            if (opacity < 1f) sb.append(" fill-opacity=\"").append(num(opacity)).append('"')
        }
        if (style.fillType.equals("evenOdd", ignoreCase = true)) {
            sb.append(" fill-rule=\"evenodd\"")
        }

        // Stroke: omit entirely when absent/transparent (SVG default is no stroke).
        val stroke = resolveColor(style.strokeColor)
        if (stroke != null) {
            sb.append(" stroke=\"").append(stroke.hex).append('"')
            val opacity = stroke.opacity * (style.strokeAlpha ?: 1f)
            if (opacity < 1f) sb.append(" stroke-opacity=\"").append(num(opacity)).append('"')
            style.strokeWidth?.let { sb.append(" stroke-width=\"").append(num(it)).append('"') }
            style.strokeLineCap?.let { sb.append(" stroke-linecap=\"").append(escapeXml(it)).append('"') }
            style.strokeLineJoin?.let { sb.append(" stroke-linejoin=\"").append(escapeXml(it)).append('"') }
            style.strokeMiterLimit?.let { sb.append(" stroke-miterlimit=\"").append(num(it)).append('"') }
        }
        sb.append("/>\n")
    }

    /**
     * Builds an SVG `transform` value matching Android group semantics:
     * translate, then rotate around the pivot, then scale around the pivot.
     * Returns null when the group carries no transform.
     */
    private fun transformOf(group: VectorGroup): String? {
        val parts = ArrayList<String>()
        val tx = group.translateX ?: 0f
        val ty = group.translateY ?: 0f
        if (tx != 0f || ty != 0f) {
            parts += "translate(${num(tx)},${num(ty)})"
        }
        val px = group.pivotX ?: 0f
        val py = group.pivotY ?: 0f
        val rotation = group.rotation
        if (rotation != null && rotation != 0f) {
            parts += if (px != 0f || py != 0f) {
                "rotate(${num(rotation)} ${num(px)} ${num(py)})"
            } else {
                "rotate(${num(rotation)})"
            }
        }
        val sx = group.scaleX
        val sy = group.scaleY
        if (sx != null || sy != null) {
            val rsx = sx ?: 1f
            val rsy = sy ?: 1f
            if (rsx != 1f || rsy != 1f) {
                if (px != 0f || py != 0f) {
                    parts += "translate(${num(px)},${num(py)})"
                    parts += "scale(${num(rsx)},${num(rsy)})"
                    parts += "translate(${num(-px)},${num(-py)})"
                } else {
                    parts += "scale(${num(rsx)},${num(rsy)})"
                }
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /** A 6-digit `#RRGGBB` color plus its [0,1] opacity, or null for none/transparent. */
    private data class SvgColor(val hex: String, val opacity: Float)

    /**
     * Normalizes an Android color literal into an SVG-friendly `#RRGGBB` value
     * plus an opacity. Returns null for an absent, `"none"`, or fully
     * transparent color so the caller can emit `fill="none"` / omit the stroke.
     * Non-hex literals (named colors, gradient refs) pass through opaque.
     */
    private fun resolveColor(raw: String?): SvgColor? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.equals("none", ignoreCase = true)) return null
        if (!trimmed.startsWith("#")) {
            // Named color / url(...) reference — leave as-is, fully opaque.
            return SvgColor(trimmed, 1f)
        }
        val hex = trimmed.removePrefix("#")
        return when (hex.length) {
            8 -> {
                val a = hex.substring(0, 2).toIntOrNull(16) ?: return SvgColor("#000000", 1f)
                if (a == 0) return null
                SvgColor("#" + hex.substring(2).uppercase(), a / 255f)
            }
            6 -> SvgColor("#" + hex.uppercase(), 1f)
            4 -> {
                val a = expand(hex[0])
                if (a == 0) return null
                SvgColor("#" + expandHex(hex.substring(1)), a / 255f)
            }
            3 -> SvgColor("#" + expandHex(hex), 1f)
            else -> SvgColor(trimmed, 1f)
        }
    }

    private fun expand(c: Char): Int = (c.toString().toIntOrNull(16) ?: 0).let { it * 16 + it }

    private fun expandHex(rgb: String): String = buildString {
        for (c in rgb) append(c).append(c)
    }.uppercase()

    private fun escapeXml(s: String): String = buildString(s.length + 8) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    private fun num(value: Float): String = PathDataFormatter.num(value)
}
