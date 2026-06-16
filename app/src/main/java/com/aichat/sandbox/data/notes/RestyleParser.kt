package com.aichat.sandbox.data.notes

/**
 * Phase 7 — parser/validator for a named-style **restyle** reply.
 *
 * A restyle reply is an ordinary `edit-ops` document, so this delegates the
 * heavy lifting (fence/bare extraction, per-op parsing, id/layer filtering) to
 * [EditOpsParser] and then applies one extra guard: only the non-additive,
 * non-moving op subset [StylePreset.isRestyleOp] survives. Anything else
 * (`add_path` / `add_shape` — new subject matter — `transform`, `delete`,
 * `group`, `set_layer`, `merge_paths`) is moved into the document's
 * [EditOpsDoc.rejected] list rather than applied, so a named restyle can never
 * change *what* the drawing is, only how it looks.
 *
 * Like every other note parser this never throws: a non-JSON / malformed reply
 * returns [Result.failure]; a well-formed reply with no surviving ops returns a
 * success with an empty `ops` list (the caller surfaces a friendly "no changes"
 * note rather than an error).
 */
object RestyleParser {

    /**
     * Parse [raw] into an [EditOpsDoc] restricted to the restyle-safe op subset.
     * Pass the serialized canvas's short ids / layer ids as [knownIds] /
     * [knownLayers] so ops referencing invented or locked-layer targets are
     * dropped by [EditOpsParser]; `null` accepts anything (handy for tests).
     */
    fun parse(
        raw: String,
        knownIds: Set<String>? = null,
        knownLayers: Set<String>? = null,
    ): Result<EditOpsDoc> {
        val doc = EditOpsParser.parse(raw, knownIds, knownLayers).getOrElse {
            return Result.failure(it)
        }
        val kept = ArrayList<EditOp>(doc.ops.size)
        val dropped = ArrayList<EditOpsDoc.RejectedOp>()
        for (op in doc.ops) {
            if (StylePreset.isRestyleOp(op)) {
                kept += op
            } else {
                dropped += EditOpsDoc.RejectedOp(op.toString(), "op not allowed during restyle")
            }
        }
        return Result.success(
            doc.copy(ops = kept, rejected = doc.rejected + dropped)
        )
    }

    /**
     * System message for [AskMode.RESTYLE]. Pins the `edit-ops` schema and the
     * restyle-safe op vocabulary, and — crucially — forbids adding new geometry,
     * deleting, or moving items, so the restyle keeps the subject intact. The
     * concrete target look + per-preset constraints arrive in the user message
     * (see [StylePreset.buildInstruction]).
     */
    const val SYSTEM_MESSAGE: String =
        "You are a vector restyling assistant. You receive a drawing as an image " +
            "and a JSON description of every item by ID. Restyle ONLY the existing " +
            "items into the look the user asks for, keeping the same subject, " +
            "shapes and composition.\n\n" +
            "Reply with ONLY a fenced ```edit-ops block matching this schema:\n\n" +
            "{ \"schema\": 1, \"summary\": \"<one short sentence>\",\n" +
            "  \"ops\": [ /* restyle operations referencing items by ID */ ] }\n\n" +
            "Allowed ops only:\n" +
            "- recolor: { \"op\": \"recolor\", \"ids\": [...], \"color\": \"#RRGGBB\" }\n" +
            "- restyle: { \"op\": \"restyle\", \"ids\": [...], \"width\"?: float, \"opacity\"?: float }\n" +
            "- smooth: { \"op\": \"smooth\", \"ids\": [...], \"amount\": 0..1 }\n" +
            "- simplify: { \"op\": \"simplify\", \"ids\": [...], \"tolerance\": float }\n" +
            "- replace_with_shape: { \"op\": \"replace_with_shape\", \"id\": \"s_001\", " +
            "\"shape\": { … } } to clean one wobbly stroke into a crisp primitive.\n\n" +
            "Rules:\n" +
            "- Reference only ids that appear in the provided JSON.\n" +
            "- NEVER add new geometry (no add_path / add_shape), NEVER delete, " +
            "regroup, relayer, or move/scale items (no transform). The drawing must " +
            "stay the same subject.\n" +
            "- Do not target items on locked or hidden layers.\n" +
            "- If you can't restyle, return an empty ops array and explain in " +
            "`summary`. Never reply outside the fenced block."
}
