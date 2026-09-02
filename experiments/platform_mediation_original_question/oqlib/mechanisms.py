"""Per-arm allocation computation for the Python-side mechanisms.

Each function takes a scenario dict (agents carrying ``leontief_req``, ``min``,
``upper``, ``priority`` and ``mandatory_demand``, plus ``capacities``) and returns the
integer allocation (list, per agent in agent order, of resource->int dicts) that is then
installed verbatim through the canonical runtime. Weights are the equal agent priority
weights; with equal weights the resource-local mechanisms differ only in what they do
with the declared complementarity coefficient a_ir.
"""
from lib.archetypes import RESOURCES
from lib.capacity_rounding import capacity_preserving_round

from . import maxmin, leontief_relaxation, distributed
from .jobs import scenario_arrays


def _weights(sc):
    return [float(a["priority"]) for a in sc["agents"]]


def _floors(sc):
    return [a["min"] for a in sc["agents"]]


def _uppers(sc):
    return [a["upper"] for a in sc["agents"]]


def independent_bundle_maxmin_alloc(sc):
    """Resource-local weighted bundle-progress progressive filling. Keeps a_ir."""
    requirement = [a["leontief_req"] for a in sc["agents"]]
    return maxmin.independent_bundle_maxmin(
        requirement, _weights(sc), _floors(sc), _uppers(sc), sc["capacities"], RESOURCES)


def separable_leontief_relaxation_alloc(sc):
    """Separable weighted-log Leontief relaxation. Drops a_ir magnitude (participation
    only), so under equal weights it equalizes resource quantities."""
    requirement = [a["leontief_req"] for a in sc["agents"]]
    return leontief_relaxation.independent_leontief_relaxation(
        requirement, _weights(sc), _floors(sc), _uppers(sc), sc["capacities"], RESOURCES)


def distributed_price_leontief_alloc(sc):
    """Distributed price-mediated Leontief solve, rounded and returned with convergence
    info. Returns (alloc, info)."""
    R, Q, mn, up, c = scenario_arrays(sc)
    return distributed.distributed_integer_allocation(
        R, Q, mn, up, c, _floors(sc), _uppers(sc), sc["capacities"], RESOURCES)


def distributed_price_leontief_full(sc):
    """One distributed solve returning both the installed integer allocation and the
    continuous solution: (int_alloc, A_continuous, objective, info). Avoids solving the
    price problem twice per scenario."""
    R, Q, mn, up, c = scenario_arrays(sc)
    _, A, obj, info = distributed.distributed_leontief(R, Q, mn, up, c)
    floor = _floors(sc)
    upper = _uppers(sc)
    lower = [[int(floor[i][r]) for r in RESOURCES] for i in range(len(floor))]
    upp = [[int(upper[i][r]) for r in RESOURCES] for i in range(len(upper))]
    cap = [int(sc["capacities"][r]) for r in RESOURCES]
    ints = capacity_preserving_round(A.tolist(), lower, upp, cap)
    alloc = [{r: ints[i][j] for j, r in enumerate(RESOURCES)} for i in range(len(floor))]
    return alloc, A, obj, info
