import importlib.util
import os
import sys
import types

import pytest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PM = os.path.join(ROOT, "experiments", "platform_mediation")


def _load_scenario():
    if "pmlib" not in sys.modules:
        pkg = types.ModuleType("pmlib")
        pkg.__path__ = [os.path.join(PM, "lib")]
        sys.modules["pmlib"] = pkg
    for mod in ("seeds", "archetypes", "scenario"):
        name = "pmlib." + mod
        if name not in sys.modules:
            spec = importlib.util.spec_from_file_location(name, os.path.join(PM, "lib", mod + ".py"))
            m = importlib.util.module_from_spec(spec)
            sys.modules[name] = m
            spec.loader.exec_module(m)
    return sys.modules["pmlib.scenario"]


scenario = _load_scenario()
CFG = {"n_agents": 4, "tasks_per_agent": 3}
RESOURCES = ["COMPUTE", "MEMORY", "API_CREDITS", "DATASET"]


def test_homogeneous_condition_is_identical_across_agents():
    sc = scenario.base_scenario("homogeneous", "moderate", 1.3, 123, CFG)
    agents = sc["agents"]
    ref = agents[0]
    for a in agents[1:]:
        assert a["archetype"] == ref["archetype"]
        assert a["priority"] == ref["priority"]
        assert a["mandatory_demand"] == ref["mandatory_demand"]
        assert a["min"] == ref["min"]
        assert a["upper"] == ref["upper"]
        assert a["util_weights"] == ref["util_weights"]
        assert a["leontief_req"] == ref["leontief_req"]
        assert [t["mandatory"] for t in a["tasks"]] == [t["mandatory"] for t in ref["tasks"]]
        assert [t["optional"] for t in a["tasks"]] == [t["optional"] for t in ref["tasks"]]


def test_all_priorities_equal_in_homogeneous_condition():
    sc = scenario.base_scenario("homogeneous", "high", 1.9, 7, CFG)
    priorities = {a["priority"] for a in sc["agents"]}
    assert priorities == {1.0}


def test_declaration_primitive_is_normalized_mandatory_demand():
    sc = scenario.base_scenario("mixed_bundle", "moderate", 1.3, 55, CFG)
    for a in sc["agents"]:
        md = a["mandatory_demand"]
        total = sum(md[r] for r in RESOURCES)
        expected = {r: (md[r] / total if total > 0 else 0.0) for r in RESOURCES}
        assert a["util_weights"] == pytest.approx(expected)
        assert a["leontief_req"] == pytest.approx(expected)
        for r in RESOURCES:
            if md[r] == 0:
                assert a["util_weights"][r] == 0.0
                assert a["leontief_req"][r] == 0.0
                assert a["upper"][r] == 0


def test_scenario_hash_is_policy_independent_and_shared():
    sc = scenario.base_scenario("mixed_bundle", "high", 1.9, 99, CFG)
    j_equal = scenario.make_job(sc, "mixed_bundle__high", 99, "equal", 1.0, "python3", True)
    j_joint = scenario.make_job(sc, "mixed_bundle__high", 99, "joint_leontief", 1.0, "python3", True)
    assert j_equal["scenarioHash"] == j_joint["scenarioHash"] == sc["scenario_hash"]


def test_mixed_bundle_has_heterogeneous_mandatory_demand():
    sc = scenario.base_scenario("mixed_bundle", "moderate", 1.3, 3, CFG)
    demands = [tuple(a["mandatory_demand"][r] for r in RESOURCES) for a in sc["agents"]]
    assert len(set(demands)) > 1


def test_drf_demand_vector_present_for_every_agent():
    sc = scenario.base_scenario("mixed_bundle", "high", 1.9, 41, CFG)
    for a in sc["agents"]:
        assert "mandatory_demand" in a
        assert any(a["mandatory_demand"][r] > 0 for r in RESOURCES)
