package com.farewise.app.domain.repository

import com.farewise.app.domain.model.LatLng
import com.farewise.app.domain.model.WeatherSnapshot

/**
 * Fetches current weather conditions for a coordinate.
 *
 * The contract is intentionally minimal — the caller passes a [LatLng] and
 * either gets a snapshot back or null (offline, no API key, upstream error).
 * The implementation handles caching internally so callers that fire the
 * predictor every 30 s don't hammer the upstream service.
 */
interface WeatherRepository {
    suspend fun currentWeather(at: LatLng): WeatherSnapshot?
}
