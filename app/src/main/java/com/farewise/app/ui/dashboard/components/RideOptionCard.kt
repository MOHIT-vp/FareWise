package com.farewise.app.ui.dashboard.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.farewise.app.domain.model.RideOption
import com.farewise.app.domain.model.RideProvider
import com.farewise.app.ui.common.Formatters
import com.farewise.app.ui.common.RollingPrice
import com.farewise.app.ui.theme.CardShape
import com.farewise.app.ui.theme.DropGreen
import com.farewise.app.ui.theme.Motion
import com.farewise.app.ui.theme.OlaYellow
import com.farewise.app.ui.theme.RapidoYellow
import com.farewise.app.ui.theme.SurgeAmber
import com.farewise.app.ui.theme.SurgeRed
import com.farewise.app.ui.theme.UberBlack
import com.farewise.app.ui.theme.pressable
import com.farewise.app.ui.theme.softCard

/**
 * A single bookable ride quote, rendered as a card in the dashboard list.
 *
 * Layout:
 *   [provider chip] | tier + ETA + distance    | price + surge badge
 *
 * The top card in the list is drawn in "hero" form with a thicker outline
 * in the brand primary colour to make the recommended pick obvious.
 */
@Composable
fun RideOptionCard(
    option: RideOption,
    isHero: Boolean,
    isProviderInstalled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Recent observed prices for this (provider, tier), oldest first. When
     * non-empty (≥ 2 points) a sparkline renders under the meta chips so the
     * rider can see the trend behind the surge banner.
     */
    priceHistory: List<Double> = emptyList(),
    /** Draw the sparkline in on first appearance (not on every re-entry). */
    animateSparkline: Boolean = false
) {
    val heroBorder by animateColorAsState(
        targetValue = if (isHero) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(Motion.STANDARD_MS, easing = Motion.EaseOut),
        label = "heroBorder"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .softCard()
            .border(1.5.dp, heroBorder, CardShape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProviderAvatar(provider = option.provider, tier = option.serviceTier)

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${option.provider.displayName} ${option.serviceTier}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    AnimatedVisibility(
                        visible = isHero,
                        enter = fadeIn(Motion.enter()) +
                            scaleIn(Motion.enter(), initialScale = 0.9f),
                        exit = fadeOut(Motion.exit()) +
                            scaleOut(Motion.exit(), targetScale = 0.9f)
                    ) {
                        HeroTag(Modifier.padding(start = 6.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MetaChip(
                        icon = Icons.Filled.AccessTime,
                        text = "${Formatters.duration(option.etaToPickup)} away"
                    )
                    Spacer(Modifier.width(10.dp))
                    MetaChip(
                        icon = Icons.Filled.Route,
                        text = Formatters.distance(option.distanceKm)
                    )
                }
                if (priceHistory.size >= 2) {
                    Spacer(Modifier.height(6.dp))
                    PriceSparkline(
                        prices = priceHistory,
                        animateIn = animateSparkline,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(6.dp))
                CtaChip(
                    provider = option.provider,
                    isInstalled = isProviderInstalled
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(horizontalAlignment = Alignment.End) {
                RollingPrice(
                    amountInr = option.priceInr,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                option.fareEstimate?.takeIf { it.hasMeaningfulBand }?.let { est ->
                    Text(
                        text = "±${Formatters.inr(est.halfBandInr)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (option.isSurging) {
                    Text(
                        text = Formatters.inr(option.basePriceInr),
                        style = MaterialTheme.typography.labelMedium.copy(
                            textDecoration = TextDecoration.LineThrough
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                SurgeBadge(surgeMultiplier = option.surgeMultiplier)
            }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun ProviderAvatar(provider: RideProvider, tier: String) {
    val (bg, fg) = when (provider) {
        RideProvider.UBER -> UberBlack to Color.White
        RideProvider.OLA -> OlaYellow to Color.Black
        RideProvider.RAPIDO -> RapidoYellow to Color.Black
    }
    val icon: ImageVector = when {
        tier.contains("bike", ignoreCase = true) -> Icons.Filled.TwoWheeler
        else -> Icons.Filled.DirectionsCar
    }
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "${provider.displayName} $tier",
            tint = fg,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun MetaChip(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HeroTag(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = "Top Pick",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/**
 * "Open in Uber" / "Install Uber" pill.
 *
 * The install path is visually softer (outlined, muted tint) so it doesn't
 * pull attention from the Top Pick hero card when the user hasn't installed
 * that partner yet — the rider can still tap and get sent to the Play Store.
 */
@Composable
private fun CtaChip(provider: RideProvider, isInstalled: Boolean) {
    val label = if (isInstalled) {
        "Open in ${provider.displayName}"
    } else {
        "Install ${provider.displayName}"
    }
    val icon = if (isInstalled) Icons.AutoMirrored.Filled.OpenInNew else Icons.Filled.GetApp
    val container = if (isInstalled) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (isInstalled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = container
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = content
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = content
            )
        }
    }
}

/**
 * Compact surge indicator rendered in every card.
 *
 * Colour scale:
 *   • ≤ 1.05x: green "no surge" pill
 *   • ≤ 1.50x: amber "mild surge" pill
 *   • > 1.50x: red "high surge" pill
 */
@Composable
fun SurgeBadge(surgeMultiplier: Double, modifier: Modifier = Modifier) {
    val (bg, label) = when {
        surgeMultiplier <= 1.05 -> DropGreen to "No surge"
        surgeMultiplier <= 1.50 -> SurgeAmber to "%.1fx surge".format(surgeMultiplier)
        else -> SurgeRed to "%.1fx surge".format(surgeMultiplier)
    }
    Surface(
        modifier = modifier,
        color = bg.copy(alpha = 0.15f),
        shape = RoundedCornerShape(50)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Bolt,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = bg
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = bg
            )
        }
    }
}
