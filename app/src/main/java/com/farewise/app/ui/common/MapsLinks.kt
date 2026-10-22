package com.farewise.app.ui.common

import com.farewise.app.domain.model.LatLng
import java.util.Locale

/**
 * Builds Google Maps universal links. These open the Maps app when it's
 * installed and fall back to the browser otherwise, so no package check is
 * needed before launching them.
 */
object MapsLinks {

    /** Public-transit directions between two points. */
    fun transitDirections(origin: LatLng, destination: LatLng): String =
        "https://www.google.com/maps/dir/?api=1" +
            "&origin=${origin.asParam()}" +
            "&destination=${destination.asParam()}" +
            "&travelmode=transit"

    private fun LatLng.asParam(): String =
        "%.6f,%.6f".format(Locale.ENGLISH, latitude, longitude)
}
