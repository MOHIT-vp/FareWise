package com.farewise.app.domain.model

/**
 * Strategy used to rank ride options in the unified dashboard.
 *
 * - [CHEAPEST]: strictly ascending by [RideOption.priceInr].
 * - [FASTEST]:  strictly ascending by [RideOption.totalTimeToArrive].
 * - [RECOMMENDED]: weighted score that balances price against time-to-arrive
 *   and penalises surge — used as the default tab so a casual user can just
 *   "book the top card".
 */
enum class RideRankingCategory(val label: String) {
    CHEAPEST("Cheapest"),
    FASTEST("Fastest"),
    RECOMMENDED("Recommended")
}
