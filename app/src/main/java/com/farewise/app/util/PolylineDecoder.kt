package com.farewise.app.util

import com.farewise.app.domain.model.LatLng

/**
 * Decoder for Google's "Encoded Polyline Algorithm Format".
 *
 * Reference: https://developers.google.com/maps/documentation/utilities/polylinealgorithm
 *
 * Implemented inline (rather than pulling in google-maps-android-utils) so
 * the dependency footprint stays minimal — the algorithm is ~30 lines of
 * straightforward bit manipulation. The output coordinate sequence is what
 * the dashboard map composable uses to draw the actual road geometry instead
 * of a straight line between origin and destination.
 *
 * Returns an empty list for null/blank input or a malformed sequence — the
 * caller should treat that as "no polyline available" and fall back to a
 * synthetic dashed line.
 */
object PolylineDecoder {

    fun decode(encoded: String?): List<LatLng> {
        if (encoded.isNullOrEmpty()) return emptyList()
        val out = ArrayList<LatLng>(encoded.length / 2)

        var index = 0
        var lat = 0
        var lng = 0
        val len = encoded.length

        while (index < len) {
            val (dLat, nextLatIdx) = decodeNext(encoded, index) ?: return emptyList()
            lat += dLat
            index = nextLatIdx

            val (dLng, nextLngIdx) = decodeNext(encoded, index) ?: return emptyList()
            lng += dLng
            index = nextLngIdx

            out += LatLng(latitude = lat * 1e-5, longitude = lng * 1e-5)
        }
        return out
    }

    /**
     * Pull one signed varint from [encoded] starting at [start]. Returns
     * (decoded value, next index) or null if we ran off the end without
     * a terminator — defensive against truncated network bodies.
     */
    private fun decodeNext(encoded: String, start: Int): Pair<Int, Int>? {
        var index = start
        var result = 0
        var shift = 0
        var b: Int
        do {
            if (index >= encoded.length) return null
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        val sign = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        return sign to index
    }
}
