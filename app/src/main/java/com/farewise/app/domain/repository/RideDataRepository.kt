package com.farewise.app.domain.repository

import com.farewise.app.domain.model.LatLng
import com.farewise.app.domain.model.RideOption
import com.farewise.app.domain.model.RoutedRideQuotes
import kotlinx.coroutines.flow.Flow

/**
 * Contract for fetching live ride-hailing quotes from one or more providers.
 *
 * Implementations may aggregate real partner APIs or produce mock data;
 * consumers (ViewModels, use-cases) must depend only on this interface so
 * the data source can be swapped without touching UI or domain logic.
 *
 * A single call to [observeRideOptions] is expected to re-emit on its own
 * cadence (simulating or reflecting partner-app polling) until the
 * collector cancels. Each emission should be the *full* set of currently
 * available options — callers replace rather than merge.
 */
interface RideDataRepository {

    /**
     * Cold flow that emits the latest ride options + driving polyline for
     * the given trip at each polling tick. Collection should be scoped to
     * the screen that needs the data so polling stops automatically.
     */
    fun observeRideOptions(origin: LatLng, destination: LatLng): Flow<RoutedRideQuotes>

    /**
     * One-shot fetch used for explicit pull-to-refresh / "retry" flows.
     * Shares pricing + routing logic with [observeRideOptions] so a manual
     * refresh agrees with the next polled tick.
     */
    suspend fun fetchRideOptions(origin: LatLng, destination: LatLng): RoutedRideQuotes
}
