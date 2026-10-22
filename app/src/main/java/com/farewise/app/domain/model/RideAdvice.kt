package com.farewise.app.domain.model

import java.time.Duration

/**
 * A single actionable suggestion the dashboard surfaces above the ride
 * cards — the "should I book now or wait?" answer, in plain language.
 *
 * The decision engine produces at most one [RideAdvice] per dashboard tick;
 * showing multiple simultaneous banners trains the rider to ignore all of
 * them, so we deliberately pick the strongest signal instead.
 *
 * [kind] classifies the action (drives the banner's colour + icon), while
 * [headline] / [subline] carry the human-readable text that's already been
 * localised and formatted by the engine.
 */
data class RideAdvice(
    val kind: Kind,
    val headline: String,
    val subline: String,
    val provider: RideProvider,
    val serviceTier: String,
    val currentPriceInr: Double,
    val targetPriceInr: Double,
    val waitFor: Duration,
    val savingsPct: Double,
    val confidence: Float
) {
    enum class Kind {
        /** Surge is forecast to drop — rider should wait before booking. */
        WAIT_FOR_DROP,

        /** Prices are about to climb — book now to lock the current rate. */
        BOOK_NOW,

        /** Nothing meaningful to act on — dashboard hides the banner. */
        HOLD_STEADY
    }
}
