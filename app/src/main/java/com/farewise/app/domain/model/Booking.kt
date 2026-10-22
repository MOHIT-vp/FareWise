package com.farewise.app.domain.model

import java.time.Duration
import java.time.Instant

/**
 * One booking the rider initiated through the dashboard.
 *
 * Mirror of [com.farewise.app.data.local.entity.BookingEntity] in domain
 * terms — strongly-typed enums and `java.time` instead of strings/longs so
 * the use-case layer can reason about it without ever touching Room.
 *
 * The two counterfactual fields ([predictedAlternativeInr], [activeAdvice])
 * encode "what the model expected" at booking time. Comparing those to
 * [priceInr] is what powers the journal's "saved by following advice" line.
 */
data class Booking(
    val id: Long,
    val provider: RideProvider,
    val serviceTier: String,
    val priceInr: Double,
    val basePriceInr: Double,
    val surgeMultiplier: Double,
    val distanceKm: Double,
    val tripDuration: Duration,
    val originLabel: String?,
    val destinationLabel: String?,
    val bookedAt: Instant,
    val predictedAlternativeInr: Double?,
    val activeAdvice: RideAdvice.Kind?
) {
    /** Pure surge premium baked into the fare — sum across a month is "cost of impatience". */
    val surgePremiumInr: Double get() = (priceInr - basePriceInr).coerceAtLeast(0.0)

    /**
     * Positive value means the rider saved by following the advice (booked
     * just before a price climb), negative means they paid more than the
     * predicted alternative would have cost. Returns 0 when no advice was
     * active or no forecast was captured.
     *
     * Semantics:
     *   • BOOK_NOW + alt > paid → rider saved (advice was right and acted on).
     *   • WAIT_FOR_DROP + alt < paid → rider should have waited (booked anyway).
     *   • Anything else → 0 (no signal).
     */
    val savingsVsAdviceInr: Double
        get() {
            val alt = predictedAlternativeInr ?: return 0.0
            return when (activeAdvice) {
                RideAdvice.Kind.BOOK_NOW -> (alt - priceInr).coerceAtLeast(0.0)
                RideAdvice.Kind.WAIT_FOR_DROP -> -(priceInr - alt).coerceAtMost(0.0)
                else -> 0.0
            }
        }
}
