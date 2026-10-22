package com.farewise.app.data.repository

import com.farewise.app.domain.model.LatLng
import com.farewise.app.domain.model.RideOption
import com.farewise.app.domain.model.RideProvider
import com.farewise.app.domain.model.RoutedRideQuotes
import com.farewise.app.domain.repository.FareEstimator
import com.farewise.app.domain.repository.RideDataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Mock implementation of [RideDataRepository] that simulates live polling
 * from Uber / Ola / Rapido. Used everywhere Phase 1 needs data; the real
 * production impl will slot in behind the same interface later.
 *
 * Pricing model (per tier):
 *
 *     base = flagfall + per_km * distance + per_min * trip_duration_min
 *     quote = base * surge_multiplier
 *
 * The surge multiplier combines three signals so the output feels like
 * something an actual Bengaluru commuter would see:
 *
 *  1. **Time of day** — classic bi-modal rush hours (8–10.30 AM, 5.30–8.30 PM)
 *     plus a lighter nightlife bump around 10 PM–midnight and a late-night
 *     scarcity tail.
 *  2. **Hot-zone proximity** — pickups or drop-offs inside well-known
 *     high-traffic localities (Koramangala, Whitefield, MG Road, Electronic
 *     City, Indiranagar, HSR Layout) push the multiplier higher. This is
 *     what makes a live demo in those areas look convincing.
 *  3. **Per-tick jitter** — small random wobble so back-to-back ticks from
 *     the same origin/destination never return *identical* quotes, which
 *     is what the real apps feel like.
 *
 * Output is deterministic in structure (the same nine tiers every tick)
 * but non-deterministic in values (jitter + time-of-day drift), which is
 * exactly what the TFLite model in Phase 3 needs as input.
 */
@Singleton
class MockRideDataRepository @Inject constructor(
    private val fareEstimator: FareEstimator
) : RideDataRepository {

    override fun observeRideOptions(
        origin: LatLng,
        destination: LatLng
    ): Flow<RoutedRideQuotes> = flow {
        while (true) {
            emit(fetchRideOptions(origin, destination))
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.Default)

    override suspend fun fetchRideOptions(
        origin: LatLng,
        destination: LatLng
    ): RoutedRideQuotes = RoutedRideQuotes(
        options = generateOptions(origin, destination),
        // Mock has no real route — empty polyline tells the map composable
        // to fall back to its dashed straight-line rendering.
        polyline = emptyList()
    )

    // ------------------------------------------------------------------
    // Generation
    // ------------------------------------------------------------------

    private suspend fun generateOptions(origin: LatLng, destination: LatLng): List<RideOption> {
        val distanceKm = haversineKm(origin, destination).coerceAtLeast(0.5)
        val tripMinutes = (distanceKm / CRUISE_SPEED_KMPH * 60.0).coerceAtLeast(5.0)

        val zoneSurge = hotZoneBoost(origin) + hotZoneBoost(destination)
        val timeSurge = timeOfDaySurge(LocalTime.now(ZoneId.of("Asia/Kolkata")))
        val baseSurge = (1.0 + zoneSurge + timeSurge).coerceAtLeast(1.0)

        val now = Instant.now()
        val options = ArrayList<RideOption>(CATALOG.size)

        val tripDuration = Duration.ofMinutes(tripMinutes.roundToLong())
        CATALOG.forEach { tier ->
            // Per-tier jitter so providers don't return identical numbers on the same tick.
            val jitter = Random.nextDouble(-0.08, 0.12)
            val surge = (baseSurge + jitter).coerceIn(1.0, MAX_SURGE)

            val base = tier.flagfall +
                tier.perKm * distanceKm +
                tier.perMin * tripMinutes

            val price = (base * surge).roundToRupees()
            val etaMinutes = Random.nextInt(tier.minEtaMin, tier.maxEtaMin + 1)

            val fareEstimate = runCatching {
                fareEstimator.estimate(
                    provider = tier.provider,
                    serviceTier = tier.serviceTier,
                    distanceKm = distanceKm,
                    tripDuration = tripDuration
                )
            }.getOrNull()

            options += RideOption(
                id = "${tier.provider.name}-${tier.serviceTier}-${now.toEpochMilli()}"
                    .lowercase(),
                provider = tier.provider,
                serviceTier = tier.serviceTier,
                priceInr = price,
                basePriceInr = base.roundToRupees(),
                surgeMultiplier = surge.roundTo(2),
                etaToPickup = Duration.ofMinutes(etaMinutes.toLong()),
                estimatedTripDuration = tripDuration,
                distanceKm = distanceKm.roundTo(2),
                fetchedAt = now,
                fareEstimate = fareEstimate
            )
        }

        Timber.tag(TAG).d(
            "Generated %d options (baseSurge=%.2f, distance=%.1f km, tripMin=%.1f)",
            options.size, baseSurge, distanceKm, tripMinutes
        )
        return options
    }

    // ------------------------------------------------------------------
    // Surge signals
    // ------------------------------------------------------------------

    /** Bi-modal commute curve for Bengaluru + nightlife bump + late-night scarcity. */
    private fun timeOfDaySurge(now: LocalTime): Double {
        val h = now.hour + now.minute / 60.0
        return when {
            h in 8.0..10.5 -> 0.55            // morning rush
            h in 17.5..20.5 -> 0.75           // evening rush — the worst of the day
            h in 22.0..23.75 -> 0.25          // nightlife return traffic
            h in 0.0..5.0 -> 0.15             // late-night driver scarcity
            else -> 0.0
        }
    }

    private fun hotZoneBoost(point: LatLng): Double =
        HOT_ZONES.firstOrNull { it.contains(point) }?.surgeBoost ?: 0.0

    // ------------------------------------------------------------------
    // Math helpers
    // ------------------------------------------------------------------

    /**
     * Flat-earth approximation of great-circle distance. Accurate to ~0.1%
     * for the intra-city distances this app cares about, and avoids the
     * cost of full Haversine trig on every polling tick.
     */
    private fun haversineKm(a: LatLng, b: LatLng): Double {
        val latKm = 111.0
        val lonKm = 111.0 * cos(Math.toRadians((a.latitude + b.latitude) / 2.0))
        val dLat = (a.latitude - b.latitude) * latKm
        val dLon = (a.longitude - b.longitude) * lonKm
        return hypot(dLat, dLon)
    }

    private fun Double.roundTo(decimals: Int): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return (this * factor).roundToLong() / factor
    }

    private fun Double.roundToRupees(): Double = this.roundToInt().toDouble()

    // ------------------------------------------------------------------
    // Catalog & zones
    // ------------------------------------------------------------------

    private data class ServiceTier(
        val provider: RideProvider,
        val serviceTier: String,
        val flagfall: Double,
        val perKm: Double,
        val perMin: Double,
        val minEtaMin: Int,
        val maxEtaMin: Int
    )

    private data class HotZone(
        val name: String,
        val minLat: Double, val maxLat: Double,
        val minLon: Double, val maxLon: Double,
        val surgeBoost: Double
    ) {
        fun contains(p: LatLng): Boolean =
            p.latitude in minLat..maxLat && p.longitude in minLon..maxLon
    }

    companion object {
        private const val TAG = "MockRideRepo"

        /** 30 s matches the refresh cadence Uber / Ola typically run at. */
        private const val POLL_INTERVAL_MS = 30_000L

        /** Honest Bengaluru average including signals — used to convert km → min. */
        private const val CRUISE_SPEED_KMPH = 18.0

        /** Hard ceiling so no single tick produces an absurd 5x quote. */
        private const val MAX_SURGE = 3.5

        // Tier tuning is based on publicly-reported estimates from Bengaluru
        // riders; numbers are indicative, not contractual.
        private val CATALOG = listOf(
            ServiceTier(RideProvider.UBER,   "UberGo",   50.0, 14.0, 1.5, 3, 9),
            ServiceTier(RideProvider.UBER,   "Premier",  65.0, 18.0, 2.0, 4, 10),
            ServiceTier(RideProvider.UBER,   "Auto",     30.0, 11.0, 1.0, 2, 7),
            ServiceTier(RideProvider.OLA,    "Mini",     50.0, 13.5, 1.4, 3, 9),
            ServiceTier(RideProvider.OLA,    "Prime",    70.0, 19.0, 2.2, 4, 11),
            ServiceTier(RideProvider.OLA,    "Auto",     30.0, 11.5, 1.0, 2, 7),
            ServiceTier(RideProvider.RAPIDO, "Bike",     25.0,  6.5, 0.8, 1, 5),
            ServiceTier(RideProvider.RAPIDO, "Auto",     30.0, 11.0, 1.0, 2, 7),
            ServiceTier(RideProvider.RAPIDO, "Cab Lite", 45.0, 12.5, 1.4, 3, 9)
        )

        // Bounding boxes for well-known Bengaluru surge hot spots. Boxes are
        // intentionally a bit wider than the locality centroid so demos near
        // the edges (e.g. HSR/Koramangala border) still trigger the boost.
        private val HOT_ZONES = listOf(
            HotZone("Koramangala",       12.921, 12.949, 77.612, 77.645, 0.45),
            HotZone("Whitefield",        12.955, 13.010, 77.700, 77.770, 0.40),
            HotZone("MG Road / Brigade", 12.970, 12.985, 77.595, 77.625, 0.35),
            HotZone("Electronic City",   12.820, 12.855, 77.655, 77.700, 0.30),
            HotZone("Indiranagar",       12.962, 12.985, 77.635, 77.660, 0.30),
            HotZone("HSR Layout",        12.900, 12.930, 77.630, 77.665, 0.25)
        )
    }
}
