package com.farewise.app.ui.dashboard.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.farewise.app.ui.common.Place
import com.farewise.app.ui.theme.Motion
import com.farewise.app.ui.theme.pressable
import com.farewise.app.ui.theme.softCard

/**
 * Pill-shaped search bar with two rows — source and destination — and a
 * swap button on the right. Tapping either row surfaces the location picker
 * sheet; the ViewModel owns the actual selection.
 *
 * Laid out as a single Surface with two clickable rows so the visual
 * connector between them reads as "one trip" rather than "two fields".
 */
@Composable
fun LocationSearchBar(
    originLabel: String,
    destinationLabel: String,
    onOriginClick: () -> Unit,
    onDestinationClick: () -> Unit,
    onSwapClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth().softCard()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                LocationRow(
                    icon = Icons.Filled.LocationOn,
                    iconTint = MaterialTheme.colorScheme.primary,
                    label = "From",
                    value = originLabel,
                    onClick = onOriginClick
                )
                Connector()
                LocationRow(
                    icon = Icons.Filled.Flag,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    label = "To",
                    value = destinationLabel,
                    onClick = onDestinationClick
                )
            }
            Spacer(Modifier.width(8.dp))
            SwapButton(onClick = onSwapClick)
        }
    }
}

@Composable
private fun LocationRow(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.98f, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Crossfade with a slight scale when the place changes (pick or swap).
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    (fadeIn(Motion.enter()) + scaleIn(Motion.enter(), initialScale = 0.96f)) togetherWith
                        fadeOut(Motion.exit(120))
                },
                contentAlignment = Alignment.CenterStart,
                label = "placeLabel"
            ) { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Thin vertical connector between the two rows, aligned under the icons. */
@Composable
private fun Connector() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier
                .width(2.dp)
                .height(14.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

/**
 * Each tap spins the arrows half a turn with a little overshoot — the icon
 * itself shows that the two ends just traded places.
 */
@Composable
private fun SwapButton(onClick: () -> Unit) {
    var turns by remember { mutableIntStateOf(0) }
    val rotation by animateFloatAsState(
        targetValue = turns * 180f,
        animationSpec = Motion.playful(),
        label = "swapRotation"
    )
    Box(
        modifier = Modifier
            .size(42.dp)
            .pressable(pressedScale = 0.9f) {
                turns++
                onClick()
            }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.SwapVert,
            contentDescription = "Swap origin and destination",
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.graphicsLayer { rotationZ = rotation }
        )
    }
}
