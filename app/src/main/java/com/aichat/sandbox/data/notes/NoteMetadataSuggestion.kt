package com.aichat.sandbox.data.notes

/**
 * Phase 9 (AI art-assist) — **metadata & accessibility helpers** contract.
 *
 * A [NoteMetadataSuggestion] is the structured, non-mutating result the
 * metadata assistant returns for a note or icon: a short [title], a small set
 * of normalized [tags], and a short [description] usable as alt text for PNG /
 * SVG exports. None of these touch the canvas geometry — they only ever feed
 * the note row's title, the `note_tags` junction table, and the export
 * accessibility metadata (Adoption principle 3; Phase 9 acceptance criteria).
 *
 * The user always gets the last word: the panel surfaces every field as
 * editable / toggleable text and applies nothing until an explicit tap, so a
 * suggestion can be accepted, edited, or discarded wholesale.
 *
 * Producer: [MetadataParser] validates a model's JSON reply. The tags are run
 * through [IconTags.normalize] so the stored form matches the gallery's
 * chip-filter and count queries exactly.
 */
data class NoteMetadataSuggestion(
    /** A short, human-friendly title. May be blank when the model offered none. */
    val title: String,
    /** Up to [IconTags.MAX_TAGS_PER_NOTE] normalized, de-duplicated tags. */
    val tags: List<String>,
    /**
     * One short sentence describing the drawing, suitable as export alt text.
     * Capped to [MAX_DESCRIPTION_LENGTH] so it stays a caption, not an essay.
     * May be blank when the model offered none.
     */
    val description: String,
) {
    /** A suggestion is useful only if it carries at least one field. */
    val isEmpty: Boolean
        get() = title.isBlank() && tags.isEmpty() && description.isBlank()

    companion object {
        const val SCHEMA: Int = 1

        /** Title cap — a note title is a label, not a paragraph. */
        const val MAX_TITLE_LENGTH: Int = 80

        /** Description cap — alt text must stay short (Phase 9 acceptance). */
        const val MAX_DESCRIPTION_LENGTH: Int = 240
    }
}
