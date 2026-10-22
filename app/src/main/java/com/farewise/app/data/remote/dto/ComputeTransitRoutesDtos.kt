package com.farewise.app.data.remote.dto

import com.squareup.moshi.JsonClass

/**
 * Request / response bodies for `computeRoutes` in TRANSIT travel mode.
 *
 * Modelled separately from [ComputeRoutesRequest] because the request shape
 * differs (transitPreferences instead of routingPreference) and the
 * response carries a much deeper payload — we need step-level transit
 * details, line names, stop counts, departure & arrival times.
 *
 * The Google Maps Routes API rejects field masks that don't match the
 * request, so each request type ships with its own retrofit binding +
 * its own field mask in the @Headers annotation.
 */

@JsonClass(generateAdapter = false)
data class ComputeTransitRoutesRequest(
    val origin: RouteWaypoint,
    val destination: RouteWaypoint,
    val travelMode: String = TRAVEL_MODE_TRANSIT,
    val computeAlternativeRoutes: Boolean = true,
    val languageCode: String = "en-IN",
    val units: String = "METRIC",
    val transitPreferences: TransitPreferences = TransitPreferences()
) {
    companion object {
        const val TRAVEL_MODE_TRANSIT = "TRANSIT"
    }
}

/**
 * What modes Google should consider. We allow all rail/bus options because
 * Bengaluru's mix is exactly that — Metro, BMTC bus, suburban rail. Limiting
 * the set would silently drop options the rider might prefer.
 */
@JsonClass(generateAdapter = false)
data class TransitPreferences(
    val allowedTravelModes: List<String> = listOf("BUS", "SUBWAY", "TRAIN", "LIGHT_RAIL", "RAIL"),
    val routingPreference: String = "LESS_WALKING"
)

@JsonClass(generateAdapter = false)
data class ComputeTransitRoutesResponse(
    val routes: List<TransitRouteDto>?
)

@JsonClass(generateAdapter = false)
data class TransitRouteDto(
    val distanceMeters: Int?,
    val duration: String?,
    val legs: List<TransitLegDto>?
)

@JsonClass(generateAdapter = false)
data class TransitLegDto(
    val steps: List<TransitStepDto>?
)

/**
 * A single step inside a leg. travelMode is "WALK" or "TRANSIT"; only the
 * latter carries [transitDetails] — walking steps just contribute to the
 * walk-time aggregate the UI shows beside the schedule.
 */
@JsonClass(generateAdapter = false)
data class TransitStepDto(
    val travelMode: String?,
    val staticDuration: String?,
    val transitDetails: TransitDetailsDto?
)

@JsonClass(generateAdapter = false)
data class TransitDetailsDto(
    val stopDetails: TransitStopDetailsDto?,
    val transitLine: TransitLineDto?,
    val stopCount: Int?,
    val headsign: String?
)

@JsonClass(generateAdapter = false)
data class TransitStopDetailsDto(
    val arrivalStop: TransitStopDto?,
    val departureStop: TransitStopDto?,
    val arrivalTime: String?,   // RFC3339 timestamp
    val departureTime: String?
)

@JsonClass(generateAdapter = false)
data class TransitStopDto(
    val name: String?,
    /**
     * Stop coordinates — used by [com.farewise.app.domain.usecase.BuildMultimodalCombosUseCase]
     * to find the transfer point between transit and the last-mile ride.
     * Null when Google omits it (some informal stops have no geocoded
     * location).
     */
    val location: TransitStopLocationDto?
)

@JsonClass(generateAdapter = false)
data class TransitStopLocationDto(
    val latLng: RouteLatLng?
)

@JsonClass(generateAdapter = false)
data class TransitLineDto(
    val name: String?,
    val nameShort: String?,
    val agencies: List<TransitAgencyDto>?,
    val vehicle: TransitVehicleDto?
)

@JsonClass(generateAdapter = false)
data class TransitAgencyDto(
    val name: String?
)

/**
 * `type` is one of BUS, SUBWAY, HEAVY_RAIL, COMMUTER_TRAIN, LIGHT_RAIL,
 * RAIL, TRAM (and a long list of others). The repository maps these to our
 * coarser [com.farewise.app.domain.model.TransitMode] enum.
 */
@JsonClass(generateAdapter = false)
data class TransitVehicleDto(
    val type: String?,
    val name: TransitVehicleNameDto?
)

@JsonClass(generateAdapter = false)
data class TransitVehicleNameDto(
    val text: String?
)
