package com.aichat.sandbox.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aichat.sandbox.data.model.ApiProvider
import com.aichat.sandbox.data.model.ChatSettings
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.ui.components.MarkdownText
import com.aichat.sandbox.ui.components.ModelSelector
import com.aichat.sandbox.ui.components.SettingsSlider
import com.aichat.sandbox.data.model.MessageRole
import com.aichat.sandbox.ui.theme.AssistantBubble
import com.aichat.sandbox.ui.theme.UserBubble
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatId: String,
    onNavigateBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val chat = uiState.chat
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(uiState.messages.size, uiState.streamingContent) {
        if (uiState.messages.isNotEmpty() || uiState.streamingContent.isNotEmpty()) {
            var targetIndex = uiState.messages.size
            // Account for the regenerate button item
            val lastIsAssistant = uiState.messages.lastOrNull()?.role == MessageRole.ASSISTANT.value
            if (!uiState.isLoading && lastIsAssistant) targetIndex++
            // Account for retry indicator
            if (uiState.retryAttempt > 0) targetIndex++
            // Account for streaming content
            if (uiState.streamingContent.isNotEmpty()) targetIndex++
            if (targetIndex > 0) {
                listState.animateScrollToItem(targetIndex - 1)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top bar
        ChatTopBar(
            title = chat?.model ?: "Loading...",
            onBack = onNavigateBack,
            onInfoClick = { viewModel.toggleSystemMessageDialog() },
            onMenuClick = { viewModel.toggleSettingsPanel() }
        )

        // Plugin notice
        if (chat != null) {
            Text(
                text = "Using model: ${chat.model}",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Messages or examples
        Box(modifier = Modifier.weight(1f)) {
            if (uiState.messages.isEmpty() && uiState.streamingContent.isEmpty()) {
                ExamplesView(
                    onExampleClick = { viewModel.sendMessage(it) }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(uiState.messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            onDelete = { viewModel.deleteMessage(message) }
                        )
                    }
                    // Regenerate button (visible when not streaming and last message is from assistant)
                    if (!uiState.isLoading &&
                        uiState.messages.lastOrNull()?.role == MessageRole.ASSISTANT.value
                    ) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.Start
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.regenerateLastResponse() },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Regenerate",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "Regenerate",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                    // Retry indicator
                    if (uiState.retryAttempt > 0) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Retrying... (attempt ${uiState.retryAttempt + 1})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    // Streaming message
                    if (uiState.streamingContent.isNotEmpty()) {
                        item {
                            MessageBubble(
                                message = Message(
                                    chatId = chatId,
                                    role = "assistant",
                                    content = uiState.streamingContent
                                ),
                                isStreaming = true
                            )
                        }
                    }
                }
            }

            // Error snackbar
            uiState.error?.let { error ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.dismissError() }) {
                            Text("Dismiss")
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.error
                ) {
                    Text(error, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }

        // Input bar
        ChatInputBar(
            isLoading = uiState.isLoading,
            onSend = { viewModel.sendMessage(it) },
            onStop = { viewModel.stopGenerating() }
        )
    }

    // Settings panel
    AnimatedVisibility(
        visible = uiState.showSettingsPanel,
        enter = slideInVertically { it },
        exit = slideOutVertically { it }
    ) {
        ChatSettingsPanel(
            chat = chat,
            onDismiss = { viewModel.toggleSettingsPanel() },
            onModelChange = { viewModel.updateModel(it) },
            onTemperatureChange = { viewModel.updateTemperature(it) },
            onTopPChange = { viewModel.updateTopP(it) },
            onMaxTokensChange = { viewModel.updateMaxTokens(it) },
            onPresencePenaltyChange = { viewModel.updatePresencePenalty(it) },
            onFrequencyPenaltyChange = { viewModel.updateFrequencyPenalty(it) },
            onClearHistory = { viewModel.clearHistory() },
            onShareMarkdown = { viewModel.getShareContentAsMarkdown() },
            onShareJson = { viewModel.getShareContentAsJson() }
        )
    }

    // System message dialog
    if (uiState.showSystemMessageDialog) {
        SystemMessageDialog(
            currentMessage = chat?.systemMessage ?: "",
            onDismiss = { viewModel.toggleSystemMessageDialog() },
            onConfirm = {
                viewModel.updateSystemMessage(it)
                viewModel.toggleSystemMessageDialog()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    title: String,
    onBack: () -> Unit,
    onInfoClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Chat,
                    contentDescription = "Chat",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Dialogue",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = "Options",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        actions = {
            IconButton(onClick = onInfoClick) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "System message",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onMenuClick) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}

@Composable
private fun ExamplesView(onExampleClick: (String) -> Unit) {
    val examples = listOf(
        "Explain quantum computing in simple terms",
        "Got any creative ideas for a 10 year old's birthday?",
        "What is the best way to learn AI?",
        "How do I make an HTTP request in Javascript?"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Examples",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        examples.forEach { example ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable { onExampleClick(example) },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = example,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    isStreaming: Boolean = false,
    onDelete: () -> Unit = {}
) {
    val isUser = message.role == "user"
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .let { if (isUser) it.align(Alignment.End) else it.align(Alignment.Start) }
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isUser) UserBubble else AssistantBubble)
                .clickable { showMenu = true }
                .padding(12.dp)
        ) {
            if (isUser) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    lineHeight = 22.sp
                )
            } else {
                MarkdownText(
                    text = message.content + if (isStreaming) "▊" else "",
                    modifier = if (isStreaming) Modifier.semantics {
                        contentDescription = "Assistant is typing"
                    } else Modifier,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Context menu
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Copy") },
                onClick = {
                    clipboardManager.setText(AnnotatedString(message.content))
                    showMenu = false
                },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = "Copy") }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    onDelete()
                    showMenu = false
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            )
        }

        // Token info for assistant messages
        if (!isUser && !isStreaming && message.tokenCount > 0) {
            Text(
                text = "${message.tokenCount} tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    isLoading: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Text input
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp, max = 120.dp)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = "Send a message",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 5
                )
            }

            // Send / Stop button
            if (isLoading) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            onSend(text)
                            text = ""
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (text.isNotBlank()) MaterialTheme.colorScheme.onSurface
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemMessageDialog(
    currentMessage: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentMessage) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = null,
        text = {
            Column {
                Text(
                    text = "System message is a global message sent with the chat every time. It can be used to set the context of the chat, such as the topic of the chat, the purpose of the chat, etc.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("System message") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text("OK", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.primary)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatSettingsPanel(
    chat: com.aichat.sandbox.data.model.Chat?,
    onDismiss: () -> Unit,
    onModelChange: (String) -> Unit,
    onTemperatureChange: (Float) -> Unit,
    onTopPChange: (Float) -> Unit,
    onMaxTokensChange: (Int) -> Unit,
    onPresencePenaltyChange: (Float) -> Unit,
    onFrequencyPenaltyChange: (Float) -> Unit,
    onClearHistory: () -> Unit,
    onShareMarkdown: () -> String = { "" },
    onShareJson: () -> String = { "" }
) {
    if (chat == null) return

    val allModels = ApiProvider.defaults.flatMap { it.models }
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 32.dp)
        ) {
            // Share options
            TextButton(onClick = {
                val content = onShareMarkdown()
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, content)
                }
                context.startActivity(Intent.createChooser(intent, "Share as Markdown"))
            }) {
                Icon(Icons.Default.Share, contentDescription = "Share as Markdown", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Share as Markdown")
            }
            TextButton(onClick = {
                val content = onShareJson()
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_TEXT, content)
                }
                context.startActivity(Intent.createChooser(intent, "Share as JSON"))
            }) {
                Icon(Icons.Default.Share, contentDescription = "Share as JSON", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Share as JSON")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Model selector
            ModelSelector(
                label = "Model",
                selectedModel = chat.model,
                models = allModels,
                onModelSelected = onModelChange
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Temperature
            SettingsSlider(
                label = "temperature",
                value = chat.temperature,
                valueRange = 0f..2f,
                onValueChange = onTemperatureChange,
                displayFormat = { String.format("%.1f", it) }
            )

            // Top P
            SettingsSlider(
                label = "top_p",
                value = chat.topP,
                valueRange = 0f..1f,
                onValueChange = onTopPChange,
                displayFormat = { String.format("%.1f", it) }
            )

            // Max tokens
            SettingsSlider(
                label = "Max tokens",
                value = chat.maxTokens.toFloat(),
                valueRange = 1f..ChatSettings.Defaults.MAX_TOKENS_LIMIT,
                onValueChange = { onMaxTokensChange(it.toInt()) },
                displayFormat = { it.toInt().toString() }
            )

            // Presence penalty
            SettingsSlider(
                label = "Presence penalty",
                value = chat.presencePenalty,
                valueRange = -2f..2f,
                onValueChange = onPresencePenaltyChange,
                displayFormat = { String.format("%.1f", it) }
            )

            // Frequency penalty
            SettingsSlider(
                label = "Frequency penalty",
                value = chat.frequencyPenalty,
                valueRange = -2f..2f,
                onValueChange = onFrequencyPenaltyChange,
                displayFormat = { String.format("%.1f", it) }
            )
        }
    }
}

