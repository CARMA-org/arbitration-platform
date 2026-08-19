import numpy as np
from . import solver

POS = 1e-9


def agent_utilities(instance, cfgs, A):
    return np.array([
        solver.eval_utility(cfgs[i], instance.W[i], A[i]) for i in range(instance.n)])


def weighted_log_objective(instance, cfgs, A):
    u = agent_utilities(instance, cfgs, A)
    return float(np.sum(instance.c * np.log(np.maximum(u, POS))))


def normalized_utilities(instance, cfgs, A):
    u = agent_utilities(instance, cfgs, A)
    ref = np.array([
        solver.eval_utility(cfgs[i], instance.W[i], instance.ideals[i])
        for i in range(instance.n)])
    ref = np.maximum(ref, POS)
    return u / ref


def bound_fractions(instance, A, tol=1e-6):
    lo = instance.mins
    hi = instance.ideals
    at_lo = np.isclose(A, lo, atol=tol)
    at_hi = np.isclose(A, hi, atol=tol)
    interior = (~at_lo) & (~at_hi)
    total = A.size
    return (float(at_lo.sum()) / total,
            float(interior.sum()) / total,
            float(at_hi.sum()) / total)


def capacity_utilization(instance, A):
    used = A.sum(axis=0)
    return float(np.mean(used / np.maximum(instance.Q, POS)))


def rule_metrics(instance, cfgs, A, equal_norm, strongest_norm):
    norm = normalized_utilities(instance, cfgs, A)
    at_lo, interior, at_hi = bound_fractions(instance, A)
    delta_equal = norm - equal_norm
    return {
        "objective": weighted_log_objective(instance, cfgs, A),
        "mean_norm_util": float(np.mean(norm)),
        "min_norm_util": float(np.min(norm)),
        "median_norm_util": float(np.median(norm)),
        "frac_worse_than_equal": float(np.mean(norm < equal_norm - 1e-9)),
        "frac_worse_than_strongest_sep": float(np.mean(norm < strongest_norm - 1e-9)),
        "worst_indiv_change_vs_equal": float(np.min(delta_equal)),
        "p5_indiv_change_vs_equal": float(np.percentile(delta_equal, 5)),
        "capacity_utilization": capacity_utilization(instance, A),
        "frac_cells_at_lower": at_lo,
        "frac_cells_interior": interior,
        "frac_cells_at_upper": at_hi,
    }


def summarize(values):
    v = np.asarray(values, dtype=float)
    v = v[np.isfinite(v)]
    if v.size == 0:
        return {"median": None, "iqr_lo": None, "iqr_hi": None, "p5": None, "p95": None, "mean": None}
    return {
        "median": float(np.median(v)),
        "iqr_lo": float(np.percentile(v, 25)),
        "iqr_hi": float(np.percentile(v, 75)),
        "p5": float(np.percentile(v, 5)),
        "p95": float(np.percentile(v, 95)),
        "mean": float(np.mean(v)),
    }
