package com.farewise.app.ui.dashboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.farewise.app.ui.common.Place

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
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp
    ) {
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
            .clickable(onClick = onClick)
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
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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

@Composable
private fun SwapButton(onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 0.dp
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Filled.SwapVert,
                contentDescription = "Swap origin and destination",
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
