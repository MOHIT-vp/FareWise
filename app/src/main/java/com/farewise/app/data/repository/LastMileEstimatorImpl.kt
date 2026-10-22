package com.farewise.app.data.repository

import com.farewise.app.domain.model.LatLng
import com.farewise.app.domain.model.MultimodalCombo
import com.farewise.app.domain.model.RideProvider
import com.farewise.app.domain.repository.LastMileEstimator
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Catalog-based last-mile estimator.
 *
 * Uses the same flagfall + per_km + per_min pricing skeleton as the live
 * mock so the numbers feel consistent with the ride cards above. We
 * intentionally do *not* hit Routes API for these short hops — the cost
 * (one API call per transit alight per tick) is unjustified for an
 * estimation that the rider can't book directly anyway.
 *
 * Speed assumptions are tuned for last-mile Bengaluru traffic, where bikes
 * lane-split and autos crawl: bike at 22 kmph, auto at 18 kmph. Distance is
 * a flat-earth approximation of haversine — accurate to ~0.1 % for the
 * sub-5-km hops this estimator handles.
 */
@Singleton
class LastMileEstimatorImpl @Inject constructor() : LastMileEstimator {

    override suspend fun estimate(
        from: LatLng,
        to: LatLng
    ): List<MultimodalCombo.LastMileLeg> {
        val km = approxDistanceKm(from, to)
        if (km <= 0.05) return emptyList()
        return CANDIDATES.map { tier ->
            val tripMin = (km / tier.cruiseKmph * 60.0).coerceAtLeast(3.0)
            val price = tier.flagfall + tier.perKm * km + tier.perMin * tripMin
            MultimodalCombo.LastMileLeg(
                provider = tier.provider,
                tier = tier.label,
                priceInr = price.roundToInt().toDouble(),
                distanceKm = roundTo(km, 2),
                tripDuration = Duration.ofMinutes(tripMin.toLong())
            )
        }
    }

    private fun roundTo(v: Double, decimals: Int): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return (v * factor).roundToInt() / factor
    }

    private fun approxDistanceKm(a: LatLng, b: LatLng): Double {
        val latKm = 111.0
        val lonKm = 111.0 * cos(Math.toRadians((a.latitude + b.latitude) / 2.0))
        return hypot(
            (a.latitude - b.latitude) * latKm,
            (a.longitude - b.longitude) * lonKm
        )
    }

    private data class CandidateTier(
        val provider: RideProvider,
        val label: String,
        val flagfall: Double,
        val perKm: Double,
        val perMin: Double,
        val cruiseKmph: Double
    )

    companion object {
        // Two realistic last-mile options. Cab tiers are excluded because
        // booking a sedan for a 1.5-km hop is an outlier — bike/auto cover
        // 95% of real last-mile demand in the city.
        private val CANDIDATES = listOf(
            CandidateTier(RideProvider.RAPIDO, "Bike", 25.0, 6.5, 0.8, 22.0),
            CandidateTier(RideProvider.RAPIDO, "Auto", 30.0, 11.0, 1.0, 18.0)
        )
    }
}
