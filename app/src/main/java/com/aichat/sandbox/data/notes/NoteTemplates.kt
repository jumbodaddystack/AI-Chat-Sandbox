package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteFrame
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.ConnectorCodec
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.StickyCodec
import com.aichat.sandbox.ui.components.notes.TextItemCodec

/**
 * Sub-phase 11.4 — code-defined starter templates.
 *
 * Each template stamps frames + shapes + stickies + text (+ connectors)
 * into a *fresh* note. Every [NoteItem] / [NoteFrame] is built with a new
 * UUID (the model defaults), so the StampPayloadCodec-style re-keying is
 * inherent — building twice never collides. Raw ARGB ints only, no
 * `android.graphics`, so the builders stay JVM-pure. No DB change: the
 * content seeds straight into the editor's in-memory state and persists
 * through the normal save path.
 */
enum class NoteTemplate(
    val id: String,
    val displayName: String,
    val description: String,
) {
    BRAINSTORM("brainstorm", "Brainstorm grid", "Topic header + a 3×2 grid of stickies"),
    KANBAN("kanban", "Kanban board", "To do / Doing / Done columns"),
    MIND_MAP("mindmap", "Mind map", "Central idea with four connected branches"),
    CORNELL("cornell", "Cornell notes", "Cue column, notes area, summary strip"),

    // Sub-phase 14.3 — gallery growth.
    WEEKLY("weekly", "Weekly planner", "Seven day columns, Monday through Sunday"),
    RETRO("retro", "Retrospective", "Went well / To improve / Action items"),
    SWOT("swot", "SWOT analysis", "Strengths, weaknesses, opportunities, threats"),
    STORYBOARD("storyboard", "Storyboard", "Six caption-lined panels"),
    ;

    companion object {
        fun fromId(id: String?): NoteTemplate? = entries.firstOrNull { it.id == id }
    }
}

object NoteTemplates {

    data class TemplateContent(
        val items: List<NoteItem>,
        val frames: List<NoteFrame>,
    )

    fun build(template: NoteTemplate, noteId: String, layerId: String?): TemplateContent =
        when (template) {
            NoteTemplate.BRAINSTORM -> Builder(noteId, layerId).brainstorm()
            NoteTemplate.KANBAN -> Builder(noteId, layerId).kanban()
            NoteTemplate.MIND_MAP -> Builder(noteId, layerId).mindMap()
            NoteTemplate.CORNELL -> Builder(noteId, layerId).cornell()
            NoteTemplate.WEEKLY -> Builder(noteId, layerId).weekly()
            NoteTemplate.RETRO -> Builder(noteId, layerId).retro()
            NoteTemplate.SWOT -> Builder(noteId, layerId).swot()
            NoteTemplate.STORYBOARD -> Builder(noteId, layerId).storyboard()
        }

    private class Builder(
        private val noteId: String,
        private val layerId: String?,
    ) {
        private val items = ArrayList<NoteItem>()
        private val frames = ArrayList<NoteFrame>()
        private var z = 0

        fun brainstorm(): TemplateContent {
            frame("Brainstorm", 0f, 0f, 1040f, 720f)
            text("Topic", 48f, 36f, fontSize = 40f)
            val fills = StickyCodec.PRESET_FILLS
            var fill = 0
            for (row in 0..1) {
                for (col in 0..2) {
                    sticky(
                        minX = 80f + col * 320f,
                        minY = 160f + row * 280f,
                        fillArgb = fills[fill++ % fills.size],
                    )
                }
            }
            return content()
        }

        fun kanban(): TemplateContent {
            frame("Kanban", 0f, 0f, 1240f, 820f)
            val headers = listOf("To do", "Doing", "Done")
            for (i in 0..2) {
                val left = 40f + i * 400f
                text(headers[i], left + 12f, 36f, fontSize = 32f)
                rect(left, 100f, left + 360f, 780f, fillArgb = COLUMN_FILL, cornerRadius = 12f)
            }
            sticky(64f, 128f, StickyCodec.PRESET_FILLS[0])
            sticky(64f, 312f, StickyCodec.PRESET_FILLS[5])
            return content()
        }

        fun mindMap(): TemplateContent {
            frame("Mind map", 0f, 0f, 1100f, 800f)
            // Reserve the bottom z slots for the connectors so the spokes
            // render *under* the bubbles, classic mind-map style — they're
            // created last (they need the ellipse ids) but stack first.
            val connectorZs = IntArray(4) { z++ }
            val centerId = ellipse(550f, 400f, 150f, 75f, fillArgb = CENTER_FILL)
            text("Central idea", 550f - 90f, 400f - 18f, fontSize = 30f)
            val branches = listOf(
                Triple(250f, 180f, "Branch 1"),
                Triple(850f, 180f, "Branch 2"),
                Triple(250f, 620f, "Branch 3"),
                Triple(850f, 620f, "Branch 4"),
            )
            for ((i, branch) in branches.withIndex()) {
                val (cx, cy, label) = branch
                val branchId = ellipse(cx, cy, 115f, 58f, fillArgb = BRANCH_FILL)
                text(label, cx - 60f, cy - 16f, fontSize = 26f)
                // Bound on both ends: dragging either ellipse carries the
                // connector along (render-time resolution). Fallback coords
                // are the current centres so the geometry stays sane if a
                // bubble is deleted.
                connector(
                    fromId = centerId, fromX = 550f, fromY = 400f,
                    toId = branchId, toX = cx, toY = cy,
                    zOverride = connectorZs[i],
                )
            }
            return content()
        }

        fun cornell(): TemplateContent {
            frame("Cornell notes", 0f, 0f, 900f, 1200f)
            text("Title", 24f, 28f, fontSize = 34f)
            line(0f, 100f, 900f, 100f)
            line(240f, 100f, 240f, 980f)
            line(0f, 980f, 900f, 980f)
            text("Cues", 24f, 120f, fontSize = 26f)
            text("Notes", 264f, 120f, fontSize = 26f)
            text("Summary", 24f, 1000f, fontSize = 26f)
            return content()
        }

        // ---- 14.3 gallery growth ----

        fun weekly(): TemplateContent {
            frame("Weekly planner", 0f, 0f, 1480f, 820f)
            val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            for (i in days.indices) {
                val left = 20f + i * 208f
                text(days[i], left + 12f, 36f, fontSize = 28f)
                rect(left, 100f, left + 192f, 780f, fillArgb = COLUMN_FILL, cornerRadius = 12f)
            }
            return content()
        }

        fun retro(): TemplateContent {
            frame("Retrospective", 0f, 0f, 1240f, 820f)
            val headers = listOf("Went well", "To improve", "Action items")
            val fills = listOf(RETRO_GOOD_FILL, RETRO_BAD_FILL, RETRO_ACTION_FILL)
            for (i in 0..2) {
                val left = 40f + i * 400f
                text(headers[i], left + 12f, 36f, fontSize = 32f)
                rect(left, 100f, left + 360f, 780f, fillArgb = fills[i], cornerRadius = 12f)
                sticky(left + 24f, 128f, StickyCodec.PRESET_FILLS[i * 2 % StickyCodec.PRESET_FILLS.size])
            }
            return content()
        }

        fun swot(): TemplateContent {
            frame("SWOT analysis", 0f, 0f, 1100f, 820f)
            text("SWOT", 24f, 24f, fontSize = 34f)
            val quadrants = listOf(
                Triple("Strengths", 40f to 100f, RETRO_GOOD_FILL),
                Triple("Weaknesses", 570f to 100f, RETRO_BAD_FILL),
                Triple("Opportunities", 40f to 470f, CENTER_FILL),
                Triple("Threats", 570f to 470f, THREAT_FILL),
            )
            for ((label, corner, fill) in quadrants) {
                val (left, top) = corner
                rect(left, top, left + 490f, top + 340f, fillArgb = fill, cornerRadius = 12f)
                text(label, left + 16f, top + 12f, fontSize = 28f)
            }
            return content()
        }

        fun storyboard(): TemplateContent {
            frame("Storyboard", 0f, 0f, 1240f, 940f)
            var scene = 1
            for (row in 0..1) {
                for (col in 0..2) {
                    val left = 40f + col * 400f
                    val top = 40f + row * 450f
                    rect(left, top, left + 360f, top + 280f, fillArgb = 0, cornerRadius = 4f)
                    text("Scene $scene", left, top + 296f, fontSize = 22f)
                    line(left, top + 360f, left + 360f, top + 360f)
                    line(left, top + 400f, left + 360f, top + 400f)
                    scene++
                }
            }
            return content()
        }

        // ---- primitives ----

        private fun content() = TemplateContent(items.toList(), frames.toList())

        private fun frame(name: String, minX: Float, minY: Float, maxX: Float, maxY: Float) {
            frames += NoteFrame(
                noteId = noteId,
                name = name,
                minX = minX, minY = minY, maxX = maxX, maxY = maxY,
                ordinal = frames.size,
            )
        }

        private fun add(
            kind: String,
            payload: ByteArray,
            colorArgb: Int,
            widthPx: Float,
            zOverride: Int? = null,
        ): String {
            val item = NoteItem(
                noteId = noteId,
                zIndex = zOverride ?: z++,
                kind = kind,
                tool = null,
                colorArgb = colorArgb,
                baseWidthPx = widthPx,
                payload = payload,
                layerId = layerId,
            )
            items += item
            return item.id
        }

        private fun text(body: String, x: Float, y: Float, fontSize: Float): String =
            add(
                kind = TextItemCodec.KIND,
                payload = TextItemCodec.encode(
                    TextItemCodec.newAt(x, y, body = body, fontSize = fontSize),
                ),
                colorArgb = TEXT_COLOR,
                widthPx = 0f,
            )

        private fun sticky(minX: Float, minY: Float, fillArgb: Int): String =
            add(
                kind = StickyCodec.KIND,
                payload = StickyCodec.encode(
                    StickyCodec.StickyPayload(
                        minX = minX, minY = minY,
                        maxX = minX + StickyCodec.DEFAULT_SIZE_WORLD,
                        maxY = minY + StickyCodec.DEFAULT_SIZE_WORLD,
                        fillArgb = fillArgb,
                        fontSize = StickyCodec.DEFAULT_FONT_SIZE,
                        body = "",
                    )
                ),
                colorArgb = 0,
                widthPx = 0f,
            )

        private fun rect(
            x0: Float, y0: Float, x1: Float, y1: Float,
            fillArgb: Int, cornerRadius: Float,
        ): String = add(
            kind = Shape.KIND,
            payload = ShapeCodec.encode(Shape.Rect(x0, y0, x1, y1, cornerRadius), fillArgb),
            colorArgb = OUTLINE_COLOR,
            widthPx = 2f,
        )

        private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, fillArgb: Int): String =
            add(
                kind = Shape.KIND,
                payload = ShapeCodec.encode(Shape.Ellipse(cx, cy, rx, ry), fillArgb),
                colorArgb = OUTLINE_COLOR,
                widthPx = 2f,
            )

        private fun line(x0: Float, y0: Float, x1: Float, y1: Float): String = add(
            kind = Shape.KIND,
            payload = ShapeCodec.encode(Shape.Line(x0, y0, x1, y1)),
            colorArgb = OUTLINE_COLOR,
            widthPx = 2f,
        )

        private fun connector(
            fromId: String, fromX: Float, fromY: Float,
            toId: String, toX: Float, toY: Float,
            zOverride: Int? = null,
        ): String = add(
            kind = ConnectorCodec.KIND,
            payload = ConnectorCodec.encode(
                ConnectorCodec.ConnectorPayload(
                    fromItemId = fromId,
                    fromAnchor = ConnectorCodec.ANCHOR_CENTER,
                    toItemId = toId,
                    toAnchor = ConnectorCodec.ANCHOR_CENTER,
                    x0 = fromX, y0 = fromY, x1 = toX, y1 = toY,
                    arrowAtEnd = false,
                )
            ),
            colorArgb = OUTLINE_COLOR,
            widthPx = 2f,
            zOverride = zOverride,
        )
    }

    private const val TEXT_COLOR: Int = 0xFF000000.toInt()
    private const val OUTLINE_COLOR: Int = 0xFF37474F.toInt()
    private const val COLUMN_FILL: Int = 0x14000000
    private const val CENTER_FILL: Int = 0x402463EB
    private const val BRANCH_FILL: Int = 0x40109F5C

    // 14.3 — retro / SWOT quadrant tints.
    private const val RETRO_GOOD_FILL: Int = 0x28109F5C
    private const val RETRO_BAD_FILL: Int = 0x28D62828
    private const val RETRO_ACTION_FILL: Int = 0x282463EB
    private const val THREAT_FILL: Int = 0x28FF9F1C
}
