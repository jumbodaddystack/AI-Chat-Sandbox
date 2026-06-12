package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteFrame
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.ConnectorCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sub-phase 14.3 — pins the user-template wire format: items + frames
 * round-trip, fresh-id re-keying with connector-binding + groupId remap,
 * the dangling-binding drop, and fail-soft parsing.
 */
class TemplatePayloadCodecTest {

    private fun item(
        id: String,
        kind: String = "shape",
        payload: ByteArray = byteArrayOf(1, 2, 3),
        groupId: String? = null,
    ) = NoteItem(
        id = id,
        noteId = "source-note",
        zIndex = 5,
        kind = kind,
        tool = null,
        colorArgb = 0xFF112233.toInt(),
        baseWidthPx = 2.5f,
        payload = payload,
        layerId = "source-layer",
        groupId = groupId,
    )

    private fun frame(name: String, ordinal: Int) = NoteFrame(
        noteId = "source-note",
        name = name,
        minX = 0f, minY = 0f, maxX = 100f, maxY = 80f,
        ordinal = ordinal,
    )

    @Test
    fun roundTripPreservesContentUnderFreshIds() {
        val json = TemplatePayloadCodec.encode(
            items = listOf(item("a"), item("b", groupId = "g1")),
            frames = listOf(frame("Board", 0), frame("Detail", 1)),
        )
        val content = TemplatePayloadCodec.instantiate(json, "new-note", "new-layer")!!
        assertEquals(2, content.items.size)
        assertEquals(2, content.frames.size)
        for (instantiated in content.items) {
            assertEquals("new-note", instantiated.noteId)
            assertEquals("new-layer", instantiated.layerId)
            assertEquals(5, instantiated.zIndex)
            assertEquals(0xFF112233.toInt(), instantiated.colorArgb)
            assertEquals(2.5f, instantiated.baseWidthPx, 1e-6f)
            assertArrayEquals(byteArrayOf(1, 2, 3), instantiated.payload)
            assertNotEquals("a", instantiated.id)
            assertNotEquals("b", instantiated.id)
        }
        assertEquals("Board", content.frames[0].name)
        assertEquals(1, content.frames[1].ordinal)
        assertEquals("new-note", content.frames[0].noteId)
    }

    @Test
    fun instantiatingTwiceNeverCollides() {
        val json = TemplatePayloadCodec.encode(listOf(item("a")), listOf(frame("F", 0)))
        val first = TemplatePayloadCodec.instantiate(json, "n1", null)!!
        val second = TemplatePayloadCodec.instantiate(json, "n2", null)!!
        val firstIds = first.items.map { it.id } + first.frames.map { it.id }
        val secondIds = second.items.map { it.id } + second.frames.map { it.id }
        assertTrue((firstIds intersect secondIds.toSet()).isEmpty())
    }

    @Test
    fun connectorBindingsRemapOntoTheFreshIds() {
        val connectorPayload = ConnectorCodec.encode(
            ConnectorCodec.ConnectorPayload(
                fromItemId = "a", fromAnchor = ConnectorCodec.ANCHOR_E,
                toItemId = "b", toAnchor = ConnectorCodec.ANCHOR_W,
                x0 = 0f, y0 = 0f, x1 = 50f, y1 = 0f,
            )
        )
        val json = TemplatePayloadCodec.encode(
            items = listOf(
                item("a"), item("b"),
                item("c", kind = ConnectorCodec.KIND, payload = connectorPayload),
            ),
            frames = emptyList(),
        )
        val content = TemplatePayloadCodec.instantiate(json, "new-note", null)!!
        val newIds = content.items.mapTo(HashSet()) { it.id }
        val connector = content.items.first { it.kind == ConnectorCodec.KIND }
        val decoded = ConnectorCodec.decode(connector.payload)
        assertTrue(decoded.fromItemId in newIds)
        assertTrue(decoded.toItemId in newIds)
        assertNotEquals("a", decoded.fromItemId)
        assertNotEquals("b", decoded.toItemId)
    }

    @Test
    fun danglingConnectorBindingDropsToFree() {
        val connectorPayload = ConnectorCodec.encode(
            ConnectorCodec.ConnectorPayload(
                fromItemId = "a", fromAnchor = ConnectorCodec.ANCHOR_E,
                toItemId = "not-in-template", toAnchor = ConnectorCodec.ANCHOR_W,
                x0 = 0f, y0 = 0f, x1 = 50f, y1 = 0f,
            )
        )
        val json = TemplatePayloadCodec.encode(
            items = listOf(item("a"), item("c", kind = ConnectorCodec.KIND, payload = connectorPayload)),
            frames = emptyList(),
        )
        val content = TemplatePayloadCodec.instantiate(json, "new-note", null)!!
        val decoded = ConnectorCodec.decode(
            content.items.first { it.kind == ConnectorCodec.KIND }.payload,
        )
        assertNull(decoded.toItemId)
        assertEquals(50f, decoded.x1, 1e-4f)
        assertTrue(decoded.fromItemId in content.items.map { it.id })
    }

    @Test
    fun groupIdsRemapConsistently() {
        val json = TemplatePayloadCodec.encode(
            items = listOf(
                item("a", groupId = "g1"),
                item("b", groupId = "g1"),
                item("c", groupId = "g2"),
                item("d"),
            ),
            frames = emptyList(),
        )
        val content = TemplatePayloadCodec.instantiate(json, "new-note", null)!!
        val groups = content.items.map { it.groupId }
        assertEquals(groups[0], groups[1])
        assertNotEquals(groups[0], groups[2])
        assertNull(groups[3])
        assertNotEquals("g1", groups[0])
        assertNotEquals("g2", groups[2])
    }

    @Test
    fun malformedJsonParsesNull() {
        assertNull(TemplatePayloadCodec.instantiate("", "n", null))
        assertNull(TemplatePayloadCodec.instantiate("{}", "n", null))
        assertNull(TemplatePayloadCodec.instantiate("not even json", "n", null))
    }

    @Test
    fun futureSchemaParsesNull() {
        val json = """{"schema": 99, "items": [], "frames": []}"""
        assertNull(TemplatePayloadCodec.instantiate(json, "n", null))
    }
}
