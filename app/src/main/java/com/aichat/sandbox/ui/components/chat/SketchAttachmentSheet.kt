package com.aichat.sandbox.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.notes.NoteRasterizer
import com.aichat.sandbox.ui.components.notes.BackgroundLayer
import com.aichat.sandbox.ui.components.notes.DrawingSurfaceView
import com.aichat.sandbox.ui.components.notes.StrokeTransform
import com.aichat.sandbox.ui.components.notes.Tool

/**
 * Bottom-sheet sketch surface for the chat composer (sub-phase 3.4).
 *
 * Stripped-down reuse of [DrawingSurfaceView] running in sketchMode: finger
 * and stylus both draw, pan/zoom is off, and only pen + stroke-eraser are
 * exposed. Confirm rasterizes the strokes to a PNG via [NoteRasterizer]
 * and hands the bytes to [onConfirm]; the caller routes them through the
 * existing image-attachment pipeline so the sketch lands on the outgoing
 * message identically to a photo-picked image.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SketchAttachmentSheet(
    state: SketchAttachmentState,
    onConfirm: (pngBytes: ByteArray) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.isOpen) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(SHEET_HEIGHT_FRACTION)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SketchToolbar(state = state)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.White),
            ) {
                DrawingSurfaceView(
                    items = state.items,
                    backgroundStyle = BackgroundLayer.STYLE_PLAIN,
                    paletteState = state.palette,
                    selectedIds = emptySet(),
                    selectionMatrix = StrokeTransform.IDENTITY,
                    editingTextId = null,
                    onStrokeCommitted = state::addItem,
                    onItemsErased = state::removeItems,
                    onLassoCompleted = { _ -> },
                    onSelectionShouldClear = {},
                    onTextTap = { _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    sketchMode = true,
                )
            }
            SketchActionRow(
                canConfirm = state.canConfirm,
                onCancel = onDismiss,
                onConfirm = {
                    val snapshot = state.items.toList()
                    val bounds = NoteRasterizer.computeBounds(snapshot) ?: return@SketchActionRow
                    val bitmap = NoteRasterizer.render(
                        items = snapshot,
                        bounds = bounds,
                        maxEdgePx = SKETCH_MAX_EDGE_PX,
                    )
                    val png = NoteRasterizer.toPng(bitmap)
                    bitmap.recycle()
                    onConfirm(png)
                },
            )
        }
    }
}

@Composable
private fun SketchToolbar(state: SketchAttachmentState) {
    val selected = state.palette.selected
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = selected == Tool.PEN,
            onClick = { state.palette.select(Tool.PEN) },
            label = { Text("Pen") },
            leadingIcon = {
                Icon(
                    Icons.Filled.Create,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
        FilterChip(
            selected = selected == Tool.ERASER_STROKE,
            onClick = { state.palette.select(Tool.ERASER_STROKE) },
            label = { Text("Eraser") },
            leadingIcon = {
                Icon(
                    Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = state::undo, enabled = state.canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
        }
        IconButton(onClick = state::clearCanvas, enabled = state.canConfirm) {
            Icon(Icons.Filled.Delete, contentDescription = "Clear")
        }
    }
}

@Composable
private fun SketchActionRow(
    canConfirm: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Cancel")
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onConfirm,
            enabled = canConfirm,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add to message")
        }
    }
}

private const val SHEET_HEIGHT_FRACTION: Float = 0.6f
private const val SKETCH_MAX_EDGE_PX: Int = 1024
