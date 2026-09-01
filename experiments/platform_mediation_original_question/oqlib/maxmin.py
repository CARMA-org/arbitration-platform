"""Independent resource max-min mechanism.

Each resource is allocated on its own by an owner that sees only each agent's demand,
weight, floor, and upper bound for that resource. It never sees the other resource
allocations, the full cross-resource bundle, the task identities, or any cross-resource
utility. The owner runs weighted progressive filling of normalized demand satisfaction.

For a resource with capacity Q, agent demands d_i, weights w_i, floors f_i, and per-agent
usable caps c_i = min(upper_i, d_i), the continuous allocation is

  a_i(theta) = min(c_i, max(f_i, theta * w_i * d_i)).

The satisfaction ratio a_i / d_i grows in proportion to w_i as the common level theta
rises. An agent that reaches its usable cap stops growing and the remaining capacity
continues to the others, which is progressive filling continued lexicographically after
an agent reaches its demand or upper bound. The level theta is found by bisection so the
column sum equals the capacity, unless every agent reaches its usable cap first, in which
case any surplus capacity is left unused. Ties are resolved by this deterministic shared
level and, at equal level, by agent index. There are no cross-resource prices or messages.
"""
import numpy as np

from lib.capacity_rounding import capacity_preserving_round


def _fill_resource(d, w, fl, cap, Q):
    # cap is the per-agent usable ceiling min(upper, demand) already raised to the floor.
    n = len(d)
    a_lo = [float(fl[i]) for i in range(n)]
    if sum(a_lo) >= Q:
        return a_lo
    usable = [float(cap[i]) for i in range(n)]

    def total(theta):
        s = 0.0
        for i in range(n):
            s += min(usable[i], max(fl[i], theta * w[i] * d[i]))
        return s

    if total(1e18) <= Q + 1e-12:
        return [min(usable[i], max(fl[i], 1e18 * w[i] * d[i])) for i in range(n)]
    lo, hi = 0.0, 1.0
    while total(hi) < Q:
        hi *= 2.0
        if hi > 1e18:
            break
    for _ in range(200):
        mid = 0.5 * (lo + hi)
        if total(mid) > Q:
            hi = mid
        else:
            lo = mid
    theta = 0.5 * (lo + hi)
    return [min(usable[i], max(fl[i], theta * w[i] * d[i])) for i in range(n)]


def independent_resource_maxmin(demand, weight, floor, upper, capacity, resources):
    """Return the integer allocation (list of resource->int per agent) from the
    resource-local max-min mechanism, rounded with the canonical capacity-preserving
    rounding subject to floors, upper bounds, and capacity."""
    n = len(demand)
    m = len(resources)
    cont = [[0.0] * m for _ in range(n)]
    for j, r in enumerate(resources):
        d = [float(demand[i][r]) for i in range(n)]
        w = [float(weight[i]) for i in range(n)]
        fl = [float(floor[i][r]) for i in range(n)]
        cap_i = [min(float(upper[i][r]), float(demand[i][r])) for i in range(n)]
        cap_i = [max(cap_i[i], fl[i]) for i in range(n)]
        col = _fill_resource(d, w, fl, cap_i, float(capacity[r]))
        for i in range(n):
            cont[i][j] = col[i]
    lower = [[int(floor[i][r]) for r in resources] for i in range(n)]
    upp = [[int(upper[i][r]) for r in resources] for i in range(n)]
    cap = [int(capacity[r]) for r in resources]
    ints = capacity_preserving_round(cont, lower, upp, cap)
    return [{r: ints[i][j] for j, r in enumerate(resources)} for i in range(n)]
