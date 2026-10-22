package com.farewise.app.ui.dashboard.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.farewise.app.domain.model.LatLng
import com.farewise.app.ui.theme.DropGreen
import com.farewise.app.ui.theme.SurgeAmber
import com.farewise.app.ui.theme.SurgeRed
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlin.math.abs
import kotlin.math.max

/** A zone to overlay on the map. [intensity] is 0..1; drives colour + alpha. */
data class HotZoneOverlay(
    val name: String,
    val center: LatLng,
    val intensity: Float
)

/**
 * Real Google Maps view replacing the synthetic canvas.
 *
 * Requirements met:
 *   • GoogleMap draws dark-styled tiles.
 *   • Origin/Destination markers are rendered.
 *   • Polyline drawn between markers with dashing to signify non-exact route.
 *   • Hot zones shown as colored semi-transparent circles.
 *   • Auto-zooms to fit origin and destination when they change.
 */
@Composable
fun GoogleMapView(
    origin: LatLng,
    destination: LatLng,
    hotZones: List<HotZoneOverlay>,
    modifier: Modifier = Modifier,
    /**
     * Decoded driving path. When non-empty, we stroke it as a solid blue
     * line (real route geometry from Google Routes API). When empty —
     * cold start, mock fallback, or network error — the map degrades to
     * the dashed straight-line indicator we used before.
     */
    routePolyline: List<LatLng> = emptyList(),
    /** Rounded inside the dashboard list; [RectangleShape] when full screen. */
    shape: Shape = RoundedCornerShape(22.dp)
) {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(origin.toGmsLatLng(), 12f)
    }

    // Auto-fit bounds whenever the origin or destination changes. We do *not*
    // re-fit when only the polyline updates: every 30s polling tick produces
    // a fresh polyline list reference, and animating the camera every tick
    // would feel jittery. Bounds of origin↔destination are tight enough
    // that any reasonable route still fits.
    LaunchedEffect(origin, destination) {
        val bounds = LatLngBounds.builder()
            .include(origin.toGmsLatLng())
            .include(destination.toGmsLatLng())
            .build()
        cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 100))
    }

    // Minimal UI: no compass, no rotation, no zoom controls, just the map.
    val uiSettings = remember {
        MapUiSettings(
            zoomControlsEnabled = false,
            compassEnabled = false,
            myLocationButtonEnabled = false,
            rotationGesturesEnabled = true,
            scrollGesturesEnabled = true,
            tiltGesturesEnabled = false,
            zoomGesturesEnabled = true
        )
    }

    // Standard dark mode style JSON for Maps
    val mapProperties = remember {
        MapProperties(
            mapStyleOptions = MapStyleOptions(
                """
                [
                  {
                    "elementType": "geometry",
                    "stylers": [{ "color": "#242f3e" }]
                  },
                  {
                    "elementType": "labels.text.fill",
                    "stylers": [{ "color": "#746855" }]
                  },
                  {
                    "elementType": "labels.text.stroke",
                    "stylers": [{ "color": "#242f3e" }]
                  },
                  {
                    "featureType": "administrative.locality",
                    "elementType": "labels.text.fill",
                    "stylers": [{ "color": "#d59563" }]
                  },
                  {
                    "featureType": "poi",
                    "elementType": "labels.text.fill",
                    "stylers": [{ "color": "#d59563" }]
                  },
                  {
                    "featureType": "poi.park",
                    "elementType": "geometry",
                    "stylers": [{ "color": "#263c3f" }]
                  },
                  {
                    "featureType": "poi.park",
                    "elementType": "labels.text.fill",
                    "stylers": [{ "color": "#6b9a76" }]
                  },
                  {
                    "featureType": "road",
                    "elementType": "geometry",
                    "stylers": [{ "color": "#38414e" }]
                  },
                  {
                    "featureType": "road",
                    "elementType": "geometry.stroke",
                    "stylers": [{ "color": "#212a37" }]
                  },
                  {
                    "featureType": "road",
                    "elementType": "labels.text.fill",
                    "stylers": [{ "color": "#9ca5b3" }]
                  },
                  {
                    "featureType": "road.highway",
                    "elementType": "geometry",
                    "stylers": [{ "color": "#746855" }]
                  },
                  {
                    "featureType": "road.highway",
                    "elementType": "geometry.stroke",
                    "stylers": [{ "color": "#1f2835" }]
                  },
                  {
                    "featureType": "road.highway",
                    "elementType": "labels.text.fill",
                    "stylers": [{ "color": "#f3d19c" }]
                  },
                  {
                    "featureType": "transit",
                    "elementType": "geometry",
                    "stylers": [{ "color": "#2f3948" }]
                  },
                  {
                    "featureType": "transit.station",
                    "elementType": "labels.text.fill",
                    "stylers": [{ "color": "#d59563" }]
                  },
                  {
                    "featureType": "water",
                    "elementType": "geometry",
                    "stylers": [{ "color": "#17263c" }]
                  },
                  {
                    "featureType": "water",
                    "elementType": "labels.text.fill",
                    "stylers": [{ "color": "#515c6d" }]
                  },
                  {
                    "featureType": "water",
                    "elementType": "labels.text.stroke",
                    "stylers": [{ "color": "#17263c" }]
                  }
                ]
                """.trimIndent()
            )
        )
    }

    Box(modifier = modifier.clip(shape)) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = mapProperties,
            uiSettings = uiSettings
        ) {
            // Hot zones
            hotZones.forEach { zone ->
                val baseColor = when {
                    zone.intensity >= 0.66f -> SurgeRed
                    zone.intensity >= 0.33f -> SurgeAmber
                    else -> DropGreen
                }
                Circle(
                    center = zone.center.toGmsLatLng(),
                    radius = 2000.0, // approx 2km
                    fillColor = baseColor.copy(alpha = 0.3f),
                    strokeWidth = 0f
                )
            }

            // Route path. Prefer the real driving polyline when the Routes
            // API has returned one; otherwise fall back to a dashed straight
            // line so the user still sees an indicator while we wait.
            if (routePolyline.size >= 2) {
                Polyline(
                    points = routePolyline.map { it.toGmsLatLng() },
                    color = Color(0xFF4FC3F7), // a bright cyan that pops against the dark map
                    width = 14f,
                    geodesic = false
                )
            } else {
                Polyline(
                    points = listOf(origin.toGmsLatLng(), destination.toGmsLatLng()),
                    color = Color.White,
                    width = 12f,
                    pattern = listOf(Dash(30f), Gap(20f))
                )
            }

            // Markers
            Marker(
                state = MarkerState(position = origin.toGmsLatLng()),
                title = "Pickup",
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
            )

            Marker(
                state = MarkerState(position = destination.toGmsLatLng()),
                title = "Drop",
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
            )
        }
    }
}

private fun LatLng.toGmsLatLng() = com.google.android.gms.maps.model.LatLng(latitude, longitude)
