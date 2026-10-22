package com.farewise.app.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.farewise.app.domain.repository.BookingRepository
import com.farewise.app.domain.usecase.ObserveMonthlyInsightsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.YearMonth
import javax.inject.Inject

/**
 * Drives the trip-journal screen.
 *
 * The currently-selected [YearMonth] is held in a [MutableStateFlow]; the
 * insights + booking-list flows are re-subscribed (cancel-and-restart)
 * whenever it changes. This keeps Room queries scoped to one window at a
 * time so a year of bookings never streams into memory all at once.
 */
@HiltViewModel
class TripJournalViewModel @Inject constructor(
    private val observeMonthlyInsights: ObserveMonthlyInsightsUseCase,
    private val bookingRepository: BookingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TripJournalUiState())
    val uiState: StateFlow<TripJournalUiState> = _uiState.asStateFlow()

    private var subscription: Job? = null

    init {
        subscribe(_uiState.value.month)
    }

    fun onMonthSelected(month: YearMonth) {
        if (month == _uiState.value.month) return
        _uiState.value = _uiState.value.copy(month = month, isLoading = true)
        subscribe(month)
    }

    fun onPreviousMonth() = onMonthSelected(_uiState.value.month.minusMonths(1))
    fun onNextMonth() = onMonthSelected(_uiState.value.month.plusMonths(1))

    private fun subscribe(month: YearMonth) {
        subscription?.cancel()
        subscription = viewModelScope.launch {
            combine(
                observeMonthlyInsights(month),
                bookingRepository.observeBookingsForMonth(month)
            ) { insights, bookings ->
                _uiState.value.copy(
                    month = month,
                    insights = insights,
                    bookings = bookings,
                    isLoading = false
                )
            }.collect { _uiState.value = it }
        }.also { it.invokeOnCompletion { t ->
            if (t != null) Timber.w(t, "Journal subscription failed")
        } }
    }
}
