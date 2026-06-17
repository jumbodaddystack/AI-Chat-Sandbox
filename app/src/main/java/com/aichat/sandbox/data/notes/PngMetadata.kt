package com.aichat.sandbox.data.notes

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Phase 9 — minimal PNG `tEXt` metadata writer for export accessibility.
 *
 * PNG has no first-class alt-text field, so screen-reader / cataloguing tools
 * read a `tEXt` chunk with a well-known keyword. [withDescription] inserts a
 * `tEXt` "Description" chunk immediately before the terminal `IEND` chunk,
 * leaving the image data untouched. Pure JVM (only [CRC32] from `java.util.zip`)
 * so the wire format is unit-testable without an Android device.
 *
 * Keyword + text are Latin-1 per the PNG spec's `tEXt` definition; characters
 * outside Latin-1 are dropped (a caption falling back to ASCII is acceptable and
 * keeps the chunk spec-compliant — use `iTXt` if full UTF-8 is ever needed).
 */
object PngMetadata {

    /** PNG `tEXt` keyword used for the export description. */
    const val DESCRIPTION_KEYWORD: String = "Description"

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /**
     * Return a copy of [png] with a `tEXt` "Description" chunk carrying
     * [description] inserted before `IEND`. Returns [png] unchanged when the
     * description is blank or the input isn't a recognizable PNG (best-effort —
     * a metadata helper must never corrupt the exported image).
     */
    fun withDescription(png: ByteArray, description: String): ByteArray {
        val text = description.trim()
        if (text.isEmpty()) return png
        if (!hasSignature(png)) return png
        val iendStart = findIendStart(png) ?: return png
        val chunk = textChunk(DESCRIPTION_KEYWORD, text)
        val out = ByteArrayOutputStream(png.size + chunk.size)
        out.write(png, 0, iendStart)
        out.write(chunk)
        out.write(png, iendStart, png.size - iendStart)
        return out.toByteArray()
    }

    private fun hasSignature(png: ByteArray): Boolean {
        if (png.size < SIGNATURE.size + 12) return false
        for (i in SIGNATURE.indices) if (png[i] != SIGNATURE[i]) return false
        return true
    }

    /**
     * Walk the chunk list and return the byte offset of the `IEND` chunk's
     * length field, or null if the stream is malformed. Scanning (rather than
     * assuming `IEND` is the final 12 bytes) tolerates trailing bytes some
     * encoders append.
     */
    private fun findIendStart(png: ByteArray): Int? {
        var pos = SIGNATURE.size
        while (pos + 8 <= png.size) {
            val len = readUInt(png, pos)
            if (len < 0) return null
            val type = String(png, pos + 4, 4, Charsets.US_ASCII)
            if (type == "IEND") return pos
            // Advance past length(4) + type(4) + data(len) + crc(4).
            val next = pos + 12 + len
            if (next <= pos || next > png.size) return null
            pos = next
        }
        return null
    }

    private fun readUInt(b: ByteArray, off: Int): Int {
        val v = ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
        // PNG lengths fit in 31 bits; a negative value means a corrupt stream.
        return if (v < 0) -1 else v
    }

    /** Build a complete `tEXt` chunk: length + "tEXt" + keyword\0text + CRC. */
    private fun textChunk(keyword: String, text: String): ByteArray {
        val kw = keyword.toByteArray(Charsets.ISO_8859_1)
        val tx = text.toByteArray(Charsets.ISO_8859_1)
        val data = ByteArrayOutputStream(kw.size + 1 + tx.size)
        data.write(kw)
        data.write(0)
        data.write(tx)
        val dataBytes = data.toByteArray()
        val typeBytes = "tEXt".toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(dataBytes)
        }.value

        val out = ByteArrayOutputStream(12 + dataBytes.size)
        writeUInt(out, dataBytes.size)
        out.write(typeBytes)
        out.write(dataBytes)
        writeUInt(out, crc)
        return out.toByteArray()
    }

    private fun writeUInt(out: ByteArrayOutputStream, value: Int) =
        writeUInt(out, value.toLong() and 0xFFFFFFFFL)

    private fun writeUInt(out: ByteArrayOutputStream, value: Long) {
        out.write(((value ushr 24) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }
}
