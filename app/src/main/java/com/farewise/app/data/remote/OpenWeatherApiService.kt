package com.farewise.app.data.remote

import com.farewise.app.data.remote.dto.OpenWeatherResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit binding for the OpenWeatherMap "current weather" endpoint.
 *
 *   GET https://api.openweathermap.org/data/2.5/weather?lat=…&lon=…&units=metric&appid=…
 *
 * We use the v2.5 endpoint (free tier) rather than the newer paid One Call —
 * the free tier covers ~60 calls/min, more than enough for a single user.
 *
 * The API key is appended via an [@Query] rather than a header because OWM
 * doesn't accept the X-API-Key style — they only take the `appid` URL param.
 */
interface OpenWeatherApiService {

    @GET("data/2.5/weather")
    suspend fun currentWeather(
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double,
        @Query("appid") apiKey: String,
        @Query("units") units: String = "metric"
    ): OpenWeatherResponse

    companion object {
        const val BASE_URL = "https://api.openweathermap.org/"
    }
}
