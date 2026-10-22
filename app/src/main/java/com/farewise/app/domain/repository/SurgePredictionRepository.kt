package com.farewise.app.domain.repository

import com.farewise.app.domain.model.PricePrediction
import com.farewise.app.domain.model.RideOption
import kotlinx.coroutines.flow.Flow

/**
 * Contract for running on-device surge forecasting and reading the cached
 * forecasts the background worker has already produced.
 *
 * The predictor exposes *both* sides because they share the same loaded
 * interpreter + metadata — forcing them behind a second interface would
 * just duplicate that state.
 */
interface SurgePredictionRepository {

    /**
     * Run one inference pass. Returns null when the provided [recentHistory]
     * is shorter than the model's required input window — callers should
     * degrade gracefully (hide prediction UI) rather than block the user.
     */
    suspend fun predict(recentHistory: List<RideOption>): PricePrediction?

    /**
     * Stream of the latest forecast per (provider, serviceTier).
     *
     * Emits whenever the background worker persists a new forecast row.
     * The dashboard VM collects this alongside the live ride feed to derive
     * Phase-4 "wait vs book now" advice.
     */
    fun observeLatestPredictions(): Flow<List<PricePrediction>>
}
