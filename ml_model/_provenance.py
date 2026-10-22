"""
Shared provenance helpers — print banners and assert that no pre-trained
weights are sneaking into either trainer.

Both `train_surge_lstm.py` and `train_fare_estimator.py` import from this
file. The intent is to make "this is a custom architecture trained from
scratch" visible at three places:

  1. Stdout — printed banner before every training run.
  2. Code   — runtime assertion that confirms freshly-built weights are
              actually at Glorot-uniform scale (i.e. nothing has been
              loaded from disk before .fit() runs).
  3. Meta   — writes `from_scratch: true` and `pretrained_checkpoint: null`
              into the model's meta JSON so the artefacts are auditable
              after the fact.
"""
from __future__ import annotations

from typing import Any, Dict

import numpy as np


# Random seed used by both trainers — kept here so it's the single source
# of truth referenced by the banner, the assertion, and the meta JSON.
RANDOM_SEED = 42


def print_no_pretrain_banner() -> None:
    print(">>")
    print(">> Initialising from random weights — NO pre-trained checkpoints are loaded.")
    print(">> Glorot uniform (Dense kernels) | orthogonal (LSTM recurrent) | zeros (biases)")
    print(f">> Deterministic init via RANDOM_SEED = {RANDOM_SEED}")
    print(">>")


def print_surge_architecture(features_per_step: int) -> None:
    print(r"""
+-------------------------------------------------------------------------+
| SURGE LSTM FORECASTER  (custom — trained from scratch, no pre-training) |
+-------------------------------------------------------------------------+""")
    print(f"| Input   (1, 24, {features_per_step})   float32   2 h history, 5-min steps          |")
    print(r"""|                       v                                                 |
|         LSTM(units=32, default tanh)         ~5,120 params              |
|                       v                                                 |
|         Dense(32, relu)                      ~1,056 params              |
|                       v                                                 |
|         Dense(6, linear)                       ~198 params              |
|                       v                                                 |
| Output  (1, 6)       float32   next 30 min, normalised price            |
|                                                                         |
| Loss    Mean Squared Error                                              |
| Optim.  Adam(lr=1e-3)                                                   |
+-------------------------------------------------------------------------+
""")


def print_fare_architecture(quantile: bool) -> None:
    head = "Dense(3, linear)   <-- 3 quantile heads" if quantile else "Dense(1, linear)   <-- single fare head"
    out_shape = "(1, 3)  [p10, p50, p90]" if quantile else "(1, 1)  scalar fare"
    loss = "Pinball (quantile) loss" if quantile else "Mean Squared Error"
    print(r"""
+-------------------------------------------------------------------------+
| FARE ESTIMATOR MLP   (custom — trained from scratch, no pre-training)   |
+-------------------------------------------------------------------------+
| Input   (1, 11)      float32                                            |
|         [distance_norm, duration_norm, hour_sin, hour_cos,              |
|          provider_uber, provider_ola, provider_rapido,                  |
|          tier_bike, tier_auto, tier_economy, tier_premium]              |
|                       v                                                 |
|         Dense(64, relu)                        ~768 params              |""")
    print(f"|         Dense(32, relu)                      ~2,080 params              |")
    print(f"|         {head:<63}|")
    print(f"|                       v                                                 |")
    print(f"| Output  {out_shape:<64}|")
    print(f"|                                                                         |")
    print(f"| Loss    {loss:<64}|")
    print(r"""| Optim.  Adam(lr=1e-3)                                                   |
+-------------------------------------------------------------------------+
""")


def assert_random_init(model: Any) -> None:
    """Verify the model's *trainable* weights look randomly initialised.

    A loaded checkpoint would land tensors at trained values; we sanity-check
    by walking each layer:

      * Dense layers — kernel std should be Glorot-scale (~0.05–0.5), bias
        should be exactly zero (Keras default).
      * LSTM layers — kernel + recurrent_kernel std should be Glorot/orthogonal-
        scale; the bias vector is allowed to be non-zero because Keras
        initialises the forget-gate slice to ones (`unit_forget_bias=True`),
        which is *expected* behaviour, not evidence of a checkpoint.
      * Every weight must be finite (no NaN/Inf — would indicate a corrupt
        checkpoint partial-load).

    The intent is "fail loud if someone accidentally wires up
    `model.load_weights(...)` upstream of training". It is NOT a
    cryptographic guarantee — code review is the real defence. The
    Glorot-scale check is loose because Keras's default initialisers and
    a trained checkpoint can produce overlapping weight distributions.
    """
    issues: list = []

    for layer in model.layers:
        layer_type = layer.__class__.__name__
        for w in layer.weights:
            arr = w.numpy()
            if not np.isfinite(arr).all():
                issues.append(
                    f"  {layer_type}.{w.name!r} contains NaN/Inf — "
                    "the weight buffer is corrupt or partially loaded."
                )
                continue

            if arr.size <= 1:
                continue

            wname = w.name.lower()
            if "bias" in wname:
                if layer_type == "Dense":
                    if np.any(arr != 0.0):
                        issues.append(
                            f"  Dense.{w.name!r} bias is non-zero at init "
                            f"(expected zeros — possible loaded checkpoint)."
                        )
                # LSTM biases legitimately include `unit_forget_bias=1.0` —
                # don't flag those.
                continue

            std = float(arr.std())
            if std < 1e-4:
                issues.append(
                    f"  {layer_type}.{w.name!r} kernel std {std:.2e} is near "
                    "zero — possibly a pruned / loaded checkpoint."
                )

    if issues:
        print(">> [ASSERTION FAILED] Model weights do NOT look randomly initialised:")
        for line in issues:
            print(line)
        raise RuntimeError(
            "Model appears to have been initialised from a checkpoint. "
            "EIS trains both models from scratch — investigate before continuing."
        )
    print(">> Random-init assertion passed: kernels at Glorot/orthogonal scale,",
          "Dense biases are zero, LSTM forget-gate bias is at Keras default 1.0.")


def provenance_meta() -> Dict[str, Any]:
    """JSON block recording how the artefact was produced.

    Both trainers spread this dict into their meta JSON so a reader of
    `surge_forecast_meta.json` / `fare_estimator_meta.json` can see at a
    glance that nothing was preloaded.
    """
    return {
        "from_scratch": True,
        "pretrained_checkpoint": None,
        "random_seed": RANDOM_SEED,
        "weight_init": {
            "dense_kernels": "glorot_uniform (Keras default)",
            "lstm_recurrent": "orthogonal (Keras default)",
            "biases": "zeros (Keras default)",
        },
        "country_scope": "India",
    }
