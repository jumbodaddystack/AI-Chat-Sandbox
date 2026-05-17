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

/**
 * Front-buffered ink surface (sub-phase 1.4).
 *
 * Implementation notes:
 *
 *  - We stay on a plain [View] base class rather than the SurfaceView +
 *    `CanvasFrontBufferedRenderer` path mentioned as the primary option in
 *    `STYLUS_NOTES_PHASE_1.md` sub-phase 1.4. The front-buffer library
 *    (`androidx.graphics:graphics-core`) is still RC and its integration
 *    with `AndroidView` is fiddly. Latency reduction here comes from:
 *      • Variable-width per-segment rendering of pressure + tilt
 *      • Quadratic-Bezier smoothing between sample midpoints
 *      • One-frame look-ahead via [MotionEventPredictor]
 *      • `historySize` iteration so we never drop S-Pen samples
 *    TODO(post-1.4): revisit SurfaceView + CanvasFrontBufferedRenderer
 *    once graphics-core ships stable and we can measure the win on device.
 *
 *  - Stylus-only ink (palm rejection); hover events produce a small nib cursor.
 *  - Pressure and tilt are stored on every sample so the codec format
 *    established in 1.3 is preserved — no schema bump.
 */
class DrawingSurface(context: Context) : View(context) {

    private var sceneBitmap: Bitmap? = null
    private var sceneCanvas: Canvas? = null

    /** Active live-stroke samples packed as `[x, y, pressure, tilt]` per sample. */
    private var liveSamples: FloatArray =
        FloatArray(INITIAL_SAMPLE_CAPACITY * StrokeCodec.FLOATS_PER_SAMPLE)
    private var liveSampleCount: Int = 0

    /** Look-ahead samples; re-derived every move event, drawn with reduced alpha. */
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

    /** Invoked once per committed stroke. Caller is responsible for noteId / zIndex. */
    var strokeListener: ((NoteItem) -> Unit)? = null

    private var pendingReplay: List<NoteItem>? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        motionPredictor = MotionEventPredictor.newInstance(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        motionPredictor = null
    }

    /** Render the given items onto the scene bitmap, replacing any existing scene contents. */
    fun replayItems(items: List<NoteItem>) {
        val canvas = sceneCanvas
        if (canvas == null) {
            pendingReplay = items
            return
        }
        drawItemsTo(canvas, items)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val previous = sceneBitmap
        val next = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val nextCanvas = Canvas(next)
        if (previous != null) {
            nextCanvas.drawBitmap(previous, 0f, 0f, null)
            previous.recycle()
        }
        sceneBitmap = next
        sceneCanvas = nextCanvas
        pendingReplay?.let {
            drawItemsTo(nextCanvas, it)
            pendingReplay = null
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        sceneBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
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
        if (hoverVisible) {
            canvas.drawCircle(hoverX, hoverY, HOVER_RADIUS_PX, hoverPaint)
        }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        // Hover events drive a small nib cursor; stylus only.
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
        // Palm rejection: only stylus pointers contribute to ink.
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                liveSampleCount = 0
                clearPredicted()
                hoverVisible = false
                appendLiveSample(event.x, event.y, event.pressure, tiltOf(event))
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
                        event.getHistoricalX(h),
                        event.getHistoricalY(h),
                        event.getHistoricalPressure(h),
                        historicalTilt(event, h),
                    )
                }
                appendLiveSample(event.x, event.y, event.pressure, tiltOf(event))
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
            // Anchor predicted samples on the last real sample so the visible
            // tail extends seamlessly from the actual nib position.
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
                buf[dst] = predicted.getHistoricalX(h)
                buf[dst + 1] = predicted.getHistoricalY(h)
                buf[dst + 2] = predicted.getHistoricalPressure(h)
                buf[dst + 3] = historicalTilt(predicted, h)
                dst += StrokeCodec.FLOATS_PER_SAMPLE
            }
            buf[dst] = predicted.x
            buf[dst + 1] = predicted.y
            buf[dst + 2] = predicted.pressure
            buf[dst + 3] = tiltOf(predicted)

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
        // Blit the live stroke onto the persistent scene layer.
        sceneCanvas?.let { c ->
            replayPaint.color = inkColor
            StrokeRenderer.drawStrokePath(
                c, replayPaint, liveSamples, liveSampleCount,
                baseWidthPx, activeTool, scratchPath,
            )
        }
        strokeListener?.invoke(item)
        liveSampleCount = 0
        invalidate()
    }

    private fun drawItemsTo(canvas: Canvas, items: List<NoteItem>) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        for (item in items) {
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
    }

    private fun tiltOf(event: MotionEvent): Float =
        event.getAxisValue(MotionEvent.AXIS_TILT)

    private fun historicalTilt(event: MotionEvent, h: Int): Float =
        event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, h)

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
            if (!replayed && items.isNotEmpty()) {
                view.replayItems(items.toList())
                replayed = true
            }
        },
    )
}
