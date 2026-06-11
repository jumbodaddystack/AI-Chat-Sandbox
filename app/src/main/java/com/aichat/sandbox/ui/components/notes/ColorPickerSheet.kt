package com.aichat.sandbox.ui.components.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Sub-phase 5.3 modal colour picker.
 *
 * Layout (top → bottom):
 *  1. **Recents row** — twelve most-recent custom colours, one-tap apply.
 *  2. **Hue ring + SV square** — `hue` selected by dragging around the ring;
 *     `saturation` and `value` (HSV brightness) selected by dragging inside
 *     the inscribed square. The square's background is the live hue. The
 *     square's gradients (white→hue across, →black down) are the classic HSV
 *     layout, so the picked channel math must be HSV too — reading the thumb
 *     position as HSL used to return a much lighter colour than the pixel
 *     under the indicator.
 *  3. **Alpha slider** — `0..255` mapped to a translucent → opaque gradient.
 *  4. **Hex input + preview swatch** — accepts `#RRGGBB` and `#AARRGGBB`,
 *     rejects malformed input inline (the colour preview holds the last
 *     valid value).
 *  5. **Confirm / Cancel** — `Confirm` returns the ARGB int via [onConfirm]
 *     and dismisses; `Cancel` just dismisses.
 *
 * Performance: the picker is a transient sheet, so we don't bother
 * minimizing recomposition beyond the per-channel local `mutableFloatStateOf`
 * pattern. Anything more would burn complexity for no win.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerSheet(
    initialColorArgb: Int,
    recents: List<Int>,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Decompose the initial colour into HSV + alpha so the wheel and SV
    // square land on the user's existing pick.
    val initialHsv = remember(initialColorArgb) { argbToHsv(initialColorArgb) }
    var hue by remember { mutableFloatStateOf(initialHsv.hue) }
    var saturation by remember { mutableFloatStateOf(initialHsv.saturation) }
    var value by remember { mutableFloatStateOf(initialHsv.value) }
    var alpha by remember { mutableFloatStateOf((initialColorArgb ushr 24) / 255f) }
    var hexText by remember(initialColorArgb) {
        mutableStateOf(formatHex(initialColorArgb))
    }
    var hexError by remember { mutableStateOf(false) }

    val currentColor by remember {
        derivedStateOf {
            hsvaToArgb(hue, saturation, value, alpha)
        }
    }

    // Re-sync the hex field when the user manipulates the wheel / sliders so
    // the textual representation stays in lockstep.
    LaunchedEffect(currentColor) {
        if (!hexError) hexText = formatHex(currentColor)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Custom colour",
                style = MaterialTheme.typography.titleMedium,
            )

            if (recents.isNotEmpty()) {
                RecentsRow(
                    recents = recents,
                    onPick = { picked ->
                        val hsv = argbToHsv(picked)
                        hue = hsv.hue
                        saturation = hsv.saturation
                        value = hsv.value
                        alpha = (picked ushr 24) / 255f
                        hexError = false
                        hexText = formatHex(picked)
                    },
                )
            }

            HueRingWithSVSquare(
                hue = hue,
                saturation = saturation,
                value = value,
                onHueChanged = { hue = it },
                onSVChanged = { s, v -> saturation = s; value = v },
            )

            AlphaSlider(
                hue = hue,
                saturation = saturation,
                value = value,
                alpha = alpha,
                onAlphaChanged = { alpha = it },
            )

            HexRow(
                hex = hexText,
                error = hexError,
                previewArgb = currentColor,
                initialArgb = initialColorArgb,
                onHexChanged = { text ->
                    hexText = text
                    val parsed = parseHex(text)
                    if (parsed == null) {
                        hexError = true
                    } else {
                        hexError = false
                        val hsv = argbToHsv(parsed)
                        hue = hsv.hue
                        saturation = hsv.saturation
                        value = hsv.value
                        alpha = (parsed ushr 24) / 255f
                    }
                },
                onRestoreInitial = {
                    hue = initialHsv.hue
                    saturation = initialHsv.saturation
                    value = initialHsv.value
                    alpha = (initialColorArgb ushr 24) / 255f
                    hexError = false
                    hexText = formatHex(initialColorArgb)
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onConfirm(currentColor) },
                    enabled = !hexError,
                ) { Text("Use colour") }
            }
        }
    }
}

@Composable
private fun RecentsRow(
    recents: List<Int>,
    onPick: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Recent",
            style = MaterialTheme.typography.labelMedium,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            recents.take(RecentColorsStoreLimit).forEach { argb ->
                // Full-height 28 dp tap target around the 24 dp visual — the
                // bare circles were the smallest targets on the sheet.
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clickable { onPick(argb) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(0.5.dp, Color.Black.copy(alpha = 0.25f), CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun HueRingWithSVSquare(
    hue: Float,
    saturation: Float,
    value: Float,
    onHueChanged: (Float) -> Unit,
    onSVChanged: (Float, Float) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        val sizePx = with(LocalDensity.current) { maxWidth.toPx() }
        val ringThickness = sizePx * 0.10f
        val outerR = sizePx * 0.5f
        val innerR = outerR - ringThickness
        // The SV square is inscribed in the inner circle. Side = inner * √2.
        val squareSide = innerR * 1.4142f
        val squareHalf = squareSide * 0.5f
        val center = Offset(outerR, outerR)

        val sweep = remember {
            Brush.sweepGradient(
                colors = listOf(
                    Color.hsl(0f, 1f, 0.5f),
                    Color.hsl(60f, 1f, 0.5f),
                    Color.hsl(120f, 1f, 0.5f),
                    Color.hsl(180f, 1f, 0.5f),
                    Color.hsl(240f, 1f, 0.5f),
                    Color.hsl(300f, 1f, 0.5f),
                    Color.hsl(360f, 1f, 0.5f),
                ),
            )
        }

        // Hue ring layer.
        Canvas(
            modifier = Modifier
                .size(maxWidth)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val r = hypot(pos.x - center.x, pos.y - center.y)
                            if (r in innerR..outerR) {
                                onHueChanged(angleOf(center, pos))
                            }
                        },
                    ) { change, _ ->
                        val r = hypot(change.position.x - center.x, change.position.y - center.y)
                        // Once the user begins on the ring we keep tracking
                        // even outside its strict thickness — a finger drifts.
                        if (r > innerR * 0.5f) {
                            onHueChanged(angleOf(center, change.position))
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        val r = hypot(pos.x - center.x, pos.y - center.y)
                        if (r in innerR..outerR) {
                            onHueChanged(angleOf(center, pos))
                        }
                    }
                },
        ) {
            // Draw the hue ring as a filled outer circle minus an inner disc.
            drawCircle(
                brush = sweep,
                radius = outerR,
                center = center,
            )
            drawCircle(
                color = Color.White,
                radius = innerR,
                center = center,
            )
            // Hue thumb.
            val thumbAngle = Math.toRadians(hue.toDouble()).toFloat()
            val midR = (outerR + innerR) * 0.5f
            val thumbCenter = Offset(
                x = center.x + midR * cos(thumbAngle),
                y = center.y + midR * sin(thumbAngle),
            )
            drawCircle(
                color = Color.White,
                radius = ringThickness * 0.45f,
                center = thumbCenter,
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.6f),
                radius = ringThickness * 0.45f,
                center = thumbCenter,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
            )
        }

        // SV square — separate Canvas inside the ring.
        val squareSideDp = with(LocalDensity.current) { squareSide.toDp() }
        Box(
            modifier = Modifier
                .size(squareSideDp)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            Canvas(
                modifier = Modifier
                    .size(squareSideDp)
                    .pointerInput(hue) {
                        detectDragGestures { change, _ ->
                            val s = (change.position.x / size.width).coerceIn(0f, 1f)
                            // Value: top is bright, bottom is black.
                            val v = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                            onSVChanged(s, v)
                        }
                    }
                    .pointerInput(hue) {
                        detectTapGestures { pos ->
                            val s = (pos.x / size.width).coerceIn(0f, 1f)
                            val v = 1f - (pos.y / size.height).coerceIn(0f, 1f)
                            onSVChanged(s, v)
                        }
                    },
            ) {
                // Horizontal: white → pure hue. Vertical: top transparent,
                // bottom black. Together these paint colour(x, y) =
                // hsv(hue, x/w, 1 - y/h), which is what the gesture handlers
                // above report back.
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.White, Color.hsv(hue, 1f, 1f)),
                    ),
                    size = Size(size.width, size.height),
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black),
                    ),
                    size = Size(size.width, size.height),
                )
                // SV thumb.
                val thumbX = saturation * size.width
                val thumbY = (1f - value) * size.height
                drawCircle(
                    color = Color.White,
                    radius = 8f,
                    center = Offset(thumbX, thumbY),
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.6f),
                    radius = 8f,
                    center = Offset(thumbX, thumbY),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                )
            }
        }
    }
}

@Composable
private fun AlphaSlider(
    hue: Float,
    saturation: Float,
    value: Float,
    alpha: Float,
    onAlphaChanged: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Alpha ${(alpha * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
        )
        // One control: the transparent → opaque gradient *is* the slider
        // track (the slider's own track is made transparent and the thumb
        // rides directly on the gradient). The old layout stacked a
        // decorative gradient strip above a plain slider, which read as two
        // disconnected rows.
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.hsv(hue, saturation, value, 0f),
                                Color.hsv(hue, saturation, value, 1f),
                            ),
                        ),
                    ),
            )
            Slider(
                value = alpha,
                onValueChange = onAlphaChanged,
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                ),
            )
        }
    }
}

@Composable
private fun HexRow(
    hex: String,
    error: Boolean,
    previewArgb: Int,
    initialArgb: Int,
    onHexChanged: (String) -> Unit,
    onRestoreInitial: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = hex,
            onValueChange = onHexChanged,
            label = { Text("Hex") },
            singleLine = true,
            isError = error,
            supportingText = {
                if (error) Text("Use #RRGGBB or #AARRGGBB")
            },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                capitalization = KeyboardCapitalization.Characters,
            ),
            modifier = Modifier.weight(1f),
        )
        // Old-vs-new comparison swatch: left half is the colour the sheet
        // opened with (tap to restore it), right half tracks the live pick —
        // a single preview circle gave nothing to compare against.
        Row(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .border(1.dp, Color.Black.copy(alpha = 0.25f), CircleShape)
                .clickable(onClick = onRestoreInitial),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(initialArgb)),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(previewArgb)),
            )
        }
    }
}

/** Mirror of [RecentColorsStore.MAX_ENTRIES] so the picker UI doesn't reach
 *  into the store from a composable. Kept in sync deliberately; if the cap
 *  changes, update both. */
private const val RecentColorsStoreLimit: Int = 12

// ── Colour math ─────────────────────────────────────────────────────────────

internal data class Hsv(val hue: Float, val saturation: Float, val value: Float)

/** ARGB int → HSV using the standard sRGB formula. Returned hue is in degrees `[0, 360)`. */
internal fun argbToHsv(argb: Int): Hsv {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val maxC = maxOf(r, g, b)
    val minC = minOf(r, g, b)
    val d = maxC - minC
    val s = if (maxC == 0f) 0f else d / maxC
    val h = when {
        d == 0f -> 0f
        maxC == r -> 60f * (((g - b) / d) % 6f)
        maxC == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    val hNorm = ((h % 360f) + 360f) % 360f
    return Hsv(hNorm, s.coerceIn(0f, 1f), maxC.coerceIn(0f, 1f))
}

internal fun hsvaToArgb(h: Float, s: Float, v: Float, alpha: Float): Int {
    val color = Color.hsv(
        hue = (h % 360f + 360f) % 360f,
        saturation = s.coerceIn(0f, 1f),
        value = v.coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f),
    )
    return color.toArgb()
}

/** Format an ARGB int as `#AARRGGBB`. */
internal fun formatHex(argb: Int): String =
    "#" + "%08X".format(argb)

/**
 * Parse `#RRGGBB` or `#AARRGGBB` (case-insensitive, leading `#` required).
 * Returns the ARGB int or `null` on any malformed input.
 */
internal fun parseHex(text: String): Int? {
    val raw = text.trim()
    if (!raw.startsWith("#")) return null
    val hex = raw.removePrefix("#")
    return when (hex.length) {
        6 -> runCatching {
            val rgb = hex.toLong(16).toInt()
            (0xFF shl 24) or (rgb and 0x00FFFFFF)
        }.getOrNull()
        8 -> runCatching { hex.toLong(16).toInt() }.getOrNull()
        else -> null
    }
}

/** Convert a screen-space offset to a hue angle `[0, 360)` around [center]. */
private fun angleOf(center: Offset, pos: Offset): Float {
    val dx = pos.x - center.x
    val dy = pos.y - center.y
    val rad = atan2(dy.toDouble(), dx.toDouble())
    val deg = Math.toDegrees(rad).toFloat()
    return ((deg % 360f) + 360f) % 360f
}

