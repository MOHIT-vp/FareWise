package com.farewise.app.domain.repository

import com.farewise.app.domain.model.RideOption
import com.farewise.app.domain.model.RideProvider
import kotlinx.coroutines.flow.Flow

/**
 * Append-only price log + reader for the TFLite predictor pipeline.
 *
 * The dashboard VM writes observed prices here on every tick so a
 * realistic 2-hour window is always available; the surge worker reads
 * by (provider, tier) to feed the model.
 */
interface PriceHistoryRepository {

    /** Persist a batch of observed options (one polling tick). */
    suspend fun record(options: List<RideOption>)

    /**
     * Last [sinceMillis]-ms of history for a specific tier, ordered
     * oldest → newest so the caller can feed it straight into a model
     * window.
     */
    suspend fun windowFor(
        provider: RideProvider,
        serviceTier: String,
        sinceMillis: Long
    ): List<HistoryPoint>

    /** All distinct (provider, tier) pairs seen since [sinceMillis]. */
    suspend fun distinctTiersSince(sinceMillis: Long): List<TierRef>

    /** Drop rows older than [cutoffMillis] — called by the worker to keep the DB small. */
    suspend fun prune(cutoffMillis: Long)

    /**
     * Reactive sparkline feed. Emits the recent price series for every tier
     * grouped as `Map<TierRef, List<Double>>`, refreshed whenever new rows
     * are inserted.
     *
     * The dashboard collects this once per session and pushes the matching
     * series down into each [com.farewise.app.ui.dashboard.components.RideOptionCard]
     * so the rider can see *why* the surge banner reads "wait for drop".
     */
    fun observeRecentPricesByTier(sinceMillis: Long): Flow<Map<TierRef, List<Double>>>

    data class HistoryPoint(
        val priceInr: Double,
        val surgeMultiplier: Double,
        val fetchedAtMillis: Long
    )

    data class TierRef(val provider: RideProvider, val serviceTier: String)
}
