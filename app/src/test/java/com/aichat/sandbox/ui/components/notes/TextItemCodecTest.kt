package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TextItemCodecTest {

    @Test
    fun emptyBodyRoundTrips() {
        val payload = TextItemCodec.newAt(worldX = 12f, worldY = -7.5f, body = "")
        val decoded = TextItemCodec.decode(TextItemCodec.encode(payload))
        assertEquals(payload, decoded)
        assertEquals("", decoded.body)
        // Matrix translation should be exactly the tap point.
        assertEquals(12f, decoded.matrix[2], 0f)
        assertEquals(-7.5f, decoded.matrix[5], 0f)
    }

    @Test
    fun asciiBodyRoundTrips() {
        val payload = TextItemCodec.TextPayload(
            fontSize = 32f,
            alignment = TextItemCodec.ALIGN_CENTER,
            matrix = StrokeTransform.translation(100f, 200f),
            body = "hello, canvas!",
        )
        val decoded = TextItemCodec.decode(TextItemCodec.encode(payload))
        assertEquals(payload, decoded)
        assertEquals(TextItemCodec.ALIGN_CENTER, decoded.alignment)
    }

    @Test
    fun utf8BodyPreservesMultiByteCharacters() {
        // Mix of BMP and astral plane characters — emoji surrogate pairs are
        // the most failure-prone byte sequences in a UTF-8 round trip.
        val body = "café — 日本語 — 🚀✨"
        val payload = TextItemCodec.newAt(0f, 0f, body)
        val decoded = TextItemCodec.decode(TextItemCodec.encode(payload))
        assertEquals(body, decoded.body)
    }

    @Test
    fun matrixIsCopiedNotShared() {
        val source = StrokeTransform.translation(50f, 50f)
        val payload = TextItemCodec.TextPayload(
            fontSize = 24f,
            alignment = TextItemCodec.ALIGN_LEFT,
            matrix = source,
            body = "x",
        )
        val decoded = TextItemCodec.decode(TextItemCodec.encode(payload))
        assertArrayEquals(source, decoded.matrix, 0f)
        // Mutating the round-tripped matrix must not affect the encoded payload.
        decoded.matrix[2] = 999f
        val decodedAgain = TextItemCodec.decode(TextItemCodec.encode(payload))
        assertEquals(50f, decodedAgain.matrix[2], 0f)
    }

    @Test
    fun withBodyAndWithMatrixOnlyChangeTheirSlots() {
        val original = TextItemCodec.newAt(10f, 20f, "initial", fontSize = 30f)
        val rebodied = TextItemCodec.withBody(original, "rewritten")
        assertEquals("rewritten", rebodied.body)
        assertEquals(original.fontSize, rebodied.fontSize, 0f)
        assertArrayEquals(original.matrix, rebodied.matrix, 0f)

        val moved = TextItemCodec.withMatrix(original, StrokeTransform.translation(99f, 99f))
        assertEquals(99f, moved.matrix[2], 0f)
        assertEquals(99f, moved.matrix[5], 0f)
        assertEquals(original.body, moved.body)
        assertNotEquals(original, moved)
    }

    @Test
    fun headerSizeIsStable() {
        // 4 (fontSize) + 1 (alignment) + 36 (9-float matrix) + 4 (bodyLen) = 45.
        assertEquals(45, TextItemCodec.HEADER_BYTES)
        val payload = TextItemCodec.newAt(0f, 0f, "")
        assertEquals(TextItemCodec.HEADER_BYTES, TextItemCodec.encode(payload).size)
    }

    @Test
    fun decodeRejectsShortPayload() {
        assertThrows(IllegalArgumentException::class.java) {
            TextItemCodec.decode(ByteArray(TextItemCodec.HEADER_BYTES - 1))
        }
    }

    @Test
    fun decodeRejectsBodyLengthMismatch() {
        val good = TextItemCodec.encode(TextItemCodec.newAt(0f, 0f, "abc"))
        // Lop off a body byte without updating the embedded bodyLen field.
        val truncated = good.copyOf(good.size - 1)
        assertThrows(IllegalArgumentException::class.java) {
            TextItemCodec.decode(truncated)
        }
    }

    @Test
    fun encodedSizeMatchesBodyByteLength() {
        val ascii = TextItemCodec.encode(TextItemCodec.newAt(0f, 0f, "hello"))
        assertEquals(TextItemCodec.HEADER_BYTES + 5, ascii.size)

        // "日本語" is 3 chars but 9 bytes in UTF-8.
        val multi = TextItemCodec.encode(TextItemCodec.newAt(0f, 0f, "日本語"))
        assertEquals(TextItemCodec.HEADER_BYTES + 9, multi.size)
    }

    @Test
    fun textPayloadEqualityIsDeep() {
        val a = TextItemCodec.newAt(0f, 0f, "abc")
        val b = TextItemCodec.newAt(0f, 0f, "abc")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        val c = TextItemCodec.newAt(0f, 0f, "abc", fontSize = 40f)
        assertNotEquals(a, c)
    }

    @Test
    fun payloadRejectsWrongSizedMatrix() {
        assertThrows(IllegalArgumentException::class.java) {
            TextItemCodec.TextPayload(
                fontSize = 24f,
                alignment = TextItemCodec.ALIGN_LEFT,
                matrix = FloatArray(6),
                body = "x",
            )
        }
    }

    @Test
    fun alignmentByteSurvivesAllVariants() {
        for (a in listOf(
            TextItemCodec.ALIGN_LEFT,
            TextItemCodec.ALIGN_CENTER,
            TextItemCodec.ALIGN_RIGHT,
        )) {
            val payload = TextItemCodec.TextPayload(
                fontSize = 16f,
                alignment = a,
                matrix = StrokeTransform.IDENTITY,
                body = "",
            )
            val decoded = TextItemCodec.decode(TextItemCodec.encode(payload))
            assertEquals(a, decoded.alignment)
        }
        // Sanity: alignment constants are distinct.
        assertTrue(TextItemCodec.ALIGN_LEFT != TextItemCodec.ALIGN_CENTER)
        assertTrue(TextItemCodec.ALIGN_CENTER != TextItemCodec.ALIGN_RIGHT)
    }
}
