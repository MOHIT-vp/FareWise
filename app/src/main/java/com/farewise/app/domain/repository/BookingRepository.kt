package com.farewise.app.domain.repository

import com.farewise.app.domain.model.Booking
import com.farewise.app.domain.model.RideAdvice
import com.farewise.app.domain.model.RideOption
import kotlinx.coroutines.flow.Flow
import java.time.YearMonth

/**
 * Append-only journal of bookings the rider committed to via the deep-linker.
 *
 * The repository handles the conversion between [RideOption] (transient
 * dashboard tick) and persistent [Booking] rows so callers can stay
 * model-typed end to end. Counterfactual fields (predicted alternative price,
 * active advice) are passed in at record time because the booking moment is
 * the only one where both signals are available together.
 */
interface BookingRepository {

    /**
     * Persist one booking. The rider pressed an "Open in Uber/Ola/Rapido"
     * card; the deep-linker fired, and we treat that as commitment to ride.
     */
    suspend fun record(
        option: RideOption,
        originLabel: String?,
        destinationLabel: String?,
        predictedAlternativeInr: Double?,
        activeAdvice: RideAdvice.Kind?
    )

    /** Bookings inside [month], newest first. Reactive — re-emits on insert. */
    fun observeBookingsForMonth(month: YearMonth): Flow<List<Booking>>

    /** Most-recent N bookings across all time. */
    fun observeRecentBookings(limit: Int = 200): Flow<List<Booking>>
}
