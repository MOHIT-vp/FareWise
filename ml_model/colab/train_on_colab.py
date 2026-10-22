"""
End-to-end trainer that runs BOTH models back-to-back in a single Python
process, designed to be executed from a Google Colab notebook.

What it produces (all four land in the current working directory, which is
`/content/` on Colab by default):

    surge_forecast.tflite          LSTM price forecaster     (~20–40 KiB)
    surge_forecast_meta.json       normalisation + shape metadata
    fare_estimator.tflite          MLP fare regressor        (~15–25 KiB)
    fare_estimator_meta.json       feature layout + fare-norm constants

After the run finishes, the companion notebook (`EIS_Train_Models.ipynb`)
triggers `google.colab.files.download(...)` for each artefact so you can
drop them straight into `app/src/main/assets/` in the Android project.

Why a separate Colab file?
  • The local `train_surge_lstm.py` and `train_fare_estimator.py` both write
    into `../app/src/main/assets/`, which only exists inside the Gradle
    checkout. On Colab there's no repo, so we resolve artefacts to the
    current working directory instead.
  • Colab gives you a free GPU/TPU; this script doesn't need one (training
    finishes in ~2 min on CPU) but the option is there if you bump SAMPLES.
  • The logic of both models is duplicated here deliberately so the notebook
    has zero external imports beyond `tensorflow` + `numpy` — no need to
    upload the rest of the repo.
"""
from __future__ import annotations

import json
import math
import os
import pathlib
import random
from typing import List, Tuple

import numpy as np
import tensorflow as tf
from tensorflow.keras import layers, models

# TFLite cannot lower the cuDNN-backed LSTM kernel (CudnnRNNV3). On a Colab
# GPU runtime Keras auto-selects that kernel and conversion blows up with
# `'tf.CudnnRNNV3' op is neither a custom op nor a flex op`. Hiding the GPU
# forces Keras to build the standard LSTM, which has a fused TFLite op.
# Training on CPU is fine — the surge model is ~6k params and takes <10 sec.
try:
    tf.config.set_visible_devices([], "GPU")
except RuntimeError:
    pass

# =============================================================================
# Output location
# =============================================================================
# On Colab this is `/content/`. Locally (if you just `python train_on_colab.py`
# from this folder) it becomes `ml_model/colab/`. Either way, the notebook
# downloads by filename so the absolute path doesn't matter.
OUTPUT_DIR = pathlib.Path(os.getcwd())

RANDOM_SEED = 42
random.seed(RANDOM_SEED)
np.random.seed(RANDOM_SEED)
tf.random.set_seed(RANDOM_SEED)


# =============================================================================
# Architecture diagrams (printed at the top of each model's training run)
# =============================================================================
# Both models initialise from random weights — Glorot uniform for Dense kernels,
# orthogonal for the LSTM recurrent kernel, zeros for biases. Random seeds are
# fixed (RANDOM_SEED = 42) so reruns are deterministic, but no checkpoint or
# pre-trained weight file is loaded at any point.
def print_no_pretrain_banner() -> None:
    print(">> Initialising from random weights — NO pre-trained checkpoints are loaded.")
    print(">> Glorot uniform (Dense kernels) | orthogonal (LSTM recurrent) | zeros (biases)")
    print(f">> Deterministic init via RANDOM_SEED = {RANDOM_SEED}")


def print_surge_architecture() -> None:
    print(r"""
+-------------------------------------------------------------------------+
| SURGE LSTM FORECASTER  (trained from scratch — no pre-trained weights)  |
+-------------------------------------------------------------------------+
| Input   (1, 24, 7)   float32                                            |
|         24 timesteps x 7 features per step:                             |
|         [price_norm, sin_h, cos_h,                                      |
|          temperature_z, precipIntensity_z, windSpeed_z, visibility_z]   |
|                       |                                                 |
|                       v                                                 |
|         LSTM(units=32, default tanh)         ~5,120 params              |
|                       |                                                 |
|                       v                                                 |
|         Dense(32, relu)                      ~1,056 params              |
|                       |                                                 |
|                       v                                                 |
|         Dense(6, linear)                       ~198 params              |
|                       |                                                 |
|                       v                                                 |
| Output  (1, 6)       float32   next 6 x 5min = 30min in normalised price|
|                                                                         |
| Loss    Mean Squared Error                                              |
| Optim.  Adam(lr=1e-3)                                                   |
+-------------------------------------------------------------------------+
""")


def print_fare_architecture() -> None:
    print(r"""
+-------------------------------------------------------------------------+
| FARE ESTIMATOR MLP  (trained from scratch — no pre-trained weights)     |
+-------------------------------------------------------------------------+
| Input   (1, 11)      float32                                            |
|         [distance_norm, duration_norm, hour_sin, hour_cos,              |
|          provider_uber, provider_ola, provider_rapido,                  |
|          tier_bike, tier_auto, tier_economy, tier_premium]              |
|                       |                                                 |
|                       v                                                 |
|         Dense(64, relu)                        ~768 params              |
|                       |                                                 |
|                       v                                                 |
|         Dense(32, relu)                      ~2,080 params              |
|                       |                                                 |
|                       v                                                 |
|         Dense(3, linear)   <-- 3 quantile heads ~99 params              |
|                       |                                                 |
|                       v                                                 |
| Output  (1, 3)       float32   [p10, p50, p90] of normalised fare       |
|                                (sort ascending on-device, then          |
|                                 de-normalise with mean / std from meta) |
|                                                                         |
| Loss    Pinball (quantile) loss averaged across the 3 heads             |
| Optim.  Adam(lr=1e-3)                                                   |
+-------------------------------------------------------------------------+
""")


# =============================================================================
# Shared: Bengaluru time-of-day surge curve
# =============================================================================
def time_of_day_surge(hour: float) -> float:
    """Bi-modal commute curve + nightlife bump + late-night scarcity tail."""
    if 8.0 <= hour <= 10.5:
        return 0.55
    if 17.5 <= hour <= 20.5:
        return 0.75
    if 22.0 <= hour <= 23.75:
        return 0.25
    if 0.0 <= hour <= 5.0:
        return 0.15
    return 0.0


# =============================================================================
# MODEL 1 — Surge LSTM forecaster
#
# Contract (must match SurgePredictorImpl.kt):
#   input  : (1, 24, 7)  float32  — last 2 h, step 5 min,
#            [price, sin_h, cos_h, temp_z, precip_z, wind_z, vis_z]
#   output : (1, 6)      float32  — next 30 min in normalised price
#
# Weather features are synthetic standard-normal samples in this trainer
# (real Indian weather is used in `train_on_real_data.py`). Even synthetic
# noise lets the model learn the input slot layout, so the on-device side
# can be wired up against a real weather provider without retraining a
# different architecture.
# =============================================================================
SURGE_WINDOW = 24
SURGE_HORIZON = 6
WEATHER_FEATURES = 4
SURGE_FEATURES = 3 + WEATHER_FEATURES
SURGE_STEP_MIN = 5
SURGE_SAMPLES_PER_TIER = 4000
SURGE_EPOCHS = 12
SURGE_BATCH = 64
SURGE_HIDDEN = 32

WEATHER_COL_NAMES = ["temperature", "precipIntensity", "windSpeed", "visibility"]

SURGE_MODEL_PATH = OUTPUT_DIR / "surge_forecast.tflite"
SURGE_META_PATH = OUTPUT_DIR / "surge_forecast_meta.json"


def simulate_day(base_price: float) -> Tuple[np.ndarray, np.ndarray]:
    """Return one day of (prices, weather_seq) at `SURGE_STEP_MIN` resolution.

    Weather is a standard-normal anchor + per-step drift. Surge gets a small
    boost when the synthetic precip/visibility z-scores cross thresholds —
    same shape of signal the real-data trainer sees, so the LSTM learns to
    look at those slots instead of zeroing them out.
    """
    n = int(24 * 60 / SURGE_STEP_MIN)
    anchor = np.random.normal(0.0, 1.0, size=(WEATHER_FEATURES,)).astype(np.float32)
    drift = np.random.normal(0.0, 0.15, size=(n, WEATHER_FEATURES)).astype(np.float32)
    weather_seq = (anchor[None, :] + drift).astype(np.float32)
    precip_z = weather_seq[:, 1]
    vis_z = weather_seq[:, 3]
    weather_boost = np.maximum(precip_z, 0.0) * 0.20 + np.maximum(-vis_z, 0.0) * 0.10

    prices = np.zeros(n, dtype=np.float32)
    event_mask = np.zeros(n, dtype=np.float32)
    for _ in range(random.randint(0, 3)):
        start = random.randint(0, n - 1)
        duration = random.randint(6, 18)
        end = min(n, start + duration)
        magnitude = random.uniform(0.3, 0.9)
        event_mask[start:end] += magnitude

    for i in range(n):
        hour = (i * SURGE_STEP_MIN) / 60.0
        surge = 1.0 + time_of_day_surge(hour) + event_mask[i] + float(weather_boost[i])
        jitter = np.random.normal(0.0, 0.06)
        prices[i] = max(base_price * (surge + jitter), base_price * 0.9)
    return prices, weather_seq


def build_surge_dataset() -> Tuple[np.ndarray, np.ndarray, float, float]:
    X: List[np.ndarray] = []
    y: List[np.ndarray] = []
    base_prices = [150.0, 220.0, 320.0]
    per_base = SURGE_SAMPLES_PER_TIER // len(base_prices) + 1
    for base in base_prices:
        for _ in range(per_base):
            day, weather_seq = simulate_day(base)
            start = random.randint(0, len(day) - SURGE_WINDOW - SURGE_HORIZON - 1)
            window = day[start:start + SURGE_WINDOW]
            future = day[start + SURGE_WINDOW:start + SURGE_WINDOW + SURGE_HORIZON]
            weather_window = weather_seq[start:start + SURGE_WINDOW]

            hours = np.array(
                [(start + k) * SURGE_STEP_MIN / 60.0 for k in range(SURGE_WINDOW)],
                dtype=np.float32,
            )
            sin_h = np.sin(2 * math.pi * hours / 24.0).astype(np.float32)
            cos_h = np.cos(2 * math.pi * hours / 24.0).astype(np.float32)
            time_features = np.stack([window, sin_h, cos_h], axis=-1)
            feats = np.concatenate([time_features, weather_window], axis=-1)
            X.append(feats)
            y.append(future.astype(np.float32))

    X_arr = np.stack(X)
    y_arr = np.stack(y)
    price_mean = float(X_arr[..., 0].mean())
    price_std = float(X_arr[..., 0].std() + 1e-6)
    X_arr[..., 0] = (X_arr[..., 0] - price_mean) / price_std
    y_norm = (y_arr - price_mean) / price_std
    return X_arr, y_norm, price_mean, price_std


def build_surge_model() -> tf.keras.Model:
    inputs = layers.Input(shape=(SURGE_WINDOW, SURGE_FEATURES), name="history")
    x = layers.LSTM(SURGE_HIDDEN, name="lstm")(inputs)
    x = layers.Dense(SURGE_HIDDEN, activation="relu", name="dense_hidden")(x)
    outputs = layers.Dense(SURGE_HORIZON, name="forecast")(x)
    model = models.Model(inputs=inputs, outputs=outputs, name="surge_forecast")
    model.compile(optimizer=tf.keras.optimizers.Adam(1e-3), loss="mse", metrics=["mae"])
    return model


def train_surge_model() -> None:
    print("=" * 70)
    print("MODEL 1 — Surge LSTM forecaster")
    print("=" * 70)
    print_surge_architecture()
    print_no_pretrain_banner()
    print("Generating synthetic Bengaluru surge data...")
    X, y, price_mean, price_std = build_surge_dataset()
    print(f"  X shape: {X.shape}  y shape: {y.shape}")
    print(f"  price_mean={price_mean:.2f}  price_std={price_std:.2f}")

    split = int(len(X) * 0.9)
    X_train, X_val = X[:split], X[split:]
    y_train, y_val = y[:split], y[split:]

    print("Training LSTM...")
    model = build_surge_model()
    model.summary()
    model.fit(
        X_train, y_train,
        validation_data=(X_val, y_val),
        epochs=SURGE_EPOCHS,
        batch_size=SURGE_BATCH,
        verbose=2,
    )

    print(f"Converting to TFLite -> {SURGE_MODEL_PATH}")
    # Export through a serving function with a static batch dimension. The
    # standard LSTM uses TensorListReserve for its internal scratchpad, and
    # TFLite can only lower it when element_shape is fully known — a None
    # batch dim leaves it dynamic and conversion fails. Training stays
    # dynamic-batch; on-device we only ever feed batch=1, which is what we
    # already document in the meta JSON.
    @tf.function(input_signature=[
        tf.TensorSpec(shape=[1, SURGE_WINDOW, SURGE_FEATURES],
                      dtype=tf.float32, name="history")
    ])
    def serving_fn(history):
        return model(history)

    concrete = serving_fn.get_concrete_function()
    converter = tf.lite.TFLiteConverter.from_concrete_functions([concrete], model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_bytes = converter.convert()
    SURGE_MODEL_PATH.write_bytes(tflite_bytes)
    print(f"  wrote {len(tflite_bytes) / 1024:.1f} KiB")

    meta = {
        "input_shape": [1, SURGE_WINDOW, SURGE_FEATURES],
        "output_shape": [1, SURGE_HORIZON],
        "window": SURGE_WINDOW,
        "horizon": SURGE_HORIZON,
        "features": SURGE_FEATURES,
        "step_minutes": SURGE_STEP_MIN,
        "price_mean": price_mean,
        "price_std": price_std,
        "feature_order": [
            "price_normalised", "hour_sin", "hour_cos",
            "temperature_z", "precipIntensity_z", "windSpeed_z", "visibility_z",
        ],
        "weather_normalisation": {
            "columns": WEATHER_COL_NAMES,
            "means": [0.0] * WEATHER_FEATURES,
            "stds": [1.0] * WEATHER_FEATURES,
            "source": "synthetic standard-normal (use real-data trainer for live stats)",
        },
    }
    SURGE_META_PATH.write_text(json.dumps(meta, indent=2))
    print(f"  wrote metadata -> {SURGE_META_PATH}")


# =============================================================================
# MODEL 2 — Fare estimator MLP
#
# Contract (must match FareEstimatorImpl.kt on the Android side):
#   input  : (1, 11) float32
#            [distance_norm, duration_norm, hour_sin, hour_cos,
#             provider_uber, provider_ola, provider_rapido,
#             tier_bike, tier_auto, tier_economy, tier_premium]
#   output : (1, 3)  float32 — [p10, p50, p90] of normalised fare
#            De-normalise each on device using fare_mean_inr + fare_std_inr,
#            then sort ascending to handle quantile crossing. The UI shows
#            p50 ± (p90 - p10) / 2 as the fare confidence band.
#
# Trained with pinball (quantile) loss so each output head learns its own
# conditional quantile of the target distribution.
# =============================================================================
FARE_FEATURES = 11
FARE_SAMPLES = 30_000
FARE_EPOCHS = 20
FARE_BATCH = 128
FARE_HIDDEN_1 = 64
FARE_HIDDEN_2 = 32
DIST_NORM = 20.0    # divide distance_km by this before feeding the model
DUR_NORM = 60.0     # divide duration_min by this
QUANTILES = (0.10, 0.50, 0.90)

FARE_MODEL_PATH = OUTPUT_DIR / "fare_estimator.tflite"
FARE_META_PATH = OUTPUT_DIR / "fare_estimator_meta.json"

# Must mirror SmartRideDataRepository.CATALOG on the Android side.
# Each entry: (provider_idx, tier_class, flagfall, per_km, per_min, surge_sens)
#   provider_idx: 0=UBER, 1=OLA, 2=RAPIDO
#   tier_class:   0=BIKE, 1=AUTO, 2=ECONOMY, 3=PREMIUM
CATALOG = [
    (0, 2, 50.0, 14.0, 1.5, 1.00),   # Uber UberGo
    (0, 3, 65.0, 18.0, 2.0, 1.15),   # Uber Premier
    (0, 1, 30.0, 11.0, 1.0, 0.70),   # Uber Auto
    (1, 2, 48.0, 13.5, 1.4, 0.95),   # Ola Mini
    (1, 3, 70.0, 19.0, 2.2, 1.20),   # Ola Prime
    (1, 1, 30.0, 11.5, 1.0, 0.65),   # Ola Auto
    (2, 0, 25.0,  6.5, 0.8, 0.30),   # Rapido Bike
    (2, 1, 30.0, 11.0, 1.0, 0.60),   # Rapido Auto
    (2, 2, 42.0, 12.5, 1.4, 0.85),   # Rapido Cab Lite
]


def synth_fare_sample() -> Tuple[np.ndarray, float]:
    distance_km = float(np.clip(np.random.lognormal(mean=1.7, sigma=0.5), 0.5, 35.0))
    free_flow_min = max(3.0, distance_km / 30.0 * 60.0)
    delay_frac = float(np.clip(np.random.beta(2.0, 4.0), 0.0, 0.8))
    trip_min = free_flow_min * (1.0 + delay_frac)

    hour = random.uniform(0.0, 24.0)
    tod = time_of_day_surge(hour)

    provider_idx, tier_cls, flagfall, per_km, per_min, sens = random.choice(CATALOG)

    surge = 1.0 + tod + delay_frac * 1.8 * sens + np.random.normal(0.0, 0.05)
    surge = float(np.clip(surge, 1.0, 3.5))

    base = flagfall + per_km * distance_km + per_min * trip_min
    fare = base * surge

    provider_onehot = [0.0, 0.0, 0.0]
    provider_onehot[provider_idx] = 1.0
    tier_onehot = [0.0, 0.0, 0.0, 0.0]
    tier_onehot[tier_cls] = 1.0

    feats = np.array([
        distance_km / DIST_NORM,
        trip_min / DUR_NORM,
        math.sin(2 * math.pi * hour / 24.0),
        math.cos(2 * math.pi * hour / 24.0),
        *provider_onehot,
        *tier_onehot,
    ], dtype=np.float32)
    return feats, float(fare)


def build_fare_dataset() -> Tuple[np.ndarray, np.ndarray, float, float]:
    X = np.zeros((FARE_SAMPLES, FARE_FEATURES), dtype=np.float32)
    y = np.zeros((FARE_SAMPLES,), dtype=np.float32)
    for i in range(FARE_SAMPLES):
        feats, fare = synth_fare_sample()
        X[i] = feats
        y[i] = fare

    fare_mean = float(y.mean())
    fare_std = float(y.std() + 1e-6)
    y_norm = (y - fare_mean) / fare_std
    return X, y_norm, fare_mean, fare_std


def pinball_loss(quantiles: Tuple[float, ...] = QUANTILES):
    """Quantile loss: averaged max(q*e, (q-1)*e) per head."""
    q = tf.constant(quantiles, dtype=tf.float32)

    def loss(y_true, y_pred):
        y_true = tf.reshape(y_true, [-1, 1])
        e = y_true - y_pred
        return tf.reduce_mean(tf.maximum(q * e, (q - 1.0) * e))

    return loss


def build_fare_model() -> tf.keras.Model:
    inputs = layers.Input(shape=(FARE_FEATURES,), name="trip_features")
    x = layers.Dense(FARE_HIDDEN_1, activation="relu")(inputs)
    x = layers.Dense(FARE_HIDDEN_2, activation="relu")(x)
    outputs = layers.Dense(len(QUANTILES), name="fare_quantiles")(x)
    model = models.Model(inputs=inputs, outputs=outputs, name="fare_estimator")
    model.compile(
        optimizer=tf.keras.optimizers.Adam(1e-3),
        loss=pinball_loss(),
        metrics=["mae"],
    )
    return model


def train_fare_model() -> None:
    print()
    print("=" * 70)
    print("MODEL 2 — Fare estimator MLP")
    print("=" * 70)
    print_fare_architecture()
    print_no_pretrain_banner()
    print("Generating synthetic fare training data...")
    X, y, fare_mean, fare_std = build_fare_dataset()
    print(f"  X shape: {X.shape}  y shape: {y.shape}")
    print(f"  fare_mean=Rs{fare_mean:.2f}  fare_std=Rs{fare_std:.2f}")

    split = int(len(X) * 0.9)
    X_train, X_val = X[:split], X[split:]
    y_train, y_val = y[:split], y[split:]

    print("Training fare MLP...")
    model = build_fare_model()
    model.summary()
    model.fit(
        X_train, y_train,
        validation_data=(X_val, y_val),
        epochs=FARE_EPOCHS,
        batch_size=FARE_BATCH,
        verbose=2,
    )

    print("\nSample predictions (val set, [p10, p50, p90] in INR):")
    preds = model.predict(X_val[:5], verbose=0)
    for i in range(len(preds)):
        p10, p50, p90 = sorted(float(v) for v in preds[i])
        p10_inr = p10 * fare_std + fare_mean
        p50_inr = p50 * fare_std + fare_mean
        p90_inr = p90 * fare_std + fare_mean
        actual_inr = float(y_val[i]) * fare_std + fare_mean
        band = (p90_inr - p10_inr) / 2.0
        print(
            f"  p50=Rs{p50_inr:>4.0f}  +/-Rs{band:>3.0f}  "
            f"(p10=Rs{p10_inr:>4.0f}, p90=Rs{p90_inr:>4.0f})  actual=Rs{actual_inr:>4.0f}"
        )

    print(f"\nConverting to TFLite -> {FARE_MODEL_PATH}")
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_bytes = converter.convert()
    FARE_MODEL_PATH.write_bytes(tflite_bytes)
    print(f"  wrote {len(tflite_bytes) / 1024:.1f} KiB")

    meta = {
        "input_shape": [1, FARE_FEATURES],
        "output_shape": [1, len(QUANTILES)],
        "features": FARE_FEATURES,
        "feature_order": [
            "distance_norm",
            "duration_norm",
            "hour_sin",
            "hour_cos",
            "provider_uber",
            "provider_ola",
            "provider_rapido",
            "tier_bike",
            "tier_auto",
            "tier_economy",
            "tier_premium",
        ],
        "output_order": ["p10", "p50", "p90"],
        "quantiles": list(QUANTILES),
        "distance_norm_divisor_km": DIST_NORM,
        "duration_norm_divisor_min": DUR_NORM,
        "fare_mean_inr": fare_mean,
        "fare_std_inr": fare_std,
    }
    FARE_META_PATH.write_text(json.dumps(meta, indent=2))
    print(f"  wrote metadata -> {FARE_META_PATH}")


# =============================================================================
# Entry point
# =============================================================================
def main() -> None:
    print(f"Writing artefacts into: {OUTPUT_DIR}")
    train_surge_model()
    train_fare_model()
    print()
    print("=" * 70)
    print("Done. Four artefacts produced:")
    for p in (SURGE_MODEL_PATH, SURGE_META_PATH, FARE_MODEL_PATH, FARE_META_PATH):
        print(f"  {p.name:32s} {p.stat().st_size / 1024:>8.1f} KiB")
    print("=" * 70)


if __name__ == "__main__":
    main()
