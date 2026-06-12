package com.aichat.sandbox.data.notes

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 15.4 — one-tap icon set export for freehand icon notes.
 *
 * Bundles everything needed to ship the icon into a single `.zip`:
 *  - VectorDrawable XML at every [NoteVectorDrawableExporter.IconSize]
 *    (`ic_{name}_24.xml` / `_48` / `_108`), ready for `res/drawable/`,
 *  - one SVG (`{name}.svg`) for design tools,
 *  - one 512 px PNG (`{name}_512.png`) raster preview.
 *
 * The freehand-note sibling of the Vector Tune-Up lane's
 * [com.aichat.sandbox.data.vector.IconSetExporter]: that one derives sizes
 * losslessly from a parsed VectorDocument; this one runs the note exporters
 * over the icon's artboard frame.
 */
@Singleton
class NoteIconSetExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Zip URI plus the count of items the vector formats had to skip. */
    data class Result(val uri: Uri, val skippedCount: Int)

    suspend fun exportIconSet(
        note: Note,
        items: List<NoteItem>,
        /** Artboard / frame bounds; null falls back to content bounds. */
        frameBounds: FloatArray? = null,
        /** Phase 15.1 — variable-width strokes as filled outlines. */
        preservePressure: Boolean = false,
    ): Result = withContext(Dispatchers.IO) {
        val name = NoteExporter.sanitizeBaseName(note.title)
        val entries = LinkedHashMap<String, ByteArray>()
        var skipped = 0
        for (size in NoteVectorDrawableExporter.IconSize.entries) {
            val rendered = NoteVectorDrawableExporter.render(
                items, size.dp, frameBounds, preservePressure,
            )
            skipped = maxOf(skipped, rendered.skippedCount)
            entries["ic_${name}_${size.dp}.xml"] = rendered.xml.toByteArray(Charsets.UTF_8)
        }
        entries["$name.svg"] = NoteSvgExporter
            .renderSvg(note, items, context.filesDir, frameBounds, preservePressure)
            .toByteArray(Charsets.UTF_8)
        entries["${name}_512.png"] = renderPng(items, frameBounds)

        val dir = exportsDir().apply { if (!exists()) mkdirs() }
        val outName = "$name-iconset-${System.currentTimeMillis()}.zip"
        val finalFile = File(dir, outName)
        val tmpFile = File(dir, "$outName.tmp")
        try {
            ZipOutputStream(FileOutputStream(tmpFile)).use { zip ->
                for ((entryName, bytes) in entries) {
                    zip.putNextEntry(ZipEntry(entryName))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            if (finalFile.exists()) finalFile.delete()
            check(tmpFile.renameTo(finalFile)) {
                "NoteIconSetExporter: rename ${tmpFile.name} → ${finalFile.name} failed"
            }
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
        NoteExporter.pruneOld(dir, keep = NoteExporter.MAX_KEEP_FILES, extensions = MANAGED_EXTENSIONS)
        Result(
            uri = FileProvider.getUriForFile(
                context, NoteExporter.fileProviderAuthority(context), finalFile,
            ),
            skippedCount = skipped,
        )
    }

    private fun renderPng(items: List<NoteItem>, frameBounds: FloatArray?): ByteArray {
        val bitmap = if (frameBounds != null) {
            NoteRasterizer.renderForFrame(
                items = items,
                frameBounds = frameBounds,
                maxEdgePx = PNG_EDGE_PX,
                filesDir = context.filesDir,
            )
        } else {
            val bounds = NoteRasterizer.computeBounds(items)
                ?: NoteExporter.defaultPaperBounds()
            NoteRasterizer.render(
                items = items,
                bounds = bounds,
                maxEdgePx = PNG_EDGE_PX,
                filesDir = context.filesDir,
            )
        }
        return try {
            ByteArrayOutputStream(64 * 1024).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun exportsDir(): File = File(context.cacheDir, NoteExporter.EXPORTS_SUBDIR)

    companion object {
        val MANAGED_EXTENSIONS: Set<String> = setOf("zip")
        private const val PNG_EDGE_PX = 512
    }
}
