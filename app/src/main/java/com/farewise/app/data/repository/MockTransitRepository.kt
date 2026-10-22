package com.farewise.app.data.repository

import com.farewise.app.domain.model.LatLng
import com.farewise.app.domain.model.TransitMode
import com.farewise.app.domain.model.TransitSchedule
import com.farewise.app.domain.repository.TransitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Mock implementation of [TransitRepository].
 *
 * Returns one upcoming departure for each of the three public-transit
 * modes a Bengaluru rider typically has access to: Namma Metro, a BMTC
 * AC bus (Vajra), and a Namma Railu suburban service. Fares approximate
 * the real operator tariffs so the comparative dashboard feels grounded.
 */
@Singleton
class MockTransitRepository @Inject constructor() : TransitRepository {

    override fun observeTransitSchedules(
        origin: LatLng,
        destination: LatLng
    ): Flow<List<TransitSchedule>> = flow {
        while (true) {
            emit(generate(origin, destination))
            delay(POLL_MS)
        }
    }.flowOn(Dispatchers.Default)

    override suspend fun fetchTransitSchedules(
        origin: LatLng,
        destination: LatLng
    ): List<TransitSchedule> = generate(origin, destination)

    // ------------------------------------------------------------------

    private fun generate(origin: LatLng, destination: LatLng): List<TransitSchedule> {
        val now = Instant.now()
        val straightKm = approxDistanceKm(origin, destination).coerceAtLeast(1.0)

        return listOf(
            schedule(
                id = "metro-purple-${now.toEpochMilli()}",
                mode = TransitMode.METRO,
                routeName = "Purple Line",
                headsign = "Whitefield (Kadugodi)",
                fare = bengaluruMetroFare(straightKm),
                walkMin = Random.nextInt(4, 12),
                rideMin = (straightKm / 28.0 * 60.0).toLong().coerceAtLeast(6),
                stops = Random.nextInt(3, 12),
                now = now
            ),
            schedule(
                id = "bus-500d-${now.toEpochMilli()}",
                mode = TransitMode.BUS,
                routeName = "500D Vajra (BMTC AC)",
                headsign = "Silk Board ↔ Hebbal",
                fare = 40.0 + straightKm * 3.0,
                walkMin = Random.nextInt(3, 9),
                rideMin = (straightKm / 16.0 * 60.0).toLong().coerceAtLeast(10),
                stops = Random.nextInt(6, 20),
                now = now
            ),
            schedule(
                id = "rail-krp-${now.toEpochMilli()}",
                mode = TransitMode.SUBURBAN_RAIL,
                routeName = "Namma Railu",
                headsign = "KSR ↔ Yesvantpur",
                fare = 15.0 + straightKm * 1.5,
                walkMin = Random.nextInt(7, 15),
                rideMin = (straightKm / 32.0 * 60.0).toLong().coerceAtLeast(8),
                stops = Random.nextInt(2, 6),
                now = now
            )
        )
    }

    private fun schedule(
        id: String,
        mode: TransitMode,
        routeName: String,
        headsign: String,
        fare: Double,
        walkMin: Int,
        rideMin: Long,
        stops: Int,
        now: Instant
    ): TransitSchedule {
        val walk = Duration.ofMinutes(walkMin.toLong())
        // Next departure lands a minute or two after the rider reaches the stop,
        // reflecting realistic BMRCL / BMTC frequencies.
        val departs = now.plus(walk).plus(Duration.ofMinutes(Random.nextLong(1, 7)))
        return TransitSchedule(
            id = id,
            mode = mode,
            routeName = routeName,
            headsign = headsign,
            fareInr = fare.coerceAtLeast(10.0),
            departsAt = departs,
            arrivesAt = departs.plus(Duration.ofMinutes(rideMin)),
            walkingDurationToStop = walk,
            numberOfStops = stops
        )
    }

    /** BMRCL-style graduated fare in INR. */
    private fun bengaluruMetroFare(km: Double): Double = when {
        km < 2 -> 10.0
        km < 5 -> 20.0
        km < 12 -> 40.0
        km < 20 -> 50.0
        else -> 60.0
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
        private const val POLL_MS = 45_000L
    }
}
