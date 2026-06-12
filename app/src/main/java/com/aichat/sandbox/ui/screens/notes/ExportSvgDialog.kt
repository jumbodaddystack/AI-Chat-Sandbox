package com.aichat.sandbox.ui.screens.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Phase 15.1 — modal dialog presented before an SVG export.
 *
 * The single option mirrors [ExportVectorXmlDialog]'s pressure toggle: when
 * enabled, variable-width strokes export as filled outlines that track the
 * renderer's width curve instead of flattening to a mean stroke width.
 */
@Composable
fun ExportSvgDialog(
    title: String = "Share as SVG",
    onCancel: () -> Unit,
    onExport: (preservePressure: Boolean) -> Unit,
) {
    var preservePressure by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = "Options",
                    style = MaterialTheme.typography.labelLarge,
                )
                PreservePressureRow(
                    checked = preservePressure,
                    onCheckedChange = { preservePressure = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onExport(preservePressure) }) {
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
