"""
Real-data variant of `train_on_colab.py` — INDIA-ONLY edition.

Trains the fare estimator on real Indian Uber bookings and pulls real
Indian weather into the surge LSTM, with synthetic CATALOG augmentation
for the slots no public Indian dataset covers.

-----------------------------------------------------------------------------
Why India-only
-----------------------------------------------------------------------------
  Earlier revisions of this trainer used Boston (Uber+Lyft) and NYC Uber
  data with USD→INR conversion. That kept fare *units* in INR but the
  underlying *pricing structure* (per-km rates, surge response, demand
  shape) was American. Indian rideshare economics differ enough — much
  cheaper per km, very different bike/auto market, monsoon-driven surge —
  that training on US fares biases the model toward the wrong rate card.
  This edition drops all foreign data so the learned distribution matches
  what the Android app sees in production.

-----------------------------------------------------------------------------
Dataset sources (all India-based)
-----------------------------------------------------------------------------

  Fare model — real Indian Uber bookings:
    [UBR-IN] Kaggle "yashdevladdha/uber-ride-analytics-dashboard"
             https://www.kaggle.com/datasets/yashdevladdha/uber-ride-analytics-dashboard
             Real Uber India bookings with native INR fares. Columns used:
                 booking_value (INR), ride_distance (km),
                 vehicle_type   (Auto, Bike, eBike, Go Mini, Go Sedan,
                                 Premier Sedan, Uber XL),
                 booking_status (we keep only "Completed"),
                 date / time    (for the hour-of-day feature).
             Maps to provider = UBER, with all 4 tier classes covered:
                 Bike, eBike                          -> BIKE     (0)
                 Auto                                 -> AUTO     (1)
                 Go Mini, Go Sedan, UberX/Go         -> ECONOMY  (2)
                 Premier Sedan, Uber XL              -> PREMIUM  (3)

  Fare model — synthetic (OLA + RAPIDO):
    Neither Ola nor Rapido publishes pricing data, so all OLA tiers and
    all RAPIDO tiers are synthesized from the Bengaluru CATALOG — the
    same rate card the on-device SmartRideDataRepository prices with.
    Training and inference therefore share one source of truth for those
    providers, which is the cleanest contract we can offer without a
    public Indian Ola/Rapido dataset.

  Surge LSTM — real Indian weather:
    [WX-IN]  Kaggle "nelgiriyewithana/indian-weather-repository-daily-snapshot"
             https://www.kaggle.com/datasets/nelgiriyewithana/indian-weather-repository-daily-snapshot
             Daily-updated weather snapshots for Indian cities. Columns used:
                 temperature_celsius, precip_mm, wind_kph, visibility_km
             Z-score normalised; the (mean, std) constants are written
             into surge_forecast_meta.json so the on-device weather
             provider can z-score live readings the same way.

  Surge LSTM — synthetic Bengaluru price curve:
    The bi-modal commute curve (8-10:30 AM and 5:30-8:30 PM peaks) plus
    event pulses and jitter. No public Indian dataset records 5-minute
    surge multiplier history at city scale, so we keep this synthetic and
    inject the real weather above as a side channel.

-----------------------------------------------------------------------------
Datasets considered and rejected (India)
-----------------------------------------------------------------------------
  • shubhambathwal/ola-bike-ride-request-forecast   — demand/timestamp
    only, no pricing.
  • anupammajhi/uber-request-data                   — Bengaluru airport
    request data, no fare column.
  • Any Rapido dataset                              — Rapido is private,
    no public pricing dataset exists.
  • Indian Ola pricing datasets                     — none found that
    publish per-trip rupee amounts at scale.

-----------------------------------------------------------------------------
Inputs
-----------------------------------------------------------------------------
  Set the following environment variables to the downloaded CSV paths:

      UBER_INDIA_CSV  — path to the yashdevladdha rides CSV
      INDIA_WEATHER_CSV — path to the nelgiriyewithana weather CSV

  The companion notebook does this automatically via kagglehub. Both
  paths are optional individually: if a CSV is missing, that source is
  skipped and the trainer falls back to synthetic data for it.

Output (same four files as train_on_colab.py):
  surge_forecast.tflite / surge_forecast_meta.json
  fare_estimator.tflite / fare_estimator_meta.json
"""
from __future__ import annotations

import json
import math
import os
import pathlib
import random
from typing import List, Tuple

import numpy as np
import pandas as pd
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
    pass  # already initialised — too late, but the model trace will reflect this on first run

# =============================================================================
# Output + global config
# =============================================================================
OUTPUT_DIR = pathlib.Path(os.getcwd())

CITY_AVG_KMPH = 22.0        # Bengaluru urban average — used to back-fill duration
                            # since the Uber India CSV has distance but no duration

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
# Bengaluru time-of-day surge curve (used by both synthetic generators below)
# =============================================================================
def time_of_day_surge(hour: float) -> float:
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
# MODEL 1 — Surge LSTM forecaster  (synthetic curve + real Indian weather)
# =============================================================================
# Weather augmentation: the synthetic Bengaluru commute curve still drives the
# bi-modal peak shape (no public Indian dataset has 5-min surge multiplier
# history), but weather features are sampled from a real Indian weather
# Kaggle CSV so the LSTM learns "monsoon rain → surge stays high longer".
# Each training day picks one weather anchor row from the pool and adds
# small per-step drift, so a window sees a coherent weather profile rather
# than flickering noise.
SURGE_WINDOW = 24
SURGE_HORIZON = 6
WEATHER_FEATURES = 4
SURGE_FEATURES = 3 + WEATHER_FEATURES   # price, sin_h, cos_h + 4 weather z-scores
SURGE_STEP_MIN = 5
SURGE_SAMPLES_PER_TIER = 4000
SURGE_EPOCHS = 12
SURGE_BATCH = 64
SURGE_HIDDEN = 32

# Order matters — Android side reads features in this exact slot order.
# These are the canonical names the model + meta JSON use; the loader
# below maps from the Indian weather repo's actual column names
# (temperature_celsius, precip_mm, wind_kph, visibility_km).
WEATHER_COL_NAMES = ["temperature", "precipitation", "windSpeed", "visibility"]
# Mapping from canonical name -> list of acceptable source-CSV column names
# (defensive: the Indian weather dataset has been re-versioned in the past).
INDIA_WEATHER_COLUMN_MAP = {
    "temperature":   ["temperature_celsius", "temp_c", "temperature"],
    "precipitation": ["precip_mm", "precip_in", "precipitation"],
    "windSpeed":     ["wind_kph", "wind_mph", "wind_speed"],
    "visibility":    ["visibility_km", "visibility_miles", "visibility"],
}

SURGE_MODEL_PATH = OUTPUT_DIR / "surge_forecast.tflite"
SURGE_META_PATH = OUTPUT_DIR / "surge_forecast_meta.json"


def _resolve_indian_weather_columns(df: pd.DataFrame) -> dict:
    """Pick the actual CSV column for each canonical weather feature."""
    resolved = {}
    for canonical, candidates in INDIA_WEATHER_COLUMN_MAP.items():
        match = next((c for c in candidates if c in df.columns), None)
        if match is None:
            raise ValueError(
                f"Indian weather CSV missing a column for '{canonical}' "
                f"(tried {candidates}; have {list(df.columns)[:8]}...)"
            )
        resolved[canonical] = match
    return resolved


def load_weather_pool() -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Extract weather features from the Indian weather Kaggle dataset and
    z-score normalise. Returns (pool, means, stds).

    Falls back to a synthetic standard-normal pool if INDIA_WEATHER_CSV
    is unset or the file is missing — the surge LSTM still trains, just
    without real weather variance. Mean/std are written to the meta JSON
    so the Android side can z-score live weather readings the same way
    before feeding the model.
    """
    csv_path = os.environ.get("INDIA_WEATHER_CSV")
    if not csv_path or not os.path.exists(csv_path):
        print("  INDIA_WEATHER_CSV not set / file missing — using synthetic "
              "standard-normal weather pool")
        means = np.zeros(WEATHER_FEATURES, dtype=np.float32)
        stds = np.ones(WEATHER_FEATURES, dtype=np.float32)
        pool = np.random.normal(0.0, 1.0, size=(2000, WEATHER_FEATURES)).astype(np.float32)
        return pool, means, stds

    print(f"Loading Indian weather pool from: {csv_path}")
    df_head = pd.read_csv(csv_path, nrows=1, low_memory=False)
    col_map = _resolve_indian_weather_columns(df_head)
    src_cols = list(col_map.values())
    df = pd.read_csv(csv_path, usecols=src_cols, low_memory=False).dropna()

    # Reorder into our canonical schema and convert to float32.
    arr = np.stack(
        [df[col_map[name]].to_numpy(dtype=np.float32) for name in WEATHER_COL_NAMES],
        axis=-1,
    )
    means = arr.mean(axis=0)
    stds = arr.std(axis=0) + 1e-6
    pool = ((arr - means) / stds).astype(np.float32)
    print(f"  weather pool: {len(pool):,} rows  (source columns = {src_cols})")
    print(f"    means = {dict(zip(WEATHER_COL_NAMES, means.round(2).tolist()))}")
    print(f"    stds  = {dict(zip(WEATHER_COL_NAMES, stds.round(2).tolist()))}")
    return pool, means.astype(np.float32), stds.astype(np.float32)


def simulate_day_with_weather(
    base_price: float, weather_pool: np.ndarray,
) -> Tuple[np.ndarray, np.ndarray]:
    """Generate one day of (prices, weather_sequence) at SURGE_STEP_MIN cadence.

    Anchor weather to a single pool row, drift slightly per step. Surge gets
    a weather boost — positive precipitation z-scores or low visibility push
    the surge multiplier up, which is the relationship we want the LSTM to
    learn so the on-device "wait 10 min" advice respects the rain forecast.
    """
    n = int(24 * 60 / SURGE_STEP_MIN)
    anchor = weather_pool[random.randrange(len(weather_pool))]
    drift = np.random.normal(0.0, 0.15, size=(n, WEATHER_FEATURES)).astype(np.float32)
    weather_seq = (anchor[None, :] + drift).astype(np.float32)

    # WEATHER_COL_NAMES order: [temperature, precipIntensity, windSpeed, visibility]
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


def build_surge_dataset(
    weather_pool: np.ndarray,
) -> Tuple[np.ndarray, np.ndarray, float, float]:
    X: List[np.ndarray] = []
    y: List[np.ndarray] = []
    base_prices = [150.0, 220.0, 320.0]
    per_base = SURGE_SAMPLES_PER_TIER // len(base_prices) + 1
    for base in base_prices:
        for _ in range(per_base):
            day, weather_seq = simulate_day_with_weather(base, weather_pool)
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
    print("MODEL 1 — Surge LSTM forecaster (Bengaluru curve + Indian weather)")
    print("=" * 70)
    print_surge_architecture()
    print_no_pretrain_banner()
    weather_pool, weather_means, weather_stds = load_weather_pool()
    X, y, price_mean, price_std = build_surge_dataset(weather_pool)
    print(f"  X shape: {X.shape}  y shape: {y.shape}")
    split = int(len(X) * 0.9)
    model = build_surge_model()
    model.summary()
    model.fit(
        X[:split], y[:split],
        validation_data=(X[split:], y[split:]),
        epochs=SURGE_EPOCHS, batch_size=SURGE_BATCH, verbose=2,
    )

    # Export through a serving function with a static batch dimension. The
    # standard LSTM uses TensorListReserve for its internal scratchpad, and
    # TFLite can only lower it when element_shape is fully known — a None
    # batch dim leaves it dynamic and conversion fails. Training stays
    # dynamic-batch; the on-device interpreter only ever feeds batch=1
    # anyway, which is what we document in the meta JSON.
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
    print(f"  wrote {SURGE_MODEL_PATH.name}  {len(tflite_bytes) / 1024:.1f} KiB")
    SURGE_META_PATH.write_text(json.dumps({
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
            "temperature_z", "precipitation_z", "windSpeed_z", "visibility_z",
        ],
        "weather_normalisation": {
            "columns": WEATHER_COL_NAMES,
            "means": [float(x) for x in weather_means],
            "stds": [float(x) for x in weather_stds],
            "source": "kaggle:nelgiriyewithana/indian-weather-repository-daily-snapshot",
            "units": {
                "temperature": "celsius",
                "precipitation": "mm",
                "windSpeed": "kph",
                "visibility": "km",
            },
        },
    }, indent=2))


# =============================================================================
# MODEL 2 — Fare estimator MLP  (real Uber India data + synthetic Ola/Rapido augment)
# =============================================================================
# Quantile regression: 3 output heads predicting the 10th / 50th / 90th
# percentile fare (z-score normalised). The Android UI shows the median plus
# (p90 − p10) / 2 as a "± ₹X" confidence band, giving honest uncertainty
# without changing the input contract. Trained with pinball (quantile) loss.
FARE_FEATURES = 11
FARE_EPOCHS = 20
FARE_BATCH = 128
FARE_HIDDEN_1 = 64
FARE_HIDDEN_2 = 32
DIST_NORM = 20.0
DUR_NORM = 60.0
QUANTILES = (0.10, 0.50, 0.90)

# Target mix: ~30k real Uber India + ~12k synthetic = ~42k total.
UBER_INDIA_SAMPLE_LIMIT = 30_000
SYNTH_PER_MISSING_SLOT = 2_000

FARE_MODEL_PATH = OUTPUT_DIR / "fare_estimator.tflite"
FARE_META_PATH = OUTPUT_DIR / "fare_estimator_meta.json"

# Tier classes: 0=BIKE, 1=AUTO, 2=ECONOMY, 3=PREMIUM
# Uber India vehicle_type → our tier class (lowercase + stripped).
UBER_INDIA_TIER_BY_TYPE = {
    "bike": 0, "ebike": 0, "uber moto": 0,
    "auto": 1, "uber auto": 1,
    "go mini": 2, "go sedan": 2, "uberx": 2, "ubergo": 2, "uber go": 2,
    "premier sedan": 3, "uber xl": 3, "uberxl": 3, "black": 3,
}

# Indian catalog used to synthesize the rows that real data can't supply.
# Format matches train_on_colab.py / SmartRideDataRepository.CATALOG:
#   (provider_idx, tier_class, flagfall, per_km, per_min, surge_sensitivity)
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

# All Ola + all Rapido slots are synthesized (no public Indian pricing data
# for either provider). UBER × {BIKE, AUTO, ECONOMY, PREMIUM} comes from the
# real Uber India dataset.
SYNTH_SLOTS = {
    (1, 1), (1, 2), (1, 3),       # Ola Auto / Mini / Prime
    (2, 0), (2, 1), (2, 2),       # Rapido Bike / Auto / Cab Lite
}


def features_for(
    distance_km: float,
    duration_min: float,
    hour: float,
    provider_idx: int,
    tier_cls: int,
) -> np.ndarray:
    provider_onehot = [0.0, 0.0, 0.0]
    provider_onehot[provider_idx] = 1.0
    tier_onehot = [0.0, 0.0, 0.0, 0.0]
    tier_onehot[tier_cls] = 1.0
    return np.array([
        distance_km / DIST_NORM,
        duration_min / DUR_NORM,
        math.sin(2 * math.pi * hour / 24.0),
        math.cos(2 * math.pi * hour / 24.0),
        *provider_onehot,
        *tier_onehot,
    ], dtype=np.float32)


# Uber India CSV has column names with spaces and mixed case; different
# snapshots of the dataset differ slightly. Resolve defensively.
UBER_INDIA_COLUMN_MAP = {
    "booking_value":  ["Booking Value", "booking_value", "Booking_Value", "fare"],
    "ride_distance": ["Ride Distance", "ride_distance", "Ride_Distance",
                      "distance_km", "distance"],
    "vehicle_type":  ["Vehicle Type", "vehicle_type", "Vehicle_Type"],
    "booking_status":["Booking Status", "booking_status", "Booking_Status", "status"],
    "date":          ["Date", "date", "booking_date", "Booking Date"],
    "time":          ["Time", "time", "booking_time", "Booking Time"],
}


def _resolve_uber_india_columns(df: pd.DataFrame) -> dict:
    """Pick the actual CSV column for each canonical field we need."""
    resolved = {}
    for canonical, candidates in UBER_INDIA_COLUMN_MAP.items():
        match = next((c for c in candidates if c in df.columns), None)
        if match is None:
            # 'time' is optional — we can fall back to date-only if absent.
            if canonical == "time":
                resolved[canonical] = None
                continue
            raise ValueError(
                f"Uber India CSV missing a column for '{canonical}' "
                f"(tried {candidates}; have {list(df.columns)[:10]}...)"
            )
        resolved[canonical] = match
    return resolved


def load_uber_india_rides() -> pd.DataFrame:
    """Load + clean Kaggle 'yashdevladdha/uber-ride-analytics-dashboard'.

    Keeps only Completed bookings, normalises vehicle_type to the classes
    in UBER_INDIA_TIER_BY_TYPE, and extracts hour-of-day from the date/time
    columns. Fares are already in INR — no currency conversion applied.

    Skipped silently (returns empty DataFrame) if UBER_INDIA_CSV is unset,
    so the pipeline still runs on synthetic rows alone.
    """
    csv_path = os.environ.get("UBER_INDIA_CSV")
    if not csv_path or not os.path.exists(csv_path):
        print("Skipping Uber India dataset (UBER_INDIA_CSV not set or file missing)")
        return pd.DataFrame()

    print(f"Loading Uber India rides from: {csv_path}")
    df_head = pd.read_csv(csv_path, nrows=1, low_memory=False)
    col_map = _resolve_uber_india_columns(df_head)
    src_cols = [c for c in col_map.values() if c is not None]
    df = pd.read_csv(csv_path, usecols=src_cols, low_memory=False)

    # Keep only completed rides; everything else (cancelled / incomplete /
    # no-driver) has a zero or missing booking_value.
    status_col = col_map["booking_status"]
    df = df[df[status_col].astype(str).str.lower().str.strip() == "completed"]

    df = df.rename(columns={
        col_map["booking_value"]: "booking_value_inr",
        col_map["ride_distance"]: "ride_distance_km",
        col_map["vehicle_type"]: "vehicle_type",
        col_map["date"]: "ride_date",
    })
    keep = ["booking_value_inr", "ride_distance_km", "vehicle_type", "ride_date"]
    if col_map["time"] is not None:
        df = df.rename(columns={col_map["time"]: "ride_time"})
        keep.append("ride_time")
    df = df[keep].dropna()

    df["vehicle_type_lc"] = df["vehicle_type"].astype(str).str.lower().str.strip()
    df = df[df["vehicle_type_lc"].isin(UBER_INDIA_TIER_BY_TYPE.keys())]

    df = df[(df["ride_distance_km"] > 0.3) & (df["ride_distance_km"] < 40.0)]
    df = df[(df["booking_value_inr"] > 30.0) & (df["booking_value_inr"] < 10_000.0)]

    # Hour of day — prefer the separate time column, fall back to the
    # combined date+time parse if only one is present.
    if "ride_time" in df.columns:
        hour = pd.to_datetime(
            df["ride_time"].astype(str), errors="coerce", format="mixed",
        ).dt.hour
    else:
        hour = pd.to_datetime(df["ride_date"], errors="coerce").dt.hour
    df = df.assign(hour=hour).dropna(subset=["hour"])
    df["hour"] = df["hour"].astype(int)

    df = df.reset_index(drop=True)
    print(f"  kept {len(df):,} rows after cleaning")
    print(f"  vehicle_type distribution: "
          f"{df['vehicle_type_lc'].value_counts().to_dict()}")
    return df


def uber_india_rows_to_features(df: pd.DataFrame) -> Tuple[np.ndarray, np.ndarray]:
    """Turn the Uber India DataFrame into (features, fare_inr) arrays.

    Provider is always UBER (0). Tier comes from UBER_INDIA_TIER_BY_TYPE —
    covers BIKE/AUTO/ECONOMY/PREMIUM so all four tier classes get real
    Indian pricing signal for at least the Uber slot.
    """
    if df.empty:
        return (
            np.empty((0, FARE_FEATURES), dtype=np.float32),
            np.empty((0,), dtype=np.float32),
        )
    if len(df) > UBER_INDIA_SAMPLE_LIMIT:
        df = df.sample(UBER_INDIA_SAMPLE_LIMIT, random_state=RANDOM_SEED).reset_index(drop=True)

    n = len(df)
    X = np.zeros((n, FARE_FEATURES), dtype=np.float32)
    y = np.zeros((n,), dtype=np.float32)

    dist_km = df["ride_distance_km"].to_numpy(dtype=float)
    # Uber India CSV has no duration column — back-fill from distance at a
    # Bengaluru urban average, then add a beta-distributed traffic delay so
    # peak-hour rows see realistic congestion.
    base_min = np.maximum(3.0, dist_km / CITY_AVG_KMPH * 60.0)
    delay_frac = np.clip(np.random.beta(2.0, 4.0, size=n), 0.0, 0.8)
    duration_min = base_min * (1.0 + delay_frac)
    hours = df["hour"].to_numpy().astype(float)
    tier_cls = df["vehicle_type_lc"].map(UBER_INDIA_TIER_BY_TYPE).to_numpy().astype(int)
    fare_inr = df["booking_value_inr"].to_numpy(dtype=float)

    for i in range(n):
        X[i] = features_for(
            distance_km=float(dist_km[i]),
            duration_min=float(duration_min[i]),
            hour=float(hours[i]),
            provider_idx=0,                 # UBER
            tier_cls=int(tier_cls[i]),
        )
        y[i] = float(fare_inr[i])
    return X, y


def synth_rows_for_slot(
    provider_idx: int, tier_cls: int, n: int,
) -> Tuple[np.ndarray, np.ndarray]:
    """Synthetic fare rows for a (provider, tier) slot the Uber India dataset
    doesn't cover (all OLA + all RAPIDO slots).

    Uses the same CATALOG formula train_on_colab.py ships, since that's also
    what SmartRideDataRepository prices with on-device. The surge_sensitivity
    is what distinguishes bike/auto from economy/premium in the catalog.
    """
    entry = next((e for e in CATALOG if e[0] == provider_idx and e[1] == tier_cls), None)
    if entry is None:
        raise ValueError(f"No CATALOG entry for slot ({provider_idx}, {tier_cls})")
    _, _, flagfall, per_km, per_min, sens = entry

    X = np.zeros((n, FARE_FEATURES), dtype=np.float32)
    y = np.zeros((n,), dtype=np.float32)
    for i in range(n):
        distance_km = float(np.clip(np.random.lognormal(mean=1.7, sigma=0.5), 0.5, 35.0))
        free_flow_min = max(3.0, distance_km / 30.0 * 60.0)
        delay_frac = float(np.clip(np.random.beta(2.0, 4.0), 0.0, 0.8))
        trip_min = free_flow_min * (1.0 + delay_frac)
        hour = random.uniform(0.0, 24.0)
        tod = time_of_day_surge(hour)
        surge = 1.0 + tod + delay_frac * 1.8 * sens + np.random.normal(0.0, 0.05)
        surge = float(np.clip(surge, 1.0, 3.5))
        base = flagfall + per_km * distance_km + per_min * trip_min
        fare = base * surge
        X[i] = features_for(distance_km, trip_min, hour, provider_idx, tier_cls)
        y[i] = float(fare)
    return X, y


def build_fare_dataset() -> Tuple[np.ndarray, np.ndarray, float, float, dict]:
    # Uber India (real, all 4 tiers covered via UBER_INDIA_TIER_BY_TYPE)
    df_ubr = load_uber_india_rides()
    X_ubr, y_ubr = uber_india_rows_to_features(df_ubr)
    if len(X_ubr):
        print(f"  Uber India rows: {len(X_ubr):,}  mean fare Rs{y_ubr.mean():.0f}")

    # Synthetic — slots no public Indian dataset covers (all OLA + all RAPIDO)
    X_synth_list: List[np.ndarray] = []
    y_synth_list: List[np.ndarray] = []
    slot_counts = {}
    for (p_idx, t_cls) in sorted(SYNTH_SLOTS):
        Xs, ys = synth_rows_for_slot(p_idx, t_cls, SYNTH_PER_MISSING_SLOT)
        X_synth_list.append(Xs)
        y_synth_list.append(ys)
        slot_counts[f"provider={p_idx},tier={t_cls}"] = len(Xs)
    X_synth = np.concatenate(X_synth_list, axis=0) if X_synth_list else np.empty((0, FARE_FEATURES))
    y_synth = np.concatenate(y_synth_list, axis=0) if y_synth_list else np.empty((0,))
    print(f"  Synth rows:      {len(X_synth):,}  (missing slots = {slot_counts})")

    X = np.concatenate([X_ubr, X_synth], axis=0).astype(np.float32)
    y = np.concatenate([y_ubr, y_synth], axis=0).astype(np.float32)

    # Shuffle so train/val split isn't biased toward the synthetic tail.
    perm = np.random.permutation(len(X))
    X = X[perm]
    y = y[perm]

    fare_mean = float(y.mean())
    fare_std = float(y.std() + 1e-6)
    y_norm = (y - fare_mean) / fare_std

    stats = {
        "uber_india_rows": int(len(X_ubr)),
        "synth_rows": int(len(X_synth)),
        "total_rows": int(len(X)),
        "uber_india_fare_mean_inr": float(y_ubr.mean()) if len(y_ubr) else None,
        "synth_slot_counts": slot_counts,
        "datasets": [
            "kaggle:yashdevladdha/uber-ride-analytics-dashboard" if len(X_ubr) else None,
            "synthetic:CATALOG (Ola + Rapido tiers)",
        ],
    }
    return X, y_norm, fare_mean, fare_std, stats


def pinball_loss(quantiles: Tuple[float, ...] = QUANTILES):
    """Quantile (a.k.a. pinball) loss: average over the 3 heads of
    max(q * e, (q - 1) * e), where e = y_true - y_pred. Each head learns its
    own conditional quantile of the target distribution.
    """
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
    print("MODEL 2 — Fare estimator MLP (Uber India + synthetic Ola/Rapido augment)")
    print("=" * 70)
    print_fare_architecture()
    print_no_pretrain_banner()
    X, y, fare_mean, fare_std, stats = build_fare_dataset()
    print(f"  X shape: {X.shape}  y shape: {y.shape}")
    print(f"  fare_mean=Rs{fare_mean:.2f}  fare_std=Rs{fare_std:.2f}")

    split = int(len(X) * 0.9)
    model = build_fare_model()
    model.summary()
    model.fit(
        X[:split], y[:split],
        validation_data=(X[split:], y[split:]),
        epochs=FARE_EPOCHS, batch_size=FARE_BATCH, verbose=2,
    )

    print("\nSample predictions (val set, [p10, p50, p90] in INR):")
    preds = model.predict(X[split:split + 5], verbose=0)
    for i in range(len(preds)):
        # Sort ascending so p10 ≤ p50 ≤ p90 even when the model crosses
        # quantiles slightly — same trick the on-device side will use.
        p10, p50, p90 = sorted(float(v) for v in preds[i])
        p10_inr = p10 * fare_std + fare_mean
        p50_inr = p50 * fare_std + fare_mean
        p90_inr = p90 * fare_std + fare_mean
        actual_inr = float(y[split + i]) * fare_std + fare_mean
        band = (p90_inr - p10_inr) / 2.0
        print(
            f"  p50=Rs{p50_inr:>4.0f}  +/-Rs{band:>3.0f}  "
            f"(p10=Rs{p10_inr:>4.0f}, p90=Rs{p90_inr:>4.0f})  actual=Rs{actual_inr:>4.0f}"
        )

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_bytes = converter.convert()
    FARE_MODEL_PATH.write_bytes(tflite_bytes)
    print(f"\n  wrote {FARE_MODEL_PATH.name}  {len(tflite_bytes) / 1024:.1f} KiB")

    meta = {
        "input_shape": [1, FARE_FEATURES],
        "output_shape": [1, len(QUANTILES)],
        "features": FARE_FEATURES,
        "feature_order": [
            "distance_norm", "duration_norm", "hour_sin", "hour_cos",
            "provider_uber", "provider_ola", "provider_rapido",
            "tier_bike", "tier_auto", "tier_economy", "tier_premium",
        ],
        "output_order": ["p10", "p50", "p90"],
        "quantiles": list(QUANTILES),
        "distance_norm_divisor_km": DIST_NORM,
        "duration_norm_divisor_min": DUR_NORM,
        "fare_mean_inr": fare_mean,
        "fare_std_inr": fare_std,
        "training_data": {
            "source": "Uber India (yashdevladdha) + synthetic CATALOG augment for Ola/Rapido",
            "currency": "INR (native — no currency conversion applied)",
            **stats,
        },
    }
    FARE_META_PATH.write_text(json.dumps(meta, indent=2))


# =============================================================================
# Entry
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
