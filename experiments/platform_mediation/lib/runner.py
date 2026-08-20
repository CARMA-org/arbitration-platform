"""Invoke the canonical Java runtime harness over a batch of jobs (JSONL)."""
import json
import os
import subprocess

HARNESS = "org.carma.arbitration.experiment.PlatformMediationHarness"


def project_root():
    return os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))


def classpath(root=None):
    root = root or project_root()
    cp_file = os.path.join(root, "cp.txt")
    if not os.path.exists(cp_file):
        raise RuntimeError("cp.txt missing; run: mvn -o -q dependency:build-classpath "
                           "-Dmdep.outputFile=cp.txt")
    with open(cp_file) as f:
        deps = f.read().strip()
    return os.path.join(root, "target", "classes") + os.pathsep + deps


def run_jobs(jobs, cp=None, java="java", root=None, chunk=300):
    """Run jobs through one or more JVMs and return parsed result dicts in order."""
    root = root or project_root()
    cp = cp or classpath(root)
    results = []
    for start in range(0, len(jobs), chunk):
        batch = jobs[start:start + chunk]
        payload = "\n".join(json.dumps(j) for j in batch) + "\n"
        proc = subprocess.run(
            [java, "-cp", cp, HARNESS],
            input=payload, capture_output=True, text=True, cwd=root)
        lines = [ln for ln in proc.stdout.splitlines() if ln.strip()]
        if len(lines) != len(batch):
            raise RuntimeError("harness returned %d results for %d jobs; stderr:\n%s"
                               % (len(lines), len(batch), proc.stderr[-2000:]))
        for ln in lines:
            results.append(json.loads(ln))
    return results
