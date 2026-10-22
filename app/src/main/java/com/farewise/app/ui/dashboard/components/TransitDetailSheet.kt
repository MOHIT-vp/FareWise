package com.farewise.app.ui.dashboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.farewise.app.domain.model.TransitSchedule
import com.farewise.app.ui.common.Formatters
import com.farewise.app.ui.theme.DropGreen
import kotlin.math.abs

/**
 * Bottom sheet opened from a [TransitScheduleCard]: the journey as a
 * step-by-step timeline, how it compares with the cheapest ride right now,
 * and a hand-off to Google Maps for live turn-by-turn transit directions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransitDetailSheet(
    schedule: TransitSchedule,
    cheapestRideInr: Double?,
    onOpenInMaps: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Header(schedule)

            cheapestRideInr?.let { SavingsCallout(schedule.fareInr, it) }

            Column {
                TimelineStep(
                    icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                    title = "Walk to the stop",
                    detail = Formatters.duration(schedule.walkingDurationToStop),
                    showConnector = true
                )
                TimelineStep(
                    icon = Icons.Filled.Schedule,
                    title = "Board at ${Formatters.clock(schedule.departsAt)}",
                    detail = "${schedule.mode.displayName} · ${schedule.routeName}",
                    showConnector = true
                )
                TimelineStep(
                    icon = Icons.Filled.Flag,
                    title = "Arrive at ${Formatters.clock(schedule.arrivesAt)}",
                    detail = "${schedule.numberOfStops} stops · " +
                        Formatters.duration(schedule.tripDuration) + " on board",
                    showConnector = false
                )
            }

            Button(
                onClick = onOpenInMaps,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.Map, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Directions in Google Maps")
            }
        }
    }
}

@Composable
private fun Header(schedule: TransitSchedule) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = schedule.routeName,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "towards ${schedule.headsign}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = Formatters.inr(schedule.fareInr),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun SavingsCallout(transitFareInr: Double, cheapestRideInr: Double) {
    val diff = cheapestRideInr - transitFareInr
    val (text, color) = if (diff > 0) {
        "${Formatters.inr(diff)} cheaper than the cheapest ride right now" to DropGreen
    } else {
        "${Formatters.inr(abs(diff))} more than the cheapest ride right now" to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = color,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun TimelineStep(
    icon: ImageVector,
    title: String,
    detail: String,
    showConnector: Boolean
) {
    Row {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (showConnector) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(22.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.padding(top = 6.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
