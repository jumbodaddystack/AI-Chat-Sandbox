package com.aichat.sandbox.ui.screens.notes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.notes.PaletteScheme
import com.aichat.sandbox.data.notes.PaletteSource
import com.aichat.sandbox.data.notes.StylePreset
import com.aichat.sandbox.data.notes.StylePresetCatalog
import com.aichat.sandbox.ui.components.ModelSelector
import com.aichat.sandbox.ui.components.notes.BrushPreviewStroke

/**
 * Right-edge slide-in sheet that hosts the AI ask/reply loop for the note
 * editor (sub-phase 2.6 of `docs/STYLUS_NOTES_PHASE_2.md`).
 *
 * P0.2 (audit A2) — a non-blocking docked rail: no scrim, and the editor
 * reserves [aiSheetWidthFor] of layout for it so the canvas sits *beside* the
 * sheet (fully visible and live: draw / pan / point) rather than underneath a
 * tap-absorbing scrim. Streaming replies are appended to the active turn's
 * bubble; the lazy list auto-scrolls to the latest turn as it grows.
 *
 * Canned prompt chips and the scope chip landed in sub-phase 2.7. Sub-phase
 * 2.8 adds the per-reply action row (Copy / Insert / Send to chat) for every
 * Done turn and a `ModelSelector` in the header so the model can be swapped
 * mid-conversation (subsequent turns only — existing replies are immutable).
 */
@Composable
fun AiSideSheet(
    state: AiSideSheetState,
    onInputChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onCannedPrompt: (CannedPrompt) -> Unit,
    onIconQuickAction: (IconQuickAction) -> Unit,
    onFooterModeChanged: (AiFooterMode) -> Unit,
    onClearScope: () -> Unit,
    onReScopeToSelection: () -> Unit,
    canReScope: Boolean,
    onInsertConvertResult: (turnId: String) -> Unit,
    onInsertReply: (turnId: String) -> Unit,
    onSendReplyToChat: (turnId: String) -> Unit,
    onModelSelected: (String) -> Unit,
    availableModels: List<String>,
    customModels: List<String>,
    paletteState: PaletteUiState?,
    onOpenPalette: () -> Unit,
    onPaletteScheme: (PaletteScheme) -> Unit,
    onPaletteAi: () -> Unit,
    onPalettePreviewRecolor: () -> Unit,
    onPaletteClose: () -> Unit,
    critiqueState: CritiqueUiState?,
    onRequestCritique: () -> Unit,
    onPreviewCritiqueFix: (Int) -> Unit,
    onCritiqueClose: () -> Unit,
    brushDesignState: BrushDesignUiState?,
    onOpenBrushDesigner: () -> Unit,
    onBrushPromptChanged: (String) -> Unit,
    onDesignBrush: () -> Unit,
    onSaveBrush: () -> Unit,
    onClearBrushDesign: () -> Unit,
    onBrushDesignerClose: () -> Unit,
    restyleState: RestyleUiState?,
    onOpenRestyle: () -> Unit,
    onApplyStylePreset: (String) -> Unit,
    onRestyleClose: () -> Unit,
    drawWithMeState: DrawWithMeUiState?,
    inkAuthoringEnabled: Boolean,
    onOpenDrawWithMe: () -> Unit,
    onDrawWithMePromptChanged: (String) -> Unit,
    onStartDrawWithMe: () -> Unit,
    onOpenReplay: () -> Unit,
    onDrawWithMeClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val sheetWidth = aiSheetWidthFor(screenWidthDp)

    // P0.2 (audit A2) — the sheet is a non-blocking docked rail: the editor
    // reserves `sheetWidth` of layout for it (end padding on the canvas
    // column) so the canvas is never *underneath* the sheet. There is
    // therefore no scrim — the canvas stays fully visible and live (draw /
    // pan / point) while the AI panel is open, which is exactly what you want
    // while reviewing the on-canvas edit diff. Dismissal is the header's
    // close button (the old "tap the scrim" gesture had no visible target).
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = state.isOpen,
            enter = slideInHorizontally(
                animationSpec = tween(SHEET_ANIM_MS),
                initialOffsetX = { it },
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(SHEET_ANIM_MS),
                targetOffsetX = { it },
            ),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(sheetWidth),
        ) {
            Surface(
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    // Swallow taps inside the sheet so they don't fall through
                    // to anything layered behind the rail.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                SheetContent(
                    state = state,
                    onInputChanged = onInputChanged,
                    onSubmit = onSubmit,
                    onCancel = onCancel,
                    onClose = onClose,
                    onCannedPrompt = onCannedPrompt,
                    onIconQuickAction = onIconQuickAction,
                    onFooterModeChanged = onFooterModeChanged,
                    onClearScope = onClearScope,
                    onReScopeToSelection = onReScopeToSelection,
                    canReScope = canReScope,
                    onInsertConvertResult = onInsertConvertResult,
                    onInsertReply = onInsertReply,
                    onSendReplyToChat = onSendReplyToChat,
                    onModelSelected = onModelSelected,
                    availableModels = availableModels,
                    customModels = customModels,
                    paletteState = paletteState,
                    onOpenPalette = onOpenPalette,
                    onPaletteScheme = onPaletteScheme,
                    onPaletteAi = onPaletteAi,
                    onPalettePreviewRecolor = onPalettePreviewRecolor,
                    onPaletteClose = onPaletteClose,
                    critiqueState = critiqueState,
                    onRequestCritique = onRequestCritique,
                    onPreviewCritiqueFix = onPreviewCritiqueFix,
                    onCritiqueClose = onCritiqueClose,
                    brushDesignState = brushDesignState,
                    onOpenBrushDesigner = onOpenBrushDesigner,
                    onBrushPromptChanged = onBrushPromptChanged,
                    onDesignBrush = onDesignBrush,
                    onSaveBrush = onSaveBrush,
                    onClearBrushDesign = onClearBrushDesign,
                    onBrushDesignerClose = onBrushDesignerClose,
                    restyleState = restyleState,
                    onOpenRestyle = onOpenRestyle,
                    onApplyStylePreset = onApplyStylePreset,
                    onRestyleClose = onRestyleClose,
                    drawWithMeState = drawWithMeState,
                    inkAuthoringEnabled = inkAuthoringEnabled,
                    onOpenDrawWithMe = onOpenDrawWithMe,
                    onDrawWithMePromptChanged = onDrawWithMePromptChanged,
                    onStartDrawWithMe = onStartDrawWithMe,
                    onOpenReplay = onOpenReplay,
                    onDrawWithMeClose = onDrawWithMeClose,
                )
            }
        }
    }
}

@Composable
private fun SheetContent(
    state: AiSideSheetState,
    onInputChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onCannedPrompt: (CannedPrompt) -> Unit,
    onIconQuickAction: (IconQuickAction) -> Unit,
    onFooterModeChanged: (AiFooterMode) -> Unit,
    onClearScope: () -> Unit,
    onReScopeToSelection: () -> Unit,
    canReScope: Boolean,
    onInsertConvertResult: (turnId: String) -> Unit,
    onInsertReply: (turnId: String) -> Unit,
    onSendReplyToChat: (turnId: String) -> Unit,
    onModelSelected: (String) -> Unit,
    availableModels: List<String>,
    customModels: List<String>,
    paletteState: PaletteUiState?,
    onOpenPalette: () -> Unit,
    onPaletteScheme: (PaletteScheme) -> Unit,
    onPaletteAi: () -> Unit,
    onPalettePreviewRecolor: () -> Unit,
    onPaletteClose: () -> Unit,
    critiqueState: CritiqueUiState?,
    onRequestCritique: () -> Unit,
    onPreviewCritiqueFix: (Int) -> Unit,
    onCritiqueClose: () -> Unit,
    brushDesignState: BrushDesignUiState?,
    onOpenBrushDesigner: () -> Unit,
    onBrushPromptChanged: (String) -> Unit,
    onDesignBrush: () -> Unit,
    onSaveBrush: () -> Unit,
    onClearBrushDesign: () -> Unit,
    onBrushDesignerClose: () -> Unit,
    restyleState: RestyleUiState?,
    onOpenRestyle: () -> Unit,
    onApplyStylePreset: (String) -> Unit,
    onRestyleClose: () -> Unit,
    drawWithMeState: DrawWithMeUiState?,
    inkAuthoringEnabled: Boolean,
    onOpenDrawWithMe: () -> Unit,
    onDrawWithMePromptChanged: (String) -> Unit,
    onStartDrawWithMe: () -> Unit,
    onOpenReplay: () -> Unit,
    onDrawWithMeClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SheetHeader(
            title = if (state.isIcon) "AI for this icon" else "Ask about this note",
            modelId = state.activeModelId,
            availableModels = availableModels,
            customModels = customModels,
            onModelSelected = onModelSelected,
            onClose = onClose,
            footerMode = state.footerMode,
            isStreaming = state.isStreaming,
            onFooterModeChanged = onFooterModeChanged,
        )
        HorizontalDivider()
        ConversationList(
            turns = state.turns,
            onInsertConvertResult = onInsertConvertResult,
            onInsertReply = onInsertReply,
            onSendReplyToChat = onSendReplyToChat,
            modifier = Modifier.weight(1f),
        )
        HorizontalDivider()
        // Phase 2 — palette & colour-harmony panel sits above the scope row
        // when open, so its swatches and actions read as part of the AI surface.
        if (paletteState?.isOpen == true) {
            PalettePanel(
                state = paletteState,
                onScheme = onPaletteScheme,
                onAskAi = onPaletteAi,
                onPreviewRecolor = onPalettePreviewRecolor,
                onClose = onPaletteClose,
            )
            HorizontalDivider()
        }
        // Phase 3 — composition critique panel sits alongside the palette panel,
        // above the scope row, when open.
        if (critiqueState?.isOpen == true) {
            CritiquePanel(
                state = critiqueState,
                onPreviewFix = onPreviewCritiqueFix,
                onRetry = onRequestCritique,
                onClose = onCritiqueClose,
            )
            HorizontalDivider()
        }
        // Phase 4 (N1) — AI brush designer panel sits alongside the palette and
        // critique panels, above the scope row, when open.
        if (brushDesignState?.isOpen == true) {
            BrushDesignerPanel(
                state = brushDesignState,
                onPromptChanged = onBrushPromptChanged,
                onDesign = onDesignBrush,
                onSave = onSaveBrush,
                onClear = onClearBrushDesign,
                onClose = onBrushDesignerClose,
            )
            HorizontalDivider()
        }
        // Phase 7 — named-style restyle panel sits alongside the other
        // art-assist panels, above the scope row, when open.
        if (restyleState?.isOpen == true) {
            RestylePanel(
                state = restyleState,
                onApplyPreset = onApplyStylePreset,
                onClose = onRestyleClose,
            )
            HorizontalDivider()
        }
        // Phase 6 (N4) — "Draw with me" launcher sits alongside the other
        // art-assist panels, above the scope row, when open.
        if (drawWithMeState?.isOpen == true) {
            DrawWithMePanel(
                state = drawWithMeState,
                inkEnabled = inkAuthoringEnabled,
                onPromptChanged = onDrawWithMePromptChanged,
                onStart = onStartDrawWithMe,
                onReplay = onOpenReplay,
                onClose = onDrawWithMeClose,
            )
            HorizontalDivider()
        }
        ScopeAndCannedPromptRow(
            scopeLabel = state.scopeLabel,
            hasSelection = state.pendingSelection != null,
            isIcon = state.isIcon,
            convertEnabled = state.pendingSelection != null && !state.isStreaming,
            isStreaming = state.isStreaming,
            canReScope = canReScope,
            paletteOpen = paletteState?.isOpen == true,
            critiqueOpen = critiqueState?.isOpen == true,
            brushDesignerOpen = brushDesignState?.isOpen == true,
            restyleOpen = restyleState?.isOpen == true,
            drawWithMeOpen = drawWithMeState?.isOpen == true,
            onCannedPrompt = onCannedPrompt,
            onIconQuickAction = onIconQuickAction,
            onClearScope = onClearScope,
            onReScopeToSelection = onReScopeToSelection,
            onOpenPalette = onOpenPalette,
            onRequestCritique = onRequestCritique,
            onOpenBrushDesigner = onOpenBrushDesigner,
            onOpenRestyle = onOpenRestyle,
            onOpenDrawWithMe = onOpenDrawWithMe,
        )
        SheetFooter(
            inputText = state.inputText,
            footerMode = state.footerMode,
            isStreaming = state.isStreaming,
            onInputChanged = onInputChanged,
            onSubmit = onSubmit,
            onCancel = onCancel,
        )
    }
}

@Composable
private fun SheetHeader(
    title: String,
    modelId: String,
    availableModels: List<String>,
    customModels: List<String>,
    onModelSelected: (String) -> Unit,
    onClose: () -> Unit,
    footerMode: AiFooterMode,
    isStreaming: Boolean,
    onFooterModeChanged: (AiFooterMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close AI panel")
            }
        }
        // A3 fix — the Ask / Edit toggle (it changes the assistant's whole
        // behaviour: prose reply vs. staged vector edits) used to sit unlabelled
        // at the very bottom of the footer, far from where attention lives.
        // Moved up under the title with a one-line description of the active
        // mode so the consequence of the toggle is spelled out, not guessed.
        AskEditModeSelector(
            footerMode = footerMode,
            isStreaming = isStreaming,
            onFooterModeChanged = onFooterModeChanged,
        )
        Spacer(modifier = Modifier.height(8.dp))
        // Inline model picker (sub-phase 2.8). Switching mid-conversation
        // only affects subsequent turns — replies already on screen keep
        // showing whatever model produced them.
        ModelSelector(
            label = "Model",
            selectedModel = modelId,
            models = availableModels,
            onModelSelected = onModelSelected,
            customModels = customModels,
        )
    }
}

/**
 * A3 fix — the Ask | Edit mode toggle plus a one-line description of the
 * active mode. ASK returns a prose reply in the conversation; EDIT stages a
 * vector-edit preview the user accepts or rejects on the canvas. Two
 * `FilterChip`s keep the affordance compact and avoid depending on the
 * `SegmentedButton` API version.
 */
@Composable
private fun AskEditModeSelector(
    footerMode: AiFooterMode,
    isStreaming: Boolean,
    onFooterModeChanged: (AiFooterMode) -> Unit,
) {
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = footerMode == AiFooterMode.ASK,
            onClick = { onFooterModeChanged(AiFooterMode.ASK) },
            label = { Text("Ask") },
            enabled = !isStreaming,
            colors = FilterChipDefaults.filterChipColors(),
        )
        FilterChip(
            selected = footerMode == AiFooterMode.EDIT,
            onClick = { onFooterModeChanged(AiFooterMode.EDIT) },
            label = { Text("Edit") },
            enabled = !isStreaming,
            colors = FilterChipDefaults.filterChipColors(),
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = if (footerMode == AiFooterMode.EDIT) {
            "Edit — stages changes to draw on the canvas; you accept or reject them."
        } else {
            "Ask — answers in the conversation without changing your drawing."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ConversationList(
    turns: List<AskTurn>,
    onInsertConvertResult: (turnId: String) -> Unit,
    onInsertReply: (turnId: String) -> Unit,
    onSendReplyToChat: (turnId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val latestTurnId = turns.lastOrNull()?.id
    val latestReplyLength = turns.lastOrNull()?.replyBuffer?.length ?: 0

    // Re-scroll to the bottom whenever a new turn lands OR the active reply
    // grows. Cheap because LazyColumn only re-measures the tail.
    LaunchedEffect(latestTurnId, latestReplyLength) {
        val lastIndex = turns.lastIndex
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
    }

    if (turns.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Ask anything about this note.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(turns, key = { it.id }) { turn ->
            TurnBubble(
                turn = turn,
                onInsertConvertResult = onInsertConvertResult,
                onInsertReply = onInsertReply,
                onSendReplyToChat = onSendReplyToChat,
            )
        }
    }
}

@Composable
private fun TurnBubble(
    turn: AskTurn,
    onInsertConvertResult: (turnId: String) -> Unit,
    onInsertReply: (turnId: String) -> Unit,
    onSendReplyToChat: (turnId: String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PromptBubble(prompt = turn.prompt, selectionSummary = turn.selectionSummary)
        Spacer(modifier = Modifier.height(4.dp))
        ReplyBubble(turn = turn)
        // Reply-action row — visible once the turn is Done and produced
        // text. Convert-to-text replies keep the slimmer single-action row
        // because Copy / Send-to-chat don't add much value for raw OCR
        // output (Phase 4 may revisit). The general AI replies get the
        // full Copy / Insert / Send-to-chat row that sub-phase 2.8
        // promises.
        if (turn.state is TurnState.Done && turn.replyBuffer.isNotEmpty()) {
            if (turn.isConvertResult) {
                // A8 fix — OCR text is auto-placed on the canvas the instant it
                // resolves, so the bubble confirms the placement and offers a
                // re-insert instead of demanding a second tap to insert at all.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    if (turn.convertInserted) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Added as a text box",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    TextButton(onClick = { onInsertConvertResult(turn.id) }) {
                        Icon(
                            imageVector = Icons.Filled.TextFields,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (turn.convertInserted) "Insert again" else "Insert as text box")
                    }
                }
            } else {
                ReplyActionRow(
                    text = turn.replyBuffer,
                    onInsert = { onInsertReply(turn.id) },
                    onSendToChat = { onSendReplyToChat(turn.id) },
                )
            }
        }
    }
}

/**
 * Per-reply action buttons for AI replies (sub-phase 2.8). Copy lives in
 * the composable rather than the VM so it has a `LocalClipboardManager`
 * handle without bouncing through an `ApplicationContext` injection.
 *
 * Buttons stay text-only (no surface chip) to keep the bubble visually
 * close to a chat message — the actions are the affordance, not a separate
 * surface fighting the reply for attention.
 */
@Composable
private fun ReplyActionRow(
    text: String,
    onInsert: () -> Unit,
    onSendToChat: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Copy")
        }
        TextButton(onClick = onInsert) {
            Icon(
                imageVector = Icons.Filled.TextFields,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Insert")
        }
        TextButton(onClick = onSendToChat) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Send to chat")
        }
    }
}

@Composable
private fun PromptBubble(prompt: String, selectionSummary: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (selectionSummary != null) {
                    Text(
                        text = selectionSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ReplyBubble(turn: AskTurn) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                when (turn.state) {
                    is TurnState.Streaming -> {
                        if (turn.replyBuffer.isEmpty()) {
                            TypingIndicator()
                        } else {
                            Text(
                                text = turn.replyBuffer,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    is TurnState.Done -> {
                        Text(
                            text = turn.replyBuffer.ifEmpty { "(empty response)" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is TurnState.Error -> {
                        if (turn.replyBuffer.isNotEmpty()) {
                            Text(
                                text = turn.replyBuffer,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Text(
                            text = turn.state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(14.dp)
                .semantics { contentDescription = "Streaming response" },
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Thinking…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Scope chip + canned-prompt row (sub-phase 2.7). Sits above the input
 * field. The scope chip ("Whole note" or "3 strokes selected") tap-clears
 * the frozen selection so the user can pivot mid-conversation. Canned
 * prompts fire on a single tap; Convert-to-text is only enabled when there
 * is a selection in scope.
 *
 * A6 fix — the frozen scope is captured once at open time, so a user who
 * lassoes something new mid-conversation was silently still asking about the
 * old selection. The row now spells out that the scope is fixed and offers a
 * "Use selection" chip ([canReScope]) to re-point it at the current canvas
 * selection, alongside the existing tap-to-clear.
 */
@Composable
private fun ScopeAndCannedPromptRow(
    scopeLabel: String,
    hasSelection: Boolean,
    isIcon: Boolean,
    convertEnabled: Boolean,
    isStreaming: Boolean,
    canReScope: Boolean,
    paletteOpen: Boolean,
    critiqueOpen: Boolean,
    brushDesignerOpen: Boolean,
    restyleOpen: Boolean,
    drawWithMeOpen: Boolean,
    onCannedPrompt: (CannedPrompt) -> Unit,
    onIconQuickAction: (IconQuickAction) -> Unit,
    onClearScope: () -> Unit,
    onReScopeToSelection: () -> Unit,
    onOpenPalette: () -> Unit,
    onRequestCritique: () -> Unit,
    onOpenBrushDesigner: () -> Unit,
    onOpenRestyle: () -> Unit,
    onOpenDrawWithMe: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = hasSelection,
                onClick = { if (hasSelection) onClearScope() },
                label = { Text(scopeLabel, style = MaterialTheme.typography.labelMedium) },
                leadingIcon = if (hasSelection) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else null,
                enabled = hasSelection,
                colors = FilterChipDefaults.filterChipColors(),
            )
            // Re-point the frozen scope at the live canvas selection. Hidden
            // unless there's something selected to re-scope to.
            if (canReScope) {
                AssistChip(
                    onClick = onReScopeToSelection,
                    enabled = !isStreaming,
                    label = { Text("Use selection") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = if (hasSelection) {
                "Scope is fixed to this selection. Tap it to clear, or re-scope to a new one."
            } else {
                "Answering about the whole ${if (isIcon) "icon" else "note"}."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Phase 2 — palette assistant entry point, available for both notes
            // and icons. Disabled while the panel is already open.
            item(key = "palette_help") {
                AssistChip(
                    onClick = onOpenPalette,
                    enabled = !paletteOpen,
                    label = { Text("Palette help") },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
            // Phase 3 — composition critique entry point, available for both
            // notes and icons. Disabled while the panel is already open.
            item(key = "critique") {
                AssistChip(
                    onClick = onRequestCritique,
                    enabled = !critiqueOpen,
                    label = { Text("Critique") },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
            // Phase 4 (N1) — AI brush designer entry point. Available for both
            // notes and icons; designing a brush never touches the canvas.
            // Disabled while the panel is already open.
            item(key = "brush_designer") {
                AssistChip(
                    onClick = onOpenBrushDesigner,
                    enabled = !brushDesignerOpen,
                    label = { Text("Brush designer") },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
            // Phase 7 — named-style restyle entry point, available for both
            // notes and icons. Disabled while the panel is already open.
            item(key = "restyle") {
                AssistChip(
                    onClick = onOpenRestyle,
                    enabled = !restyleOpen,
                    label = { Text("Restyle") },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
            // Phase 6 (N4) — "Draw with me" tutor + replay entry point. Always
            // present so the capability is discoverable; the launch button
            // inside the panel is gated behind the experimental ink engine.
            item(key = "draw_with_me") {
                AssistChip(
                    onClick = onOpenDrawWithMe,
                    enabled = !drawWithMeOpen,
                    label = { Text("Draw with me") },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
            if (isIcon) {
                // Icons get design-oriented edit actions instead of the
                // note-centric ask prompts. Convert-to-text is dropped — it's
                // meaningless for an icon. Each action routes through the
                // model-backed EDIT pipeline (Recolor opens the colour picker).
                items(IconQuickAction.entries, key = { it.name }) { action ->
                    AssistChip(
                        onClick = { onIconQuickAction(action) },
                        enabled = !isStreaming,
                        label = { Text(action.label) },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            } else {
                items(CannedPrompt.ASK_PROMPTS, key = { it.name }) { prompt ->
                    AssistChip(
                        onClick = { onCannedPrompt(prompt) },
                        enabled = !isStreaming,
                        label = { Text(prompt.label) },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
                // Convert-to-text last so the order matches the lasso menu and
                // it visually trails the ask prompts as the "different kind" one.
                items(listOf(CannedPrompt.CONVERT_TO_TEXT), key = { it.name }) { prompt ->
                    AssistChip(
                        onClick = { onCannedPrompt(prompt) },
                        enabled = convertEnabled,
                        label = { Text(prompt.label) },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }
        }
    }
}

/**
 * Phase 2 — palette & colour-harmony panel. Scheme chips regenerate a local
 * palette instantly; "Ask AI" refines it through the model. Swatches preview
 * the colours; "Preview recolor" stages a `recolor` edit through the normal
 * on-canvas diff (accept/reject lives in the banner), and "Copy palette" puts
 * the hex list on the clipboard. The suggestion itself never mutates the canvas.
 */
@Composable
private fun PalettePanel(
    state: PaletteUiState,
    onScheme: (PaletteScheme) -> Unit,
    onAskAi: () -> Unit,
    onPreviewRecolor: () -> Unit,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val suggestion = state.suggestion
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Palette help",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (state.source == PaletteSource.AI) "AI" else "Local",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close palette panel",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        // Scheme chips.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(PaletteScheme.entries, key = { it.name }) { scheme ->
                FilterChip(
                    selected = scheme == state.scheme,
                    onClick = { onScheme(scheme) },
                    enabled = !state.loading,
                    label = { Text(scheme.label, style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Swatch row.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            suggestion?.swatches?.forEach { argb ->
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(argb))
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .semantics { contentDescription = hexOf(argb) },
                )
            }
        }
        val rationale = suggestion?.rationale
        if (!rationale.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = rationale,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.error?.let { err ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = err,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Action row.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AssistChip(
                onClick = onPreviewRecolor,
                enabled = suggestion != null && !state.loading,
                label = { Text("Preview recolor") },
                colors = AssistChipDefaults.assistChipColors(),
            )
            AssistChip(
                onClick = {
                    suggestion?.let {
                        clipboard.setText(AnnotatedString(it.swatches.joinToString(" ", transform = ::hexOf)))
                    }
                },
                enabled = suggestion != null,
                label = { Text("Copy palette") },
                colors = AssistChipDefaults.assistChipColors(),
            )
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                AssistChip(
                    onClick = onAskAi,
                    label = { Text("Ask AI") },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
        }
    }
}

/** Format an opaque ARGB int as an `#RRGGBB` string for copy / a11y labels. */
private fun hexOf(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

/**
 * Phase 3 — guided composition / layout critique panel. Opens in a loading
 * state (a critique always needs the model), then renders the summary plus 3–5
 * advisory cards. Each card explains a design principle in plain language;
 * cards that carry a safe, validated edit-op payload also offer "Preview fix",
 * which stages the change through the normal on-canvas diff (accept/reject lives
 * in the banner). Prose-only cards are still useful — the panel never mutates
 * the canvas on its own.
 */
@Composable
private fun CritiquePanel(
    state: CritiqueUiState,
    onPreviewFix: (Int) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val critique = state.critique
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Composition critique",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            // Re-run is offered once a result (or error) has landed.
            if (!state.loading) {
                IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Re-run critique",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close critique panel",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        when {
            state.loading -> {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Looking at your drawing…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            critique == null -> {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = state.error ?: "Couldn't get a critique. Try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            else -> {
                if (critique.summary.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = critique.summary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                // Cards can be tall (up to five); keep the panel bounded and let
                // the cards scroll inside it so the footer/input stay reachable.
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    critique.suggestions.forEachIndexed { index, suggestion ->
                        CritiqueCard(
                            suggestion = suggestion,
                            onPreviewFix = { onPreviewFix(index) },
                        )
                    }
                }
                if (critique.safetyNotes.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = critique.safetyNotes,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // A preview error (e.g. "nothing left to change") shows under the
                // cards without clearing the critique itself.
                state.error?.let { err ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * One critique suggestion rendered as a card: title, principle, a plain-language
 * reason, small confidence/effort labels, and — only when the suggestion carries
 * a validated fix — a "Preview fix" action. Prose-only cards show a quiet "Tip
 * only" marker so the absence of a button reads as intentional.
 */
@Composable
private fun CritiqueCard(
    suggestion: com.aichat.sandbox.data.notes.CritiqueSuggestion,
    onPreviewFix: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = suggestion.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (suggestion.principle.isNotBlank()) {
                Text(
                    text = suggestion.principle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (suggestion.why.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = suggestion.why,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "${suggestion.confidence.label} · ${suggestion.effort.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (suggestion.hasFix) {
                    AssistChip(
                        onClick = onPreviewFix,
                        label = { Text("Preview fix") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                } else {
                    Text(
                        text = "Tip only",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Phase 4 (N1) — AI brush designer panel. The user describes a brush in plain
 * words (or taps an example), the model returns a validated [BrushSpec], and the
 * panel renders a deterministic sample stroke. Saving is a deliberate second
 * step that writes a reusable user-scope preset to the brush palette — designing
 * never touches the canvas, and a parse failure surfaces an error rather than
 * leaving a half-made preset behind.
 */
@Composable
private fun BrushDesignerPanel(
    state: BrushDesignUiState,
    onPromptChanged: (String) -> Unit,
    onDesign: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val spec = state.spec
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Brush designer",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close brush designer",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            text = "Describe a brush and AI builds a reusable preset. Your drawing isn't touched.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = state.prompt,
            onValueChange = onPromptChanged,
            placeholder = { Text("e.g. dry gouache with soft edges") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            maxLines = 3,
            enabled = !state.loading,
        )
        Spacer(modifier = Modifier.height(6.dp))
        // Example prompts — one tap fills the field so a blank-page user has a
        // starting point. They don't fire the request, leaving room to tweak.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(BRUSH_EXAMPLES, key = { it }) { example ->
                AssistChip(
                    onClick = { onPromptChanged(example) },
                    enabled = !state.loading,
                    label = { Text(example) },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (spec != null) {
            // Deterministic sample stroke on a subtle card.
            Surface(
                tonalElevation = 1.dp,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                BrushPreviewStroke(
                    spec = spec,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(8.dp),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${spec.name} · ${spec.tool} · ${spec.textureId.replaceFirstChar { it.uppercase() }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        state.error?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        // Action row reflects the flow stage: design → preview/save → saved.
        when {
            state.loading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Designing your brush…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.savedName != null -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Saved “${state.savedName}” — it's now your active brush.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClear) { Text("Design another") }
                }
            }
            spec != null -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSave, enabled = state.canSave) { Text("Save brush") }
                    TextButton(onClick = onDesign) { Text("Re-roll") }
                }
            }
            else -> {
                Button(
                    onClick = onDesign,
                    enabled = state.prompt.isNotBlank(),
                ) { Text("Design brush") }
            }
        }
    }
}

/** Example brush prompts surfaced as one-tap chips in the designer (Phase 4). */
private val BRUSH_EXAMPLES = listOf(
    "inky brush pen",
    "dry gouache",
    "soft marker",
    "scratchy pencil",
)

/**
 * Phase 7 — named-style restyle panel. Tapping a preset chip ("Flat icon",
 * "Line art", "Isometric", "Sticker") asks the model to re-dress the in-scope
 * geometry into that look; the validated, restyle-safe ops stage as a normal
 * on-canvas diff (accept/reject lives in the banner), so the panel itself never
 * mutates the canvas. A spinner marks the preset in flight, and a footnote makes
 * the difference from the local copy/paste-style tool explicit.
 */
@Composable
private fun RestylePanel(
    state: RestyleUiState,
    onApplyPreset: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Restyle into a look",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close restyle panel",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            text = "Pick a named look to restyle the selected items (or the whole drawing). " +
                "You'll preview the change on the canvas before keeping it.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StylePresetCatalog.PRESETS.forEach { preset ->
                StylePresetCard(
                    preset = preset,
                    busy = state.loading && state.activePresetId == preset.id,
                    enabled = !state.loading,
                    onApply = { onApplyPreset(preset.id) },
                )
            }
        }
        state.error?.let { err ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = err,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "This restyles into an AI look. To copy the exact style of one item " +
                "onto another instead, use copy/paste style from the selection menu.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One named-style preset rendered as a card: the look's name + tagline and an
 * "Apply" action (a spinner replaces it while that preset's request is in
 * flight). Applying stages a previewable restyle edit on the canvas.
 */
@Composable
private fun StylePresetCard(
    preset: StylePreset,
    busy: Boolean,
    enabled: Boolean,
    onApply: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preset.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = preset.tagline,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                AssistChip(
                    onClick = onApply,
                    enabled = enabled,
                    label = { Text("Apply") },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
        }
    }
}

/**
 * Phase 6 (N4 / idea #7) — "Draw with me" launcher. Two related N4 actions:
 *  - **Learn to draw**: type a subject; the model authors simple construction
 *    strokes through the unchanged GENERATE pipeline (so they preview as a
 *    normal staged edit) that land on a ghosted guide layer, then the canvas
 *    overlay steps the reveal Next / Back / Skip / Redo.
 *  - **Replay**: scrub the marks already on the note in draw order.
 *
 * Both are gated behind the experimental ink engine: when [inkEnabled] is false
 * the actions render disabled under a line of copy explaining how to turn it on
 * (mirroring Phase 5's "Smart select"), so the feature is discoverable without
 * flipping the default.
 */
@Composable
private fun DrawWithMePanel(
    state: DrawWithMeUiState,
    inkEnabled: Boolean,
    onPromptChanged: (String) -> Unit,
    onStart: () -> Unit,
    onReplay: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Draw with me",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close draw with me",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            text = "Learn a subject step by step on a ghost guide layer, or replay how this drawing was made.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!inkEnabled) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Turn on the experimental ink engine (More ▸ Ink engine) to use draw-with-me and replay.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.prompt,
            onValueChange = onPromptChanged,
            placeholder = { Text("e.g. a fox sitting") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            maxLines = 2,
            enabled = inkEnabled,
        )
        Spacer(modifier = Modifier.height(6.dp))
        // Example subjects — one tap fills the field (doesn't fire) so a
        // blank-page user has somewhere to start.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(DRAW_WITH_ME_EXAMPLES, key = { it }) { example ->
                AssistChip(
                    onClick = { onPromptChanged(example) },
                    enabled = inkEnabled,
                    label = { Text(example) },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
        }
        state.error?.let { err ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = err,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStart,
                enabled = inkEnabled && state.prompt.isNotBlank(),
            ) { Text("Start") }
            TextButton(
                onClick = onReplay,
                enabled = inkEnabled,
            ) { Text("Replay drawing") }
        }
    }
}

/** Example subjects surfaced as one-tap chips in the draw-with-me launcher (Phase 6). */
private val DRAW_WITH_ME_EXAMPLES = listOf(
    "a fox",
    "a simple house",
    "a coffee cup",
    "a smiling face",
)

@Composable
private fun SheetFooter(
    inputText: String,
    footerMode: AiFooterMode,
    isStreaming: Boolean,
    onInputChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
  Column(
      modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 8.dp),
  ) {
    // A3 fix — the Ask | Edit toggle moved up to the header (next to the
    // title) so the footer is just the input + send. The placeholder still
    // reflects the active mode as a second cue.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChanged,
            placeholder = {
                Text(
                    if (footerMode == AiFooterMode.EDIT) "Describe an edit…"
                    else "Ask anything…"
                )
            },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            maxLines = 5,
            enabled = !isStreaming,
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (isStreaming) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .size(48.dp),
            ) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = "Stop response",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        } else {
            val enabled = inputText.isNotBlank()
            IconButton(
                onClick = onSubmit,
                enabled = enabled,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .size(48.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send prompt",
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
  }
}

/**
 * Width the docked AI rail occupies for a given [screenWidthDp]. Shared by
 * the sheet itself and by the editor, which reserves the same width as end
 * padding on the canvas column so the two never overlap (P0.2 / audit A2).
 * Narrower than the pre-dock 70% overlay so a phone keeps a live canvas strip
 * beside the rail; still capped so a tablet rail doesn't sprawl.
 */
fun aiSheetWidthFor(screenWidthDp: Dp): Dp =
    (screenWidthDp.value * SHEET_WIDTH_FRACTION).dp
        .coerceAtLeast(MIN_SHEET_WIDTH)
        .coerceAtMost(MAX_SHEET_WIDTH)

private const val SHEET_WIDTH_FRACTION: Float = 0.5f
private val MIN_SHEET_WIDTH = 280.dp
private val MAX_SHEET_WIDTH = 460.dp
private const val SHEET_ANIM_MS: Int = 220
