package com.farewise.app.ui.common

import com.farewise.app.domain.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class FormattersTest {

    @Test
    fun `inr rounds to whole rupees`() {
        assertEquals("₹182", Formatters.inr(181.6))
        assertEquals("₹0", Formatters.inr(0.0))
    }

    @Test
    fun `duration is compact and rounds to the nearest minute`() {
        assertEquals("now", Formatters.duration(Duration.ZERO))
        assertEquals("now", Formatters.duration(Duration.ofSeconds(-30)))
        assertEquals("4 min", Formatters.duration(Duration.ofSeconds(4 * 60 + 10)))
        assertEquals("5 min", Formatters.duration(Duration.ofSeconds(4 * 60 + 40)))
        assertEquals("1 hr", Formatters.duration(Duration.ofMinutes(60)))
        assertEquals("1 hr 12 min", Formatters.duration(Duration.ofMinutes(72)))
    }

    @Test
    fun `distance switches from metres to kilometres`() {
        assertEquals("850 m", Formatters.distance(0.85))
        assertEquals("12.4 km", Formatters.distance(12.43))
    }

    @Test
    fun `maps link carries both endpoints and transit mode`() {
        val url = MapsLinks.transitDirections(
            LatLng(12.9716, 77.5946),
            LatLng(12.9352, 77.6245)
        )
        assertEquals(
            "https://www.google.com/maps/dir/?api=1" +
                "&origin=12.971600,77.594600" +
                "&destination=12.935200,77.624500" +
                "&travelmode=transit",
            url
        )
    }
}
