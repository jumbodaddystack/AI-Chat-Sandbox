package com.aichat.sandbox.data.vector

/**
 * Serializes a [VectorDocument] back into Android `VectorDrawable` XML.
 *
 * The export side of the Vector Art Tune-Up pipeline and the inverse of
 * [AndroidVectorDrawableParser]. Output is deterministic — attributes are
 * emitted in a fixed order and only when present — so tests can compare strings
 * directly or re-parse and assert equivalence. Path data is taken from
 * [PathDataFormatter] when the path has parsed commands; otherwise the original
 * `pathData` is emitted verbatim so unparsed paths survive a round trip.
 *
 * The synthetic [VectorDocument.root] group is not emitted as a `<group>`; its
 * children are written directly under `<vector>`.
 */
object AndroidVectorDrawableWriter {

    private const val INDENT = "    "

    fun write(document: VectorDocument): String {
        val sb = StringBuilder(1024)
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
        val vp = document.viewport
        sb.append(INDENT).append("android:width=\"").append(num(vp.widthDp)).append("dp\"\n")
        sb.append(INDENT).append("android:height=\"").append(num(vp.heightDp)).append("dp\"\n")
        sb.append(INDENT).append("android:viewportWidth=\"").append(num(vp.viewportWidth)).append("\"\n")
        sb.append(INDENT).append("android:viewportHeight=\"").append(num(vp.viewportHeight)).append("\">\n")

        for (child in document.root.children) {
            writeNode(sb, child, depth = 1)
        }

        sb.append("</vector>\n")
        return sb.toString()
    }

    private fun writeNode(sb: StringBuilder, node: VectorNode, depth: Int) {
        when (node) {
            is VectorNode.GroupNode -> writeGroup(sb, node.group, depth)
            is VectorNode.PathNode -> writePath(sb, node.path, depth)
        }
    }

    private fun writeGroup(sb: StringBuilder, group: VectorGroup, depth: Int) {
        val pad = INDENT.repeat(depth)
        sb.append(pad).append("<group")
        appendAttr(sb, "android:name", group.name)
        appendAttr(sb, "android:rotation", group.rotation)
        appendAttr(sb, "android:pivotX", group.pivotX)
        appendAttr(sb, "android:pivotY", group.pivotY)
        appendAttr(sb, "android:scaleX", group.scaleX)
        appendAttr(sb, "android:scaleY", group.scaleY)
        appendAttr(sb, "android:translateX", group.translateX)
        appendAttr(sb, "android:translateY", group.translateY)
        if (group.children.isEmpty()) {
            sb.append("/>\n")
            return
        }
        sb.append(">\n")
        for (child in group.children) writeNode(sb, child, depth + 1)
        sb.append(pad).append("</group>\n")
    }

    private fun writePath(sb: StringBuilder, path: VectorPath, depth: Int) {
        val pad = INDENT.repeat(depth)
        val data = path.commands?.takeIf { it.isNotEmpty() }
            ?.let { PathDataFormatter.format(it) }
            ?: path.pathData
        sb.append(pad).append("<path")
        appendAttr(sb, "android:name", path.name)
        appendAttr(sb, "android:pathData", data)
        val style = path.style
        appendAttr(sb, "android:fillColor", style.fillColor)
        appendAttr(sb, "android:fillAlpha", style.fillAlpha)
        appendAttr(sb, "android:fillType", style.fillType)
        appendAttr(sb, "android:strokeColor", style.strokeColor)
        appendAttr(sb, "android:strokeAlpha", style.strokeAlpha)
        appendAttr(sb, "android:strokeWidth", style.strokeWidth)
        appendAttr(sb, "android:strokeLineCap", style.strokeLineCap)
        appendAttr(sb, "android:strokeLineJoin", style.strokeLineJoin)
        appendAttr(sb, "android:strokeMiterLimit", style.strokeMiterLimit)
        sb.append("/>\n")
    }

    private fun appendAttr(sb: StringBuilder, name: String, value: String?) {
        if (value == null) return
        sb.append(' ').append(name).append("=\"").append(escapeXml(value)).append('"')
    }

    private fun appendAttr(sb: StringBuilder, name: String, value: Float?) {
        if (value == null) return
        sb.append(' ').append(name).append("=\"").append(num(value)).append('"')
    }

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
