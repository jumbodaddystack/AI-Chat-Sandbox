package com.aichat.sandbox.data.vector

/**
 * Unified import front door for the Vector Art Tune-Up workflow (Phase 9).
 *
 * Detects whether pasted/imported text is Android `VectorDrawable` XML or SVG
 * and routes it to the matching parser, so the rest of the pipeline only ever
 * deals with a [VectorDocument]. Unknown input yields a safe empty document with
 * a warning rather than an exception.
 */
object VectorDocumentImporter {

    fun import(input: String): VectorDocument {
        return when (VectorImportDetector.detect(input)) {
            VectorImportFormat.ANDROID_VECTOR -> AndroidVectorDrawableParser.parse(input)
            VectorImportFormat.SVG -> VectorSvgParser.parse(input)
            VectorImportFormat.UNKNOWN -> VectorDocument(
                viewport = VectorViewport(24f, 24f, 24f, 24f),
                root = VectorGroup(id = "root", children = emptyList()),
                warnings = listOf(
                    VectorWarning(
                        VectorWarning.Codes.IMPORT_UNKNOWN_FORMAT,
                        "Could not detect the input format. Paste Android VectorDrawable XML or SVG.",
                    ),
                ),
                originalXmlBytes = input.toByteArray(Charsets.UTF_8).size,
            )
        }
    }
}
