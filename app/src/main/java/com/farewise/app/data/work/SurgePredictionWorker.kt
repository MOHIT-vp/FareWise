package com.farewise.app.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.farewise.app.data.local.dao.PricePredictionDao
import com.farewise.app.data.local.entity.PricePredictionEntity
import com.farewise.app.data.ml.ModelAssets
import com.farewise.app.data.ml.SurgePredictorImpl
import com.farewise.app.domain.repository.PriceHistoryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.time.Duration
import java.time.Instant

/**
 * Periodic worker that runs every ~15 minutes (the WorkManager minimum).
 *
 * For each (provider, tier) pair observed in the last 2 hours:
 *   1. pull the history window from Room,
 *   2. feed it into the TFLite predictor,
 *   3. persist the forecast so Phase 4's UI banners can read it.
 *
 * Finally prunes history older than the look-back window to keep the DB
 * bounded — Room is great at short, hot windows and we never need rows
 * older than the model input depth.
 *
 * The worker is a no-op when:
 *   • the .tflite assets aren't yet shipped (predictor returns null), or
 *   • no price history has been recorded yet.
 *
 * Both cases are expected during bootstrapping and must not fail the job —
 * WorkManager will just re-fire on the next tick.
 */
@HiltWorker
class SurgePredictionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val history: PriceHistoryRepository,
    private val predictor: SurgePredictorImpl,
    private val predictionDao: PricePredictionDao
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val now = Instant.now()
        val lookBack = Duration.ofMinutes(
            (ModelAssets.WINDOW * ModelAssets.STEP_MINUTES).toLong()
        )
        val sinceMillis = now.minus(lookBack).toEpochMilli()

        val tiers = history.distinctTiersSince(sinceMillis)
        if (tiers.isEmpty()) {
            Timber.tag(TAG).d("No tiers with recent history — skipping prediction tick.")
            return Result.success()
        }

        var predicted = 0
        tiers.forEach { tier ->
            val window = history.windowFor(tier.provider, tier.serviceTier, sinceMillis)
            // Need at least a quarter of a full window before predictions are meaningful;
            // the predictor will left-pad but very short histories produce garbage output.
            if (window.size < MIN_HISTORY_POINTS) return@forEach

            val prediction = predictor.predictFromHistory(
                historyPricesInr = window.map { it.priceInr },
                historyTimestampsMillis = window.map { it.fetchedAtMillis },
                provider = tier.provider,
                serviceTier = tier.serviceTier
            ) ?: return@forEach

            predictionDao.insert(
                PricePredictionEntity(
                    provider = tier.provider.name,
                    serviceTier = tier.serviceTier,
                    generatedAtMillis = prediction.generatedAt.toEpochMilli(),
                    stepMinutes = ModelAssets.STEP_MINUTES,
                    horizonPointsCsv = PricePredictionEntity.encodePoints(prediction.pointsInr),
                    confidence = prediction.confidence
                )
            )
            predicted++
        }

        // Keep DB small — retain only what fits in one model window plus a small margin.
        val cutoff = now.minus(lookBack.multipliedBy(2)).toEpochMilli()
        history.prune(cutoff)
        predictionDao.pruneOlderThan(
            now.minus(Duration.ofHours(24)).toEpochMilli()
        )

        Timber.tag(TAG).i("Prediction tick complete: %d forecasts saved.", predicted)
        return Result.success()
    }

    companion object {
        const val TAG = "SurgeWorker"
        private const val MIN_HISTORY_POINTS = 6  // 30 min of history
    }
}
