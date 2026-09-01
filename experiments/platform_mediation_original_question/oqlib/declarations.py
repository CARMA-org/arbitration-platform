"""Declaration construction and fixed physical capacity for the drift experiment.

A declaration is an estimate of an agent's mandatory resource demand over the eight
task execution horizon. It is built with the same aggregation, normalization, and
zero handling as the confirmed exact-pending-queue implementation. Four declaration
sources are supported.

* stale     estimate from the fixed baseline 48 task calibration history (pre-drift)
* refreshed estimate from a fresh 48 task history drawn from the current drift mixture
* latent    expected eight task demand under the current latent mixture, no queue seen
* execution exact mandatory demand of the realized eight task execution queue (oracle)

Physical capacity is a function of baseline latent expected demand and the contention
ratio only. It never uses the realized execution queue and is frozen across every
drift level and declaration condition for a scenario seed and contention level. Floors
and upper bounds are policy independent and are derived from baseline expected demand,
the agent count, and the resource capacities, so no declaration condition can change
them and no execution information leaks through a bound.
"""
from lib.archetypes import RESOURCES

from . import driftgen as G

TPA = G.TPA
CAL_HISTORY = G.CAL_HISTORY
HORIZON_SCALE = TPA / float(CAL_HISTORY)   # scale a 48 task history to an 8 task estimate
FLOOR_EXPECTED_THRESHOLD = 1.0             # floor a resource when expected 8 task demand >= 1


def estimate_from_history(history_types):
    """Estimate eight task mandatory demand from a 48 task calibration history."""
    d48 = G.demand_from_types(history_types)
    return {r: d48[r] * HORIZON_SCALE for r in RESOURCES}


def estimate_from_latent(dist):
    """Expected eight task mandatory demand under an archetype distribution."""
    per = G.expected_per_task_demand(dist)
    return {r: per[r] * TPA for r in RESOURCES}


def estimate_from_execution(exec_types):
    """Exact eight task mandatory demand of the realized execution queue."""
    d = G.demand_from_types(exec_types)
    return {r: float(d[r]) for r in RESOURCES}


def declaration_vectors(demand):
    """Return (drf_demand_int, leontief_req_normalized, util_weights) from an estimated
    demand vector, matching the confirmed normalization and zero handling."""
    drf_demand = {r: int(round(demand[r])) for r in RESOURCES}
    total = sum(demand[r] for r in RESOURCES)
    if total > 0:
        weights = {r: demand[r] / total for r in RESOURCES}
    else:
        weights = {r: 0.0 for r in RESOURCES}
    return drf_demand, dict(weights), dict(weights)


def build_capacity(p, contention_ratio, n_agents):
    """Fixed physical capacity from baseline latent expected demand and contention."""
    total = {r: 0.0 for r in RESOURCES}
    for i in range(n_agents):
        est = estimate_from_latent(p[i])
        for r in RESOURCES:
            total[r] += est[r]
    return {r: max(1, int(round(total[r] / contention_ratio))) for r in RESOURCES}


def build_bounds(p, capacities, n_agents):
    """Policy independent floors and upper bounds.

    A resource is floored at one unit for an agent when the agent's baseline expected
    eight task demand for that resource is at least one unit. Upper bounds are the
    resource capacity, which is generous and identical for every declaration condition.
    """
    floors = []
    uppers = []
    for i in range(n_agents):
        est = estimate_from_latent(p[i])
        fl = {}
        up = {}
        for r in RESOURCES:
            fl[r] = 1 if est[r] >= FLOOR_EXPECTED_THRESHOLD else 0
            up[r] = int(capacities[r])
            if up[r] < fl[r]:
                up[r] = fl[r]
        floors.append(fl)
        uppers.append(up)
    return floors, uppers
