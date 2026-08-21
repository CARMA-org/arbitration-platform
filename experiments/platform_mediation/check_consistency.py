#!/usr/bin/env python3
"""Reconstruct results from raw records and fail if the generated headline, memo,
figures, manifest, or documentation disagree with the raw data or violate
structural invariants. Runs in CI."""
import csv
import glob
import hashlib
import json
import os
import sys
from collections import defaultdict

import numpy as np

from lib.analysis import cell_bootstrap, stratified_bootstrap

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

OBSOLETE_CELLS = {"identical", "nearly_specialized", "broad_heterogeneous", "complementary"}
OBSOLETE_POLICIES = {"separable", "joint"}
OBSOLETE_FIELDS = {"gamma", "declared_welfare"}
OBSOLETE_TEXT = ["separable water-filling", "gamma =", "gamma=", "latency-budget completion",
                 "acquireSlot", "releaseSlot"]


def load_csv(path):
    with open(path) as f:
        rows = list(csv.DictReader(f))
        return rows, (list(rows[0].keys()) if rows else [])


def load_json(path):
    if os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    return None


def approx(a, b, tol=1e-6):
    return abs(a - b) <= tol * max(1.0, abs(a), abs(b))


def main():
    errors = []
    runs, run_fields = load_csv(os.path.join(HERE, "results", "raw", "runs.csv"))
    agents, agent_fields = load_csv(os.path.join(HERE, "results", "raw", "agents.csv"))
    infeasible, _ = load_csv(os.path.join(HERE, "results", "raw", "infeasible_runs.csv"))
    headline = load_json(os.path.join(HERE, "results", "headline.json"))
    summary = load_json(os.path.join(HERE, "results", "summary.json"))
    validation = load_json(os.path.join(HERE, "results", "decomposition_validation.json"))
    memo_path = os.path.join(HERE, "RESULTS_FOR_PAPER.md")
    memo = open(memo_path).read() if os.path.exists(memo_path) else ""

    if headline is None or summary is None:
        print("missing headline or summary")
        sys.exit(1)

    policies = list(summary["policies"])
    boot_seed = summary["bootstrap_seed"]
    expected_seeds = summary["n_test_seeds_per_cell"]
    cells_cfg = {"%s__%s" % (comp, con) for comp in summary["compositions"]
                 for con in summary["contention"]}

    for f in OBSOLETE_FIELDS:
        if f in run_fields:
            errors.append("runs.csv has obsolete field '%s'" % f)
    if OBSOLETE_POLICIES & {r["policy"] for r in runs}:
        errors.append("runs.csv has obsolete policy")
    for r in runs:
        if r["cell"].rsplit("__", 1)[0] in OBSOLETE_CELLS:
            errors.append("runs.csv has obsolete cell %s" % r["cell"]); break
        if r["policy"] not in policies or r["cell"] not in cells_cfg:
            errors.append("runs.csv has policy/cell not in config"); break

    if headline["n_runs"] != len(runs):
        errors.append("headline n_runs %d != runs rows %d" % (headline["n_runs"], len(runs)))
    if headline["n_agent_records"] != len(agents):
        errors.append("headline n_agent_records != agents rows")
    if len(runs) + len(infeasible) != summary["expected_runs"]:
        errors.append("feasible+infeasible %d != expected %d" %
                      (len(runs) + len(infeasible), summary["expected_runs"]))

    cells = sorted({r["cell"] for r in runs})
    idx = {}
    seeds_by_cell = defaultdict(list)
    seen = set()
    by_cell_policy = defaultdict(list)
    for r in runs:
        idx[(r["cell"], r["seed"], r["policy"])] = r
        by_cell_policy[(r["cell"], r["policy"])].append(r["seed"])
        if (r["cell"], r["seed"]) not in seen:
            seen.add((r["cell"], r["seed"]))
            seeds_by_cell[r["cell"]].append(r["seed"])

    for c in cells:
        for p in policies:
            sds = by_cell_policy.get((c, p), [])
            if len(sds) != expected_seeds:
                errors.append("cell %s policy %s has %d seeds != %d" % (c, p, len(sds), expected_seeds))
            if len(set(sds)) != len(sds):
                errors.append("cell %s policy %s duplicate seeds" % (c, p))

    for c in cells:
        wl = defaultdict(set)
        sc = defaultdict(set)
        for r in runs:
            if r["cell"] == c:
                wl[r["seed"]].add(r["workload_hash"])
                sc[r["seed"]].add(r["scenario_hash"])
        distinct_wl = {list(v)[0] for v in wl.values() if len(v) == 1}
        if c.startswith("mixed") and len(distinct_wl) != expected_seeds:
            errors.append("mixed cell %s has %d distinct workload hashes != %d" %
                          (c, len(distinct_wl), expected_seeds))
        for sd, hs in sc.items():
            if len(hs) != 1:
                errors.append("cell %s seed %s: policies disagree on scenario hash" % (c, sd))
        for sd, hs in wl.items():
            if len(hs) != 1:
                errors.append("cell %s seed %s: policies disagree on workload hash" % (c, sd))

    homo_arch = defaultdict(set)
    for a in agents:
        if a["cell"].startswith("homogeneous"):
            homo_arch[(a["cell"], a["seed"], a["policy"])].add(a["archetype"])
    for key, arch in homo_arch.items():
        if len(arch) != 1:
            errors.append("homogeneous workload differs across agents for %s" % (key,))

    recon_cell_mean = {}
    for c in cells:
        for p in policies:
            vals = [float(idx[(c, s, p)]["completion_mean"]) for s in seeds_by_cell[c] if (c, s, p) in idx]
            recon_cell_mean[(c, p)] = float(np.mean(vals)) if vals else None
            hv = headline["per_cell_completion"][c][p]
            if hv is not None and recon_cell_mean[(c, p)] is not None and not approx(hv, recon_cell_mean[(c, p)]):
                errors.append("headline completion %s/%s %.6f != recon %.6f" % (c, p, hv, recon_cell_mean[(c, p)]))

    joints = summary["joint_policies"]
    ref = summary["reference_policy"]
    comparisons = [(jp, "equal") for jp in joints] + [(jp, "drf") for jp in joints]
    comparisons += [(jp, ref) for jp in joints if jp != ref]
    comparisons += [("decomposed_cobb_douglas", "joint_cobb_douglas")]
    mixed_cells = [c for c in cells if c.startswith("mixed")]
    for treat, base in comparisons:
        name = "%s_minus_%s" % (treat, base)
        mixed_diffs = []
        for c in mixed_cells:
            mixed_diffs.append([float(idx[(c, s, treat)]["completion_mean"]) - float(idx[(c, s, base)]["completion_mean"])
                                for s in seeds_by_cell[c] if (c, s, treat) in idx and (c, s, base) in idx])
        recon = stratified_bootstrap(mixed_diffs, "mixed|%s" % name, boot_seed)
        hv = headline["mixed_aggregate_completion"][name]
        for k in ("mean", "ci_lo", "ci_hi"):
            if not approx(recon[k], hv[k]):
                errors.append("mixed aggregate %s %s recon %.6f != headline %.6f" % (name, k, recon[k], hv[k]))

    recon_solver = defaultdict(lambda: defaultdict(int))
    for r in runs:
        if r["policy"] in joints:
            st = r["solver_status"].lower()
            cls = "optimal_inaccurate" if "optimal_inaccurate" in st else ("optimal" if "optimal" in st else "failed")
            recon_solver[r["policy"]][cls] += 1
    for p in joints:
        if dict(recon_solver[p]) != summary["solver_status_counts"].get(p, {}):
            errors.append("solver status counts mismatch for %s" % p)
    total_solver = sum(sum(v.values()) for v in recon_solver.values())
    if total_solver != len(joints) * len(cells) * expected_seeds:
        errors.append("joint solver status total %d unexpected" % total_solver)

    cap_v = sum(int(r["capacity_violation"]) for r in runs)
    bnd_v = sum(int(r["bound_violation"]) for r in runs)
    if cap_v or bnd_v:
        errors.append("capacity/bound violations present: %d/%d" % (cap_v, bnd_v))
    for r in runs:
        if str(r["fallback_used"]).lower() == "true":
            errors.append("unexpected fallback_used in run"); break

    if validation is None:
        errors.append("decomposition_validation.json missing")
    else:
        cc = validation["continuous_comparison"]
        if cc["n_compared"] <= 0:
            errors.append("decomposition validation compared 0 instances")
        mcv = headline["cobb_douglas_decomposition"].get("measured_continuous_validation")
        if mcv is None:
            errors.append("headline missing measured decomposition validation")
        if "continuous_agreement_tolerance" in json.dumps(headline["cobb_douglas_decomposition"]):
            errors.append("headline contains a hardcoded continuous-agreement tolerance")

    dyn_dir = os.path.join(ROOT, "experiments", "dynamic_allocation", "results")
    dyn_summary = load_json(os.path.join(dyn_dir, "summary.json"))
    epoch_files = glob.glob(os.path.join(dyn_dir, "raw", "epochs_*.csv"))
    if dyn_summary is not None:
        if not epoch_files:
            errors.append("dynamic summary present but epoch raw data missing")
        else:
            epoch_path = os.path.join(dyn_dir, "raw", "epochs_%s.csv" % dyn_summary["mode"])
            erows, _ = load_csv(epoch_path)
            if len(erows) != dyn_summary["n_epoch_rows"]:
                errors.append("dynamic epoch rows %d != summary %d" % (len(erows), dyn_summary["n_epoch_rows"]))
            recon = defaultdict(lambda: {"viol": 0, "prot": 0, "infeas": 0})
            for e in erows:
                p = e["policy"]
                recon[p]["viol"] += len(json.loads(e["floor_shortfall_from_promise"]))
                recon[p]["prot"] += len(json.loads(e["promised_floors"]))
                recon[p]["infeas"] += int(e["original_floors_infeasible"])
            for a in dyn_summary["aggregate"]:
                p = a["policy"]
                if a["discrete_floor_violations"]["total"] != recon[p]["viol"]:
                    errors.append("dynamic %s floor violations %d != raw %d" %
                                  (p, a["discrete_floor_violations"]["total"], recon[p]["viol"]))
                if a["protected_agent_epochs"]["total"] != recon[p]["prot"]:
                    errors.append("dynamic %s protected epochs %d != raw %d" %
                                  (p, a["protected_agent_epochs"]["total"], recon[p]["prot"]))
                if a["infeasible_floor_epochs"]["total"] != recon[p]["infeas"]:
                    errors.append("dynamic %s infeasible-floor epochs mismatch" % p)
                if not isinstance(a["discrete_floor_violations"], dict):
                    errors.append("dynamic aggregate not labeled with denominators")

    fig_src = ""
    fig_path = os.path.join(HERE, "figures.py")
    if os.path.exists(fig_path):
        fig_src = open(fig_path).read()
    for needle in ("complementarity-aware", "complementary-aware"):
        if needle in fig_src.lower() or needle in memo.lower():
            errors.append("figure/memo calls a policy '%s'" % needle)
    if "CES" in memo and "intermediate" in memo:
        errors.append("memo describes CES as intermediate")

    if memo:
        for c in mixed_cells:
            for p in policies:
                v = headline["per_cell_completion"][c][p]
                cellstr = ("%+.3f" % v if v < 0 else "%.3f" % v) if v is not None else "n/a"
                if cellstr not in memo:
                    errors.append("memo missing mixed completion %s/%s (%s)" % (c, p, cellstr))
        for bad in OBSOLETE_TEXT:
            if bad in memo:
                errors.append("memo contains stale phrase '%s'" % bad)

    manifest = load_json(os.path.join(HERE, "EXPERIMENT_MANIFEST.json")) if "--with-manifest" in sys.argv else None
    if manifest:
        gen = manifest.get("generated_utc")
        for art in manifest.get("artifacts", []):
            path = os.path.join(ROOT, art["path"])
            if not os.path.exists(path):
                errors.append("manifest artifact missing: %s" % art["path"]); continue
            if hashlib.sha256(open(path, "rb").read()).hexdigest() != art["sha256"]:
                errors.append("manifest hash mismatch: %s" % art["path"])
        if manifest.get("source_commit") is None:
            errors.append("manifest missing source_commit")

    if errors:
        print("CONSISTENCY CHECK FAILED (%d issues):" % len(errors))
        for e in errors:
            print("  - " + e)
        sys.exit(1)
    print("consistency check passed: %d runs, %d agents, %d cells, %d policies; "
          "solver statuses, mixed CIs, dynamic epoch aggregates, and decomposition validation "
          "reconstructed from raw" % (len(runs), len(agents), len(cells), len(policies)))


if __name__ == "__main__":
    main()
