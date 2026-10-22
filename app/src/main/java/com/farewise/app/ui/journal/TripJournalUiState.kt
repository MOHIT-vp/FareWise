package com.farewise.app.ui.journal

import com.farewise.app.domain.model.Booking
import com.farewise.app.domain.model.MonthlyInsights
import java.time.YearMonth

/**
 * Snapshot of the trip-journal screen.
 *
 * Holds both the rolled-up [insights] for the currently selected month and
 * the raw [bookings] list rendered as a feed below the cards. The two are
 * derived from the same DAO source but exposed independently so swapping
 * months only re-fetches what's needed.
 */
data class TripJournalUiState(
    val month: YearMonth = YearMonth.now(),
    val insights: MonthlyInsights = MonthlyInsights.empty(YearMonth.now()),
    val bookings: List<Booking> = emptyList(),
    val isLoading: Boolean = true
)
