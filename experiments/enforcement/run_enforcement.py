#!/usr/bin/env python3
"""Enforcement fault-injection driver.

Runs the Java fault-injection harness against the canonical runtime and adds a
solver-contract check that unsupported utility families are refused rather than
silently replaced by a linear surrogate. Writes a machine-readable report; every
invariant target is zero.

These checks concern mechanical enforcement only. They are NOT evidence of
strategyproofness, truthful reporting, collusion resistance, or protection
against a malicious platform operator.
"""
import argparse
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
RESULTS = os.path.join(HERE, "results")
HARNESS = "org.carma.arbitration.experiment.EnforcementFaultInjection"


def classpath():
    cp_file = os.path.join(ROOT, "cp.txt")
    with open(cp_file) as f:
        deps = f.read().strip()
    return os.path.join(ROOT, "target", "classes") + os.pathsep + deps


def run_solver(solver_python, data):
    solver_script = os.path.join(ROOT, "scripts", "joint_solver.py")
    proc = subprocess.run([solver_python, solver_script], input=json.dumps(data),
                          capture_output=True, text=True, cwd=ROOT)
    if proc.returncode != 0:
        raise RuntimeError("solver subprocess exited %d: %s"
                           % (proc.returncode, proc.stderr.strip()[:400]))
    lines = [ln for ln in proc.stdout.splitlines() if ln.strip()]
    if len(lines) != 1:
        raise RuntimeError("solver produced %d JSON result lines, expected 1: %r"
                           % (len(lines), proc.stdout[:400]))
    try:
        return json.loads(lines[0])
    except json.JSONDecodeError as e:
        raise RuntimeError("solver produced non-JSON output: %s: %r" % (e, lines[0][:400]))


def unsupported_utility_check(solver_python):
    data = {
        "n_agents": 2, "n_resources": 2,
        "preferences": [[0.6, 0.4], [0.4, 0.6]],
        "priority_weights": [1.0, 1.0],
        "capacities": [100, 100],
        "minimums": [[1, 1], [1, 1]],
        "ideals": [[100, 100], [100, 100]],
        "utility_configs": [{"type": "SOFTPLUS_LOSS_AVERSION"}, {"type": "LINEAR"}],
    }
    res = run_solver(solver_python, data)
    incorrect_success = 1 if res.get("allocations") is not None else 0
    silent_substitution = 0 if res.get("status") == "unsupported_model" else 1
    return {
        "case": "unsupported_utility_requests",
        "status": res.get("status"),
        "trials": 1, "operations": 1, "single_shot": True,
        "expected_successes": 0, "observed_successes": incorrect_success,
        "expected_denials": 1, "observed_denials": 1 - incorrect_success,
        "backend_after_denial": 0,
        "quota_violations": 0,
        "capacity_violations": 0,
        "partial_deductions": 0,
        "silent_fallbacks": silent_substitution,
        "incorrect_success": incorrect_success,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--solver-python", default=os.environ.get("SOLVER_PYTHON", "python3"))
    ap.add_argument("--reps", type=int, default=100)
    ap.add_argument("--smoke", action="store_true")
    ap.add_argument("--output-dir", default=RESULTS)
    args = ap.parse_args()
    reps = 10 if args.smoke else args.reps

    malformed = os.path.join(HERE, "fake_solvers", "malformed.py")
    hung = os.path.join(HERE, "fake_solvers", "hung_solver.py")

    proc = subprocess.run(
        ["java", "-cp", classpath(), HARNESS, args.solver_python, malformed, hung, str(reps)],
        capture_output=True, text=True, cwd=ROOT)
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        raise SystemExit("Java harness failed")
    report = json.loads(proc.stdout.strip().splitlines()[-1])

    solver_case = unsupported_utility_check(args.solver_python)
    report["cases"].append(solver_case)
    report["unsupported_utility_status"] = solver_case["status"]

    totals = report["totals"]
    for k in ("silent_fallbacks", "incorrect_success"):
        totals[k] += solver_case[k]
    report["all_invariants_zero"] = all(v == 0 for v in totals.values())

    out_dir = args.output_dir
    os.makedirs(out_dir, exist_ok=True)
    mode = "smoke" if args.smoke else "full"
    with open(os.path.join(out_dir, "enforcement_report_%s.json" % mode), "w") as f:
        json.dump(report, f, indent=2)

    import csv
    fields = ["case", "trials", "operations", "expected_successes", "observed_successes",
              "expected_denials", "observed_denials", "single_shot", "backend_after_denial",
              "quota_violations", "capacity_violations", "partial_deductions",
              "silent_fallbacks", "incorrect_success"]
    with open(os.path.join(out_dir, "enforcement_cases_%s.csv" % mode), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for c in report["cases"]:
            w.writerow({k: c.get(k, "") for k in fields})

    print(json.dumps({"mode": mode, "reps": reps, "totals": totals,
                      "all_invariants_zero": report["all_invariants_zero"],
                      "unsupported_utility_status": report["unsupported_utility_status"]}, indent=2))
    if not report["all_invariants_zero"]:
        raise SystemExit("INVARIANT VIOLATION DETECTED")


if __name__ == "__main__":
    main()
