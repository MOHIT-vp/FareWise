package com.farewise.app.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import com.farewise.app.ui.theme.DropGreen
import com.farewise.app.ui.theme.Motion
import com.farewise.app.ui.theme.SurgeRed
import com.farewise.app.ui.theme.TABULAR_NUMBERS
import kotlinx.coroutines.delay

/**
 * A rupee amount that rolls like an odometer when it changes.
 *
 * Only the digits that actually changed animate. They slide up when the
 * price rises and down when it drops, and the number briefly tints red or
 * green, so the rider sees *which way* the fare moved without reading it
 * twice. Digits are keyed from the right so "₹99 → ₹101" lines up the ones
 * column instead of shifting every character.
 */
@Composable
fun RollingPrice(
    amountInr: Double,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val text = Formatters.inr(amountInr)

    // Plain (non-snapshot) holder: updating it mid-composition must not
    // trigger another recomposition.
    val trend = remember { Trend(amountInr) }
    trend.update(amountInr)
    val direction = trend.direction

    // Brief tint after a change, then settle back to the normal colour.
    var tint by remember { mutableIntStateOf(0) }
    LaunchedEffect(text) {
        if (direction == 0) return@LaunchedEffect
        tint = direction
        delay(TINT_HOLD_MS)
        tint = 0
    }
    val animatedColor by animateColorAsState(
        targetValue = when (tint) {
            1 -> SurgeRed
            -1 -> DropGreen
            else -> color
        },
        animationSpec = tween(Motion.STANDARD_MS, easing = Motion.EaseOut),
        label = "priceTint"
    )

    val tabular = style.copy(fontFeatureSettings = TABULAR_NUMBERS)
    Row(modifier.clearAndSetSemantics { contentDescription = text }) {
        text.forEachIndexed { index, char ->
            key(text.length - index) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        val dir = if (direction >= 0) 1 else -1
                        (slideInVertically(Motion.enter()) { h -> dir * h } +
                            fadeIn(Motion.enter())) togetherWith
                            (slideOutVertically(Motion.exit()) { h -> -dir * h } +
                                fadeOut(Motion.exit())) using
                            SizeTransform(clip = true)
                    },
                    label = "digit"
                ) { digit ->
                    Text(text = digit.toString(), style = tabular, color = animatedColor)
                }
            }
        }
    }
}

private class Trend(var previous: Double) {
    /** +1 after a rise, -1 after a drop, 0 before the first change. */
    var direction = 0
        private set

    fun update(amount: Double) {
        if (amount == previous) return
        direction = if (amount > previous) 1 else -1
        previous = amount
    }
}

private const val TINT_HOLD_MS = 900L
