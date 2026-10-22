package com.farewise.app.data.remote.dto

import com.squareup.moshi.JsonClass

/**
 * Request / response bodies for Google Maps Routes API `computeRoutes`.
 *
 *   POST https://routes.googleapis.com/directions/v2:computeRoutes
 *
 * The real API has dozens of optional fields; we only model what this
 * project uses so the JSON surface stays small and predictable.
 *
 * Required headers (attached by the interceptor, not the DTO):
 *   • X-Goog-Api-Key     — the BuildConfig key
 *   • X-Goog-FieldMask   — which response fields we're requesting
 *   • Content-Type: application/json
 *
 * The FieldMask is mandatory — Google rejects the request with 400 if it's
 * missing, so the interceptor always sends:
 *
 *     routes.distanceMeters,routes.duration,routes.staticDuration
 *
 * [duration] is the live-traffic ETA (requires routingPreference =
 * TRAFFIC_AWARE_OPTIMAL), [staticDuration] is the free-flow baseline. Their
 * difference is the delay we turn into a surge multiplier in Phase 3.
 */

@JsonClass(generateAdapter = false)
data class ComputeRoutesRequest(
    val origin: RouteWaypoint,
    val destination: RouteWaypoint,
    val travelMode: String = TRAVEL_MODE_DRIVE,
    /**
     * TRAFFIC_AWARE_OPTIMAL is the slowest/most-expensive routing mode but
     * is the only one that fills `duration` with live traffic data. Without
     * it the field collapses to staticDuration and the surge algorithm has
     * nothing to react to.
     */
    val routingPreference: String = ROUTING_TRAFFIC_AWARE_OPTIMAL,
    val computeAlternativeRoutes: Boolean = false,
    val languageCode: String = "en-IN",
    val units: String = "METRIC"
) {
    companion object {
        const val TRAVEL_MODE_DRIVE = "DRIVE"
        const val ROUTING_TRAFFIC_AWARE_OPTIMAL = "TRAFFIC_AWARE_OPTIMAL"
        const val FIELD_MASK =
            "routes.distanceMeters,routes.duration,routes.staticDuration," +
                "routes.polyline.encodedPolyline"
    }
}

@JsonClass(generateAdapter = false)
data class RouteWaypoint(
    val location: RouteLocation
)

@JsonClass(generateAdapter = false)
data class RouteLocation(
    val latLng: RouteLatLng
)

@JsonClass(generateAdapter = false)
data class RouteLatLng(
    val latitude: Double,
    val longitude: Double
)

@JsonClass(generateAdapter = false)
data class ComputeRoutesResponse(
    val routes: List<RouteDto>?
)

/**
 * Duration fields come back as protobuf Duration JSON strings — e.g.
 * "1245s". We keep them as strings here and let the repository layer
 * parse them; the parser is a couple of lines and needing a Moshi
 * adapter just for this one shape would be overkill.
 *
 * [polyline.encodedPolyline] is the Google encoded-polyline string for the
 * full driving path. The repository decodes it into a [com.farewise.app.domain.model.LatLng]
 * sequence so the dashboard map can draw the actual road geometry instead
 * of a straight line between origin and destination.
 */
@JsonClass(generateAdapter = false)
data class RouteDto(
    val distanceMeters: Int?,
    val duration: String?,
    val staticDuration: String?,
    val polyline: RoutePolylineDto?
)

@JsonClass(generateAdapter = false)
data class RoutePolylineDto(
    val encodedPolyline: String?
)
