"""Reduced end-to-end architecture and drift execution through the canonical runtime."""
import csv
import json
import os

import pytest

from conftest import solver_available

pytestmark = pytest.mark.skipif(not solver_available(),
                                reason="Java harness classpath or SOLVER_PYTHON unavailable")


def _no_violations(out):
    with open(os.path.join(out, "raw", "runs.csv")) as f:
        runs = list(csv.DictReader(f))
    assert runs, "no runs produced"
    assert all(int(r["capacity_violation"]) == 0 for r in runs)
    assert all(int(r["bound_violation"]) == 0 for r in runs)
    assert all(str(r["fallback_used"]).lower() != "true" for r in runs)
    with open(os.path.join(out, "raw", "infeasible.csv")) as f:
        infeas = list(csv.DictReader(f))
    assert len(infeas) == 0


def test_reduced_architecture_execution(tmp_path, monkeypatch):
    import run_architecture
    out = str(tmp_path / "arch")
    monkeypatch.setenv("OQ_DEV_OUT", out)
    run_architecture.main(["--dev-seeds", "1", "--block", "1",
                           "--solver-python", os.environ["SOLVER_PYTHON"]])
    _no_violations(out)
    with open(os.path.join(out, "raw", "distributed.csv")) as f:
        dist = list(csv.DictReader(f))
    assert dist and all(float(r["rel_obj_gap"]) <= 1e-3 for r in dist)
    summary = json.load(open(os.path.join(out, "summary.json")))
    assert summary["feasible_runs"] == summary["expected_runs"]


def test_reduced_drift_execution(tmp_path, monkeypatch):
    import run_declaration_drift
    out = str(tmp_path / "drift")
    monkeypatch.setenv("OQ_DEV_OUT", out)
    run_declaration_drift.main(["--dev-seeds", "1", "--block", "10",
                                "--carrier", "central_joint_leontief",
                                "--solver-python", os.environ["SOLVER_PYTHON"]])
    _no_violations(out)
    # nine arms per unit for a native carrier
    summary = json.load(open(os.path.join(out, "summary.json")))
    assert summary["n_arms_per_unit"] == 9
