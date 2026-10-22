package com.farewise.app.ui.dashboard.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.farewise.app.domain.model.RideRankingCategory
import com.farewise.app.ui.theme.Motion

/**
 * iOS-style segmented control for the ride ranking modes.
 *
 * A single raised "thumb" slides between segments on a spring rather than
 * each segment repainting its own background. Springs keep their velocity
 * when interrupted, so rapid taps (Cheapest → Fastest → Recommended) glide
 * continuously instead of restarting. Each change ticks the haptic motor.
 */
@Composable
fun RankingTabs(
    selected: RideRankingCategory,
    onSelected: (RideRankingCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    val categories = RideRankingCategory.entries
    val selectedIndex = categories.indexOf(selected)
    val haptics = LocalHapticFeedback.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val thumbColor = if (isDark) Color(0xFF3A3C52) else Color.White

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp)
    ) {
        val segmentWidth = maxWidth / categories.size
        val thumbOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = Motion.snappy(),
            label = "thumbOffset"
        )

        Box(
            Modifier
                .offset(x = thumbOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .shadow(
                    elevation = 3.dp,
                    shape = RoundedCornerShape(9.dp),
                    ambientColor = Color.Black.copy(alpha = 0.08f),
                    spotColor = Color.Black.copy(alpha = 0.12f)
                )
                .background(thumbColor, RoundedCornerShape(9.dp))
        )

        Row(Modifier.fillMaxWidth().selectableGroup()) {
            categories.forEachIndexed { index, category ->
                val isSelected = index == selectedIndex
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(Motion.FAST_MS, easing = Motion.EaseOut),
                    label = "segmentText"
                )
                Box(
                    modifier = Modifier
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .selectable(
                            selected = isSelected,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab
                        ) {
                            if (!isSelected) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onSelected(category)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = category.label,
                        textAlign = TextAlign.Center,
                        color = textColor,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                        )
                    )
                }
            }
        }
    }
}
