package com.farewise.app.ui.dashboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsSubway
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.farewise.app.domain.model.MultimodalCombo
import com.farewise.app.domain.model.TransitMode
import com.farewise.app.ui.common.Formatters
import com.farewise.app.ui.theme.DropGreen
import com.farewise.app.ui.theme.RapidoYellow

/**
 * "Combo" card — transit + last-mile ride pair, presented as one bookable
 * journey with prominent total fare and savings vs the cheapest direct ride.
 *
 * Layout intentionally diverges from [RideOptionCard] so the rider's eye
 * registers it as a different *category* of option, not just another row:
 *
 *   ┌──────────────────────────────────────────────┐
 *   │ [SAVE ₹X] Combo deal                         │
 *   │ ╭─ Metro Purple Line  ──→  Bike (Rapido) ─╮  │
 *   │ │ ₹40 · 8 stops                ₹35 · 1.4 km│  │
 *   │ ╰────────────────────────────────────────╯   │
 *   │ Total ₹75  ·  42 min (+8 min)                │
 *   └──────────────────────────────────────────────┘
 */
@Composable
fun MultimodalComboCard(
    combo: MultimodalCombo,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = DropGreen.copy(alpha = 0.45f),
                shape = RoundedCornerShape(18.dp)
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            SavingsHeader(combo.savingsVsDirectInr)
            Spacer(Modifier.height(10.dp))
            LegRow(combo)
            Spacer(Modifier.height(10.dp))
            TotalsFooter(combo)
        }
    }
}

@Composable
private fun SavingsHeader(savings: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = DropGreen.copy(alpha = 0.18f),
            shape = RoundedCornerShape(50)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Savings,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = DropGreen
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Save ${Formatters.inr(savings)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = DropGreen
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "Combo deal",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun LegRow(combo: MultimodalCombo) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TransitLegBlock(combo)
        Spacer(Modifier.width(8.dp))
        Text(
            "→",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        LastMileBlock(combo)
    }
}

@Composable
private fun TransitLegBlock(combo: MultimodalCombo) {
    val transit = combo.transit
    Column(modifier = Modifier.padding(end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModeBadge(mode = transit.mode)
            Spacer(Modifier.width(6.dp))
            Text(
                transit.routeName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            "${Formatters.inr(transit.fareInr)} · ${transit.numberOfStops} stops",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LastMileBlock(combo: MultimodalCombo) {
    val leg = combo.lastMile
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(RapidoYellow),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.TwoWheeler,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                "${leg.provider.displayName} ${leg.tier}",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            "${Formatters.inr(leg.priceInr)} · ${"%.1f km".format(leg.distanceKm)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TotalsFooter(combo: MultimodalCombo) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Total ${Formatters.inr(combo.totalFareInr)}",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.Filled.AccessTime,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        val penaltyMin = combo.timePenaltyVsDirect.toMinutes()
        val penaltyText = if (penaltyMin <= 0) "no time penalty"
        else "+$penaltyMin min vs direct"
        Text(
            "${Formatters.duration(combo.totalDuration)} · $penaltyText",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ModeBadge(mode: TransitMode) {
    val icon: ImageVector = when (mode) {
        TransitMode.METRO -> Icons.Filled.DirectionsSubway
        TransitMode.BUS -> Icons.Filled.DirectionsBus
        TransitMode.SUBURBAN_RAIL -> Icons.Filled.Train
    }
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = mode.displayName,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
    }
}
