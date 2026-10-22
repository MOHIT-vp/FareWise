package com.farewise.app.domain.model

/**
 * Minimal geographic point.
 *
 * Kept as a plain data class (instead of pulling in Play Services Maps types)
 * so the domain layer has zero Android framework dependencies and remains
 * trivially unit-testable on the JVM.
 */
data class LatLng(
    val latitude: Double,
    val longitude: Double
) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
    }
}
