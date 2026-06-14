package com.aichat.sandbox.ui.components.notes

import com.aichat.sandbox.data.model.NoteItem

/**
 * Phase **I2 — eraser kind-dispatch** (the "no regression for non-stroke kinds"
 * guarantee of the ink default-on gate; see
 * `docs/ANDROIDX_INK_MIGRATION_PLAN.md` and `docs/INK_I2_PARITY_GATE.md`).
 *
 * The eraser hit-tests **every** [NoteItem] kind — strokes, shapes, stickies,
 * connectors, and paths — through the existing pure-geometry [HitTest] helpers.
 * AndroidX Ink's `PartitionedMesh` hit-testing only understands ink strokes, so
 * the I2 gate must guarantee that adopting ink for *authoring* never quietly
 * routes the *eraser* through a stroke-only mesh path and loses the other four
 * kinds. To make that guarantee testable on the headless JVM (no Android
 * `View`), the per-item hit decision that used to live inline in
 * `DrawingSurface.eraseAtLastSample` is lifted here verbatim.
 *
 * It is pure: the two pieces that depend on `DrawingSurface`'s live state — the
 * decoded-stroke cache and connector routing against the current item mirror —
 * are injected as lambdas, so a unit test can drive every branch with plain
 * payloads and no ink classpath at all. That a non-stroke kind erases here with
 * **no reference to ink geometry** is exactly the property the gate locks in.
 */
object EraserHitTest {

    /** Decoded stroke geometry the caller already has cached. */
    data class StrokeGeom(
        val samples: FloatArray,
        val count: Int,
        /** `[minX, minY, maxX, maxY]` in world units. */
        val bounds: FloatArray,
    )

    /**
     * True if the eraser tip at (`px`, `py`) with world [radius] hits [item].
     * Dispatches by [NoteItem.kind] to the matching pure [HitTest] routine —
     * the same multi-kind behaviour the surface has shipped since phase 12, with
     * no ink-mesh involvement for any kind.
     *
     * @param decodeStroke resolves a stroke item to its cached [StrokeGeom]
     *   (or null when undecodable / empty); the caller owns the cache.
     * @param connectorPolyline resolves a connector item to its routed +
     *   flattened world polyline `[x0,y0,x1,y1,…]` (or null), since routing
     *   depends on the live item mirror.
     */
    fun hits(
        item: NoteItem,
        px: Float,
        py: Float,
        radius: Float,
        decodeStroke: (NoteItem) -> StrokeGeom?,
        connectorPolyline: (NoteItem) -> FloatArray?,
    ): Boolean = when (item.kind) {
        NoteItem.KIND_STROKE -> {
            val decoded = decodeStroke(item)
            decoded != null &&
                HitTest.bboxContainsPoint(decoded.bounds, px, py, radius) &&
                HitTest.pointWithinStroke(decoded.samples, decoded.count, px, py, radius)
        }
        Shape.KIND -> {
            val shape = ShapeCodec.decode(item.payload).shape
            val sb = ShapeCodec.boundsOf(shape)
            sb != null &&
                HitTest.bboxContainsPoint(sb, px, py, radius) &&
                HitTest.shapeContainsPoint(shape, px, py, radius)
        }
        StickyCodec.KIND -> {
            // 11.1 — stickies erase via their rect (expanded by the radius).
            val sb = StickyCodec.boundsOf(StickyCodec.decode(item.payload))
            px >= sb[0] - radius && px <= sb[2] + radius &&
                py >= sb[1] - radius && py <= sb[3] + radius
        }
        ConnectorCodec.KIND -> {
            // 14.2 — erase against the routed polyline (curves flatten), so
            // elbow / curved connectors erase where they actually draw.
            val pts = connectorPolyline(item)
            var segHit = false
            if (pts != null) {
                for (s in 0 until pts.size / 2 - 1) {
                    if (HitTest.shapeContainsPoint(
                            Shape.Line(
                                pts[s * 2], pts[s * 2 + 1],
                                pts[s * 2 + 2], pts[s * 2 + 3],
                            ),
                            px, py, radius,
                        )
                    ) {
                        segHit = true
                        break
                    }
                }
            }
            segHit
        }
        PathCodec.KIND -> {
            // 12.1 — paths erase via the flattened curve (interior counts when
            // closed, mirroring closed polygons).
            val payload = PathCodec.decode(item.payload)
            val pb = PathCodec.boundsOf(payload)
            pb != null &&
                HitTest.bboxContainsPoint(pb, px, py, radius) &&
                HitTest.pathContainsPoint(payload, px, py, radius)
        }
        else -> false
    }
}
