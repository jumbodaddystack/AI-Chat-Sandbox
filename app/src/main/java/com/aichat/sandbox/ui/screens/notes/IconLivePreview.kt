package com.aichat.sandbox.ui.screens.notes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.notes.NoteRasterizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Phase 15.3 — live small-size icon preview. Renders the artboard at the
 * real display sizes (24 / 48 dp) while editing, so the designer sees what
 * the icon reads like at usage size — the standard icon-studio affordance
 * that catches muddy detail early instead of at export time.
 *
 * Re-renders key off a cheap identity signature of the item list: every
 * committed edit (add / erase / transform bake / restyle) replaces the
 * affected [NoteItem] instances, so instance identity changing is exactly
 * the "content changed" signal. A short debounce coalesces rapid commits.
 */
@Composable
fun IconLivePreview(
    items: List<NoteItem>,
    frameBounds: FloatArray?,
    modifier: Modifier = Modifier,
) {
    if (frameBounds == null) return
    // Reading every element subscribes this composable to list writes
    // (items is the editor's SnapshotStateList).
    var signature = frameBounds.contentHashCode()
    for (item in items) signature = signature * 31 + System.identityHashCode(item)
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(signature) {
        delay(DEBOUNCE_MS)
        val snapshot = items.toList()
        preview = withContext(Dispatchers.Default) {
            NoteRasterizer.renderForFrame(
                items = snapshot,
                frameBounds = frameBounds,
                maxEdgePx = RENDER_EDGE_PX,
            ).asImageBitmap()
        }
    }
    val bitmap = preview ?: return
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            PreviewAt(bitmap, 24.dp)
            PreviewAt(bitmap, 48.dp)
        }
    }
}

@Composable
private fun PreviewAt(bitmap: ImageBitmap, size: Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .background(Color.White, RoundedCornerShape(4.dp)),
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "Icon preview at ${size.value.toInt()} dp",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = "${size.value.toInt()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Coalesce rapid ink commits before paying for a re-render. */
private const val DEBOUNCE_MS = 180L

/** Render resolution — comfortably above 48 dp at any sane density. */
private const val RENDER_EDGE_PX = 256
