"""Distributed solution of the same canonical Leontief weighted proportional fairness
objective by price-mediated tatonnement.

Each resource owner keeps a price for its own resource. Each agent solves a local
subproblem using only its own Leontief coefficients and the prices it receives, which
gives its utility level u_i. Each owner then raises or lowers its own price from the
ratio of local demand to its capacity. No component collects the full set of
declarations or the full queue, and no component calls the central solver.

The agent subproblem maximizes c_i log(u_i) - u_i sum_j lambda_j r_ij, whose solution is
u_i = c_i / (sum_j lambda_j r_ij) clamped to [lb_i, ub_i]. The owner of resource j
updates lambda_j by lambda_j <- lambda_j * (demand_j / Q_j) ** eta, using only the local
demand sum_i r_ij u_i and its own capacity Q_j. This multiplicative update is the natural
market tatonnement for a proportional fairness allocation and each step uses only
resource-local information. After the prices settle, each owner distributes any remaining
capacity of its own resource to agents that still have headroom below their upper bound,
weighted by their requirement for that resource. This slack distribution stays local to
one resource and does not change any agent's smallest ratio, so it leaves the Leontief
objective unchanged while using capacity that the fixed-proportion demands leave idle.
"""
import time

import numpy as np

from lib.capacity_rounding import capacity_preserving_round
from .central import bounds, leontief_objective

# frozen algorithm parameters (set before confirmatory execution)
ETA = 0.5
ITERS = 8000
LAMBDA_INIT = 1.0
LAMBDA_FLOOR = 1e-9
FEAS_TOL = 1e-7


def _agent_subproblem(R, lam, c, lb, ub):
    du = R @ lam
    u = np.where(du > 0, c / np.maximum(du, 1e-12), ub)
    return np.clip(u, lb, ub)


def _demand(R, u, m):
    return np.array([np.sum(R[:, j] * u) for j in range(m)])


def _slack_fill(A, R, Q, up):
    n, m = R.shape
    for j in range(m):
        for _ in range(n + 2):
            left = Q[j] - A[:, j].sum()
            if left <= 1e-9:
                break
            elig = [i for i in range(n) if R[i, j] > 0 and A[i, j] < up[i, j] - 1e-9]
            if not elig:
                break
            wsum = sum(R[i, j] for i in elig)
            progressed = False
            for i in elig:
                add = min(left * R[i, j] / wsum, up[i, j] - A[i, j])
                if add > 1e-12:
                    A[i, j] += add
                    progressed = True
            if not progressed:
                break
    return A


def distributed_leontief(R, Q, mn, up, c):
    """Return (u, A_continuous, objective, info) for the distributed solve."""
    t0 = time.time()
    n, m = R.shape
    lb, ub = bounds(R, mn, up)
    lam = np.full(m, LAMBDA_INIT)
    messages = 0
    for _ in range(ITERS):
        u = _agent_subproblem(R, lam, c, lb, ub)          # owners broadcast m prices
        dem = _demand(R, u, m)                             # agents report n demands
        messages += n + m
        lam = np.maximum(lam * np.power(np.maximum(dem, 1e-12) / Q, ETA), LAMBDA_FLOOR)

    u = _agent_subproblem(R, lam, c, lb, ub)
    dem = _demand(R, u, m)
    # feasibility safety: uniform down-scaling preserves the fixed proportions and makes
    # the continuous allocation strictly feasible if a tiny overshoot remains.
    over = [Q[j] / dem[j] for j in range(m) if dem[j] > Q[j]]
    s = min(1.0, min(over)) if over else 1.0
    u = u * s
    A = np.array([[u[i] * R[i, j] if R[i, j] > 0 else 0.0 for j in range(m)] for i in range(n)])
    A = _slack_fill(A, R, Q, up)

    cap_residual = float(max(0.0, max(A[:, j].sum() - Q[j] for j in range(m))))
    bound_residual = 0.0
    for i in range(n):
        for j in range(m):
            bound_residual = max(bound_residual, max(0.0, mn[i, j] - A[i, j]), max(0.0, A[i, j] - up[i, j]))
    obj = leontief_objective(A, R, c)
    info = {
        "iterations": ITERS,
        "converged": bool(cap_residual <= FEAS_TOL and bound_residual <= FEAS_TOL),
        "capacity_residual": cap_residual,
        "bound_residual": float(bound_residual),
        "objective": obj,
        "message_count": int(messages),
        "runtime_ms": int((time.time() - t0) * 1000),
    }
    return u, A, obj, info


def distributed_integer_allocation(R, Q, mn, up, c, floor, upper, capacity, resources):
    """Full distributed arm: solve, then round with the canonical capacity-preserving
    rounding. Returns (allocation dicts per agent, info)."""
    _, A, obj, info = distributed_leontief(R, Q, mn, up, c)
    lower = [[int(floor[i][r]) for r in resources] for i in range(len(floor))]
    upp = [[int(upper[i][r]) for r in resources] for i in range(len(upper))]
    cap = [int(capacity[r]) for r in resources]
    ints = capacity_preserving_round(A.tolist(), lower, upp, cap)
    alloc = [{r: ints[i][j] for j, r in enumerate(resources)} for i in range(len(floor))]
    return alloc, info
