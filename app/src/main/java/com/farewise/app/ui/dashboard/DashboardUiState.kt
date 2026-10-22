package com.farewise.app.ui.dashboard

import com.farewise.app.domain.model.LatLng
import com.farewise.app.domain.model.MultimodalCombo
import com.farewise.app.domain.model.RideAdvice
import com.farewise.app.domain.model.RideOption
import com.farewise.app.domain.model.RideProvider
import com.farewise.app.domain.model.RideRankingCategory
import com.farewise.app.domain.model.TransitSchedule
import com.farewise.app.domain.repository.PriceHistoryRepository.TierRef
import com.farewise.app.ui.common.BengaluruPlaces
import com.farewise.app.ui.common.Place

/**
 * Single source of truth for the dashboard screen.
 *
 * Exposed as an immutable data class so Compose recomposes cleanly on copy,
 * and so snapshot testing the UI is just "construct a state, render it".
 */
data class DashboardUiState(
    val origin: Place = BengaluruPlaces.DEFAULT_ORIGIN,
    val destination: Place = BengaluruPlaces.DEFAULT_DESTINATION,
    val rankingCategory: RideRankingCategory = RideRankingCategory.RECOMMENDED,
    val rankedRides: List<RideOption> = emptyList(),
    val transitSchedules: List<TransitSchedule> = emptyList(),
    val combos: List<MultimodalCombo> = emptyList(),
    /**
     * Decoded route geometry from the Routes API. Empty when the live API
     * hasn't returned yet (cold start, network error, missing key) — the
     * map then renders a dashed straight-line fallback.
     */
    val routePolyline: List<LatLng> = emptyList(),
    val advice: RideAdvice? = null,
    val installedProviders: Set<RideProvider> = emptySet(),
    /**
     * Recent price series per (provider, tier), oldest first. Drives the
     * sparkline on each ride card. Empty until at least one polling tick has
     * been persisted to Room.
     */
    val priceHistoryByTier: Map<TierRef, List<Double>> = emptyMap(),
    val isLoading: Boolean = true,
    /** True while a user-initiated refresh (pull / button / retry) is in flight. */
    val isRefreshing: Boolean = false,
    val isLocating: Boolean = false,
    val errorMessage: String? = null,

    /** Results from forward-geocoding a user-typed address query. */
    val addressSearchResults: List<Place> = emptyList(),
    /** True while a geocoding request is in flight. */
    val isSearching: Boolean = false,

    /** Reverse-geocoded address shown in the map picker bottom panel. */
    val mapPickerResolvedAddress: String? = null,
    /** True while the map picker is reverse-geocoding a camera position. */
    val isResolvingMapAddress: Boolean = false
) {
    /** Convenience — top card highlighted in the "hero" slot under the map. */
    val topRide: RideOption? get() = rankedRides.firstOrNull()
}

/**
 * Which location field the user is currently editing from the picker sheet.
 * Kept separate from [DashboardUiState] because it's transient UI scaffolding,
 * not app state that needs to survive process death.
 */
enum class LocationField { ORIGIN, DESTINATION }
