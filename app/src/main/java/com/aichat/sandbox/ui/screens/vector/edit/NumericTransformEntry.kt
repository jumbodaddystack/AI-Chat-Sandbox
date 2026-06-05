package com.aichat.sandbox.ui.screens.vector.edit

/**
 * Phase 5 (sub-feature 4) — numeric transform entry for the node editor.
 *
 * A parsed bundle of the optional transform fields the inspector exposes. Each
 * field is nullable so a blank/garbage text box simply contributes nothing,
 * rather than forcing the user to fill every box. Pure value type.
 */
data class TransformEntry(
    val dx: Float? = null,
    val dy: Float? = null,
    val scale: Float? = null,
    val rotateDeg: Float? = null,
) {
    /** True when every field is absent — nothing for the editor to apply. */
    val isEmpty: Boolean get() = dx == null && dy == null && scale == null && rotateDeg == null

    /**
     * The translation half of this entry as a [VectorEditAction.MoveSelection],
     * or null when neither dx nor dy was supplied. (Scale/rotate have no reducer
     * action yet — they are carried for the UI/future and ignored here.)
     */
    fun toMoveSelection(): VectorEditAction.MoveSelection? {
        if (dx == null && dy == null) return null
        return VectorEditAction.MoveSelection(dx ?: 0f, dy ?: 0f)
    }
}

/**
 * Parses free-text inspector fields into a [TransformEntry]. Pure — feed it the
 * raw strings and dispatch the resulting [VectorEditAction.MoveSelection] exactly
 * as a drag would. The Compose layer owns nothing but the text boxes.
 */
object NumericTransform {

    /**
     * Build a [TransformEntry] from the four text fields. Each field parses to a
     * nullable Float (blank or non-numeric → null). Returns null only when every
     * field is empty/garbage, so callers can treat null as "nothing to do".
     */
    fun parse(
        dxText: String,
        dyText: String,
        scaleText: String,
        rotText: String,
    ): TransformEntry? {
        val entry = TransformEntry(
            dx = parseField(dxText),
            dy = parseField(dyText),
            scale = parseField(scaleText),
            rotateDeg = parseField(rotText),
        )
        return if (entry.isEmpty) null else entry
    }

    /** Trim and parse a single field; blank or non-numeric → null. */
    private fun parseField(text: String): Float? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.toFloatOrNull()
    }
}
