package com.farewise.app.ui.common

import com.farewise.app.domain.model.LatLng

/**
 * A named geographic point the user can pick from the location sheet.
 *
 * Kept in the `ui.common` layer (not `domain`) because it's an affordance
 * of this particular Android UI — a real app would swap it for a
 * Places-API-backed autocomplete.
 */
data class Place(
    val name: String,
    val area: String,
    val coordinates: LatLng
) {
    val fullLabel: String get() = "$name, $area"
}

/**
 * Preset Bengaluru landmarks used to seed the source/destination picker.
 *
 * Coordinates are chosen so they sit inside (or next to) the hot-zone
 * bounding boxes in [MockRideDataRepository], which is what makes a live
 * demo show realistic surge when, for example, "Koramangala → MG Road"
 * is selected during evening rush.
 */
object BengaluruPlaces {

    val KORAMANGALA = Place(
        name = "Koramangala 5th Block",
        area = "Koramangala",
        coordinates = LatLng(12.9352, 77.6245)
    )
    val WHITEFIELD = Place(
        name = "Whitefield ITPL",
        area = "Whitefield",
        coordinates = LatLng(12.9855, 77.7361)
    )
    val MG_ROAD = Place(
        name = "MG Road Metro",
        area = "Brigade Rd",
        coordinates = LatLng(12.9756, 77.6068)
    )
    val INDIRANAGAR = Place(
        name = "Indiranagar 100 Ft Rd",
        area = "Indiranagar",
        coordinates = LatLng(12.9719, 77.6412)
    )
    val HSR_LAYOUT = Place(
        name = "HSR Layout Sector 1",
        area = "HSR",
        coordinates = LatLng(12.9141, 77.6476)
    )
    val ELECTRONIC_CITY = Place(
        name = "Electronic City Phase 1",
        area = "Electronic City",
        coordinates = LatLng(12.8456, 77.6603)
    )
    val JP_NAGAR = Place(
        name = "JP Nagar 3rd Phase",
        area = "JP Nagar",
        coordinates = LatLng(12.9081, 77.5831)
    )
    val MARATHAHALLI = Place(
        name = "Marathahalli Bridge",
        area = "Marathahalli",
        coordinates = LatLng(12.9591, 77.6974)
    )
    val KEMPEGOWDA_AIRPORT = Place(
        name = "Kempegowda Intl Airport",
        area = "Devanahalli",
        coordinates = LatLng(13.1986, 77.7066)
    )
    val MAJESTIC = Place(
        name = "Majestic (KSR Bengaluru)",
        area = "Majestic",
        coordinates = LatLng(12.9783, 77.5712)
    )

    val ALL: List<Place> = listOf(
        KORAMANGALA,
        INDIRANAGAR,
        MG_ROAD,
        HSR_LAYOUT,
        WHITEFIELD,
        MARATHAHALLI,
        JP_NAGAR,
        ELECTRONIC_CITY,
        MAJESTIC,
        KEMPEGOWDA_AIRPORT
    )

    /** Sensible defaults for the first dashboard paint — peak-surge pairing. */
    val DEFAULT_ORIGIN: Place = KORAMANGALA
    val DEFAULT_DESTINATION: Place = WHITEFIELD
}
