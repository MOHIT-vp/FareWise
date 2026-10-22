package com.farewise.app.data.ml

/** Asset names + model contract constants — must match train_surge_lstm.py. */
internal object ModelAssets {
    const val TFLITE = "surge_forecast.tflite"
    const val META = "surge_forecast_meta.json"

    const val WINDOW = 24       // last 2 hours @ 5 min / step
    const val HORIZON = 6       // next 30 minutes
    const val FEATURES = 3
    const val STEP_MINUTES = 5
}
