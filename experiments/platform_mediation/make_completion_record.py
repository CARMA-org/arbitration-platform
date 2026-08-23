#!/usr/bin/env python3
import csv
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


def count_rows(path):
    if not os.path.exists(path):
        return None
    with open(path) as f:
        return sum(1 for _ in csv.reader(f)) - 1


def load_json(path):
    if os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    return None


def main():
    pm_summary = load_json(os.path.join(HERE, "results", "summary.json")) or {}
    pm_runs = count_rows(os.path.join(HERE, "results", "raw", "runs.csv"))
    pm_infeasible = count_rows(os.path.join(HERE, "results", "raw", "infeasible_runs.csv"))
    pm_agents = count_rows(os.path.join(HERE, "results", "raw", "agents.csv"))
    pm_expected = pm_summary.get("expected_runs")

    dyn_dir = os.path.join(ROOT, "experiments", "dynamic_allocation", "results")
    dyn_summary = load_json(os.path.join(dyn_dir, "summary.json")) or {}
    dyn_mode = dyn_summary.get("mode", "full")
    dyn_epochs = count_rows(os.path.join(dyn_dir, "raw", "epochs_%s.csv" % dyn_mode))
    dyn_expected = None
    if dyn_summary:
        dyn_expected = len(dyn_summary["policies"]) * dyn_summary["seeds"] * dyn_summary["epochs"]

    primary_ok = pm_runs is not None and pm_expected is not None and \
        (pm_runs + (pm_infeasible or 0)) == pm_expected
    dynamic_ok = dyn_epochs is not None and dyn_expected is not None and dyn_epochs == dyn_expected

    record = {
        "note": "completion status derived from committed raw row counts, not from run logs",
        "primary": {
            "runs_csv_rows": pm_runs,
            "infeasible_rows": pm_infeasible,
            "expected_runs": pm_expected,
            "agent_records": pm_agents,
            "complete": bool(primary_ok),
        },
        "dynamic": {
            "epoch_rows": dyn_epochs,
            "expected_epoch_rows": dyn_expected,
            "complete": bool(dynamic_ok),
        },
        "all_complete": bool(primary_ok and dynamic_ok),
    }
    with open(os.path.join(HERE, "results", "run_completion.json"), "w") as f:
        json.dump(record, f, indent=2)
    print(json.dumps(record, indent=2))


if __name__ == "__main__":
    main()
