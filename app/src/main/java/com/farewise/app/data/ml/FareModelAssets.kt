package com.farewise.app.data.ml

/** Asset names + model contract constants — must match train_fare_estimator.py. */
internal object FareModelAssets {
    const val TFLITE = "fare_estimator.tflite"
    const val META = "fare_estimator_meta.json"

    /**
     * Feature layout — order MUST match the trainer's writes. Indices into
     * the 11-element feature vector the MLP expects.
     */
    const val FEATURES = 11

    const val IDX_DISTANCE_NORM = 0
    const val IDX_DURATION_NORM = 1
    const val IDX_HOUR_SIN = 2
    const val IDX_HOUR_COS = 3
    const val IDX_PROVIDER_UBER = 4
    const val IDX_PROVIDER_OLA = 5
    const val IDX_PROVIDER_RAPIDO = 6
    const val IDX_TIER_BIKE = 7
    const val IDX_TIER_AUTO = 8
    const val IDX_TIER_ECONOMY = 9
    const val IDX_TIER_PREMIUM = 10
}
