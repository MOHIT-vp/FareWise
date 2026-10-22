package com.farewise.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineDecoderTest {

    @Test
    fun `decodes the example from Google's algorithm reference`() {
        val points = PolylineDecoder.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")

        assertEquals(3, points.size)
        assertEquals(38.5, points[0].latitude, 1e-5)
        assertEquals(-120.2, points[0].longitude, 1e-5)
        assertEquals(40.7, points[1].latitude, 1e-5)
        assertEquals(-120.95, points[1].longitude, 1e-5)
        assertEquals(43.252, points[2].latitude, 1e-5)
        assertEquals(-126.453, points[2].longitude, 1e-5)
    }

    @Test
    fun `null or empty input gives no points`() {
        assertTrue(PolylineDecoder.decode(null).isEmpty())
        assertTrue(PolylineDecoder.decode("").isEmpty())
    }
}
