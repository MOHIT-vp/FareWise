package com.farewise.app.domain.model

/**
 * One polling tick's worth of ride data: the per-tier quotes plus the
 * shared driving polyline they were priced from.
 *
 * Pulled into its own type because [polyline] is a route-level concept (the
 * 9 ride options share the same path), not a per-option attribute. Stuffing
 * it into [RideOption] would duplicate identical points 9 times and make
 * the journal/booking entity's mapping awkward.
 *
 * [polyline] is empty when:
 *   • the live Routes API call failed (mock fallback path), or
 *   • the response had no encoded polyline string for some reason.
 *
 * The map composable reads that as "no real route — fall back to the
 * dashed straight line we used to draw before".
 */
data class RoutedRideQuotes(
    val options: List<RideOption>,
    val polyline: List<LatLng>
)
