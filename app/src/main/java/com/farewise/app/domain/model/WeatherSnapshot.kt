package com.farewise.app.domain.model

import java.time.Instant

/**
 * One reading of current weather at a coarse city-area level.
 *
 * The four channels here line up with the optional weather inputs of the
 * surge LSTM (see `train_surge_lstm.py --with-weather`):
 *
 *   • [temperatureCelsius] — air temperature; correlates with ride demand
 *     (cooler evenings push demand up in Indian cities).
 *   • [precipitationMmPerHour] — rainfall intensity in the last hour. Has
 *     an outsized effect on Bengaluru surge during the monsoon — drivers
 *     refuse short trips, supply collapses.
 *   • [windKmh] — gust strength; weak signal on its own but captures
 *     thunderstorm conditions when paired with precip.
 *   • [visibilityKm] — fog / heavy-rain visibility; a leading indicator of
 *     traffic slowdowns.
 *
 * If a richer feature set is added later, extend this data class and bump
 * the surge model's `feature_order`. The two sides decouple via meta JSON.
 */
data class WeatherSnapshot(
    val temperatureCelsius: Double,
    val precipitationMmPerHour: Double,
    val windKmh: Double,
    val visibilityKm: Double,
    val fetchedAt: Instant
)
