package com.aichat.sandbox.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val backgroundStyle: String,   // "plain" | "dot" | "line" | "graph"
    val schemaVersion: Int,        // bump when stroke binary format changes
    // Stroke geometry bounds, used for thumbnails & initial viewport.
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val thumbnailPath: String?,    // cached PNG in app files dir
    val ocrText: String?,          // most-recent OCR pass for search / non-vision AI fallback
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // Phase 5.2 — serialized `EditorAction` stack (past + future), JSON.
    // Null on rows created before schema v6 and on notes that have never
    // had an editor session. See `EditorActionCodec`.
    val undoLogJson: String? = null,
    // Phase 9.1 — owning notebook (if any). Standalone notes carry null.
    // The notes-list excludes rows where this is non-null; the notebooks
    // list owns presentation for those.
    val notebookId: String? = null,
    // Icon mode — true for notes created as vector icons. Drives the square
    // artboard seed, the icon-tuned AI edit prompt, and the VectorDrawable
    // export defaults.
    val isIcon: Boolean = false,
    // Persisted viewport (pan offset + zoom) so a reopened icon restores the
    // exact view the user left, rather than getting "lost" on the canvas.
    // Null on non-icon notes and on icons saved before schema v16 — those
    // fall back to fitting the artboard on open. See NoteEditorScreen's
    // initial-fit effect and NoteEditorViewModel.save().
    val viewportOffsetX: Float? = null,
    val viewportOffsetY: Float? = null,
    val viewportScale: Float? = null,
)
