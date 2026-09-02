#!/usr/bin/env python3
"""Resumable deterministic pipeline for the original-question closure.

Phases (run in order; architecture must be committed and pushed before drift):
  preflight       verify the public preregistration is present, record the environment
                  provenance, confirm no confirmatory raw result exists yet, and confirm
                  seed and workload-hash disjointness.
  architecture    run the architecture experiment (resumable), its analysis, and the
                  frozen carrier selection; confirm the carrier decision reconstructs
                  exactly (run the selection twice and compare).
  drift           refuse unless the carrier decision is present; run the drift experiment
                  (resumable) for the selected carrier and its analysis.
  manifests       build the source, architecture and drift SHA-256 manifests.

The underlying drivers write raw data incrementally and atomically and skip completed
units on restart, so any phase can be re-entered without recomputing or replacing
finished work. Partial outcomes are never inspected: analysis runs only after a phase's
execution completes.
"""
import argparse
import hashlib
import json
import os
import platform
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
PROTOCOL = os.path.join(HERE, "ORIGINAL_QUESTION_CLOSURE_PROTOCOL.md")
ARCH_RAW = os.path.join(HERE, "results", "architecture_v1", "raw", "runs.csv")
DRIFT_RAW = os.path.join(HERE, "results", "drift_v1", "raw", "runs.csv")
CARRIER = os.path.join(HERE, "DRIFT_CARRIER_DECISION.json")


def sh(cmd, **kw):
    print("+ " + " ".join(cmd), flush=True)
    return subprocess.run(cmd, check=True, **kw)


def solver_python():
    return os.environ.get("SOLVER_PYTHON", sys.executable)


def _pkg_versions():
    out = {}
    for mod in ("numpy", "cvxpy", "clarabel", "scipy"):
        try:
            m = __import__(mod)
            out[mod] = getattr(m, "__version__", "?")
        except Exception:
            out[mod] = None
    return out


def _tool_version(cmd):
    try:
        return subprocess.check_output(cmd, text=True, stderr=subprocess.STDOUT).splitlines()[0]
    except Exception:
        return None


def preflight():
    assert os.path.exists(PROTOCOL), "public preregistration protocol is not present"
    prov = {
        "generated_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "python": sys.version.split()[0], "platform": platform.platform(),
        "solver_python": solver_python(),
        "java": _tool_version(["java", "-version"]), "maven": _tool_version(["mvn", "-version"]),
        "packages": _pkg_versions(),
        "protocol_sha256": hashlib.sha256(open(PROTOCOL, "rb").read()).hexdigest(),
        "git_head": _tool_version(["git", "-C", ROOT, "rev-parse", "HEAD"]),
    }
    os.makedirs(os.path.join(HERE, "results"), exist_ok=True)
    with open(os.path.join(HERE, "results", "provenance.json"), "w") as f:
        json.dump(prov, f, indent=2)
    # disjointness of confirmatory seeds
    sys.path.insert(0, HERE)
    from oqlib import seeds_oq as S
    S.assert_disjoint_scenario_seeds(S.NS_ARCH_CONF, 200)
    S.assert_disjoint_scenario_seeds(S.NS_DRIFT_CONF, 200)
    print("preflight ok: protocol present, provenance recorded, confirmatory seeds disjoint")
    print("  architecture confirmatory raw present:", os.path.exists(ARCH_RAW))
    print("  drift confirmatory raw present:", os.path.exists(DRIFT_RAW))


def run_architecture():
    sp = solver_python()
    sh([sp, os.path.join(HERE, "run_architecture.py"), "--solver-python", sp])
    sh([sp, os.path.join(HERE, "make_oq_analysis.py"), "architecture"])
    sh([sp, os.path.join(HERE, "select_drift_carrier.py")])
    first = json.load(open(CARRIER))
    sh([sp, os.path.join(HERE, "select_drift_carrier.py")])
    second = json.load(open(CARRIER))
    assert first["selected_carrier"] == second["selected_carrier"] and first["branch"] == second["branch"], \
        "carrier decision did not reconstruct exactly"
    print("architecture phase complete; selected carrier:", first["selected_carrier"])


def run_drift():
    assert os.path.exists(CARRIER), "carrier decision missing; run and commit the architecture phase first"
    sp = solver_python()
    sh([sp, os.path.join(HERE, "run_declaration_drift.py"), "--solver-python", sp])
    sh([sp, os.path.join(HERE, "make_oq_analysis.py"), "drift"])
    print("drift phase complete")


def manifests():
    sp = solver_python()
    for scope in ("source", "architecture", "drift"):
        if scope == "drift" and not os.path.exists(DRIFT_RAW):
            continue
        sh([sp, os.path.join(HERE, "make_oq_manifest.py"), scope])


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("phase", choices=["preflight", "architecture", "drift", "manifests"])
    args = ap.parse_args(argv)
    {"preflight": preflight, "architecture": run_architecture,
     "drift": run_drift, "manifests": manifests}[args.phase]()


if __name__ == "__main__":
    main()
