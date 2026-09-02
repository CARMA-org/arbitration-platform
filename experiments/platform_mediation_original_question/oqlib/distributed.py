"""Distributed price-mediated (tatonnement) solution of the same central Leontief
weighted-proportional-fairness objective, with no central solver call.

Central problem (identical to ``scripts/joint_solver.py`` for LEONTIEF agents):

    maximize   sum_i c_i * log(u_i)
    subject to u_i <= a_ij / r_ij         for every r_ij > 0
               mins_ij <= a_ij <= ideals_ij
               sum_i a_ij <= Q_j

Each resource owner keeps a single price ``lambda_j`` for its own resource. Each agent
solves a local subproblem using only its own Leontief coefficients ``r_i`` and the
prices it receives, and reports only its per-resource demand. Each owner then adjusts
its own price from the ratio of local demand to its own capacity. No component ever
collects the full set of declarations or the full queue, and no component calls the
central solver.

Agent subproblem (dual decomposition on the capacity constraints). Given prices
``lambda``, agent ``i`` maximizes over its utility level ``u``:

    g_i(u) = c_i log u - sum_{j: r_ij>0} lambda_j * max(mins_ij, u * r_ij),
    for 0 <= u <= ub_i = min_{j: r_ij>0} ideals_ij / r_ij,

because at cost-minimizing best response the installed quantity is
``a_ij = max(mins_ij, u r_ij)`` (a floored resource is held at its floor; a resource
above its floor is held in fixed proportion). ``g_i`` is concave and piecewise-smooth
with breakpoints at ``u = mins_ij / r_ij``; its unique maximizer is recovered in closed
form by walking the sorted breakpoints (the active resource set only grows with ``u``),
with no central solve. This is exact primal recovery of ``u_i`` and ``a_i``.

Owner update. The owner of resource ``j`` sees only its local demand
``d_j = sum_i a_ij`` and its own capacity ``Q_j`` and updates

    lambda_j <- clip( lambda_j * clip(d_j / Q_j, 1/RATIO_CLIP, RATIO_CLIP) ** ETA,
                      LAMBDA_FLOOR, LAMBDA_MAX ).

The ratio and the price are clipped so the multiplicative tatonnement cannot overflow.
Because raising any price lowers every agent's utility and therefore every demand, and
because at sufficiently high prices every agent is pushed onto its floors with total
floor demand ``sum_i mins_ij <= Q_j`` feasible, the market has a feasible price vector;
the iteration converges to it. A short feasibility-polish phase then raises the price of
any resource still marginally over capacity until the continuous allocation is feasible,
and finally each owner distributes leftover local capacity to agents below their upper
bound (which cannot change any agent's smallest ratio, so it leaves the objective
unchanged while using otherwise idle capacity).
"""
import time

import numpy as np

from lib.capacity_rounding import capacity_preserving_round
from .central import leontief_objective

# Frozen algorithm parameters (set before confirmatory execution; see the distributed
# solver derivation in the preregistration).
ETA = 0.5              # price-update exponent
ITERS = 8000           # iteration cap for the main tatonnement
STOP_TOL = 1e-12       # early stop when max price relative change is below this
LAMBDA_INIT = 1.0      # initial price on every resource
LAMBDA_FLOOR = 1e-12   # lowest price
LAMBDA_MAX = 1e12      # highest price (bounds the multiplicative update against overflow)
SCALE_BISECT = 100     # bisection steps for the global feasibility repair
REPAIR_TOL = 1e-9      # feasibility-repair target slack (keeps the residual << FEAS_TOL)
FEAS_TOL = 1e-7        # continuous feasibility tolerance
_TINY = 1e-15


def _agent_best_response(R, mn, up, c, lam):
    """Exact per-agent primal recovery. Returns (u, A) where u[i] is the agent's utility
    level and A[i, j] = max(mn[i, j], u[i] * R[i, j]) for required resources (mn[i, j]
    for resources it does not require).

    The agent objective g(u) = c log u - sum_j lambda_j max(mn_ij, u r_ij) is concave
    with kinks at the breakpoints u = mn_ij / r_ij (where a resource leaves its floor).
    The maximizer is therefore one of: an interior stationary point c / sum_active(lam
    r) that lands inside its own active-set interval, a breakpoint kink, or the upper
    bound. All such candidates are evaluated and the best feasible one is returned."""
    n, m = R.shape
    u = np.zeros(n)
    A = np.array(mn, dtype=float)          # unrequired resources sit at their floor
    # Vectorized fast path: when no floor binds and the upper bound is slack, the
    # concave maximizer is simply u = c / (r . lambda). This covers the great majority
    # of scenarios; the per-agent candidate search below handles the rest exactly.
    pos_mask = R > 0.0
    du = R @ lam
    with np.errstate(divide="ignore", invalid="ignore"):
        u_simple = np.where(du > _TINY, c / np.maximum(du, _TINY), np.inf)
        ub_mat = np.where(pos_mask, up / np.maximum(R, _TINY), np.inf)
    ub_vec = ub_mat.min(axis=1)
    for i in range(n):
        pos = [j for j in range(m) if R[i, j] > 0.0]
        if not pos:
            continue
        ub = ub_vec[i]
        us = u_simple[i]
        if np.isfinite(us) and us <= ub + _TINY and all(us * R[i, j] >= mn[i, j] - 1e-12 for j in pos):
            u_i = min(us, ub)
            u[i] = u_i
            for j in pos:
                A[i, j] = min(up[i, j], max(mn[i, j], u_i * R[i, j]))
            continue
        bps = sorted(pos, key=lambda j: (mn[i, j] / R[i, j]))

        def cost(uu):
            return sum(lam[j] * max(mn[i, j], uu * R[i, j]) for j in pos)

        def g(uu):
            return c[i] * np.log(uu) - cost(uu)

        # Candidate maximizers: breakpoints, the upper bound, and each interior
        # stationary point c / D_k (k smallest-breakpoint resources active).
        cands = [ub]
        for j in pos:
            b = mn[i, j] / R[i, j]
            if 0.0 < b <= ub:
                cands.append(b)
        denom = 0.0
        for k in range(len(bps) + 1):
            if k >= 1:
                denom += lam[bps[k - 1]] * R[i, bps[k - 1]]
            if denom > _TINY:
                cand = c[i] / denom
                if 0.0 < cand <= ub:
                    cands.append(cand)
        u_i = max(cands, key=g)
        u_i = min(max(u_i, 0.0), ub)
        u[i] = u_i
        for j in pos:
            A[i, j] = min(up[i, j], max(mn[i, j], u_i * R[i, j]))
    return u, A


def _demand(A, m):
    return np.array([A[:, j].sum() for j in range(m)])


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
    R = np.asarray(R, float)
    Q = np.asarray(Q, float)
    mn = np.asarray(mn, float)
    up = np.asarray(up, float)
    c = np.asarray(c, float)
    n, m = R.shape
    lam = np.full(m, LAMBDA_INIT)
    messages = 0
    iters_used = ITERS
    for it in range(ITERS):
        u, A = _agent_best_response(R, mn, up, c, lam)   # owners broadcast m prices
        dem = _demand(A, m)                               # agents report n demands
        messages += n + m
        # Owner update: each owner adjusts only its own price from its own excess
        # demand. Demand is bounded by the agents' upper bounds and the price is bounded
        # by LAMBDA_MAX, so the multiplicative update cannot overflow.
        with np.errstate(over="ignore"):
            new_lam = np.clip(lam * np.power(np.where(Q > 0, dem / np.maximum(Q, _TINY), 1.0), ETA),
                              LAMBDA_FLOOR, LAMBDA_MAX)
        rel = np.max(np.abs(new_lam - lam) / np.maximum(lam, _TINY))
        lam = new_lam
        if rel < STOP_TOL:
            iters_used = it + 1
            break

    u, A = _agent_best_response(R, mn, up, c, lam)
    dem = _demand(A, m)
    # Feasibility repair: the fixed-proportion utility levels are scaled by a single
    # global factor s in [0, 1] (the largest that makes the continuous allocation
    # feasible). Because s = 0 leaves every resource at its floors with total floor
    # demand sum_i mins_ij <= Q_j feasible and s = 1 is the unrepaired allocation, a
    # unique largest feasible s exists and is found by bisection. A converged
    # tatonnement leaves only a negligible overshoot, so s is at or very near 1.
    def demand_at(s):
        return np.array([sum(min(up[i, j], max(mn[i, j], s * u[i] * R[i, j])) if R[i, j] > 0
                             else mn[i, j] for i in range(n)) for j in range(m)])
    if np.any(dem > Q + REPAIR_TOL):
        s_lo, s_hi = 0.0, 1.0
        for _ in range(SCALE_BISECT):
            s_mid = 0.5 * (s_lo + s_hi)
            if np.all(demand_at(s_mid) <= Q + REPAIR_TOL):
                s_lo = s_mid
            else:
                s_hi = s_mid
        u = u * s_lo
        A = np.array(mn, dtype=float)                     # recompute A at the scaled u
        for i in range(n):
            for j in range(m):
                if R[i, j] > 0:
                    A[i, j] = min(up[i, j], max(mn[i, j], u[i] * R[i, j]))
    A = _slack_fill(A, R, Q, up)

    cap_residual = float(max(0.0, max(A[:, j].sum() - Q[j] for j in range(m))))
    bound_residual = 0.0
    for i in range(n):
        for j in range(m):
            bound_residual = max(bound_residual,
                                 max(0.0, mn[i, j] - A[i, j]), max(0.0, A[i, j] - up[i, j]))
    # Complementary-slackness (dual) residual: a priced resource should be full.
    dual_residual = 0.0
    for j in range(m):
        if Q[j] > 0:
            dual_residual = max(dual_residual, float(lam[j] * max(0.0, Q[j] - A[:, j].sum()) / Q[j]))
    obj = leontief_objective(A, R, c)
    info = {
        "iterations": int(iters_used),
        "converged": bool(cap_residual <= FEAS_TOL and bound_residual <= FEAS_TOL),
        "capacity_residual": cap_residual,
        "bound_residual": float(bound_residual),
        "primal_residual": cap_residual,
        "dual_residual": float(dual_residual),
        "objective": obj,
        "message_count": int(messages),
        "runtime_ms": int((time.time() - t0) * 1000),
        "prices": [float(x) for x in lam],
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
