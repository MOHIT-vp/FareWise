package com.farewise.app.domain.usecase

import com.farewise.app.domain.model.LatLng
import com.farewise.app.domain.model.MultimodalCombo
import com.farewise.app.domain.model.RideOption
import com.farewise.app.domain.model.TransitSchedule
import com.farewise.app.domain.repository.LastMileEstimator
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Pair every transit option with a last-mile ride from the alight stop to
 * the rider's destination, and emit the combos that materially beat
 * booking a direct ride.
 *
 * The pitch this implements is the project's namesake — "Estimated
 * Intermodal Savings". Take the Metro for the long part, jump on a Rapido
 * Bike for the last 1.5 km, save 60% on fare for a 6-minute time penalty.
 *
 * Filtering rules (deliberately conservative — we only show combos that
 * are obviously worth it, otherwise we train the rider to ignore them):
 *
 *   • Last-mile distance must sit in [MIN_LAST_MILE_KM, MAX_LAST_MILE_KM].
 *     Closer than the lower bound: walk; further: transit alone is bad.
 *   • Combo fare must be ≤ [MAX_FARE_RATIO_VS_DIRECT] of cheapest direct.
 *   • Time penalty must be ≤ [MAX_TIME_PENALTY] vs the fastest direct ride.
 *
 * For each surviving combo we attach the *cheapest* viable last-mile leg.
 * One combo per transit option keeps the dashboard scannable.
 */
class BuildMultimodalCombosUseCase @Inject constructor(
    private val lastMileEstimator: LastMileEstimator
) {
    suspend operator fun invoke(
        destination: LatLng,
        transits: List<TransitSchedule>,
        directRides: List<RideOption>
    ): List<MultimodalCombo> {
        if (transits.isEmpty() || directRides.isEmpty()) return emptyList()

        val cheapestDirectFare = directRides.minOf { it.priceInr }
        val fastestDirect = directRides.minOf { it.totalTimeToArrive }

        val now = Instant.now()
        val combos = mutableListOf<MultimodalCombo>()

        for (transit in transits) {
            val arrival = transit.arrivalStopLocation ?: continue
            val lastMileKm = approxDistanceKm(arrival, destination)
            if (lastMileKm < MultimodalCombo.MIN_LAST_MILE_KM ||
                lastMileKm > MultimodalCombo.MAX_LAST_MILE_KM
            ) continue

            val candidates = lastMileEstimator.estimate(arrival, destination)
            val cheapestLeg = candidates.minByOrNull { it.priceInr } ?: continue

            val totalFare = transit.fareInr + cheapestLeg.priceInr
            if (totalFare > cheapestDirectFare * MAX_FARE_RATIO_VS_DIRECT) continue

            // Total wall-clock = walk-to-stop + (depart→arrive) + last-mile.
            // We approximate the wait between transit arrival and the
            // rider hailing the last-mile ride as zero — most rideshare
            // apps surface a vehicle in under a minute at a metro stop.
            val totalDuration = transit.walkingDurationToStop
                .plus(transit.tripDuration)
                .plus(cheapestLeg.tripDuration)

            val timePenalty = totalDuration.minus(fastestDirect)
            if (timePenalty > MAX_TIME_PENALTY) continue

            combos += MultimodalCombo(
                id = "combo-${transit.id}-${cheapestLeg.tier}-${now.toEpochMilli()}",
                transit = transit,
                lastMile = cheapestLeg,
                totalFareInr = totalFare,
                totalDuration = totalDuration,
                savingsVsDirectInr = (cheapestDirectFare - totalFare).coerceAtLeast(0.0),
                timePenaltyVsDirect = if (timePenalty.isNegative) Duration.ZERO else timePenalty
            )
        }
        // Best deals first (largest savings), then by lowest time penalty.
        return combos.sortedWith(
            compareByDescending<MultimodalCombo> { it.savingsVsDirectInr }
                .thenBy { it.timePenaltyVsDirect }
        )
    }

    private fun approxDistanceKm(a: LatLng, b: LatLng): Double {
        val latKm = 111.0
        val lonKm = 111.0 * cos(Math.toRadians((a.latitude + b.latitude) / 2.0))
        return hypot(
            (a.latitude - b.latitude) * latKm,
            (a.longitude - b.longitude) * lonKm
        )
    }

    companion object {
        /**
         * Combo fare must be at most this fraction of the cheapest direct
         * ride to surface. 0.85 means "at least 15% cheaper than just
         * booking a cab".
         */
        private const val MAX_FARE_RATIO_VS_DIRECT = 0.85

        /**
         * Hard cap on extra wall-clock time vs the fastest direct ride.
         * Beyond 25 min the savings stop being psychologically worth it
         * for most riders.
         */
        private val MAX_TIME_PENALTY: Duration = Duration.ofMinutes(25)
    }
}
