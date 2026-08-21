import importlib.util
import json
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


def test_same_seed_reproduces_identical_jobs():
    a = scenario.base_scenario("mixed_bundle", "moderate", 1.3, 101, CFG)
    b = scenario.base_scenario("mixed_bundle", "moderate", 1.3, 101, CFG)
    ja = scenario.make_job(a, "mixed_bundle__moderate", 101, "joint_leontief", "python3", True)
    jb = scenario.make_job(b, "mixed_bundle__moderate", 101, "joint_leontief", "python3", True)
    assert json.dumps(ja, sort_keys=True) == json.dumps(jb, sort_keys=True)
    assert a["scenario_hash"] == b["scenario_hash"]


def test_all_policies_share_scenario_hash():
    sc = scenario.base_scenario("mixed_bundle", "high", 1.9, 7, CFG)
    hashes = {scenario.make_job(sc, "mixed_bundle__high", 7, p, "python3", True)["scenarioHash"]
              for p in ["equal", "drf", "decomposed_cobb_douglas", "joint_linear",
                        "joint_cobb_douglas", "joint_ces", "joint_leontief"]}
    assert hashes == {sc["scenario_hash"]}


def test_homogeneous_agents_identical_within_scenario():
    sc = scenario.base_scenario("homogeneous", "moderate", 1.3, 55, CFG)
    ref = sc["agents"][0]
    for a in sc["agents"][1:]:
        for field in ("task_types", "mandatory_demand", "optional_demand", "min", "upper",
                      "util_weights", "leontief_req", "priority"):
            assert a[field] == ref[field]
        assert [t["mandatory"] for t in a["tasks"]] == [t["mandatory"] for t in ref["tasks"]]


def test_mixed_agents_are_heterogeneous():
    sc = scenario.base_scenario("mixed_bundle", "moderate", 1.3, 3, CFG)
    demands = [tuple(a["mandatory_demand"][r] for r in RESOURCES) for a in sc["agents"]]
    assert len(set(demands)) > 1


def test_different_seeds_give_different_hashes_in_both_compositions():
    for comp in ("homogeneous", "mixed_bundle"):
        hs = {scenario.base_scenario(comp, "moderate", 1.3, s, CFG)["scenario_hash"]
              for s in range(30)}
        assert len(hs) > 1, comp


def test_task_type_and_order_affect_scenario_hash():
    sc = scenario.base_scenario("mixed_bundle", "moderate", 1.3, 9, CFG)
    base = sc["scenario_hash"]
    swapped = json.loads(json.dumps(sc))
    swapped["agents"][0]["tasks"][0], swapped["agents"][0]["tasks"][1] = \
        swapped["agents"][0]["tasks"][1], swapped["agents"][0]["tasks"][0]
    payload = {
        "composition": swapped["composition"], "contention": swapped["contention"],
        "capacities": swapped["capacities"],
        "agents": [{
            "id": a["id"], "task_types": a["task_types"],
            "mandatory_seq": [t["mandatory"] for t in a["tasks"]],
            "optional_seq": [t["optional"] for t in a["tasks"]],
            "quality": [t["quality"] for t in a["tasks"]],
            "refinement": [t["refinement"] for t in a["tasks"]],
            "slo": [t["sloMs"] for t in a["tasks"]],
            "mandatory_demand": a["mandatory_demand"], "optional_demand": a["optional_demand"],
            "min": a["min"], "upper": a["upper"], "util_weights": a["util_weights"],
            "leontief_req": a["leontief_req"], "priority": a["priority"],
        } for a in swapped["agents"]],
    }
    reordered = scenario._scenario_hash(payload)
    assert reordered != base or [t["type"] for t in sc["agents"][0]["tasks"]][0] == \
        [t["type"] for t in sc["agents"][0]["tasks"]][1]


def test_declaration_primitive_equals_normalized_mandatory_demand():
    sc = scenario.base_scenario("mixed_bundle", "high", 1.9, 41, CFG)
    for a in sc["agents"]:
        md = a["mandatory_demand"]
        total = sum(md[r] for r in RESOURCES)
        expected = {r: (md[r] / total if total > 0 else 0.0) for r in RESOURCES}
        assert a["util_weights"] == pytest.approx(expected)
        assert a["leontief_req"] == pytest.approx(expected)
        for r in RESOURCES:
            if md[r] == 0:
                assert a["util_weights"][r] == 0.0
                assert a["upper"][r] == 0


def test_mixed_bundle_rejects_degenerate_draws():
    sc = scenario.base_scenario("mixed_bundle", "moderate", 1.3, 12345, CFG)
    demands = [tuple(a["mandatory_demand"][r] for r in RESOURCES) for a in sc["agents"]]
    aggregate = {r: sum(a["mandatory_demand"][r] for a in sc["agents"]) for r in RESOURCES}
    assert len(set(demands)) > 1
    assert all(aggregate[r] > 0 for r in RESOURCES)
