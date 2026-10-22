"""
Trains the fare-estimator MLP on INDIA-ONLY data and exports it as TensorFlow Lite.

Data sources (all India-based — no foreign currency conversion is ever applied):

  1. Kaggle "yashdevladdha/uber-ride-analytics-dashboard"
       Real Uber India bookings with native INR fares. We auto-download via
       `kagglehub` at training time and keep up to ~30k completed-ride rows.
       Provides UBER × {BIKE, AUTO, ECONOMY, PREMIUM} via vehicle_type.

  2. Synthetic CATALOG augment (Ola + Rapido)
       Neither Ola nor Rapido publishes per-trip pricing data, so all OLA
       and RAPIDO tiers are generated from the Bengaluru CATALOG below —
       the same rate card the on-device SmartRideDataRepository uses.

  3. Fallback: 100% CATALOG-synthetic
       If kagglehub isn't installed or Kaggle credentials aren't configured,
       we degrade to the CATALOG generator for every slot (still India-tuned
       fares, just without the real Uber distribution). The script prints a
       loud warning so it's obvious what happened.

Datasets considered and rejected:
  - brllrb/uber-and-lyft-dataset-boston-ma   — US pricing, dropped.
  - yasserh/uber-fares-dataset (NYC)         — US pricing, dropped.
  - shubhambathwal/ola-bike-ride-request-forecast — demand only, no fare.
  - anupammajhi/uber-request-data            — Bengaluru airport demand,
                                               no fare column.

Model contract (must match `FareEstimatorImpl.kt`):

    Input  shape: (1, FEATURES)  dtype: float32
        FEATURES = 11
        order    = [
            distance_norm,                  # km / 20
            duration_norm,                  # minutes / 60
            hour_sin, hour_cos,             # time-of-day
            provider_uber, provider_ola, provider_rapido,   # one-hot
            tier_bike, tier_auto, tier_economy, tier_premium # one-hot
        ]

    Output shape: (1, OUTPUTS)     dtype: float32
        OUTPUTS = 1                — single fare regression (default, MSE)
        OUTPUTS = 3                — quantile heads (p10, p50, p90), pinball
                                     loss; pass --quantile.

The Android side reads `fare_estimator_meta.json::output_shape` and switches
behaviour automatically:
    output_shape [1, 1]: synthesises a fixed ±15% band around the point.
    output_shape [1, 3]: renders the calibrated p10–p90 band directly.

Run from this folder:

    python -m venv .venv
    .venv\\Scripts\\activate   # Windows
    source .venv/bin/activate  # Unix

    pip install -r requirements.txt
    python train_fare_estimator.py            # MSE single head
    python train_fare_estimator.py --quantile # 3-head quantile regression

Artefacts land in `../app/src/main/assets/`:
    fare_estimator.tflite        — the compiled regression model
    fare_estimator_meta.json     — feature layout + normalisation constants
"""
from __future__ import annotations

import argparse
import json
import math
import os
import pathlib
import random
from typing import List, Optional, Tuple

import numpy as np
import pandas as pd
import tensorflow as tf
from tensorflow.keras import layers, models

from _provenance import (
    RANDOM_SEED,
    assert_random_init,
    print_fare_architecture,
    print_no_pretrain_banner,
    provenance_meta,
)

# Quantile heads emitted in this order — must match FareEstimatorImpl meta read.
QUANTILES = (0.1, 0.5, 0.9)

# -----------------------------------------------------------------------------
# Hyperparameters
# -----------------------------------------------------------------------------
FEATURES = 11           # see file header for order
SAMPLES = 30_000        # synthetic-only fallback target
EPOCHS = 20
BATCH_SIZE = 128
HIDDEN_1 = 64
HIDDEN_2 = 32

DIST_NORM = 20.0        # divide distance_km by this before feeding the model
DUR_NORM = 60.0         # divide duration_min by this

# Bengaluru urban average — used to back-fill duration since the Uber India
# CSV reports distance but not duration.
CITY_AVG_KMPH = 22.0

# Real-data targets.
UBER_INDIA_DATASET = "yashdevladdha/uber-ride-analytics-dashboard"
UBER_INDIA_SAMPLE_LIMIT = 30_000
SYNTH_PER_MISSING_SLOT = 2_000  # rows per (provider, tier) the real dataset can't cover

ASSET_DIR = (
    pathlib.Path(__file__).resolve().parent.parent
    / "app" / "src" / "main" / "assets"
)
ASSET_DIR.mkdir(parents=True, exist_ok=True)
MODEL_PATH = ASSET_DIR / "fare_estimator.tflite"
META_PATH = ASSET_DIR / "fare_estimator_meta.json"

random.seed(RANDOM_SEED)
np.random.seed(RANDOM_SEED)
tf.random.set_seed(RANDOM_SEED)

# -----------------------------------------------------------------------------
# Tier catalog — mirrors SmartRideDataRepository.CATALOG in the Android app.
# -----------------------------------------------------------------------------
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

# All Ola + all Rapido slots are synthesised because no public Indian dataset
# publishes per-trip pricing for those providers. Uber × {bike/auto/economy/
# premium} comes from the real Uber India dataset when available.
SYNTH_SLOTS = {
    (1, 1), (1, 2), (1, 3),       # Ola Auto / Mini / Prime
    (2, 0), (2, 1), (2, 2),       # Rapido Bike / Auto / Cab Lite
}

# Uber India vehicle_type → tier class. Lower-cased + stripped before lookup.
UBER_INDIA_TIER_BY_TYPE = {
    "bike": 0, "ebike": 0, "uber moto": 0,
    "auto": 1, "uber auto": 1,
    "go mini": 2, "go sedan": 2, "uberx": 2, "ubergo": 2, "uber go": 2,
    "premier sedan": 3, "uber xl": 3, "uberxl": 3, "black": 3,
}

# The Uber India CSV's column names drift across snapshots — try several.
UBER_INDIA_COLUMN_MAP = {
    "booking_value": ["Booking Value", "booking_value", "Booking_Value", "fare"],
    "ride_distance": ["Ride Distance", "ride_distance", "Ride_Distance",
                      "distance_km", "distance"],
    "vehicle_type":  ["Vehicle Type", "vehicle_type", "Vehicle_Type"],
    "booking_status": ["Booking Status", "booking_status", "Booking_Status", "status"],
    "date":          ["Date", "date", "booking_date", "Booking Date"],
    "time":          ["Time", "time", "booking_time", "Booking Time"],
}


# -----------------------------------------------------------------------------
# India-only data loading (real Uber India bookings via kagglehub)
# -----------------------------------------------------------------------------


def time_of_day_surge(hour: float) -> float:
    """Bi-modal Bengaluru commute curve — matches Android side."""
    if 8.0 <= hour <= 10.5:
        return 0.55
    if 17.5 <= hour <= 20.5:
        return 0.75
    if 22.0 <= hour <= 23.75:
        return 0.25
    if 0.0 <= hour <= 5.0:
        return 0.15
    return 0.0


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


def _resolve_uber_india_columns(df: pd.DataFrame) -> dict:
    resolved = {}
    for canonical, candidates in UBER_INDIA_COLUMN_MAP.items():
        match = next((c for c in candidates if c in df.columns), None)
        if match is None:
            if canonical == "time":
                resolved[canonical] = None
                continue
            raise ValueError(
                f"Uber India CSV missing a column for '{canonical}' "
                f"(tried {candidates}; have {list(df.columns)[:10]}...)"
            )
        resolved[canonical] = match
    return resolved


def _find_uber_india_csv(root: pathlib.Path) -> Optional[pathlib.Path]:
    """Pick the most likely rides CSV from the unpacked Kaggle dataset.

    The dataset ships several small CSVs (vehicle types, hours, etc.) plus
    one big bookings file. We pick the largest CSV under the root since the
    bookings table is by far the heaviest file in the bundle.
    """
    csvs = list(root.rglob("*.csv"))
    if not csvs:
        return None
    return max(csvs, key=lambda p: p.stat().st_size)


def download_uber_india_csv() -> Optional[pathlib.Path]:
    """Download `yashdevladdha/uber-ride-analytics-dashboard` via kagglehub.

    Returns the path to the bookings CSV, or None when:
      - kagglehub isn't installed,
      - no Kaggle credentials are configured (`~/.kaggle/kaggle.json`),
      - the network call fails.

    Each failure mode is logged and falls through to the synthetic generator
    so the trainer is always runnable.
    """
    try:
        import kagglehub  # type: ignore
    except ImportError:
        print("  [INFO] kagglehub not installed — skipping real Uber India dataset.")
        print("         pip install kagglehub  to enable real-data training.")
        return None

    try:
        print(f"  Downloading Kaggle dataset: {UBER_INDIA_DATASET}")
        local = pathlib.Path(kagglehub.dataset_download(UBER_INDIA_DATASET))
    except Exception as e:  # noqa: BLE001 — kagglehub raises plain Exception
        print(f"  [WARN] kagglehub download failed ({e!r}) — using synthetic only.")
        return None

    csv = _find_uber_india_csv(local)
    if csv is None:
        print(f"  [WARN] No CSV found under {local}; using synthetic only.")
        return None
    print(f"  Using {csv.name} ({csv.stat().st_size / 1e6:.1f} MB)")
    return csv


def load_uber_india_rides(csv_path: pathlib.Path) -> pd.DataFrame:
    """Read the Uber India CSV and return a cleaned ride DataFrame.

    Filters: only `Completed` bookings, distance 0.3–40 km, fare Rs30–Rs10,000,
    and vehicle types we have a tier mapping for. Adds an `hour` column for
    the time-of-day feature.
    """
    df_head = pd.read_csv(csv_path, nrows=1, low_memory=False)
    col_map = _resolve_uber_india_columns(df_head)
    src_cols = [c for c in col_map.values() if c is not None]
    df = pd.read_csv(csv_path, usecols=src_cols, low_memory=False)

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

    if "ride_time" in df.columns:
        hour = pd.to_datetime(
            df["ride_time"].astype(str), errors="coerce", format="mixed",
        ).dt.hour
    else:
        hour = pd.to_datetime(df["ride_date"], errors="coerce").dt.hour
    df = df.assign(hour=hour).dropna(subset=["hour"])
    df["hour"] = df["hour"].astype(int)

    return df.reset_index(drop=True)


def uber_india_rows_to_features(df: pd.DataFrame) -> Tuple[np.ndarray, np.ndarray]:
    """Turn cleaned Uber India rows into (features, fare_inr) arrays."""
    if df.empty:
        return (
            np.empty((0, FEATURES), dtype=np.float32),
            np.empty((0,), dtype=np.float32),
        )
    if len(df) > UBER_INDIA_SAMPLE_LIMIT:
        df = df.sample(UBER_INDIA_SAMPLE_LIMIT, random_state=RANDOM_SEED).reset_index(drop=True)

    n = len(df)
    X = np.zeros((n, FEATURES), dtype=np.float32)
    y = np.zeros((n,), dtype=np.float32)

    dist_km = df["ride_distance_km"].to_numpy(dtype=float)
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


# -----------------------------------------------------------------------------
# Synthetic generators (CATALOG-driven) — used for Ola/Rapido slots and as
# the fully-synthetic fallback when kagglehub is unavailable.
# -----------------------------------------------------------------------------


def synth_rows_for_slot(
    provider_idx: int, tier_cls: int, n: int,
) -> Tuple[np.ndarray, np.ndarray]:
    entry = next((e for e in CATALOG if e[0] == provider_idx and e[1] == tier_cls), None)
    if entry is None:
        raise ValueError(f"No CATALOG entry for slot ({provider_idx}, {tier_cls})")
    _, _, flagfall, per_km, per_min, sens = entry

    X = np.zeros((n, FEATURES), dtype=np.float32)
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


def synth_full_catalog_dataset() -> Tuple[np.ndarray, np.ndarray]:
    """Synthetic dataset covering every CATALOG slot — kagglehub fallback."""
    print("  [FALLBACK] Generating 100% synthetic CATALOG dataset (India-tuned).")
    per_slot = SAMPLES // len(CATALOG) + 1
    Xs: List[np.ndarray] = []
    ys: List[np.ndarray] = []
    for entry in CATALOG:
        provider_idx, tier_cls = entry[0], entry[1]
        Xi, yi = synth_rows_for_slot(provider_idx, tier_cls, per_slot)
        Xs.append(Xi)
        ys.append(yi)
    X = np.concatenate(Xs, axis=0)[:SAMPLES]
    y = np.concatenate(ys, axis=0)[:SAMPLES]
    return X, y


# -----------------------------------------------------------------------------
# Top-level dataset builder
# -----------------------------------------------------------------------------


def build_dataset() -> Tuple[np.ndarray, np.ndarray, float, float, dict]:
    """Assemble the training tensors using India-only sources.

    Returns (X, y_norm, fare_mean, fare_std, dataset_stats). The stats dict
    is written into the meta JSON so it's easy to audit which rows were
    real vs synthetic after the fact.
    """
    csv = download_uber_india_csv()
    if csv is not None:
        df = load_uber_india_rides(csv)
        print(f"  Uber India rows after cleaning: {len(df):,}")
        if df.empty:
            print("  [WARN] No usable Uber India rows after cleaning — going synthetic.")
            X, y = synth_full_catalog_dataset()
            stats = {
                "uber_india_rows": 0,
                "synth_rows": int(len(X)),
                "total_rows": int(len(X)),
                "datasets": ["synthetic:CATALOG (India-tuned)"],
            }
        else:
            X_ubr, y_ubr = uber_india_rows_to_features(df)
            print(f"  Uber India rows used: {len(X_ubr):,}  mean fare Rs{y_ubr.mean():.0f}")

            # Synthesise the slots no public Indian dataset covers.
            Xs_list: List[np.ndarray] = []
            ys_list: List[np.ndarray] = []
            slot_counts = {}
            for (p_idx, t_cls) in sorted(SYNTH_SLOTS):
                Xi, yi = synth_rows_for_slot(p_idx, t_cls, SYNTH_PER_MISSING_SLOT)
                Xs_list.append(Xi)
                ys_list.append(yi)
                slot_counts[f"provider={p_idx},tier={t_cls}"] = len(Xi)
            X_synth = np.concatenate(Xs_list, axis=0)
            y_synth = np.concatenate(ys_list, axis=0)
            print(f"  Synth Ola/Rapido rows: {len(X_synth):,}  (slots = {slot_counts})")

            X = np.concatenate([X_ubr, X_synth], axis=0)
            y = np.concatenate([y_ubr, y_synth], axis=0)
            stats = {
                "uber_india_rows": int(len(X_ubr)),
                "synth_rows": int(len(X_synth)),
                "total_rows": int(len(X)),
                "uber_india_fare_mean_inr": float(y_ubr.mean()),
                "synth_slot_counts": slot_counts,
                "datasets": [
                    f"kaggle:{UBER_INDIA_DATASET}",
                    "synthetic:CATALOG (Ola + Rapido tiers)",
                ],
            }
    else:
        X, y = synth_full_catalog_dataset()
        stats = {
            "uber_india_rows": 0,
            "synth_rows": int(len(X)),
            "total_rows": int(len(X)),
            "datasets": ["synthetic:CATALOG (India-tuned)"],
        }

    # Shuffle so the train/val split isn't biased toward the synthetic tail.
    perm = np.random.permutation(len(X))
    X = X[perm].astype(np.float32)
    y = y[perm].astype(np.float32)

    fare_mean = float(y.mean())
    fare_std = float(y.std() + 1e-6)
    y_norm = (y - fare_mean) / fare_std
    return X, y_norm, fare_mean, fare_std, stats


# -----------------------------------------------------------------------------
# Model
# -----------------------------------------------------------------------------


def build_model(quantile: bool) -> tf.keras.Model:
    """
    Build the MLP. With `quantile=False` the head is a single scalar trained
    with MSE — the original contract. With `quantile=True` the head emits
    three scalars (p10, p50, p90) trained with pinball loss so the band the
    Android UI renders is calibrated to the training distribution.
    """
    inputs = layers.Input(shape=(FEATURES,), name="trip_features")
    x = layers.Dense(HIDDEN_1, activation="relu")(inputs)
    x = layers.Dense(HIDDEN_2, activation="relu")(x)
    if quantile:
        outputs = layers.Dense(len(QUANTILES), name="fare_quantiles")(x)
        loss = pinball_loss(QUANTILES)
        metrics: list = []
    else:
        outputs = layers.Dense(1, name="fare_norm")(x)
        loss = "mse"
        metrics = ["mae"]
    model = models.Model(inputs=inputs, outputs=outputs, name="fare_estimator")
    model.compile(optimizer=tf.keras.optimizers.Adam(1e-3), loss=loss, metrics=metrics)
    return model


def pinball_loss(quantiles: Tuple[float, ...]):
    """Multi-output pinball (a.k.a. quantile) loss — see docstring above."""
    qs = tf.constant(quantiles, dtype=tf.float32)

    def loss_fn(y_true, y_pred):
        y_true_b = tf.reshape(y_true, (-1, 1))
        err = y_true_b - y_pred
        per_head = tf.maximum(qs * err, (qs - 1.0) * err)
        return tf.reduce_mean(tf.reduce_sum(per_head, axis=-1))

    return loss_fn


# -----------------------------------------------------------------------------
# TFLite conversion
# -----------------------------------------------------------------------------


def to_tflite(model: tf.keras.Model, path: pathlib.Path) -> int:
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
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
        "--quantile",
        action="store_true",
        help="Train a 3-head quantile-regression model (p10/p50/p90, pinball loss) "
             "instead of the default single-output MSE regression.",
    )
    args = parser.parse_args()

    print("Building India-only fare training dataset...")
    X, y, fare_mean, fare_std, dataset_stats = build_dataset()
    print(f"  X shape: {X.shape}  y shape: {y.shape}")
    print(f"  fare_mean=Rs{fare_mean:.2f}  fare_std=Rs{fare_std:.2f}")

    split = int(len(X) * 0.9)
    X_train, X_val = X[:split], X[split:]
    y_train, y_val = y[:split], y[split:]

    print(
        f"Training fare MLP "
        f"({'quantile heads p10/p50/p90' if args.quantile else 'single regression head'})..."
    )
    print_fare_architecture(quantile=args.quantile)
    print_no_pretrain_banner()
    model = build_model(quantile=args.quantile)
    model.summary()

    # Verify nothing has been loaded from a checkpoint before .fit() runs.
    # Fails loud if biases aren't zero or kernels collapsed to ~0 — both
    # tell-tale signs of a loaded checkpoint.
    assert_random_init(model)

    model.fit(
        X_train, y_train,
        validation_data=(X_val, y_val),
        epochs=EPOCHS,
        batch_size=BATCH_SIZE,
        verbose=2,
    )

    # Eyeball-level sanity check.
    print("\nSample predictions (val set):")
    preds = model.predict(X_val[:5], verbose=0)
    for i in range(min(5, len(preds))):
        actual_inr = y_val[i] * fare_std + fare_mean
        if args.quantile:
            p10, p50, p90 = (preds[i][k] * fare_std + fare_mean for k in range(3))
            print(f"  p10 Rs{p10:.0f}  p50 Rs{p50:.0f}  p90 Rs{p90:.0f}  actual Rs{actual_inr:.0f}")
        else:
            predicted_inr = preds[i][0] * fare_std + fare_mean
            print(f"  predicted Rs{predicted_inr:.0f}  actual Rs{actual_inr:.0f}")

    print(f"\nConverting to TFLite -> {MODEL_PATH}")
    size_bytes = to_tflite(model, MODEL_PATH)
    print(f"  wrote {size_bytes / 1024:.1f} KiB")

    meta = {
        "input_shape": [1, FEATURES],
        "output_shape": [1, len(QUANTILES) if args.quantile else 1],
        "features": FEATURES,
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
        "distance_norm_divisor_km": DIST_NORM,
        "duration_norm_divisor_min": DUR_NORM,
        "fare_mean_inr": fare_mean,
        "fare_std_inr": fare_std,
        "training_data": dataset_stats,
        # Provenance — explicit so anyone reading the meta JSON can see
        # the artefact was trained from scratch with no external weights.
        "provenance": provenance_meta(),
    }
    if args.quantile:
        meta["quantiles"] = list(QUANTILES)
    META_PATH.write_text(json.dumps(meta, indent=2))
    print(f"  wrote metadata -> {META_PATH}")


if __name__ == "__main__":
    main()
