package com.aichat.sandbox.ui.components.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.input.motionprediction.MotionEventPredictor
import com.aichat.sandbox.data.model.NoteItem
import kotlin.math.hypot

/**
 * Front-buffered ink surface (sub-phase 1.4) + infinite viewport & background
 * layer (sub-phase 1.5).
 *
 * Implementation notes:
 *
 *  - Plain [View] base class rather than the SurfaceView +
 *    `CanvasFrontBufferedRenderer` path mentioned as the primary option in
 *    `STYLUS_NOTES_PHASE_1.md` sub-phase 1.4. The front-buffer library
 *    (`androidx.graphics:graphics-core`) is still RC and its integration with
 *    `AndroidView` is fiddly. Latency reduction comes from variable-width
 *    per-segment rendering, quadratic-Bezier smoothing between sample
 *    midpoints, one-frame look-ahead via [MotionEventPredictor], and history
 *    iteration so we never drop S-Pen samples.
 *
 *  - Stroke samples are stored in **world** coordinates. The viewport
 *    transform is re-applied on every render. Committed strokes are
 *    rasterized to a screen-space scene bitmap on viewport changes (and on
 *    each commit); the live + predicted strokes are drawn directly each
 *    frame under `canvas.translate + scale`.
 *
 *  - Touch routing: any active stylus pointer wins (ink mode). Otherwise
 *    1-finger pan, 2-finger pinch.
 */
class DrawingSurface(context: Context) : View(context) {

    /** Pan / zoom state. */
    val viewport: ViewportController = ViewportController().also {
        it.onChanged = ::onViewportChanged
    }

    /** Per-note background pattern (plain / dot / line / graph). */
    var backgroundStyle: String = BackgroundLayer.STYLE_PLAIN
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var sceneBitmap: Bitmap? = null
    private var sceneCanvas: Canvas? = null
    private var sceneDirty = true

    /** Committed strokes, kept on the view so we can re-rasterize on viewport changes. */
    private var committedItems: List<NoteItem> = emptyList()

    /** Active live-stroke samples packed as `[x, y, pressure, tilt]` per sample, world coords. */
    private var liveSamples: FloatArray =
        FloatArray(INITIAL_SAMPLE_CAPACITY * StrokeCodec.FLOATS_PER_SAMPLE)
    private var liveSampleCount: Int = 0

    /** Look-ahead samples (world coords); re-derived every move event. */
    private var predictedSamples: FloatArray? = null
    private var predictedSampleCount: Int = 0

    private var hoverX: Float = 0f
    private var hoverY: Float = 0f
    private var hoverVisible: Boolean = false

    private var activeTool: String = STROKE_TOOL_PEN
    private var baseWidthPx: Float = DEFAULT_STROKE_WIDTH_PX
    private var inkColor: Int = DEFAULT_INK_COLOR

    private val livePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
        color = DEFAULT_INK_COLOR
    }
    private val predictedPaint = Paint(livePaint).apply { alpha = PREDICTED_ALPHA }
    private val replayPaint = Paint(livePaint)
    private val hoverPaint = Paint().apply {
        style = Paint.Style.FILL
        color = HOVER_COLOR
        isAntiAlias = true
    }
    private val scratchPath = Path()

    private var motionPredictor: MotionEventPredictor? = null

    /** Invoked once per committed stroke. Caller assigns noteId / zIndex. */
    var strokeListener: ((NoteItem) -> Unit)? = null

    // Viewport gesture state — only used for finger input (stylus has its own branch).
    private enum class GestureMode { NONE, PAN, PINCH }
    private var gestureMode: GestureMode = GestureMode.NONE
    private var panLastX: Float = 0f
    private var panLastY: Float = 0f
    private var pinchLastDist: Float = 0f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        motionPredictor = MotionEventPredictor.newInstance(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        motionPredictor = null
    }

    /** Replace the committed-item set and re-rasterize the scene. */
    fun replayItems(items: List<NoteItem>) {
        committedItems = items.toList()
        sceneDirty = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val previous = sceneBitmap
        val next = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        sceneBitmap = next
        sceneCanvas = Canvas(next)
        previous?.recycle()
        sceneDirty = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Background runs in screen space so stroke widths stay perceptually
        // constant across zoom levels; the function handles paper fill too.
        BackgroundLayer.draw(canvas, viewport, backgroundStyle, width, height)

        if (sceneDirty) {
            rasterizeScene()
            sceneDirty = false
        }
        sceneBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // Live + predicted strokes are stored in world coords; transform on draw
        // so the in-progress stroke moves with pan/zoom alongside the scene.
        if (liveSampleCount > 0 || predictedSampleCount > 0) {
            canvas.save()
            canvas.translate(viewport.offsetX, viewport.offsetY)
            canvas.scale(viewport.scale, viewport.scale)
            if (liveSampleCount > 0) {
                livePaint.color = inkColor
                StrokeRenderer.drawStrokePath(
                    canvas, livePaint, liveSamples, liveSampleCount,
                    baseWidthPx, activeTool, scratchPath,
                )
            }
            val predicted = predictedSamples
            if (predicted != null && predictedSampleCount > 0) {
                predictedPaint.color = inkColor
                predictedPaint.alpha = PREDICTED_ALPHA
                StrokeRenderer.drawStrokePath(
                    canvas, predictedPaint, predicted, predictedSampleCount,
                    baseWidthPx, activeTool, scratchPath,
                )
            }
            canvas.restore()
        }

        if (hoverVisible) {
            canvas.drawCircle(hoverX, hoverY, HOVER_RADIUS_PX, hoverPaint)
        }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        // Hover cursor follows the stylus only.
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
            return super.onHoverEvent(event)
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hoverX = event.x
                hoverY = event.y
                hoverVisible = true
                invalidate()
                true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (hoverVisible) {
                    hoverVisible = false
                    invalidate()
                }
                true
            }
            else -> super.onHoverEvent(event)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val stylusIdx = stylusPointerIndex(event)
        if (stylusIdx >= 0) {
            // Stylus + finger: ink wins, drop any in-flight viewport gesture
            // so a stray finger doesn't pan mid-stroke.
            gestureMode = GestureMode.NONE
            return handleStylusEvent(event, stylusIdx)
        }
        return handleViewportEvent(event)
    }

    private fun stylusPointerIndex(event: MotionEvent): Int {
        for (i in 0 until event.pointerCount) {
            if (event.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS) return i
        }
        return -1
    }

    private fun handleStylusEvent(event: MotionEvent, idx: Int): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                liveSampleCount = 0
                clearPredicted()
                hoverVisible = false
                appendStylusSample(event, idx)
                motionPredictor?.record(event)
                invalidate()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                motionPredictor?.record(event)
                // S-Pen samples faster than the frame rate; iterating history
                // avoids dropped samples and jagged segments.
                for (h in 0 until event.historySize) {
                    appendLiveSample(
                        viewport.screenToWorldX(event.getHistoricalX(idx, h)),
                        viewport.screenToWorldY(event.getHistoricalY(idx, h)),
                        event.getHistoricalPressure(idx, h),
                        event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, idx, h),
                    )
                }
                appendStylusSample(event, idx)
                updatePredictedFromPredictor()
                invalidate()
                true
            }
            MotionEvent.ACTION_UP -> {
                clearPredicted()
                commitLiveStroke()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                liveSampleCount = 0
                clearPredicted()
                invalidate()
                true
            }
            else -> false
        }
    }

    private fun appendStylusSample(event: MotionEvent, idx: Int) {
        appendLiveSample(
            viewport.screenToWorldX(event.getX(idx)),
            viewport.screenToWorldY(event.getY(idx)),
            event.getPressure(idx),
            event.getAxisValue(MotionEvent.AXIS_TILT, idx),
        )
    }

    private fun handleViewportEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panLastX = event.x
                panLastY = event.y
                gestureMode = GestureMode.PAN
                true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    pinchLastDist = pointerDistance(event, 0, 1)
                    gestureMode = GestureMode.PINCH
                }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                when (gestureMode) {
                    GestureMode.PAN -> {
                        val dx = event.x - panLastX
                        val dy = event.y - panLastY
                        panLastX = event.x
                        panLastY = event.y
                        if (dx != 0f || dy != 0f) viewport.applyPan(dx, dy)
                    }
                    GestureMode.PINCH -> {
                        if (event.pointerCount >= 2) {
                            val newDist = pointerDistance(event, 0, 1)
                            if (pinchLastDist > 1f && newDist > 1f) {
                                val factor = newDist / pinchLastDist
                                val focalX = (event.getX(0) + event.getX(1)) * 0.5f
                                val focalY = (event.getY(0) + event.getY(1)) * 0.5f
                                viewport.applyZoom(focalX, focalY, factor)
                            }
                            pinchLastDist = newDist
                        }
                    }
                    GestureMode.NONE -> Unit
                }
                true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Stepping back from pinch to pan: anchor on the remaining pointer.
                val remaining = event.pointerCount - 1
                if (remaining == 1) {
                    val keepIdx = if (event.actionIndex == 0) 1 else 0
                    panLastX = event.getX(keepIdx)
                    panLastY = event.getY(keepIdx)
                    gestureMode = GestureMode.PAN
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gestureMode = GestureMode.NONE
                true
            }
            else -> false
        }
    }

    private fun pointerDistance(event: MotionEvent, a: Int, b: Int): Float =
        hypot(event.getX(a) - event.getX(b), event.getY(a) - event.getY(b))

    private fun onViewportChanged() {
        sceneDirty = true
        invalidate()
    }

    private fun appendLiveSample(x: Float, y: Float, pressure: Float, tilt: Float) {
        ensureLiveCapacity(liveSampleCount + 1)
        val base = liveSampleCount * StrokeCodec.FLOATS_PER_SAMPLE
        liveSamples[base] = x
        liveSamples[base + 1] = y
        liveSamples[base + 2] = pressure
        liveSamples[base + 3] = tilt
        liveSampleCount++
    }

    private fun ensureLiveCapacity(samples: Int) {
        val needed = samples * StrokeCodec.FLOATS_PER_SAMPLE
        if (needed <= liveSamples.size) return
        var newSize = liveSamples.size
        while (newSize < needed) newSize *= 2
        liveSamples = liveSamples.copyOf(newSize)
    }

    private fun updatePredictedFromPredictor() {
        val predicted = motionPredictor?.predict()
        if (predicted == null) {
            clearPredicted()
            return
        }
        try {
            if (liveSampleCount < 1) {
                clearPredicted()
                return
            }
            // Predicted events arrive in screen coords; convert to world so
            // they line up with the live samples under the viewport transform.
            val anchorIdx = (liveSampleCount - 1) * StrokeCodec.FLOATS_PER_SAMPLE
            val total = 1 + predicted.historySize + 1
            val needed = total * StrokeCodec.FLOATS_PER_SAMPLE
            val buf = predictedSamples?.takeIf { it.size >= needed }
                ?: FloatArray(needed).also { predictedSamples = it }

            buf[0] = liveSamples[anchorIdx]
            buf[1] = liveSamples[anchorIdx + 1]
            buf[2] = liveSamples[anchorIdx + 2]
            buf[3] = liveSamples[anchorIdx + 3]

            var dst = StrokeCodec.FLOATS_PER_SAMPLE
            for (h in 0 until predicted.historySize) {
                buf[dst] = viewport.screenToWorldX(predicted.getHistoricalX(h))
                buf[dst + 1] = viewport.screenToWorldY(predicted.getHistoricalY(h))
                buf[dst + 2] = predicted.getHistoricalPressure(h)
                buf[dst + 3] = predicted.getHistoricalAxisValue(MotionEvent.AXIS_TILT, h)
                dst += StrokeCodec.FLOATS_PER_SAMPLE
            }
            buf[dst] = viewport.screenToWorldX(predicted.x)
            buf[dst + 1] = viewport.screenToWorldY(predicted.y)
            buf[dst + 2] = predicted.pressure
            buf[dst + 3] = predicted.getAxisValue(MotionEvent.AXIS_TILT)

            predictedSampleCount = total
        } finally {
            predicted.recycle()
        }
    }

    private fun clearPredicted() {
        predictedSampleCount = 0
    }

    private fun commitLiveStroke() {
        if (liveSampleCount < 1) {
            invalidate()
            return
        }
        val packed = FloatArray(liveSampleCount * StrokeCodec.FLOATS_PER_SAMPLE)
        System.arraycopy(liveSamples, 0, packed, 0, packed.size)
        val item = NoteItem(
            noteId = "",
            zIndex = 0,
            kind = STROKE_KIND,
            tool = activeTool,
            colorArgb = inkColor,
            baseWidthPx = baseWidthPx,
            payload = StrokeCodec.encode(packed),
        )
        committedItems = committedItems + item
        sceneDirty = true
        strokeListener?.invoke(item)
        liveSampleCount = 0
        invalidate()
    }

    /** Redraw the scene bitmap from [committedItems] under the current viewport. */
    private fun rasterizeScene() {
        val canvas = sceneCanvas ?: return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (committedItems.isEmpty()) return
        canvas.save()
        canvas.translate(viewport.offsetX, viewport.offsetY)
        canvas.scale(viewport.scale, viewport.scale)
        for (item in committedItems) {
            if (item.kind != STROKE_KIND) continue
            val samples = StrokeCodec.decode(item.payload)
            val count = samples.size / StrokeCodec.FLOATS_PER_SAMPLE
            if (count < 1) continue
            replayPaint.color = item.colorArgb
            StrokeRenderer.drawStrokePath(
                canvas, replayPaint, samples, count,
                item.baseWidthPx, item.tool, scratchPath,
            )
        }
        canvas.restore()
    }

    companion object {
        const val DEFAULT_STROKE_WIDTH_PX = 4f
        const val DEFAULT_INK_COLOR = Color.BLACK
        const val STROKE_KIND = "stroke"
        const val STROKE_TOOL_PEN = "pen"
        private const val INITIAL_SAMPLE_CAPACITY = 128
        // 0.4 alpha — predicted tail "fades in" when real samples arrive.
        private const val PREDICTED_ALPHA = 102
        private const val HOVER_RADIUS_PX = 4f
        // Translucent grey nib cursor.
        private const val HOVER_COLOR = 0x66000000
    }
}

@Composable
fun DrawingSurfaceView(
    items: List<NoteItem>,
    backgroundStyle: String,
    onStrokeCommitted: (NoteItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnCommit by rememberUpdatedState(onStrokeCommitted)
    var replayed by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            DrawingSurface(ctx).apply {
                strokeListener = { item -> currentOnCommit(item) }
            }
        },
        update = { view ->
            view.strokeListener = { item -> currentOnCommit(item) }
            view.backgroundStyle = backgroundStyle
            if (!replayed && items.isNotEmpty()) {
                view.replayItems(items.toList())
                replayed = true
            }
        },
    )
}
