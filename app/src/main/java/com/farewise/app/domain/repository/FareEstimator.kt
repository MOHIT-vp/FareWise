package com.farewise.app.domain.repository

import com.farewise.app.domain.model.FareEstimate
import com.farewise.app.domain.model.RideProvider
import java.time.Duration

/**
 * On-device ML fare estimator.
 *
 * Inference is fast (sub-millisecond MLP) so this is exposed as a synchronous
 * suspend boundary — callers in the repository layer can run it in a tight
 * loop over the 9-tier catalog on every poll tick without breaking a sweat.
 *
 * Returns null when the model assets are missing, the input is malformed,
 * or the meta JSON is unreadable. Callers should degrade gracefully and
 * keep showing the catalog-derived [com.farewise.app.domain.model.RideOption.priceInr]
 * as the only number.
 */
interface FareEstimator {
    suspend fun estimate(
        provider: RideProvider,
        serviceTier: String,
        distanceKm: Double,
        tripDuration: Duration
    ): FareEstimate?
}
