"""Comparator semantics: independent bundle max-min, separable Leontief relaxation, and
distinctness from DRF."""
from oqlib import maxmin, leontief_relaxation

R2 = ["COMPUTE", "MEMORY"]


def _drf_replica(demand, cap, res):
    """A reference DRF (dominant-share water-filling) for testing distinctness only."""
    n, m = len(demand), len(res)
    dd = [max((demand[i][r] / cap[r]) for r in res if cap[r] > 0 and demand[i][r] > 0) for i in range(n)]

    def colsum(t):
        cs = {r: 0.0 for r in res}
        for i in range(n):
            s = t / dd[i]
            for r in res:
                cs[r] += s * demand[i][r]
        return cs
    lo, hi = 0.0, 1e9
    for _ in range(200):
        mid = 0.5 * (lo + hi)
        cs = colsum(mid)
        if all(cs[r] <= cap[r] for r in res):
            lo = mid
        else:
            hi = mid
    return [{r: (lo / dd[i]) * demand[i][r] for r in res} for i in range(n)]


def test_maxmin_fills_proportional_to_weight_times_coefficient():
    # Two agents, one resource, equal weights, no floors/bounds active: the fill is
    # proportional to the declared coefficient a_ir.
    req = [{"COMPUTE": 0.75, "MEMORY": 0.25}, {"COMPUTE": 0.25, "MEMORY": 0.75}]
    fl = [{r: 0 for r in R2} for _ in range(2)]
    up = [{r: 1000 for r in R2} for _ in range(2)]
    cap = {"COMPUTE": 100, "MEMORY": 100}
    alloc = maxmin.independent_bundle_maxmin(req, [1.0, 1.0], fl, up, cap, R2)
    # COMPUTE fills 0.75:0.25 -> 75:25
    assert alloc[0]["COMPUTE"] == 75 and alloc[1]["COMPUTE"] == 25
    assert alloc[0]["MEMORY"] == 25 and alloc[1]["MEMORY"] == 75


def test_maxmin_respects_floor_and_upper_bounds():
    req = [{"COMPUTE": 0.9, "MEMORY": 0.1}, {"COMPUTE": 0.1, "MEMORY": 0.9}]
    fl = [{"COMPUTE": 2, "MEMORY": 2}, {"COMPUTE": 2, "MEMORY": 2}]
    up = [{"COMPUTE": 5, "MEMORY": 1000}, {"COMPUTE": 1000, "MEMORY": 1000}]
    cap = {"COMPUTE": 20, "MEMORY": 20}
    alloc = maxmin.independent_bundle_maxmin(req, [1.0, 1.0], fl, up, cap, R2)
    for i in range(2):
        for r in R2:
            assert fl[i][r] <= alloc[i][r] <= up[i][r]
    assert alloc[0]["COMPUTE"] <= 5  # upper bound active


def test_maxmin_distinct_from_drf_on_constructed_case():
    demand = [{"COMPUTE": 9, "MEMORY": 9}, {"COMPUTE": 9, "MEMORY": 1}]
    req = [{r: demand[i][r] / sum(demand[i].values()) for r in R2} for i in range(2)]
    cap = {"COMPUTE": 9, "MEMORY": 6}
    fl = [{r: 0 for r in R2} for _ in range(2)]
    up = [{r: 9 for r in R2} for _ in range(2)]
    mm = maxmin.independent_bundle_maxmin(req, [1.0, 1.0], fl, up, cap, R2)
    drf = _drf_replica(demand, cap, R2)
    # they must differ somewhere (integer maxmin vs continuous drf rounded)
    drf_int = [{r: round(drf[i][r]) for r in R2} for i in range(2)]
    assert mm != drf_int


def test_separable_relaxation_collapses_to_equal_quotas_under_equal_weights():
    req = [{"COMPUTE": 0.7, "MEMORY": 0.3}, {"COMPUTE": 0.3, "MEMORY": 0.7}]
    cap = {"COMPUTE": 10, "MEMORY": 10}
    fl = [{r: 0 for r in R2} for _ in range(2)]
    up = [{r: 100 for r in R2} for _ in range(2)]
    alloc = leontief_relaxation.independent_leontief_relaxation(req, [1.0, 1.0], fl, up, cap, R2)
    assert alloc == [{"COMPUTE": 5, "MEMORY": 5}, {"COMPUTE": 5, "MEMORY": 5}]


def test_separable_relaxation_broken_by_unequal_weights():
    req = [{"COMPUTE": 0.7, "MEMORY": 0.3}, {"COMPUTE": 0.3, "MEMORY": 0.7}]
    cap = {"COMPUTE": 10, "MEMORY": 10}
    fl = [{r: 0 for r in R2} for _ in range(2)]
    up = [{r: 100 for r in R2} for _ in range(2)]
    alloc = leontief_relaxation.independent_leontief_relaxation(req, [3.0, 1.0], fl, up, cap, R2)
    assert alloc != [{"COMPUTE": 5, "MEMORY": 5}, {"COMPUTE": 5, "MEMORY": 5}]
    assert alloc[0]["COMPUTE"] > alloc[1]["COMPUTE"]  # weight-proportional


def test_separable_relaxation_broken_by_active_upper_bound():
    req = [{"COMPUTE": 0.7, "MEMORY": 0.3}, {"COMPUTE": 0.3, "MEMORY": 0.7}]
    cap = {"COMPUTE": 10, "MEMORY": 10}
    fl = [{r: 0 for r in R2} for _ in range(2)]
    up = [{"COMPUTE": 2, "MEMORY": 100}, {"COMPUTE": 100, "MEMORY": 100}]
    alloc = leontief_relaxation.independent_leontief_relaxation(req, [1.0, 1.0], fl, up, cap, R2)
    assert alloc[0]["COMPUTE"] == 2 and alloc[1]["COMPUTE"] == 8  # bound active, remainder redistributed
