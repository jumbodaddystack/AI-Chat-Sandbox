package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteFrame
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.ConnectorCodec
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64
import java.util.UUID

/**
 * Sub-phase 14.3 — lossless codec for user-saved note templates.
 *
 * The [StampPayloadCodec] shape (Base64 item payloads + metadata) extended
 * with frames and groupIds, because a template is a whole note layout, not
 * a positioned selection. [instantiate] is the template counterpart of the
 * builder in [NoteTemplates]: every item and frame comes back under a fresh
 * UUID, with connector `fromItemId`/`toItemId` bindings and `groupId`s
 * remapped through the old→new id map so bound connectors and groups stay
 * intact across instantiations (a stamp would drop the bindings).
 * Deliberately separate from the stamp codec so the two libraries can
 * evolve their schemas independently.
 */
object TemplatePayloadCodec {

    const val SCHEMA: Int = 1

    /** Build the JSON document persisted as `UserTemplate.payloadJson`. */
    fun encode(items: List<NoteItem>, frames: List<NoteFrame>): String {
        val root = JsonObject()
        root.addProperty("schema", SCHEMA)
        val itemArr = JsonArray(items.size)
        for (item in items) itemArr.add(encodeItem(item))
        root.add("items", itemArr)
        val frameArr = JsonArray(frames.size)
        for (frame in frames) frameArr.add(encodeFrame(frame))
        root.add("frames", frameArr)
        return root.toString()
    }

    /**
     * Parse [json] and re-key everything onto [noteId] / [layerId]. Returns
     * null on malformed input or a future schema (fail-soft, mirroring
     * [StampPayloadCodec.parse]).
     */
    fun instantiate(
        json: String,
        noteId: String,
        layerId: String?,
    ): NoteTemplates.TemplateContent? {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val schema = root.get("schema")?.asInt ?: return null
            if (schema != SCHEMA) return null
            val itemsArr = root.getAsJsonArray("items") ?: JsonArray()
            val rawItems = ArrayList<JsonObject>(itemsArr.size())
            for (el in itemsArr) (el as? JsonObject)?.let { rawItems += it }

            // First pass: fresh ids for every item and every distinct group,
            // so the second pass can remap references in any order.
            val idMap = HashMap<String, String>(rawItems.size)
            val groupMap = HashMap<String, String>()
            for (obj in rawItems) {
                idMap[obj.get("id").asString] = UUID.randomUUID().toString()
                obj.get("groupId")?.takeIf { !it.isJsonNull }?.asString?.let { g ->
                    groupMap.getOrPut(g) { UUID.randomUUID().toString() }
                }
            }

            val items = rawItems.map { obj -> decodeItem(obj, noteId, layerId, idMap, groupMap) }
            val framesArr = root.getAsJsonArray("frames") ?: JsonArray()
            val frames = ArrayList<NoteFrame>(framesArr.size())
            for (el in framesArr) {
                val obj = el as? JsonObject ?: continue
                frames += NoteFrame(
                    noteId = noteId,
                    name = obj.get("name").asString,
                    minX = obj.get("minX").asFloat,
                    minY = obj.get("minY").asFloat,
                    maxX = obj.get("maxX").asFloat,
                    maxY = obj.get("maxY").asFloat,
                    ordinal = obj.get("ordinal")?.asInt ?: frames.size,
                )
            }
            NoteTemplates.TemplateContent(items = items, frames = frames)
        } catch (_: Throwable) {
            null
        }
    }

    private fun encodeItem(item: NoteItem): JsonObject {
        val obj = JsonObject()
        obj.addProperty("id", item.id)
        obj.addProperty("kind", item.kind)
        if (item.tool != null) obj.addProperty("tool", item.tool)
        obj.addProperty("colorArgb", item.colorArgb)
        obj.addProperty("baseWidthPx", item.baseWidthPx)
        obj.addProperty("zIndex", item.zIndex)
        obj.addProperty("payload", Base64.getEncoder().encodeToString(item.payload))
        if (item.groupId != null) obj.addProperty("groupId", item.groupId)
        return obj
    }

    private fun encodeFrame(frame: NoteFrame): JsonObject {
        val obj = JsonObject()
        obj.addProperty("name", frame.name)
        obj.addProperty("minX", frame.minX)
        obj.addProperty("minY", frame.minY)
        obj.addProperty("maxX", frame.maxX)
        obj.addProperty("maxY", frame.maxY)
        obj.addProperty("ordinal", frame.ordinal)
        return obj
    }

    private fun decodeItem(
        obj: JsonObject,
        noteId: String,
        layerId: String?,
        idMap: Map<String, String>,
        groupMap: Map<String, String>,
    ): NoteItem {
        val kind = obj.get("kind").asString
        val toolEl = obj.get("tool")
        var payload = Base64.getDecoder().decode(obj.get("payload").asString)
        if (kind == ConnectorCodec.KIND) {
            // Remap bindings onto the freshly instantiated items; a binding
            // whose target wasn't part of the template falls back to free
            // (the stored fallback geometry keeps the segment in place).
            val connector = ConnectorCodec.decode(payload)
            payload = ConnectorCodec.encode(
                connector.copy(
                    fromItemId = connector.fromItemId?.let { idMap[it] },
                    toItemId = connector.toItemId?.let { idMap[it] },
                )
            )
        }
        val oldGroup = obj.get("groupId")?.takeIf { !it.isJsonNull }?.asString
        return NoteItem(
            id = idMap.getValue(obj.get("id").asString),
            noteId = noteId,
            zIndex = obj.get("zIndex")?.asInt ?: 0,
            kind = kind,
            tool = if (toolEl == null || toolEl.isJsonNull) null else toolEl.asString,
            colorArgb = obj.get("colorArgb").asInt,
            baseWidthPx = obj.get("baseWidthPx").asFloat,
            payload = payload,
            layerId = layerId,
            groupId = oldGroup?.let { groupMap[it] },
        )
    }
}
