package com.aichat.sandbox.ui.components.notes

import com.aichat.sandbox.data.vector.VectorStyle
import com.aichat.sandbox.data.vector.edit.AnchorType
import com.aichat.sandbox.data.vector.edit.ControlPoint
import com.aichat.sandbox.data.vector.edit.EditAnchor
import com.aichat.sandbox.data.vector.edit.EditSubpath
import com.aichat.sandbox.data.vector.edit.EditablePath
import com.aichat.sandbox.data.vector.edit.boolean.PathBoolean

/**
 * Sub-phase 13.1 — boolean ops for notes paths. Phase 16.1 — multi-subpath.
 *
 * Bridges [PathCodec.PathPayload]s to the vector tune-up lane's pure
 * flatten → clip → refit pipeline ([PathBoolean] / `PolygonClipper` /
 * `CurveRefit`) and back. The master plan suggested
 * `android.graphics.Path.op()`, but reading the result's geometry back out
 * of a framework `Path` needs API-34 `PathIterator` (or a new androidx
 * dependency) and is untestable on the JVM; the in-repo clipper is proven,
 * pure Kotlin, and already refits rings to smooth/corner cubic anchors.
 *
 * Booleans are area ops: open inputs are implicitly closed by the
 * flattener and every result ring comes back closed. Since 16.1 the result
 * is **one payload carrying every ring as a subpath** (holes included, with
 * the clipper's fill rule), so a subtracted donut actually punches through
 * instead of rendering as a filled blob on top.
 */
object PathBooleanBridge {

    /**
     * Combine per-item payload lists under [op]. The first element is the
     * **subject** (Subtract removes every later item from it). Items whose
     * payloads are all degenerate (< 2 anchors) are dropped; fewer than two
     * usable inputs — or an empty result (e.g. a disjoint intersect) —
     * return null. The result carries geometry + fill rule only; the caller
     * stamps fill / stroke style.
     */
    fun combine(
        itemPayloads: List<List<PathCodec.PathPayload>>,
        op: PathBoolean.Op,
    ): PathCodec.PathPayload? {
        val paths = itemPayloads.mapIndexedNotNull { i, payloads ->
            // 16.1 — expand *every* subpath of every payload into the
            // clipper input; reading only subpath 0 here would silently
            // drop the holes of a previous boolean result being re-combined.
            val subs = payloads.flatMapIndexed { j, p -> toSubpaths(p, "in$i.s$j") }
            if (subs.isEmpty()) {
                null
            } else {
                EditablePath(pathId = "in$i", subpaths = subs, style = VectorStyle())
            }
        }
        if (paths.size < 2) return null
        val result = PathBoolean.combine(paths, op, "bool") ?: return null
        return fromEditablePath(result)
    }

    /** 16.1 — every non-degenerate subpath of [payload] as an [EditSubpath]. */
    fun toSubpaths(payload: PathCodec.PathPayload, idPrefix: String): List<EditSubpath> =
        payload.subpaths.mapIndexedNotNull { k, sub ->
            if (sub.anchors.size < 2) null else toSubpath(sub, "$idPrefix.p$k")
        }

    /** Relative handle deltas → absolute control points (zero delta = no handle). */
    fun toSubpath(sub: PathCodec.Subpath, id: String): EditSubpath {
        val anchors = sub.anchors.mapIndexed { i, a ->
            EditAnchor(
                id = "$id.a$i",
                x = a.x,
                y = a.y,
                inHandle = if (a.inDx != 0f || a.inDy != 0f) {
                    ControlPoint(a.x + a.inDx, a.y + a.inDy)
                } else {
                    null
                },
                outHandle = if (a.outDx != 0f || a.outDy != 0f) {
                    ControlPoint(a.x + a.outDx, a.y + a.outDy)
                } else {
                    null
                },
                type = when (a.type) {
                    PathCodec.TYPE_SMOOTH -> AnchorType.SMOOTH
                    PathCodec.TYPE_SYMMETRIC -> AnchorType.SYMMETRIC
                    else -> AnchorType.CORNER
                },
            )
        }
        return EditSubpath(id = id, anchors = anchors, closed = sub.closed)
    }

    /**
     * 16.1 — an [EditablePath] as **one** multi-subpath payload, fill rule
     * lifted from the path's style (`fillType == "evenOdd"`). The bridging
     * primitive shared by boolean results and document → note import.
     * Returns null when every subpath is degenerate.
     */
    fun fromEditablePath(path: EditablePath): PathCodec.PathPayload? {
        val subs = path.subpaths
            .filter { it.anchors.size >= 2 }
            .map { subpathOf(it) }
        if (subs.isEmpty()) return null
        return PathCodec.PathPayload(
            subpaths = subs,
            fillRule = if (path.style.fillType.equals("evenOdd", ignoreCase = true)) {
                PathCodec.FILL_RULE_EVEN_ODD
            } else {
                PathCodec.FILL_RULE_NON_ZERO
            },
        )
    }

    /** Absolute control points → relative handle deltas. */
    fun subpathOf(sub: EditSubpath): PathCodec.Subpath = PathCodec.Subpath(
        anchors = sub.anchors.map { a ->
            PathCodec.Anchor(
                x = a.x,
                y = a.y,
                inDx = (a.inHandle?.x ?: a.x) - a.x,
                inDy = (a.inHandle?.y ?: a.y) - a.y,
                outDx = (a.outHandle?.x ?: a.x) - a.x,
                outDy = (a.outHandle?.y ?: a.y) - a.y,
                type = when (a.type) {
                    AnchorType.SMOOTH -> PathCodec.TYPE_SMOOTH
                    AnchorType.SYMMETRIC -> PathCodec.TYPE_SYMMETRIC
                    else -> PathCodec.TYPE_CORNER
                },
            )
        },
        closed = sub.closed,
    )
}
