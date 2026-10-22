# ML Models

Two on-device TensorFlow Lite models power the predictive side of the app:

| Model | Script | Purpose |
| ----- | ------ | ------- |
| **Surge LSTM forecaster** | `train_surge_lstm.py` | Predicts the next 30 min of ride-hailing surge from the last 2 h of history. Feeds the "Wait 10 min, Rapido surge will drop" advice banner. |
| **Fare estimator MLP** | `train_fare_estimator.py` | Predicts the actual rupee fare for a `(distance, duration, provider, tier)` tuple under current traffic. Complements the forecaster — it answers *what will I pay now*, not *how will the price move*. |

Both scripts export a quantised `.tflite` plus a JSON metadata sidecar that
Android reads at runtime to un-normalise the model's output.

## Output artefacts

All four land in `../app/src/main/assets/` so Android loads them via the
`AssetManager`:

```
surge_forecast.tflite        # ~20–40 KiB  LSTM
surge_forecast_meta.json     # shape + price normalisation stats
fare_estimator.tflite        # ~15–25 KiB  MLP
fare_estimator_meta.json     # feature layout + fare normalisation stats
```

Re-ship the `.tflite` and its matching `_meta.json` **together** whenever you
retrain — the normalisation constants change every run.

## Running locally

```bash
cd ml_model
python -m venv .venv

# Windows
.venv\Scripts\activate
# Unix / macOS
source .venv/bin/activate

pip install -r requirements.txt

python train_surge_lstm.py --with-weather   # ~1–2 min on CPU
python train_fare_estimator.py --quantile   # ~1–2 min on CPU
```

Both local trainers now default to **India-only** data via `kagglehub`:

* `train_fare_estimator.py` auto-downloads `yashdevladdha/uber-ride-analytics-dashboard`
  (real Uber India bookings, native INR) and trains the Uber slots on it.
  All Ola + Rapido slots are filled from the synthetic CATALOG (no public
  Indian dataset publishes per-trip pricing for those providers). The
  meta JSON's `training_data` block records exactly how many rows came
  from each source.
* `train_surge_lstm.py --with-weather` auto-downloads
  `nelgiriyewithana/indian-weather-repository-daily-snapshot` and samples
  per-day weather snapshots from real Indian cities to drive the LSTM's
  weather channels. The pool's z-score stats land in `weather_norm` so
  the on-device weather provider normalises live readings the same way.

If `kagglehub` isn't installed or Kaggle credentials aren't configured
(`~/.kaggle/kaggle.json`), both scripts fall back to **India-tuned
synthetic data** (Bengaluru priors for fares + monsoon-aware weather
generator) and print a clear `[FALLBACK]` / `[INFO]` banner. The
artefacts are still India-shaped, just without per-row real signal.

## Running on Google Colab

Preferred when you don't want to deal with a local TensorFlow install. Pick
one of two notebooks:

### Option A — Synthetic data only (fastest, no Kaggle account needed)

1. Open `colab/EIS_Train_Models.ipynb` in [colab.research.google.com](https://colab.research.google.com) (File → Upload notebook).
2. Run the cells in order. The second cell prompts you to upload `colab/train_on_colab.py` from this repo.
3. The final cell triggers four browser downloads. Save each file into `app/src/main/assets/` in the Android project.
4. Rebuild the app. Done.

### Option B — Real Kaggle data for the fare model (India-only)

1. Open `colab/EIS_Train_Models_RealData.ipynb` in Colab.
2. Run cells in order. You'll authenticate with Kaggle (interactive prompt or by uploading `kaggle.json`), then the notebook downloads two small India-based datasets:
   * `yashdevladdha/uber-ride-analytics-dashboard` — real Uber India bookings (~50 MB, native INR).
   * `nelgiriyewithana/indian-weather-repository-daily-snapshot` — daily weather snapshots for Indian cities (~30 MB).
3. Upload `colab/train_on_real_data.py` when prompted.
4. The script trains the fare MLP on real Uber India fares (augmented with synthetic Ola/Rapido rows) and the surge LSTM on the synthetic Bengaluru curve driven by real Indian weather. Same four artefacts, same Android contract.

Both Colab scripts are self-contained — no other repo files need to be
uploaded, and no GPU is required.

## Training data sources

Both models are **trained from scratch** — Glorot uniform / orthogonal kernel
initialisation, fixed seed (42), no checkpoints or pre-trained weights are
loaded at any point.

### Datasets used (real-data notebook)

All training data is **India-based** so the learned pricing distribution
matches what the Android app sees in production — no foreign currency
conversion, no foreign rate-card leaking into the weights.

| # | Dataset | Rows used | Purpose | Maps to |
| - | ------- | --------- | ------- | ------- |
| 1 | Kaggle [`yashdevladdha/uber-ride-analytics-dashboard`](https://www.kaggle.com/datasets/yashdevladdha/uber-ride-analytics-dashboard) — Uber India bookings, native INR | up to 30k sampled | Real fares across **all 4 tier classes** — Bike/eBike → BIKE, Auto → AUTO, Go Mini/Go Sedan/UberX/Go → ECONOMY, Premier Sedan/Uber XL → PREMIUM | UBER × {BIKE, AUTO, ECONOMY, PREMIUM} |
| 2 | Kaggle [`nelgiriyewithana/indian-weather-repository-daily-snapshot`](https://www.kaggle.com/datasets/nelgiriyewithana/indian-weather-repository-daily-snapshot) — daily weather snapshots for Indian cities | pooled | Supplies the real weather pool the surge LSTM samples from: `temperature_celsius`, `precip_mm`, `wind_kph`, `visibility_km` | surge LSTM weather channels |
| 3 | Synthetic (CATALOG formula) | 2k per slot × 6 slots = 12k | Fills slots no public Indian dataset covers (Ola has no public pricing; Rapido is private) | Ola Auto/Mini/Prime + Rapido Bike/Auto/Cab-Lite |

Total fare-model dataset: **~42k rows** — real Uber India + synthetic
Ola/Rapido augment. No currency conversion applied; `fare_mean_inr` /
`fare_std_inr` are computed directly from INR booking values.

The synthetic-only notebook (`EIS_Train_Models.ipynb`) trains the same
architectures from 30k purely-synthetic CATALOG samples — useful when you
want to skip the Kaggle download.

### Why these specific Indian datasets

* **Uber Ride Analytics Dashboard** is the only public Indian rideshare
  dataset with native INR booking values, ride distance, and a
  vehicle-type column granular enough to separate all 4 tier classes. It
  also has a booking status column so we can cleanly filter to
  `Completed` rides (the cancelled / no-driver rows have zero fare).
* **Indian Weather Repository** is continuously updated and covers most
  Indian cities with the four features the surge LSTM expects
  (temperature / precipitation / wind / visibility). We z-score the pool
  and store the means + stds in `surge_forecast_meta.json` so the Android
  side can normalise live readings the same way.

### Why no Ola / Rapido real data

Neither Ola nor Rapido publishes per-trip pricing data. All OLA tiers and
all RAPIDO tiers are synthesized from the Bengaluru CATALOG — the same
rate-card the on-device `SmartRideDataRepository` prices with — so training
and inference share one source of truth for those providers.

### Why the surge LSTM keeps the synthetic Bengaluru curve

No public Indian dataset records 5-minute surge multiplier history at city
scale, so the bi-modal commute curve (8–10:30 AM and 5:30–8:30 PM peaks) is
generated synthetically. We inject **real Indian weather** as a side
channel so the LSTM still learns "monsoon rain → surge stays high longer"
and the on-device "wait 10 min" advice respects the rain forecast.

### Datasets considered and rejected

* **Boston Uber + Lyft** (`brllrb/uber-and-lyft-dataset-boston-ma`) — earlier
  revisions used this with a USD→INR multiplier, but the underlying pricing
  structure (per-km rates, surge response) is American. Dropped to keep the
  learned distribution India-native.
* **NYC Uber Fares** (`yasserh/uber-fares-dataset`) — same reason as Boston;
  US pricing structure doesn't generalise to Indian rupee-per-km rates.
* **Ola Bike Ride Request Forecast** (`shubhambathwal/ola-bike-ride-request-forecast`) — demand / timestamp data only, no fare column.
* **Uber Request Data** (`anupammajhi/uber-request-data`) — Bengaluru airport
  request data, no fare column.
* **NYC TLC Yellow / Green Taxi Trip Data** — taxi (not rideshare), no surge
  multiplier, pricing structure diverges from Uber/Ola tiering.

## Architectures

Both networks are tiny (~5–8k params each, <50 KiB after TFLite quantisation)
because they have to run inside an Android app on a phone CPU. The trainer
prints the same diagrams at the start of each model's run, plus a banner
confirming **no pre-trained weights are loaded**.

### Surge LSTM forecaster — architecture

```
Input   (1, 24, 7)   float32
        24 timesteps × 7 features per step:
        [price_norm, sin_h, cos_h,
         temperature_z, precipIntensity_z, windSpeed_z, visibility_z]
                       │
                       ▼
        LSTM(units=32, default tanh)         ~5,120 params
                       │
                       ▼
        Dense(32, relu)                      ~1,056 params
                       │
                       ▼
        Dense(6, linear)                       ~198 params
                       │
                       ▼
Output  (1, 6)       float32   next 6 × 5 min = 30 min in normalised price

Loss    Mean Squared Error
Optim.  Adam(lr=1e-3)
Init    Glorot uniform (Dense) | orthogonal (LSTM recurrent) | zeros (biases)
```

### Fare estimator MLP — architecture

```
Input   (1, 11)      float32
        [distance_norm, duration_norm, hour_sin, hour_cos,
         provider_uber, provider_ola, provider_rapido,
         tier_bike, tier_auto, tier_economy, tier_premium]
                       │
                       ▼
        Dense(64, relu)                        ~768 params
                       │
                       ▼
        Dense(32, relu)                      ~2,080 params
                       │
                       ▼
        Dense(3, linear)   ← 3 quantile heads  ~99 params
                       │
                       ▼
Output  (1, 3)       float32   [p10, p50, p90] of normalised fare

Loss    Pinball (quantile) loss averaged across the 3 heads
Optim.  Adam(lr=1e-3)
Init    Glorot uniform (Dense kernels) | zeros (biases)
```

### Why these architectures

* **LSTM (not ARIMA)** for surge — stdlib ARIMA implementations are
  Python-only and don't export to TFLite. A 32-unit LSTM (~20 KiB quantised)
  captures the same short-horizon auto-correlation and runs on any Android
  device without a delegate.
* **MLP (not gradient-boosted trees)** for fares — trees export awkwardly to
  TFLite and balloon in size. A two-layer MLP (64 → 32 → 3) fits the pricing
  formula well under 25 KiB and runs in <1 ms.
* **Quantile regression (not Bayesian / dropout MC)** for confidence — three
  Dense output heads + pinball loss is the cheapest way to get calibrated
  uncertainty on-device. No extra inference passes, no Monte Carlo sampling.

## Model contracts

Both contracts are hard-coded on the Python *and* Kotlin sides. Changing any
number here means editing the training script **and** the corresponding
Android loader (`SurgePredictorImpl.kt` / `FareEstimatorImpl.kt`) in lock-step.

### Surge LSTM forecaster

| Property      | Value    | Notes                                                                |
| ------------- | -------- | -------------------------------------------------------------------- |
| step          | 5 min    | resolution of each price point                                       |
| window        | 24       | last 2 h of history is the model's input                             |
| horizon       | 6        | next 30 min is the model's output                                    |
| features      | 7        | price + cyclic hour + 4 weather z-scores (see order below)           |
| input dtype   | float32  | `(1, 24, 7)`                                                         |
| output dtype  | float32  | `(1, 6)` — prices still in normalised space                          |

Feature order (each timestep):

```
[price_normalised, hour_sin, hour_cos,
 temperature_z, precipIntensity_z, windSpeed_z, visibility_z]
```

Weather z-score normalisation constants are written to
`surge_forecast_meta.weather_normalisation` (`columns`, `means`, `stds`)
when the real-data trainer runs. The on-device weather provider must
z-score live readings against those stats before feeding the model. The
synthetic trainer writes `means=[0]*4, stds=[1]*4` so the model still has
the right input slots — wire up real weather later without retraining a
different architecture.

### Fare estimator MLP

| Property      | Value    | Notes                                                                              |
| ------------- | -------- | ---------------------------------------------------------------------------------- |
| features      | 11       | see order below                                                                    |
| input dtype   | float32  | `(1, 11)`                                                                          |
| output dtype  | float32  | `(1, 3)` — `[p10, p50, p90]` of normalised fare                                    |
| distance norm | ÷ 20.0   | divide `distance_km` by this before feeding the model                              |
| duration norm | ÷ 60.0   | divide `duration_min` by this                                                      |

Feature order (must match exactly on the Android side):

```
[distance_norm, duration_norm, hour_sin, hour_cos,
 provider_uber, provider_ola, provider_rapido,
 tier_bike, tier_auto, tier_economy, tier_premium]
```

Output is **3 quantile heads** trained with pinball (quantile) loss:
`fare_estimator_meta.quantiles = [0.10, 0.50, 0.90]`,
`output_order = ["p10", "p50", "p90"]`. De-normalise each with
`fare_mean_inr` + `fare_std_inr`, then **sort the three values ascending**
on-device — quantile crossing is rare but possible, and the sort costs
nothing. The UI shows `p50 ± (p90 − p10) / 2` as the "₹240 (±₹35)"
confidence band.

The tier catalog in `train_fare_estimator.py` mirrors
`SmartRideDataRepository.CATALOG` on the Android side — keep them in sync so
training-time fares match inference-time pricing behaviour.

### Android-side updates required

Both upgrades change the on-device contract; the Kotlin loaders need to
catch up before new artefacts can ship:

* `SurgePredictorImpl.kt` — bump input tensor to `(1, 24, 7)`, append the 4
  z-scored weather features per timestep. Add a `WeatherProvider`
  abstraction that returns the latest temperature / precipIntensity /
  windSpeed / visibility, z-scored against the meta JSON's
  `weather_normalisation` block.
* `FareEstimatorImpl.kt` — change output buffer to `FloatArray(3)`, sort
  ascending after read, expose `Triple<p10, p50, p90>` instead of a single
  fare. Build sites should consume `p50` for the headline price.
* Fare card UI (`RideOptionCard.kt` or equivalent) — render the
  ±confidence band next to the median fare.

## Generating paper figures

Two figures referenced in the report (`Fig. 3 — Surge Prediction vs.
Ground Truth` and `Fig. 4 — Fare Comparison Dashboard`) are produced by
a separate matplotlib script. Outputs land in `ml_model/figures/`.

```bash
cd ml_model
python generate_figures.py
```

What it produces:

| File                              | Source                                                                                                                |
| --------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| `figures/surge_graph.pdf`         | Slides a 24-tick window across a full simulated Bengaluru day, runs the trained TFLite model on each window, plots the 30-minute predictions against the ground truth. AM/PM commute peaks are shaded; the legend reports the mean absolute error. Falls back to an analytic mean-reversion predictor when the `.tflite` assets are missing or TF isn't installed. |
| `figures/dashboard_screenshot.png` | A layout-accurate matplotlib mockup of the Android dashboard — Unified Mobility header, route map block, advice banner, three ranked ride cards (Uber / Ola / Rapido) with sparklines and CTAs, plus a multimodal combo strip. Used in the paper as a substitute for a device screenshot so the build doesn't have to be run on a phone to refresh the figure. |

Both files overwrite cleanly on each run, and the random seed is fixed
to `42` so re-runs produce byte-identical figures (paper-friendly for
diffs in version control).
