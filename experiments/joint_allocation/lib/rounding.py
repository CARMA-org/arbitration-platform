import math
import numpy as np


def java_math_round(x):
    return int(math.floor(x + 0.5))


def naive_cellwise_round(cont):
    cont = np.asarray(cont, dtype=float)
    return np.vectorize(java_math_round)(cont).astype(np.int64)


def largest_remainder_round(cont, lower, upper, cap):
    """Bounded largest-remainder rounding, one resource column at a time.

    Mirrors ConvexJointArbitrator.roundColumnsPreservingCapacity: integer floors
    clamped into [lower, upper]; a column target of min(cap, round(colsum)); the
    residual units handed to the largest fractional remainders (lowest index wins
    ties) among cells still below their upper bound. Never exceeds cap or upper.
    """
    cont = np.asarray(cont, dtype=float)
    lower = np.asarray(lower, dtype=np.int64)
    upper = np.asarray(upper, dtype=np.int64)
    cap = np.asarray(cap, dtype=np.int64)
    n, m = cont.shape
    out = np.zeros((n, m), dtype=np.int64)

    for j in range(m):
        base = np.floor(cont[:, j]).astype(np.int64)
        base = np.clip(base, lower[:, j], upper[:, j])
        rem = cont[:, j] - base
        sum_base = int(base.sum())
        target = min(int(cap[j]), java_math_round(float(cont[:, j].sum())))
        if target < sum_base:
            target = sum_base
        units = target - sum_base

        rem_work = rem.copy()
        while units > 0:
            pick = -1
            best = -np.inf
            for i in range(n):
                if base[i] < upper[i, j] and rem_work[i] > best:
                    best = rem_work[i]
                    pick = i
            if pick < 0:
                break
            base[pick] += 1
            rem_work[pick] = -np.inf
            units -= 1

        col = int(base.sum())
        if col > int(cap[j]):
            raise AssertionError(
                f"rounding exceeded capacity on resource {j}: {col} > {cap[j]}")
        out[:, j] = base
    return out
