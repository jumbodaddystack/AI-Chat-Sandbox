# App-Wide UI Modernization — Brainstorm & Plan

## Context

The AI Chat Sandbox app feels dated ("Windows 98"): flat grey surfaces, default
Material spacing/typography, monochrome toolbars, weak hierarchy. The root cause
isn't bad taste — it's that the app currently runs **two unrelated design worlds**:

- **The dated half (Chat, Notes, Settings)** uses the bare-bones
  `AIChatSandboxTheme` (`ui/theme/Theme.kt`): a `darkColorScheme`/`lightColorScheme`
  with a handful of hand-picked greys + one blue accent, **no typography file, no
  shape file, no spacing/motion tokens**. Screens scatter raw `8.dp/12.dp/16.dp`
  and read straight from Material defaults.
- **The modern half (Icons, Vector)** uses **Studio Bench** (`ui/theme/studio/`):
  a fully-tokenized system — spacing scale, radius scale, sizing, a real type
  scale with a mono "instrument readout" voice, motion tokens with reduce-motion
  support, and a disciplined palette ("one loud color" rule). This part already
  looks like a pro tool.

The disparity *is* the dated feeling. The chosen direction is a **fresh new
identity applied everywhere** (retiring both current styles), with emphasis on
**modern Material 3 components** and **motion / micro-interactions**.

The strategic move: **keep Studio Bench's excellent token *architecture* as the
skeleton for a single app-wide design system, but dress it in a brand-new skin**
(new palette, type, shape, surface language) and repoint every screen — including
Icons/Vector — at it. We rebuild the *look*, reuse the *bones*.

---

## Part 1 — Brainstorm: three fresh directions

Three divergent identities, each defined enough to picture. Recommendation follows.

### Direction A — "Nocturne" (recommended)
A refined, dark-first AI-creative identity. Deep near-black canvas with a faint
cool tint (not flat `#1A1A1A`), layered *tiers* of elevation instead of one grey,
and a single luminous signature accent used for primary action + AI moments.
- **Mood:** premium, focused, modern AI app (think a calm pro editor at night).
- **Accent:** electric iris/violet `#7C6CFF` as primary; a complementary
  **AI-spark** treatment (violet→cyan) reserved *only* for AI affordances (the
  sparkle button, streaming, "Tune-Up"). One loud color, used with restraint.
- **Surfaces:** a 4-tier graphite ramp (canvas → low → raised → overlay) so cards
  and sheets separate by tone, not by heavy shadows.
- **Why it fits:** the hero screens are already dark; an AI sandbox reads as a
  night-mode creative tool; it differentiates hard from default-Material apps.

### Direction B — "Studio Daylight"
Warm-neutral, ink-forward, editorial. Paper-like light surfaces, strong type
contrast, one warm signal accent (amber/coral). Light-first with a dark parity.
- **Mood:** clean, calm, document-centric — great for Notes.
- **Trade-off:** a bigger departure from the current dark UI; light-first is more
  work, and great light+dark parity was not a stated priority.

### Direction C — "Mono + Signal"
Evolves Studio Bench's machined precision but with a *new* signal color (retiring
cyan-teal): near-monochrome graphite, hairline rules instead of cards, one
high-voltage accent (e.g. signal lime or hot magenta), mono readouts everywhere.
- **Mood:** technical instrument, high contrast, opinionated.
- **Trade-off:** the "instrument" voice is perfect for Vector/Icons but can feel
  cold for Chat/Notes, which are conversational.

### Recommendation
**Direction A "Nocturne"**, dark-first as the hero with a correct (but
not over-invested) light parity. It honors the existing dark UI, gives the app a
distinctive non-Material accent, and lets the AI-spark accent become a recognizable
brand signature across Chat streaming, the Notes sparkle button, and Vector Tune-Up.
The exact accent hue is a one-line token swap, so it can be A/B'd during build.

> The token values below are written for Nocturne. If you prefer B or C, the
> *architecture* is identical — only the palette/type constants change.

---

## Part 2 — Token & theme architecture (the foundation)

Create one unified token layer modeled on Studio Bench, under
`app/src/main/java/com/aichat/sandbox/ui/theme/` (new `tokens/` package). Studio
Bench's files are the reference for *how* to structure each of these.

| New file | Models / replaces | Contents |
|---|---|---|
| `tokens/AppColor.kt` | `Theme.kt` color consts + `StudioColor.kt` | Brand palette constants; `darkColorScheme`/`lightColorScheme`; an `AppExtendedColors` `@Immutable` data class (via `CompositionLocal`) for what M3 lacks: 4-tier surfaces, AI-spark accent, user/assistant bubble, success/warning, hairline/hairlineStrong. |
| `tokens/AppType.kt` | (none today — net new) | Full M3 `Typography` (display→label) with a chosen brand font + a **mono readout** style carried over from `StudioType.kt` for code blocks / coordinates / token counts. |
| `tokens/AppShape.kt` | (none today) | M3 `Shapes` (small/medium/large/extraLarge) defining the new corner language (e.g. 8/12/16/28dp) + a `pill` for primary action. |
| `tokens/AppSpacing.kt` | scattered raw dp | Spacing scale copied from `StudioSpacing` (hair→xxl, 4dp base) via `LocalAppSpacing`. |
| `tokens/AppMotion.kt` | `StudioMotion.kt` | Durations + easings + reduce-motion flag, generalized for app-wide use. |
| `theme/AppTheme.kt` | `AIChatSandboxTheme` | Wires `MaterialTheme(colorScheme, typography, shapes)` and provides all `CompositionLocal`s. Optional `dynamicColor` param left OFF by default (Material You not prioritized). |

**Reconcile Studio Bench:** generalize its tokens into the shared layer and
**repoint `StudioTheme` to derive from `AppTheme`** (same spacing/type-scale/motion
structure, new unified palette), or retire `StudioTheme` entirely in favor of an
"instrument" accent variant. Either way the cyan-teal palette is retired per the
"fresh identity, retire both" choice; the mono-readout voice and artboard-cradle
component survive because they're genuinely good and screen-appropriate.

**Accessor convenience:** add `MaterialTheme.appSpacing`, `.appMotion`,
`.extColors` extension getters (mirroring how `StudioTheme` exposes its locals) so
screens read `MaterialTheme.appSpacing.l` instead of `16.dp`.

---

## Part 3 — Per-screen modernization moves (priority order)

Concrete, opinionated changes. Each can ship independently once the foundation lands.

### 1. Bottom navigation — `ui/navigation/Navigation.kt` (`CompactBottomBar`)
*High impact, low risk, seen on every screen.*
- Keep the compact 56dp height, but add an **animated selection pill / indicator**
  behind the active tab's icon (M3 `NavigationBar` indicator language, animated).
- Animate icon **filled↔outlined** swap and a subtle scale/spring on selection.
- Tint with the new accent; rest state uses `inkMuted`. Add haptic tick on switch.

### 2. Chat list — `ui/screens/chatlist/ChatListScreen.kt`
- Replace flat divider-separated rows with **grouped surfaces** (subtle tier-2
  surface, rounded `medium`, generous 16dp rhythm). Optional date grouping headers.
- Promote "New chat" to a **primary FAB** (or a prominent filled button) instead
  of an inline grey row.
- Redesign the empty state ("No chats yet") with a friendlier illustration/icon,
  brand type, and a clear CTA button — not faint grey text.
- Animate list item entrance (stagger) and search-bar expand/collapse.

### 3. Chat conversation — `ui/screens/chat/ChatScreen.kt`
- **Message bubbles:** asymmetric corner radii (tail effect), user = accent-tinted,
  assistant = tier-2 surface; tighten padding to the spacing scale; improve
  long-text/markdown line-height via the new type scale.
- **Streaming:** replace the `▊` text cursor with a tasteful animated typing
  indicator; apply the AI-spark accent to the streaming state.
- **Input bar:** elevate to a rounded **pill composer** with clear affordances
  for attach/sketch/send; animate the send↔stop morph; clearer focus state.
- Refresh the empty `ExamplesView` prompt cards as proper M3 cards.

### 4. Notes editor toolbar/palette — `ui/screens/notes/NoteEditorScreen.kt`, `ui/components/notes/ToolPalette.kt`, `FavoritesBar.kt`
*This is the screen with the monochrome tool row.*
- Give the active tool a clear **selected state** (accent pill behind it, the way
  the eraser is highlighted today — make all tools do this) with an animated
  indicator that slides between tools.
- Group tools visually (draw / erase / text / shape / structure) with hairline
  separators and consistent touch targets from `sizing.touchTarget`.
- Modernize the collapse handle, Favs row (real swatch chips), and the floating
  zoom + mic controls as cohesive rounded surfaces with the new elevation tiers.
- Tooltip/label polish (the "Stroke eraser — removes whole strokes…" hint).

### 5. Notes list — `ui/screens/notes/NotesListScreen.kt`
- Notebook covers and note rows as M3 cards with consistent radii/elevation tiers;
  refresh section headers with the new type scale; improve the FAB dropdown styling
  and the empty state.

### 6. Settings — `ui/screens/settings/SettingsScreen.kt`
- Convert the long scroll into **grouped setting cards/sections** with section
  headers, dividers, and breathing room.
- Modernize rows: leading icons, M3 `Switch` styling, `OutlinedTextField` with
  proper focus/error states, and the existing `SettingsSlider`/`ModelSelector`
  reskinned to tokens.
- Add a small **theme preview** (since we now have a real theme to show off),
  plus the accent/light-dark controls.

### 7. Icons & Vector — `ui/screens/icons/IconsListScreen.kt`, `ui/screens/vector/VectorTuneupScreen.kt`
- Mostly a **palette re-point**: swap retired cyan-teal for the new Nocturne
  accent via the unified tokens. Keep the artboard cradle, mono readouts, tab
  workflow, and "one loud color" discipline — they're already modern.

---

## Part 4 — Modern M3 components to adopt

- M3 `NavigationBar` indicator semantics for the bottom bar (custom-styled).
- `Card` / `ElevatedCard` / `OutlinedCard` for chat list rows, examples, settings
  groups, notebook covers.
- `SegmentedButton` where there are mode toggles (e.g. Vector compare modes,
  export format, share Markdown/JSON).
- Refreshed `FloatingActionButton` / `ExtendedFloatingActionButton` for New chat /
  New note.
- `ModalBottomSheet` polish for the chat settings panel and note sheets.
- **Predictive back** is out of scope for v1; note it as a future enhancement.

---

## Part 5 — Motion & micro-interactions

Build on `AppMotion.kt` (generalized from `StudioMotion.kt`, which already
respects reduce-motion):
- **Durations/easings tokens:** short (~120ms) for taps/selection, medium (~200ms)
  for sheets/tabs, with standard/emphasized easings. Always gate on the
  reduce-motion flag.
- **Selection feedback:** animated bottom-nav indicator, tool-palette indicator
  slide, button press scale (`animateFloatAsState` / spring), haptics on key taps.
- **List motion:** staggered item entrance on chat list / notes list.
- **AI moments:** streaming typing indicator, sparkle button pulse, Tune-Up
  progress — all using the AI-spark accent so AI reads as a consistent brand beat.
- **Transitions:** content fade/slide between tabs and into the note/chat detail.

---

## Part 6 — Material 3 / dependency upgrades

- **Compose BOM:** currently `2024.02.02` — quite old. Bump to a recent stable BOM
  (e.g. a 2025 release) to get current M3 component APIs (SegmentedButton,
  improved bottom sheets, indicator APIs). **Risk:** must verify against AGP
  8.11.1 / Kotlin 2.0.21 / compileSdk 36; bump incrementally and rebuild. This is
  the single highest-risk step — do it isolated, before screen work, behind its
  own commit so it's easy to bisect.
- **Material You / dynamic color:** scaffold the `dynamicColor` switch in
  `AppTheme` but leave it **OFF** (not prioritized); easy to flip on later.
- Keep `material-icons-extended`; consider curating a smaller icon set later.

---

## Part 7 — Sequencing (incremental, each phase shippable)

1. **P0 — Dependency baseline.** Bump Compose BOM; confirm build + tests green.
   *(isolated commit; gate for everything after.)*
2. **P1 — Token foundation.** Add `tokens/` + `AppTheme.kt`; define Nocturne
   palette/type/shape/spacing/motion; add accessor extensions. App still compiles
   using existing screens (old colors map onto new scheme). *(No visible redesign
   yet — pure plumbing.)*
3. **P2 — Repoint + retire.** Replace `AIChatSandboxTheme` usage with `AppTheme`;
   generalize/retire `StudioTheme` onto the shared foundation. Verify Icons/Vector
   still render with the new accent.
4. **P3 — Bottom nav + Chat** (highest visibility): nav indicator/motion, chat
   list cards + empty state, conversation bubbles + composer + streaming.
5. **P4 — Notes** (editor toolbar/palette active-state + motion, notes list cards).
6. **P5 — Settings** (grouped cards, reskinned controls, theme preview).
7. **P6 — Polish pass:** motion tuning, haptics, empty states, accessibility
   contrast + touch-target audit across all screens.

**Low-risk / high-impact first:** bottom-nav indicator, chat list cards + empty
state, tool-palette active state. **Most expensive:** the BOM bump (P0) and the
chat conversation screen (P3, lots of states).

---

## Part 8 — Verification

Per `CLAUDE.md` (Android SDK is set up in the web/cloud container by the session
hook):
- **Unit tests:** `./gradlew :app:testDebugUnitTest --console=plain`. Treat the
  suite as green if the only failures are the two known pre-existing ones
  (`NoteVectorDrawableExporterTest.textIsSkippedAndCounted`,
  `NoteAiServiceTest.editModeMalformedReplyEmitsError`).
- **Build:** `./gradlew :app:assembleDebug --console=plain` after the BOM bump and
  after each phase.
- **Visual checks:** since this is UI, screenshot each touched screen in dark +
  light and compare against the dated baselines. Use Compose `@Preview`s for new
  token-driven components to iterate fast.
- **Per-phase gate:** build + tests green, screen renders in both themes, motion
  honors reduce-motion.

---

## Open questions / decisions to confirm at build time
- **Accent hue** for Nocturne (iris/violet `#7C6CFF` proposed) — final pick can be
  A/B'd since it's one token.
- **Brand font:** keep system default for v1, or bundle a display/brand typeface?
  (Adds an asset; recommend system default first, revisit in P6.)
- How far to push **light theme** parity given it wasn't a stated priority
  (recommend: correct but not pixel-perfected in v1).
