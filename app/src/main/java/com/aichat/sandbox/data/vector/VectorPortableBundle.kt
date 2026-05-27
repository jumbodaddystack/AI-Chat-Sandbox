package com.aichat.sandbox.data.vector

import com.google.gson.GsonBuilder

/**
 * Builds a portable JSON bundle describing a Vector Art Tune-Up project and its
 * version tree (Phase 9 — export only).
 *
 * Independent of the persistence layer: callers pass plain [ProjectInfo] /
 * [VersionInfo] inputs so this stays pure and JVM-testable. The output is a
 * deterministic, self-contained document (schema 1) that captures each version's
 * canonical Android XML plus its metrics/warnings, so a project can be shared or
 * archived. Bundle *import* is intentionally out of scope for Phase 9.
 */
object VectorPortableBundle {

    const val SCHEMA: Int = 1
    const val KIND: String = "vector_tuneup_project"

    data class ProjectInfo(
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
    )

    data class VersionInfo(
        val id: String,
        val parentId: String?,
        val label: String,
        val mode: String,
        val instruction: String,
        val xml: String,
        val metrics: VectorMetrics,
        val warnings: List<VectorWarning>,
        val reportSummary: String?,
        val createdAt: Long,
    )

    fun build(project: ProjectInfo, versions: List<VersionInfo>): String =
        GSON.toJson(Bundle(schema = SCHEMA, kind = KIND, project = project, versions = versions))

    // Serialized wrapper — field order is the on-disk JSON order.
    private data class Bundle(
        val schema: Int,
        val kind: String,
        val project: ProjectInfo,
        val versions: List<VersionInfo>,
    )

    private val GSON = GsonBuilder().serializeNulls().create()
}
