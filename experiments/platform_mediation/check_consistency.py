#!/usr/bin/env python3
"""Fail if the paper-facing memo diverges from the raw data or if structural
invariants of the sweep are violated. Runs in CI."""
import csv
import hashlib
import json
import os
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

OBSOLETE_CELLS = {"identical", "nearly_specialized", "broad_heterogeneous", "complementary"}
OBSOLETE_POLICIES = {"separable", "joint"}
OBSOLETE_FIELDS = {"gamma", "declared_welfare"}


def load_csv(path):
    with open(path) as f:
        rows = list(csv.DictReader(f))
        return rows, (rows[0].keys() if rows else [])


def load_json(path):
    if os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    return None


def f3(x):
    return "n/a" if x is None else ("%+.3f" % x if x < 0 else "%.3f" % x)


def main():
    errors = []
    runs, run_fields = load_csv(os.path.join(HERE, "results", "raw", "runs.csv"))
    agents, agent_fields = load_csv(os.path.join(HERE, "results", "raw", "agents.csv"))
    headline = load_json(os.path.join(HERE, "results", "headline.json"))
    summary = load_json(os.path.join(HERE, "results", "summary.json"))
    memo_path = os.path.join(HERE, "RESULTS_FOR_PAPER.md")
    memo = open(memo_path).read() if os.path.exists(memo_path) else ""

    if headline is None or summary is None:
        print("missing headline or summary")
        sys.exit(1)

    policies = set(summary["policies"])
    cells_cfg = {"%s__%s" % (comp, con) for comp in summary["compositions"]
                 for con in summary["contention"]}

    for f in OBSOLETE_FIELDS:
        if f in run_fields:
            errors.append("runs.csv has obsolete field '%s'" % f)
    for r in runs:
        if r["cell"].rsplit("__", 1)[0] in OBSOLETE_CELLS:
            errors.append("runs.csv has obsolete cell %s" % r["cell"])
            break
    if OBSOLETE_POLICIES & {r["policy"] for r in runs}:
        errors.append("runs.csv has obsolete policy: %s" % (OBSOLETE_POLICIES & {r["policy"] for r in runs}))
    for r in runs:
        if r["policy"] not in policies:
            errors.append("runs.csv policy %s not in config" % r["policy"])
            break
        if r["cell"] not in cells_cfg:
            errors.append("runs.csv cell %s not in config" % r["cell"])
            break

    if headline["n_runs"] != len(runs):
        errors.append("headline n_runs %d != runs rows %d" % (headline["n_runs"], len(runs)))
    if headline["n_agent_records"] != len(agents):
        errors.append("headline n_agent_records %d != agents rows %d" % (headline["n_agent_records"], len(agents)))
    if summary["total_test_runs"] != summary["expected_runs"]:
        errors.append("run count %d != expected %d" % (summary["total_test_runs"], summary["expected_runs"]))

    expected_seeds = summary["n_test_seeds_per_cell"]
    by_cell_policy = defaultdict(list)
    for r in runs:
        by_cell_policy[(r["cell"], r["policy"])].append(r["seed"])
    cells = sorted({r["cell"] for r in runs})
    for c in cells:
        for p in summary["policies"]:
            seeds = by_cell_policy.get((c, p), [])
            if len(seeds) != expected_seeds:
                errors.append("cell %s policy %s has %d seeds, expected %d" % (c, p, len(seeds), expected_seeds))
            if len(set(seeds)) != len(seeds):
                errors.append("cell %s policy %s has duplicate seeds" % (c, p))
        if (c, "drf") not in by_cell_policy:
            errors.append("DRF missing for cell %s" % c)

    hash_by_cell_seed = defaultdict(set)
    for r in runs:
        hash_by_cell_seed[(r["cell"], r["seed"])].add(r["scenario_hash"])
    for key, hashes in hash_by_cell_seed.items():
        if len(hashes) != 1:
            errors.append("scenario hash mismatch across policies for %s" % (key,))

    for c in cells:
        n_distinct = summary["distinct_workload_hashes_per_cell"].get(c, 0)
        if c.startswith("mixed") and n_distinct < expected_seeds:
            errors.append("mixed cell %s has %d distinct workload hashes, expected %d"
                          % (c, n_distinct, expected_seeds))
    for key, v in headline["workload_variation_mixed"].items():
        if v["distinct_values"] <= 1:
            errors.append("inert mixed workload (no completion variation) for %s" % key)

    homo_arch = defaultdict(set)
    for a in agents:
        if a["cell"].startswith("homogeneous"):
            homo_arch[(a["cell"], a["seed"], a["policy"])].add(a["archetype"])
    for key, arch in homo_arch.items():
        if len(arch) != 1:
            errors.append("homogeneous workload differs across agents for %s: %s" % (key, arch))

    if memo:
        for c in headline["mixed_cells"]:
            for p in headline["policies"]:
                v = headline["per_cell_completion"][c][p]
                if f3(v) not in memo:
                    errors.append("memo missing mixed completion %s/%s (%s)" % (c, p, f3(v)))
        jl = headline["joint_latency_ms"]
        if ("median %.0f ms, p95 %.0f ms, max\n" % (jl["median"], jl["p95"]))[:20] not in memo \
                and ("median %.0f ms" % jl["median"]) not in memo:
            errors.append("memo joint latency median not present")

    import glob
    dyn_dir = os.path.join(ROOT, "experiments", "dynamic_allocation", "results")
    dyn_summary = load_json(os.path.join(dyn_dir, "summary.json"))
    if dyn_summary is not None and not glob.glob(os.path.join(dyn_dir, "raw", "epochs_*.csv")):
        errors.append("dynamic summary present but epoch-level raw data missing")

    manifest = load_json(os.path.join(HERE, "EXPERIMENT_MANIFEST.json")) if "--with-manifest" in sys.argv else None
    if manifest:
        for art in manifest.get("artifacts", []):
            path = os.path.join(ROOT, art["path"])
            if not os.path.exists(path):
                errors.append("manifest artifact missing: %s" % art["path"])
                continue
            if hashlib.sha256(open(path, "rb").read()).hexdigest() != art["sha256"]:
                errors.append("manifest hash mismatch: %s" % art["path"])

    if errors:
        print("CONSISTENCY CHECK FAILED (%d issues):" % len(errors))
        for e in errors:
            print("  - " + e)
        sys.exit(1)
    print("consistency check passed: %d runs, %d agent records, %d cells, %d policies, "
          "min distinct mixed completion=%d"
          % (len(runs), len(agents), len(cells), len(policies), headline["min_distinct_completion_mixed"]))


if __name__ == "__main__":
    main()
