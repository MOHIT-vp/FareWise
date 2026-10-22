package com.farewise.app.domain.model

import java.time.Duration
import java.time.Instant

/** Class of public-transit service. */
enum class TransitMode(val displayName: String) {
    METRO("Metro"),
    BUS("Bus"),
    SUBURBAN_RAIL("Suburban Rail")
}

/**
 * One departure of a public-transit vehicle that serves the rider's route.
 *
 * We model the *next* departure rather than a full timetable because the
 * dashboard only needs to answer "what can I catch right now?". If a
 * richer schedule view is needed later, a list of these is enough.
 */
data class TransitSchedule(
    val id: String,
    val mode: TransitMode,
    val routeName: String,
    val headsign: String,
    val fareInr: Double,
    val departsAt: Instant,
    val arrivesAt: Instant,
    val walkingDurationToStop: Duration,
    val numberOfStops: Int,
    /**
     * Where the rider gets off the transit leg, when known. Used by the
     * multimodal use-case to size the last-mile ride. Null for mock data
     * or when Google didn't return geocoded stop coordinates.
     */
    val arrivalStopLocation: LatLng? = null,
    /**
     * Where the rider boards. Useful for "walking distance to first stop"
     * UX in future iterations; not currently rendered.
     */
    val departureStopLocation: LatLng? = null
) {
    /** Wall-clock duration from *now* until the rider would arrive. */
    val totalDurationFromNow: Duration
        get() = Duration.between(Instant.now(), arrivesAt)

    /** Duration spent actually riding the vehicle. */
    val tripDuration: Duration
        get() = Duration.between(departsAt, arrivesAt)
}
