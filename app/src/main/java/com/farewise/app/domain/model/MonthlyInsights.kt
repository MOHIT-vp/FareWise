package com.farewise.app.domain.model

import java.time.YearMonth

/**
 * Aggregated booking summary for a single calendar month.
 *
 * Every field is derivable from the underlying [Booking] list — this type
 * exists so the UI layer never has to compute these aggregates itself, and
 * so future tests can fix one set of bookings and assert the rolled-up
 * numbers against a golden expected snapshot.
 */
data class MonthlyInsights(
    val month: YearMonth,
    val tripCount: Int,
    val totalSpentInr: Double,
    val totalSurgePremiumInr: Double,
    /**
     * Net rupees the rider saved by following the dashboard's advice (BOOK_NOW
     * → prices climbed afterwards) minus what they lost by ignoring it
     * (WAIT_FOR_DROP → they booked anyway and prices fell). Negative means
     * the rider would have been better off ignoring the model.
     */
    val netAdviceSavingsInr: Double,
    val averageFareInr: Double,
    val byProvider: Map<RideProvider, ProviderTotal>,
    val mostExpensiveTrip: Booking?
) {
    data class ProviderTotal(
        val tripCount: Int,
        val totalInr: Double
    )

    companion object {
        fun empty(month: YearMonth): MonthlyInsights = MonthlyInsights(
            month = month,
            tripCount = 0,
            totalSpentInr = 0.0,
            totalSurgePremiumInr = 0.0,
            netAdviceSavingsInr = 0.0,
            averageFareInr = 0.0,
            byProvider = emptyMap(),
            mostExpensiveTrip = null
        )
    }
}
