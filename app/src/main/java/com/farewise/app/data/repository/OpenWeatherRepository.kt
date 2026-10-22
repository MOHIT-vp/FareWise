package com.farewise.app.data.repository

import com.farewise.app.BuildConfig
import com.farewise.app.data.remote.OpenWeatherApiService
import com.farewise.app.domain.model.LatLng
import com.farewise.app.domain.model.WeatherSnapshot
import com.farewise.app.domain.repository.WeatherRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.hypot

/**
 * OpenWeatherMap-backed [WeatherRepository] with a small in-memory cache.
 *
 * We cache by (rounded coords, freshness window) because:
 *   • OWM weather updates at ~10 min cadence; sub-minute polling wastes the
 *     free-tier quota and just returns the same numbers.
 *   • The surge predictor on a 15-min cadence + the dashboard live polling
 *     would otherwise make two unnecessary calls per minute.
 *
 * Returns null whenever upstream fails or the API key isn't configured —
 * the surge predictor degrades gracefully and feeds zeros for the weather
 * channels (which the trainer normalises around the dataset mean, so this
 * just collapses to the no-weather baseline).
 */
@Singleton
class OpenWeatherRepository @Inject constructor(
    private val api: OpenWeatherApiService
) : WeatherRepository {

    private val lock = Mutex()
    private var cached: CachedSnapshot? = null

    override suspend fun currentWeather(at: LatLng): WeatherSnapshot? {
        if (BuildConfig.OPEN_WEATHER_API_KEY.isBlank()) return null

        lock.withLock {
            val current = cached
            if (current != null && current.isFreshFor(at)) return current.snapshot
        }

        val fresh = runCatching {
            api.currentWeather(at.latitude, at.longitude, BuildConfig.OPEN_WEATHER_API_KEY)
        }.onFailure { Timber.tag(TAG).w(it, "OpenWeather call failed") }
            .getOrNull() ?: return null

        val snapshot = WeatherSnapshot(
            temperatureCelsius = fresh.main?.temp ?: return null,
            // OWM returns wind speed in m/s under metric units; convert to km/h
            // because that's the unit the trainer normalises against.
            windKmh = (fresh.wind?.speed ?: 0.0) * 3.6,
            // Both rain and snow may be reported under a "1h" key; sum so the
            // model sees a single "precipitation" channel. Either side is
            // usually zero in Bengaluru — but the field exists year-round.
            precipitationMmPerHour =
                (fresh.rain?.lastHourMm ?: 0.0) + (fresh.snow?.lastHourMm ?: 0.0),
            // OWM caps visibility at 10 km; convert metres → km.
            visibilityKm = (fresh.visibility ?: 10_000) / 1000.0,
            fetchedAt = Instant.now()
        )

        lock.withLock { cached = CachedSnapshot(snapshot, at) }
        return snapshot
    }

    private data class CachedSnapshot(
        val snapshot: WeatherSnapshot,
        val center: LatLng
    ) {
        fun isFreshFor(query: LatLng): Boolean {
            if (Duration.between(snapshot.fetchedAt, Instant.now()) > FRESHNESS) return false
            // 5 km radius — Bengaluru weather is essentially uniform at that scale,
            // so cached data is fine for any nearby query.
            return distanceKm(center, query) < CACHE_RADIUS_KM
        }
    }

    companion object {
        private const val TAG = "OpenWeather"
        private val FRESHNESS = Duration.ofMinutes(10)
        private const val CACHE_RADIUS_KM = 5.0

        private fun distanceKm(a: LatLng, b: LatLng): Double {
            val latKm = 111.0
            val lonKm = 111.0 * cos(Math.toRadians((a.latitude + b.latitude) / 2.0))
            return hypot(
                (a.latitude - b.latitude) * latKm,
                (a.longitude - b.longitude) * lonKm
            )
        }
    }
}
