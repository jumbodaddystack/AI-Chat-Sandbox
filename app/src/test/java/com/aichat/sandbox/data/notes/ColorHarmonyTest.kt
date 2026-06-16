package com.aichat.sandbox.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 — tests for the local colour-theory fallback. Pure JVM maths, so this
 * runs without `android.graphics.Color`. Covers seed selection, the per-scheme
 * swatch counts/contract, HSV round-tripping, and the deterministic
 * existing-colour → swatch assignment plan.
 */
class ColorHarmonyTest {

    @Test
    fun everySchemeProducesContractSizedDistinctOpaqueSwatches() {
        val seed = 0xFF3F6FB3.toInt()
        for (scheme in PaletteScheme.entries) {
            val swatches = ColorHarmony.generate(scheme, seed)
            assertTrue(
                "${scheme.label} swatch count ${swatches.size}",
                swatches.size in PaletteSuggestion.MIN_SWATCHES..PaletteSuggestion.MAX_SWATCHES,
            )
            // All opaque.
            assertTrue(swatches.all { (it ushr 24) == 0xFF })
            // All distinct.
            assertEquals(swatches.size, swatches.toSet().size)
        }
    }

    @Test
    fun seedFallsBackToDefaultForEmptyOrTransparentInput() {
        // No colours → a usable (opaque) seed, not 0.
        val seed = ColorHarmony.seedFrom(emptyList())
        assertEquals(0xFF, seed ushr 24)
    }

    @Test
    fun seedPicksMostFrequentColour() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val seed = ColorHarmony.seedFrom(listOf(blue, red, red, blue, red))
        assertEquals(red, seed)
    }

    @Test
    fun degenerateGreySeedStillYieldsThreePlusSwatches() {
        // Pure black has zero saturation/value; the generator floors S/V and
        // pads duplicates so we never drop below the minimum.
        val swatches = ColorHarmony.generate(PaletteScheme.MONOCHROMATIC, 0xFF000000.toInt())
        assertTrue(swatches.size >= PaletteSuggestion.MIN_SWATCHES)
        assertEquals(swatches.size, swatches.toSet().size)
    }

    @Test
    fun hsvRoundTripsApproximately() {
        val original = 0xFF2E5AA8.toInt()
        val hsv = ColorHarmony.rgbToHsv(original)
        val back = ColorHarmony.hsvToRgb(hsv[0], hsv[1], hsv[2])
        // Allow ±1 per channel for rounding.
        for (shift in intArrayOf(16, 8, 0)) {
            val a = (original shr shift) and 0xFF
            val b = (back shr shift) and 0xFF
            assertTrue("channel diff ${Math.abs(a - b)}", Math.abs(a - b) <= 1)
        }
    }

    @Test
    fun complementaryHueIsOppositeOfSeed() {
        val seed = 0xFFCC2200.toInt() // warm red, hue ~9°
        val swatches = ColorHarmony.generate(PaletteScheme.COMPLEMENTARY, seed)
        val seedHue = ColorHarmony.rgbToHsv(seed)[0]
        // Some swatch should sit roughly opposite (≈180° away).
        val hasComplement = swatches.any { s ->
            val d = Math.abs(ColorHarmony.rgbToHsv(s)[0] - seedHue)
            val circular = minOf(d, 360f - d)
            circular > 120f
        }
        assertTrue(hasComplement)
    }

    @Test
    fun assignmentsMapEveryDistinctColourToASwatch() {
        val swatches = listOf(0xFF111111.toInt(), 0xFF888888.toInt(), 0xFFEEEEEE.toInt())
        val current = listOf(0xFF202020.toInt(), 0xFFDDDDDD.toInt(), 0xFF202020.toInt())
        val map = ColorHarmony.assignments(current, swatches)
        // Two distinct current colours → two entries.
        assertEquals(2, map.size)
        // Every target is one of the swatches.
        assertTrue(map.values.all { it in swatches })
        // Darker original maps to a darker swatch than the lighter original.
        val darkTarget = map[0xFF202020.toInt()]!!
        val lightTarget = map[0xFFDDDDDD.toInt()]!!
        assertTrue(ColorHarmony.luminance(darkTarget) <= ColorHarmony.luminance(lightTarget))
    }

    @Test
    fun assignmentsAreEmptyWhenNoUsableColours() {
        assertTrue(ColorHarmony.assignments(emptyList(), listOf(0xFF111111.toInt())).isEmpty())
        assertTrue(ColorHarmony.assignments(listOf(0x00FFFFFF), emptyList()).isEmpty())
        // Fully transparent input contributes nothing.
        assertTrue(ColorHarmony.assignments(listOf(0x00123456), listOf(0xFF111111.toInt())).isEmpty())
    }

    @Test
    fun suggestProducesNonEmptyPaletteAndLabel() {
        val suggestion = ColorHarmony.suggest(PaletteScheme.ANALOGOUS, listOf(0xFF3366CC.toInt()))
        assertEquals(PaletteScheme.ANALOGOUS.label, suggestion.schemeName)
        assertTrue(suggestion.swatches.size >= PaletteSuggestion.MIN_SWATCHES)
        assertNotEquals("", suggestion.rationale)
    }
}
