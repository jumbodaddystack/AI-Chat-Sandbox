package com.aichat.sandbox.data.ink.parity

import androidx.ink.brush.InputToolType
import com.aichat.sandbox.data.ink.InkInterop
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.ConnectorCodec
import com.aichat.sandbox.ui.components.notes.ConnectorRouter
import com.aichat.sandbox.ui.components.notes.EraserHitTest
import com.aichat.sandbox.ui.components.notes.HitTest
import com.aichat.sandbox.ui.components.notes.PathCodec
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.StickyCodec
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase **I2 — eraser parity gate** (headless slice).
 *
 * The ink default-on checklist demands an explicit guarantee that adopting
 * AndroidX Ink for *authoring* introduces **no regression for the eraser on
 * non-stroke `NoteItem` kinds** — shapes, stickies, connectors, and paths. ink's
 * `PartitionedMesh` hit-testing only understands ink strokes; the gate must
 * prove the eraser never silently routes through a stroke-only mesh path and
 * drops the other four kinds.
 *
 * These are pure-JVM tests over [EraserHitTest] — the helper the surface's
 * `eraseAtLastSample` now delegates to — so the whole multi-kind dispatch is
 * verified with no Android `View` and no ink mesh. Two properties are locked in:
 *  1. every kind hits/misses through its existing [HitTest] geometry, and
 *  2. a committed **ink-authored** stroke (round-tripped through [InkInterop])
 *     erases byte-for-byte identically to a hand-drawn stroke.
 */
class EraserHitTestParityTest {

    private val radius = 6f

    /** Decode a stroke payload exactly as `DrawingSurface.decode` does. */
    private val decodeStroke: (NoteItem) -> EraserHitTest.StrokeGeom? = { item ->
        val samples = StrokeCodec.decode(item.payload)
        val count = samples.size / StrokeCodec.FLOATS_PER_SAMPLE
        val bounds = HitTest.boundsOf(samples, count)
        if (count < 1 || bounds == null) null
        else EraserHitTest.StrokeGeom(samples, count, bounds)
    }

    /** Route + flatten a connector exactly as `DrawingSurface` does (unbound here). */
    private val connectorPolyline: (NoteItem) -> FloatArray = { item ->
        ConnectorRouter.flatten(
            ConnectorRouter.route(ConnectorCodec.decode(item.payload)) { null },
        )
    }

    private fun item(kind: String, payload: ByteArray, tool: String? = null) = NoteItem(
        noteId = "n",
        zIndex = 0,
        kind = kind,
        tool = tool,
        colorArgb = 0xFF000000.toInt(),
        baseWidthPx = 4f,
        payload = payload,
    )

    private fun hit(item: NoteItem, px: Float, py: Float) =
        EraserHitTest.hits(item, px, py, radius, decodeStroke, connectorPolyline)

    // ── Sample data ──────────────────────────────────────────────────────────

    /** Horizontal stroke from (0,100) to (200,100) as packed [x,y,p,t]*. */
    private fun strokeSamples(n: Int = 32): FloatArray {
        val out = FloatArray(n * StrokeCodec.FLOATS_PER_SAMPLE)
        for (i in 0 until n) {
            val t = i.toFloat() / (n - 1)
            val b = i * StrokeCodec.FLOATS_PER_SAMPLE
            out[b] = 200f * t       // x
            out[b + 1] = 100f       // y
            out[b + 2] = 0.6f       // pressure
            out[b + 3] = 0.15f      // tilt
        }
        return out
    }

    // ── Per-kind hit / miss ──────────────────────────────────────────────────

    @Test
    fun strokeKindHitsAndMisses() {
        val stroke = item(NoteItem.KIND_STROKE, StrokeCodec.encode(strokeSamples()), tool = "pen")
        assertTrue("on the line", hit(stroke, 100f, 100f))
        assertFalse("far below", hit(stroke, 100f, 300f))
    }

    @Test
    fun shapeKindHitsAndMisses() {
        val shape = item(Shape.KIND, ShapeCodec.encode(Shape.Rect(50f, 50f, 150f, 150f)))
        assertTrue("inside rect", hit(shape, 100f, 100f))
        assertFalse("outside rect", hit(shape, 400f, 400f))
    }

    @Test
    fun stickyKindHitsAndMisses() {
        val sticky = item(
            StickyCodec.KIND,
            StickyCodec.encode(StickyCodec.newAt(100f, 100f, fillArgb = 0xFFFFFF00.toInt(), size = 80f)),
        )
        assertTrue("inside sticky rect", hit(sticky, 100f, 100f))
        assertFalse("far from sticky", hit(sticky, 400f, 400f))
    }

    @Test
    fun connectorKindHitsAndMisses() {
        val connector = item(
            ConnectorCodec.KIND,
            ConnectorCodec.encode(
                ConnectorCodec.ConnectorPayload(
                    fromItemId = null, fromAnchor = ConnectorCodec.ANCHOR_CENTER,
                    toItemId = null, toAnchor = ConnectorCodec.ANCHOR_CENTER,
                    x0 = 0f, y0 = 0f, x1 = 200f, y1 = 0f,
                    routeStyle = ConnectorCodec.ROUTE_STRAIGHT,
                ),
            ),
        )
        assertTrue("on the connector segment", hit(connector, 100f, 0f))
        assertFalse("off the connector segment", hit(connector, 100f, 200f))
    }

    @Test
    fun pathKindHitsAndMisses() {
        val square = listOf(
            PathCodec.Anchor(0f, 0f), PathCodec.Anchor(100f, 0f),
            PathCodec.Anchor(100f, 100f), PathCodec.Anchor(0f, 100f),
        )
        val path = item(PathCodec.KIND, PathCodec.encode(PathCodec.PathPayload(square, closed = true)))
        assertTrue("inside closed path", hit(path, 50f, 50f))
        assertFalse("outside closed path", hit(path, 400f, 400f))
    }

    @Test
    fun unknownKindNeverHits() {
        val unknown = item("text", byteArrayOf(1, 2, 3))
        assertFalse(hit(unknown, 0f, 0f))
    }

    // ── The core gate guarantee ──────────────────────────────────────────────

    /**
     * The non-stroke kinds must hit-test **without ever decoding stroke
     * geometry** — the exact property that breaks if the eraser is backed by an
     * ink stroke-only mesh. We assert it directly: a [decodeStroke] that throws
     * proves shapes / stickies / connectors / paths erase via their own
     * geometry and never touch the stroke path.
     */
    @Test
    fun nonStrokeKindsDoNotDependOnStrokeGeometry() {
        val explode: (NoteItem) -> EraserHitTest.StrokeGeom? = {
            throw AssertionError("non-stroke kind must not decode stroke geometry")
        }
        fun hitNoStroke(item: NoteItem, px: Float, py: Float) =
            EraserHitTest.hits(item, px, py, radius, explode, connectorPolyline)

        val shape = item(Shape.KIND, ShapeCodec.encode(Shape.Rect(50f, 50f, 150f, 150f)))
        val sticky = item(
            StickyCodec.KIND,
            StickyCodec.encode(StickyCodec.newAt(100f, 100f, fillArgb = 0xFF00FF00.toInt(), size = 80f)),
        )
        val connector = item(
            ConnectorCodec.KIND,
            ConnectorCodec.encode(
                ConnectorCodec.ConnectorPayload(
                    fromItemId = null, fromAnchor = ConnectorCodec.ANCHOR_CENTER,
                    toItemId = null, toAnchor = ConnectorCodec.ANCHOR_CENTER,
                    x0 = 0f, y0 = 0f, x1 = 200f, y1 = 0f,
                ),
            ),
        )
        val path = item(
            PathCodec.KIND,
            PathCodec.encode(
                PathCodec.PathPayload(
                    listOf(
                        PathCodec.Anchor(0f, 0f), PathCodec.Anchor(100f, 0f),
                        PathCodec.Anchor(100f, 100f), PathCodec.Anchor(0f, 100f),
                    ),
                    closed = true,
                ),
            ),
        )

        assertTrue(hitNoStroke(shape, 100f, 100f))
        assertTrue(hitNoStroke(sticky, 100f, 100f))
        assertTrue(hitNoStroke(connector, 100f, 0f))
        assertTrue(hitNoStroke(path, 50f, 50f))
    }

    /**
     * A committed **ink-authored** stroke is converted back to a canonical
     * [StrokeCodec] payload the instant the pen lifts, so the eraser must treat
     * it byte-for-byte like a hand-drawn stroke. We build the same geometry both
     * ways and assert the eraser agrees at a grid of probe points.
     */
    @Test
    fun inkAuthoredStrokeErasesIdenticallyToHandDrawn() {
        val samples = strokeSamples()
        val handDrawn = item(NoteItem.KIND_STROKE, StrokeCodec.encode(samples), tool = "pen")

        // The ink-authoring commit path: samples -> ink Stroke -> fromStroke.
        val brush = InkInterop.brushForTool("pen", 0xFF000000.toInt(), baseWidthPx = 4f)
        val stroke = InkInterop.toStroke(StrokeCodec.encode(samples), brush, InputToolType.STYLUS)
        val inkAuthored = item(NoteItem.KIND_STROKE, InkInterop.fromStroke(stroke, null), tool = "pen")

        // The payloads are byte-identical (ink is a pure passthrough here), so
        // the eraser cannot tell them apart — assert both the bytes and the
        // hit-test outcome at a sweep of probe points.
        assertEquals(
            "ink-authored payload is byte-identical to hand-drawn",
            handDrawn.payload.toList(), inkAuthored.payload.toList(),
        )
        var probes = 0
        for (px in 0..200 step 20) {
            for (py in 40..160 step 20) {
                assertEquals(
                    "probe ($px,$py)",
                    hit(handDrawn, px.toFloat(), py.toFloat()),
                    hit(inkAuthored, px.toFloat(), py.toFloat()),
                )
                probes++
            }
        }
        assertTrue("swept a meaningful grid", probes >= 50)
    }

    /** Empty / undecodable stroke payloads never hit (defensive, matches surface). */
    @Test
    fun emptyStrokeNeverHits() {
        val empty = item(NoteItem.KIND_STROKE, StrokeCodec.encode(FloatArray(0)), tool = "pen")
        assertFalse(hit(empty, 0f, 0f))
    }
}
