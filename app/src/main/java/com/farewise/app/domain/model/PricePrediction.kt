package com.farewise.app.domain.model

import java.time.Duration
import java.time.Instant

/**
 * Forward-looking surge-price forecast produced by the on-device TFLite model.
 *
 * Each element of [pointsInr] is the predicted price for the given
 * [provider]+[serviceTier] pair at offset `index * stepDuration` relative
 * to [generatedAt]. The list is always non-empty and in chronological order.
 *
 * [confidence] is the model's own reported confidence (0..1). The decision
 * engine in Phase 4 uses it to suppress noisy advice when the model is
 * unsure.
 */
data class PricePrediction(
    val provider: RideProvider,
    val serviceTier: String,
    val generatedAt: Instant,
    val stepDuration: Duration,
    val pointsInr: List<Double>,
    val confidence: Float
) {
    init {
        require(pointsInr.isNotEmpty()) { "pointsInr must not be empty" }
        require(confidence in 0f..1f) { "confidence out of range: $confidence" }
        require(!stepDuration.isNegative && !stepDuration.isZero) { "stepDuration must be positive" }
    }

    /** Total horizon covered by this prediction. */
    val horizon: Duration get() = stepDuration.multipliedBy(pointsInr.size.toLong())

    val predictedMinInr: Double get() = pointsInr.min()
    val predictedMaxInr: Double get() = pointsInr.max()

    /** Offset from [generatedAt] at which the predicted price is lowest. */
    fun timeToCheapest(): Duration {
        val minIdx = pointsInr.withIndex().minBy { it.value }.index
        return stepDuration.multipliedBy(minIdx.toLong())
    }

    /** Price predicted at a specific offset; clamps to horizon bounds. */
    fun priceAt(offset: Duration): Double {
        val idx = (offset.toMillis() / stepDuration.toMillis())
            .toInt()
            .coerceIn(0, pointsInr.lastIndex)
        return pointsInr[idx]
    }
}
