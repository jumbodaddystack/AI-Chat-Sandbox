package com.aichat.sandbox.ui.components.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Compose-side overlay editor for text items (sub-phase 1.9).
 *
 * Sits above the `DrawingSurface` so the IME never has to fight with the
 * AndroidView-hosted SurfaceView for focus — see "Risks" in the parent doc.
 * The underlying text item is hidden in the surface while editing so we
 * don't double-render.
 *
 * Positioning is in **screen** pixels: callers convert the item's world
 * origin through `ViewportController.worldToScreen…` and pass that here.
 * Font size also follows the viewport scale so what the user types matches
 * what the renderer will commit at the same zoom level.
 */
@Composable
fun TextItemEditor(
    initialBody: String,
    screenOriginX: Float,
    screenOriginY: Float,
    fontSizePx: Float,
    alignment: Byte,
    maxWidthPx: Float,
    onBodyChanged: (String) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onCommitRef by rememberUpdatedState(onCommit)
    val onBodyRef by rememberUpdatedState(onBodyChanged)

    val focusRequester = remember { FocusRequester() }
    var value by remember(initialBody) {
        mutableStateOf(
            TextFieldValue(text = initialBody, selection = androidx.compose.ui.text.TextRange(initialBody.length)),
        )
    }
    // Guard so the very first onFocusChanged callback (which fires with
    // `isFocused = false` before the LaunchedEffect lands) doesn't commit
    // and immediately tear the editor down.
    var hasFocusedOnce by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val density = LocalDensity.current
    val fontSp = with(density) { fontSizePx.toSp() }
    val maxWidthDp = with(density) { maxWidthPx.coerceAtLeast(0f).toDp() }

    Box(modifier = modifier.fillMaxSize()) {
        BasicTextField(
            value = value,
            onValueChange = {
                value = it
                onBodyRef(it.text)
            },
            modifier = Modifier
                .offset { IntOffset(screenOriginX.toInt(), screenOriginY.toInt()) }
                .widthIn(min = MIN_EDITOR_WIDTH_DP, max = maxWidthDp)
                .background(Color.White.copy(alpha = 0.92f))
                .border(width = 1.dp, color = MaterialTheme.colorScheme.primary)
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    // Tapping outside the field clears focus → commit. The
                    // surface-side onSelectionShouldClear fires on the same
                    // gesture, but [onCommit] is idempotent on the VM side
                    // (it bails when no edit target is active).
                    if (state.isFocused) {
                        hasFocusedOnce = true
                    } else if (hasFocusedOnce) {
                        onCommitRef()
                    }
                }
                .onPreviewKeyEvent { event ->
                    // Hardware ESC / dedicated Back key commits without losing
                    // focus first. Enter is intentionally not handled — newlines
                    // are part of the body.
                    if (event.type == KeyEventType.KeyUp &&
                        (event.key == Key.Escape || event.key == Key.Back)
                    ) {
                        onCommitRef()
                        true
                    } else {
                        false
                    }
                },
            textStyle = TextStyle(
                fontSize = fontSp,
                color = Color.Black,
                textAlign = composeAlignment(alignment),
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions.Default,
        )
    }
}

private fun composeAlignment(alignment: Byte): TextAlign = when (alignment) {
    TextItemCodec.ALIGN_CENTER -> TextAlign.Center
    TextItemCodec.ALIGN_RIGHT -> TextAlign.End
    else -> TextAlign.Start
}

private val MIN_EDITOR_WIDTH_DP = 80.dp
