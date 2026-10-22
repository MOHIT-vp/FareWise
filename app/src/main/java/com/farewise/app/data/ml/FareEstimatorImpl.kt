package com.farewise.app.data.ml

import android.content.Context
import android.content.res.AssetManager
import com.farewise.app.domain.model.FareEstimate
import com.farewise.app.domain.model.RideProvider
import com.farewise.app.domain.repository.FareEstimator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.time.Duration
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * On-device fare estimator backed by TensorFlow Lite.
 *
 * Loads `fare_estimator.tflite` + `fare_estimator_meta.json` from `assets/`
 * (produced by `ml_model/train_fare_estimator.py`).
 *
 * Two output shapes are supported via the meta JSON:
 *
 *   • `output_shape: [1, 1]` (current shipped model, MSE-trained):
 *     the single output is the predicted normalised fare. We de-normalise
 *     with `fare_mean_inr` / `fare_std_inr` and synthesise a band of
 *     ±[FareEstimate.BAND_FRACTION] around the point so the UI can still
 *     render a confidence range.
 *
 *   • `output_shape: [1, 3]` + `quantiles: [0.1, 0.5, 0.9]` (rerun the
 *     trainer with `--quantile`): the three outputs are the 10/50/90 percentile
 *     fares directly. The band rendered to the UI is then calibrated to the
 *     training distribution.
 *
 * Returns null when assets are missing — same graceful-degradation pattern as
 * [SurgePredictorImpl].
 */
@Singleton
class FareEstimatorImpl @Inject constructor(
    @param:ApplicationContext private val context: Context
) : FareEstimator {

    private val lock = Mutex()
    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var meta: ModelMeta? = null
    @Volatile private var loadAttempted = false

    override suspend fun estimate(
        provider: RideProvider,
        serviceTier: String,
        distanceKm: Double,
        tripDuration: Duration
    ): FareEstimate? {
        if (distanceKm <= 0.0 || tripDuration.isZero || tripDuration.isNegative) return null

        return withContext(Dispatchers.Default) {
            ensureLoaded() ?: return@withContext null
            val localMeta = meta ?: return@withContext null
            val localInterpreter = interpreter ?: return@withContext null

            val input = buildFeatures(provider, serviceTier, distanceKm, tripDuration, localMeta)
            val output = Array(1) { FloatArray(localMeta.outputSize) }

            lock.withLock {
                localInterpreter.run(input, output)
            }

            buildEstimate(output[0], localMeta)
        }
    }

    // -------------------------------------------------------------------------
    // Tensor assembly
    // -------------------------------------------------------------------------

    private fun buildFeatures(
        provider: RideProvider,
        serviceTier: String,
        distanceKm: Double,
        tripDuration: Duration,
        meta: ModelMeta
    ): Array<FloatArray> {
        val feats = FloatArray(FareModelAssets.FEATURES)
        feats[FareModelAssets.IDX_DISTANCE_NORM] =
            (distanceKm / meta.distanceNormDivisorKm).toFloat()
        feats[FareModelAssets.IDX_DURATION_NORM] =
            (tripDuration.toMinutes() / meta.durationNormDivisorMin).toFloat()

        val cal = Calendar.getInstance(TimeZone.getTimeZone(ZoneId.systemDefault()))
        val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
        feats[FareModelAssets.IDX_HOUR_SIN] = sin(2 * PI * hour / 24.0).toFloat()
        feats[FareModelAssets.IDX_HOUR_COS] = cos(2 * PI * hour / 24.0).toFloat()

        when (provider) {
            RideProvider.UBER -> feats[FareModelAssets.IDX_PROVIDER_UBER] = 1f
            RideProvider.OLA -> feats[FareModelAssets.IDX_PROVIDER_OLA] = 1f
            RideProvider.RAPIDO -> feats[FareModelAssets.IDX_PROVIDER_RAPIDO] = 1f
        }

        when (classifyTier(serviceTier)) {
            TierClass.BIKE -> feats[FareModelAssets.IDX_TIER_BIKE] = 1f
            TierClass.AUTO -> feats[FareModelAssets.IDX_TIER_AUTO] = 1f
            TierClass.ECONOMY -> feats[FareModelAssets.IDX_TIER_ECONOMY] = 1f
            TierClass.PREMIUM -> feats[FareModelAssets.IDX_TIER_PREMIUM] = 1f
        }

        return arrayOf(feats)
    }

    private fun buildEstimate(raw: FloatArray, meta: ModelMeta): FareEstimate? {
        return when (meta.outputSize) {
            1 -> {
                val point = denormalise(raw[0], meta)
                val half = point * FareEstimate.BAND_FRACTION
                FareEstimate(
                    pointInr = point,
                    lowerInr = (point - half).coerceAtLeast(0.0),
                    upperInr = point + half,
                    isCalibrated = false
                )
            }
            3 -> {
                // Quantile heads are emitted in the order declared in meta
                // (default [0.1, 0.5, 0.9]). Sort by quantile so we don't
                // depend on the trainer's output order.
                val pairs = meta.quantiles.zip(raw.toList())
                    .sortedBy { it.first }
                val lower = denormalise(pairs[0].second, meta)
                val median = denormalise(pairs[1].second, meta)
                val upper = denormalise(pairs[2].second, meta)
                FareEstimate(
                    pointInr = median,
                    lowerInr = lower.coerceAtLeast(0.0),
                    upperInr = upper.coerceAtLeast(median),
                    isCalibrated = true
                )
            }
            else -> {
                Timber.w("Fare model has unsupported output size %d", meta.outputSize)
                null
            }
        }
    }

    private fun denormalise(normalised: Float, meta: ModelMeta): Double =
        (normalised * meta.fareStdInr + meta.fareMeanInr).coerceAtLeast(0.0)

    private fun classifyTier(tier: String): TierClass {
        val lower = tier.lowercase()
        return when {
            lower.contains("bike") -> TierClass.BIKE
            lower.contains("auto") -> TierClass.AUTO
            lower.contains("premier") || lower.contains("prime") -> TierClass.PREMIUM
            else -> TierClass.ECONOMY
        }
    }

    private enum class TierClass { BIKE, AUTO, ECONOMY, PREMIUM }

    // -------------------------------------------------------------------------
    // Lazy asset loading
    // -------------------------------------------------------------------------

    private suspend fun ensureLoaded(): Unit? {
        if (interpreter != null && meta != null) return Unit
        if (loadAttempted && (interpreter == null || meta == null)) return null

        lock.withLock {
            if (interpreter != null && meta != null) return Unit
            loadAttempted = true
            val loadedMeta = tryLoadMeta(context.assets) ?: run {
                Timber.w("Fare model metadata missing — estimator disabled.")
                return null
            }
            val loadedInterpreter = tryLoadInterpreter(context.assets) ?: run {
                Timber.w("Fare model TFLite missing — estimator disabled.")
                return null
            }
            meta = loadedMeta
            interpreter = loadedInterpreter
            Timber.i(
                "Fare estimator ready (output=%d, calibrated=%s, mean=%.2f).",
                loadedMeta.outputSize,
                loadedMeta.outputSize == 3,
                loadedMeta.fareMeanInr
            )
        }
        return Unit
    }

    private fun tryLoadMeta(assets: AssetManager): ModelMeta? = try {
        assets.open(FareModelAssets.META).use { stream ->
            val json = JSONObject(stream.bufferedReader().readText())
            val outputShape = json.optJSONArray("output_shape")
            val outputSize = outputShape?.optInt(1, 1) ?: 1

            val quantiles = mutableListOf<Double>()
            json.optJSONArray("quantiles")?.let { arr ->
                for (i in 0 until arr.length()) quantiles += arr.getDouble(i)
            }
            // Fall back to an even distribution when meta declares output
            // size 3 but didn't write the quantile array (older trainer).
            if (outputSize == 3 && quantiles.size != 3) {
                quantiles.clear()
                quantiles += listOf(0.1, 0.5, 0.9)
            }

            ModelMeta(
                fareMeanInr = json.getDouble("fare_mean_inr"),
                fareStdInr = json.getDouble("fare_std_inr").coerceAtLeast(1e-6),
                distanceNormDivisorKm = json.optDouble("distance_norm_divisor_km", 20.0),
                durationNormDivisorMin = json.optDouble("duration_norm_divisor_min", 60.0),
                outputSize = outputSize,
                quantiles = quantiles
            )
        }
    } catch (_: FileNotFoundException) {
        null
    } catch (t: Throwable) {
        Timber.w(t, "Failed to parse %s", FareModelAssets.META)
        null
    }

    private fun tryLoadInterpreter(assets: AssetManager): Interpreter? = try {
        val fd = assets.openFd(FareModelAssets.TFLITE)
        fd.createInputStream().channel.use { channel ->
            val buffer = channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength
            ).order(ByteOrder.nativeOrder())
            Interpreter(buffer as ByteBuffer, Interpreter.Options().apply { numThreads = 1 })
        }
    } catch (_: FileNotFoundException) {
        null
    } catch (t: Throwable) {
        Timber.e(t, "Failed to load %s", FareModelAssets.TFLITE)
        null
    }

    private data class ModelMeta(
        val fareMeanInr: Double,
        val fareStdInr: Double,
        val distanceNormDivisorKm: Double,
        val durationNormDivisorMin: Double,
        val outputSize: Int,
        val quantiles: List<Double>
    )
}
