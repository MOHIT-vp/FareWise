package com.farewise.app.domain.model

import java.time.Duration

/**
 * Two-leg journey: a public-transit segment followed by a short last-mile
 * ride from the alight stop to the rider's actual destination.
 *
 * This is the multimodal pitch baked into the project name "Estimated
 * Intermodal Savings" — instead of taking a single ride end-to-end, the
 * rider catches a Metro/bus for the bulk of the distance and a bike/auto
 * for the final 1–3 km. The combination beats the direct ride on fare
 * almost every time, at the cost of a few extra minutes.
 *
 * The use-case that produces these only emits a combo when:
 *   • the transit drop is between [MIN_LAST_MILE_KM] and [MAX_LAST_MILE_KM]
 *     from the rider's destination (otherwise transit alone wins or the
 *     ride alone is cheaper),
 *   • the combo's total fare is meaningfully cheaper than the cheapest
 *     direct ride,
 *   • the time penalty is bounded so we don't suggest a 90-minute saving
 *     of ₹50.
 */
data class MultimodalCombo(
    val id: String,
    val transit: TransitSchedule,
    val lastMile: LastMileLeg,
    val totalFareInr: Double,
    val totalDuration: Duration,
    val savingsVsDirectInr: Double,
    val timePenaltyVsDirect: Duration
) {
    /**
     * One bookable last-mile ride attached to a transit alight stop.
     *
     * Not a [RideOption] because it represents an *estimate* against a
     * synthetic origin (the transit stop) — the rider can't deep-link into
     * Uber for this leg without first arriving at the stop, so the data
     * model only carries what the UI needs to display the combo.
     */
    data class LastMileLeg(
        val provider: RideProvider,
        val tier: String,
        val priceInr: Double,
        val distanceKm: Double,
        val tripDuration: Duration
    )

    companion object {
        const val MIN_LAST_MILE_KM = 0.4
        const val MAX_LAST_MILE_KM = 5.0
    }
}
