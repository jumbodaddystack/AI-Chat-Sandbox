package com.aichat.sandbox.ui.screens.vector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.vector.VectorExportFormat

/**
 * Export-format selector for the Export tab (Phase 9): Android VectorDrawable
 * XML, SVG, or a portable project bundle JSON. SVG is converted from the
 * version's canonical Android XML on export.
 */
@Composable
fun VectorExportOptionsPanel(
    selected: VectorExportFormat,
    onSelect: (VectorExportFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        VectorExportFormat.entries.forEach { format ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = format == selected, onClick = { onSelect(format) })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = format == selected, onClick = { onSelect(format) })
                Column {
                    Text(
                        text = formatLabel(format),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = ".${format.extension} · ${format.mimeType}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatLabel(format: VectorExportFormat): String = when (format) {
    VectorExportFormat.ANDROID_VECTOR_XML -> "Android VectorDrawable XML"
    VectorExportFormat.SVG -> "SVG"
    VectorExportFormat.PROJECT_BUNDLE -> "Project bundle JSON"
}
