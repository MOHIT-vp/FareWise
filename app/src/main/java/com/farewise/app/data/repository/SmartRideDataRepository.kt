package com.farewise.app.data.repository

import com.farewise.app.BuildConfig
import com.farewise.app.data.remote.GoogleMapsApiService
import com.farewise.app.data.remote.dto.ComputeRoutesRequest
import com.farewise.app.data.remote.dto.RouteDto
import com.farewise.app.data.remote.dto.RouteLatLng
import com.farewise.app.data.remote.dto.RouteLocation
import com.farewise.app.data.remote.dto.RouteWaypoint
import com.farewise.app.domain.model.LatLng
import com.farewise.app.domain.model.RideOption
import com.farewise.app.domain.model.RideProvider
import com.farewise.app.domain.model.RoutedRideQuotes
import com.farewise.app.domain.repository.FareEstimator
import com.farewise.app.domain.repository.RideDataRepository
import com.farewise.app.util.PolylineDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * "Smart Mock" ride quotes.
 *
 * Fetches the real driving distance + live-traffic duration from Google Maps
 * Routes API v2, then derives a realistic surge multiplier from the delta
 * between live traffic and free-flow. Provider prices (Uber / Ola / Rapido)
 * are still synthesised — their consumer pricing APIs are closed — but the
 * inputs the synthesis runs on are the same ones their real dispatchers see.
 *
 * Pipeline per tick:
 *
 *   1. POST computeRoutes with (origin, destination) + TRAFFIC_AWARE_OPTIMAL.
 *   2. Parse distanceMeters, duration (s, live), staticDuration (s, free-flow).
 *   3. trafficDelay = duration − staticDuration. Translate it into a surge
 *      multiplier: the bigger the delay relative to free-flow, the heavier.
 *   4. For each of the 9 (provider, tier) pairs in the catalog, compute a
 *      base fare from real distance + duration and scale by a per-tier surge
 *      weight (bikes are less reactive than premium cabs, Rapido bikes float
 *      a lower mean than Uber Premier, etc).
 *
 * Graceful degradation — any of the following paths fall back to the
 * original jitter-based [MockRideDataRepository] for that tick so the UI
 * never dies silently:
 *   • `BuildConfig.GOOGLE_MAPS_API_KEY` is blank (dev forgot to paste it),
 *   • the HTTP call throws (no network / 4xx / 5xx),
 *   • the response has no routes (unreachable point pair).
 *
 * Polling cadence matches the old mock (30 s) so downstream collectors
 * (dashboard VM, price-history writer, TFLite worker feed) don't change.
 */
@Singleton
class SmartRideDataRepository @Inject constructor(
    private val api: GoogleMapsApiService,
    private val fallback: MockRideDataRepository,
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
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchRideOptions(
        origin: LatLng,
        destination: LatLng
    ): RoutedRideQuotes {
        if (BuildConfig.GOOGLE_MAPS_API_KEY.isBlank()) {
            Timber.tag(TAG).d("API key missing — returning synthetic fallback.")
            return fallback.fetchRideOptions(origin, destination)
        }

        val route = runCatching { callRoutes(origin, destination) }
            .onFailure { Timber.tag(TAG).w(it, "Routes API failed; falling back.") }
            .getOrNull()
            ?: return fallback.fetchRideOptions(origin, destination)

        val distanceKm = (route.distanceMeters ?: 0) / 1000.0
        val liveSec = parseSeconds(route.duration) ?: 0L
        val staticSec = parseSeconds(route.staticDuration) ?: liveSec
        if (distanceKm <= 0.0 || liveSec <= 0L) {
            Timber.tag(TAG).w("Routes returned zeros; falling back.")
            return fallback.fetchRideOptions(origin, destination)
        }

        val polyline = PolylineDecoder.decode(route.polyline?.encodedPolyline)
        return RoutedRideQuotes(
            options = buildOptions(distanceKm, liveSec, staticSec),
            polyline = polyline
        )
    }

    // ------------------------------------------------------------------
    // Network
    // ------------------------------------------------------------------

    private suspend fun callRoutes(origin: LatLng, destination: LatLng): RouteDto? {
        val body = ComputeRoutesRequest(
            origin = RouteWaypoint(RouteLocation(RouteLatLng(origin.latitude, origin.longitude))),
            destination = RouteWaypoint(
                RouteLocation(RouteLatLng(destination.latitude, destination.longitude))
            )
        )
        return api.computeRoutes(body).routes?.firstOrNull()
    }

    /**
     * Protobuf Duration JSON format is a decimal number of seconds suffixed
     * with "s" ("1245s", "60.500s"). Anything else is treated as unparseable.
     */
    private fun parseSeconds(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val stripped = raw.trimEnd('s', 'S')
        return stripped.toDoubleOrNull()?.toLong()
    }

    // ------------------------------------------------------------------
    // Pricing
    // ------------------------------------------------------------------

    /**
     * Turn raw route metrics into the full tier catalog.
     *
     * Math per tier:
     *
     *   tripMin      = liveSec / 60
     *   base         = flagfall + perKm * distanceKm + perMin * tripMin
     *   trafficBoost = clamp(delay / staticSec, 0, 1) * TRAFFIC_SURGE_CEIL
     *   surge        = 1 + trafficBoost * tier.surgeSensitivity + jitter
     *   price        = (base * surge).roundToRupees()
     */
    private suspend fun buildOptions(
        distanceKm: Double,
        liveSec: Long,
        staticSec: Long
    ): List<RideOption> {
        val tripMin = liveSec / 60.0
        val delaySec = max(0L, liveSec - staticSec).toDouble()
        val delayRatio = if (staticSec > 0) (delaySec / staticSec).coerceIn(0.0, 1.0) else 0.0
        val trafficBoost = delayRatio * TRAFFIC_SURGE_CEIL
        val now = Instant.now()

        Timber.tag(TAG).d(
            "Routes tick: %.1f km, live=%ds, static=%ds, delay=%.0fs, boost=%.2f",
            distanceKm, liveSec, staticSec, delaySec, trafficBoost
        )

        val tripDuration = Duration.ofSeconds(liveSec)
        return CATALOG.map { tier ->
            val jitter = Random.nextDouble(-0.04, 0.06)
            val surge = (1.0 + trafficBoost * tier.surgeSensitivity + jitter)
                .coerceIn(1.0, MAX_SURGE)

            val base = tier.flagfall +
                tier.perKm * distanceKm +
                tier.perMin * tripMin
            val price = (base * surge).roundToRupees()
            val etaMinutes = Random.nextInt(tier.minEtaMin, tier.maxEtaMin + 1)

            // Run the on-device ML fare estimator. Returns null when assets
            // aren't shipped — the UI then renders just the catalog price.
            val fareEstimate = runCatching {
                fareEstimator.estimate(
                    provider = tier.provider,
                    serviceTier = tier.serviceTier,
                    distanceKm = distanceKm,
                    tripDuration = tripDuration
                )
            }.onFailure { Timber.tag(TAG).w(it, "Fare estimator threw — skipping band") }
                .getOrNull()

            RideOption(
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
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun Double.roundTo(decimals: Int): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return (this * factor).roundToLong() / factor
    }

    private fun Double.roundToRupees(): Double = this.roundToInt().toDouble()

    /**
     * A single tier in the 3-provider catalog.
     *
     * [surgeSensitivity] scales how aggressively this tier reacts to traffic
     * — premium cabs spike hardest, bikes barely notice (they lane-split).
     */
    private data class ServiceTier(
        val provider: RideProvider,
        val serviceTier: String,
        val flagfall: Double,
        val perKm: Double,
        val perMin: Double,
        val minEtaMin: Int,
        val maxEtaMin: Int,
        val surgeSensitivity: Double
    )

    companion object {
        private const val TAG = "SmartRideRepo"

        /** Matches Phase-1 polling cadence so downstream collectors don't change. */
        private const val POLL_INTERVAL_MS = 30_000L

        /** Absolute ceiling on any single quote's surge — keeps outliers sane. */
        private const val MAX_SURGE = 3.5

        /**
         * Maximum multiplicative uplift the traffic signal can produce on its
         * own (before per-tier sensitivity). 1.8 means: at fully-saturated
         * traffic (live = 2× free-flow), a max-sensitivity tier sees a 2.8×
         * surge before jitter.
         */
        private const val TRAFFIC_SURGE_CEIL = 1.8

        /**
         * Catalog tuned so the three providers land in distinct price bands:
         *   Uber ≈ Ola ≈ mid-range cabs, Rapido Bike well under, Rapido Auto
         *   close to Uber Auto, Rapido Cab Lite slightly below Ola Mini.
         * Sensitivity decays from sedans → autos → bikes.
         */
        private val CATALOG = listOf(
            ServiceTier(RideProvider.UBER,   "UberGo",    50.0, 14.0, 1.5, 3, 9,  1.00),
            ServiceTier(RideProvider.UBER,   "Premier",   65.0, 18.0, 2.0, 4, 10, 1.15),
            ServiceTier(RideProvider.UBER,   "Auto",      30.0, 11.0, 1.0, 2, 7,  0.70),
            ServiceTier(RideProvider.OLA,    "Mini",      48.0, 13.5, 1.4, 3, 9,  0.95),
            ServiceTier(RideProvider.OLA,    "Prime",     70.0, 19.0, 2.2, 4, 11, 1.20),
            ServiceTier(RideProvider.OLA,    "Auto",      30.0, 11.5, 1.0, 2, 7,  0.65),
            ServiceTier(RideProvider.RAPIDO, "Bike",      25.0,  6.5, 0.8, 1, 5,  0.30),
            ServiceTier(RideProvider.RAPIDO, "Auto",      30.0, 11.0, 1.0, 2, 7,  0.60),
            ServiceTier(RideProvider.RAPIDO, "Cab Lite",  42.0, 12.5, 1.4, 3, 9,  0.85)
        )
    }
}
