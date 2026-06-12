package com.aichat.sandbox.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Sub-phase 14.3 — a user-saved note template ("save this note as a
 * template"). App-scoped, like [Stamp]: the user builds a personal gallery
 * that the new-note flow lists alongside the code-defined
 * [com.aichat.sandbox.data.notes.NoteTemplate] built-ins.
 *
 * [payloadJson] is a [com.aichat.sandbox.data.notes.TemplatePayloadCodec]
 * blob (items + frames). Instantiation re-keys everything to fresh UUIDs —
 * connector bindings and groupIds included — so two notes from the same
 * template never share ids. No thumbnail: the template menu is a text
 * dropdown, and rendering one would drag Android rasterization into an
 * otherwise pure save path.
 */
@Entity(
    tableName = "user_templates",
    indices = [Index("lastUsedAt")],
)
data class UserTemplate(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val payloadJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null,
)
