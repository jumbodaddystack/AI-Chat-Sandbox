package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.PathCodec
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 12.5 — paths in the AI canvas JSON: `p_` ids, anchors, closed, fill. */
class VectorCanvasJsonPathTest {

    private fun pathItem(id: String, payload: PathCodec.PathPayload) = NoteItem(
        id = id,
        noteId = "n",
        zIndex = 0,
        kind = PathCodec.KIND,
        tool = "path_pen",
        colorArgb = 0xFF000000.toInt(),
        baseWidthPx = 4f,
        payload = PathCodec.encode(payload),
    )

    private fun triangle(fill: Int = 0) = PathCodec.PathPayload(
        anchors = listOf(
            PathCodec.Anchor(0f, 0f, outDx = 10f, outDy = 0f),
            PathCodec.Anchor(100f, 0f),
            PathCodec.Anchor(50f, 80f),
        ),
        closed = true,
        fillArgb = fill,
    )

    @Test
    fun pathsGetShortIdsAndAnchors() {
        val items = listOf(
            pathItem("uuid-1", triangle()),
            pathItem("uuid-2", triangle(fill = 0xFF2463EB.toInt())),
        )
        val result = VectorCanvasJson.serialize(items, bounds = null, layers = emptyList())
        assertEquals("uuid-1", result.idMap["p_001"])
        assertEquals("uuid-2", result.idMap["p_002"])

        val root = JsonParser.parseString(result.json).asJsonObject
        val arr = root.getAsJsonArray("items")
        assertEquals(2, arr.size())
        val first = arr[0] as JsonObject
        assertEquals("path", first.get("kind").asString)
        assertTrue(first.get("closed").asBoolean)
        val anchors = first.getAsJsonArray("anchors")
        assertEquals(3, anchors.size())
        // Anchor rows are [x, y, inDx, inDy, outDx, outDy].
        val a0 = anchors[0].asJsonArray
        assertEquals(6, a0.size())
        assertEquals(10f, a0[4].asFloat, 1e-3f)
        // Fill only when set (and the colour is hex).
        assertFalse(first.has("fill"))
        val second = arr[1] as JsonObject
        assertEquals("#2463EB", second.get("fill").asString)
    }

    @Test
    fun openPathReportsClosedFalseAndNoFill() {
        val payload = triangle(fill = 0xFF2463EB.toInt()).copy(closed = false)
        val result = VectorCanvasJson.serialize(
            listOf(pathItem("uuid-3", payload)),
            bounds = null,
            layers = emptyList(),
        )
        val root = JsonParser.parseString(result.json).asJsonObject
        val obj = root.getAsJsonArray("items")[0] as JsonObject
        assertFalse(obj.get("closed").asBoolean)
        assertFalse(obj.has("fill"))
    }
}
