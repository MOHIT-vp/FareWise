package com.farewise.app.data.remote

import com.farewise.app.data.remote.dto.ComputeRoutesRequest
import com.farewise.app.data.remote.dto.ComputeRoutesResponse
import com.farewise.app.data.remote.dto.ComputeTransitRoutesRequest
import com.farewise.app.data.remote.dto.ComputeTransitRoutesResponse
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

/**
 * Retrofit binding for Google Maps Routes API v2.
 *
 * The base URL is injected via Hilt so tests can swap in a MockWebServer
 * without touching this interface.
 *
 * Only `computeRoutes` is declared — the app doesn't need any other endpoint
 * for the Smart Mock pricing pipeline. New endpoints can be added here as
 * independent suspend functions without affecting existing callers.
 *
 * Note the hard-coded [ComputeRoutesRequest.Companion.FIELD_MASK] header:
 * the Routes API rejects requests without X-Goog-FieldMask as 400 INVALID.
 */
interface GoogleMapsApiService {

    @Headers(
        "Content-Type: application/json",
        "X-Goog-FieldMask: routes.distanceMeters,routes.duration,routes.staticDuration," +
            "routes.polyline.encodedPolyline"
    )
    @POST("directions/v2:computeRoutes")
    suspend fun computeRoutes(
        @Body body: ComputeRoutesRequest
    ): ComputeRoutesResponse

    /**
     * TRANSIT travel mode — returns one or more journeys composed of walking
     * + transit legs. The field mask is broader than [computeRoutes] because
     * we need step-level transit details (line name, vehicle type, stop
     * counts, departure/arrival times) to render a [TransitSchedule].
     */
    @Headers(
        "Content-Type: application/json",
        "X-Goog-FieldMask: routes.duration,routes.distanceMeters," +
            "routes.legs.steps.travelMode," +
            "routes.legs.steps.staticDuration," +
            "routes.legs.steps.transitDetails," +
            "routes.legs.steps.transitDetails.stopDetails.arrivalStop.location," +
            "routes.legs.steps.transitDetails.stopDetails.departureStop.location"
    )
    @POST("directions/v2:computeRoutes")
    suspend fun computeTransitRoutes(
        @Body body: ComputeTransitRoutesRequest
    ): ComputeTransitRoutesResponse

    companion object {
        const val BASE_URL = "https://routes.googleapis.com/"
    }
}
