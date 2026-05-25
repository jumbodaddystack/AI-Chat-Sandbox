package com.aichat.sandbox.data.vector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1 — structural sanity warnings over an already-parsed document. */
class VectorDocumentValidatorTest {

    private fun docOf(viewport: VectorViewport, vararg paths: VectorPath): VectorDocument =
        VectorDocument(
            viewport = viewport,
            root = VectorGroup(id = "root", children = paths.map { VectorNode.PathNode(it) }),
        )

    @Test
    fun flagsNonPositiveViewport() {
        val doc = docOf(VectorViewport(0f, 24f, 0f, 24f))
        val warnings = VectorDocumentValidator.validate(doc)
        assertTrue(warnings.any { it.code == VectorWarning.Codes.NON_POSITIVE_VIEWPORT })
    }

    @Test
    fun flagsBlankPathData() {
        val doc = docOf(
            VectorViewport(24f, 24f, 24f, 24f),
            VectorPath(id = "p_001", pathData = "  ", commands = null, style = VectorStyle(fillColor = "#000000")),
        )
        val warnings = VectorDocumentValidator.validate(doc)
        assertTrue(warnings.any { it.code == VectorWarning.Codes.MISSING_PATH_DATA && it.nodeId == "p_001" })
    }

    @Test
    fun flagsPathWithNeitherFillNorStroke() {
        val doc = docOf(
            VectorViewport(24f, 24f, 24f, 24f),
            VectorPath(id = "p_001", pathData = "M0 0 L1 1", style = VectorStyle()),
        )
        val warnings = VectorDocumentValidator.validate(doc)
        assertTrue(warnings.any { it.code == VectorWarning.Codes.NO_FILL_OR_STROKE })
    }

    @Test
    fun flagsNegativeStrokeWidth() {
        val doc = docOf(
            VectorViewport(24f, 24f, 24f, 24f),
            VectorPath(
                id = "p_001",
                pathData = "M0 0 L1 1",
                style = VectorStyle(strokeColor = "#000000", strokeWidth = -2f),
            ),
        )
        val warnings = VectorDocumentValidator.validate(doc)
        assertTrue(warnings.any { it.code == VectorWarning.Codes.NEGATIVE_STROKE_WIDTH })
    }

    @Test
    fun cleanDocumentHasNoWarnings() {
        val doc = docOf(
            VectorViewport(24f, 24f, 24f, 24f),
            VectorPath(
                id = "p_001",
                pathData = "M0 0 L1 1",
                style = VectorStyle(strokeColor = "#000000", strokeWidth = 1f),
            ),
        )
        assertTrue(VectorDocumentValidator.validate(doc).isEmpty())
    }

    @Test
    fun validatorNeverThrowsOnEmptyDocument() {
        val doc = VectorDocument(
            viewport = VectorViewport(24f, 24f, 24f, 24f),
            root = VectorGroup(id = "root", children = emptyList()),
        )
        assertFalse(VectorDocumentValidator.validate(doc).isNotEmpty())
    }
}
