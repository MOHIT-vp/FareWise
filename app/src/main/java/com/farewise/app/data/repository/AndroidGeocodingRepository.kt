package com.farewise.app.data.repository

import android.content.Context
import android.location.Geocoder
import com.farewise.app.domain.model.LatLng
import com.farewise.app.domain.repository.GeocodingRepository
import com.farewise.app.ui.common.Place
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.util.Locale
import javax.inject.Inject

/**
 * [GeocodingRepository] backed by Android's built-in [Geocoder].
 *
 * This avoids pulling in the Google Places SDK (and its billing) for simple
 * address lookups. [Geocoder] uses the same Google backend on devices with
 * Play Services — it's just a thinner wrapper.
 *
 * All calls run on [Dispatchers.IO] because [Geocoder] performs synchronous
 * network I/O under the hood.
 */
class AndroidGeocodingRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) : GeocodingRepository {

    private val geocoder: Geocoder by lazy {
        Geocoder(context, Locale.getDefault())
    }

    override suspend fun searchByAddress(query: String, maxResults: Int): List<Place> {
        if (query.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(query, maxResults)
                addresses?.mapNotNull { address ->
                    val lat = address.latitude
                    val lon = address.longitude
                    if (lat == 0.0 && lon == 0.0) return@mapNotNull null

                    val name = buildAddressName(address)
                    val area = buildAreaLabel(address)

                    Place(
                        name = name,
                        area = area,
                        coordinates = LatLng(lat, lon)
                    )
                } ?: emptyList()
            } catch (e: IOException) {
                Timber.w(e, "Forward geocoding failed for query: %s", query)
                emptyList()
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "Invalid geocoding query: %s", query)
                emptyList()
            }
        }
    }

    override suspend fun reverseGeocode(latLng: LatLng): Place? {
        return withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                val address = addresses?.firstOrNull() ?: return@withContext null

                val name = buildAddressName(address)
                val area = buildAreaLabel(address)

                Place(
                    name = name,
                    area = area,
                    coordinates = latLng
                )
            } catch (e: IOException) {
                Timber.w(e, "Reverse geocoding failed for %s", latLng)
                null
            }
        }
    }

    /**
     * Build a concise, human-readable name from an [android.location.Address].
     *
     * Priority: feature name (POI/street number) → first address line →
     * sub-locality → locality → fallback to coordinates-style label.
     */
    private fun buildAddressName(address: android.location.Address): String {
        // getAddressLine(0) typically yields the most useful single-line summary
        val firstLine = address.getAddressLine(0)
        if (!firstLine.isNullOrBlank()) {
            // Trim to a reasonable length — full lines can be very long
            val parts = firstLine.split(",").map { it.trim() }
            return parts.take(2).joinToString(", ")
        }
        return address.featureName
            ?: address.subLocality
            ?: address.locality
            ?: "%.4f, %.4f".format(address.latitude, address.longitude)
    }

    /**
     * Build a secondary area/city label for the [Place.area] field.
     */
    private fun buildAreaLabel(address: android.location.Address): String {
        return listOfNotNull(
            address.subLocality,
            address.locality,
            address.adminArea
        ).distinct().take(2).joinToString(", ").ifBlank { "Unknown area" }
    }
}
