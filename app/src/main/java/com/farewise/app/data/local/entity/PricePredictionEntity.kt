package com.farewise.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A stored forecast produced by the TFLite predictor.
 *
 * Prediction vectors are stored as a CSV string instead of a separate
 * join table because:
 *   • each row is always the same fixed length (6 points, 30 minutes),
 *   • we never query for individual points — the consumer always wants
 *     the full curve,
 *   • avoiding a child table keeps the DB migration story trivial.
 */
@Entity(
    tableName = "price_prediction",
    indices = [
        Index(value = ["provider", "serviceTier"], name = "idx_prediction_tier")
    ]
)
data class PricePredictionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val serviceTier: String,
    val generatedAtMillis: Long,
    val stepMinutes: Int,
    val horizonPointsCsv: String,
    val confidence: Float
) {
    fun pointsInr(): List<Double> =
        horizonPointsCsv.split(',').mapNotNull { it.toDoubleOrNull() }

    companion object {
        fun encodePoints(points: List<Double>): String =
            points.joinToString(",")
    }
}
