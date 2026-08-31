"""Shared analysis helpers for the pilot sweeps.

All policy comparisons are paired by task-workload seed within a cell. Bootstrap
intervals use the canonical ``lib.analysis`` routines with a fixed documented seed
so they are reproducible and reconstructible from the raw records.
"""
import csv
import json
import os
from collections import defaultdict

import numpy as np

from lib.analysis import cell_bootstrap

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(HERE, "results", "raw")
TASKS_PER_RUN = 48


def load_rows(sweep, kind):
    path = os.path.join(RAW, "%s_%s.csv" % (sweep, kind))
    with open(path) as f:
        return list(csv.DictReader(f))


def index_runs(runs):
    idx = {}
    seeds_by_cell = defaultdict(list)
    seen = set()
    cell_meta = {}
    for r in runs:
        idx[(r["cell"], r["seed"], r["policy"])] = r
        if (r["cell"], r["seed"]) not in seen:
            seen.add((r["cell"], r["seed"]))
            seeds_by_cell[r["cell"]].append(r["seed"])
        cell_meta[r["cell"]] = {"regime": r["regime"], "concentration": r["concentration"],
                                "floor_regime": r["floor_regime"], "contention": r["contention"]}
    return idx, seeds_by_cell, cell_meta


def paired_diffs(idx, seeds, cell, treat, base, metric="completion_mean"):
    out = []
    for s in seeds:
        kt, kb = (cell, s, treat), (cell, s, base)
        if kt in idx and kb in idx:
            out.append(float(idx[kt][metric]) - float(idx[kb][metric]))
    return out


def cell_policy_mean(idx, seeds, cell, policy, metric):
    vals = [float(idx[(cell, s, policy)][metric]) for s in seeds if (cell, s, policy) in idx]
    return float(np.mean(vals)) if vals else None


def frac_zero_and_worse(agents, cell, policy):
    """Agent-level fraction completing zero and fraction worse than the paired
    equal allocation, pooled over the cell's agents."""
    rows = [a for a in agents if a["cell"] == cell and a["policy"] == policy]
    if not rows:
        return None, None, None, None
    comps = [float(a["completion"]) for a in rows]
    zero = np.mean([1.0 if c <= 1e-12 else 0.0 for c in comps])
    worse_vals = [float(a["completion_minus_equal"]) for a in rows if a["completion_minus_equal"] not in ("", None)]
    worse = np.mean([1.0 if v < -1e-9 else 0.0 for v in worse_vals]) if worse_vals else None
    return float(zero), (float(worse) if worse is not None else None), float(np.min(comps)), float(np.percentile(comps, 5))


def allocation_cell_at_lower_fraction(agents, cell, policy, resources):
    """Fraction of (agent, used-resource) cells whose installed allocation equals
    its lower bound, pooled over the cell."""
    rows = [a for a in agents if a["cell"] == cell and a["policy"] == policy]
    total = at = 0
    for a in rows:
        alloc = json.loads(a["allocated"])
        lower = json.loads(a["min_bound"])
        # A resource is "used" when its lower or allocation could be positive; use
        # the recorded upper_bound>0 as the "used" indicator to avoid re-deriving demand.
        upper = json.loads(a["upper_bound"])
        for r in resources:
            if upper.get(r, 0) > 0:
                total += 1
                if alloc.get(r, 0) == lower.get(r, 0):
                    at += 1
    return (at / total) if total else None
