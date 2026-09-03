"""Tests for the preregistered drift secondary completion (complete_drift_secondary.py).

Independently aggregates representative cells directly from the frozen raw CSVs and checks
the committed supplemental JSON and CSV, and guards that every expected row and field is
present. Uses its own aggregation (does not trust the module under test for the numbers it
checks), then also confirms the module regenerates byte-stable outputs in a temp dir.
"""
import csv
import json
import os
import subprocess
import sys
from collections import defaultdict

import numpy as np
import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
EXP = os.path.abspath(os.path.join(HERE, ".."))
DRIFT = os.path.join(EXP, "results", "drift_v1")
OUT = os.path.join(DRIFT, "preregistered_secondary_completion")
TPA = 8
EPS = 1e-9

SOURCES = ["stale_calibration", "refreshed_calibration", "latent_distribution_oracle", "execution_queue_oracle"]
ARMS = ["equal"] + ["drf_%s" % s for s in SOURCES] + ["carrier_%s" % s for s in SOURCES]
CELLS = ["delta%.2f__%s" % (d, c) for d in (0.0, 0.25, 0.5, 0.75, 1.0) for c in ("moderate", "high")]


def load_csv(p):
    with open(p) as f:
        return list(csv.DictReader(f))


@pytest.fixture(scope="module")
def sec_json():
    return json.load(open(os.path.join(OUT, "drift_secondary_completion.json")))


@pytest.fixture(scope="module")
def sec_csv():
    return load_csv(os.path.join(OUT, "drift_secondary_completion.csv"))


@pytest.fixture(scope="module")
def raw_agents():
    ag = defaultdict(dict)
    for a in load_csv(os.path.join(DRIFT, "raw", "agents.csv")):
        ag[(a["cell"], a["arm"])][(a["seed"], a["agent"])] = float(a["queue_order_completion"])
    return ag


def _distributional(a_rows, ref_rows):
    keys = sorted(set(a_rows) & set(ref_rows))
    per = np.array([(a_rows[k] - ref_rows[k]) * TPA for k in keys], float)
    comp = np.array([a_rows[k] * TPA for k in keys], float)
    harmed = per < -EPS
    better = per > EPS
    return {
        "n_harmed": int(harmed.sum()), "frac_harmed": float(np.mean(harmed)),
        "mean_loss_harmed": float(-per[harmed].mean()) if harmed.any() else 0.0,
        "worst_loss": float(-per.min()),
        "n_improved": int(better.sum()), "frac_improved": float(np.mean(better)),
        "mean_gain_improved": float(per[better].mean()) if better.any() else 0.0,
        "min_completion_tasks": float(comp.min()), "bottom_decile_tasks": float(np.percentile(comp, 10)),
        "mean_completion_tasks": float(comp.mean()),
    }


def test_all_cells_and_arms_present(sec_json, sec_csv):
    assert set(sec_json["cells"]) == set(CELLS)
    for cell in CELLS:
        assert set(sec_json["cells"][cell]["arms"]) == set(ARMS), cell
    # flat CSV: exactly one row per (cell, arm)
    assert len(sec_csv) == len(CELLS) * len(ARMS) == 90
    seen = {(r["cell"], r["arm"]) for r in sec_csv}
    assert seen == {(c, a) for c in CELLS for a in ARMS}


def test_all_fields_present(sec_json):
    scen_fields = {"drift_source_total_mean", "changed_identities_total_mean", "task_mixture_tv_from_baseline_mean",
                   "mand_demand_tv_mean_pairwise", "task_entropy_mean", "cross_agent_dissimilarity_mean",
                   "staleness_error_mean", "calibration_error_mean", "latent_oracle_error_mean",
                   "realized_contention_mean"}
    dist_fields = {"n_harmed", "frac_harmed", "mean_loss_harmed", "median_loss_harmed", "worst_loss",
                   "n_improved", "frac_improved", "mean_gain_improved", "median_gain_improved",
                   "frac_zero", "min_completion_tasks", "bottom_decile_tasks", "mean_completion_tasks"}
    run_fields = {"mean_qo_tasks_per_run", "mean_bs_tasks_per_run", "mean_cap_util",
                  "mean_unused_installed", "frac_zero_qo"}
    for cell, cb in sec_json["cells"].items():
        assert scen_fields <= set(cb["scenario_metrics"]), cell
        for arm, blk in cb["arms"].items():
            assert run_fields <= set(blk), (cell, arm)
            assert dist_fields <= set(blk["vs_equal"]), (cell, arm)
            if arm.startswith("carrier_"):
                assert "vs_matched_drf" in blk, (cell, arm)
                assert dist_fields <= set(blk["vs_matched_drf"]), (cell, arm)
            else:
                assert "vs_matched_drf" not in blk, (cell, arm)


@pytest.mark.parametrize("cell,arm", [
    ("delta0.25__moderate", "carrier_stale_calibration"),
    ("delta0.25__high", "drf_stale_calibration"),
    ("delta1.00__moderate", "carrier_execution_queue_oracle"),
])
def test_distributional_vs_equal_independent(sec_json, raw_agents, cell, arm):
    exp = _distributional(raw_agents[(cell, arm)], raw_agents[(cell, "equal")])
    got = sec_json["cells"][cell]["arms"][arm]["vs_equal"]
    for k, v in exp.items():
        assert got[k] == pytest.approx(v, abs=1e-9), (cell, arm, k, got[k], v)


@pytest.mark.parametrize("cell,src", [("delta0.25__moderate", "stale_calibration"),
                                      ("delta0.50__high", "refreshed_calibration")])
def test_matched_carrier_vs_drf_independent(sec_json, raw_agents, cell, src):
    exp = _distributional(raw_agents[(cell, "carrier_%s" % src)], raw_agents[(cell, "drf_%s" % src)])
    got = sec_json["cells"][cell]["arms"]["carrier_%s" % src]["vs_matched_drf"]
    for k, v in exp.items():
        assert got[k] == pytest.approx(v, abs=1e-9), (cell, src, k)


def test_scenario_metrics_independent(sec_json):
    scen = defaultdict(list)
    for s in load_csv(os.path.join(DRIFT, "raw", "scenarios.csv")):
        scen[s["cell"]].append(s)
    for cell in ("delta0.25__moderate", "delta1.00__high"):
        rows = scen[cell]
        exp_ds = float(np.mean([float(r["drift_source_total"]) for r in rows]))
        exp_rc = float(np.mean([max(json.loads(r["realized_contention_by_resource"]).values()) for r in rows]))
        exp_cd = float(np.mean([float(r["cross_agent_dissimilarity"]) for r in rows]))
        sm = sec_json["cells"][cell]["scenario_metrics"]
        assert sm["drift_source_total_mean"] == pytest.approx(exp_ds, abs=1e-9)
        assert sm["realized_contention_mean"] == pytest.approx(exp_rc, abs=1e-9)
        assert sm["cross_agent_dissimilarity_mean"] == pytest.approx(exp_cd, abs=1e-9)


def test_run_means_match_headline_arm_means(sec_json):
    """The reported per-arm mean queue-order tasks/run must equal the frozen headline's
    independently-produced arm_tasks_per_run (a cross-check against the frozen analysis)."""
    head = json.load(open(os.path.join(DRIFT, "drift_headline.json")))
    for cell, cb in sec_json["cells"].items():
        hd = head["secondary"][cell]["arm_tasks_per_run"]
        for arm, blk in cb["arms"].items():
            assert blk["mean_qo_tasks_per_run"] == pytest.approx(hd[arm], abs=1e-6), (cell, arm)


def test_module_regenerates_stable(tmp_path):
    """Running the module over a copy of the frozen raw reproduces the committed outputs
    byte-for-byte, confirming determinism and that it reads only the raw files."""
    import shutil
    dst = tmp_path / "drift_v1"
    shutil.copytree(os.path.join(DRIFT, "raw"), dst / "raw")
    env = dict(os.environ, OQ_DRIFT_DIR=str(dst))
    subprocess.check_call([sys.executable, os.path.join(EXP, "complete_drift_secondary.py")], env=env)
    for name in ("drift_secondary_completion.json", "drift_secondary_completion.csv"):
        a = open(os.path.join(OUT, name), "rb").read()
        b = open(dst / "preregistered_secondary_completion" / name, "rb").read()
        assert a == b, "regenerated %s differs from committed" % name


def test_frozen_headline_untouched():
    """The completion must not have altered the frozen drift headline or response table."""
    head = json.load(open(os.path.join(DRIFT, "drift_headline.json")))
    assert head["declaration_robustness_classification"] == "ROBUST_AT_MODEST_DRIFT"
    assert head["co_primary_decision"]["delta0.25__moderate"]["ci_lo_tasks"] == pytest.approx(1.315, abs=1e-9)
