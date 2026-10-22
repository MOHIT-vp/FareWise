package com.farewise.app.domain.model

import java.time.Duration
import java.time.Instant

/**
 * One bookable ride quote returned by a [RideProvider].
 *
 * A "service tier" captures the vehicle class (e.g. UberGo, Ola Prime,
 * Rapido Bike) — each provider exposes several tiers with distinct fares
 * and ETAs. We keep both the surged [priceInr] and the [basePriceInr] so
 * the decision engine in Phase 4 can reason about how much of the current
 * quote is pure surge, and therefore how much could evaporate if the rider
 * waits for predicted prices to drop.
 */
data class RideOption(
    val id: String,
    val provider: RideProvider,
    val serviceTier: String,
    val priceInr: Double,
    val basePriceInr: Double,
    val surgeMultiplier: Double,
    val etaToPickup: Duration,
    val estimatedTripDuration: Duration,
    val distanceKm: Double,
    val fetchedAt: Instant,
    /**
     * Optional ML fare estimate that lets the UI render a confidence band
     * around the point [priceInr]. Null when the TFLite estimator is
     * disabled (assets missing) — the UI degrades to a single number.
     */
    val fareEstimate: FareEstimate? = null
) {
    /** True when the current quote is meaningfully above the unsurged base. */
    val isSurging: Boolean get() = surgeMultiplier > 1.05

    /** Simple per-km rate — used by the "Cheapest" ranking. */
    val costPerKm: Double get() = if (distanceKm > 0) priceInr / distanceKm else 0.0

    /** Total time-to-arrival if the rider books immediately right now. */
    val totalTimeToArrive: Duration get() = etaToPickup.plus(estimatedTripDuration)
}
