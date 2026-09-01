"""Clean separable relaxation of the central Leontief objective.

The central continuous problem maximizes sum_i w_i log(u_i) subject to floors, upper
bounds, capacities, and u_i <= x_ir / a_ir for every applicable agent and resource,
where a_ir is the agent's Leontief requirement for resource r. A single utility level
u_i couples all of an agent's resources.

The separable relaxation introduces resource-local utility copies u_ir and drops the
cross-resource consensus u_ir = u_is. Each resource owner solves, using only its own
resource, its floors, its upper bounds, its capacity, and the agents' Leontief
coefficients for that resource,

  maximize  sum_i w_i log(u_ir)  subject to  sum_i x_ir <= Q_r,
            floor_ir <= x_ir <= upper_ir,  u_ir <= x_ir / a_ir.

At the optimum u_ir = x_ir / a_ir, so the owner maximizes sum_i w_i log(x_ir) over the
agents that actually require the resource (a_ir > 0). Agents that do not require the
resource receive only their floor. The requirement declaration is therefore used to
decide who participates for each resource, which keeps the complementarity information,
while the magnitude of a_ir cancels inside a single resource once the consensus is
dropped. Summed over resources and scaled by one over the number of resources, the
local objectives recover the same weighted-log objective on any consensus-feasible
allocation. This mechanism differs from the joint solver only by removing the
cross-resource utility consensus. It does not replace Leontief coefficients with linear
utility and it does not hide the complementarity declarations.

The per-resource problem is weighted proportional fairness over the participating
agents, solved by water filling: x_ir = clip(w_i / lambda, floor_ir, upper_ir) with
lambda chosen so the participating column sums to the capacity, or every participant is
at a bound. Ties are resolved by the shared level and then by agent index. There are no
cross-resource prices, messages, or residuals.
"""
import numpy as np

from lib.capacity_rounding import capacity_preserving_round


def _waterfill(w, fl, up, Q, participate):
    n = len(w)
    x = [float(fl[i]) for i in range(n)]
    part = [i for i in range(n) if participate[i]]
    if not part:
        return x
    if sum(fl[i] for i in part) >= Q:
        return x

    def total(lmb):
        s = 0.0
        for i in part:
            s += min(up[i], max(fl[i], w[i] / lmb))
        return s

    lo, hi = 1e-12, 1.0
    while total(hi) > Q:
        hi *= 2.0
        if hi > 1e18:
            break
    while total(lo) < Q and lo > 1e-18:
        lo *= 0.5
    for _ in range(200):
        mid = (lo * hi) ** 0.5
        if total(mid) > Q:
            lo = mid
        else:
            hi = mid
    lmb = (lo * hi) ** 0.5
    for i in part:
        x[i] = min(up[i], max(fl[i], w[i] / lmb))
    return x


def independent_leontief_relaxation(requirement, weight, floor, upper, capacity, resources):
    """Return the integer allocation (list of resource->int per agent) from the clean
    separable Leontief relaxation, rounded with the canonical capacity-preserving
    rounding subject to floors, upper bounds, and capacity."""
    n = len(requirement)
    m = len(resources)
    cont = [[0.0] * m for _ in range(n)]
    for j, r in enumerate(resources):
        w = [float(weight[i]) for i in range(n)]
        fl = [float(floor[i][r]) for i in range(n)]
        up = [float(upper[i][r]) for i in range(n)]
        participate = [requirement[i][r] > 0 for i in range(n)]
        col = _waterfill(w, fl, up, float(capacity[r]), participate)
        for i in range(n):
            cont[i][j] = col[i]
    lower = [[int(floor[i][r]) for r in resources] for i in range(n)]
    upp = [[int(upper[i][r]) for r in resources] for i in range(n)]
    cap = [int(capacity[r]) for r in resources]
    ints = capacity_preserving_round(cont, lower, upp, cap)
    return [{r: ints[i][j] for j, r in enumerate(resources)} for i in range(n)]
