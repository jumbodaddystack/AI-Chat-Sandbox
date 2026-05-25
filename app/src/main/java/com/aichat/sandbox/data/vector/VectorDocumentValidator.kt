package com.aichat.sandbox.data.vector

/**
 * Cheap structural sanity checks over a parsed [VectorDocument].
 *
 * Distinct from [AndroidVectorDrawableParser]'s warnings (which describe what
 * the *parse* could not handle), the validator flags content that parsed fine
 * but is suspect — degenerate viewports, paths with no geometry, paths that
 * would render nothing, negative stroke widths. It feeds the diagnostics panel
 * in later phases and never throws.
 */
object VectorDocumentValidator {

    fun validate(document: VectorDocument): List<VectorWarning> {
        val warnings = ArrayList<VectorWarning>()
        val vp = document.viewport

        if (vp.viewportWidth <= 0f || vp.viewportHeight <= 0f) {
            warnings += VectorWarning(
                VectorWarning.Codes.NON_POSITIVE_VIEWPORT,
                "Viewport has non-positive dimensions " +
                    "(${vp.viewportWidth} x ${vp.viewportHeight})",
            )
        }
        if (vp.widthDp <= 0f || vp.heightDp <= 0f) {
            warnings += VectorWarning(
                VectorWarning.Codes.NON_POSITIVE_VIEWPORT,
                "Intrinsic size has non-positive dimensions " +
                    "(${vp.widthDp}dp x ${vp.heightDp}dp)",
            )
        }

        for (path in document.allPaths()) {
            val hasGeometry = path.pathData.isNotBlank() ||
                (path.commands?.isNotEmpty() == true)
            if (!hasGeometry) {
                warnings += VectorWarning(
                    VectorWarning.Codes.MISSING_PATH_DATA,
                    "Path has blank path data",
                    path.id,
                )
            }

            val style = path.style
            if (style.fillColor == null && style.strokeColor == null) {
                warnings += VectorWarning(
                    VectorWarning.Codes.NO_FILL_OR_STROKE,
                    "Path has neither fill nor stroke and will not render",
                    path.id,
                )
            }

            val sw = style.strokeWidth
            if (sw != null && sw < 0f) {
                warnings += VectorWarning(
                    VectorWarning.Codes.NEGATIVE_STROKE_WIDTH,
                    "Path has negative stroke width ($sw)",
                    path.id,
                )
            }
        }

        return warnings
    }
}
