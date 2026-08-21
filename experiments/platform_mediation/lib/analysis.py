import hashlib

import numpy as np


def comparison_rng(name, boot_seed):
    digest = hashlib.sha256(("%s|%d" % (name, boot_seed)).encode()).hexdigest()
    return np.random.default_rng(int(digest[:16], 16))


def cell_bootstrap(diffs, name, boot_seed, n_boot=2000):
    diffs = np.asarray(diffs, float)
    if len(diffs) == 0:
        return {"mean": float("nan"), "ci_lo": float("nan"), "ci_hi": float("nan"), "n": 0}
    rng = comparison_rng(name, boot_seed)
    means = np.array([diffs[rng.integers(0, len(diffs), len(diffs))].mean() for _ in range(n_boot)])
    return {"mean": float(diffs.mean()), "ci_lo": float(np.percentile(means, 2.5)),
            "ci_hi": float(np.percentile(means, 97.5)), "n": int(len(diffs))}


def stratified_bootstrap(per_cell_diffs, name, boot_seed, n_boot=2000):
    cell_arrays = [np.asarray(d, float) for d in per_cell_diffs if len(d) > 0]
    if not cell_arrays:
        return {"mean": float("nan"), "ci_lo": float("nan"), "ci_hi": float("nan"),
                "n_cells": 0, "n_per_cell": 0}
    rng = comparison_rng(name, boot_seed)
    point = float(np.mean([a.mean() for a in cell_arrays]))
    boot = np.empty(n_boot)
    for b in range(n_boot):
        cell_means = [a[rng.integers(0, len(a), len(a))].mean() for a in cell_arrays]
        boot[b] = np.mean(cell_means)
    return {"mean": point, "ci_lo": float(np.percentile(boot, 2.5)),
            "ci_hi": float(np.percentile(boot, 97.5)),
            "n_cells": len(cell_arrays), "n_per_cell": int(len(cell_arrays[0]))}
