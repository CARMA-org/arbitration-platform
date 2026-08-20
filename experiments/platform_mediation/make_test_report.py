#!/usr/bin/env python3
"""Assemble a machine-readable test report from the Java and Python suites and
the enforcement fault-injection invariants."""
import glob
import json
import os
import re
import subprocess
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


def java_report():
    suites = []
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for xml in sorted(glob.glob(os.path.join(ROOT, "target", "surefire-reports", "TEST-*.xml"))):
        t = ET.parse(xml).getroot()
        s = {"suite": t.get("name"),
             "tests": int(t.get("tests")), "failures": int(t.get("failures")),
             "errors": int(t.get("errors")), "skipped": int(t.get("skipped"))}
        suites.append(s)
        for k in totals:
            totals[k] += s[k]
    return {"totals": totals, "suites": suites}


def python_report(pytest_python):
    proc = subprocess.run([pytest_python, "-m", "pytest", "tests/python", "-q"],
                          cwd=ROOT, capture_output=True, text=True)
    m = re.search(r"(\d+) passed", proc.stdout)
    failed = re.search(r"(\d+) failed", proc.stdout)
    return {"passed": int(m.group(1)) if m else 0,
            "failed": int(failed.group(1)) if failed else 0,
            "returncode": proc.returncode}


def enforcement_report():
    fp = os.path.join(ROOT, "experiments", "enforcement", "results", "enforcement_report_full.json")
    if not os.path.exists(fp):
        return None
    with open(fp) as f:
        rep = json.load(f)
    return {"totals": rep["totals"], "all_invariants_zero": rep["all_invariants_zero"],
            "cases": [c["case"] for c in rep["cases"]]}


def main():
    pytest_python = os.environ.get("SOLVER_PYTHON", "python3")
    report = {
        "java": java_report(),
        "python": python_report(pytest_python),
        "enforcement_fault_injection": enforcement_report(),
    }
    jt = report["java"]["totals"]
    py = report["python"]
    enf = report["enforcement_fault_injection"] or {}
    report["all_green"] = (
        jt["tests"] > 0
        and jt["failures"] == 0
        and jt["errors"] == 0
        and jt["skipped"] == 0
        and py["passed"] > 0
        and py["returncode"] == 0
        and py["failed"] == 0
        and enf.get("all_invariants_zero", False))
    out = os.path.join(HERE, "test_report.json")
    with open(out, "w") as f:
        json.dump(report, f, indent=2)
    print(json.dumps({"java": report["java"]["totals"], "python": report["python"],
                      "enforcement_zero": report["enforcement_fault_injection"]["all_invariants_zero"],
                      "all_green": report["all_green"]}, indent=2))


if __name__ == "__main__":
    main()
