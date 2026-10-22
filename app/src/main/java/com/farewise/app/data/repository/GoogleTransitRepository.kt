package com.farewise.app.data.repository

import com.farewise.app.BuildConfig
import com.farewise.app.data.remote.GoogleMapsApiService
import com.farewise.app.data.remote.dto.ComputeTransitRoutesRequest
import com.farewise.app.data.remote.dto.RouteLatLng
import com.farewise.app.data.remote.dto.RouteLocation
import com.farewise.app.data.remote.dto.RouteWaypoint
import com.farewise.app.data.remote.dto.TransitRouteDto
import com.farewise.app.data.remote.dto.TransitStepDto
import com.farewise.app.domain.model.LatLng
import com.farewise.app.domain.model.TransitMode
import com.farewise.app.domain.model.TransitSchedule
import com.farewise.app.domain.repository.TransitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Real public-transit repository backed by Google Maps Routes API in
 * TRANSIT travel mode.
 *
 * The Routes API returns a list of full journeys (origin → destination,
 * possibly with multiple legs and walk segments). We collapse each route
 * into a single [TransitSchedule] keyed on the *first* transit step —
 * that's the leg the rider needs to catch first, which is what the
 * dashboard wants to surface.
 *
 * Falls back to [MockTransitRepository] whenever:
 *   • the Google Maps API key is blank,
 *   • the upstream call fails,
 *   • the response has no usable transit routes.
 *
 * That keeps the unified dashboard alive even on a dev build with no key —
 * exact mirror of the smart-vs-mock pattern in [SmartRideDataRepository].
 */
@Singleton
class GoogleTransitRepository @Inject constructor(
    private val api: GoogleMapsApiService,
    private val fallback: MockTransitRepository
) : TransitRepository {

    override fun observeTransitSchedules(
        origin: LatLng,
        destination: LatLng
    ): Flow<List<TransitSchedule>> = flow {
        while (true) {
            emit(fetchTransitSchedules(origin, destination))
            delay(POLL_MS)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchTransitSchedules(
        origin: LatLng,
        destination: LatLng
    ): List<TransitSchedule> {
        if (BuildConfig.GOOGLE_MAPS_API_KEY.isBlank()) {
            return fallback.fetchTransitSchedules(origin, destination)
        }

        val routes = runCatching { call(origin, destination) }
            .onFailure { Timber.tag(TAG).w(it, "Transit Routes call failed; falling back.") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return fallback.fetchTransitSchedules(origin, destination)

        val straightKm = approxDistanceKm(origin, destination).coerceAtLeast(1.0)
        val now = Instant.now()
        val schedules = routes.mapIndexedNotNull { idx, route ->
            buildSchedule(route, idx, straightKm, now)
        }

        // If the response had only car-fallback routes (every transit step had
        // missing details), fall back too. Empty list still beats lying.
        return if (schedules.isEmpty()) fallback.fetchTransitSchedules(origin, destination)
        else schedules
    }

    // ------------------------------------------------------------------

    private suspend fun call(origin: LatLng, destination: LatLng): List<TransitRouteDto>? {
        val body = ComputeTransitRoutesRequest(
            origin = RouteWaypoint(RouteLocation(RouteLatLng(origin.latitude, origin.longitude))),
            destination = RouteWaypoint(
                RouteLocation(RouteLatLng(destination.latitude, destination.longitude))
            )
        )
        return api.computeTransitRoutes(body).routes
    }

    /**
     * Pick the first transit step in the journey and use it to populate the
     * schedule. Walking-only routes (no transit step at all) are dropped.
     */
    private fun buildSchedule(
        route: TransitRouteDto,
        index: Int,
        straightKm: Double,
        now: Instant
    ): TransitSchedule? {
        val steps = route.legs?.flatMap { it.steps.orEmpty() }.orEmpty()
        val firstTransit = steps.firstOrNull { it.travelMode == "TRANSIT" } ?: return null
        val details = firstTransit.transitDetails ?: return null
        val line = details.transitLine
        val stops = details.stopDetails

        val mode = mapVehicleType(line?.vehicle?.type)
        val routeName = line?.nameShort ?: line?.name ?: line?.vehicle?.name?.text ?: "Transit"
        val headsign = details.headsign
            ?: stops?.arrivalStop?.name
            ?: "Towards destination"

        val departsAt = parseRfc3339(stops?.departureTime) ?: now.plus(Duration.ofMinutes(5))
        val arrivesAt = parseRfc3339(stops?.arrivalTime)
            ?: departsAt.plus(Duration.ofMinutes(estimateRideMinutes(mode, straightKm)))

        // Walking steps that precede the first transit step contribute to
        // walk-to-stop. Everything after is "in transit" — already part of
        // the depart→arrive interval.
        val walkSec = steps
            .takeWhile { it.travelMode != "TRANSIT" }
            .mapNotNull { parseSeconds(it.staticDuration) }
            .sum()

        val arrival = stops?.arrivalStop?.location?.latLng
            ?.let { LatLng(it.latitude, it.longitude) }
        val departure = stops?.departureStop?.location?.latLng
            ?.let { LatLng(it.latitude, it.longitude) }

        return TransitSchedule(
            id = "transit-${route.duration ?: index}-${now.toEpochMilli()}",
            mode = mode,
            routeName = routeName,
            headsign = headsign,
            // Routes API doesn't return fares — fall back to the city tariff
            // approximation we use in the mock so the dashboard stays useful.
            fareInr = approximateFareInr(mode, straightKm),
            departsAt = departsAt,
            arrivesAt = arrivesAt,
            walkingDurationToStop = Duration.ofSeconds(walkSec.coerceAtLeast(0L)),
            numberOfStops = (details.stopCount ?: 0).coerceAtLeast(0),
            arrivalStopLocation = arrival,
            departureStopLocation = departure
        )
    }

    private fun mapVehicleType(raw: String?): TransitMode = when (raw?.uppercase()) {
        "SUBWAY", "METRO_RAIL", "MONORAIL" -> TransitMode.METRO
        "HEAVY_RAIL", "COMMUTER_TRAIN", "LONG_DISTANCE_TRAIN", "RAIL", "HIGH_SPEED_TRAIN" ->
            TransitMode.SUBURBAN_RAIL
        else -> TransitMode.BUS  // BUS, TROLLEYBUS, SHARE_TAXI, TRAM, LIGHT_RAIL, …
    }

    /** BMRCL / BMTC tariff approximation — same shape as MockTransitRepository. */
    private fun approximateFareInr(mode: TransitMode, km: Double): Double = when (mode) {
        TransitMode.METRO -> when {
            km < 2 -> 10.0
            km < 5 -> 20.0
            km < 12 -> 40.0
            km < 20 -> 50.0
            else -> 60.0
        }
        TransitMode.BUS -> (40.0 + km * 3.0).coerceAtLeast(15.0)
        TransitMode.SUBURBAN_RAIL -> (15.0 + km * 1.5).coerceAtLeast(10.0)
    }

    private fun estimateRideMinutes(mode: TransitMode, km: Double): Long {
        val cruise = when (mode) {
            TransitMode.METRO -> 28.0
            TransitMode.BUS -> 16.0
            TransitMode.SUBURBAN_RAIL -> 32.0
        }
        return (km / cruise * 60.0).toLong().coerceAtLeast(6L)
    }

    private fun parseRfc3339(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(raw)) }
            .getOrNull()
    }

    private fun parseSeconds(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return raw.trimEnd('s', 'S').toDoubleOrNull()?.toLong()
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
        private const val TAG = "GoogleTransit"
        private const val POLL_MS = 60_000L  // transit timetables drift slower than ride pricing
    }
}
