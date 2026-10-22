package com.farewise.app.domain.usecase

import com.farewise.app.domain.model.LatLng
import com.farewise.app.domain.model.MultimodalCombo
import com.farewise.app.domain.model.PricePrediction
import com.farewise.app.domain.model.RideAdvice
import com.farewise.app.domain.model.RideOption
import com.farewise.app.domain.model.TransitSchedule
import com.farewise.app.domain.repository.RideDataRepository
import com.farewise.app.domain.repository.SurgePredictionRepository
import com.farewise.app.domain.repository.TransitRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/**
 * Stitches the three live streams — ride-hailing quotes, public transit, and
 * the cached TFLite forecasts — into a single snapshot the ViewModel can
 * collect once.
 *
 * The decision engine runs on every emission so the banner updates in step
 * with the ride cards below it. The engine is pure, so re-running it per
 * tick is cheap and keeps the "wait vs book now" message consistent with
 * the prices the user is actually looking at.
 */
class ObserveUnifiedDashboardUseCase @Inject constructor(
    private val rideRepo: RideDataRepository,
    private val transitRepo: TransitRepository,
    private val surgeRepo: SurgePredictionRepository,
    private val decisionEngine: SurgeDecisionEngine,
    private val buildCombos: BuildMultimodalCombosUseCase
) {
    data class Snapshot(
        val rides: List<RideOption>,
        val transits: List<TransitSchedule>,
        val advice: RideAdvice?,
        /**
         * Latest forecast per (provider, tier). The dashboard VM uses this at
         * booking time to record "what the model expected this tier to cost
         * 30 min later" — the counterfactual that powers the journal's
         * "saved by following advice" line.
         */
        val predictions: List<PricePrediction>,
        /**
         * Transit + last-mile ride combos that beat booking a direct ride.
         * Empty when no transit option drops the rider close enough to
         * destination, or when the savings don't clear the use-case's
         * conservative threshold.
         */
        val combos: List<MultimodalCombo>,
        /**
         * Decoded driving polyline for the current trip — sequence of
         * lat/lng vertices the map composable strokes as the route line.
         * Empty when the mock fallback path is in use.
         */
        val routePolyline: List<LatLng>
    )

    operator fun invoke(origin: LatLng, destination: LatLng): Flow<Snapshot> =
        combine(
            rideRepo.observeRideOptions(origin, destination),
            transitRepo.observeTransitSchedules(origin, destination),
            surgeRepo.observeLatestPredictions()
        ) { quotes, transits, predictions ->
            // The combo builder is suspending (last-mile estimator is async)
            // but the costs are dominated by the haversine math + a couple
            // of arithmetic ops per candidate, so awaiting it inline here
            // keeps the snapshot emission ordering trivial.
            val combos = buildCombos(destination, transits, quotes.options)
            Snapshot(
                rides = quotes.options,
                transits = transits,
                advice = decisionEngine(quotes.options, predictions),
                predictions = predictions,
                combos = combos,
                routePolyline = quotes.polyline
            )
        }
}
