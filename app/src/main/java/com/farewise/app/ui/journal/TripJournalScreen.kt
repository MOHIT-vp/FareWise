package com.farewise.app.ui.journal

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.farewise.app.domain.model.Booking
import com.farewise.app.domain.model.MonthlyInsights
import com.farewise.app.domain.model.RideAdvice
import com.farewise.app.domain.model.RideProvider
import com.farewise.app.ui.common.Formatters
import com.farewise.app.ui.theme.BrandIndigo
import com.farewise.app.ui.theme.BrandIndigoDark
import com.farewise.app.ui.theme.CardShape
import com.farewise.app.ui.theme.DropGreen
import com.farewise.app.ui.theme.Motion
import com.farewise.app.ui.theme.OlaYellow
import com.farewise.app.ui.theme.RapidoYellow
import com.farewise.app.ui.theme.SurgeRed
import com.farewise.app.ui.theme.TABULAR_NUMBERS
import com.farewise.app.ui.theme.UberBlack
import com.farewise.app.ui.theme.pressable
import com.farewise.app.ui.theme.staggeredReveal
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Trip-journal screen — monthly spend roll-up plus a feed of recent bookings.
 *
 * Layout:
 *   • App bar with month picker
 *   • Hero card: total spent + trip count
 *   • Stat row: surge premium paid + net advice savings
 *   • Provider breakdown chips
 *   • Most-expensive-trip callout
 *   • Per-booking feed
 */
@Composable
fun TripJournalScreen(
    viewModel: TripJournalViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // The top bar pads for the status bar; the host's bottom bar pads
        // for the system navigation bar.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            JournalTopBar(
                month = state.month,
                onPrev = viewModel::onPreviousMonth,
                onNext = viewModel::onNextMonth
            )
        }
    ) { padding ->
        JournalContent(
            state = state,
            contentPadding = padding
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun JournalTopBar(
    month: YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "My trips",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                // The month slides in from the side you navigated towards.
                AnimatedContent(
                    targetState = month,
                    transitionSpec = {
                        val forward = targetState > initialState
                        val dir = if (forward) 1 else -1
                        (slideInHorizontally(Motion.enter()) { w -> dir * w / 3 } +
                            fadeIn(Motion.enter())) togetherWith
                            (slideOutHorizontally(Motion.exit()) { w -> -dir * w / 3 } +
                                fadeOut(Motion.exit(120)))
                    },
                    label = "month"
                ) { shown ->
                    Text(
                        formatMonth(shown),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            MonthButton(Icons.Filled.ChevronLeft, "Previous month", onPrev)
            MonthButton(Icons.Filled.ChevronRight, "Next month", onNext)
        }
    }
}

@Composable
private fun MonthButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .pressable(pressedScale = 0.88f, haptic = HapticFeedbackType.SegmentTick, onClick = onClick)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun JournalContent(
    state: TripJournalUiState,
    contentPadding: PaddingValues
) {
    // Sections that already played their entrance (see staggeredReveal).
    val revealed = remember { HashSet<String>() }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "hero") {
            Box(Modifier.staggeredReveal(remember { revealed.add("hero") }, 0)) {
                SpendHeroCard(state.insights)
            }
        }
        item(key = "stats") {
            Box(Modifier.staggeredReveal(remember { revealed.add("stats") }, 1)) {
                StatsRow(state.insights)
            }
        }

        if (state.insights.byProvider.isNotEmpty()) {
            item(key = "providers") {
                Box(Modifier.animateItem().staggeredReveal(remember { revealed.add("providers") }, 2)) {
                    ProviderBreakdownCard(state.insights.byProvider)
                }
            }
        }

        state.insights.mostExpensiveTrip?.let { trip ->
            item(key = "priciest") {
                Box(Modifier.animateItem().staggeredReveal(remember { revealed.add("priciest") }, 3)) {
                    MostExpensiveCallout(trip)
                }
            }
        }

        if (state.bookings.isNotEmpty()) {
            item {
                Text(
                    "All trips this month",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(state.bookings, key = { it.id }) { booking ->
                Box(Modifier.animateItem()) { BookingRow(booking) }
            }
        } else if (!state.isLoading) {
            item(key = "empty") {
                Box(Modifier.animateItem().staggeredReveal(remember { revealed.add("empty") }, 2)) {
                    EmptyMonthCard()
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SpendHeroCard(insights: MonthlyInsights) {
    // Count up to the month's total — shown once per visit, so a little
    // delight is affordable here.
    val total = remember { Animatable(0f) }
    LaunchedEffect(insights.totalSpentInr) {
        total.animateTo(
            insights.totalSpentInr.toFloat(),
            tween(COUNT_UP_MS, easing = Motion.EaseOut)
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 18.dp,
                shape = CardShape,
                ambientColor = BrandIndigo.copy(alpha = 0.25f),
                spotColor = BrandIndigo.copy(alpha = 0.35f)
            )
            .clip(CardShape)
            .background(Brush.linearGradient(listOf(BrandIndigo, BrandIndigoDark, Color(0xFF2A1F8F))))
    ) {
        Column(Modifier.padding(22.dp)) {
            Text(
                "You spent",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f)
            )
            Text(
                Formatters.inr(total.value.toDouble()),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp,
                    fontFeatureSettings = TABULAR_NUMBERS
                ),
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "across ${insights.tripCount} ${if (insights.tripCount == 1) "trip" else "trips"} " +
                    if (insights.tripCount > 0) "(avg ${Formatters.inr(insights.averageFareInr)})" else "",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

private const val COUNT_UP_MS = 900

@Composable
private fun StatsRow(insights: MonthlyInsights) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        MetricTile(
            label = "Surge premium",
            value = Formatters.inr(insights.totalSurgePremiumInr),
            tone = SurgeRed,
            modifier = Modifier.weight(1f)
        )
        val savings = insights.netAdviceSavingsInr
        val savingsLabel = if (savings >= 0) "Saved by advice" else "Lost vs advice"
        val savingsColor = if (savings >= 0) DropGreen else SurgeRed
        MetricTile(
            label = savingsLabel,
            value = Formatters.inr(kotlin.math.abs(savings)),
            tone = savingsColor,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MetricTile(label: String, value: String, tone: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = tone.copy(alpha = 0.10f)
        ),
        modifier = modifier
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = tone
            )
        }
    }
}

@Composable
private fun ProviderBreakdownCard(byProvider: Map<RideProvider, MonthlyInsights.ProviderTotal>) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "By provider",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            byProvider.entries.sortedByDescending { it.value.totalInr }.forEach { (provider, total) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProviderDot(provider)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        provider.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${total.tripCount} × ${Formatters.inr(total.totalInr)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderDot(provider: RideProvider) {
    val color = when (provider) {
        RideProvider.UBER -> UberBlack
        RideProvider.OLA -> OlaYellow
        RideProvider.RAPIDO -> RapidoYellow
    }
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(RoundedCornerShape(50))
            .background(color)
    )
}

@Composable
private fun MostExpensiveCallout(trip: Booking) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Highest fare",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${trip.provider.displayName} ${trip.serviceTier} • ${Formatters.inr(trip.priceInr)}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            trip.destinationLabel?.let {
                Text(
                    "to $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BookingRow(booking: Booking) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProviderDot(booking.provider)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${booking.provider.displayName} ${booking.serviceTier}",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    booking.destinationLabel?.let { "to $it" } ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                booking.activeAdvice?.let { kind ->
                    AdviceTag(kind = kind, savings = booking.savingsVsAdviceInr)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Formatters.inr(booking.priceInr),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    Formatters.clock(booking.bookedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AdviceTag(kind: RideAdvice.Kind, savings: Double) {
    if (kind == RideAdvice.Kind.HOLD_STEADY) return
    val (label, color) = when {
        kind == RideAdvice.Kind.BOOK_NOW && savings > 0 ->
            "Followed BOOK NOW, saved ${Formatters.inr(savings)}" to DropGreen
        kind == RideAdvice.Kind.WAIT_FOR_DROP && savings < 0 ->
            "Ignored WAIT, paid ${Formatters.inr(-savings)} extra" to SurgeRed
        kind == RideAdvice.Kind.BOOK_NOW ->
            "On BOOK NOW advice" to MaterialTheme.colorScheme.onSurfaceVariant
        else ->
            "Despite WAIT advice" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Spacer(Modifier.height(4.dp))
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun EmptyMonthCard() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "No trips recorded yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Bookings you launch from the dashboard will appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatMonth(month: java.time.YearMonth): String {
    val monthName = month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    return "$monthName ${month.year}"
}
