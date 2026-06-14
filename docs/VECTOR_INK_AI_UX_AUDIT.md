# Vector Ink & AI UX Audit

> **Status:** Audit only — no code changes. This document maps UX friction
> across the vector ink editor and its embedded AI assistant, prioritizes it,
> and gives rough effort estimates so the fixes can be sequenced in a later
> build phase.
>
> **Date:** 2026-06-14 · **Scope:** `ui/screens/notes/`, `ui/components/notes/`,
> `data/notes/` (AI service + export pipeline).

## Context

The vector ink editor (`NoteEditorScreen`) and its AI assistant (`AiSideSheet`
/ `NoteAiService`) grew organically across ~17 build phases. Each phase added
capability — 15 tools, layers, frames, stamps, brushes, presentation, multi-
format export, vision + OCR AI, structured edit-ops — but the surface has never
been reviewed as a whole the way a user meets it. The result is powerful but
**cumbersome**: controls are crammed into single rows, several flows ask the
user to act blind, and the AI panel fights the canvas for space.

This audit looks at both surfaces together and sequences the work. Findings
cite concrete `file:line` references so each one is directly actionable later.

---

## Findings — Vector Ink editor

### V1. Tool row is overcrowded; variant tools are hidden — *High*
`ToolPalette.kt:136-180` packs ~10 equal-weight icon buttons into one row on a
360 dp phone (3 ink + eraser + lasso + text + shapes + board + frame). Three are
*grouped* buttons (`GroupedToolButton`, `:202-247`) whose other variants only
appear on "tap-while-active" or long-press — an invisible gesture with no
caret/badge hint. A new user has no way to discover Rect, Ellipse, Arrow,
Polygon, Path-pen (all under "Shapes") or the area eraser.

### V2. The two eraser variants look identical — *Medium*
Both `ERASER_STROKE` and `ERASER_AREA` render the same `Outlined.Backspace`
glyph (`ToolPalette.kt:302-303`), and the group button wears one fixed glyph
(`:150`). In the picker dropdown the only differentiator is the text label.

### V3. Unrelated tools share the "Board" group — *Medium*
Sticky notes and connectors are bundled under one grouped button
(`ToolPalette.kt:170-177`) despite being conceptually unrelated (a content
object vs. a relationship line), forcing the same hidden-variant gesture to
reach either.

### V4. Per-tool config height is unstable — *Medium*
The config area swaps wholesale per tool (`ToolPalette.kt:92-122`): ink shows 1
row, but shapes show `InkConfigRow` + `ShapeStyleRow` + `SnapChipRow` (3 rows)
and path-pen shows 3. The bottom palette jumps in height as you switch tools,
shifting the canvas and the collapse handle under the user's finger.

### V5. Custom-color discovery relies on a hidden long-press — *Low*
The color row (`ToolPalette.kt:334-357`) exposes preset swatches + a "+" tile;
the full HSV picker *also* opens by long-pressing any swatch (`:346`), but
nothing signals that. The "+" tile is the only visible route.

### V6. Hint rows substitute prose for affordances — *Low*
Lasso, Frame, Text, and stroke-eraser show a sentence of instructions where a
control would go (`ToolPalette.kt:446-468`, `:586-596`, `:414-426`) — e.g.
"Lasso — draw a loop to select strokes." It reads like a tooltip that never
dismisses, costs vertical space, and signals the gesture isn't self-evident.

### V7. Export/share is fragmented across a deep menu + 4 modal dialogs — *High*
The overflow `⋮` menu (`NoteEditorScreen.kt:486-583`) mixes background styles,
finger-drawing toggle, pixel grid, insert image, brush sheet, present, save-as-
template, resize-artboard **and** six share/export actions (PNG, PDF, SVG,
Vector XML, Send-to-chat, frame PNG/SVG). PDF/SVG/Vector-XML each then open
their *own* `AlertDialog`. There is no single "Export" surface.

### V8. The selection action menu is a dense, scrollable strip — *Medium*
`SelectionOverlay` is wired with 20+ callbacks (`NoteEditorScreen.kt:691-719+`:
duplicate, delete, cut, copy, paste, ask, convert-to-text, canned edits, save-
as-stamp, set-fill, set-stroke, group, ungroup, align, distribute, reorder,
boolean-combine, outline…). Frequently-used actions sit beside rare ones with no
hierarchy.

### V9. Panels (Frames/Pages, Stamps, Layers) are buried behind a generic icon — *Low*
They live inside a `Dashboard`-icon dropdown (`NoteEditorScreen.kt:406-466`).
The icon doesn't communicate "panels," and each panel is one extra tap + read.

### V10. The collapse handle is a thin, easy-to-miss target — *Low*
`PaletteCollapseHandle` (`:1675-1696`) is a ~20 dp chevron strip; while the tap
target spans the full width, the visual signal is faint and competes with the
favorites/brush rows above it.

---

## Findings — AI assistant

### A1. AI edits are accepted blind — no on-canvas diff — *Critical*
The single biggest gap. `AiEditPreviewBanner` (`NoteEditorScreen.kt:1574-1622`)
shows only a *count*: "AI Simplify · 2 added, 1 removed, 3 modified" with
Reject/Accept. The code itself flags it: *"Visual diff overlay (alpha+outline)
is a follow-up; for v1 we show the summary + counts"* (`:1186-1190`,
`:1568-1572`). The simulation already holds `added` / `removed` / `modified`
item lists — they're simply never drawn. Users commit vector edits to their
artwork without seeing them.

### A2. The AI panel blocks the canvas it's discussing — *High*
`AiSideSheet` is a right-edge sheet at 70% screen width (`AiSideSheet.kt:684`,
capped 480 dp) with a 32%-opacity scrim that absorbs all canvas taps
(`:112-123`). You cannot see the note you're asking about beyond a sliver, can't
point at anything, and can't draw while it's open. For a "look at this and help
me" tool, hiding the subject is backwards.

### A3. Ask vs. Edit modes are an unexplained toggle — *Medium*
Two `FilterChip`s at the bottom of the footer (`AiSideSheet.kt:609-624`) switch
between prose replies and staged vector edits — a fundamental behavior change —
with no label beyond "Ask"/"Edit" and only a placeholder hint ("Ask anything…"
vs "Describe an edit…"). The mode also sits *below* the conversation, far from
where the user's attention is.

### A4. Edit-mode previews land with no progressive feedback — *Medium*
`collectEdit` buffers the entire model reply before parsing
(`NoteAiService.kt`), so for large/icon edits the user stares at "Thinking…" for
seconds with no sense of progress or size.

### A5. Partial edit failures are silent / vague — *Medium*
`EditOpsParser` drops invalid ops into a `rejected` list that's never surfaced;
the user either sees "Could not parse AI edit response — try rephrasing." or
gets 7 of 10 ops applied with no notice of the 3 dropped.

### A6. Frozen selection scope is non-obvious — *Medium*
`pendingSelection` is captured at open time and held for the sheet's life
(`AiSideSheetState.kt`). The only way to re-scope is to tap the scope chip to
*clear* it (`AiSideSheet.kt:543-549`); there's no "re-scope to current
selection." A user who lassoes something new mid-conversation is silently still
talking about the old selection.

### A7. No multi-turn context — *Medium*
Each turn is one-shot — prior turns aren't packed into later requests. "Explain
this" → "now expand on it" loses the thread; the chat-bubble UI
(`ConversationList`, `AiSideSheet.kt:274-323`) *looks* like a conversation but
doesn't behave like one.

### A8. Convert-to-text is a two-step it doesn't need to be — *Low*
The OCR result arrives as a Done turn, then requires a separate "Insert as text
box" tap (`AiSideSheet.kt:343-356`).

### A9. Icon refine placement is implicit — *Low*
The result replaces the sketch in-place on icons but lands side-by-side on
notes (`NoteEditorViewModel` refine logic), with no pre-action indication of
which.

### A10. Canned actions hide what they'll do — *Low*
Chips like "Auto-shape" / "Simplify" send a fixed hidden prompt with no preview
or way to tweak before firing (`AiSideSheet.kt:551-588`).

---

## Prioritized recommendations

Effort key: **S** ≈ hours · **M** ≈ 1–2 days · **L** ≈ multi-day.

### P0 — highest leverage
1. **On-canvas visual diff for AI edits** (addresses **A1**). Render `added`
   (accent/green outline), `removed` (red, faded), `modified` (outline) directly
   on the canvas behind the banner, before Accept/Reject. The simulation data
   already exists; this is the explicitly-deferred follow-up. **Effort: M.**
2. **Make the AI panel non-blocking** (**A2**). Spec options: a narrower docked
   rail, a dismissible bottom sheet, or a "compact" mode that drops the scrim so
   the canvas stays live and visible. Pairs naturally with P0 — you want to
   *see* the diff while the panel is open. **Effort: M.**

### P1 — high-value, contained
3. **Unify export into one "Share / Export" sheet** (**V7**): a single entry
   listing PNG / PDF / SVG / Vector XML / Send-to-chat with inline options,
   instead of menu-scattered actions + 4 dialogs. **Effort: M.**
4. **Signal grouped-tool variants** (**V1**): add a caret/long-press affordance
   indicator on grouped buttons; consider promoting Shapes to its own expandable
   tray. **Effort: S–M.**
5. **Clarify Ask/Edit** (**A3**): move the toggle up near the title, add one-line
   descriptions, and surface partial-failure summaries from **A5** ("Applied 7
   of 10 edits; 3 invalid"). **Effort: S.**
6. **Stabilize palette height** (**V4**): reserve a consistent config area or
   animate height changes so the canvas/handle don't jump. **Effort: S.**

### P2 — polish / lower risk
7. Distinct eraser glyphs (**V2**) — **S**.
8. Split or rename the "Board" group (**V3**) — **S**.
9. Declutter the selection menu into primary actions + a "More" overflow
   (**V8**) — **M**.
10. Re-scope affordance + clearer frozen-scope copy for AI (**A6**) — **S**.
11. One-tap convert-to-text insertion (**A8**) — **S**.
12. Visible custom-color route / panels labeling / collapse-handle emphasis
    (**V5**, **V9**, **V10**) — **S** each.
13. Multi-turn AI context (**A7**) — **L**; flag as its own initiative.

### Quick wins (do-anytime, all S)
Distinct eraser icons (**V2**), one-line Ask/Edit descriptions (**A3**),
partial-failure summary (**A5**), one-tap OCR insert (**A8**), custom-color hint
(**V5**).

---

## What's already good (preserve)

- Active-tool "glowing accent" fill (`ToolPalette.kt:264-291`) — a strong,
  legible state signal.
- Live nib preview dot tracking the width slider (`:393-411`).
- 44 dp swatch touch targets wrapping 28 dp visuals (`:699-721`).
- Streaming reply with typing indicator + auto-scroll
  (`AiSideSheet.kt:288-323`, `:502-518`).
- AI edits commit as a single `CompositeEdit` undo entry — clean reversibility.
- Vision/OCR branch selection is transparent to the user.

These should survive any redesign — don't regress them while fixing the above.

---

## Out of scope this round

Implementation of P0–P2. Those are sequenced here for a later build phase. No
source changes accompany this audit, so the build and unit tests are unaffected
(the two known pre-existing failures noted in `CLAUDE.md` remain the bar).
