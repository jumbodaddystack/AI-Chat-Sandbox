package com.aichat.sandbox.ui.util

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Thin, semantic wrapper over [View.performHapticFeedback]. The app's `minSdk`
 * is 36, so every constant referenced here is always available — there are no
 * version guards to maintain.
 *
 * Compose's own `LocalHapticFeedback` only exposes `LongPress` /
 * `TextHandleMove` on this Compose version, which is too coarse for the
 * confirm/reject/tick distinctions the UI wants. Routing through the platform
 * view gives us the richer palette while staying a single, testable seam.
 *
 * Each method names the *moment* it marks rather than the constant it uses, so
 * call sites read as intent ("a primary action just fired", "something was
 * deleted") and the physical feel can be retuned in one place.
 */
class Haptics(private val view: View) {
    /** A light tap confirming a primary action fired — e.g. sending a message. */
    fun light() {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** A discrete selection change — e.g. switching bottom-nav tabs. */
    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** A context affordance opening — e.g. a long-press revealing a menu/dialog. */
    fun longPress() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** A positive confirmation — e.g. an operation completed successfully. */
    fun confirm() {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    /** A destructive / negative confirmation — e.g. deleting an item. */
    fun reject() {
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }
}

/** Remembers a [Haptics] bound to the current Compose host view. */
@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
