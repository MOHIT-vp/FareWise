package com.farewise.app.ui.dashboard.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.farewise.app.domain.model.RideRankingCategory

/**
 * Segmented pill-control used to flip the ride list between ranking modes.
 *
 * Implemented as three equally-weighted clickable rows inside a pill-shaped
 * Surface. Compose's built-in [androidx.compose.material3.TabRow] would work
 * too but this keeps the dashboard visually self-contained and gives us the
 * colored selection indicator we want.
 */
@Composable
fun RankingTabs(
    selected: RideRankingCategory,
    onSelected: (RideRankingCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RideRankingCategory.entries.forEach { category ->
            TabPill(
                label = category.label,
                isSelected = category == selected,
                onClick = { onSelected(category) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TabPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        label = "tabBg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "tabFg"
    )
    Text(
        text = label,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .background(background)
            .padding(vertical = 10.dp),
        textAlign = TextAlign.Center,
        color = contentColor,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    )
}
