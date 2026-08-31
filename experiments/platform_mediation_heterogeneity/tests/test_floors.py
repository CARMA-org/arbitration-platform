"""Floor-regime tests: largest-remainder apportionment, budgets, feasibility."""
import math

import pytest

from pilotlib import floors

RES = ["COMPUTE", "MEMORY", "API_CREDITS", "DATASET"]


def test_largest_remainder_basic_split():
    assert floors._largest_remainder(10, [1, 1, 1, 1], [100, 100, 100, 100]) == [3, 3, 2, 2]


def test_largest_remainder_weight_tiebreak():
    assert floors._largest_remainder(10, [3, 1, 0, 0], [100, 100, 100, 100]) == [8, 2, 0, 0]


def test_largest_remainder_zero_weight_gets_zero():
    out = floors._largest_remainder(10, [5, 0, 5, 0], [100, 100, 100, 100])
    assert out[1] == 0 and out[3] == 0
    assert sum(out) == 10


def test_largest_remainder_respects_upper_caps():
    out = floors._largest_remainder(10, [1, 1], [3, 100])
    assert out[0] <= 3 and sum(out) == 10


def test_largest_remainder_never_exceeds_budget_or_capacity():
    out = floors._largest_remainder(10, [1, 1], [2, 3])
    assert sum(out) == 5  # min(budget=10, sum uppers=5)
    assert all(o <= u for o, u in zip(out, [2, 3]))


def _hand_scenario():
    md = [
        {"COMPUTE": 10, "MEMORY": 10, "API_CREDITS": 10, "DATASET": 0},
        {"COMPUTE": 10, "MEMORY": 0, "API_CREDITS": 10, "DATASET": 10},
    ]
    upper = [
        {"COMPUTE": 20, "MEMORY": 20, "API_CREDITS": 20, "DATASET": 0},
        {"COMPUTE": 20, "MEMORY": 0, "API_CREDITS": 20, "DATASET": 20},
    ]
    caps = {"COMPUTE": 20, "MEMORY": 20, "API_CREDITS": 20, "DATASET": 20}
    return md, upper, caps


def test_zero_regime():
    md, upper, caps = _hand_scenario()
    floors_out, frac = floors.compute_floors("zero", RES, md, upper, caps)
    assert all(v == 0 for a in floors_out for v in a.values())
    assert all(v == 0.0 for v in frac.values())


def test_unit_regime():
    md, upper, caps = _hand_scenario()
    floors_out, frac = floors.compute_floors("unit", RES, md, upper, caps)
    assert floors_out[0] == {"COMPUTE": 1, "MEMORY": 1, "API_CREDITS": 1, "DATASET": 0}
    assert floors_out[1] == {"COMPUTE": 1, "MEMORY": 0, "API_CREDITS": 1, "DATASET": 1}


@pytest.mark.parametrize("f", [0.10, 0.25, 0.50, 0.75])
def test_proportional_no_floor_on_zero_demand_cell(f):
    md, upper, caps = _hand_scenario()
    floors_out, frac = floors.compute_floors("proportional_%.2f" % f, RES, md, upper, caps)
    # agent0 has zero DATASET demand; agent1 has zero MEMORY demand
    assert floors_out[0]["DATASET"] == 0
    assert floors_out[1]["MEMORY"] == 0


@pytest.mark.parametrize("f", [0.10, 0.25, 0.50, 0.75])
def test_proportional_budget_and_feasibility(f):
    md, upper, caps = _hand_scenario()
    floors_out, frac = floors.compute_floors("proportional_%.2f" % f, RES, md, upper, caps)
    for r in RES:
        budget = int(math.floor(f * caps[r]))
        total = sum(a[r] for a in floors_out)
        assert total <= budget            # never exceeds the floor budget
        assert total <= caps[r]           # hence never exceeds capacity
        for i in range(len(md)):
            assert floors_out[i][r] <= upper[i][r]   # respects upper bound
            assert floors_out[i][r] >= 0
        assert abs(frac[r] - (total / caps[r])) < 1e-12


def test_proportional_apportions_by_demand():
    md = [
        {"COMPUTE": 30, "MEMORY": 0, "API_CREDITS": 0, "DATASET": 0},
        {"COMPUTE": 10, "MEMORY": 0, "API_CREDITS": 0, "DATASET": 0},
    ]
    upper = [
        {"COMPUTE": 100, "MEMORY": 0, "API_CREDITS": 0, "DATASET": 0},
        {"COMPUTE": 100, "MEMORY": 0, "API_CREDITS": 0, "DATASET": 0},
    ]
    caps = {"COMPUTE": 40, "MEMORY": 1, "API_CREDITS": 1, "DATASET": 1}
    floors_out, _ = floors.compute_floors("proportional_0.50", RES, md, upper, caps)
    # budget = floor(0.5*40)=20, weights 30:10 -> 15:5
    assert floors_out[0]["COMPUTE"] == 15
    assert floors_out[1]["COMPUTE"] == 5


def test_unknown_regime_raises():
    with pytest.raises(ValueError):
        floors.parse_regime("bogus")
