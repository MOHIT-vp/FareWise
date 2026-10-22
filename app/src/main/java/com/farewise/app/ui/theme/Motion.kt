package com.farewise.app.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * FareWise motion tokens.
 *
 * The built-in Material easings are too soft to feel intentional, so every
 * animation in the app picks from these curves instead:
 *   • [EaseOut] — anything entering or responding to the user. Starts fast,
 *     so the UI reacts the instant it's touched.
 *   • [EaseInOut] — things moving from one place on screen to another.
 *   • [Drawer] — iOS-style sheet / full-screen curve.
 *
 * Durations stay under 300 ms for UI; exits are faster than entrances.
 */
object Motion {
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    val EaseInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)
    val Drawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    const val PRESS_MS = 120
    const val FAST_MS = 180
    const val STANDARD_MS = 260
    const val DRAWER_MS = 420

    fun <T> enter(durationMs: Int = STANDARD_MS, delayMs: Int = 0): FiniteAnimationSpec<T> =
        tween(durationMs, delayMs, EaseOut)

    fun <T> exit(durationMs: Int = FAST_MS): FiniteAnimationSpec<T> =
        tween(durationMs, easing = EaseOut)

    /** Critically-damped-ish spring for interruptible UI (tab thumb, reorder). */
    fun <T> snappy(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.86f, stiffness = 520f)

    /** A touch of bounce — reserved for rare, playful moments (swap button). */
    fun <T> playful(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
}

/**
 * Press feedback without a ripple: the element shrinks slightly while held
 * and springs back on release. Press-in is quick and eased; release is a
 * spring so a fast tap still visibly "lands".
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (pressed) {
            tween(Motion.PRESS_MS, easing = Motion.EaseOut)
        } else {
            spring(dampingRatio = 0.6f, stiffness = 600f)
        },
        label = "pressScale"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Tap target with scale-on-press instead of a Material ripple, plus a light
 * haptic tick so the press is felt as well as seen.
 */
fun Modifier.pressable(
    pressedScale: Float = 0.97f,
    haptic: HapticFeedbackType? = HapticFeedbackType.ContextClick,
    role: Role = Role.Button,
    onClick: () -> Unit
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    this
        .pressScale(interaction, pressedScale)
        .clickable(
            interactionSource = interaction,
            indication = null,
            role = role
        ) {
            haptic?.let(haptics::performHapticFeedback)
            onClick()
        }
}

/**
 * One-time entrance: fade in while rising 12 dp, delayed by [index] so a
 * list cascades in. Pass `enabled = false` for anything already seen, so
 * scrolling an item back into view doesn't replay it. The stagger is
 * capped so long lists never make the user wait.
 */
fun Modifier.staggeredReveal(enabled: Boolean, index: Int): Modifier = composed {
    val progress = remember { Animatable(if (enabled) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 340,
                    delayMillis = index.coerceAtMost(MAX_STAGGERED) * STAGGER_MS,
                    easing = Motion.EaseOut
                )
            )
        }
    }
    graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * 12.dp.toPx()
    }
}

private const val STAGGER_MS = 45
private const val MAX_STAGGERED = 6
