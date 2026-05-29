package com.aichat.sandbox.ui.screens.notes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.notes.NoteVectorDrawableExporter.IconSize

/**
 * Modal dialog presented before an Android VectorDrawable (`.xml`) export.
 *
 * Lets the user pick the icon size, which becomes both the drawable's display
 * size (`android:width/height`) and its coordinate viewport
 * (`android:viewportWidth/Height`). The drawing is scaled to fill that
 * viewport so the result imports into `res/drawable/` at the chosen size.
 *
 * Shows a live [preview] of the resulting icon (strokes + shapes only, no grid)
 * and warns about dropped text/image items only when [skippedCount] > 0, so the
 * user isn't told about a loss that won't happen.
 */
@Composable
fun ExportVectorXmlDialog(
    skippedCount: Int,
    preview: ImageBitmap?,
    initialSize: IconSize = IconSize.MEDIUM_48,
    onCancel: () -> Unit,
    onExport: (sizeDp: Int) -> Unit,
) {
    var size by remember { mutableStateOf(initialSize) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Export as Android XML") },
        text = {
            Column {
                IconPreview(preview)
                Text(
                    text = "Icon size",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                IconSize.entries.forEach { option ->
                    SizeRow(
                        label = option.label,
                        selected = size == option,
                        onSelect = { size = option },
                    )
                }
                if (skippedCount > 0) {
                    Text(
                        text = "$skippedCount text/image item(s) will be skipped — Android " +
                            "vector drawables only support paths and shapes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onExport(size.dp) }) {
                Text("Export")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun IconPreview(preview: ImageBitmap?) {
    Box(
        modifier = Modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = "Icon preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                )
            } else {
                Text(
                    text = "Nothing to preview",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
        }
    }
}

@Composable
private fun SizeRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
