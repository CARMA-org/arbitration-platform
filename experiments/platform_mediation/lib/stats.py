"""Paired bootstrap confidence intervals for policy differences."""
import numpy as np


def paired_diff_ci(values_a, values_b, n_boot=2000, alpha=0.05, seed=12345):
    """Bootstrap CI for mean(values_a - values_b) over paired observations."""
    a = np.asarray(values_a, dtype=float)
    b = np.asarray(values_b, dtype=float)
    assert a.shape == b.shape, "paired arrays must match"
    diff = a - b
    n = len(diff)
    if n == 0:
        return {"mean": float("nan"), "lo": float("nan"), "hi": float("nan"), "n": 0}
    rng = np.random.default_rng(seed)
    means = np.empty(n_boot)
    for k in range(n_boot):
        idx = rng.integers(0, n, size=n)
        means[k] = diff[idx].mean()
    lo = float(np.percentile(means, 100 * alpha / 2))
    hi = float(np.percentile(means, 100 * (1 - alpha / 2)))
    return {"mean": float(diff.mean()), "lo": lo, "hi": hi, "n": n}
