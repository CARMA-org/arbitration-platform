"""Tests for the locally_optimized_completion diagnostic."""
import json

from pilotlib import local_opt
from pilotlib.local_opt import locally_optimized_completion as loc

AB = ["A", "B"]
A = ["A"]


def test_all_tasks_fit():
    r = loc({"A": 10}, [{"A": 1}, {"A": 1}, {"A": 1}], [0.5, 0.5, 0.5], A)
    assert r["count"] == 3
    assert r["completion"] == 1.0
    assert r["selected_indices"] == [0, 1, 2]


def test_no_tasks_fit():
    r = loc({"A": 0}, [{"A": 5}, {"A": 5}], [0.5, 0.5], A)
    assert r["count"] == 0
    assert r["completion"] == 0.0
    assert r["selected_indices"] == []


def test_count_dominates_quality():
    # A single high-quality task (count 1) must lose to two lower-quality tasks.
    r = loc({"A": 9}, [{"A": 4}, {"A": 4}, {"A": 4}], [0.9, 0.1, 0.1], A)
    assert r["count"] == 2                      # 2*4=8 <= 9; 3*4=12 > 9
    assert r["selected_indices"] == [0, 1]      # {0,1},{0,2} tie on quality -> lex


def test_equal_count_quality_tiebreak():
    r = loc({"A": 2}, [{"A": 1}, {"A": 1}, {"A": 1}], [0.1, 0.9, 0.9], A)
    assert r["count"] == 2
    assert r["selected_indices"] == [1, 2]      # highest summed base quality


def test_equal_quality_resource_tiebreak():
    # Both count 1 and quality 0.5; task1 consumes less, and it is the higher index,
    # so this distinguishes the resource-consumption rule from the lexicographic rule.
    r = loc({"A": 1, "B": 5}, [{"A": 1, "B": 5}, {"A": 1, "B": 0}], [0.5, 0.5], AB)
    assert r["count"] == 1
    assert r["selected_indices"] == [1]
    assert r["total_consumption"] == 1


def test_final_lexicographic_tiebreak():
    # Identical count, quality, and consumption -> smallest index tuple wins.
    r = loc({"A": 1}, [{"A": 1}, {"A": 1}], [0.5, 0.5], A)
    assert r["count"] == 1
    assert r["selected_indices"] == [0]


def test_selected_never_exceeds_allocation():
    fps = [{"A": 3, "B": 1}, {"A": 1, "B": 4}, {"A": 2, "B": 2}, {"A": 5, "B": 0}]
    alloc = {"A": 6, "B": 5}
    table = local_opt.build_subset_table(fps, [0.5] * 4, AB)
    cnt, idxs, total, qual = local_opt.select_from_table(table, alloc, AB)
    agg = {"A": 0, "B": 0}
    for i in idxs:
        for r in AB:
            agg[r] += fps[i].get(r, 0)
    assert agg["A"] <= alloc["A"] and agg["B"] <= alloc["B"]


def test_completion_is_count_over_n():
    r = loc({"A": 3}, [{"A": 1}] * 8, [0.5] * 8, A)
    assert r["count"] == 3 and abs(r["completion"] - 3 / 8) < 1e-12


def test_runtime_crosscheck(solver_python):
    """Executing the selected subset alone through a fresh runtime and ledger
    completes exactly the predicted mandatory tasks."""
    import os
    from lib import scenario as canon, runner
    from lib.archetypes import RESOURCES
    from pilotlib import workload, pilot_scenario

    # Take a real concentrated scenario and its equal + Leontief installed bundles.
    wl = workload.generate_workload(
        {"name": "dirichlet_0.1", "kind": "dirichlet", "concentration": 0.1},
        workload.dev_seeds("heterogeneity_pilot", 3)[0], 6, 8, "heterogeneity_pilot")
    sc = pilot_scenario.build_scenario(wl, "high", 1.9, "unit", "x__high")
    jobs = [canon.make_job(sc, "x__high", 0, p, solver_python, True) for p in ("equal", "joint_leontief")]
    res = runner.run_jobs(jobs)

    checked = 0
    for r in res:
        for i, a in enumerate(r["agents"]):
            alloc = a["allocated"]
            types = sc["agents"][i]["task_types"]
            fps = [canon.mandatory_footprint(t) for t in types]
            quals = [t["quality"] for t in sc["agents"][i]["tasks"]]
            sel = loc(alloc, fps, quals, RESOURCES)
            if sel["count"] == 0:
                continue
            # Build a single-agent job running ONLY the selected tasks, with the
            # installed bundle fixed as its bounds/capacities (equal installs it exactly).
            sel_types = [types[j] for j in sel["selected_indices"]]
            tasks = canon.build_tasks(0, sel_types)
            md = {rr: sum(canon.mandatory_footprint(t)[rr] for t in sel_types) for rr in RESOURCES}
            tot = sum(md.values())
            uw = {rr: (md[rr] / tot if tot else 0.0) for rr in RESOURCES}
            used = sorted({s for t in tasks for s in (t["mandatory"] + t["optional"])})
            agent = {"id": "a0", "archetype": "+".join(sorted(set(sel_types))), "prefs": uw,
                     "utilWeights": uw, "leontiefReq": uw, "mandatoryDemand": md,
                     "min": alloc, "upper": alloc, "priority": 1.0, "tasks": tasks}
            job = {"cell": "cc", "seed": 0, "policy": "equal", "solverPython": solver_python,
                   "execute": True, "fallbackAllowed": False, "scenarioHash": "s", "workloadHash": "w",
                   "capacities": alloc, "services": {s: 100000 for s in used}, "agents": [agent]}
            out = runner.run_jobs([job])[0]
            done = out["agents"][0]["completion"] * len(sel_types)
            assert abs(out["agents"][0]["completion"] - 1.0) < 1e-9, \
                "selected subset did not fully complete: %s" % json.dumps(out["agents"][0])
            assert round(done) == sel["count"]
            checked += 1
            if checked >= 6:
                return
    assert checked > 0
