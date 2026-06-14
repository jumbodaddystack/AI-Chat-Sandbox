package com.aichat.sandbox.ui.screens.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.notes.PdfLayout

/**
 * Modal dialog presented before a PDF export (sub-phase 4.2).
 *
 * Lets the user pick a layout mode and page size; the live page-count caption
 * recomputes whenever either changes so the user has feedback on what their
 * choices will produce *before* the rasteriser runs. Defaults: tile layout
 * (faithful to the canvas) and a locale-derived page size.
 *
 * [boundsForPreview] is the note's current geometry bounds — used only by the
 * preview to estimate tile counts and never by the export itself, which
 * recomputes bounds inside [com.aichat.sandbox.data.notes.NoteExporter] to
 * pick up any last-second edits the user makes between opening the dialog
 * and tapping Export.
 */
@Composable
fun ExportPdfDialog(
    boundsForPreview: FloatArray,
    initialMode: PdfLayout.Mode = PdfLayout.Mode.TILE,
    initialPageSize: PdfLayout.PageSize = PdfLayout.defaultPageSize(),
    onCancel: () -> Unit,
    onExport: (mode: PdfLayout.Mode, pageSize: PdfLayout.PageSize) -> Unit,
) {
    var mode by remember { mutableStateOf(initialMode) }
    var pageSize by remember { mutableStateOf(initialPageSize) }
    val pageCount = remember(mode, pageSize, boundsForPreview) {
        PdfLayout.pageCount(boundsForPreview, pageSize, mode)
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Export as PDF") },
        text = {
            Column {
                Text(
                    text = "Layout",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                LayoutModeRow(
                    label = "Fit to one page",
                    description = "Scale the whole note to fit a single page.",
                    selected = mode == PdfLayout.Mode.FIT_ONE_PAGE,
                    onSelect = { mode = PdfLayout.Mode.FIT_ONE_PAGE },
                )
                LayoutModeRow(
                    label = "Tile across pages",
                    description = "Keep size; split across a grid of pages.",
                    selected = mode == PdfLayout.Mode.TILE,
                    onSelect = { mode = PdfLayout.Mode.TILE },
                )
                Text(
                    text = "Paper size",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                PageSizeDropdown(
                    selected = pageSize,
                    onSelect = { pageSize = it },
                )
                Text(
                    text = if (pageCount == 1) "This will produce 1 page."
                    else "This will produce $pageCount pages.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onExport(mode, pageSize) }) {
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
internal fun LayoutModeRow(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun PageSizeDropdown(
    selected: PdfLayout.PageSize,
    onSelect: (PdfLayout.PageSize) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = labelFor(selected),
                modifier = Modifier.weight(1f, fill = true),
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = "Choose page size",
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PdfLayout.PageSize.entries.forEach { size ->
                DropdownMenuItem(
                    text = { Text(labelFor(size)) },
                    onClick = {
                        onSelect(size)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun labelFor(size: PdfLayout.PageSize): String = when (size) {
    PdfLayout.PageSize.A4 -> "A4 (210 × 297 mm)"
    PdfLayout.PageSize.LETTER -> "Letter (8.5 × 11 in)"
}
