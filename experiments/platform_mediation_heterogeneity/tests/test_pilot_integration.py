"""Integration tests through the canonical Java harness (skipped without solver).

These assert operational invariants on a small live sweep: no fallback, no
capacity/bound violations, capacity-preserving integer allocations, complete raw
row counts, all policy pairs present, correct tasks-per-run conversion, factorial
control across contention, and summary/headline reconstructibility from raw.
"""
import csv
import json
import os

RES = ["COMPUTE", "MEMORY", "API_CREDITS", "DATASET"]


def _load(raw, kind):
    with open(os.path.join(raw, "workload_%s.csv" % kind)) as f:
        return list(csv.DictReader(f))


def test_no_fallback_and_no_violations(live_workload_sweep):
    runs = _load(live_workload_sweep["raw"], "runs")
    assert runs
    assert all(str(r["fallback_used"]).lower() != "true" for r in runs)
    assert all(int(r["capacity_violation"]) == 0 for r in runs)
    assert all(int(r["bound_violation"]) == 0 for r in runs)
    assert all(r["feasible"] == "True" for r in runs)


def test_capacity_preserving_allocations(live_workload_sweep):
    agents = _load(live_workload_sweep["raw"], "agents")
    workloads = _load(live_workload_sweep["raw"], "workloads")
    caps = {(w["cell"], w["seed"]): json.loads(w["capacity_by_resource"]) for w in workloads}
    col = {}
    for a in agents:
        key = (a["cell"], a["seed"], a["policy"])
        alloc = json.loads(a["allocated"])
        agg = col.setdefault(key, {r: 0 for r in RES})
        for r in RES:
            agg[r] += alloc[r]
    for (cell, seed, policy), agg in col.items():
        cap = caps[(cell, seed)]
        for r in RES:
            assert agg[r] <= cap[r], "capacity exceeded %s %s %s %s" % (cell, seed, policy, r)


def test_floors_respected(live_workload_sweep):
    agents = _load(live_workload_sweep["raw"], "agents")
    for a in agents:
        alloc = json.loads(a["allocated"])
        lower = json.loads(a["min_bound"])
        upper = json.loads(a["upper_bound"])
        for r in RES:
            assert alloc[r] >= lower[r]
            assert alloc[r] <= upper[r]


def test_row_counts_complete(live_workload_sweep):
    s = live_workload_sweep["summary"]
    runs = _load(live_workload_sweep["raw"], "runs")
    agents = _load(live_workload_sweep["raw"], "agents")
    workloads = _load(live_workload_sweep["raw"], "workloads")
    assert s["feasible_runs"] == len(runs)
    assert s["n_agent_records"] == len(agents)
    assert len(agents) == len(runs) * s["n_agents"]
    assert len(workloads) == s["n_cells"] * s["n_seeds_per_cell"]
    assert s["feasible_runs"] + s["infeasible_runs"] == s["expected_runs"]


def test_all_policy_pairs_present(live_workload_sweep):
    runs = _load(live_workload_sweep["raw"], "runs")
    cfg = live_workload_sweep["cfg"]
    by_cs = {}
    for r in runs:
        by_cs.setdefault((r["cell"], r["seed"]), set()).add(r["policy"])
    for key, pols in by_cs.items():
        assert pols == set(cfg["policies"]), "missing policies for %s" % (key,)


def test_tasks_per_run_conversion(live_workload_sweep):
    runs = _load(live_workload_sweep["raw"], "runs")
    for r in runs:
        expect = float(r["completion_mean"]) * 48
        assert abs(float(r["completed_tasks_per_run"]) - expect) < 1e-9


def test_identical_workload_across_contention(live_workload_sweep):
    workloads = _load(live_workload_sweep["raw"], "workloads")
    by = {}
    for w in workloads:
        by.setdefault((w["regime"], w["seed"]), {})[w["contention"]] = w["task_workload_hash"]
    for key, d in by.items():
        assert len(set(d.values())) == 1, "workload hash differs across contention for %s" % (key,)


def test_summary_and_headline_reconstructible(live_workload_sweep, monkeypatch):
    import make_pilot_tables as mpt
    from pilotlib import pilot_analysis as pa

    raw = live_workload_sweep["raw"]
    monkeypatch.setattr(pa, "RAW", raw)
    cfg = live_workload_sweep["cfg"]
    rows, headline, seeds_by_cell = mpt.build_cell_policy("workload", cfg, cfg["policies"])

    # Independently reconstruct one cell/policy completion mean and a paired diff.
    runs = _load(raw, "runs")
    idx = {(r["cell"], r["seed"], r["policy"]): float(r["completion_mean"]) for r in runs}
    cell = sorted(headline)[0]
    seeds = seeds_by_cell[cell]
    for p in cfg["policies"]:
        recon = sum(idx[(cell, s, p)] for s in seeds) / len(seeds)
        assert abs(recon - headline[cell][p]["completion_mean"]) < 1e-12
        recon_diff = sum(idx[(cell, s, p)] - idx[(cell, s, "equal")] for s in seeds) / len(seeds)
        assert abs(recon_diff - headline[cell][p]["diff_vs_equal"]) < 1e-9
