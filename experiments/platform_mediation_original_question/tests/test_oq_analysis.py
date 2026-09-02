"""Analysis primitives: exact best-subset enumeration, bootstrap reproducibility,
harmed-set calculation, five-condition reconstruction, manifest verification."""
import json
import os

import numpy as np

from pilotlib import local_opt
from lib.analysis import cell_bootstrap
import make_oq_analysis as A
import make_oq_manifest as M


def test_best_subset_enumeration_matches_bruteforce():
    res = ["COMPUTE", "MEMORY"]
    fps = [{"COMPUTE": 3, "MEMORY": 1}, {"COMPUTE": 2, "MEMORY": 2}, {"COMPUTE": 1, "MEMORY": 4},
           {"COMPUTE": 5, "MEMORY": 1}]
    quals = [0.8, 0.7, 0.6, 0.75]
    alloc = {"COMPUTE": 6, "MEMORY": 5}
    got = local_opt.locally_optimized_completion(alloc, fps, quals, res)
    # brute force over all 16 subsets
    best = (0, -1.0)
    for mask in range(1 << 4):
        cu = {"COMPUTE": 0, "MEMORY": 0}
        cnt = 0
        q = 0.0
        for i in range(4):
            if mask >> i & 1:
                for r in res:
                    cu[r] += fps[i][r]
                cnt += 1
                q += quals[i]
        if all(cu[r] <= alloc[r] for r in res):
            if (cnt, q) > best:
                best = (cnt, q)
    assert got["count"] == best[0]


def test_all_256_subsets_enumerated():
    res = ["COMPUTE", "MEMORY", "API_CREDITS", "DATASET"]
    fps = [{r: (i + 1) for r in res} for i in range(8)]
    quals = [0.5] * 8
    table = local_opt.build_subset_table(fps, quals, res)
    assert len(table) == 256


def test_bootstrap_reproducible():
    diffs = list(np.linspace(-0.2, 0.5, 200))
    a = cell_bootstrap(diffs, "reprod|x", 20260902, 20000)
    b = cell_bootstrap(diffs, "reprod|x", 20260902, 20000)
    assert a == b


def test_bootstrap_seed_changes_interval():
    diffs = list(np.linspace(-0.2, 0.5, 200))
    a = cell_bootstrap(diffs, "reprod|x", 20260902, 20000)
    b = cell_bootstrap(diffs, "reprod|y", 20260902, 20000)
    assert a["mean"] == b["mean"] and (a["ci_lo"], a["ci_hi"]) != (b["ci_lo"], b["ci_hi"])


def test_harmed_set_calculation():
    # arm A harms agents (s0,a0); arm B harms (s0,a1); ref is 'equal'
    agents_by = {
        ("c", "equal"): [{"seed": "0", "agent": "a0", "queue_order_completion": "0.5"},
                         {"seed": "0", "agent": "a1", "queue_order_completion": "0.5"}],
        ("c", "A"): [{"seed": "0", "agent": "a0", "queue_order_completion": "0.25"},
                     {"seed": "0", "agent": "a1", "queue_order_completion": "0.5"}],
        ("c", "B"): [{"seed": "0", "agent": "a0", "queue_order_completion": "0.5"},
                     {"seed": "0", "agent": "a1", "queue_order_completion": "0.25"}],
    }
    out = A.harmed_set_compare(agents_by, "c", "A", "B", "equal")
    assert out["harmed_a"] == 1 and out["harmed_b"] == 1
    assert out["exact_harmed_set_equal"] is False
    assert out["harmed_set_jaccard"] == 0.0
    assert abs(out["harm_indicator_agreement"] - 0.0) < 1e-9  # disagree on both


def test_five_condition_reconstruction():
    cell_policy = {"c": {"t": {"qo_tasks_per_run": 40.0, "frac_zero_qo": 0.0, "zero_events": True},
                         "b": {"qo_tasks_per_run": 36.0, "frac_zero_qo": 0.0, "zero_events": True}}}
    paired_qo = {"c": {"t_minus_b": {"mean_tasks": 4.0, "ci_lo_tasks": 2.0, "ci_hi_tasks": 6.0}}}
    d = A.five_condition(cell_policy, paired_qo, "c", "t", "b")
    assert d["pass"] is True and d["c3_at_least_one_task"] and d["c2_ci_above_zero"]
    # failing case: CI crosses zero
    paired_qo["c"]["t_minus_b"] = {"mean_tasks": 0.5, "ci_lo_tasks": -0.3, "ci_hi_tasks": 1.2}
    d2 = A.five_condition(cell_policy, paired_qo, "c", "t", "b")
    assert d2["pass"] is False and not d2["c3_at_least_one_task"]


def test_manifest_roundtrip(tmp_path):
    f = tmp_path / "artifact.txt"
    f.write_text("hello manifest")
    manifest = {"artifacts": [{"path": os.path.relpath(str(f), M.ROOT), "bytes": f.stat().st_size,
                               "sha256": M.sha256(str(f))}]}
    mpath = tmp_path / "m.json"
    mpath.write_text(json.dumps(manifest))
    assert M.verify(str(mpath)) == []
    f.write_text("tampered")
    assert M.verify(str(mpath)) != []
