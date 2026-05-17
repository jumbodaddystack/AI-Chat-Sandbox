package com.aichat.sandbox.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

/**
 * JVM-only coverage of [PdfLayout]. The PDF rendering path itself depends on
 * `android.graphics.pdf.PdfDocument`, which is exercised by the Phase 4.2
 * manual verification matrix — what's testable on the JVM is the pure layout
 * math (fit transform, tiling grid, page-count preview).
 */
class PdfLayoutTest {

    private val eps: Float = 0.01f

    private fun assertClose(expected: Float, actual: Float, tag: String) {
        assertTrue(
            "$tag: expected $expected got $actual (Δ=${abs(expected - actual)})",
            abs(expected - actual) <= eps,
        )
    }

    // ── defaultPageSize ────────────────────────────────────────────────────

    @Test
    fun defaultPageSizeFallsBackToA4ForUnknownCountry() {
        assertEquals(PdfLayout.PageSize.A4, PdfLayout.defaultPageSize(Locale("en", "GB")))
        assertEquals(PdfLayout.PageSize.A4, PdfLayout.defaultPageSize(Locale("ja", "JP")))
        assertEquals(PdfLayout.PageSize.A4, PdfLayout.defaultPageSize(Locale("", "")))
    }

    @Test
    fun defaultPageSizePicksLetterForLetterBelt() {
        assertEquals(PdfLayout.PageSize.LETTER, PdfLayout.defaultPageSize(Locale("en", "US")))
        assertEquals(PdfLayout.PageSize.LETTER, PdfLayout.defaultPageSize(Locale("es", "MX")))
        assertEquals(PdfLayout.PageSize.LETTER, PdfLayout.defaultPageSize(Locale("fr", "CA")))
    }

    // ── fitOnePage ─────────────────────────────────────────────────────────

    @Test
    fun fitOnePageScalesByHeightForTallBounds() {
        // 100×1000 world units into A4 (595×842, 36pt margin) → height-limited.
        val t = PdfLayout.fitOnePage(
            bounds = floatArrayOf(0f, 0f, 100f, 1000f),
            pageSize = PdfLayout.PageSize.A4,
        )
        val usableH = 842f - 72f
        assertClose(usableH / 1000f, t.scale, "scale (tall)")
        // Drawn width = 100 * scale; centred horizontally → tx > marginPt.
        val drawnW = 100f * t.scale
        val expectedTx = 36f + ((595f - 72f) - drawnW) * 0.5f
        assertClose(expectedTx, t.translateX, "translateX (tall)")
        assertClose(36f, t.translateY, "translateY (tall, height-pinned)")
    }

    @Test
    fun fitOnePageScalesByWidthForWideBounds() {
        // 1000×100 world units → width-limited.
        val t = PdfLayout.fitOnePage(
            bounds = floatArrayOf(0f, 0f, 1000f, 100f),
            pageSize = PdfLayout.PageSize.A4,
        )
        val usableW = 595f - 72f
        assertClose(usableW / 1000f, t.scale, "scale (wide)")
        val drawnH = 100f * t.scale
        val expectedTy = 36f + ((842f - 72f) - drawnH) * 0.5f
        assertClose(36f, t.translateX, "translateX (wide, width-pinned)")
        assertClose(expectedTy, t.translateY, "translateY (wide)")
    }

    @Test
    fun fitOnePageRespectsBoundsOriginOffset() {
        // Offset bounds — the transform should pull the origin into place so
        // an item at world (bounds.minX, bounds.minY) lands at the drawn
        // top-left, not at the page origin.
        val t = PdfLayout.fitOnePage(
            bounds = floatArrayOf(500f, 200f, 600f, 1200f),
            pageSize = PdfLayout.PageSize.A4,
        )
        // x_page = x_world * scale + tx; the bound's min corner should map
        // to the centred drawn-rect top-left.
        val mappedX = 500f * t.scale + t.translateX
        val mappedY = 200f * t.scale + t.translateY
        val drawnW = (600f - 500f) * t.scale
        val drawnH = (1200f - 200f) * t.scale
        val expectedX = 36f + ((595f - 72f) - drawnW) * 0.5f
        val expectedY = 36f + ((842f - 72f) - drawnH) * 0.5f
        assertClose(expectedX, mappedX, "mapped origin X")
        assertClose(expectedY, mappedY, "mapped origin Y")
    }

    @Test
    fun fitOnePageHandlesDegenerateBoundsWithoutNaN() {
        // Zero-area bounds — math should clamp to a sane scale, not NaN/∞.
        val t = PdfLayout.fitOnePage(
            bounds = floatArrayOf(0f, 0f, 0f, 0f),
            pageSize = PdfLayout.PageSize.A4,
        )
        assertTrue("scale must be finite, got ${t.scale}", t.scale.isFinite())
        assertTrue("translateX must be finite", t.translateX.isFinite())
        assertTrue("translateY must be finite", t.translateY.isFinite())
        assertTrue("scale must be positive", t.scale > 0f)
    }

    // ── tile ───────────────────────────────────────────────────────────────

    @Test
    fun tileFitsInSinglePageWhenBoundsAreSmall() {
        val tiles = PdfLayout.tile(
            bounds = floatArrayOf(0f, 0f, 200f, 300f),
            pageSize = PdfLayout.PageSize.A4,
        )
        assertEquals(1, tiles.size)
        val only = tiles.single()
        assertEquals(0, only.pageIndex)
        assertEquals(0, only.column)
        assertEquals(0, only.row)
        assertArrayEqualsEps(floatArrayOf(0f, 0f, 200f, 300f), only.worldBounds)
    }

    @Test
    fun tileProducesCeilWidthOverUsableWidthColumns() {
        val pageSize = PdfLayout.PageSize.A4
        val usableW = pageSize.widthPt - 72f
        val usableH = pageSize.heightPt - 72f
        // Make the bounds 2.5 columns × 1.1 rows → expect 3 × 2 pages.
        val bounds = floatArrayOf(
            0f, 0f,
            usableW * 2.5f, usableH * 1.1f,
        )
        val tiles = PdfLayout.tile(bounds, pageSize)
        assertEquals(3 * 2, tiles.size)
        // Reading order: row 0 columns 0..2, then row 1 columns 0..2.
        assertEquals(0 to 0, tiles[0].column to tiles[0].row)
        assertEquals(1 to 0, tiles[1].column to tiles[1].row)
        assertEquals(2 to 0, tiles[2].column to tiles[2].row)
        assertEquals(0 to 1, tiles[3].column to tiles[3].row)
        assertEquals(2 to 1, tiles[5].column to tiles[5].row)
        // pageIndex is 0..N-1, contiguous.
        tiles.forEachIndexed { i, t -> assertEquals(i, t.pageIndex) }
    }

    @Test
    fun tileSlicesAreContiguousWithNoOverlap() {
        val pageSize = PdfLayout.PageSize.LETTER
        val bounds = floatArrayOf(-100f, -50f, 1700f, 1600f)
        val tiles = PdfLayout.tile(bounds, pageSize)
        // Within a row, adjacent columns share an edge exactly.
        val byRow = tiles.groupBy { it.row }
        for ((_, row) in byRow) {
            val sorted = row.sortedBy { it.column }
            for (i in 1 until sorted.size) {
                assertClose(
                    sorted[i - 1].worldBounds[2], sorted[i].worldBounds[0],
                    "row ${row.first().row} column ${i - 1}|$i x-seam",
                )
            }
        }
        // Adjacent rows share a y-seam at every column.
        val rowKeys = byRow.keys.sorted()
        for (i in 1 until rowKeys.size) {
            val above = byRow.getValue(rowKeys[i - 1]).sortedBy { it.column }
            val below = byRow.getValue(rowKeys[i]).sortedBy { it.column }
            for (c in above.indices) {
                assertClose(
                    above[c].worldBounds[3], below[c].worldBounds[1],
                    "col $c row ${i - 1}|$i y-seam",
                )
            }
        }
    }

    @Test
    fun tileCoversFullBoundsExactly() {
        val pageSize = PdfLayout.PageSize.A4
        val bounds = floatArrayOf(10f, 20f, 1500f, 2100f)
        val tiles = PdfLayout.tile(bounds, pageSize)
        // Top-left tile starts at bounds origin; bottom-right ends at bounds max.
        val tl = tiles.first { it.column == 0 && it.row == 0 }
        assertClose(bounds[0], tl.worldBounds[0], "tl.minX")
        assertClose(bounds[1], tl.worldBounds[1], "tl.minY")
        val maxCol = tiles.maxOf { it.column }
        val maxRow = tiles.maxOf { it.row }
        val br = tiles.first { it.column == maxCol && it.row == maxRow }
        assertClose(bounds[2], br.worldBounds[2], "br.maxX")
        assertClose(bounds[3], br.worldBounds[3], "br.maxY")
    }

    @Test
    fun tileReturnsSinglePageForDegenerateBounds() {
        val tiles = PdfLayout.tile(
            bounds = floatArrayOf(0f, 0f, 0f, 0f),
            pageSize = PdfLayout.PageSize.A4,
        )
        assertEquals(1, tiles.size)
    }

    // ── tileTranslation ────────────────────────────────────────────────────

    @Test
    fun tileTranslationPositionsSliceAtMarginCorner() {
        val tile = PdfLayout.TileSpec(
            pageIndex = 4,
            column = 1,
            row = 1,
            worldBounds = floatArrayOf(523f, 770f, 1046f, 1540f),
        )
        val t = PdfLayout.tileTranslation(tile, marginPt = 36f)
        assertEquals(1f, t.scale, 0f)
        // The slice's top-left should map to (marginPt, marginPt) on the page.
        val mappedX = tile.worldBounds[0] * t.scale + t.translateX
        val mappedY = tile.worldBounds[1] * t.scale + t.translateY
        assertClose(36f, mappedX, "mappedX")
        assertClose(36f, mappedY, "mappedY")
    }

    // ── pageCount ──────────────────────────────────────────────────────────

    @Test
    fun pageCountIsOneInFitMode() {
        assertEquals(
            1,
            PdfLayout.pageCount(
                bounds = floatArrayOf(0f, 0f, 9999f, 9999f),
                pageSize = PdfLayout.PageSize.A4,
                mode = PdfLayout.Mode.FIT_ONE_PAGE,
            ),
        )
    }

    @Test
    fun pageCountTracksTileGridInTileMode() {
        val pageSize = PdfLayout.PageSize.A4
        val usableW = pageSize.widthPt - 72f
        val usableH = pageSize.heightPt - 72f
        // 4 × 3 tiles by construction.
        val bounds = floatArrayOf(0f, 0f, usableW * 3.2f, usableH * 2.1f)
        val n = PdfLayout.pageCount(bounds, pageSize, PdfLayout.Mode.TILE)
        assertEquals(4 * 3, n)
        // And differs from fit mode.
        assertNotEquals(
            n,
            PdfLayout.pageCount(bounds, pageSize, PdfLayout.Mode.FIT_ONE_PAGE),
        )
    }

    private fun assertArrayEqualsEps(expected: FloatArray, actual: FloatArray) {
        assertEquals("array size", expected.size, actual.size)
        for (i in expected.indices) {
            assertClose(expected[i], actual[i], "index $i")
        }
    }
}
