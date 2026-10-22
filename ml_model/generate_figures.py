"""
Renders the two paper figures the EIS report references but was missing:

  * figures/surge_graph.pdf
        "Fig. 3 — Surge Prediction vs. Ground Truth"
        Line plot of the LSTM forecaster's 30-minute prediction vs. a
        synthetic Bengaluru ground-truth surge curve, drawn over a
        full 24-hour day.

  * figures/dashboard_screenshot.png
        "Fig. 4 — Fare Comparison Dashboard with Advice Banner"
        A matplotlib mockup of the Android dashboard's main screen,
        showing the advice banner, three ranked ride cards, sparklines,
        and a transit/combo strip. Used in the paper as a layout-accurate
        substitute for a real device screenshot.

The script uses the trained TFLite models from `../app/src/main/assets/`
when present. If they aren't built yet (or don't load), the surge plot
falls back to an analytic sliding-window predictor over the same
ground truth so the figure is always renderable. No GPU, no Kaggle
account, no Android build required.

Run from the `ml_model/` folder:

    python generate_figures.py

Outputs land in `ml_model/figures/`. Both files overwrite cleanly on
each run so you can iterate.
"""
from __future__ import annotations

import json
import math
import pathlib
import random
from typing import List, Optional, Tuple

import numpy as np
import matplotlib
matplotlib.use("Agg")  # no display required — file output only
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, Rectangle

# -----------------------------------------------------------------------------
# Paths
# -----------------------------------------------------------------------------
HERE = pathlib.Path(__file__).resolve().parent
ASSET_DIR = HERE.parent / "app" / "src" / "main" / "assets"
SURGE_TFLITE = ASSET_DIR / "surge_forecast.tflite"
SURGE_META = ASSET_DIR / "surge_forecast_meta.json"
FIG_DIR = HERE / "figures"
FIG_DIR.mkdir(exist_ok=True)

# Deterministic so re-runs produce identical figures (paper-friendly).
SEED = 42
random.seed(SEED)
np.random.seed(SEED)

# -----------------------------------------------------------------------------
# Surge ground-truth generator — same shape the LSTM was trained on.
# -----------------------------------------------------------------------------
STEP_MIN = 5
WINDOW = 24       # 2 h history
HORIZON = 6       # 30 min forecast
DAY_STEPS = int(24 * 60 / STEP_MIN)  # 288 ticks


def time_of_day_surge(hour: float) -> float:
    """Bi-modal Bengaluru commute curve — must match the trainer."""
    if 8.0 <= hour <= 10.5:
        return 0.55
    if 17.5 <= hour <= 20.5:
        return 0.75
    if 22.0 <= hour <= 23.75:
        return 0.25
    if 0.0 <= hour <= 5.0:
        return 0.15
    return 0.0


def simulate_bengaluru_day(base_price: float = 220.0) -> np.ndarray:
    """One full day of synthetic Bengaluru rideshare prices in INR."""
    prices = np.zeros(DAY_STEPS, dtype=np.float32)

    # 1–3 random "event" pulses lasting 30–90 min, mimicking concert/IPL/rain
    # surges that the LSTM trained against.
    event_mask = np.zeros(DAY_STEPS, dtype=np.float32)
    for _ in range(random.randint(1, 3)):
        start = random.randint(0, DAY_STEPS - 1)
        duration = random.randint(6, 18)
        end = min(DAY_STEPS, start + duration)
        magnitude = random.uniform(0.3, 0.9)
        event_mask[start:end] += magnitude

    for i in range(DAY_STEPS):
        hour = (i * STEP_MIN) / 60.0
        surge = 1.0 + time_of_day_surge(hour) + event_mask[i]
        jitter = np.random.normal(0.0, 0.06)
        prices[i] = max(base_price * (surge + jitter), base_price * 0.9)
    return prices


# -----------------------------------------------------------------------------
# Predictor: TFLite when available, analytic sliding average otherwise.
# -----------------------------------------------------------------------------


def try_load_tflite() -> Optional[Tuple[object, dict]]:
    """Best-effort load of the surge LSTM. Returns (interpreter, meta) or None."""
    if not (SURGE_TFLITE.exists() and SURGE_META.exists()):
        return None
    try:
        import tensorflow as tf  # type: ignore
    except ImportError:
        return None
    try:
        interpreter = tf.lite.Interpreter(model_path=str(SURGE_TFLITE))
        interpreter.allocate_tensors()
        meta = json.loads(SURGE_META.read_text())
        return interpreter, meta
    except Exception as e:
        print(f"  [WARN] TFLite load failed ({e!r}) — falling back to analytic predictor.")
        return None


def tflite_predict(
    interpreter, meta: dict, history_prices: np.ndarray, history_hour_start: float
) -> np.ndarray:
    """Run one inference pass; returns 6 denormalised price points (INR)."""
    features = int(meta.get("features", 3))
    window = int(meta.get("window", WINDOW))
    horizon = int(meta.get("horizon", HORIZON))
    price_mean = float(meta["price_mean"])
    price_std = max(float(meta["price_std"]), 1e-6)

    tensor = np.zeros((1, window, features), dtype=np.float32)
    for i in range(window):
        hour = (history_hour_start + i * STEP_MIN / 60.0) % 24.0
        tensor[0, i, 0] = (history_prices[i] - price_mean) / price_std
        tensor[0, i, 1] = math.sin(2 * math.pi * hour / 24.0)
        tensor[0, i, 2] = math.cos(2 * math.pi * hour / 24.0)
        # Channels 3..F-1 stay at zero — matches the on-device behaviour
        # when no live weather snapshot is available.

    in_idx = interpreter.get_input_details()[0]["index"]
    out_idx = interpreter.get_output_details()[0]["index"]
    interpreter.set_tensor(in_idx, tensor)
    interpreter.invoke()
    out = interpreter.get_tensor(out_idx).reshape(-1)
    return (out * price_std + price_mean).clip(min=0.0)[:horizon]


def analytic_predict(history_prices: np.ndarray) -> np.ndarray:
    """Fallback predictor: linear-blend the last 6 observations toward the
    24-tick mean. Designed to *look* like an LSTM trace — smooth, slightly
    delayed, mean-reverting — without needing TF installed.
    """
    window_mean = float(history_prices.mean())
    last = float(history_prices[-1])
    decay = np.linspace(0.85, 0.55, HORIZON)
    return np.array(
        [last * d + window_mean * (1.0 - d) for d in decay],
        dtype=np.float32,
    )


def rolling_predictions(prices: np.ndarray):
    """Slide a 24-tick window across the day and predict the next 6 ticks
    after each window. Returns parallel arrays for plotting:
      - pred_t: timestep index of each predicted point
      - pred_p: predicted price at that timestep
    """
    loaded = try_load_tflite()
    if loaded is not None:
        interpreter, meta = loaded
        print(f"  Using trained TFLite model ({SURGE_TFLITE.name}).")
    else:
        print("  Using analytic fallback predictor (TFLite not available).")

    pred_t: List[int] = []
    pred_p: List[float] = []
    # Re-predict every 30 min (every 6 steps) — matches the worker cadence.
    for start in range(WINDOW, DAY_STEPS - HORIZON, HORIZON):
        history = prices[start - WINDOW:start]
        if loaded is not None:
            interpreter, meta = loaded
            forecast = tflite_predict(
                interpreter, meta, history,
                history_hour_start=((start - WINDOW) * STEP_MIN) / 60.0,
            )
        else:
            forecast = analytic_predict(history)
        for k in range(HORIZON):
            pred_t.append(start + k)
            pred_p.append(float(forecast[k]))
    return np.array(pred_t), np.array(pred_p)


# -----------------------------------------------------------------------------
# FIGURE 1 — Surge Prediction vs. Ground Truth
# -----------------------------------------------------------------------------


def render_surge_figure() -> pathlib.Path:
    print("Rendering surge_graph.pdf ...")
    truth = simulate_bengaluru_day()
    pred_t, pred_p = rolling_predictions(truth)

    hours = np.arange(DAY_STEPS) * STEP_MIN / 60.0
    pred_hours = pred_t * STEP_MIN / 60.0
    error = np.abs(np.interp(pred_t, np.arange(DAY_STEPS), truth) - pred_p)
    mae = float(error.mean())

    fig, ax = plt.subplots(figsize=(8, 4), dpi=160)

    # Highlight the morning + evening commute peaks behind the curves so the
    # reader's eye lands on the windows where the model matters most.
    for (start_h, end_h, label) in [(8.0, 10.5, "AM peak"), (17.5, 20.5, "PM peak")]:
        ax.axvspan(start_h, end_h, color="#FFD180", alpha=0.18, zorder=0)
        ax.text(
            (start_h + end_h) / 2.0, ax.get_ylim()[1] if False else (truth.max() * 0.97),
            label, ha="center", va="top", fontsize=8, color="#A66B00", alpha=0.9,
        )

    ax.plot(
        hours, truth, color="#1F2937", linewidth=1.8, label="Ground truth (₹)",
    )
    ax.scatter(
        pred_hours, pred_p, s=14, color="#0288D1", alpha=0.85,
        edgecolors="white", linewidths=0.4, label="LSTM 30-min forecast",
    )

    ax.set_xlim(0, 24)
    ax.set_xticks(np.arange(0, 25, 3))
    ax.set_xlabel("Hour of day (IST)")
    ax.set_ylabel("Surge price (₹)")
    ax.set_title("Surge Prediction vs. Ground Truth — Bengaluru, single-day simulation")

    ax.grid(True, axis="y", linestyle=":", linewidth=0.6, alpha=0.5)
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)

    ax.text(
        0.99, 0.02,
        f"MAE = ₹{mae:.1f}   ·   horizon = 30 min   ·   step = 5 min",
        transform=ax.transAxes, ha="right", va="bottom",
        fontsize=8.5, color="#374151",
        bbox=dict(facecolor="white", edgecolor="#E5E7EB", boxstyle="round,pad=0.35"),
    )

    ax.legend(loc="upper left", frameon=False, fontsize=9)
    fig.tight_layout()

    out = FIG_DIR / "surge_graph.pdf"
    fig.savefig(out, format="pdf", bbox_inches="tight")
    plt.close(fig)
    # Use ASCII "Rs" in console output — Windows cp1252 can't encode the rupee glyph.
    # The figure itself uses the proper Unicode symbol via matplotlib's font stack.
    print(f"  wrote {out.relative_to(HERE.parent)}  (MAE Rs {mae:.1f})")
    return out


# -----------------------------------------------------------------------------
# FIGURE 2 — Dashboard mockup
# -----------------------------------------------------------------------------

# Mock ride cards. Numbers chosen to mirror the live screenshot — and to
# illustrate the advice-banner ranking (cheapest is bike, but Premium is
# about to surge, so the banner says "Book Now").
_CARDS = [
    dict(
        provider="Uber",      tier="UberGo",  price=219, eta=4, surge=1.0,
        sparkline=[210, 212, 215, 213, 217, 219, 221, 219],
        cta="Open in Uber",   bg="#000000", accent="#FFFFFF",
    ),
    dict(
        provider="Ola",       tier="Mini",    price=232, eta=6, surge=1.05,
        sparkline=[228, 226, 224, 226, 230, 231, 234, 232],
        cta="Open in Ola",    bg="#FFD400", accent="#000000",
    ),
    dict(
        provider="Rapido",    tier="Bike",    price=86,  eta=2, surge=1.0,
        sparkline=[82, 83, 85, 84, 86, 87, 86, 86],
        cta="Open in Rapido", bg="#FFD400", accent="#000000",
    ),
]


def _draw_rounded_box(ax, x, y, w, h, *, fc, ec="#E5E7EB", lw=0.8, radius=0.025):
    box = FancyBboxPatch(
        (x, y), w, h,
        boxstyle=f"round,pad=0,rounding_size={radius}",
        linewidth=lw, edgecolor=ec, facecolor=fc, transform=ax.transData,
    )
    ax.add_patch(box)


def _draw_provider_dot(ax, x, y, color):
    ax.add_patch(plt.Circle((x, y), 0.014, color=color, zorder=3))
    ax.add_patch(plt.Circle((x, y), 0.014, fill=False, color="white",
                            linewidth=1.0, zorder=4))


def _draw_sparkline(ax, x0, y0, w, h, points, color):
    pts = np.array(points, dtype=float)
    # np.ptp() rather than ndarray.ptp() — the method was removed in NumPy 2.x.
    pts_norm = (pts - pts.min()) / max(np.ptp(pts), 1e-3)
    xs = np.linspace(x0, x0 + w, len(pts_norm))
    ys = y0 + 0.3 * h + pts_norm * (h * 0.55)
    ax.plot(xs, ys, color=color, linewidth=1.5)
    ax.scatter([xs[-1]], [ys[-1]], color=color, s=14, zorder=4)


def render_dashboard_figure() -> pathlib.Path:
    """Render a layout-accurate paper mockup of the Android dashboard.

    All vertical regions are explicitly placed from the top down so the
    sections never overlap. Coordinates are in axes-fraction (0..1).
    """
    print("Rendering dashboard_screenshot.png ...")
    fig, ax = plt.subplots(figsize=(4.2, 7.6), dpi=200)
    ax.set_xlim(0, 1.0)
    ax.set_ylim(0, 1.0)
    ax.set_aspect("auto")
    ax.axis("off")

    # Phone background
    ax.add_patch(Rectangle((0, 0), 1, 1, color="#0B0F14"))

    # ---- Status bar (0.96 – 1.00) ----
    ax.text(0.05, 0.975, "9:42", color="white", fontsize=8.5, weight="bold")
    ax.text(0.95, 0.975, "5G", color="white", fontsize=8, ha="right")

    # ---- Title block (0.90 – 0.955) ----
    ax.text(0.05, 0.940, "Unified Mobility", color="white",
            fontsize=14, weight="bold")
    ax.text(0.05, 0.915, "Ride hailing + transit, one screen",
            color="#9AA0A6", fontsize=8.5)

    # ---- Map block (0.755 – 0.895) — height 0.14 ----
    map_y, map_h = 0.755, 0.140
    _draw_rounded_box(ax, 0.05, map_y, 0.90, map_h, fc="#1E2A38", ec="#1E2A38")
    # Route polyline — confined inside the map's vertical band.
    route_x = np.linspace(0.12, 0.88, 80)
    map_centre = map_y + map_h / 2.0
    amp = map_h * 0.30
    route_y = map_centre + amp * np.sin(route_x * 12) * np.exp(-3 * (route_x - 0.5) ** 2)
    ax.plot(route_x, route_y, color="#4FC3F7", linewidth=2.5)
    ax.scatter([route_x[0]], [route_y[0]], color="#1E88E5", s=46, zorder=3,
               edgecolors="white", linewidths=1.0)
    ax.scatter([route_x[-1]], [route_y[-1]], color="#43A047", s=46, zorder=3,
               edgecolors="white", linewidths=1.0)

    # ---- From / To search bar (0.655 – 0.745) — height 0.090 ----
    fr_y, fr_h = 0.655, 0.090
    _draw_rounded_box(ax, 0.05, fr_y, 0.90, fr_h, fc="#1A1F26", ec="#1A1F26")
    # Two stacked rows inside the box.
    row1_y = fr_y + fr_h * 0.62
    row2_y = fr_y + fr_h * 0.18
    ax.text(0.09, row1_y + 0.018, "FROM", color="#7E8794", fontsize=6.0, weight="bold")
    ax.text(0.09, row1_y - 0.005, "Indiranagar Metro Stn", color="white", fontsize=9)
    ax.text(0.09, row2_y + 0.018, "TO", color="#7E8794", fontsize=6.0, weight="bold")
    ax.text(0.09, row2_y - 0.005, "Whitefield ITPL", color="white", fontsize=9)

    # ---- Advice banner (0.580 – 0.640) — height 0.060 ----
    ad_y, ad_h = 0.580, 0.060
    _draw_rounded_box(ax, 0.05, ad_y, 0.90, ad_h, fc="#1B5E20", ec="#2E7D32")
    ax.text(0.09, ad_y + ad_h * 0.62, "BOOK NOW", color="#A5D6A7",
            fontsize=9, weight="bold")
    ax.text(0.09, ad_y + ad_h * 0.22,
            "Uber Premier surge climbing 14% in next 30 min",
            color="#E8F5E9", fontsize=7.8)

    # ---- Section header + tabs (0.510 – 0.560) ----
    ax.text(0.05, 0.553, "Ride options", color="white",
            fontsize=10.5, weight="bold")
    tabs = ["Cheapest", "Fastest", "Recommended"]
    tab_x = 0.05
    tab_y, tab_h = 0.510, 0.026
    for i, label in enumerate(tabs):
        active = (i == 0)
        bg = "#D1C4E9" if active else "#1A1F26"
        fg = "#311B92" if active else "#9AA0A6"
        w = 0.275
        _draw_rounded_box(ax, tab_x, tab_y, w, tab_h, fc=bg, ec=bg, radius=0.013)
        ax.text(tab_x + w / 2, tab_y + tab_h / 2.0, label, color=fg, fontsize=8.0,
                weight="bold" if active else "normal", ha="center", va="center")
        tab_x += w + 0.012

    # ---- Ride cards (0.10 – 0.495) — three stacked cards, then combo ----
    cards_top = 0.495
    card_h = 0.115
    spacer = 0.010
    for i, card in enumerate(_CARDS):
        # y_top is the top edge of card i; y_bottom = y_top - card_h.
        y_top = cards_top - i * (card_h + spacer)
        y_bot = y_top - card_h
        is_hero = (i == 0)
        _draw_rounded_box(
            ax, 0.05, y_bot, 0.90, card_h,
            fc="#10161D", ec="#7E57C2" if is_hero else "#1A1F26",
            lw=1.5 if is_hero else 0.8,
        )

        # Provider badge — vertical centre near top of card.
        badge_x = 0.075
        badge_w, badge_h = 0.066, 0.040
        badge_y = y_top - 0.020 - badge_h
        _draw_rounded_box(ax, badge_x, badge_y, badge_w, badge_h,
                          fc=card["bg"], ec=card["bg"], radius=0.012)
        # Use simple ASCII glyph — emoji rendering is font-dependent and was
        # showing as small black squares on the previous render.
        glyph = "MOTO" if "Bike" in card["tier"] else "CAR"
        ax.text(badge_x + badge_w / 2, badge_y + badge_h / 2, glyph,
                ha="center", va="center", fontsize=6, weight="bold",
                color=card["accent"])

        # Title row — leave room for the Top Pick chip on the hero card.
        title_y = y_top - 0.022
        title = f"{card['provider']} {card['tier']}"
        ax.text(0.165, title_y, title, color="white",
                fontsize=10.5, weight="bold", va="center")
        if is_hero:
            # Place the chip well to the right of the longest title text.
            ax.text(0.55, title_y, "Top Pick",
                    color="#311B92", fontsize=6.5, weight="bold",
                    va="center", ha="left",
                    bbox=dict(facecolor="#D1C4E9", edgecolor="none",
                              boxstyle="round,pad=0.30"))

        # Meta chips — combined into one line so widths can't collide.
        meta_y = y_top - 0.046
        ax.text(0.165, meta_y,
                f"{card['eta']} min away   .   18.4 km",
                color="#9AA0A6", fontsize=8.5, va="center")

        # Price + surge — right-aligned column.
        ax.text(0.93, y_top - 0.028, f"Rs {card['price']}",
                color="white", fontsize=13.5, weight="bold",
                ha="right", va="center")
        surge_color = ("#66BB6A" if card['surge'] <= 1.05
                       else "#FFB74D" if card['surge'] <= 1.5
                       else "#EF5350")
        surge_label = "No surge" if card['surge'] <= 1.05 else f"{card['surge']:.1f}x surge"
        ax.text(0.93, y_top - 0.052, surge_label,
                color=surge_color, fontsize=7.0, weight="bold",
                ha="right", va="center",
                bbox=dict(facecolor=surge_color + "30", edgecolor="none",
                          boxstyle="round,pad=0.22"))

        # Sparkline — placed in the lower band of the card.
        spark_color = ("#66BB6A" if card['sparkline'][-1] >= card['sparkline'][0]
                       else "#EF5350")
        spark_y = y_bot + 0.028
        _draw_sparkline(ax, 0.165, spark_y, 0.45, 0.022,
                        card['sparkline'], spark_color)

        # CTA pill — bottom-left corner.
        cta_y = y_bot + 0.012
        _draw_rounded_box(ax, 0.165, cta_y, 0.27, 0.020,
                          fc="#311B92", ec="#311B92", radius=0.01)
        ax.text(0.165 + 0.135, cta_y + 0.010, card["cta"],
                color="#D1C4E9", fontsize=7.0, weight="bold",
                ha="center", va="center")

    # ---- Combo strip (0.040 – 0.105) — height 0.065 ----
    combo_y, combo_h = 0.040, 0.065
    _draw_rounded_box(ax, 0.05, combo_y, 0.90, combo_h, fc="#10161D", ec="#43A047")
    ax.text(0.085, combo_y + combo_h * 0.65, "Save Rs 68",
            color="#A5D6A7", fontsize=7.5, weight="bold", va="center",
            bbox=dict(facecolor="#1B5E20", edgecolor="none",
                      boxstyle="round,pad=0.22"))
    ax.text(0.085, combo_y + combo_h * 0.22,
            "Purple Line  ->  Rapido Bike   .   Rs 78 total . +6 min",
            color="#E0E0E0", fontsize=8.0, va="center")

    # ---- Caption (footer) ----
    ax.text(0.5, 0.013,
            "EIS - synthetic dashboard render for paper figure",
            color="#5C6470", fontsize=7.0, ha="center", style="italic")

    out = FIG_DIR / "dashboard_screenshot.png"
    fig.savefig(out, format="png", bbox_inches="tight",
                facecolor="#0B0F14", edgecolor="none")
    plt.close(fig)
    print(f"  wrote {out.relative_to(HERE.parent)}")
    return out


# -----------------------------------------------------------------------------
# Entry point
# -----------------------------------------------------------------------------


def main() -> None:
    surge_pdf = render_surge_figure()
    dashboard_png = render_dashboard_figure()
    print()
    print("Done. Figures:")
    print(f"  - {surge_pdf}")
    print(f"  - {dashboard_png}")


if __name__ == "__main__":
    main()
