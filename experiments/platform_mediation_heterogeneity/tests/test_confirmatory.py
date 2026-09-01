"""Confirmatory-protocol tests: frozen config, seed disjointness, live reduced run."""
import csv
import json
import os

from pilotlib import workload
from lib.seeds import derive_seed

import run_confirmatory

HERE = os.path.dirname(os.path.abspath(__file__))
PILOT = os.path.abspath(os.path.join(HERE, ".."))


def load_cfg():
    with open(os.path.join(PILOT, "config", "confirmatory_v1.json")) as f:
        return json.load(f)


def test_frozen_config_fields():
    cfg = load_cfg()
    assert cfg["n_seeds"] == 200
    assert cfg["floor_regime"] == "unit"
    assert cfg["n_bootstrap"] == 10000
    assert cfg["primary_policy"] == "joint_leontief"
    assert cfg["co_primary_cells"] == ["dirichlet_0.1__moderate", "dirichlet_0.1__high"]
    assert cfg["seed_label"] == "heterogeneity_confirmatory_v1"
    assert cfg["n_agents"] == 6 and cfg["tasks_per_agent"] == 8
    assert len(cfg["workload_regimes"]) == 7
    assert cfg["policies"] == ["equal", "drf", "decomposed_cobb_douglas", "joint_linear",
                               "joint_cobb_douglas", "joint_ces", "joint_leontief"]


def test_confirmatory_seeds_disjoint_from_canonical_and_exploratory():
    cfg = load_cfg()
    conf = set(run_confirmatory.confirmatory_seeds(cfg))
    assert len(conf) == 200
    canon = {derive_seed("%s__%s" % (c, k), "test", i)
             for c in ("homogeneous", "mixed_bundle") for k in ("moderate", "high") for i in range(100)}
    explor = set(workload.dev_seeds("heterogeneity_pilot", 30))
    assert not (conf & canon)
    assert not (conf & explor)


def test_live_reduced_confirmatory(solver_python, tmp_path, monkeypatch):
    """A reduced live confirmatory run records both completion metrics, passes the
    disjointness asserts, and produces complete rows with no violations."""
    cfg = load_cfg()
    cfg = dict(cfg)
    cfg["n_seeds"] = 3
    cfg["workload_regimes"] = [r for r in cfg["workload_regimes"]
                               if r["name"] in ("iid_uniform", "dirichlet_0.1")]
    raw = tmp_path / "raw"
    raw.mkdir(parents=True)
    monkeypatch.setattr(run_confirmatory, "OUT", str(tmp_path))
    monkeypatch.setattr(run_confirmatory, "RAW", str(raw))
    monkeypatch.setattr(run_confirmatory, "LOGS", str(tmp_path))
    monkeypatch.setattr(run_confirmatory, "load_config", lambda: cfg)

    run_confirmatory.main(["--solver-python", solver_python])

    runs = list(csv.DictReader(open(raw / "runs.csv")))
    agents = list(csv.DictReader(open(raw / "agents.csv")))
    scen = list(csv.DictReader(open(raw / "scenarios.csv")))
    summary = json.load(open(os.path.join(str(tmp_path), "summary.json")))

    expected = 2 * 2 * 3 * 7  # regimes x contention x seeds x policies
    assert len(runs) == expected and summary["infeasible_runs"] == 0
    assert len(agents) == expected * 6
    assert len(scen) == 2 * 2 * 3
    assert summary["capacity_violations_total"] == 0
    assert summary["bound_violations_total"] == 0
    assert summary["fallback_used_total"] == 0
    assert all(v == 0 for v in summary["disjointness"].values())
    # both completion metrics present and in [0,1]; tasks/run == mean*48
    for r in runs:
        qo = float(r["queue_order_completion_mean"]); lo = float(r["locally_optimized_completion_mean"])
        assert 0.0 <= qo <= 1.0 and 0.0 <= lo <= 1.0
        assert lo >= qo - 1e-9   # optimal selection is never worse than queue order
        assert abs(float(r["queue_order_tasks_per_run"]) - qo * 48) < 1e-9
        assert abs(float(r["locally_optimized_tasks_per_run"]) - lo * 48) < 1e-9
    # same task workload across contention
    by = {}
    for s in scen:
        by.setdefault((s["regime"], s["seed"]), {})[s["contention"]] = s["task_workload_hash"]
    for d in by.values():
        assert len(set(d.values())) == 1
