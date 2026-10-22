package com.farewise.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One observed ride-option price point.
 *
 * Rows accumulate as the dashboard polls — every emission from
 * [com.farewise.app.domain.repository.RideDataRepository] is persisted via
 * [com.farewise.app.domain.repository.PriceHistoryRepository]. The
 * [SurgePredictionWorker] later reads the last 2 hours of rows for a given
 * (provider, tier) and feeds them into the TFLite model.
 *
 * Indexed on `(provider, serviceTier, fetchedAtMillis)` so the "last N ticks
 * for tier X" query is a cheap range scan.
 */
@Entity(
    tableName = "price_history",
    indices = [
        Index(value = ["provider", "serviceTier", "fetchedAtMillis"], name = "idx_tier_time")
    ]
)
data class PriceHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val serviceTier: String,
    val priceInr: Double,
    val basePriceInr: Double,
    val surgeMultiplier: Double,
    val fetchedAtMillis: Long
)
