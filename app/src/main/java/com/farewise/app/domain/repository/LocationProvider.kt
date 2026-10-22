package com.farewise.app.domain.repository

import com.farewise.app.domain.model.LocationFix

/**
 * Abstraction over the system location source.
 *
 * Kept interface-only in the domain layer so tests (and the in-app
 * preset location picker) don't have to touch Google Play Services.
 */
interface LocationProvider {

    /**
     * Returns the current best-known location wrapped in a [LocationFix].
     *
     * The sealed result lets the caller distinguish between a missing
     * permission, disabled system Location Services, and a transient
     * "no fix in time" failure — each warrants a different user prompt.
     */
    suspend fun currentLocation(): LocationFix
}
