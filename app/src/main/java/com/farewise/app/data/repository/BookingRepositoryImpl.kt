package com.farewise.app.data.repository

import com.farewise.app.data.local.dao.BookingDao
import com.farewise.app.data.local.entity.BookingEntity
import com.farewise.app.domain.model.Booking
import com.farewise.app.domain.model.RideAdvice
import com.farewise.app.domain.model.RideOption
import com.farewise.app.domain.model.RideProvider
import com.farewise.app.domain.repository.BookingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookingRepositoryImpl @Inject constructor(
    private val dao: BookingDao
) : BookingRepository {

    override suspend fun record(
        option: RideOption,
        originLabel: String?,
        destinationLabel: String?,
        predictedAlternativeInr: Double?,
        activeAdvice: RideAdvice.Kind?
    ) {
        dao.insert(
            BookingEntity(
                provider = option.provider.name,
                serviceTier = option.serviceTier,
                priceInr = option.priceInr,
                basePriceInr = option.basePriceInr,
                surgeMultiplier = option.surgeMultiplier,
                distanceKm = option.distanceKm,
                tripMinutes = option.estimatedTripDuration.toMinutes(),
                originLabel = originLabel,
                destinationLabel = destinationLabel,
                bookedAtMillis = Instant.now().toEpochMilli(),
                predictedAlternativeInr = predictedAlternativeInr,
                activeAdviceKind = activeAdvice?.name
            )
        )
    }

    override fun observeBookingsForMonth(month: YearMonth): Flow<List<Booking>> {
        // Use the system zone so the journal aligns with the rider's local
        // calendar. Stored timestamps are absolute epoch millis, so the
        // boundary conversion is the only zone-aware step.
        val zone = ZoneId.systemDefault()
        val startMillis = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return dao.observeBetween(startMillis, endMillis).map { rows -> rows.mapNotNull { it.toDomain() } }
    }

    override fun observeRecentBookings(limit: Int): Flow<List<Booking>> =
        dao.observeRecent(limit).map { rows -> rows.mapNotNull { it.toDomain() } }

    private fun BookingEntity.toDomain(): Booking? {
        val providerEnum = runCatching { RideProvider.valueOf(provider) }.getOrNull()
            ?: return null
        val advice = activeAdviceKind
            ?.let { kind -> runCatching { RideAdvice.Kind.valueOf(kind) }.getOrNull() }
        return Booking(
            id = id,
            provider = providerEnum,
            serviceTier = serviceTier,
            priceInr = priceInr,
            basePriceInr = basePriceInr,
            surgeMultiplier = surgeMultiplier,
            distanceKm = distanceKm,
            tripDuration = Duration.ofMinutes(tripMinutes),
            originLabel = originLabel,
            destinationLabel = destinationLabel,
            bookedAt = Instant.ofEpochMilli(bookedAtMillis),
            predictedAlternativeInr = predictedAlternativeInr,
            activeAdvice = advice
        )
    }
}
