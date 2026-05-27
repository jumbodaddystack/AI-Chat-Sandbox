package com.aichat.sandbox.data.vector

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 9 — portable project bundle export JSON. */
class VectorPortableBundleTest {

    private val metrics = VectorMetrics(
        xmlBytes = 100,
        pathCount = 1,
        groupCount = 0,
        commandCount = 2,
        parsedCommandCount = 2,
        unsupportedPathCount = 0,
        estimatedPointCount = 2,
        colorCounts = mapOf("#FF0000" to 1),
        strokePathCount = 1,
        fillPathCount = 0,
        zeroLengthPathCount = 0,
        tinySegmentEstimate = 0,
        duplicateCoordinateEstimate = 0,
        bounds = VectorBounds(0f, 0f, 10f, 10f),
        warnings = emptyList(),
    )

    private fun version(id: String, parentId: String?) = VectorPortableBundle.VersionInfo(
        id = id,
        parentId = parentId,
        label = "Original",
        mode = "ORIGINAL",
        instruction = "Imported source XML",
        xml = "<vector/>",
        metrics = metrics,
        warnings = emptyList(),
        reportSummary = null,
        createdAt = 123L,
    )

    @Test
    fun buildsSchemaAndKind() {
        val json = VectorPortableBundle.build(
            VectorPortableBundle.ProjectInfo("My Vector", 1L, 2L),
            listOf(version("v0", null), version("v1", "v0")),
        )
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals(1, root.get("schema").asInt)
        assertEquals("vector_tuneup_project", root.get("kind").asString)
        assertEquals("My Vector", root.getAsJsonObject("project").get("title").asString)
        assertEquals(2, root.getAsJsonArray("versions").size())
    }

    @Test
    fun preservesVersionLineageAndXml() {
        val json = VectorPortableBundle.build(
            VectorPortableBundle.ProjectInfo("P", 0L, 0L),
            listOf(version("v0", null), version("v1", "v0")),
        )
        val versions = JsonParser.parseString(json).asJsonObject.getAsJsonArray("versions")
        val v1 = versions[1].asJsonObject
        assertEquals("v1", v1.get("id").asString)
        assertEquals("v0", v1.get("parentId").asString)
        assertEquals("<vector/>", v1.get("xml").asString)
        assertTrue(v1.has("metrics"))
    }

    @Test
    fun isDeterministic() {
        val project = VectorPortableBundle.ProjectInfo("P", 1L, 2L)
        val versions = listOf(version("v0", null))
        assertEquals(
            VectorPortableBundle.build(project, versions),
            VectorPortableBundle.build(project, versions),
        )
    }
}
