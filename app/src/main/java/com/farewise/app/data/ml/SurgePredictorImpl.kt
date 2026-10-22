package com.farewise.app.data.ml

import android.content.Context
import android.content.res.AssetManager
import com.farewise.app.data.local.dao.PricePredictionDao
import com.farewise.app.data.local.entity.PricePredictionEntity
import com.farewise.app.domain.model.LatLng
import com.farewise.app.domain.model.PricePrediction
import com.farewise.app.domain.model.RideOption
import com.farewise.app.domain.model.RideProvider
import com.farewise.app.domain.model.WeatherSnapshot
import com.farewise.app.domain.repository.SurgePredictionRepository
import com.farewise.app.domain.repository.WeatherRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * On-device surge-price forecaster backed by TensorFlow Lite.
 *
 * Loads `surge_forecast.tflite` + `surge_forecast_meta.json` from the
 * `assets/` bundle produced by `ml_model/train_surge_lstm.py`.
 *
 * The model contract is rigid:
 *   • input  : float32[1, WINDOW, FEATURES] — last 2 h, 5-minute ticks,
 *              feature order = [normalised_price, sin(hour), cos(hour)]
 *   • output : float32[1, HORIZON]          — next 30 min, *normalised* price
 *
 * This class handles:
 *   • memory-mapping the .tflite file (zero-copy load),
 *   • reading the metadata file for price_mean / price_std,
 *   • padding or trimming the caller's history to exactly WINDOW samples,
 *   • un-normalising the model output back into ₹ before returning.
 *
 * If the assets are missing (e.g. the Python script hasn't been run yet),
 * [predict] returns `null` and logs a warning — the UI degrades gracefully
 * rather than crashing the app. That way the rest of the project can be
 * demo'd even before the ML artefacts ship.
 */
@Singleton
class SurgePredictorImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val predictionDao: PricePredictionDao,
    private val weatherRepository: WeatherRepository
) : SurgePredictionRepository {

    override fun observeLatestPredictions(): Flow<List<PricePrediction>> =
        predictionDao.observeLatestPerTier().map { rows -> rows.mapNotNull { it.toDomain() } }

    private fun PricePredictionEntity.toDomain(): PricePrediction? {
        val provider = runCatching { RideProvider.valueOf(provider) }.getOrNull() ?: return null
        val points = pointsInr()
        if (points.isEmpty()) return null
        return PricePrediction(
            provider = provider,
            serviceTier = serviceTier,
            generatedAt = Instant.ofEpochMilli(generatedAtMillis),
            stepDuration = Duration.ofMinutes(stepMinutes.toLong()),
            pointsInr = points,
            confidence = confidence
        )
    }

    private val lock = Mutex()
    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var meta: ModelMeta? = null
    @Volatile private var loadAttempted = false

    override suspend fun predict(recentHistory: List<RideOption>): PricePrediction? {
        if (recentHistory.isEmpty()) return null

        return withContext(Dispatchers.Default) {
            ensureLoaded() ?: return@withContext null

            val localMeta = meta ?: return@withContext null
            val localInterpreter = interpreter ?: return@withContext null

            val tier = recentHistory.last()
            val weather = if (localMeta.usesWeather) {
                runCatching { weatherRepository.currentWeather(BENGALURU_CENTROID) }.getOrNull()
            } else null

            val input = buildInputTensor(recentHistory, localMeta, weather)
            val output = Array(1) { FloatArray(ModelAssets.HORIZON) }

            lock.withLock {
                localInterpreter.run(input, output)
            }

            val pointsInr = output[0].map { normalisedToInr(it, localMeta) }
            val confidence = estimateConfidence(recentHistory)

            PricePrediction(
                provider = tier.provider,
                serviceTier = tier.serviceTier,
                generatedAt = Instant.now(),
                stepDuration = Duration.ofMinutes(ModelAssets.STEP_MINUTES.toLong()),
                pointsInr = pointsInr,
                confidence = confidence
            )
        }
    }

    /** Run inference against a raw history-point list (used by the worker). */
    suspend fun predictFromHistory(
        historyPricesInr: List<Double>,
        historyTimestampsMillis: List<Long>,
        provider: com.farewise.app.domain.model.RideProvider,
        serviceTier: String,
        at: LatLng? = null
    ): PricePrediction? {
        if (historyPricesInr.size != historyTimestampsMillis.size) return null
        if (historyPricesInr.isEmpty()) return null

        return withContext(Dispatchers.Default) {
            ensureLoaded() ?: return@withContext null

            val localMeta = meta ?: return@withContext null
            val localInterpreter = interpreter ?: return@withContext null

            val weather = if (localMeta.usesWeather) {
                runCatching {
                    weatherRepository.currentWeather(at ?: BENGALURU_CENTROID)
                }.getOrNull()
            } else null

            val input = buildInputTensorFromPrimitives(
                historyPricesInr,
                historyTimestampsMillis,
                localMeta,
                weather
            )
            val output = Array(1) { FloatArray(ModelAssets.HORIZON) }

            lock.withLock {
                localInterpreter.run(input, output)
            }

            val pointsInr = output[0].map { normalisedToInr(it, localMeta) }

            PricePrediction(
                provider = provider,
                serviceTier = serviceTier,
                generatedAt = Instant.now(),
                stepDuration = Duration.ofMinutes(ModelAssets.STEP_MINUTES.toLong()),
                pointsInr = pointsInr,
                confidence = estimateConfidenceFromHistorySize(historyPricesInr.size)
            )
        }
    }

    // -------------------------------------------------------------------------
    // Tensor assembly
    // -------------------------------------------------------------------------

    private fun buildInputTensor(
        history: List<RideOption>,
        meta: ModelMeta,
        weather: WeatherSnapshot?
    ): Array<Array<FloatArray>> {
        val prices = history.map { it.priceInr }
        val timestamps = history.map { it.fetchedAt.toEpochMilli() }
        return buildInputTensorFromPrimitives(prices, timestamps, meta, weather)
    }

    /**
     * Build a (1, WINDOW, F) tensor where F is meta.featuresPerStep.
     *
     * The first three channels are always [price_norm, sin(hour), cos(hour)] —
     * matching the original 3-feature contract. When the meta declares weather
     * features in [ModelMeta.weatherChannels], those channels are appended in
     * the order the trainer wrote them, normalised against the per-channel
     * mean / std also in the meta. Missing weather (no key, upstream failure)
     * collapses to zero — the same as the channel mean after normalisation.
     */
    private fun buildInputTensorFromPrimitives(
        prices: List<Double>,
        timestamps: List<Long>,
        meta: ModelMeta,
        weather: WeatherSnapshot?
    ): Array<Array<FloatArray>> {
        val w = ModelAssets.WINDOW
        val padded = when {
            prices.size >= w -> prices.takeLast(w) to timestamps.takeLast(w)
            else -> {
                val deficit = w - prices.size
                val firstPrice = prices.first()
                val firstTs = timestamps.first()
                val stepMs = ModelAssets.STEP_MINUTES * 60L * 1000L
                val padPrices = List(deficit) { firstPrice }
                val padTs = List(deficit) { i -> firstTs - (deficit - i) * stepMs }
                (padPrices + prices) to (padTs + timestamps)
            }
        }
        val (pxs, tss) = padded

        val featuresPerStep = meta.featuresPerStep
        val tensor = Array(1) { Array(w) { FloatArray(featuresPerStep) } }
        val cal = Calendar.getInstance(TimeZone.getTimeZone(ZoneId.systemDefault()))

        // Pre-compute the weather feature row once; it doesn't vary across the
        // history window because the upstream feed is a single snapshot.
        val weatherRow = if (meta.usesWeather) buildWeatherRow(meta, weather) else FloatArray(0)

        for (i in 0 until w) {
            val priceNorm = ((pxs[i] - meta.priceMean) / meta.priceStd).toFloat()
            cal.timeInMillis = tss[i]
            val hour = cal.get(Calendar.HOUR_OF_DAY) +
                cal.get(Calendar.MINUTE) / 60.0
            val row = tensor[0][i]
            row[0] = priceNorm
            row[1] = sin(2 * PI * hour / 24.0).toFloat()
            row[2] = cos(2 * PI * hour / 24.0).toFloat()
            // Copy weather channels into the trailing slots (indices 3..F-1).
            for (k in weatherRow.indices) row[3 + k] = weatherRow[k]
        }
        return tensor
    }

    private fun buildWeatherRow(meta: ModelMeta, weather: WeatherSnapshot?): FloatArray {
        val row = FloatArray(meta.weatherChannels.size)
        if (weather == null) return row  // zeros = post-normalisation channel mean
        for ((i, channel) in meta.weatherChannels.withIndex()) {
            val raw = when (channel.name) {
                "temp_c" -> weather.temperatureCelsius
                "precip_mm" -> weather.precipitationMmPerHour
                "wind_kmh" -> weather.windKmh
                "visibility_km" -> weather.visibilityKm
                else -> 0.0
            }
            row[i] = ((raw - channel.mean) / channel.std).toFloat()
        }
        return row
    }

    private fun normalisedToInr(normalised: Float, meta: ModelMeta): Double =
        (normalised * meta.priceStd + meta.priceMean).coerceAtLeast(0.0)

    /**
     * Cheap heuristic: confidence grows linearly with how full the input
     * window is. A real deployment would feed variance between repeated
     * model calls or a calibration set.
     */
    private fun estimateConfidence(history: List<RideOption>): Float =
        estimateConfidenceFromHistorySize(history.size)

    private fun estimateConfidenceFromHistorySize(n: Int): Float =
        (n.toFloat() / ModelAssets.WINDOW).coerceIn(0.1f, 1.0f)

    // -------------------------------------------------------------------------
    // Lazy asset loading
    // -------------------------------------------------------------------------

    /** Returns Unit on success, null when assets are missing. */
    private suspend fun ensureLoaded(): Unit? {
        if (interpreter != null && meta != null) return Unit
        if (loadAttempted && (interpreter == null || meta == null)) return null

        lock.withLock {
            if (interpreter != null && meta != null) return Unit
            loadAttempted = true
            val loadedMeta = tryLoadMeta(context.assets) ?: run {
                Timber.w("Surge model metadata missing — predictor disabled until assets ship.")
                return null
            }
            val loadedInterpreter = tryLoadInterpreter(context.assets) ?: run {
                Timber.w("Surge model TFLite missing — predictor disabled.")
                return null
            }
            meta = loadedMeta
            interpreter = loadedInterpreter
            Timber.i("Surge predictor ready (mean=%.2f std=%.2f).", loadedMeta.priceMean, loadedMeta.priceStd)
        }
        return Unit
    }

    private fun tryLoadMeta(assets: AssetManager): ModelMeta? = try {
        assets.open(ModelAssets.META).use { stream ->
            val json = JSONObject(stream.bufferedReader().readText())
            val featuresPerStep = json.optInt("features", ModelAssets.FEATURES)
            val weatherChannels = parseWeatherChannels(json)
            ModelMeta(
                priceMean = json.getDouble("price_mean"),
                priceStd = json.getDouble("price_std").coerceAtLeast(1e-6),
                featuresPerStep = featuresPerStep,
                weatherChannels = weatherChannels
            )
        }
    } catch (_: FileNotFoundException) {
        null
    } catch (t: Throwable) {
        Timber.w(t, "Failed to parse %s", ModelAssets.META)
        null
    }

    /**
     * Pull weather channel definitions out of meta. The trainer writes a
     * `weather_norm` block when invoked with `--with-weather`; older meta
     * files don't have it, in which case we return an empty list and the
     * predictor stays in 3-feature mode.
     *
     * Block shape (matches train_surge_lstm.py):
     *
     *     "weather_norm": {
     *         "temp_c":        { "mean": 24.0, "std": 4.5 },
     *         "precip_mm":     { "mean": 0.4,  "std": 1.8 },
     *         "wind_kmh":      { "mean": 9.0,  "std": 4.0 },
     *         "visibility_km": { "mean": 8.5,  "std": 2.5 }
     *     }
     */
    private fun parseWeatherChannels(json: JSONObject): List<WeatherChannel> {
        val block = json.optJSONObject("weather_norm") ?: return emptyList()
        val channels = mutableListOf<WeatherChannel>()
        // Iterate in feature_order so channel positions in the tensor match
        // what the trainer wrote (insertion order of the JSON keys is not
        // guaranteed; feature_order is).
        val featureOrder = json.optJSONArray("feature_order")
        val orderedNames = buildList {
            if (featureOrder != null) {
                for (i in 0 until featureOrder.length()) add(featureOrder.getString(i))
            }
        }
        // Skip the always-present price/sin/cos head channels.
        val weatherNames = orderedNames.filter { name ->
            name !in setOf("price_normalised", "hour_sin", "hour_cos")
        }
        for (name in weatherNames) {
            val sub = block.optJSONObject(name) ?: continue
            channels += WeatherChannel(
                name = name,
                mean = sub.optDouble("mean", 0.0),
                std = sub.optDouble("std", 1.0).coerceAtLeast(1e-6)
            )
        }
        return channels
    }

    private fun tryLoadInterpreter(assets: AssetManager): Interpreter? = try {
        val fd = assets.openFd(ModelAssets.TFLITE)
        fd.createInputStream().channel.use { channel ->
            val buffer = channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength
            ).order(ByteOrder.nativeOrder())
            Interpreter(buffer as ByteBuffer, Interpreter.Options().apply { numThreads = 2 })
        }
    } catch (_: FileNotFoundException) {
        null
    } catch (t: Throwable) {
        Timber.e(t, "Failed to load %s", ModelAssets.TFLITE)
        null
    }

    private data class ModelMeta(
        val priceMean: Double,
        val priceStd: Double,
        val featuresPerStep: Int,
        val weatherChannels: List<WeatherChannel>
    ) {
        val usesWeather: Boolean get() = weatherChannels.isNotEmpty()
    }

    private data class WeatherChannel(
        val name: String,
        val mean: Double,
        val std: Double
    )

    companion object {
        /**
         * Bengaluru centroid — used as the default weather query location when
         * no rider coordinate is available (worker tick before the dashboard
         * has resolved a real origin). City-scale weather is uniform enough at
         * this resolution that the cached snapshot is fine for the whole metro.
         */
        private val BENGALURU_CENTROID = LatLng(12.9716, 77.5946)
    }
}
