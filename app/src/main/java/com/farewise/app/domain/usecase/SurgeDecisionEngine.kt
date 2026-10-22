package com.farewise.app.domain.usecase

import com.farewise.app.domain.model.PricePrediction
import com.farewise.app.domain.model.RideAdvice
import com.farewise.app.domain.model.RideOption
import java.time.Duration
import javax.inject.Inject
import kotlin.math.abs

/**
 * Turns the current ride quotes + the TFLite forecast into one actionable
 * banner. Stateless and deterministic — given the same inputs it always
 * returns the same advice, which keeps the UI stable across ticks.
 *
 * The engine runs this algorithm on every (option, prediction) pair:
 *
 *   1. Skip tiers where confidence is below [MIN_CONFIDENCE] — a low-data
 *      prediction is worse than no advice at all.
 *   2. For the predicted min-price inside the horizon:
 *        • if it's at least [MIN_DROP_PCT] cheaper than the current price,
 *          emit a WAIT_FOR_DROP candidate with the wait duration.
 *   3. For the predicted price at the end of the horizon:
 *        • if it's at least [MIN_RISE_PCT] *more expensive* than current,
 *          emit a BOOK_NOW candidate.
 *   4. Rank candidates by absolute rupee swing and return the winner.
 *
 * We rank by absolute savings (₹) not percent, because a 10% drop on a
 * ₹95 bike trip (₹9) is less compelling than 6% off a ₹420 cab (₹25).
 */
class SurgeDecisionEngine @Inject constructor() {

    operator fun invoke(
        currentOptions: List<RideOption>,
        predictions: List<PricePrediction>
    ): RideAdvice? {
        if (currentOptions.isEmpty() || predictions.isEmpty()) return null

        val predictionByTier = predictions.associateBy { it.provider to it.serviceTier }

        val candidates = currentOptions.mapNotNull { option ->
            val prediction = predictionByTier[option.provider to option.serviceTier]
                ?: return@mapNotNull null
            if (prediction.confidence < MIN_CONFIDENCE) return@mapNotNull null
            evaluate(option, prediction)
        }

        return candidates.maxByOrNull { abs(it.currentPriceInr - it.targetPriceInr) }
    }

    private fun evaluate(
        option: RideOption,
        prediction: PricePrediction
    ): RideAdvice? {
        val currentPrice = option.priceInr
        if (currentPrice <= 0.0) return null

        val minIdx = prediction.pointsInr.withIndex().minBy { it.value }.index
        val predictedMin = prediction.pointsInr[minIdx]
        val waitFor = prediction.stepDuration.multipliedBy(minIdx.toLong())
        val dropPct = (currentPrice - predictedMin) / currentPrice

        val predictedEnd = prediction.pointsInr.last()
        val risePct = (predictedEnd - currentPrice) / currentPrice

        return when {
            dropPct >= MIN_DROP_PCT && waitFor >= MIN_WAIT -> waitAdvice(
                option = option,
                prediction = prediction,
                target = predictedMin,
                waitFor = waitFor,
                dropPct = dropPct
            )

            risePct >= MIN_RISE_PCT -> bookNowAdvice(
                option = option,
                prediction = prediction,
                target = predictedEnd,
                risePct = risePct
            )

            else -> null
        }
    }

    private fun waitAdvice(
        option: RideOption,
        prediction: PricePrediction,
        target: Double,
        waitFor: Duration,
        dropPct: Double
    ): RideAdvice {
        val waitMins = (waitFor.toMinutes()).coerceAtLeast(1)
        val pctRounded = (dropPct * 100).toInt()
        return RideAdvice(
            kind = RideAdvice.Kind.WAIT_FOR_DROP,
            headline = "Wait $waitMins min — ${option.provider.displayName} surge should ease",
            subline = "${option.serviceTier} forecast to drop ~$pctRounded% to ₹${target.toInt()}",
            provider = option.provider,
            serviceTier = option.serviceTier,
            currentPriceInr = option.priceInr,
            targetPriceInr = target,
            waitFor = waitFor,
            savingsPct = dropPct,
            confidence = prediction.confidence
        )
    }

    private fun bookNowAdvice(
        option: RideOption,
        prediction: PricePrediction,
        target: Double,
        risePct: Double
    ): RideAdvice {
        val pctRounded = (risePct * 100).toInt()
        val horizonMins = prediction.horizon.toMinutes()
        return RideAdvice(
            kind = RideAdvice.Kind.BOOK_NOW,
            headline = "Book ${option.provider.displayName} now — prices trending up",
            subline = "${option.serviceTier} forecast to rise ~$pctRounded% to ₹${target.toInt()} in $horizonMins min",
            provider = option.provider,
            serviceTier = option.serviceTier,
            currentPriceInr = option.priceInr,
            targetPriceInr = target,
            waitFor = Duration.ZERO,
            savingsPct = risePct,
            confidence = prediction.confidence
        )
    }

    companion object {
        /** Predictions below this are ignored — too little data to act on. */
        private const val MIN_CONFIDENCE = 0.4f

        /** Minimum drop to suggest waiting (8% — below this isn't worth a wait). */
        private const val MIN_DROP_PCT = 0.08

        /** Minimum rise to suggest locking in now (6% — smaller spikes are noise). */
        private const val MIN_RISE_PCT = 0.06

        /** Don't tell someone to wait 2 minutes — the UX cost outweighs the savings. */
        private val MIN_WAIT: Duration = Duration.ofMinutes(5)
    }
}
