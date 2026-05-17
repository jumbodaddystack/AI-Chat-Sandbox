package com.aichat.sandbox.data.notes

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Pure layout math for PDF export (sub-phase 4.2).
 *
 * The note canvas is infinite; printing it on a fixed-size page requires the
 * user to pick a strategy at export time:
 *
 *  - [Mode.FIT_ONE_PAGE] — uniformly scale the note's geometry bounds to fit
 *    inside the page's printable area (page size minus margins), centred.
 *  - [Mode.TILE] — keep world units at 1:1 with page points and slice the
 *    bounds into a grid of pages, reading order (left→right, top→bottom).
 *
 * All math is JVM-only so the policy is exercisable in unit tests without an
 * Android framework. World units in this app are interpreted as PostScript
 * points (1 pt = 1/72 inch); a typical pen stroke around 4 world units thus
 * prints at ≈ 1.4 mm, comfortable on paper.
 */
object PdfLayout {

    /**
     * Standard page sizes in points. Width/height are *portrait* — we don't
     * offer a landscape switch; the user can rotate after exporting.
     */
    enum class PageSize(val widthPt: Int, val heightPt: Int) {
        A4(595, 842),
        LETTER(612, 792),
    }

    enum class Mode { FIT_ONE_PAGE, TILE }

    /** Half-inch margin on every side — standard for letterhead-style output. */
    const val DEFAULT_MARGIN_PT: Float = 36f

    /**
     * ISO 216 (A4) is universal *except* in the Letter belt. Two-letter ISO
     * country codes; matches the historical list used by most cross-platform
     * print stacks (Apple, libreoffice, Chromium).
     */
    private val LETTER_COUNTRIES: Set<String> = setOf(
        "US", "CA", "MX", "DO", "CO", "VE", "PH", "CL", "CR", "GT", "NI", "PA", "PR",
    )

    /**
     * Default page size for the active [locale]. Letter for the Letter belt,
     * A4 everywhere else (including unknown / empty country codes — the safer
     * fall-back since A4 is the ISO standard).
     */
    fun defaultPageSize(locale: Locale = Locale.getDefault()): PageSize {
        val country = locale.country?.uppercase(Locale.ROOT).orEmpty()
        return if (country in LETTER_COUNTRIES) PageSize.LETTER else PageSize.A4
    }

    /**
     * Affine for world→page coords as scale + translate. Stored as plain floats
     * so callers can build an `android.graphics.Matrix` (or any other
     * representation) without coupling [PdfLayout] to the Android graphics API.
     */
    data class PageTransform(val scale: Float, val translateX: Float, val translateY: Float)

    /**
     * One page of a tiled layout. [worldBounds] is the sub-rect of the note's
     * geometry that lands on this page (already clamped to the full bounds, so
     * edge tiles can be narrower than the interior). [pageIndex] is 0-based.
     */
    data class TileSpec(
        val pageIndex: Int,
        val column: Int,
        val row: Int,
        val worldBounds: FloatArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TileSpec) return false
            return pageIndex == other.pageIndex &&
                column == other.column &&
                row == other.row &&
                worldBounds.contentEquals(other.worldBounds)
        }

        override fun hashCode(): Int {
            var result = pageIndex
            result = 31 * result + column
            result = 31 * result + row
            result = 31 * result + worldBounds.contentHashCode()
            return result
        }
    }

    /**
     * Fit-to-one-page transform: scale [bounds] uniformly to fill the page's
     * usable area, then centre. Returns identity-style scale when bounds are
     * degenerate so we never divide by zero or hand back NaN.
     */
    fun fitOnePage(
        bounds: FloatArray,
        pageSize: PageSize,
        marginPt: Float = DEFAULT_MARGIN_PT,
    ): PageTransform {
        require(bounds.size == 4) { "bounds must be [minX, minY, maxX, maxY]" }
        val worldW = max(1f, bounds[2] - bounds[0])
        val worldH = max(1f, bounds[3] - bounds[1])
        val usableW = max(1f, pageSize.widthPt - 2f * marginPt)
        val usableH = max(1f, pageSize.heightPt - 2f * marginPt)
        val scale = min(usableW / worldW, usableH / worldH)
        val drawnW = worldW * scale
        val drawnH = worldH * scale
        // Centre the drawn rect inside the usable area, then subtract the
        // bounds origin so an item at world (bounds[0], bounds[1]) lands at
        // the top-left of the drawn rect.
        val tx = marginPt + (usableW - drawnW) * 0.5f - bounds[0] * scale
        val ty = marginPt + (usableH - drawnH) * 0.5f - bounds[1] * scale
        return PageTransform(scale, tx, ty)
    }

    /**
     * Tile [bounds] across a `cols × rows` grid of pages. Each interior tile
     * is `usableW × usableH` in world units; the last column/row clips to the
     * bounds so the union of every tile equals [bounds] exactly (no overlap,
     * no gaps). Reading order: left→right within a row, top→bottom across rows.
     *
     * Returns a single page for degenerate bounds so an "empty" note still
     * exports a one-page PDF instead of failing.
     */
    fun tile(
        bounds: FloatArray,
        pageSize: PageSize,
        marginPt: Float = DEFAULT_MARGIN_PT,
    ): List<TileSpec> {
        require(bounds.size == 4) { "bounds must be [minX, minY, maxX, maxY]" }
        val worldW = max(0f, bounds[2] - bounds[0])
        val worldH = max(0f, bounds[3] - bounds[1])
        val usableW = max(1f, pageSize.widthPt - 2f * marginPt)
        val usableH = max(1f, pageSize.heightPt - 2f * marginPt)
        val cols = max(1, ceil(worldW / usableW).toInt())
        val rows = max(1, ceil(worldH / usableH).toInt())
        val tiles = ArrayList<TileSpec>(cols * rows)
        var idx = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val minX = bounds[0] + c * usableW
                val minY = bounds[1] + r * usableH
                val maxX = if (c == cols - 1) bounds[2] else minX + usableW
                val maxY = if (r == rows - 1) bounds[3] else minY + usableH
                tiles.add(TileSpec(idx++, c, r, floatArrayOf(minX, minY, maxX, maxY)))
            }
        }
        return tiles
    }

    /**
     * Translation that positions [tile]'s world rect at the page's top-left
     * margin corner. Scale is `1f` — tile mode preserves world units at 1:1
     * with page points so a stroke that's 4 world units thick prints at
     * roughly 1.4 mm regardless of how many pages the layout spans.
     */
    fun tileTranslation(tile: TileSpec, marginPt: Float = DEFAULT_MARGIN_PT): PageTransform =
        PageTransform(
            scale = 1f,
            translateX = marginPt - tile.worldBounds[0],
            translateY = marginPt - tile.worldBounds[1],
        )

    /**
     * Convenience for the dialog's live "this will produce N pages" preview.
     * Fit mode always emits exactly one page; tile mode reports the grid size.
     */
    fun pageCount(
        bounds: FloatArray,
        pageSize: PageSize,
        mode: Mode,
        marginPt: Float = DEFAULT_MARGIN_PT,
    ): Int = when (mode) {
        Mode.FIT_ONE_PAGE -> 1
        Mode.TILE -> tile(bounds, pageSize, marginPt).size
    }
}
