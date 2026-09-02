"""Independent resource bundle max-min mechanism (``independent_bundle_maxmin``).

Each resource is allocated on its own by an owner that sees only, for that one
resource, each agent's declared fixed-proportion Leontief coefficient ``a_ir``, its
priority weight ``w_i``, its floor ``floor_ir``, and its upper bound ``upper_ir``. The
owner never sees any other resource's allocation, residual, price, the cross-resource
bundle, the task identities, or any cross-resource utility. There is no cross-resource
reconciliation after the local allocations are chosen.

The owner performs deterministic weighted progressive filling of *local bundle
progress*. Local bundle progress of agent ``i`` on resource ``r`` is ``x_ir / a_ir``,
the number of declared bundles the installed quantity of that one resource supports.
Weighted progressive filling raises a common level ``theta`` and sets

  x_ir(theta) = clip( theta * w_i * a_ir , floor_ir , upper_ir ).

An unsaturated agent's weighted progress ``x_ir / (w_i a_ir) = theta`` rises with the
shared level; an agent that reaches its upper bound stops and the remaining capacity
continues to the others (progressive filling continued lexicographically). ``theta`` is
found by bisection so the column sums to the resource capacity, unless every agent
reaches its usable ceiling first, in which case any surplus capacity is left unused, or
the floors already meet or exceed the capacity, in which case each agent receives only
its floor. Ties are resolved by the shared level and, at equal level, by agent index.

Because the fill is proportional to ``w_i a_ir``, the *magnitude* of the declared
complementarity coefficient enters each resource's allocation: an agent that declares it
needs twice as much of a resource per bundle is filled toward twice the quantity at the
same level. This is what preserves the fixed-proportion declaration at the mechanism
input while removing cross-resource coordination. It is distinct from the separable
weighted-log Leontief relaxation, whose per-resource objective cancels ``a_ir`` and
allocates in proportion to ``w_i`` alone (see ``leontief_relaxation``), and from DRF,
whose dominant-resource coupling ties the resources together.
"""
import numpy as np

from lib.capacity_rounding import capacity_preserving_round


def _fill_resource(coeff, w, fl, up, Q):
    """Weighted progressive fill of one resource.

    ``coeff[i]`` is the declared Leontief coefficient ``a_ir`` (0 if agent i does not
    require the resource); ``w[i]`` the weight; ``fl[i]``/``up[i]`` the floor/upper
    bound; ``Q`` the capacity. Returns the continuous per-agent quantities."""
    n = len(coeff)
    x = [float(fl[i]) for i in range(n)]
    part = [i for i in range(n) if coeff[i] > 0.0]
    if not part:
        return x
    if sum(fl[i] for i in part) >= Q:              # floors already fill the resource
        return x

    def total(theta):
        s = 0.0
        for i in range(n):
            s += min(up[i], max(fl[i], theta * w[i] * coeff[i])) if coeff[i] > 0 else fl[i]
        return s

    # If even an unbounded level cannot exceed capacity, every participant saturates at
    # its upper bound and surplus capacity is left unused.
    if total(1e18) <= Q + 1e-12:
        for i in part:
            x[i] = min(up[i], max(fl[i], 1e18 * w[i] * coeff[i]))
        return x
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
    for i in part:
        x[i] = min(up[i], max(fl[i], theta * w[i] * coeff[i]))
    return x


def independent_bundle_maxmin(requirement, weight, floor, upper, capacity, resources):
    """Return the integer allocation (list of resource->int per agent) from the
    resource-local weighted bundle-progress progressive-filling mechanism, rounded with
    the canonical capacity-preserving rounding subject to floors, upper bounds, and
    capacity. ``requirement[i]`` is the agent's declared fixed-proportion Leontief
    coefficient vector (resource->float), identical to the vector the joint Leontief
    mechanism receives."""
    n = len(requirement)
    m = len(resources)
    cont = [[0.0] * m for _ in range(n)]
    for j, r in enumerate(resources):
        coeff = [float(requirement[i][r]) for i in range(n)]
        w = [float(weight[i]) for i in range(n)]
        fl = [float(floor[i][r]) for i in range(n)]
        up = [max(float(upper[i][r]), fl[i]) for i in range(n)]
        col = _fill_resource(coeff, w, fl, up, float(capacity[r]))
        for i in range(n):
            cont[i][j] = col[i]
    lower = [[int(floor[i][r]) for r in resources] for i in range(n)]
    upp = [[int(upper[i][r]) for r in resources] for i in range(n)]
    cap = [int(capacity[r]) for r in resources]
    ints = capacity_preserving_round(cont, lower, upp, cap)
    return [{r: ints[i][j] for j, r in enumerate(resources)} for i in range(n)]


# Backwards-compatible alias for the earlier development entry-point name.
independent_resource_maxmin = independent_bundle_maxmin
