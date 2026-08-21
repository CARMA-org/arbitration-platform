import importlib.util
import os
import sys
import types

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PM = os.path.join(ROOT, "experiments", "platform_mediation")


def _load():
    if "pmlib" not in sys.modules:
        pkg = types.ModuleType("pmlib")
        pkg.__path__ = [os.path.join(PM, "lib")]
        sys.modules["pmlib"] = pkg
    name = "pmlib.capacity_rounding"
    if name not in sys.modules:
        spec = importlib.util.spec_from_file_location(name, os.path.join(PM, "lib", "capacity_rounding.py"))
        m = importlib.util.module_from_spec(spec)
        sys.modules[name] = m
        spec.loader.exec_module(m)
    return sys.modules[name]


cr = _load()
round_ = cr.capacity_preserving_round


def test_never_exceeds_capacity():
    cont = [[4.6, 3.4], [3.6, 4.4]]
    lower = [[0, 0], [0, 0]]
    upper = [[100, 100], [100, 100]]
    cap = [8, 7]
    out = round_(cont, lower, upper, cap)
    for j in range(2):
        assert sum(out[i][j] for i in range(2)) <= cap[j]


def test_respects_lower_and_upper_bounds():
    cont = [[0.2, 9.9], [0.2, 0.1]]
    lower = [[1, 0], [1, 0]]
    upper = [[3, 5], [3, 5]]
    cap = [6, 6]
    out = round_(cont, lower, upper, cap)
    for i in range(2):
        for j in range(2):
            assert lower[i][j] <= out[i][j] <= upper[i][j]


def test_deterministic():
    cont = [[2.5, 2.5, 2.5], [2.5, 2.5, 2.5], [2.5, 2.5, 2.5]]
    lower = [[0] * 3 for _ in range(3)]
    upper = [[10] * 3 for _ in range(3)]
    cap = [7, 7, 7]
    a = round_(cont, lower, upper, cap)
    b = round_(cont, lower, upper, cap)
    assert a == b


def test_ties_go_to_earlier_index_within_capacity():
    cont = [[1.5], [1.5], [1.5]]
    lower = [[0], [0], [0]]
    upper = [[10], [10], [10]]
    cap = [5]
    out = round_(cont, lower, upper, cap)
    assert sum(out[i][0] for i in range(3)) == 5
    assert out == [[2], [2], [1]]


def test_matches_floor_when_capacity_binds_low():
    cont = [[3.9], [3.9]]
    lower = [[0], [0]]
    upper = [[10], [10]]
    cap = [6]
    out = round_(cont, lower, upper, cap)
    assert sum(out[i][0] for i in range(2)) <= 6
    assert all(out[i][0] >= int(np.floor(3.9)) - 1 for i in range(2))
