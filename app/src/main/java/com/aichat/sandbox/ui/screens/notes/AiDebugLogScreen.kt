package com.aichat.sandbox.ui.screens.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aichat.sandbox.data.notes.AiDebugTrace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Developer screen that surfaces the [com.aichat.sandbox.data.notes.AiDebugLog]
 * ring buffer: every recent note/canvas AI exchange with the payload sent, the
 * raw model reply, the parse/apply outcome, and per-op rejection reasons. This
 * is the "what is the model actually responding with?" inspector — tap a row to
 * expand the full request + raw reply, and copy any exchange to the clipboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiDebugLogScreen(
    onClose: () -> Unit,
    viewModel: AiDebugLogViewModel = hiltViewModel(),
) {
    val traces by viewModel.traces.collectAsState()
    val enabled by viewModel.enabled.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI debug log") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.clear() }, enabled = traces.isNotEmpty()) {
                        Text("Clear")
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            // Capture gate, mirrored from the persisted setting.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Capture AI exchanges", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Records the prompt, raw model reply and parse outcome for each note/canvas AI request.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { viewModel.setEnabled(it) })
            }
            HorizontalDivider()

            if (traces.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (enabled) "No AI exchanges captured yet.\nRun an AI edit and it will show up here."
                        else "Turn on capture, then run an AI edit to inspect what the model returns.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(traces, key = { it.id }) { trace ->
                        TraceCard(trace)
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceCard(trace: AiDebugTrace) {
    var expanded by rememberSaveable(trace.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val failed = trace.outcome.contains("fail", ignoreCase = true) ||
        trace.rejections.isNotEmpty() ||
        trace.outcome.contains("error", ignoreCase = true)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${trace.mode} · ${timeFmt.format(Date(trace.epochMillis))}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(trace.toShareText()))
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy exchange")
                }
            }
            Text(
                text = trace.modelId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = trace.outcome,
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
            if (trace.rejections.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                trace.rejections.take(if (expanded) Int.MAX_VALUE else 2).forEach {
                    Text(
                        text = "• $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text(if (expanded) "Hide details" else "Show request & raw reply")
            }
            if (expanded) {
                MonoBlock(label = "Request sent", body = trace.request)
                Spacer(Modifier.height(8.dp))
                MonoBlock(label = "Raw model reply", body = trace.rawReply)
            }
        }
    }
}

/** A scrollable, monospaced, copy-friendly block for a request or reply body. */
@Composable
private fun MonoBlock(label: String, body: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = body.ifBlank { "(empty)" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        )
    }
}
