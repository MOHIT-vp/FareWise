package com.farewise.app.domain.usecase

import com.farewise.app.domain.model.Booking
import com.farewise.app.domain.model.MonthlyInsights
import com.farewise.app.domain.repository.BookingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.YearMonth
import javax.inject.Inject

/**
 * Roll a month of [Booking] rows into a single [MonthlyInsights] aggregate
 * the journal screen renders directly.
 *
 * Pure function over the booking list — kept as a use-case so the screen
 * never sees the raw repository contract and so the aggregation can be
 * unit-tested independently of Room.
 */
class ObserveMonthlyInsightsUseCase @Inject constructor(
    private val bookings: BookingRepository
) {
    operator fun invoke(month: YearMonth): Flow<MonthlyInsights> =
        bookings.observeBookingsForMonth(month).map { rows -> aggregate(month, rows) }

    private fun aggregate(month: YearMonth, rows: List<Booking>): MonthlyInsights {
        if (rows.isEmpty()) return MonthlyInsights.empty(month)

        val totalSpent = rows.sumOf { it.priceInr }
        val totalSurge = rows.sumOf { it.surgePremiumInr }
        val netAdvice = rows.sumOf { it.savingsVsAdviceInr }
        val byProvider = rows.groupBy { it.provider }
            .mapValues { (_, list) ->
                MonthlyInsights.ProviderTotal(
                    tripCount = list.size,
                    totalInr = list.sumOf { it.priceInr }
                )
            }
        val mostExpensive = rows.maxByOrNull { it.priceInr }

        return MonthlyInsights(
            month = month,
            tripCount = rows.size,
            totalSpentInr = totalSpent,
            totalSurgePremiumInr = totalSurge,
            netAdviceSavingsInr = netAdvice,
            averageFareInr = totalSpent / rows.size,
            byProvider = byProvider,
            mostExpensiveTrip = mostExpensive
        )
    }
}
