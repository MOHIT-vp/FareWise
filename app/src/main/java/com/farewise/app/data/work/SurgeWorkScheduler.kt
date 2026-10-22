package com.farewise.app.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around WorkManager for the surge prediction job.
 *
 * Enqueued as a periodic job with [ExistingPeriodicWorkPolicy.KEEP] so
 * restarting the app doesn't double-schedule the worker. The minimum
 * period WorkManager permits is 15 minutes; that's also roughly the
 * cadence at which fresh predictions add real value — predicting more
 * often than that wastes battery without improving advice quality.
 */
@Singleton
class SurgeWorkScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    fun ensureScheduled() {
        val constraints = Constraints.Builder()
            // Battery-not-low keeps the model off the device while the user
            // is at <15%; no network constraint because inference is fully
            // on-device.
            .setRequiresBatteryNotLow(true)
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val request = PeriodicWorkRequestBuilder<SurgePredictionWorker>(
            Duration.ofMinutes(15)
        )
            .setConstraints(constraints)
            .addTag(WORK_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        Timber.tag(TAG).i("Surge prediction worker enqueued (period=15min).")
    }

    companion object {
        private const val TAG = "SurgeScheduler"
        const val WORK_TAG = "surge-prediction"
        const val UNIQUE_NAME = "surge-prediction-periodic"
    }
}
