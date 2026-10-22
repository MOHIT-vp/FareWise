package com.farewise.app.ui.dashboard

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.farewise.app.data.booking.ProviderDeepLinker
import com.farewise.app.domain.model.LatLng
import com.farewise.app.domain.model.RideOption
import com.farewise.app.domain.model.RideRankingCategory
import com.farewise.app.domain.model.TransitSchedule
import com.farewise.app.domain.repository.PriceHistoryRepository.TierRef
import com.farewise.app.ui.common.Formatters
import com.farewise.app.ui.common.MapsLinks
import com.farewise.app.ui.dashboard.components.AdviceBanner
import com.farewise.app.ui.dashboard.components.GoogleMapView
import com.farewise.app.ui.dashboard.components.HotZoneOverlay
import com.farewise.app.ui.dashboard.components.LocationPickerSheet
import com.farewise.app.ui.dashboard.components.LocationSearchBar
import com.farewise.app.ui.dashboard.components.MapPickerScreen
import com.farewise.app.ui.dashboard.components.MultimodalComboCard
import com.farewise.app.ui.dashboard.components.RankingTabs
import com.farewise.app.ui.dashboard.components.RideCardSkeleton
import com.farewise.app.ui.dashboard.components.RideOptionCard
import com.farewise.app.ui.dashboard.components.TransitDetailSheet
import com.farewise.app.ui.dashboard.components.TransitScheduleCard

/**
 * The unified dashboard — this is the app's home.
 *
 * Layout, top-to-bottom:
 *
 *   ┌────────────────────────────────────┐
 *   │  App bar (title + refresh)         │
 *   │  ┌──────────────────────────────┐  │
 *   │  │  Route map + best-ride chip  │  │   ← tap ⤢ for full screen
 *   │  └──────────────────────────────┘  │
 *   │  ┌──────────────────────────────┐  │
 *   │  │  Source / destination search │  │
 *   │  └──────────────────────────────┘  │
 *   │  Advice banner (wait / book now)   │
 *   │  Cheapest | Fastest | Recommended  │   ← ranking tabs
 *   │  Ride cards (skeletons while       │
 *   │  loading) · combos · transit       │   ← transit opens a detail sheet
 *   └────────────────────────────────────┘
 *
 * The whole list supports pull-to-refresh. The screen is a thin shell around
 * [DashboardViewModel]: all state lives in the VM and user intents are
 * dispatched back through method references.
 *
 * [onImmersiveChange] tells the host when a full-screen surface (map picker
 * or expanded map) is showing, so it can hide the bottom navigation bar.
 */
@Composable
fun DashboardScreen(
    onImmersiveChange: (Boolean) -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val bookingEvent by viewModel.bookingEvents.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current

    var pickerField by remember { mutableStateOf<LocationField?>(null) }
    var selectedTransit by remember { mutableStateOf<TransitSchedule?>(null) }
    var showFullMap by remember { mutableStateOf(false) }

    // Map picker state
    var showMapPicker by remember { mutableStateOf(false) }
    var mapPickerField by remember { mutableStateOf(LocationField.ORIGIN) }

    val isImmersive = showMapPicker || showFullMap
    LaunchedEffect(isImmersive) { onImmersiveChange(isImmersive) }

    // System back closes full-screen surfaces instead of leaving the app.
    BackHandler(enabled = showMapPicker) {
        viewModel.clearMapPickerState()
        showMapPicker = false
    }
    BackHandler(enabled = showFullMap) { showFullMap = false }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            viewModel.onUseCurrentLocation()
        } else {
            viewModel.onLocationPermissionDenied()
        }
    }

    LaunchedEffect(bookingEvent) {
        val event = bookingEvent ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(event.toMessage())
        viewModel.onBookingEventConsumed()
    }

    // Re-check PackageManager whenever the user returns to this screen — they
    // may have just installed a partner app via our Play Store hand-off.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshInstalledProviders()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hotZones = rememberHotZoneOverlays(state)

    when {
        showMapPicker -> MapPickerScreen(
            field = mapPickerField,
            initialCoordinates = when (mapPickerField) {
                LocationField.ORIGIN -> state.origin.coordinates
                LocationField.DESTINATION -> state.destination.coordinates
            },
            onLocationConfirmed = { latLng ->
                viewModel.onMapLocationPicked(latLng, mapPickerField)
                viewModel.clearMapPickerState()
                showMapPicker = false
            },
            onReverseGeocode = { latLng ->
                viewModel.reverseGeocodeForMapPicker(latLng)
            },
            resolvedAddress = state.mapPickerResolvedAddress,
            isResolving = state.isResolvingMapAddress,
            onDismiss = {
                viewModel.clearMapPickerState()
                showMapPicker = false
            }
        )

        showFullMap -> FullScreenMap(
            state = state,
            hotZones = hotZones,
            onClose = { showFullMap = false }
        )

        else -> Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            // The top bar pads for the status bar itself and the host's
            // bottom navigation bar pads for the system nav bar.
            contentWindowInsets = WindowInsets(0),
            topBar = {
                DashboardTopBar(
                    isBusy = state.isLoading || state.isRefreshing,
                    onRefreshClick = viewModel::refresh
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            DashboardContent(
                state = state,
                hotZones = hotZones,
                contentPadding = padding,
                onRefresh = viewModel::refresh,
                onExpandMap = { showFullMap = true },
                onOriginClick = { pickerField = LocationField.ORIGIN },
                onDestinationClick = { pickerField = LocationField.DESTINATION },
                onSwapClick = viewModel::onSwapLocations,
                onCategorySelected = viewModel::onCategorySelected,
                onRideClick = viewModel::onRideClicked,
                onAdviceClick = viewModel::onAdviceClicked,
                onTransitClick = { selectedTransit = it }
            )
        }
    }

    pickerField?.let { field ->
        LocationPickerSheet(
            field = field,
            onPlaceSelected = { place ->
                when (field) {
                    LocationField.ORIGIN -> viewModel.onOriginSelected(place)
                    LocationField.DESTINATION -> viewModel.onDestinationSelected(place)
                }
                viewModel.clearSearchResults()
                pickerField = null
            },
            onUseCurrentLocation = {
                viewModel.clearSearchResults()
                pickerField = null
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            },
            onPickOnMap = {
                viewModel.clearSearchResults()
                mapPickerField = field
                pickerField = null
                showMapPicker = true
            },
            onSearchQuery = { query ->
                viewModel.searchAddress(query)
            },
            searchResults = state.addressSearchResults,
            isSearching = state.isSearching,
            isLocating = state.isLocating,
            onDismiss = {
                viewModel.clearSearchResults()
                pickerField = null
            }
        )
    }

    selectedTransit?.let { schedule ->
        TransitDetailSheet(
            schedule = schedule,
            cheapestRideInr = state.rankedRides.minOfOrNull { it.priceInr },
            onOpenInMaps = {
                uriHandler.openUri(
                    MapsLinks.transitDirections(
                        state.origin.coordinates,
                        state.destination.coordinates
                    )
                )
            },
            onDismiss = { selectedTransit = null }
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun DashboardTopBar(
    isBusy: Boolean,
    onRefreshClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "FareWise",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Compare rides & transit, beat the surge",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    IconButton(onClick = onRefreshClick) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh prices",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardContent(
    state: DashboardUiState,
    hotZones: List<HotZoneOverlay>,
    contentPadding: PaddingValues,
    onRefresh: () -> Unit,
    onExpandMap: () -> Unit,
    onOriginClick: () -> Unit,
    onDestinationClick: () -> Unit,
    onSwapClick: () -> Unit,
    onCategorySelected: (RideRankingCategory) -> Unit,
    onRideClick: (RideOption) -> Unit,
    onAdviceClick: () -> Unit,
    onTransitClick: (TransitSchedule) -> Unit
) {
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "map") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                ) {
                    GoogleMapView(
                        origin = state.origin.coordinates,
                        destination = state.destination.coordinates,
                        routePolyline = state.routePolyline,
                        hotZones = hotZones,
                        modifier = Modifier.fillMaxSize()
                    )
                    FilledTonalIconButton(
                        onClick = onExpandMap,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                    ) {
                        Icon(Icons.Filled.OpenInFull, contentDescription = "Expand map")
                    }
                    state.topRide?.let { best ->
                        // Top-left keeps the Google logo (bottom-left) visible.
                        BestRideChip(
                            option = best,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(10.dp)
                        )
                    }
                }
            }
            item(key = "search") {
                LocationSearchBar(
                    originLabel = state.origin.fullLabel,
                    destinationLabel = state.destination.fullLabel,
                    onOriginClick = onOriginClick,
                    onDestinationClick = onDestinationClick,
                    onSwapClick = onSwapClick
                )
            }
            item(key = "advice") {
                AdviceBanner(
                    advice = state.advice,
                    onClick = onAdviceClick,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item(key = "rides-header") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Ride options")
                    RankingTabs(
                        selected = state.rankingCategory,
                        onSelected = onCategorySelected
                    )
                }
            }

            state.errorMessage?.let { message ->
                item(key = "error") {
                    ErrorBanner(message = message, onRetry = onRefresh)
                }
            }

            when {
                state.rankedRides.isEmpty() && state.isLoading ->
                    items(SKELETON_COUNT, key = { "skeleton-$it" }) { RideCardSkeleton() }

                state.rankedRides.isEmpty() ->
                    item(key = "empty") { EmptyRidesPlaceholder() }
            }

            items(state.rankedRides, key = { it.id }) { option ->
                val historyKey = TierRef(option.provider, option.serviceTier)
                RideOptionCard(
                    option = option,
                    isHero = option === state.topRide,
                    isProviderInstalled = option.provider in state.installedProviders,
                    onClick = { onRideClick(option) },
                    priceHistory = state.priceHistoryByTier[historyKey].orEmpty(),
                    // Cards glide to their new position when the ranking tab changes.
                    modifier = Modifier.animateItem()
                )
            }

            if (state.combos.isNotEmpty()) {
                item(key = "combos-header") {
                    SectionHeader("Save with combos", Modifier.padding(top = 4.dp))
                }
                items(state.combos, key = { it.id }) { combo ->
                    MultimodalComboCard(combo = combo, modifier = Modifier.animateItem())
                }
            }

            if (state.transitSchedules.isNotEmpty()) {
                item(key = "transit-header") {
                    SectionHeader("Public transit", Modifier.padding(top = 4.dp))
                }
                items(state.transitSchedules, key = { it.id }) { schedule ->
                    TransitScheduleCard(
                        schedule = schedule,
                        onClick = { onTransitClick(schedule) },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            item(key = "bottom-spacer") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun FullScreenMap(
    state: DashboardUiState,
    hotZones: List<HotZoneOverlay>,
    onClose: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        GoogleMapView(
            origin = state.origin.coordinates,
            destination = state.destination.coordinates,
            routePolyline = state.routePolyline,
            hotZones = hotZones,
            shape = RectangleShape,
            modifier = Modifier.fillMaxSize()
        )
        FilledTonalIconButton(
            onClick = onClose,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Close map")
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "${state.origin.name} → ${state.destination.name}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                state.topRide?.let { best ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Best now: ${best.provider.displayName} ${best.serviceTier} · " +
                            "${Formatters.inr(best.priceInr)} · " +
                            "${Formatters.distance(best.distanceKm)} · " +
                            Formatters.duration(best.estimatedTripDuration),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BestRideChip(option: RideOption, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Route,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${Formatters.inr(option.priceInr)} · " +
                    "${Formatters.duration(option.estimatedTripDuration)} · " +
                    Formatters.distance(option.distanceKm),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier
    )
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun EmptyRidesPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No rides right now",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Pull down to refresh",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Translates the hot-zones referenced by the mock repository into overlay
 * intensities for the map. Intensity is driven by whether the current origin
 * or destination actually sits inside the zone, plus a baseline "this area
 * is always a bit surgy" value.
 */
@Composable
private fun rememberHotZoneOverlays(state: DashboardUiState): List<HotZoneOverlay> {
    return remember(state.origin, state.destination) {
        val o = state.origin.coordinates
        val d = state.destination.coordinates
        SHOWCASE_ZONES.map { zone ->
            val touchesTrip = zone.contains(o) || zone.contains(d)
            val intensity = if (touchesTrip) 0.8f else zone.baseIntensity
            HotZoneOverlay(
                name = zone.name,
                center = zone.center,
                intensity = intensity
            )
        }
    }
}

/** Zones we visualise on the map. Mirrors the ones in MockRideDataRepository. */
private data class MapZone(
    val name: String,
    val center: LatLng,
    val radiusDeg: Double,
    val baseIntensity: Float
) {
    fun contains(p: LatLng): Boolean {
        val dLat = p.latitude - center.latitude
        val dLon = p.longitude - center.longitude
        return (dLat * dLat + dLon * dLon) <= (radiusDeg * radiusDeg)
    }
}

private fun DashboardViewModel.BookingEvent.toMessage(): String = when (outcome) {
    ProviderDeepLinker.LaunchOutcome.OpenedApp -> "Opening $providerName…"
    ProviderDeepLinker.LaunchOutcome.OpenedStore ->
        "$providerName isn't installed — opening Play Store."
    is ProviderDeepLinker.LaunchOutcome.Failed ->
        "Couldn't open $providerName: ${outcome.reason}"
}

private const val SKELETON_COUNT = 3

private val SHOWCASE_ZONES = listOf(
    MapZone("Koramangala",  LatLng(12.9352, 77.6245), 0.015, 0.55f),
    MapZone("Whitefield",   LatLng(12.9855, 77.7361), 0.018, 0.50f),
    MapZone("Indiranagar",  LatLng(12.9719, 77.6412), 0.012, 0.40f),
    MapZone("MG Road",      LatLng(12.9756, 77.6068), 0.010, 0.45f),
    MapZone("Electronic City", LatLng(12.8456, 77.6603), 0.015, 0.35f),
    MapZone("HSR Layout",   LatLng(12.9141, 77.6476), 0.012, 0.35f)
)
