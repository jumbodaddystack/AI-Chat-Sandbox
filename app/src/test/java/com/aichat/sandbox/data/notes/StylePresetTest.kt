package com.aichat.sandbox.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 7 — tests for the named-style preset catalog and its prompt
 * construction.
 *
 * Pins two things the restyle feature relies on: that [StylePreset.buildInstruction]
 * weaves the look description, every curated constraint, and the
 * subject-preservation guardrails into one body (so the model is told both *what*
 * look and *not to change the subject*), and that [StylePreset.isRestyleOp]
 * admits only the non-additive, non-moving op subset.
 */
class StylePresetTest {

    @Test
    fun catalogHasTheFourMvpPresetsWithUniqueIds() {
        val ids = StylePresetCatalog.PRESETS.map { it.id }
        assertEquals(
            listOf("flat_icon", "line_art", "isometric", "sticker"),
            ids,
        )
        // Ids must be unique so a tap resolves to exactly one preset.
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun byIdResolvesKnownPresetsAndRejectsUnknown() {
        assertNotNull(StylePresetCatalog.byId("flat_icon"))
        assertEquals("Flat icon", StylePresetCatalog.byId("flat_icon")?.displayName)
        assertNull(StylePresetCatalog.byId("watercolor"))
        assertNull(StylePresetCatalog.byId(""))
    }

    @Test
    fun buildInstructionIncludesNameDescriptionAndEveryConstraint() {
        val preset = StylePresetCatalog.LINE_ART
        val body = preset.buildInstruction()
        assertTrue(body.contains(preset.displayName))
        assertTrue(body.contains(preset.promptText))
        preset.constraints.forEach { constraint ->
            assertTrue("missing constraint: $constraint", body.contains(constraint))
        }
    }

    @Test
    fun buildInstructionPinsTheSubjectPreservationGuardrails() {
        // Every preset must tell the model to keep the same subject and not add /
        // remove objects — the core safety property of a named restyle.
        StylePresetCatalog.PRESETS.forEach { preset ->
            val body = preset.buildInstruction()
            assertTrue(
                "${preset.id} missing subject guard",
                body.contains(StylePreset.SUBJECT_GUARD),
            )
            assertTrue(
                "${preset.id} missing no-add guard",
                body.contains(StylePreset.NO_ADD_GUARD),
            )
        }
    }

    @Test
    fun isRestyleOpAdmitsOnlyTheNonAdditiveNonMovingSubset() {
        // Allowed: recolor / restyle / smooth / simplify / replace_with_shape.
        assertTrue(StylePreset.isRestyleOp(EditOp.Recolor(listOf("s_001"), 0xFF112233.toInt())))
        assertTrue(StylePreset.isRestyleOp(EditOp.Restyle(listOf("s_001"), width = 4f, opacity = null)))
        assertTrue(StylePreset.isRestyleOp(EditOp.Smooth(listOf("s_001"), amount = 0.5f)))
        assertTrue(StylePreset.isRestyleOp(EditOp.Simplify(listOf("s_001"), tolerance = 1f)))
        assertTrue(
            StylePreset.isRestyleOp(
                EditOp.ReplaceWithShape("s_001", EditOp.ShapeSpec.Ellipse(0f, 0f, 5f, 5f))
            )
        )

        // Forbidden: moving (transform), additive, destructive, structural.
        assertFalse(StylePreset.isRestyleOp(EditOp.Transform(listOf("s_001"), FloatArray(6))))
        assertFalse(StylePreset.isRestyleOp(EditOp.Delete(listOf("s_001"))))
        assertFalse(StylePreset.isRestyleOp(EditOp.AddShape(EditOp.ShapeSpec.Line(0f, 0f, 1f, 1f), null, null, null)))
        assertFalse(StylePreset.isRestyleOp(EditOp.AddPath(emptyList(), null, null, null)))
        assertFalse(StylePreset.isRestyleOp(EditOp.SetLayer(listOf("s_001"), "L1")))
        assertFalse(StylePreset.isRestyleOp(EditOp.MergePaths(listOf("p_001", "p_002"))))
        assertFalse(StylePreset.isRestyleOp(EditOp.Group(listOf("s_001"))))
    }
}
