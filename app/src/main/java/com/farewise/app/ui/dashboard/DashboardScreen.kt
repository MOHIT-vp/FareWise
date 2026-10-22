package com.farewise.app.ui.dashboard

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.farewise.app.data.booking.ProviderDeepLinker
import com.farewise.app.domain.model.LatLng
import com.farewise.app.domain.model.MultimodalCombo
import com.farewise.app.domain.model.RideOption
import com.farewise.app.domain.model.RideRankingCategory
import com.farewise.app.domain.model.TransitSchedule
import com.farewise.app.domain.repository.PriceHistoryRepository.TierRef
import com.farewise.app.ui.common.Formatters
import com.farewise.app.ui.common.MapsLinks
import com.farewise.app.ui.common.RollingPrice
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
import com.farewise.app.ui.theme.Motion
import com.farewise.app.ui.theme.pressable
import com.farewise.app.ui.theme.staggeredReveal
import kotlin.math.ceil

/**
 * The unified dashboard — this is the app's home.
 *
 * Layout, top-to-bottom:
 *
 *   ┌────────────────────────────────────┐
 *   │  Compact bar (fades in on scroll)  │   ← iOS-style collapsing title
 *   │  FareWise (large title)            │
 *   │  ┌──────────────────────────────┐  │
 *   │  │  Route map + best-ride chip  │  │   ← tap ⤢ for full screen
 *   │  └──────────────────────────────┘  │
 *   │  Source / destination search       │
 *   │  Advice banner (wait / book now)   │
 *   │  Cheapest | Fastest | Recommended  │   ← sliding segmented control
 *   │  Ride cards · combos · transit     │
 *   └────────────────────────────────────┘
 *
 * The full-screen map and the map picker animate in *over* the list rather
 * than replacing it, so the list keeps its scroll position underneath.
 *
 * [onImmersiveChange] tells the host when a full-screen surface is showing,
 * so it can hide the bottom navigation bar.
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
    val listState = rememberLazyListState()

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
    val isBusy = state.isLoading || state.isRefreshing

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        DashboardContent(
            state = state,
            listState = listState,
            hotZones = hotZones,
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

        CompactTopBar(
            listState = listState,
            isBusy = isBusy,
            onRefreshClick = viewModel::refresh
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Expanded map grows out of the map card's position (upper part of
        // the screen) rather than from the centre, and leaves faster.
        AnimatedVisibility(
            visible = showFullMap,
            enter = fadeIn(Motion.enter(Motion.FAST_MS)) +
                scaleIn(
                    tween(Motion.DRAWER_MS, easing = Motion.Drawer),
                    initialScale = 0.9f,
                    transformOrigin = MAP_ORIGIN
                ),
            exit = fadeOut(Motion.exit(160)) +
                scaleOut(Motion.exit(220), targetScale = 0.94f, transformOrigin = MAP_ORIGIN)
        ) {
            FullScreenMap(
                state = state,
                hotZones = hotZones,
                onClose = { showFullMap = false }
            )
        }

        // Map picker slides up like an iOS sheet.
        AnimatedVisibility(
            visible = showMapPicker,
            enter = slideInVertically(tween(Motion.DRAWER_MS, easing = Motion.Drawer)) { it },
            exit = slideOutVertically(tween(280, easing = Motion.EaseOut)) { it }
        ) {
            MapPickerScreen(
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
// Collapsing title

/**
 * iOS navigation-bar behaviour: at rest only the refresh button is visible
 * and the big "FareWise" title sits in the list. Once that title scrolls
 * under the bar, a compact centred title fades in with a translucent
 * background and a hairline divider.
 */
@Composable
private fun CompactTopBar(
    listState: LazyListState,
    isBusy: Boolean,
    onRefreshClick: () -> Unit
) {
    val collapseDistancePx = with(LocalDensity.current) { LARGE_TITLE_COLLAPSE.toPx() }
    // Continuous 0..1 for the background + divider; a threshold for the title.
    val scrollFraction by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / collapseDistancePx).coerceIn(0f, 1f)
        }
    }
    val titleVisible by remember { derivedStateOf { scrollFraction >= 1f } }
    val titleAlpha by animateFloatAsState(
        targetValue = if (titleVisible) 1f else 0f,
        animationSpec = tween(Motion.FAST_MS, easing = Motion.EaseOut),
        label = "compactTitle"
    )
    val background = MaterialTheme.colorScheme.background

    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(background.copy(alpha = 0.98f * scrollFraction))
                .statusBarsPadding()
                .height(TOP_BAR_HEIGHT)
                .padding(horizontal = 8.dp)
        ) {
            Text(
                text = "FareWise",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        alpha = titleAlpha
                        translationY = (1f - titleAlpha) * 6.dp.toPx()
                    }
            )
            RefreshButton(
                isBusy = isBusy,
                onClick = onRefreshClick,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.graphicsLayer { alpha = scrollFraction }
        )
    }
}

/**
 * The icon itself spins while prices load (a fast spin reads as a fast
 * load) and, when loading ends, eases out to the next full turn instead of
 * snapping — so the layout never swaps the icon for a progress ring.
 */
@Composable
private fun RefreshButton(
    isBusy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isBusy) {
        if (isBusy) {
            while (true) {
                rotation.animateTo(
                    rotation.value + 360f,
                    tween(SPIN_MS, easing = LinearEasing)
                )
            }
        } else {
            val settle = ceil(rotation.value / 360f) * 360f
            rotation.animateTo(settle, tween(Motion.STANDARD_MS + 120, easing = Motion.EaseOut))
            rotation.snapTo(0f)
        }
    }
    Box(
        modifier = modifier
            .size(44.dp)
            .pressable(pressedScale = 0.88f) { if (!isBusy) onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = if (isBusy) "Refreshing prices" else "Refresh prices",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.graphicsLayer { rotationZ = rotation.value }
        )
    }
}

@Composable
private fun LargeTitle(listState: LazyListState) {
    val collapseDistancePx = with(LocalDensity.current) { LARGE_TITLE_COLLAPSE.toPx() }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 4.dp)
            .graphicsLayer {
                // Fade as it slides under the compact bar.
                val offset = if (listState.firstVisibleItemIndex > 0) collapseDistancePx
                else listState.firstVisibleItemScrollOffset.toFloat()
                alpha = 1f - (offset / collapseDistancePx).coerceIn(0f, 1f)
            }
    ) {
        Text(
            text = "FareWise",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Compare rides & transit, beat the surge",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------
// List

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardContent(
    state: DashboardUiState,
    listState: LazyListState,
    hotZones: List<HotZoneOverlay>,
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
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TOP_BAR_HEIGHT
    val pullState = rememberPullToRefreshState()

    // Keys of everything that has already played its entrance, so cards
    // scrolled back into view (or re-ranked) don't animate in again.
    val revealed = remember { HashSet<String>() }

    // The repositories stamp every quote ID with the fetch time, so IDs
    // change every 30 s. Key the list on stable identities instead, or
    // Compose would rebuild every card each tick (no reorder animation,
    // no rolling prices).
    val rides = remember(state.rankedRides) { state.rankedRides.keyed { it.uiKey() } }
    val combos = remember(state.combos) { state.combos.keyed { it.uiKey() } }
    val transits = remember(state.transitSchedules) { state.transitSchedules.keyed { it.uiKey() } }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = state.isRefreshing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topInset)
            )
        }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = topInset,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "title") { LargeTitle(listState) }

            item(key = "map") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .staggeredReveal(remember { revealed.add("map") }, index = 0)
                ) {
                    GoogleMapView(
                        origin = state.origin.coordinates,
                        destination = state.destination.coordinates,
                        routePolyline = state.routePolyline,
                        hotZones = hotZones,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Top-left keeps the Google logo (bottom-left) visible.
                    AnimatedVisibility(
                        visible = state.topRide != null,
                        enter = fadeIn(Motion.enter()) +
                            slideInVertically(Motion.enter()) { -it / 2 },
                        exit = fadeOut(Motion.exit()),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                    ) {
                        state.topRide?.let { BestRideChip(it) }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .size(40.dp)
                            .pressable(pressedScale = 0.9f, onClick = onExpandMap)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.OpenInFull,
                            contentDescription = "Expand map",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
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
                    onSwapClick = onSwapClick,
                    modifier = Modifier.staggeredReveal(remember { revealed.add("search") }, index = 1)
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
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.staggeredReveal(remember { revealed.add("rides-header") }, index = 2)
                ) {
                    SectionHeader("Ride options")
                    RankingTabs(
                        selected = state.rankingCategory,
                        onSelected = onCategorySelected
                    )
                }
            }

            state.errorMessage?.let { message ->
                item(key = "error") {
                    ErrorBanner(
                        message = message,
                        onRetry = onRefresh,
                        modifier = Modifier.animateItem(
                            fadeInSpec = Motion.enter(),
                            fadeOutSpec = Motion.exit(),
                            placementSpec = PLACEMENT
                        )
                    )
                }
            }

            when {
                rides.isEmpty() && state.isLoading ->
                    items(SKELETON_COUNT, key = { "skeleton-$it" }) {
                        RideCardSkeleton(
                            Modifier.animateItem(
                                fadeInSpec = Motion.enter(),
                                fadeOutSpec = Motion.exit(140),
                                placementSpec = PLACEMENT
                            )
                        )
                    }

                rides.isEmpty() ->
                    item(key = "empty") { EmptyRidesPlaceholder() }
            }

            itemsIndexed(rides, key = { _, (key, _) -> key }) { index, (key, option) ->
                val firstAppearance = remember(key) { revealed.add(key) }
                RideOptionCard(
                    option = option,
                    isHero = index == 0,
                    isProviderInstalled = option.provider in state.installedProviders,
                    onClick = { onRideClick(option) },
                    priceHistory = state.priceHistoryByTier[TierRef(option.provider, option.serviceTier)]
                        .orEmpty(),
                    animateSparkline = firstAppearance,
                    // Re-ranking glides cards to their new slots on a spring;
                    // the first load cascades in.
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = Motion.exit(),
                            placementSpec = PLACEMENT
                        )
                        .staggeredReveal(firstAppearance, index)
                )
            }

            if (combos.isNotEmpty()) {
                item(key = "combos-header") {
                    SectionHeader(
                        "Save with combos",
                        Modifier
                            .padding(top = 6.dp)
                            .animateItem(fadeInSpec = Motion.enter(), placementSpec = PLACEMENT)
                    )
                }
                items(combos, key = { (key, _) -> key }) { (key, combo) ->
                    MultimodalComboCard(
                        combo = combo,
                        modifier = Modifier
                            .animateItem(fadeInSpec = null, placementSpec = PLACEMENT)
                            .staggeredReveal(remember(key) { revealed.add(key) }, index = 0)
                    )
                }
            }

            if (transits.isNotEmpty()) {
                item(key = "transit-header") {
                    SectionHeader(
                        "Public transit",
                        Modifier
                            .padding(top = 6.dp)
                            .animateItem(fadeInSpec = Motion.enter(), placementSpec = PLACEMENT)
                    )
                }
                itemsIndexed(transits, key = { _, (key, _) -> key }) { index, (key, schedule) ->
                    TransitScheduleCard(
                        schedule = schedule,
                        onClick = { onTransitClick(schedule) },
                        modifier = Modifier
                            .animateItem(fadeInSpec = null, placementSpec = PLACEMENT)
                            .staggeredReveal(remember(key) { revealed.add(key) }, index)
                    )
                }
            }
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
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
                .size(44.dp)
                .pressable(pressedScale = 0.9f, onClick = onClose)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close map",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    text = "${state.origin.name} → ${state.destination.name}",
                    style = MaterialTheme.typography.titleMedium,
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
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
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
            RollingPrice(
                amountInr = option.priceInr,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = " · ${Formatters.duration(option.estimatedTripDuration)} · " +
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
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(start = 4.dp)
    )
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
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
            .clip(RoundedCornerShape(20.dp))
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

// ---------------------------------------------------------------------------
// Stable list keys

private fun RideOption.uiKey() = "ride-${provider.name}-$serviceTier"

private fun TransitSchedule.uiKey() = "transit-${mode.name}-$routeName-$headsign"

private fun MultimodalCombo.uiKey() =
    "combo-${transit.uiKey()}-${lastMile.provider.name}-${lastMile.tier}"

/** Pairs each item with a key, suffixing duplicates so LazyColumn keys stay unique. */
private fun <T> List<T>.keyed(keyOf: (T) -> String): List<Pair<String, T>> {
    val seen = HashMap<String, Int>()
    return map { item ->
        val base = keyOf(item)
        val n = (seen[base] ?: 0) + 1
        seen[base] = n
        (if (n == 1) base else "$base#$n") to item
    }
}

// ---------------------------------------------------------------------------

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

private val TOP_BAR_HEIGHT = 52.dp
private val LARGE_TITLE_COLLAPSE = 56.dp
private const val SPIN_MS = 650
private const val SKELETON_COUNT = 3

/** Roughly where the inline map card sits, so the full map grows from it. */
private val MAP_ORIGIN = TransformOrigin(0.5f, 0.22f)

private val PLACEMENT = spring(
    dampingRatio = 0.86f,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset.VisibilityThreshold
)

private val SHOWCASE_ZONES = listOf(
    MapZone("Koramangala",  LatLng(12.9352, 77.6245), 0.015, 0.55f),
    MapZone("Whitefield",   LatLng(12.9855, 77.7361), 0.018, 0.50f),
    MapZone("Indiranagar",  LatLng(12.9719, 77.6412), 0.012, 0.40f),
    MapZone("MG Road",      LatLng(12.9756, 77.6068), 0.010, 0.45f),
    MapZone("Electronic City", LatLng(12.8456, 77.6603), 0.015, 0.35f),
    MapZone("HSR Layout",   LatLng(12.9141, 77.6476), 0.012, 0.35f)
)
