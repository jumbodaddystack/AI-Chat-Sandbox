package com.aichat.sandbox.data.notes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Phase 9 — JVM coverage for the PNG `tEXt` description writer used to embed
 * export alt text. Verifies the chunk is well-formed (length + CRC), inserted
 * before `IEND`, and that the helper is a safe no-op for blank text / non-PNG.
 */
class PngMetadataTest {

    @Test
    fun insertsWellFormedTextChunkBeforeIend() {
        val png = fakePng()
        val out = PngMetadata.withDescription(png, "A blue circle")

        // Longer than the input (a chunk was added).
        assertTrue(out.size > png.size)
        // Signature preserved.
        assertArrayEquals(png.copyOfRange(0, 8), out.copyOfRange(0, 8))
        // IEND is still the terminal chunk.
        val iendType = String(out, out.size - 8, 4, Charsets.US_ASCII)
        assertEquals("IEND", iendType)

        // Locate the tEXt chunk and validate its declared length + CRC.
        val textIdx = indexOf(out, "tEXt".toByteArray(Charsets.US_ASCII))
        assertTrue("tEXt chunk should be present", textIdx >= 4)
        val lenOff = textIdx - 4
        val len = readUInt(out, lenOff)
        val data = out.copyOfRange(textIdx + 4, textIdx + 4 + len)
        val declaredCrc = readUInt(out, textIdx + 4 + len).toLong() and 0xFFFFFFFFL
        val computed = CRC32().apply {
            update("tEXt".toByteArray(Charsets.US_ASCII))
            update(data)
        }.value
        assertEquals(computed, declaredCrc)

        // Data is "Description\0A blue circle".
        val expected = "Description".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0) + "A blue circle".toByteArray(Charsets.ISO_8859_1)
        assertArrayEquals(expected, data)
    }

    @Test
    fun blankDescriptionIsNoOp() {
        val png = fakePng()
        assertArrayEquals(png, PngMetadata.withDescription(png, "   "))
    }

    @Test
    fun nonPngIsReturnedUnchanged() {
        val notPng = byteArrayOf(1, 2, 3, 4, 5)
        assertArrayEquals(notPng, PngMetadata.withDescription(notPng, "hello"))
    }

    // ---- helpers ----

    /** A minimal, structurally valid PNG: signature + IHDR + IEND. */
    private fun fakePng(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        writeChunk(out, "IHDR", ByteArray(13))
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        writeUInt(out, data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }.value
        writeUInt(out, (crc and 0xFFFFFFFFL).toInt())
    }

    private fun writeUInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun readUInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
