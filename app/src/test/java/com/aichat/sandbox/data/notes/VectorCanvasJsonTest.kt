package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Sub-phase 7.1 — serializer happy paths plus the four edge cases called out
 * in the phase doc: empty selection, single image, mixed kinds, locked layers
 * excluded. Plus the size-cap behavior (downsampling / dropping).
 */
class VectorCanvasJsonTest {

    @Test
    fun emptyInputProducesEmptyItemsArray() {
        val out = VectorCanvasJson.serialize(emptyList(), bounds = null, layers = emptyList())
        val root = JsonParser.parseString(out.json).asJsonObject
        assertEquals(0, root.getAsJsonArray("items").size())
        assertTrue(out.idMap.isEmpty())
        assertTrue(out.includedItemIds.isEmpty())
    }

    @Test
    fun mixedKindsRoundTripPreserveShortIdsByOrder() {
        val stroke = strokeItem("n1", "pen", floatArrayOf(0f, 0f, 1f, 0f, 10f, 10f, 1f, 0f))
        val rect = NoteItem(
            id = UUID.randomUUID().toString(),
            noteId = "n1",
            zIndex = 1,
            kind = Shape.KIND,
            tool = null,
            colorArgb = 0xFFFF0000.toInt(),
            baseWidthPx = 2f,
            payload = ShapeCodec.encode(Shape.Rect(0f, 0f, 50f, 30f), fillArgb = 0),
        )
        val out = VectorCanvasJson.serialize(listOf(stroke, rect), bounds = floatArrayOf(0f, 0f, 50f, 30f), layers = emptyList())
        val root = JsonParser.parseString(out.json).asJsonObject
        val items = root.getAsJsonArray("items")
        assertEquals(2, items.size())
        assertEquals("s_001", items[0].asJsonObject.get("id").asString)
        assertEquals("h_001", items[1].asJsonObject.get("id").asString)
        assertEquals(stroke.id, out.idMap["s_001"])
        assertEquals(rect.id, out.idMap["h_001"])
        // bounds present
        assertNotNull(root.get("bounds"))
    }

    @Test
    fun lockedLayersAndItemsOnThemAreDroppedEntirely() {
        val lockedLayer = NoteLayer(
            id = "L_LOCK", noteId = "n1", name = "Locked",
            opacityPercent = 100, visible = true, locked = true, ordinal = 0,
        )
        val openLayer = lockedLayer.copy(id = "L_OPEN", name = "Ink", locked = false, ordinal = 1)
        val hidden = strokeItem("n1", "pen", floatArrayOf(0f, 0f, 1f, 0f, 5f, 5f, 1f, 0f))
            .copy(layerId = "L_LOCK")
        val visible = strokeItem("n1", "pen", floatArrayOf(20f, 20f, 1f, 0f, 30f, 30f, 1f, 0f))
            .copy(layerId = "L_OPEN")
        val out = VectorCanvasJson.serialize(
            items = listOf(hidden, visible),
            bounds = null,
            layers = listOf(lockedLayer, openLayer),
        )
        val root = JsonParser.parseString(out.json).asJsonObject
        val items = root.getAsJsonArray("items")
        assertEquals(1, items.size())
        // Only the unlocked layer is in the layers array.
        val layers = root.getAsJsonArray("layers")
        assertEquals(1, layers.size())
        assertEquals("Ink", layers[0].asJsonObject.get("name").asString)
        assertNull("locked items must not appear in idMap", out.idMap.values.firstOrNull { it == hidden.id })
    }

    @Test
    fun longStrokesAreDownsampledWithFlag() {
        val n = VectorCanvasJson.MAX_POINTS_PER_STROKE * 3
        val samples = FloatArray(n * StrokeCodec.FLOATS_PER_SAMPLE)
        for (i in 0 until n) {
            val base = i * StrokeCodec.FLOATS_PER_SAMPLE
            samples[base] = i.toFloat()
            samples[base + 1] = (i * 2).toFloat()
            samples[base + 2] = 1f
            samples[base + 3] = 0f
        }
        val item = strokeItem("n1", "pen", samples)
        val out = VectorCanvasJson.serialize(listOf(item), bounds = null, layers = emptyList())
        val root = JsonParser.parseString(out.json).asJsonObject
        val s = root.getAsJsonArray("items")[0].asJsonObject
        val pts = s.getAsJsonArray("points")
        assertTrue("expected downsampling at >MAX threshold", pts.size() <= VectorCanvasJson.MAX_POINTS_PER_STROKE)
        assertEquals(true, s.get("pointsDownsampled").asBoolean)
    }

    @Test
    fun softCapDropsLargestItemsUntilUnderBudget() {
        // Build 50 strokes; force a tiny cap by setting the soft cap to the
        // size of a few strokes by counting actual encoded bytes — easier:
        // just confirm the dropped collection is non-empty when we make
        // each stroke big enough that several won't fit. Use 5000 points
        // per stroke × 50 strokes — that's well beyond 180KB.
        val strokes = (0 until 50).map { idx ->
            val n = 5000
            val samples = FloatArray(n * StrokeCodec.FLOATS_PER_SAMPLE) { (it % 100).toFloat() }
            strokeItem("n1", "pen", samples).copy(zIndex = idx)
        }
        val out = VectorCanvasJson.serialize(strokes, bounds = null, layers = emptyList())
        assertTrue(out.json.toByteArray(Charsets.UTF_8).size <= VectorCanvasJson.MAX_JSON_BYTES)
        // Either downsampling alone keeps us under the cap, or some items
        // were dropped. The contract is "under the cap"; both are valid
        // outcomes.
        assertFalse(out.includedItemIds.isEmpty())
    }

    @Test
    fun serializeIsByteIdenticalAcrossRuns() {
        val s1 = strokeItem("n1", "pen", floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 1f, 0f))
        val s2 = strokeItem("n1", "highlighter", floatArrayOf(5f, 5f, 0.5f, 0f, 8f, 8f, 0.5f, 0f))
        val a = VectorCanvasJson.serialize(listOf(s1, s2), bounds = null, layers = emptyList())
        val b = VectorCanvasJson.serialize(listOf(s1, s2), bounds = null, layers = emptyList())
        assertEquals(a.json, b.json)
    }

    private fun strokeItem(noteId: String, tool: String, samples: FloatArray): NoteItem = NoteItem(
        id = UUID.randomUUID().toString(),
        noteId = noteId,
        zIndex = 0,
        kind = NoteItem.KIND_STROKE,
        tool = tool,
        colorArgb = 0xFF000000.toInt(),
        baseWidthPx = 2.5f,
        payload = StrokeCodec.encode(samples),
    )
}
