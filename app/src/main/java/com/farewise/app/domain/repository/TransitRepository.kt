package com.farewise.app.domain.repository

import com.farewise.app.domain.model.LatLng
import com.farewise.app.domain.model.TransitSchedule
import kotlinx.coroutines.flow.Flow

/**
 * Contract for fetching real-time public-transit departures that serve the
 * rider's route. Sits alongside [RideDataRepository] so the dashboard can
 * compose a truly unified view of "every way to get there".
 */
interface TransitRepository {
    fun observeTransitSchedules(origin: LatLng, destination: LatLng): Flow<List<TransitSchedule>>
    suspend fun fetchTransitSchedules(origin: LatLng, destination: LatLng): List<TransitSchedule>
}
