#!/usr/bin/env python3
"""Fail if the paper-facing memo diverges from the raw data or if structural
invariants of the sweep are violated. Intended to run in CI."""
import csv
import hashlib
import json
import os
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


def load_csv(name):
    with open(os.path.join(HERE, "results", "raw", name)) as f:
        return list(csv.DictReader(f))


def load_json(path):
    if os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    return None


def f3(x):
    return "n/a" if x is None else ("%+.3f" % x if x < 0 else "%.3f" % x)


def main():
    errors = []
    runs = load_csv("runs.csv")
    agents = load_csv("agents.csv")
    headline = load_json(os.path.join(HERE, "results", "headline.json"))
    summary = load_json(os.path.join(HERE, "results", "summary.json"))
    memo_path = os.path.join(HERE, "RESULTS_FOR_PAPER.md")
    memo = open(memo_path).read() if os.path.exists(memo_path) else ""

    if headline is None or summary is None:
        print("missing headline or summary; run make_headline.py and run_sweep first")
        sys.exit(1)

    if headline["n_runs"] != len(runs):
        errors.append("headline n_runs %d != runs.csv rows %d" % (headline["n_runs"], len(runs)))
    if headline["n_agent_records"] != len(agents):
        errors.append("headline n_agent_records %d != agents.csv rows %d"
                      % (headline["n_agent_records"], len(agents)))

    expected_seeds = summary["n_test_seeds_per_cell"]
    by_cell_policy = defaultdict(set)
    for r in runs:
        by_cell_policy[(r["cell"], r["policy"])].add(r["seed"])
    cells = sorted({r["cell"] for r in runs})
    policies = summary["policies"]
    for c in cells:
        for p in policies:
            got = len(by_cell_policy.get((c, p), set()))
            if got != expected_seeds:
                errors.append("cell %s policy %s has %d seeds, expected %d" % (c, p, got, expected_seeds))

    hash_by_cell_seed = defaultdict(set)
    for r in runs:
        hash_by_cell_seed[(r["cell"], r["seed"])].add(r["scenario_hash"])
    for key, hashes in hash_by_cell_seed.items():
        if len(hashes) != 1:
            errors.append("scenario hash mismatch across policies for %s: %s" % (key, hashes))

    for c in cells:
        if not c.startswith("homogeneous"):
            continue
        by = defaultdict(set)
        for a in agents:
            if a["cell"] == c:
                by[(a["seed"], a["policy"])].add(a["priority"])
        for key, prio in by.items():
            if len(prio) != 1:
                errors.append("homogeneous cell %s has unequal priorities for %s: %s" % (c, key, prio))

    for c in cells:
        if (c, "drf") not in by_cell_policy or not by_cell_policy[(c, "drf")]:
            errors.append("DRF missing for cell %s" % c)

    for p in headline["policies"]:
        if p not in {r["policy"] for r in runs}:
            errors.append("headline policy %s absent from raw data" % p)

    if memo:
        for p, v in headline["overall_completion_by_policy"].items():
            if ("| %s | %s |" % (p, f3(v))) not in memo:
                errors.append("memo missing completion for %s (%s)" % (p, f3(v)))
        lat = headline["allocation_latency_ms"]
        if ("median %.0f ms, p95 %.0f ms, max %.0f ms" % (lat["median"], lat["p95"], lat["max"])) not in memo:
            errors.append("memo latency line does not match headline")

    manifest = load_json(os.path.join(HERE, "EXPERIMENT_MANIFEST.json")) \
        if "--with-manifest" in sys.argv else None
    if manifest:
        for art in manifest.get("artifacts", []):
            path = os.path.join(ROOT, art["path"])
            if not os.path.exists(path):
                errors.append("manifest artifact missing: %s" % art["path"])
                continue
            h = hashlib.sha256(open(path, "rb").read()).hexdigest()
            if h != art["sha256"]:
                errors.append("manifest hash mismatch: %s" % art["path"])

    if errors:
        print("CONSISTENCY CHECK FAILED (%d issues):" % len(errors))
        for e in errors:
            print("  - " + e)
        sys.exit(1)
    print("consistency check passed: %d runs, %d agent records, %d cells, %d policies"
          % (len(runs), len(agents), len(cells), len(policies)))


if __name__ == "__main__":
    main()
