package com.farewise.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.farewise.app.data.booking.ProviderDeepLinker
import com.farewise.app.data.work.SurgeWorkScheduler
import com.farewise.app.domain.model.LatLng
import com.farewise.app.domain.model.LocationFix
import com.farewise.app.domain.model.PricePrediction
import com.farewise.app.domain.model.RideAdvice
import com.farewise.app.domain.model.RideOption
import com.farewise.app.domain.model.RideProvider
import com.farewise.app.domain.model.RideRankingCategory
import com.farewise.app.domain.repository.BookingRepository
import com.farewise.app.domain.repository.GeocodingRepository
import com.farewise.app.domain.repository.LocationProvider
import com.farewise.app.domain.repository.PriceHistoryRepository
import com.farewise.app.domain.usecase.ObserveUnifiedDashboardUseCase
import com.farewise.app.domain.usecase.RankRideOptionsUseCase
import com.farewise.app.ui.common.Place
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Drives the unified dashboard.
 *
 * Responsibilities:
 *   • subscribe to the combined ride + transit stream for the currently
 *     selected origin/destination,
 *   • re-rank the rides whenever the category tab changes or fresh prices
 *     arrive,
 *   • expose a single immutable [DashboardUiState] for the screen,
 *   • persist every observed price tick into the Phase-3 history table so
 *     the TFLite worker has a real 2-hour window to predict on,
 *   • hand the rider off to the partner app via [ProviderDeepLinker] when a
 *     ride card is tapped (real booking, not a mock).
 *
 * Subscription is cancel-and-restart: any change to origin/destination kills
 * the in-flight collection so we don't leak old polls. The ranking layer is
 * pure, so category changes don't need to hit the repositories again — they
 * re-rank the last-known snapshot in place.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val observeDashboard: ObserveUnifiedDashboardUseCase,
    private val rankRides: RankRideOptionsUseCase,
    private val priceHistory: PriceHistoryRepository,
    private val bookingRepository: BookingRepository,
    private val deepLinker: ProviderDeepLinker,
    private val locationProvider: LocationProvider,
    private val geocodingRepository: GeocodingRepository,
    surgeScheduler: SurgeWorkScheduler
) : ViewModel() {

    /**
     * Latest predictions snapshotted from the dashboard stream. Held as a
     * plain field — not in [_uiState] — because nothing the user sees
     * depends on it; it's only consumed at booking time to record the
     * "predicted alternative price" counterfactual.
     */
    @Volatile
    private var latestPredictions: List<PricePrediction> = emptyList()

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _bookingEvents = MutableStateFlow<BookingEvent?>(null)
    val bookingEvents: StateFlow<BookingEvent?> = _bookingEvents.asStateFlow()

    private var subscriptionJob: Job? = null
    private var searchJob: Job? = null

    init {
        // Idempotent — KEEP policy means re-calling on every VM init is safe.
        surgeScheduler.ensureScheduled()
        refreshInstalledProviders()
        subscribe()
        observePriceHistory()
    }

    /**
     * Fold the per-tier history series from Room into UI state. Runs for the
     * VM's lifetime — Room emits a fresh map every time the dashboard
     * subscribe-loop persists a new price tick, so the sparkline self-updates
     * without any extra plumbing in [subscribe].
     */
    private fun observePriceHistory() {
        viewModelScope.launch {
            priceHistory
                .observeRecentPricesByTier(System.currentTimeMillis() - SPARKLINE_WINDOW_MS)
                .catch { Timber.w(it, "Sparkline stream failed") }
                .collect { byTier ->
                    mutate { it.copy(priceHistoryByTier = byTier) }
                }
        }
    }

    /**
     * Poll the PackageManager for which partner apps are currently installed.
     *
     * Called on init and exposed so the screen can refresh when the activity
     * resumes — the user may have installed or removed an app in the
     * background since the VM was first constructed.
     */
    fun refreshInstalledProviders() {
        val installed = RideProvider.entries
            .filter { deepLinker.isProviderInstalled(it) }
            .toSet()
        mutate { it.copy(installedProviders = installed) }
    }

    // --- user intents --------------------------------------------------------

    fun onOriginSelected(place: Place) {
        if (place == _uiState.value.origin) return
        mutate { it.copy(origin = place, isLoading = true) }
        subscribe()
    }

    fun onDestinationSelected(place: Place) {
        if (place == _uiState.value.destination) return
        mutate { it.copy(destination = place, isLoading = true) }
        subscribe()
    }

    fun onUseCurrentLocation() {
        mutate { it.copy(isLocating = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = locationProvider.currentLocation()) {
                is LocationFix.Success -> {
                    mutate { it.copy(isLocating = false) }
                    val currentPlace = Place("Current Location", "GPS", result.coordinates)
                    onOriginSelected(currentPlace)
                }
                LocationFix.PermissionDenied -> mutate {
                    it.copy(
                        isLocating = false,
                        errorMessage = "Location permission denied. " +
                            "Grant it from app settings to use your current location."
                    )
                }
                LocationFix.LocationDisabled -> mutate {
                    it.copy(
                        isLocating = false,
                        errorMessage = "Location is turned off on this device. " +
                            "Enable Location in system settings and try again."
                    )
                }
                LocationFix.Unavailable -> mutate {
                    it.copy(
                        isLocating = false,
                        errorMessage = "Couldn't get a GPS fix. " +
                            "Try moving outdoors or near a window, then retry."
                    )
                }
            }
        }
    }

    /**
     * Called when the user dismisses the runtime permission dialog without
     * granting either fine or coarse location. Without this hook, the
     * "Use current location" tap silently does nothing on deny.
     */
    fun onLocationPermissionDenied() {
        mutate {
            it.copy(
                isLocating = false,
                errorMessage = "Location permission denied. " +
                    "Grant it from app settings to use your current location."
            )
        }
    }

    /**
     * Pull-to-refresh / refresh button / error "Retry". Restarting the
     * subscription makes the repositories fetch immediately instead of
     * waiting for the next 30 s polling tick.
     */
    fun refresh() {
        mutate { it.copy(isRefreshing = true, errorMessage = null) }
        subscribe()
    }

    fun onSwapLocations() {
        mutate {
            it.copy(origin = it.destination, destination = it.origin, isLoading = true)
        }
        subscribe()
    }

    // --- address search & map pick -----------------------------------------

    /**
     * Debounced forward-geocode triggered as the user types in the location
     * picker search bar. Cancels any in-flight search to avoid stale results.
     */
    fun searchAddress(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            mutate { it.copy(addressSearchResults = emptyList(), isSearching = false) }
            return
        }
        mutate { it.copy(isSearching = true) }
        searchJob = viewModelScope.launch {
            // Small debounce so we don't geocode on every keystroke
            kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)
            val results = geocodingRepository.searchByAddress(query)
            mutate { it.copy(addressSearchResults = results, isSearching = false) }
        }
    }

    /** Clear search results when the picker sheet is dismissed. */
    fun clearSearchResults() {
        searchJob?.cancel()
        mutate { it.copy(addressSearchResults = emptyList(), isSearching = false) }
    }

    /**
     * Called when the user confirms a location from the map picker.
     * Reverse-geocodes the coordinates to get a human-readable label,
     * then sets it as origin or destination based on [field].
     */
    fun onMapLocationPicked(latLng: LatLng, field: LocationField) {
        viewModelScope.launch {
            val place = geocodingRepository.reverseGeocode(latLng)
                ?: Place(
                    name = "%.4f, %.4f".format(latLng.latitude, latLng.longitude),
                    area = "Dropped pin",
                    coordinates = latLng
                )
            when (field) {
                LocationField.ORIGIN -> onOriginSelected(place)
                LocationField.DESTINATION -> onDestinationSelected(place)
            }
        }
    }

    /**
     * Reverse-geocode a map position for display in the map picker bottom panel.
     * Updates [DashboardUiState.mapPickerResolvedAddress].
     */
    fun reverseGeocodeForMapPicker(latLng: LatLng) {
        viewModelScope.launch {
            mutate { it.copy(isResolvingMapAddress = true) }
            val place = geocodingRepository.reverseGeocode(latLng)
            mutate {
                it.copy(
                    mapPickerResolvedAddress = place?.fullLabel,
                    isResolvingMapAddress = false
                )
            }
        }
    }

    /** Reset map picker address state when the picker is dismissed. */
    fun clearMapPickerState() {
        mutate { it.copy(mapPickerResolvedAddress = null, isResolvingMapAddress = false) }
    }

    fun onCategorySelected(category: RideRankingCategory) {
        mutate { current ->
            current.copy(
                rankingCategory = category,
                rankedRides = rankRides(current.rankedRides, category)
            )
        }
    }

    fun onAdviceClicked() {
        val advice = _uiState.value.advice ?: return
        // BOOK_NOW is the only kind where the banner is a direct call-to-book.
        // WAIT_FOR_DROP is informational — the banner already carries the
        // wait duration + target price, so tapping is a no-op.
        if (advice.kind != RideAdvice.Kind.BOOK_NOW) return
        val tier = _uiState.value.rankedRides.firstOrNull {
            it.provider == advice.provider && it.serviceTier == advice.serviceTier
        } ?: return
        onRideClicked(tier)
    }

    fun onRideClicked(option: RideOption) {
        val state = _uiState.value
        val outcome = deepLinker.launchBooking(
            option = option,
            pickup = state.origin.coordinates,
            dropoff = state.destination.coordinates
        )
        _bookingEvents.value = BookingEvent(option.provider.displayName, outcome)

        // Record the booking only when the deep-link actually launched the
        // partner app (or even the Play Store) — that's commitment. A Failed
        // launch shouldn't pollute the journal with phantom trips.
        if (outcome !is ProviderDeepLinker.LaunchOutcome.Failed) {
            recordBooking(option, state)
        }
    }

    private fun recordBooking(option: RideOption, state: DashboardUiState) {
        // Find the predicted alternative for *this* tier — the last point in
        // its 30-min horizon, captured at hand-off so we can later answer
        // "did the rider's choice beat the model's expectation?".
        val alternative = latestPredictions
            .firstOrNull { it.provider == option.provider && it.serviceTier == option.serviceTier }
            ?.pointsInr
            ?.lastOrNull()

        val activeAdvice = state.advice?.kind

        viewModelScope.launch {
            runCatching {
                bookingRepository.record(
                    option = option,
                    originLabel = state.origin.fullLabel,
                    destinationLabel = state.destination.fullLabel,
                    predictedAlternativeInr = alternative,
                    activeAdvice = activeAdvice
                )
            }.onFailure { Timber.w(it, "Failed to record booking in journal") }
        }
    }

    fun onBookingEventConsumed() {
        _bookingEvents.value = null
    }

    // --- subscription plumbing ----------------------------------------------

    private fun subscribe() {
        subscriptionJob?.cancel()
        val snapshot = _uiState.value
        subscriptionJob = viewModelScope.launch {
            observeDashboard(snapshot.origin.coordinates, snapshot.destination.coordinates)
                .onEach { data ->
                    // Persist every tick before re-rendering — the worker will
                    // read this on its 15-min cadence to feed the LSTM.
                    runCatching { priceHistory.record(data.rides) }
                        .onFailure { Timber.w(it, "Failed to persist price history.") }

                    latestPredictions = data.predictions

                    mutate { state ->
                        state.copy(
                            rankedRides = rankRides(data.rides, state.rankingCategory),
                            transitSchedules = data.transits,
                            combos = data.combos,
                            routePolyline = data.routePolyline,
                            advice = data.advice,
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = null
                        )
                    }
                }
                .catch { t ->
                    Timber.e(t, "Dashboard stream failed")
                    mutate {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = t.localizedMessage ?: "Unknown error"
                        )
                    }
                }
                .collect { /* side-effects already done in onEach */ }
        }
    }

    private inline fun mutate(transform: (DashboardUiState) -> DashboardUiState) {
        _uiState.value = transform(_uiState.value)
    }

    /** Single-shot signal the screen turns into a snackbar. */
    data class BookingEvent(
        val providerName: String,
        val outcome: ProviderDeepLinker.LaunchOutcome
    )

    companion object {
        /**
         * Window of price history rendered as a sparkline beside each card.
         * Two hours mirrors the surge LSTM's input window — that's the same
         * span the "wait for drop" advice considers, so the chart and the
         * banner are always reading the same horizon of data.
         */
        private const val SPARKLINE_WINDOW_MS: Long = 2 * 60 * 60 * 1000L

        /** Debounce between keystrokes before geocoding fires. */
        private const val SEARCH_DEBOUNCE_MS = 350L
    }
}
