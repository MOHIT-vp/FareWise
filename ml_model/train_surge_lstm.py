"""
Trains a tiny LSTM surge-price forecaster — INDIA-ONLY edition — and
exports it as TensorFlow Lite.

Data sources (all India-based — no foreign signal ever enters the weights):

  1. Synthetic Bengaluru commute curve.
       No public dataset records 5-minute surge multiplier history at
       city scale, so we generate the bi-modal AM/PM peak shape, event
       pulses, and per-tick jitter ourselves. The shape is derived from
       qualitative observation of Bengaluru rideshare pricing.

  2. Kaggle "nelgiriyewithana/indian-weather-repository-daily-snapshot"
       (loaded only when --with-weather is passed). Daily-updated
       weather snapshots for Indian cities, providing the four channels
       [temperature_c, precip_mm, wind_kph, visibility_km] the LSTM
       consumes alongside the price signal. We auto-download via
       `kagglehub`; if it's unavailable (no install / no Kaggle creds),
       we fall back to a synthetic-but-India-tuned weather generator
       (Bengaluru climate priors).

Datasets considered and rejected:
  - Any global weather dataset (e.g. NOAA GHCN) — wrong climate priors,
    monsoon signal washed out by global averaging.
  - shubhambathwal/ola-bike-ride-request-forecast — demand only, not weather.

Model contract (must match SurgePredictorImpl.kt on the Android side):

    Input  shape: (1, WINDOW, FEATURES)  dtype: float32
        WINDOW   = 24  # last 2 hours in 5-minute steps
        FEATURES = 3 (default) or 7 (--with-weather)
            base channels: [price_normalised, hour_sin, hour_cos]
            weather   add: [temp_c, precip_mm, wind_kmh, visibility_km] (z-score
                          normalised against per-channel mean/std written to meta)

    Output shape: (1, HORIZON)           dtype: float32
        HORIZON  = 6   # next 30 minutes in 5-minute steps, normalised price

Both sides of the price normalisation use the same constants (PRICE_MEAN /
PRICE_STD), written to `surge_forecast_meta.json`. When --with-weather is
passed, the meta also contains a `weather_norm` block so the Android side
can z-score the live OpenWeatherMap snapshot consistently with training.

Run from this folder:

    python -m venv .venv
    # Windows:
    .venv\\Scripts\\activate
    # Unix:
    source .venv/bin/activate
    pip install -r requirements.txt
    python train_surge_lstm.py

Artefacts land in `../app/src/main/assets/`:
    surge_forecast.tflite   — the compiled model
    surge_forecast_meta.json — normalisation + shape metadata
"""
from __future__ import annotations

import argparse
import json
import math
import os
import pathlib
import random
from typing import Dict, List, Optional, Tuple

import numpy as np
import pandas as pd
import tensorflow as tf
from tensorflow.keras import layers, models

from _provenance import (
    RANDOM_SEED,
    assert_random_init,
    print_no_pretrain_banner,
    print_surge_architecture,
    provenance_meta,
)

# Weather channel order — must line up with SurgePredictorImpl.buildWeatherRow
# on the Android side. The trainer writes both this order and per-channel
# (mean, std) to the meta JSON so on-device normalisation matches training.
WEATHER_CHANNELS = ("temp_c", "precip_mm", "wind_kmh", "visibility_km")

# Real Indian Weather Repository on Kaggle. Loaded only when --with-weather
# is passed. The CSV ships several hundred Indian-city snapshots — we treat
# them as a pool to sample the LSTM's per-day weather anchor from.
INDIA_WEATHER_DATASET = "nelgiriyewithana/indian-weather-repository-daily-snapshot"

# Mapping: canonical channel name → likely source-CSV column names. Defensive
# against the dataset being re-versioned with renamed columns.
INDIA_WEATHER_COLUMN_MAP = {
    "temp_c":        ["temperature_celsius", "temp_c", "temperature"],
    "precip_mm":     ["precip_mm", "precipitation_mm", "precip"],
    "wind_kmh":      ["wind_kph", "wind_kmh", "wind_speed"],
    "visibility_km": ["visibility_km", "visibility"],
}

# -----------------------------------------------------------------------------
# Hyperparameters — keep in sync with SurgePredictorImpl.kt.
# -----------------------------------------------------------------------------
WINDOW = 24       # 24 × 5 min = 2 hours of history
HORIZON = 6       # 6 × 5 min  = 30 minutes ahead
FEATURES = 3      # [price, sin(hour), cos(hour)]
STEP_MINUTES = 5
SAMPLES_PER_TIER = 4000
EPOCHS = 12
BATCH_SIZE = 64
HIDDEN = 32

ASSET_DIR = (
    pathlib.Path(__file__).resolve().parent.parent
    / "app" / "src" / "main" / "assets"
)
ASSET_DIR.mkdir(parents=True, exist_ok=True)
MODEL_PATH = ASSET_DIR / "surge_forecast.tflite"
META_PATH = ASSET_DIR / "surge_forecast_meta.json"

random.seed(RANDOM_SEED)
np.random.seed(RANDOM_SEED)
tf.random.set_seed(RANDOM_SEED)

# -----------------------------------------------------------------------------
# Synthetic data generator
#
# Reproduces the qualitative shape of Bengaluru surge observed by real riders:
#   • bi-modal commute rush (8–10.30 AM, 5.30–8.30 PM)
#   • light nightlife bump (22–23.45)
#   • late-night driver-scarcity tail (00–05)
#   • hot-zone pulses (random "event" spikes lasting 30–90 min)
#   • random tick jitter
#
# A realistic-looking training set is what lets the TFLite model produce
# advice that feels right during a demo in Koramangala or Whitefield.
# -----------------------------------------------------------------------------


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


def simulate_day(base_price: float, weather: Dict[str, float] | None = None) -> np.ndarray:
    """
    Return one full day of prices (24h / STEP_MINUTES samples).

    When [weather] is supplied, rain in particular pushes surge up for the
    rest of the simulated day — the LSTM learns to react to the weather
    channel because of this training signal, not just to historical price.
    """
    n = int(24 * 60 / STEP_MINUTES)
    prices = np.zeros(n, dtype=np.float32)

    # Randomly seed 0–3 "events" per day that push surge up for 30–90 minutes.
    event_mask = np.zeros(n, dtype=np.float32)
    for _ in range(random.randint(0, 3)):
        start = random.randint(0, n - 1)
        duration = random.randint(6, 18)  # 30–90 min
        end = min(n, start + duration)
        magnitude = random.uniform(0.3, 0.9)
        event_mask[start:end] += magnitude

    # Rainfall multiplier: each mm/h adds ~6% surge. Real Bengaluru data
    # would be sharper at the upper tail; this is a decent linear proxy.
    rain_boost = 0.0
    if weather is not None:
        rain_boost = min(0.6, weather["precip_mm"] * 0.06)

    for i in range(n):
        hour = (i * STEP_MINUTES) / 60.0
        surge = 1.0 + time_of_day_surge(hour) + event_mask[i] + rain_boost
        jitter = np.random.normal(0.0, 0.06)
        prices[i] = max(base_price * (surge + jitter), base_price * 0.9)
    return prices


def synth_day_weather() -> Dict[str, float]:
    """
    Sample one day's worth of weather. Distributions chosen to roughly match
    Bengaluru year-round: mild temps, frequent monsoon rain, occasional fog.
    Constants here only need to be representative — the model normalises
    against the empirical mean/std computed from the full training set.

    Used when the real Indian Weather Repository CSV isn't available
    (kagglehub missing or no Kaggle credentials). The Bengaluru-tuned
    priors keep the climate signal India-correct in that case.
    """
    return {
        "temp_c": float(np.clip(np.random.normal(24.0, 4.5), 14.0, 36.0)),
        # Beta(0.5, 4) is sharply zero-inflated with a long right tail —
        # most days dry, occasional heavy monsoon downpours.
        "precip_mm": float(np.clip(np.random.beta(0.5, 4.0) * 30.0, 0.0, 30.0)),
        "wind_kmh": float(np.clip(np.random.normal(9.0, 4.0), 0.0, 35.0)),
        # Visibility falls with rain; cheap proxy.
        "visibility_km": float(np.clip(10.0 - np.random.exponential(1.5), 0.5, 10.0)),
    }


def _resolve_india_weather_columns(df: pd.DataFrame) -> Optional[Dict[str, str]]:
    resolved = {}
    for canonical, candidates in INDIA_WEATHER_COLUMN_MAP.items():
        match = next((c for c in candidates if c in df.columns), None)
        if match is None:
            print(f"  [WARN] Indian weather CSV missing a column for '{canonical}' "
                  f"(tried {candidates}; have {list(df.columns)[:8]}...)")
            return None
        resolved[canonical] = match
    return resolved


def load_india_weather_pool() -> Optional[List[Dict[str, float]]]:
    """Auto-download and load the Indian Weather Repository as a sampleable
    pool of per-day weather snapshots. Returns None on any failure so the
    caller falls back to [synth_day_weather].
    """
    try:
        import kagglehub  # type: ignore
    except ImportError:
        print("  [INFO] kagglehub not installed — using synthetic Bengaluru "
              "weather priors (India-tuned, but not real per-city data).")
        print("         pip install kagglehub  to enable real weather sampling.")
        return None

    try:
        print(f"  Downloading Kaggle dataset: {INDIA_WEATHER_DATASET}")
        local = pathlib.Path(kagglehub.dataset_download(INDIA_WEATHER_DATASET))
    except Exception as e:  # noqa: BLE001
        print(f"  [WARN] kagglehub download failed ({e!r}) — using synthetic priors.")
        return None

    # Look for a tabular file under the unpacked dataset. Kaggle ships
    # weather data variously as .csv, gzipped CSV, or parquet — accept
    # any of those.
    candidates: List[pathlib.Path] = []
    for ext in ("*.csv", "*.csv.gz", "*.parquet"):
        candidates.extend(local.rglob(ext))
    if not candidates:
        print(f"  [WARN] No tabular file under {local}; using synthetic priors.")
        # Diagnostic: dump the top-level directory listing so the user
        # can see what *was* unpacked (helps spot a Kaggle dataset that
        # was restructured or replaced with stub metadata).
        try:
            files_present = [str(p.relative_to(local)) for p in local.rglob("*")
                             if p.is_file()][:20]
            print(f"         Files present (up to 20): {files_present}")
        except Exception:
            pass
        return None
    csv_path = max(candidates, key=lambda p: p.stat().st_size)
    print(f"  Using {csv_path.name} ({csv_path.stat().st_size / 1e6:.1f} MB)")

    def _read_table(path: pathlib.Path, **kw) -> pd.DataFrame:
        # Format dispatch — keeps the rest of the loader format-agnostic.
        if path.suffix == ".parquet":
            df_full = pd.read_parquet(path)
            return df_full[kw["usecols"]] if "usecols" in kw else df_full
        # pandas autoinfers .gz from the extension.
        return pd.read_csv(path, low_memory=False, **kw)

    head = _read_table(csv_path, nrows=1) if csv_path.suffix != ".parquet" else \
        pd.read_parquet(csv_path).head(1)
    col_map = _resolve_india_weather_columns(head)
    if col_map is None:
        print("  [WARN] Falling back to synthetic priors due to column mismatch.")
        return None

    src_cols = list(col_map.values())
    df = _read_table(csv_path, usecols=src_cols).dropna()
    if df.empty:
        print("  [WARN] Indian weather CSV had zero usable rows.")
        return None

    pool: List[Dict[str, float]] = []
    for _, row in df.iterrows():
        pool.append({
            "temp_c":       float(row[col_map["temp_c"]]),
            "precip_mm":    float(row[col_map["precip_mm"]]),
            "wind_kmh":     float(row[col_map["wind_kmh"]]),
            "visibility_km": float(row[col_map["visibility_km"]]),
        })
    print(f"  Loaded {len(pool):,} real Indian weather snapshots into pool.")
    return pool


def sample_day_weather(pool: Optional[List[Dict[str, float]]]) -> Dict[str, float]:
    """Pull one weather snapshot. Real pool when available, else synthetic."""
    if pool is None or len(pool) == 0:
        return synth_day_weather()
    return random.choice(pool)


def build_dataset(with_weather: bool):
    """
    Build the training tensors.

    Returns (X, y_norm, price_mean, price_std, weather_norm, source_label).
    `weather_norm` is None when --with-weather wasn't passed, otherwise
    a {channel: {"mean": float, "std": float}} dict serialised into meta.
    `source_label` describes which weather pool drove training so it's
    auditable from the meta JSON (real Kaggle vs synthetic priors).
    """
    weather_pool = load_india_weather_pool() if with_weather else None
    weather_source = (
        f"kaggle:{INDIA_WEATHER_DATASET}"
        if weather_pool is not None
        else ("synthetic:bengaluru-priors" if with_weather else "none")
    )

    X: List[np.ndarray] = []
    y: List[np.ndarray] = []
    weather_features: List[np.ndarray] = []  # only populated when with_weather

    base_prices = [150.0, 220.0, 320.0]
    samples_per_base = SAMPLES_PER_TIER
    for base in base_prices:
        for _ in range(samples_per_base // len(base_prices) + 1):
            day_weather = sample_day_weather(weather_pool) if with_weather else None
            day = simulate_day(base, day_weather)
            start = random.randint(0, len(day) - WINDOW - HORIZON - 1)
            window = day[start:start + WINDOW]
            future = day[start + WINDOW:start + WINDOW + HORIZON]

            hours = np.array(
                [(start + k) * STEP_MINUTES / 60.0 for k in range(WINDOW)],
                dtype=np.float32,
            )
            sin_h = np.sin(2 * math.pi * hours / 24.0).astype(np.float32)
            cos_h = np.cos(2 * math.pi * hours / 24.0).astype(np.float32)

            channels = [window, sin_h, cos_h]
            if with_weather:
                # Broadcast the day's single weather snapshot across every
                # timestep — matches the on-device runtime, where we feed the
                # current weather snapshot identically for every history tick.
                w_row = np.array(
                    [day_weather[c] for c in WEATHER_CHANNELS], dtype=np.float32
                )
                for value in w_row:
                    channels.append(np.full(WINDOW, value, dtype=np.float32))
                weather_features.append(w_row)

            feats = np.stack(channels, axis=-1)
            X.append(feats)
            y.append(future.astype(np.float32))

    X_arr = np.stack(X)
    y_arr = np.stack(y)

    price_mean = float(X_arr[..., 0].mean())
    price_std = float(X_arr[..., 0].std() + 1e-6)
    X_arr[..., 0] = (X_arr[..., 0] - price_mean) / price_std
    y_norm = (y_arr - price_mean) / price_std

    weather_norm = None
    if with_weather:
        w_arr = np.stack(weather_features)  # (samples, len(WEATHER_CHANNELS))
        weather_norm = {}
        for i, name in enumerate(WEATHER_CHANNELS):
            mu = float(w_arr[:, i].mean())
            sd = float(w_arr[:, i].std() + 1e-6)
            # In-place z-score on the trailing channels of X.
            X_arr[..., 3 + i] = (X_arr[..., 3 + i] - mu) / sd
            weather_norm[name] = {"mean": mu, "std": sd}

    return X_arr, y_norm, price_mean, price_std, weather_norm, weather_source


# -----------------------------------------------------------------------------
# Model
# -----------------------------------------------------------------------------


def build_model(features_per_step: int) -> tf.keras.Model:
    inputs = layers.Input(shape=(WINDOW, features_per_step), name="history")
    x = layers.LSTM(HIDDEN, name="lstm")(inputs)
    x = layers.Dense(HIDDEN, activation="relu", name="dense_hidden")(x)
    outputs = layers.Dense(HORIZON, name="forecast")(x)
    model = models.Model(inputs=inputs, outputs=outputs, name="surge_forecast")
    model.compile(optimizer=tf.keras.optimizers.Adam(1e-3), loss="mse", metrics=["mae"])
    return model


# -----------------------------------------------------------------------------
# TFLite conversion
# -----------------------------------------------------------------------------


def to_tflite(
    model: tf.keras.Model,
    features_per_step: int,
    path: pathlib.Path,
) -> int:
    """Export the surge LSTM to TFLite.

    Note on the LSTM-specific path: the standard Keras LSTM uses
    TensorListReserve for its internal scratchpad. TFLite can only lower
    that op when the tensor's element_shape is fully static. Going through
    `from_keras_model(model)` leaves the batch dim as `None` in newer TF
    versions (Colab's TF 2.18+), which makes the converter fail with:

        'tf.TensorListReserve' op requires element_shape to be static
        during TF Lite transformation pass

    We sidestep it by wrapping the model in a `@tf.function` with an
    explicit batch=1 input signature, then converting from the concrete
    function. Training itself stays dynamic-batch; on-device the
    interpreter only ever feeds batch=1 anyway, which is what the meta
    JSON's `input_shape` already documents.
    """
    @tf.function(input_signature=[
        tf.TensorSpec(
            shape=[1, WINDOW, features_per_step],
            dtype=tf.float32,
            name="history",
        )
    ])
    def serving_fn(history):
        return model(history)

    concrete = serving_fn.get_concrete_function()
    converter = tf.lite.TFLiteConverter.from_concrete_functions([concrete], model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_bytes = converter.convert()
    path.write_bytes(tflite_bytes)
    return len(tflite_bytes)


# -----------------------------------------------------------------------------
# Entry point
# -----------------------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--with-weather",
        action="store_true",
        help="Train a 7-feature model that consumes [temp_c, precip_mm, "
             "wind_kmh, visibility_km] alongside the price/time channels. "
             "Requires the on-device WeatherRepository to be wired up.",
    )
    args = parser.parse_args()
    features_per_step = 3 + (len(WEATHER_CHANNELS) if args.with_weather else 0)

    print(
        f"Generating synthetic Bengaluru surge data "
        f"({'with weather' if args.with_weather else 'no weather'})..."
    )
    X, y, price_mean, price_std, weather_norm, weather_source = build_dataset(args.with_weather)
    print(f"  X shape: {X.shape}  y shape: {y.shape}")
    print(f"  price_mean={price_mean:.2f}  price_std={price_std:.2f}")
    if weather_norm is not None:
        print(f"  weather source: {weather_source}")
        for name, stats in weather_norm.items():
            print(f"  {name}: mean={stats['mean']:.2f}  std={stats['std']:.2f}")

    split = int(len(X) * 0.9)
    X_train, X_val = X[:split], X[split:]
    y_train, y_val = y[:split], y[split:]

    print("Training LSTM...")
    print_surge_architecture(features_per_step)
    print_no_pretrain_banner()
    model = build_model(features_per_step)
    model.summary()

    # Verify weights aren't loaded from any external checkpoint before
    # training begins. Fails loud if biases aren't zero or kernels
    # collapsed below Glorot scale.
    assert_random_init(model)

    model.fit(
        X_train, y_train,
        validation_data=(X_val, y_val),
        epochs=EPOCHS,
        batch_size=BATCH_SIZE,
        verbose=2,
    )

    print(f"Converting to TFLite -> {MODEL_PATH}")
    size_bytes = to_tflite(model, features_per_step, MODEL_PATH)
    print(f"  wrote {size_bytes / 1024:.1f} KiB")

    feature_order = ["price_normalised", "hour_sin", "hour_cos"]
    if args.with_weather:
        feature_order += list(WEATHER_CHANNELS)

    meta = {
        "input_shape": [1, WINDOW, features_per_step],
        "output_shape": [1, HORIZON],
        "window": WINDOW,
        "horizon": HORIZON,
        "features": features_per_step,
        "step_minutes": STEP_MINUTES,
        "price_mean": price_mean,
        "price_std": price_std,
        "feature_order": feature_order,
        "weather_source": weather_source,
        # Provenance — explicit so anyone reading the meta JSON can see
        # the artefact was trained from scratch with no external weights.
        "provenance": provenance_meta(),
    }
    if weather_norm is not None:
        meta["weather_norm"] = weather_norm
    META_PATH.write_text(json.dumps(meta, indent=2))
    print(f"  wrote metadata -> {META_PATH}")


if __name__ == "__main__":
    main()
