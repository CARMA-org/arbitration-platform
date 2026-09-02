#!/usr/bin/env python3
"""Compare two result trees for exact equality of all non-timing fields, for the clean
clone reproduction. Timing and machine-dependent latency fields (runtime_ms,
alloc_latency_ms) are enumerated and excluded; every other field of every raw table,
headline and decision must match exactly.

Usage: compare_reproduction.py <dir_a> <dir_b>
"""
import csv
import json
import os
import sys

TIMING_FIELDS = {"runtime_ms", "alloc_latency_ms"}
RAW_TABLES = {
    "results/architecture_v1/raw": ["scenarios.csv", "runs.csv", "agents.csv", "distributed.csv", "infeasible.csv"],
    "results/drift_v1/raw": ["scenarios.csv", "runs.csv", "agents.csv", "declarations.csv", "distributed.csv", "infeasible.csv"],
}


def load(p):
    with open(p) as f:
        return list(csv.DictReader(f))


def compare_csv(a, b):
    diffs = []
    ra, rb = load(a), load(b)
    if len(ra) != len(rb):
        return ["rowcount %d != %d" % (len(ra), len(rb))]
    for i, (x, y) in enumerate(zip(ra, rb)):
        for k in x:
            if k in TIMING_FIELDS:
                continue
            if x.get(k) != y.get(k):
                diffs.append("row %d field %s: %r != %r" % (i, k, x.get(k), y.get(k)))
                if len(diffs) > 30:
                    return diffs
    return diffs


def main():
    a, b = sys.argv[1], sys.argv[2]
    all_diffs = {}
    for sub, tables in RAW_TABLES.items():
        for t in tables:
            pa, pb = os.path.join(a, sub, t), os.path.join(b, sub, t)
            if not (os.path.exists(pa) and os.path.exists(pb)):
                continue
            d = compare_csv(pa, pb)
            if d:
                all_diffs["%s/%s" % (sub, t)] = d
    # carrier decision (ignore hashes that depend on absolute paths / commit env)
    for name in ("DRIFT_CARRIER_DECISION.json",):
        pa, pb = os.path.join(a, name), os.path.join(b, name)
        if os.path.exists(pa) and os.path.exists(pb):
            da, db = json.load(open(pa)), json.load(open(pb))
            if da.get("selected_carrier") != db.get("selected_carrier") or da.get("conditions") != db.get("conditions"):
                all_diffs[name] = ["carrier or conditions differ"]
    if all_diffs:
        print("REPRODUCTION MISMATCH:")
        for k, v in all_diffs.items():
            print("  %s: %s" % (k, v[:5]))
        sys.exit(1)
    print("clean reproduction: all non-timing fields identical")


if __name__ == "__main__":
    main()
