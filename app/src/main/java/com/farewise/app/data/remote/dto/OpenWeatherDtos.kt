package com.farewise.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Subset of the OpenWeatherMap "current weather" response we actually use.
 *
 * The full response has dozens of fields (timezones, sunrise/sunset, sys
 * country code, etc.) — we model only what feeds the surge LSTM's optional
 * weather channels: temperature, recent precip, wind speed, visibility.
 *
 * Numbers come back in metric units when the request specifies `units=metric`:
 *   • [WeatherMain.temp]          °C
 *   • [WeatherWind.speed]         m/s
 *   • [WeatherSnapshot.visibility] metres (capped at 10000)
 *   • [WeatherRain.lastHourMm]    mm/h, optional (only present when raining)
 */

@JsonClass(generateAdapter = false)
data class OpenWeatherResponse(
    val main: WeatherMain?,
    val wind: WeatherWind?,
    val rain: WeatherRain?,
    val snow: WeatherRain?,
    val visibility: Int?
)

@JsonClass(generateAdapter = false)
data class WeatherMain(
    val temp: Double?,
    val humidity: Int?
)

@JsonClass(generateAdapter = false)
data class WeatherWind(
    val speed: Double?,
    val gust: Double?
)

/**
 * Used for both `rain` and `snow` since OWM ships the same shape for both.
 * The "last hour" key is literally `1h`, which Kotlin/Moshi can't bind to a
 * property name — we use [@Json] to remap.
 */
@JsonClass(generateAdapter = false)
data class WeatherRain(
    @param:Json(name = "1h") val lastHourMm: Double?
)
