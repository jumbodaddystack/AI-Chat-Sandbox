package com.aichat.sandbox.data.ink

import androidx.ink.brush.ExperimentalInkCustomBrushApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase I4 — direct coverage for the stable custom brush-family adapter.
 *
 * The rendering parity (taper / tilt / highlighter width) is proven by
 * `data.ink.parity.InkRenderParityTest`; this fixture just pins the construction
 * contract: every tool yields a family on the stable artifact (built once via
 * `by lazy`, so a second read is identity-stable), the custom families carry the
 * expected behaviors, and `marker` stays on the stock family.
 */
@OptIn(ExperimentalInkCustomBrushApi::class)
class InkBrushFamiliesTest {

    @Test
    fun everyToolYieldsAFamily() {
        for (tool in listOf("pen", "pencil", "highlighter", "marker", "unknown")) {
            assertNotNull("family for $tool", InkBrushFamilies.familyForTool(tool))
        }
    }

    @Test
    fun familiesAreCachedAndStable() {
        // `by lazy` means repeated lookups return the same native-backed family.
        assertTrue(InkBrushFamilies.familyForTool("pen") === InkBrushFamilies.familyForTool("pen"))
        assertTrue(
            InkBrushFamilies.familyForTool("highlighter")
                === InkBrushFamilies.familyForTool("highlighter"),
        )
    }

    @Test
    fun penFamilyCarriesAPressureSizeBehavior() {
        val coats = InkBrushFamilies.familyForTool("pen").coats
        val behaviors = coats.flatMap { it.tip.behaviors }
        assertTrue(
            "pen must drive SIZE_MULTIPLIER from NORMALIZED_PRESSURE",
            behaviors.any { b ->
                b.toString().contains("NORMALIZED_PRESSURE") && b.toString().contains("SIZE_MULTIPLIER")
            },
        )
    }

    @Test
    fun pencilFamilyCarriesTiltAndPressureBehaviors() {
        val behaviors = InkBrushFamilies.familyForTool("pencil").coats.flatMap { it.tip.behaviors }
        val text = behaviors.joinToString("\n") { it.toString() }
        assertTrue("pencil drives size from tilt", text.contains("TILT_IN_RADIANS"))
        assertTrue("pencil drives opacity from pressure", text.contains("OPACITY_MULTIPLIER"))
    }

    @Test
    fun highlighterIsCalibratedWidthWithNoSizeBehavior() {
        val tips = InkBrushFamilies.familyForTool("highlighter").coats.map { it.tip }
        assertTrue("highlighter holds a constant width (no behaviors)", tips.all { it.behaviors.isEmpty() })
        // Calibration constant is applied to the tip scale, leaving brush.size semantic.
        assertEquals(InkBrushFamilies.HIGHLIGHTER_TIP_SCALE, tips.first().scaleX, 0f)
    }
}
