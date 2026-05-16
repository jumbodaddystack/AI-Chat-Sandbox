# Plan: Stylus Notes for S25 Ultra (revised)

## Context

The app is a native Android Kotlin + Jetpack Compose chat client (minSdk 26, compileSdk 34). There is no notes feature today. The user is on a Samsung S25 Ultra with S-Pen and wants a first-class stylus surface — writing, brainstorming, doodling, planning — that lives alongside chat so they can switch between modes fluidly. The notes feature is **standalone** in v1 (no chat panel inside the editor), but AI hooks and quick-capture entry points are baked in from day one.

Android's standard `MotionEvent` already reports `TOOL_TYPE_STYLUS`, pressure, tilt, and orientation from the S-Pen, and exposes the side button via `BUTTON_STYLUS_PRIMARY`. No Samsung-specific SDK is required.

## Decisions (from the brainstorm)

| Axis | Decision |
| --- | --- |
| Editor / chat coupling | Editor is standalone. AI hooks live in the editor; chat-side gets a "pin note as context" affordance later. |
| Canvas shape | Truly infinite single surface, pan/zoom anywhere. |
| Canvas polish | Go big: front-buffered low-latency rendering, motion prediction, hover cursor, side-button eraser, pressure + tilt. |
| Background style | User-selectable per note: plain / dot-grid / lines / graph. |
| Pen tools | Pen, highlighter, pencil (tilt shading), dual eraser (stroke + area), lasso, text box. |
| AI surface | Lasso context menu **and** a whole-note "Ask" toolbar button. Both feed a side sheet. |
| AI model | Whichever model the user has selected for chat. Non-vision models fall back to OCR text only. |
| Lasso actions | Move / scale / rotate, duplicate / delete, cross-note clipboard, export selection. |
| Export | Share as PNG, share as PDF, send to an existing in-app chat. |
| Quick capture | Stylus icon in chat input (sketch attachment), Android 14 `ACTION_CREATE_NOTE` default-app handler, home-screen shortcut + Quick Settings tile. |
| Build order | **Canvas feel first.** AI integration and quick-capture are later phases. |

## Architecture overview

```
NotesListScreen   (new bottom-nav tab, thumbnails of bounding-box render)
    └── NoteEditorScreen (full-screen)
            ├── DrawingSurface  (custom View, owns input + front-buffer render)
            │       └── pen / highlighter / pencil / eraser / lasso / text-box
            ├── BackgroundLayer (plain / dot / line / graph, rendered to scroll/zoom)
            ├── ToolPalette     (bottom bar) + TopAppBar (title, undo/redo, Ask-AI)
            └── AiSideSheet     (slides in from the right; uses chat model)
```

## Data layer

`app/src/main/java/com/aichat/sandbox/data/model/Note.kt`

```kotlin
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val backgroundStyle: String,   // "plain" | "dot" | "line" | "graph"
    val schemaVersion: Int,        // bump when stroke binary format changes
    // Stroke geometry bounds, used for thumbnails & initial viewport.
    val minX: Float, val minY: Float, val maxX: Float, val maxY: Float,
    val thumbnailPath: String?,    // cached PNG in app files dir
    val ocrText: String?,          // most-recent OCR pass for search / non-vision AI fallback
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
```

Strokes live in a separate table — keeps notes light, allows partial loading, and avoids the JSON-blob anti-pattern from the v0 plan:

`app/src/main/java/com/aichat/sandbox/data/model/NoteItem.kt`

```kotlin
@Entity(
    tableName = "note_items",
    foreignKeys = [ForeignKey(entity = Note::class, parentColumns = ["id"],
        childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("noteId"), Index("noteId", "zIndex")],
)
data class NoteItem(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val noteId: String,
    val zIndex: Int,
    val kind: String,        // "stroke" | "text"
    val tool: String?,       // for strokes: "pen" | "highlighter" | "pencil" | "eraser"
    val colorArgb: Int,
    val baseWidthPx: Float,
    // Strokes: packed binary points (x,y,p,tilt). Text: UTF-8 body, font/size encoded.
    val payload: ByteArray,
)
```

Point packing: `FloatArray` flattened to bytes via `ByteBuffer` — `(x, y, pressure, tilt)` per sample, no per-point timestamps. ~25% smaller than the v0 design and far smaller than JSON.

**DAO** — `data/local/NoteDao.kt`, mirrors `ChatDao`:
- `observeNotes(): Flow<List<Note>>` ordered by `updatedAt DESC`
- `getNote(id)`, `getItems(noteId): List<NoteItem>`
- `upsertNote`, `upsertItems`, `deleteItems(ids)`, `deleteNote(id)`
- Transactional `saveNote(note, items)` that replaces the item set atomically.

**Database** — `data/local/AppDatabase.kt`:
- Add `Note::class` and `NoteItem::class` to `@Database(entities = ...)`
- Bump version `3 → 4`
- Add `MIGRATION_3_4` in `data/local/Migrations.kt` that creates both tables + indices (follow the `MIGRATION_2_3` pattern)
- `abstract fun noteDao(): NoteDao`

**Repository** — `data/repository/NoteRepository.kt`:
- Thin pass-through, plus `renderThumbnail(noteId)` that rasterizes the bounding box to a 512px PNG in `filesDir/note-thumbs/` and updates `thumbnailPath`. Runs on a background dispatcher after save.
- `runOcr(noteId)` (later phase) — wraps ML Kit Digital Ink, writes `ocrText`.

**DI** — `di/AppModule.kt`: add `provideNoteDao` and the repository.

## Rendering pipeline (the high-stakes piece)

S-Pen on the S25 Ultra reports ~240 Hz; the Android frame pipeline alone introduces ~1–2 frame latency on top of touch sampling. To get a "pen on paper" feel we need to render the *in-progress* stroke on a front buffer rather than going through Compose recomposition.

**Component**: `app/src/main/java/com/aichat/sandbox/ui/components/notes/DrawingSurface.kt` — a custom `SurfaceView` (or `View` backed by `CanvasBufferedRenderer` from `androidx.graphics:graphics-core:1.0+`) wrapped in an `AndroidView` for Compose embedding.

Responsibilities:

1. **Input**
   - Accept only `TOOL_TYPE_STYLUS` pointers for ink (palm rejection).
   - Touch pointers route to a `ViewportController` for pan (one-finger) and pinch-zoom (two-finger).
   - Read `MotionEvent.getButtonState() and BUTTON_STYLUS_PRIMARY` — while held, force the active tool to `eraser` and restore on release.
   - Iterate `historySize` in `ACTION_MOVE` (S-Pen samples faster than the frame rate; skipping history gives jagged lines).
   - Feed positions through `androidx.input:input-motionprediction` (`MotionEventPredictor`) to predict 1 frame ahead; render predicted tail on the front buffer, replace with real samples next frame.
   - `ACTION_HOVER_*` with `TOOL_TYPE_STYLUS` drives a small hover cursor preview at the nib position.

2. **Rendering**
   - Two layers: a **scene layer** (Bitmap-backed canvas of committed strokes, redrawn on viewport change) and a **front buffer** (current in-progress stroke + hover cursor).
   - In-progress stroke uses `Path` with quadratic Bézier smoothing between samples (`path.quadTo(p1, midpoint(p1,p2))`).
   - Variable width: `baseWidthPx * pressureCurve(pressure) * tiltFactor(tool, tilt)` sampled per segment, drawn as a stroked sub-path per segment so width can vary mid-stroke.
   - Pencil tool adds a noise-textured `BitmapShader` and modulates alpha by tilt.
   - Highlighter: `BlendMode.Multiply` (or simple 30% alpha), constant width, drawn under ink (lower `zIndex`).

3. **Commit**
   - On `ACTION_UP/CANCEL`, freeze the live stroke into a `NoteItem`, blit onto the scene layer, append to the in-memory list, clear the front buffer.

4. **Erase**
   - Stroke eraser: hit-test the stroke's bounding box first, then segment-by-segment distance check, remove matched items.
   - Area eraser: same approach with a configurable radius; partial overlap removes the whole stroke in v1 (true segment splitting is v2).

## UI layer

**Navigation** — `ui/navigation/Navigation.kt`:
- Add `data object Notes : Screen("notes", "Notes", Icons.Filled.EditNote)` and append to `bottomNavItems`.
- `composable("notes") { NotesListScreen(...) }`
- `composable("note/{noteId}") { NoteEditorScreen(...) }`, sentinel `"note/new"` for fresh notes.

**NotesListScreen** — `ui/screens/notes/NotesListScreen.kt`:
- LazyColumn of cards, mirrors `ChatListScreen`.
- Each card: cached thumbnail PNG, title, relative time, long-press → delete confirmation.
- "New note" FAB.

**NoteEditorScreen** — `ui/screens/notes/NoteEditorScreen.kt`:
- TopAppBar: back (saves on exit), inline editable title, undo, redo, **Ask about this note** button.
- Bottom palette: tool tabs (pen / highlighter / pencil / eraser / lasso / text), color row, width slider, page-style menu.
- Center: `DrawingSurface`.
- Right edge: pull-out `AiSideSheet` (hidden by default).

**Undo/redo** — event log, not snapshot:
- `EditorAction = AddItems | RemoveItems | TransformItems | UpdateText`
- ViewModel keeps `pastActions` and `futureActions` deques; applying/reverting mutates the `SnapshotStateList<NoteItem>`. Persisted across editor lifetime (in-memory only in v1).

**LassoController** — `ui/screens/notes/LassoController.kt`:
- Closed-loop path → polygon hit-test against item bounding boxes (cheap), then exact stroke containment.
- Selection handles for translate / scale / rotate (Compose `pointerInput` over the selection bounds).
- Floating menu: **Ask**, **Convert to text**, **Duplicate**, **Delete**, **Cut**, **Copy**, **Export as image**.
- Clipboard: a process-singleton `NoteClipboard` object holds the last copied items; paste re-IDs them and offsets position. Survives navigating between notes; lost on app death (v1).

**Text-box items** — tap with the text tool drops a `NoteItem(kind="text")` you can move/scale; double-tap to edit. AI side-sheet's "Insert on canvas" action creates the same item.

## AI integration

Lives entirely behind two entry points:

1. **Lasso → Ask** (selection in context)
2. **Toolbar "Ask about this note"** (whole note in context)

Both call into `NoteAiService` (`data/notes/NoteAiService.kt`):

```kotlin
suspend fun ask(
    note: Note,
    selection: List<NoteItem>?,        // null = whole note
    prompt: String,                    // free-form or canned (Explain / Expand / Convert to text)
    model: ModelChoice,                // taken from existing chat ModelSelector
): Flow<AiChunk>
```

Implementation:
- Render the selection (or whole note's bounding box) to a PNG in memory.
- If `model.supportsVision`: send `[image, ocrTextHint, prompt]` to the existing `ApiClient`.
- Else: run ML Kit Digital Ink over the selection's strokes, send `[ocrText, prompt]`.
- Stream the response into `AiSideSheet`; each reply offers **Copy**, **Insert as text box**, **Send to chat**.

ML Kit Digital Ink (`com.google.mlkit:digital-ink-recognition`) is on-device and free; model downloads on first use. OCR runs lazily in the repository on save so search and non-vision fallback are always ready.

**Canned prompt buttons** in the lasso menu: *Explain*, *Expand*, *Convert to text*, *Summarize*, *Continue this*. Plus a free-form input.

## Quick-capture entry points (phase 3)

- **Stylus icon in chat input** — `ChatScreen`'s composer gets a pen button that opens a bottom-sheet sketch surface (a stripped-down `DrawingSurface`, fixed-size). On confirm, the sketch is rasterized and attached to the outgoing message as an image (reuses existing image-attachment flow).
- **Android 14 default note-taking app** — declare an activity-alias for `Intent.ACTION_CREATE_NOTE` in `AndroidManifest.xml`. Launches `NoteEditorScreen` with `note/new`. Honours `EXTRA_USE_STYLUS_MODE`. Gated by `Build.VERSION_CODES.UPSIDE_DOWN_CAKE`.
- **Home-screen shortcut** — static shortcut in `app/src/main/res/xml/shortcuts.xml` pointing at `note/new`.
- **Quick Settings tile** — `TileService` subclass that launches the same deep link.

## Export (phase 4)

- **PNG**: rasterize the stroke bounding box (with margin), expose via `FileProvider` + `Intent.ACTION_SEND`.
- **PDF**: `PdfDocument` API. Infinite canvas → ask the user to pick "fit page" or "tile to multiple pages" at export time.
- **Send to chat**: bottom-sheet picker over existing chats; opens the target chat with the rendered PNG + OCR text attached to the draft message.

## Manifest

`app/src/main/AndroidManifest.xml`:
- `<uses-feature android:name="android.hardware.type.stylus" android:required="false" />`
- Activity-alias with `<intent-filter>` for `android.intent.action.CREATE_NOTE` (phase 3).
- `<service>` for the Quick Settings tile (phase 3).

## Files to create / modify

**Create (phase 1 — canvas feel):**
- `data/model/Note.kt`
- `data/model/NoteItem.kt`
- `data/local/NoteDao.kt`
- `data/repository/NoteRepository.kt`
- `ui/components/notes/DrawingSurface.kt`
- `ui/components/notes/BackgroundLayer.kt`
- `ui/components/notes/ViewportController.kt`
- `ui/screens/notes/NotesListScreen.kt`
- `ui/screens/notes/NoteEditorScreen.kt`
- `ui/screens/notes/NoteEditorViewModel.kt`
- `ui/screens/notes/LassoController.kt`

**Create (phase 2 — AI):**
- `data/notes/NoteAiService.kt`
- `data/notes/HandwritingOcr.kt`
- `ui/screens/notes/AiSideSheet.kt`

**Create (phase 3 — capture):**
- `ui/components/chat/SketchAttachmentSheet.kt`
- `service/NotesQuickTileService.kt`
- `app/src/main/res/xml/shortcuts.xml`

**Create (phase 4 — export):**
- `data/notes/NoteExporter.kt`
- `ui/screens/notes/SendToChatSheet.kt`

**Modify:**
- `data/local/AppDatabase.kt` (entities, version 3 → 4)
- `data/local/Migrations.kt` (`MIGRATION_3_4`)
- `di/AppModule.kt` (DAO + repository + AI service)
- `ui/navigation/Navigation.kt` (Screen entry, bottomNavItems, NavHost routes)
- `app/build.gradle.kts` (deps: `androidx.graphics:graphics-core`, `androidx.input:input-motionprediction`, `com.google.mlkit:digital-ink-recognition`)
- `app/src/main/AndroidManifest.xml` (stylus feature, CREATE_NOTE intent, tile service)
- `ui/screens/chat/ChatScreen.kt` (composer pen button — phase 3)

## Phased build order

**Phase 1 — Canvas feel (this is "v1" by itself; we can ship it).**
- Room schema + migration, repository, DI.
- `DrawingSurface` with front-buffer rendering, motion prediction, pressure/tilt, palm rejection, side-button eraser, hover cursor.
- Infinite viewport (pan/pinch-zoom on touch, ink on stylus).
- Pen, highlighter, pencil, dual eraser, lasso (with move/scale/rotate, duplicate/delete, cross-note clipboard), text box.
- Background styles (plain / dot / line / graph).
- Undo/redo as event log.
- Save/load, thumbnail caching.
- Manual S25 Ultra test pass.

**Phase 2 — AI on canvas.**
- ML Kit Digital Ink integration; OCR runs on save.
- `NoteAiService` + side sheet.
- Lasso "Ask" menu and toolbar "Ask about this note" button.
- Canned prompts (Explain / Expand / Convert to text / Summarize / Continue).
- "Insert as text box" / "Send to chat" actions on replies.

**Phase 3 — Quick capture.**
- Pen button in chat composer → sketch attachment sheet.
- `ACTION_CREATE_NOTE` alias (API 34+ gated).
- Static home-screen shortcut + Quick Settings tile.

**Phase 4 — Export.**
- PNG / PDF share targets.
- "Send to chat" picker.
- Chat-side "pin note as context" affordance (re-renders pinned note on every turn).

## Verification (phase 1 manual pass, S25 Ultra)

1. Build, install, app still launches and existing chats load (migration succeeded).
2. New "Notes" tab in bottom nav; "New note" FAB opens an empty infinite canvas with chosen background.
3. Draw with S-Pen: line tracks the nib with no perceptible lag, width varies with pressure, pencil widens and softens with tilt.
4. Rest palm while drawing — palm strokes do not appear.
5. Hold the S-Pen side button — strokes erase as if eraser were selected; release returns to previous tool.
6. Hover the S-Pen — small cursor preview tracks the nib above the screen.
7. One-finger drag pans, two-finger pinch zooms; stylus continues to ink during gestures.
8. Switch tools, change colors, run undo / redo, lasso a region → move / scale / rotate / duplicate / delete.
9. Copy a lasso selection, navigate to a different note, paste — items land with offset.
10. Set a title, back out → list shows new note at the top with a thumbnail; reopen → all items redrawn identically.
11. Force-quit and relaunch → note still there.
12. Long-press in list → delete confirmation removes the note and its items (FK cascade).

Lightweight tests alongside:
- `NoteDao` upsert / read / cascade-delete (in-memory Room).
- Stroke point binary round-trip preserves pressure / tilt within float tolerance.
- `LassoController` polygon hit-test unit cases.

## Out of scope (deliberately, even after v1 phases)

- Cloud sync.
- Full pencil-style segment-splitting eraser (v1 erases whole strokes for partial overlap).
- Multi-user collaboration on a note.
- S Pen Bluetooth-button air gestures (would need the Samsung S Pen Remote SDK).
- Handwriting → math LaTeX (separate ML Kit model; revisit if requested).
