package com.aichat.sandbox.data.vector

/**
 * The portable formats a Vector Art Tune-Up version can be exported as (Phase 9).
 *
 * [extension] and [mimeType] drive the export file name and the share intent's
 * content type so each format opens in the right downstream tool.
 */
enum class VectorExportFormat(
    val extension: String,
    val mimeType: String,
) {
    ANDROID_VECTOR_XML("xml", "text/xml"),
    SVG("svg", "image/svg+xml"),
    PROJECT_BUNDLE("json", "application/json"),
}
