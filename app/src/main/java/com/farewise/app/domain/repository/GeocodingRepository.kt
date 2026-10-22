package com.farewise.app.domain.repository

import com.farewise.app.domain.model.LatLng
import com.farewise.app.ui.common.Place

/**
 * Abstraction over forward and reverse geocoding.
 *
 * The domain layer uses this to turn user-typed addresses into coordinates
 * (forward) and map-tapped coordinates into human-readable labels (reverse).
 * The concrete implementation lives in the data layer and is free to use
 * Android's built-in [android.location.Geocoder] or any other backend.
 */
interface GeocodingRepository {

    /**
     * Forward-geocode: turn a free-text address query into a list of matching
     * [Place]s. Returns at most [maxResults] items; may return an empty list
     * if the geocoder is unavailable or yields no matches.
     */
    suspend fun searchByAddress(query: String, maxResults: Int = 5): List<Place>

    /**
     * Reverse-geocode: turn map coordinates into the nearest [Place].
     * Returns `null` if the geocoder is unavailable or no result is found.
     */
    suspend fun reverseGeocode(latLng: LatLng): Place?
}
