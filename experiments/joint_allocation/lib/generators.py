from dataclasses import dataclass
import numpy as np


@dataclass
class Instance:
    n: int
    m: int
    W: np.ndarray
    c: np.ndarray
    Q: np.ndarray
    mins: np.ndarray
    ideals: np.ndarray


def hill_breadth(w):
    w = np.asarray(w, dtype=float)
    total = w.sum()
    if total <= 0:
        return 1.0
    w = w / total
    nz = w[w > 0]
    return float(np.exp(-np.sum(nz * np.log(nz))))


def cosine_dissimilarity(W):
    n = W.shape[0]
    vals = []
    for i in range(n):
        for k in range(i + 1, n):
            a, b = W[i], W[k]
            denom = np.linalg.norm(a) * np.linalg.norm(b)
            cos = float(np.dot(a, b) / denom) if denom > 0 else 1.0
            vals.append(1.0 - cos)
    return float(np.mean(vals)) if vals else 0.0


def _temperature_for_breadth(r, target_B, tol=1e-4, iters=60):
    r = np.maximum(np.asarray(r, dtype=float), 1e-12)
    m = len(r)
    target_B = min(max(target_B, 1.0 + 1e-6), m - 1e-6)

    def breadth_at(t):
        w = np.power(r, t)
        return hill_breadth(w / w.sum())

    lo, hi = 1e-3, 1e3
    b_lo, b_hi = breadth_at(lo), breadth_at(hi)
    if target_B >= b_lo:
        return lo
    if target_B <= b_hi:
        return hi
    for _ in range(iters):
        mid = np.sqrt(lo * hi)
        b = breadth_at(mid)
        if abs(b - target_B) < tol:
            return mid
        if b > target_B:
            lo = mid
        else:
            hi = mid
    return np.sqrt(lo * hi)


def gen_dirichlet(rng, n, m, alpha, cap=100.0, lb=1.0, ub=100.0, priorities=None):
    W = rng.dirichlet(np.full(m, alpha), size=n)
    c = np.ones(n) if priorities is None else np.asarray(priorities, float)
    Q = np.full(m, float(cap))
    mins = np.full((n, m), float(lb))
    ideals = np.full((n, m), float(ub))
    return Instance(n, m, W, c, Q, mins, ideals)


def gen_breadth_controlled(rng, n, m, target_B, lam, cap=100.0, lb=1.0, ub=100.0,
                           priorities=None):
    r_shared = rng.uniform(0.2, 1.0, size=m)
    r_shared = r_shared / r_shared.sum()
    W = np.zeros((n, m))
    for i in range(n):
        r_idio = rng.uniform(0.2, 1.0, size=m)
        r_idio = r_idio / r_idio.sum()
        r = (1.0 - lam) * r_shared + lam * r_idio
        t = _temperature_for_breadth(r, target_B)
        w = np.power(np.maximum(r, 1e-12), t)
        W[i] = w / w.sum()
    c = np.ones(n) if priorities is None else np.asarray(priorities, float)
    Q = np.full(m, float(cap))
    mins = np.full((n, m), float(lb))
    ideals = np.full((n, m), float(ub))
    inst = Instance(n, m, W, c, Q, mins, ideals)
    achieved_B = float(np.mean([hill_breadth(W[i]) for i in range(n)]))
    return inst, achieved_B, cosine_dissimilarity(W)
