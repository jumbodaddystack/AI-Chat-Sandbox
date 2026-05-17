package com.aichat.sandbox.ui.components.notes

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary codec for `NoteItem(kind="text")` payloads (sub-phase 1.9).
 *
 * Layout (little-endian):
 * ```
 * [fontSize:    Float  · 4 bytes]
 * [alignment:   Byte   · 1 byte ]
 * [matrix:      9 Floats · 36 bytes]   row-major affine, matches [StrokeTransform.SIZE]
 * [bodyLen:     Int    · 4 bytes]
 * [body:        UTF-8  · bodyLen bytes]
 * ```
 *
 * Why a 9-float matrix instead of just an `(x, y)` origin:
 *
 * Text items participate in selection transforms just like strokes
 * (sub-phase 1.8 / 1.9 DoD). Strokes bake the transform into their packed
 * sample points; text has no such points to bake, so we persist the affine
 * directly. Storing it as 9 floats keeps it byte-compatible with
 * [StrokeTransform] so [com.aichat.sandbox.ui.screens.notes.EditorAction.TransformItems]
 * can mat-mul the new transform into it without any conversion.
 *
 * Pure Kotlin so the round-trip stays JVM-testable; rendering and bounds
 * live in [TextItemRenderer] because [android.text.StaticLayout] needs the
 * Android runtime.
 */
object TextItemCodec {

    /** [com.aichat.sandbox.data.model.NoteItem.kind] value for text items. */
    const val KIND: String = "text"

    const val ALIGN_LEFT: Byte = 0
    const val ALIGN_CENTER: Byte = 1
    const val ALIGN_RIGHT: Byte = 2

    const val DEFAULT_FONT_SIZE_PX: Float = 28f

    /**
     * Wrapping width used by [TextItemRenderer] when laying the body out.
     * World units — at 1.0 zoom this is roughly one mobile screen wide.
     */
    const val DEFAULT_MAX_WIDTH_WORLD: Int = 600

    private const val FONT_SIZE_BYTES = 4
    private const val ALIGNMENT_BYTES = 1
    private const val MATRIX_BYTES = StrokeTransform.SIZE * 4
    private const val BODY_LEN_BYTES = 4
    const val HEADER_BYTES: Int = FONT_SIZE_BYTES + ALIGNMENT_BYTES + MATRIX_BYTES + BODY_LEN_BYTES

    data class TextPayload(
        val fontSize: Float,
        val alignment: Byte,
        /** Row-major 9-float affine; layout matches [StrokeTransform.IDENTITY]. */
        val matrix: FloatArray,
        val body: String,
    ) {
        init {
            require(matrix.size == StrokeTransform.SIZE) {
                "matrix must have ${StrokeTransform.SIZE} floats (got ${matrix.size})"
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TextPayload) return false
            return fontSize == other.fontSize &&
                alignment == other.alignment &&
                matrix.contentEquals(other.matrix) &&
                body == other.body
        }

        override fun hashCode(): Int {
            var result = fontSize.hashCode()
            result = 31 * result + alignment.toInt()
            result = 31 * result + matrix.contentHashCode()
            result = 31 * result + body.hashCode()
            return result
        }
    }

    /** Build the payload for a freshly tapped text item at world point (`worldX`, `worldY`). */
    fun newAt(
        worldX: Float,
        worldY: Float,
        body: String = "",
        fontSize: Float = DEFAULT_FONT_SIZE_PX,
        alignment: Byte = ALIGN_LEFT,
    ): TextPayload = TextPayload(
        fontSize = fontSize,
        alignment = alignment,
        matrix = StrokeTransform.translation(worldX, worldY),
        body = body,
    )

    fun encode(payload: TextPayload): ByteArray {
        val bodyBytes = payload.body.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer
            .allocate(HEADER_BYTES + bodyBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putFloat(payload.fontSize)
        buffer.put(payload.alignment)
        for (i in 0 until StrokeTransform.SIZE) buffer.putFloat(payload.matrix[i])
        buffer.putInt(bodyBytes.size)
        buffer.put(bodyBytes)
        return buffer.array()
    }

    fun decode(payload: ByteArray): TextPayload {
        require(payload.size >= HEADER_BYTES) {
            "text payload too short (${payload.size} < $HEADER_BYTES)"
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val fontSize = buffer.float
        val alignment = buffer.get()
        val matrix = FloatArray(StrokeTransform.SIZE)
        for (i in 0 until StrokeTransform.SIZE) matrix[i] = buffer.float
        val bodyLen = buffer.int
        require(bodyLen >= 0 && HEADER_BYTES + bodyLen == payload.size) {
            "text payload length mismatch: header says $bodyLen body bytes, " +
                "have ${payload.size - HEADER_BYTES}"
        }
        val bodyBytes = ByteArray(bodyLen)
        buffer.get(bodyBytes)
        return TextPayload(
            fontSize = fontSize,
            alignment = alignment,
            matrix = matrix,
            body = String(bodyBytes, Charsets.UTF_8),
        )
    }

    /** Returns [payload] with [body] swapped — convenience for the editor's commit path. */
    fun withBody(payload: TextPayload, body: String): TextPayload =
        payload.copy(body = body)

    /** Returns [payload] with [matrix] swapped — used by [TransformItems] baking. */
    fun withMatrix(payload: TextPayload, matrix: FloatArray): TextPayload {
        require(matrix.size == StrokeTransform.SIZE)
        return payload.copy(matrix = matrix.copyOf())
    }
}
