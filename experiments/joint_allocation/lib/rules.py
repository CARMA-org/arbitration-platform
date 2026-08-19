import numpy as np
from . import solver

EPS = 1e-6
GAMMA_GRID = [0.0, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0]


def bounded_proportional(scores, lower, upper, total):
    n = len(scores)
    a = lower.astype(float).copy()
    remaining = float(total) - a.sum()
    if remaining <= 1e-12:
        return np.minimum(a, upper)
    scores = np.maximum(np.asarray(scores, float), 0.0)
    saturated = np.zeros(n, bool)
    for _ in range(4 * n + 5):
        active = (~saturated) & (a < upper - 1e-12)
        if remaining <= 1e-12 or not active.any():
            break
        idx = np.where(active)[0]
        sa = scores[idx].sum()
        if sa <= 0:
            share = remaining / len(idx)
            progressed = False
            for i in idx:
                room = upper[i] - a[i]
                add = min(share, room)
                a[i] += add
                remaining -= add
                if add < share - 1e-15:
                    saturated[i] = True
                    progressed = True
            if not progressed:
                break
            continue
        overflow = False
        for i in idx:
            room = upper[i] - a[i]
            want = remaining * scores[i] / sa
            if want > room + 1e-12:
                a[i] = upper[i]
                saturated[i] = True
                remaining -= room
                overflow = True
        if not overflow:
            for i in idx:
                a[i] += remaining * scores[i] / sa
            remaining = 0.0
            break
    return a


def _separable(instance, score_col):
    n, m = instance.n, instance.m
    A = np.zeros((n, m))
    for j in range(m):
        A[:, j] = bounded_proportional(
            score_col(j), instance.mins[:, j], instance.ideals[:, j], instance.Q[j])
    return A


def equal_shares(instance):
    return _separable(instance, lambda j: np.ones(instance.n))


def priority_shares(instance):
    return _separable(instance, lambda j: instance.c)


def proportional_weight(instance):
    return _separable(instance, lambda j: instance.c * instance.W[:, j])


def waterfill_gamma(instance, gamma):
    return _separable(
        instance,
        lambda j: instance.c * np.power(np.maximum(instance.W[:, j], EPS), gamma))


def joint_nash(instance, utility_configs):
    res = solver.solve(instance, utility_configs)
    if res["status"] not in ("optimal", "optimal_inaccurate"):
        return None, res
    return np.asarray(res["allocations"], dtype=float), res
