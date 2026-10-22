package com.farewise.app.testing

import com.farewise.app.domain.model.PricePrediction
import com.farewise.app.domain.model.RideOption
import com.farewise.app.domain.model.RideProvider
import java.time.Duration
import java.time.Instant

/** Test builders with sensible defaults so each test only states what it cares about. */
object Fixtures {

    private val NOW: Instant = Instant.parse("2026-01-15T09:00:00Z")

    fun ride(
        id: String,
        priceInr: Double,
        provider: RideProvider = RideProvider.UBER,
        serviceTier: String = id,
        surgeMultiplier: Double = 1.0,
        etaMinutes: Long = 5,
        tripMinutes: Long = 20,
        distanceKm: Double = 8.0
    ) = RideOption(
        id = id,
        provider = provider,
        serviceTier = serviceTier,
        priceInr = priceInr,
        basePriceInr = priceInr / surgeMultiplier,
        surgeMultiplier = surgeMultiplier,
        etaToPickup = Duration.ofMinutes(etaMinutes),
        estimatedTripDuration = Duration.ofMinutes(tripMinutes),
        distanceKm = distanceKm,
        fetchedAt = NOW
    )

    fun prediction(
        provider: RideProvider,
        serviceTier: String,
        pointsInr: List<Double>,
        confidence: Float = 0.9f,
        stepMinutes: Long = 5
    ) = PricePrediction(
        provider = provider,
        serviceTier = serviceTier,
        generatedAt = NOW,
        stepDuration = Duration.ofMinutes(stepMinutes),
        pointsInr = pointsInr,
        confidence = confidence
    )
}
