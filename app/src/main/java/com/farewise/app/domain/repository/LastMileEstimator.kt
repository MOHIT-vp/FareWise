package com.farewise.app.domain.repository

import com.farewise.app.domain.model.LatLng
import com.farewise.app.domain.model.MultimodalCombo

/**
 * Estimates last-mile ride options between an arbitrary pair of points.
 *
 * Distinct from [RideDataRepository] for two reasons:
 *   • the rider isn't actually booking these legs through us — Uber/Ola/Rapido
 *     deep links require the *current* GPS position as pickup, not a future
 *     transit alight stop. So we only need to *estimate* the fare/time, not
 *     fetch real-time quotes for the leg.
 *   • we don't want to spam the live ride API with one extra route request
 *     per (transit option, candidate tier) on every dashboard tick. A
 *     formula-based estimator is essentially free and accurate enough for
 *     short hops.
 *
 * Returns a small candidate set (typically bike + auto — the realistic
 * last-mile options in Bengaluru). The use-case picks the best one per
 * combo.
 */
interface LastMileEstimator {
    suspend fun estimate(
        from: LatLng,
        to: LatLng
    ): List<MultimodalCombo.LastMileLeg>
}
