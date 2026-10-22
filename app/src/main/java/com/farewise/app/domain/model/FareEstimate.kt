package com.farewise.app.domain.model

/**
 * On-device ML fare estimate for one (provider, tier, route) tuple.
 *
 * Three values are kept separately because the underlying TFLite model can be
 * trained two different ways:
 *
 *   • Single-output regression (current shipped model): [pointInr] is the
 *     network's MSE-trained estimate; [lowerInr] / [upperInr] are derived
 *     from a fixed [BAND_FRACTION] of the point so the UI can still render
 *     a confidence band, but the band is uncalibrated.
 *
 *   • Three-output quantile regression (rerun `train_fare_estimator.py
 *     --quantile`): the network outputs the 10th / 50th / 90th percentile
 *     directly via pinball loss. In that mode all three numbers come from
 *     the model and the band is calibrated.
 *
 * The Android side reads `fare_estimator_meta.json::quantiles` to decide
 * which mode to use, so the same code path works in both worlds.
 */
data class FareEstimate(
    val pointInr: Double,
    val lowerInr: Double,
    val upperInr: Double,
    val isCalibrated: Boolean
) {
    /** ± half-width of the confidence band, in INR. */
    val halfBandInr: Double get() = (upperInr - lowerInr) / 2.0

    /** True when the band is wider than ~5% of the point — worth surfacing. */
    val hasMeaningfulBand: Boolean get() =
        pointInr > 0 && halfBandInr / pointInr > 0.05

    companion object {
        /** Synthetic band as a fraction of the point estimate, single-output models only. */
        const val BAND_FRACTION = 0.15
    }
}
