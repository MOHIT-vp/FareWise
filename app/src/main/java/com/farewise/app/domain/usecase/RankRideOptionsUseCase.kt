package com.farewise.app.domain.usecase

import com.farewise.app.domain.model.RideOption
import com.farewise.app.domain.model.RideRankingCategory
import javax.inject.Inject

/**
 * Pure function that sorts [RideOption]s according to a [RideRankingCategory].
 *
 * Extracted as a UseCase so:
 *   • the ViewModel stays thin (no business logic),
 *   • the ranking can be unit-tested without any Android framework,
 *   • future ranking strategies (e.g. "Eco" / "Least Surge") slot in as new
 *     enum values without touching UI.
 *
 * The RECOMMENDED score blends three signals:
 *   • price        — weighted 50% (people feel this the most)
 *   • total ETA    — weighted 40% (door-to-door time)
 *   • surge penalty — weighted 10% (surging cars feel unfair even when cheap)
 *
 * Each signal is min-max normalised against the candidate set before mixing,
 * so the score is dimensionless and always comparable within a single tick.
 */
class RankRideOptionsUseCase @Inject constructor() {

    operator fun invoke(
        options: List<RideOption>,
        category: RideRankingCategory
    ): List<RideOption> {
        if (options.isEmpty()) return emptyList()
        return when (category) {
            RideRankingCategory.CHEAPEST -> options.sortedBy { it.priceInr }
            RideRankingCategory.FASTEST  -> options.sortedBy { it.totalTimeToArrive }
            RideRankingCategory.RECOMMENDED -> options.sortedBy { score(it, options) }
        }
    }

    // --- Recommended scoring -------------------------------------------------

    private fun score(option: RideOption, all: List<RideOption>): Double {
        val priceNorm = normalise(option.priceInr, all.map { it.priceInr })
        val timeNorm  = normalise(
            option.totalTimeToArrive.toMillis().toDouble(),
            all.map { it.totalTimeToArrive.toMillis().toDouble() }
        )
        // Surge penalty: 0 when not surging, grows linearly above 1.0, capped at 1.
        val surgePenalty = ((option.surgeMultiplier - 1.0) / 1.5).coerceIn(0.0, 1.0)

        return W_PRICE * priceNorm + W_TIME * timeNorm + W_SURGE * surgePenalty
    }

    private fun normalise(value: Double, all: List<Double>): Double {
        val min = all.min()
        val max = all.max()
        val span = max - min
        return if (span <= EPSILON) 0.0 else (value - min) / span
    }

    companion object {
        private const val W_PRICE = 0.5
        private const val W_TIME = 0.4
        private const val W_SURGE = 0.1
        private const val EPSILON = 1e-6
    }
}
