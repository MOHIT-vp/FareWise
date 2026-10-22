package com.farewise.app.ui.dashboard.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.farewise.app.domain.model.RideAdvice
import com.farewise.app.ui.theme.DropGreen
import com.farewise.app.ui.theme.Motion
import com.farewise.app.ui.theme.SurgeAmber
import com.farewise.app.ui.theme.SurgeRed
import com.farewise.app.ui.theme.pressable

/**
 * The Phase-4 advice banner — the only piece of chrome that translates the
 * raw TFLite forecast into a clear rider action.
 *
 * Styling is driven by [RideAdvice.Kind]:
 *   • WAIT_FOR_DROP → green (good-news tone, hourglass icon)
 *   • BOOK_NOW      → amber/red (urgency tone, bolt icon)
 *   • HOLD_STEADY   → never rendered (the VM sends null instead)
 *
 * The banner collapses cleanly when [advice] is null so the layout doesn't
 * jolt when the forecast drops below the engine's confidence threshold.
 */
@Composable
fun AdviceBanner(
    advice: RideAdvice?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = advice != null,
        // Grows open on the drawer curve; collapses faster than it opened.
        enter = fadeIn(Motion.enter(Motion.STANDARD_MS, delayMs = 60)) +
            expandVertically(tween(Motion.DRAWER_MS, easing = Motion.Drawer)),
        exit = fadeOut(Motion.exit(120)) +
            shrinkVertically(tween(Motion.FAST_MS + 40, easing = Motion.EaseOut)),
        modifier = modifier
    ) {
        advice?.let { BannerContent(it, onClick) }
    }
}

@Composable
private fun BannerContent(advice: RideAdvice, onClick: () -> Unit) {
    val palette = palette(advice.kind)

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.98f, onClick = onClick)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.horizontalGradient(listOf(palette.start, palette.end))
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (advice.kind) {
                        RideAdvice.Kind.WAIT_FOR_DROP -> Icons.Filled.HourglassTop
                        RideAdvice.Kind.BOOK_NOW -> Icons.Filled.Bolt
                        RideAdvice.Kind.HOLD_STEADY -> Icons.Filled.HourglassTop
                    },
                    contentDescription = null,
                    tint = Color.White
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = advice.headline,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Color.White
                )
                Text(
                    text = advice.subline,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.9f)
                )
                Spacer(Modifier.height(4.dp))
                ConfidencePill(confidence = advice.confidence)
            }
        }
    }
}

@Composable
private fun ConfidencePill(confidence: Float) {
    val pct = (confidence.coerceIn(0f, 1f) * 100).toInt()
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.22f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = "Model confidence $pct%",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White
        )
    }
}

private data class BannerPalette(val start: Color, val end: Color)

private fun palette(kind: RideAdvice.Kind): BannerPalette = when (kind) {
    RideAdvice.Kind.WAIT_FOR_DROP -> BannerPalette(DropGreen, Color(0xFF1F8E5E))
    RideAdvice.Kind.BOOK_NOW -> BannerPalette(SurgeAmber, SurgeRed)
    RideAdvice.Kind.HOLD_STEADY -> BannerPalette(SurgeAmber, SurgeAmber)
}
