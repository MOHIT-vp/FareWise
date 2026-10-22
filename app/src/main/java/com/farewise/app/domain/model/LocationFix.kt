package com.farewise.app.domain.model

/**
 * Outcome of a one-shot location request.
 *
 * Distinguishing between the failure modes lets the UI surface an actionable
 * message — e.g. "turn on Location" vs "grant permission" vs "try again
 * outdoors" — instead of a single opaque "couldn't get location" error.
 */
sealed interface LocationFix {
    data class Success(val coordinates: LatLng) : LocationFix

    /** Caller hasn't been granted ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION. */
    data object PermissionDenied : LocationFix

    /** System-level Location Services are turned off — permission is moot. */
    data object LocationDisabled : LocationFix

    /** Permission + services OK, but no fix materialised within the timeout. */
    data object Unavailable : LocationFix
}
