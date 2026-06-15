package com.aichat.sandbox.data.ink.experimental

/**
 * Phase **I4 — isolated 1.1-alpha programmable-brush path** (deliberately a
 * stub).
 *
 * The migration plan (`docs/ANDROIDX_INK_MIGRATION_PLAN.md`, I4 sequencing and
 * the "Alpha vs stable" risk) requires that any **1.1-alpha** programmable-brush
 * experiment be isolated behind its own path so the alpha API "never blocks the
 * stable adapter or the authoring migration." This package *is* that path, and
 * right now it is intentionally empty of alpha code:
 *
 *  - The deterministic, shipping `BrushPreset → Brush/BrushFamily` adapter lives
 *    entirely on the **stable `ink-brush:1.0.0`** artifact in
 *    [com.aichat.sandbox.data.ink.InkBrushFamilies] (custom `BrushTip` /
 *    `BrushBehavior`, opt-in-annotated but stable). It does **not** depend on
 *    anything here.
 *  - The 1.1-alpha dependency (`androidx.ink:ink-brush:1.1.0-alphaNN`) is **not**
 *    added to `app/build.gradle.kts`. Adding it later must be confined to this
 *    package and pinned to an exact alpha version, so a churning alpha API can
 *    never break `:app:assembleDebug` or the unit-test classpath.
 *
 * ## What is parked here (and why it can't be done on stable today)
 *
 *  1. **Jitter** (`BrushPreset.jitter`). The current engine adds deterministic
 *     per-sample width jitter. Stable 1.0.0 `BrushBehavior.Source` exposes
 *     pressure / tilt / orientation / speed / direction / time / distance — but
 *     **no randomized / noise / seed source**, so a faithful jitter brush is not
 *     expressible on stable. It needs the 1.1 randomized-behavior source. Until
 *     then jitter is a documented deferral, not a stable-adapter gap.
 *
 *  2. **AI-emitted programmable brushes (N1, the richer form).** The
 *     deterministic spec→preset mapping in `BrushSpecParser` already gives the AI
 *     brush designer a stable, shippable target (it maps onto the I4 stable
 *     families). The *programmatic* form — the model emitting a full
 *     `BrushFamily` / multi-`TextureLayer` / randomized-behavior graph and us
 *     building it directly via the 1.1 public `Brush.Builder` — is the alpha
 *     experiment that belongs here once 1.1 stabilizes.
 *
 * Keeping this as a documented stub (no alpha import, no alpha dependency) is the
 * isolation: there is a single, obvious place for the alpha work, and its
 * absence today is what keeps the stable build green.
 */
object InkProgrammableBrush {

    /**
     * True once the isolated 1.1-alpha programmable-brush path is wired in. Stays
     * `false` until the alpha dependency is intentionally added *to this package
     * only*. Callers (e.g. a future AI brush designer that wants the full
     * programmatic form) branch on this and fall back to the stable
     * `BrushSpecParser` → `BrushPreset` → [com.aichat.sandbox.data.ink.InkBrushFamilies]
     * path, which is always available.
     */
    const val ALPHA_PROGRAMMABLE_BRUSHES_ENABLED: Boolean = false
}
