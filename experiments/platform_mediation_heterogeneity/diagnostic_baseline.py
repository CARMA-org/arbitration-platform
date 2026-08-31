#!/usr/bin/env python3
"""Read-only baseline diagnostics for the canonical platform-mediation evaluation.

Reconstructs, from the canonical raw records at
``experiments/platform_mediation/results/raw`` plus deterministic workload
regeneration, the generator- and allocation-side quantities that explain why the
current mixed-bundle evaluation sits close to equal quotas. Every regenerated
scenario's ``scenario_hash`` and ``workload_hash`` are checked against the raw
records so the regeneration is provably the same workload the harness executed.

Writes only under ``experiments/platform_mediation_heterogeneity/`` (a JSON blob,
CSV tables, and the generated ``BASELINE_DIAGNOSTIC.md``). It never writes to any
canonical file.
"""
import csv
import json
import os
from collections import defaultdict

import numpy as np

from pilotlib import measures  # noqa: F401  (import first: sets up the canonical lib path)
from lib import scenario as canon
from lib.archetypes import RESOURCES, archetype_footprint
from lib.seeds import derive_seed

HERE = os.path.dirname(os.path.abspath(__file__))
CANON = os.path.abspath(os.path.join(HERE, "..", "platform_mediation"))
CANON_RAW = os.path.join(CANON, "results", "raw")
RESULTS = os.path.join(HERE, "results")
TABLES = os.path.join(HERE, "tables")

N_AGENTS = 6
TPA = 8
N_TEST = 100
TASKS_PER_RUN = N_AGENTS * TPA  # 48
CONTENTION = {"moderate": 1.3, "high": 1.9}
COMPOSITIONS = ["homogeneous", "mixed_bundle"]
TASK_TYPES = list(canon.TASK_TYPES)
POLICIES = ["equal", "drf", "decomposed_cobb_douglas", "joint_linear",
            "joint_cobb_douglas", "joint_ces", "joint_leontief"]
CFG = {"n_agents": N_AGENTS, "tasks_per_agent": TPA}


def load_csv(path):
    with open(path) as f:
        return list(csv.DictReader(f))


def cells():
    out = []
    for comp in COMPOSITIONS:
        for cname, ratio in CONTENTION.items():
            out.append((comp, cname, ratio, "%s__%s" % (comp, cname)))
    return out


def test_seeds(label):
    return [derive_seed(label, "test", i) for i in range(N_TEST)]


def stat_block(values):
    a = np.asarray(values, float)
    if a.size == 0:
        return {"mean": None, "median": None, "min": None, "max": None, "p95": None, "n": 0}
    return {"mean": float(a.mean()), "median": float(np.median(a)), "min": float(a.min()),
            "max": float(a.max()), "p95": float(np.percentile(a, 95)), "n": int(a.size)}


def regenerate(log):
    """Regenerate every canonical scenario and validate its hashes against raw."""
    runs = load_csv(os.path.join(CANON_RAW, "runs.csv"))
    raw_hash = {}
    for r in runs:
        raw_hash[(r["cell"], r["seed"])] = (r["scenario_hash"], r["workload_hash"])

    scenarios = {}
    hash_ok = 0
    hash_total = 0
    for comp, cname, ratio, label in cells():
        for seed in test_seeds(label):
            sc = canon.base_scenario(comp, cname, ratio, seed, CFG)
            scenarios[(label, str(seed))] = sc
            key = (label, str(seed))
            if key in raw_hash:
                hash_total += 1
                if (sc["scenario_hash"], sc["workload_hash"]) == raw_hash[key]:
                    hash_ok += 1
    log("regeneration: %d/%d canonical (cell,seed) scenario+workload hashes matched raw"
        % (hash_ok, hash_total))
    if hash_ok != hash_total or hash_total == 0:
        raise RuntimeError("regeneration hash mismatch: %d/%d" % (hash_ok, hash_total))
    return runs, scenarios, hash_ok, hash_total


def archetype_coverage(scenarios, agents_rows):
    """(#1) unique-archetype coverage per agent, by cell, from regeneration; also
    validate against the equal-policy 'archetype' field in raw agents.csv."""
    regen = {}   # cell -> Counter over unique-archetype-count
    for (label, seed), sc in scenarios.items():
        cnt = regen.setdefault(label, defaultdict(int))
        for a in sc["agents"]:
            k = len(set(a["task_types"]))
            cnt[k] += 1

    raw = {}
    for a in agents_rows:
        if a["policy"] != "equal":
            continue
        c = raw.setdefault(a["cell"], defaultdict(int))
        k = len([x for x in a["archetype"].split("+") if x])
        c[k] += 1

    table = {}
    agreement = True
    for label in sorted(regen):
        total = sum(regen[label].values())
        row = {"cell": label, "n_agents_total": total}
        for k in (1, 2, 3, 4):
            row["regen_agents_with_%d" % k] = regen[label].get(k, 0)
            row["raw_agents_with_%d" % k] = raw.get(label, {}).get(k, 0)
            if regen[label].get(k, 0) != raw.get(label, {}).get(k, 0):
                agreement = False
        table[label] = row
    return table, agreement


def dissimilarity(scenarios):
    """(#2, #3) per-scenario mean pairwise TV of task-mixture and resource-demand
    vectors, aggregated by cell and across the mixed cells."""
    task_by_cell = defaultdict(list)
    res_by_cell = defaultdict(list)
    centroid_by_cell = defaultdict(list)
    per_seed = {}   # (label, seed) -> (task_tv, res_tv)
    for (label, seed), sc in scenarios.items():
        att = [a["task_types"] for a in sc["agents"]]
        md = [a["mandatory_demand"] for a in sc["agents"]]
        d = measures.workload_dissimilarity(att, md, TASK_TYPES, RESOURCES)
        task_by_cell[label].append(d["task_mixture_tv_mean_pairwise"])
        res_by_cell[label].append(d["resource_demand_tv_mean_pairwise"])
        centroid_by_cell[label].append(d["resource_centroid_distance_mean"])
        per_seed[(label, seed)] = (d["task_mixture_tv_mean_pairwise"],
                                   d["resource_demand_tv_mean_pairwise"])
    task = {c: stat_block(v) for c, v in task_by_cell.items()}
    res = {c: stat_block(v) for c, v in res_by_cell.items()}
    cent = {c: stat_block(v) for c, v in centroid_by_cell.items()}
    mixed = [c for c in res_by_cell if c.startswith("mixed")]
    res_mixed_combined = stat_block([x for c in mixed for x in res_by_cell[c]])
    task_mixed_combined = stat_block([x for c in mixed for x in task_by_cell[c]])
    return task, res, cent, res_mixed_combined, task_mixed_combined, per_seed


def alloc_distance(agents_rows):
    """(#4) relative L1 allocation distance from the paired equal allocation, by
    policy and cell and across the mixed cells."""
    alloc = {}
    for a in agents_rows:
        alloc[(a["cell"], a["seed"], a["agent"], a["policy"])] = json.loads(a["allocated"])
    keys = {(a["cell"], a["seed"], a["agent"]) for a in agents_rows}
    by_pol_cell = defaultdict(lambda: defaultdict(list))
    mixed_by_pol = defaultdict(list)
    for (cell, seed, agent) in keys:
        ea = alloc.get((cell, seed, agent, "equal"))
        if ea is None:
            continue
        denom = max(sum(ea.values()), 1)
        for p in POLICIES:
            pa = alloc.get((cell, seed, agent, p))
            if pa is None:
                continue
            d = sum(abs(pa[r] - ea[r]) for r in RESOURCES) / denom
            by_pol_cell[p][cell].append(d)
            if cell.startswith("mixed"):
                mixed_by_pol[p].append(d)
    table = {p: {c: stat_block(v) for c, v in by_pol_cell[p].items()} for p in POLICIES}
    mixed_combined = {p: stat_block(mixed_by_pol[p]) for p in POLICIES}
    return table, mixed_combined


def completion_effects(runs_rows):
    """(#5) paired completion differences and their task-unit equivalents."""
    idx = {}
    seeds_by_cell = defaultdict(list)
    seen = set()
    for r in runs_rows:
        idx[(r["cell"], r["seed"], r["policy"])] = float(r["completion_mean"])
        if (r["cell"], r["seed"]) not in seen:
            seen.add((r["cell"], r["seed"]))
            seeds_by_cell[r["cell"]].append(r["seed"])
    comparisons = [("joint_cobb_douglas", "drf"), ("joint_ces", "drf"), ("joint_leontief", "drf"),
                   ("joint_cobb_douglas", "equal"), ("joint_ces", "equal"), ("joint_leontief", "equal"),
                   ("joint_linear", "equal"), ("joint_linear", "drf"),
                   ("decomposed_cobb_douglas", "joint_cobb_douglas")]
    mixed = [c for c in seeds_by_cell if c.startswith("mixed")]
    out = {}
    for treat, base in comparisons:
        name = "%s_minus_%s" % (treat, base)
        per_cell = {}
        for c in seeds_by_cell:
            diffs = [idx[(c, s, treat)] - idx[(c, s, base)] for s in seeds_by_cell[c]
                     if (c, s, treat) in idx and (c, s, base) in idx]
            per_cell[c] = {"completion_diff": float(np.mean(diffs)) if diffs else None,
                           "tasks_per_run": float(np.mean(diffs)) * TASKS_PER_RUN if diffs else None,
                           "n": len(diffs)}
        pooled = [idx[(c, s, treat)] - idx[(c, s, base)] for c in mixed for s in seeds_by_cell[c]
                  if (c, s, treat) in idx and (c, s, base) in idx]
        out[name] = {"per_cell": per_cell,
                     "mixed_pooled_completion_diff": float(np.mean(pooled)) if pooled else None,
                     "mixed_pooled_tasks_per_run": float(np.mean(pooled)) * TASKS_PER_RUN if pooled else None,
                     "n_pooled": len(pooled)}
    return out


def joint_linear_floor(runs_rows, agents_rows, scenarios):
    """(#6) joint-linear floor diagnostics by cell."""
    alloc = {}
    for a in agents_rows:
        alloc[(a["cell"], a["seed"], a["agent"], a["policy"])] = json.loads(a["allocated"])
    caputil = {}
    for r in runs_rows:
        caputil[(r["cell"], r["seed"], r["policy"])] = float(r["capacity_utilization"])

    out = {}
    for comp, cname, ratio, label in cells():
        n_zero = n_any_at = n_all_above = n_total = 0
        compl_at = []
        compl_above = []
        dists = []
        caps = []
        for (lab, seed), sc in scenarios.items():
            if lab != label:
                continue
            caps.append(caputil.get((label, seed, "joint_linear"), np.nan))
            for a in sc["agents"]:
                key = (label, seed, a["id"])
                jl = alloc.get(key + ("joint_linear",))
                eq = alloc.get(key + ("equal",))
                if jl is None:
                    continue
                n_total += 1
                # completion for this agent under joint_linear
                comp = _agent_completion(agents_rows, label, seed, a["id"], "joint_linear")
                if comp is not None and comp <= 1e-12:
                    n_zero += 1
                used = [r for r in RESOURCES if a["mandatory_demand"][r] > 0]
                any_at = any(jl[r] == a["min"][r] for r in used)
                all_above = all(jl[r] > a["min"][r] for r in used)
                n_any_at += int(any_at)
                n_all_above += int(all_above)
                if comp is not None:
                    (compl_at if any_at else compl_above).append(comp)
                if eq is not None:
                    denom = max(sum(eq.values()), 1)
                    dists.append(sum(abs(jl[r] - eq[r]) for r in RESOURCES) / denom)
        out[label] = {
            "n_agents": n_total,
            "frac_zero_completion": n_zero / n_total if n_total else None,
            "frac_any_used_at_lower_bound": n_any_at / n_total if n_total else None,
            "frac_all_used_above_lower_bound": n_all_above / n_total if n_total else None,
            "mean_completion_when_any_at_floor": float(np.mean(compl_at)) if compl_at else None,
            "mean_completion_when_all_above_floor": float(np.mean(compl_above)) if compl_above else None,
            "alloc_distance_from_equal_mean": float(np.mean(dists)) if dists else None,
            "capacity_utilization_mean": float(np.nanmean(caps)) if caps else None,
        }
    return out


_AGENT_COMPL_CACHE = {}


def _agent_completion(agents_rows, cell, seed, agent, policy):
    if not _AGENT_COMPL_CACHE:
        for a in agents_rows:
            _AGENT_COMPL_CACHE[(a["cell"], a["seed"], a["agent"], a["policy"])] = float(a["completion"])
    return _AGENT_COMPL_CACHE.get((cell, seed, agent, policy))


def quartile_association(runs_rows, per_seed_diss):
    """(#7) exploratory: policy completion differences across quartiles of realized
    resource-demand and task-mixture dissimilarity, by contention and pooled."""
    idx = {}
    for r in runs_rows:
        idx[(r["cell"], r["seed"], r["policy"])] = float(r["completion_mean"])
    comparisons = [("joint_cobb_douglas", "drf"), ("joint_ces", "drf"),
                   ("joint_leontief", "drf"), ("joint_linear", "equal")]
    mixed_cells = ["mixed_bundle__moderate", "mixed_bundle__high"]

    def quartile_means(cell_list, diss_index, comp):
        treat, base = comp
        pts = []
        for c in cell_list:
            for (lab, seed), (task_tv, res_tv) in per_seed_diss.items():
                if lab != c:
                    continue
                if (c, seed, treat) in idx and (c, seed, base) in idx:
                    pts.append((diss_index(task_tv, res_tv), idx[(c, seed, treat)] - idx[(c, seed, base)]))
        if len(pts) < 4:
            return None
        pts.sort(key=lambda x: x[0])
        qs = np.array_split(np.array([p[1] for p in pts]), 4)
        xs = np.array_split(np.array([p[0] for p in pts]), 4)
        return [{"quartile": i + 1, "diss_mean": float(xs[i].mean()),
                 "diff_mean": float(qs[i].mean()), "tasks_per_run": float(qs[i].mean()) * TASKS_PER_RUN,
                 "n": int(qs[i].size)} for i in range(4)]

    out = {"by_resource_demand_tv": {}, "by_task_mixture_tv": {}}
    for comp in comparisons:
        name = "%s_minus_%s" % comp
        out["by_resource_demand_tv"][name] = {
            "moderate": quartile_means(["mixed_bundle__moderate"], lambda t, r: r, comp),
            "high": quartile_means(["mixed_bundle__high"], lambda t, r: r, comp),
            "pooled": quartile_means(mixed_cells, lambda t, r: r, comp),
        }
        out["by_task_mixture_tv"][name] = {
            "moderate": quartile_means(["mixed_bundle__moderate"], lambda t, r: t, comp),
            "high": quartile_means(["mixed_bundle__high"], lambda t, r: t, comp),
            "pooled": quartile_means(mixed_cells, lambda t, r: t, comp),
        }
    return out


def archetype_profiles():
    """Normalized mandatory resource profiles of the four archetypes and their
    pairwise TV, supporting the 'three of four are similar' observation."""
    profs = {}
    for arch in TASK_TYPES:
        fp = archetype_footprint(arch, include_optional=False)
        profs[arch] = measures.resource_demand_vector(fp, RESOURCES)
    pairwise = {}
    for i in range(len(TASK_TYPES)):
        for j in range(i + 1, len(TASK_TYPES)):
            a, b = TASK_TYPES[i], TASK_TYPES[j]
            pairwise["%s|%s" % (a, b)] = measures.tv(profs[a], profs[b])
    return {"normalized_profiles": profs, "pairwise_tv": pairwise}


# ---------------------------------------------------------------------------
# Output assembly
# ---------------------------------------------------------------------------

def _pct(x):
    return "n/a" if x is None else "%.2f%%" % (100.0 * x)


def _f(x, nd=4):
    return "n/a" if x is None else ("%.*f" % (nd, x))


def write_tables(diag):
    os.makedirs(TABLES, exist_ok=True)

    with open(os.path.join(TABLES, "baseline_archetype_coverage.csv"), "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["cell", "n_agents_total", "with_2plus", "with_3plus", "with_4",
                    "raw_with_4", "regen_with_4"])
        for label, row in sorted(diag["archetype_coverage"]["table"].items()):
            with2 = sum(row["regen_agents_with_%d" % k] for k in (2, 3, 4))
            with3 = sum(row["regen_agents_with_%d" % k] for k in (3, 4))
            w.writerow([label, row["n_agents_total"], with2, with3,
                        row["regen_agents_with_4"], row["raw_agents_with_4"],
                        row["regen_agents_with_4"]])

    with open(os.path.join(TABLES, "baseline_dissimilarity.csv"), "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["cell", "measure", "mean", "median", "min", "max", "p95", "n"])
        for c, s in sorted(diag["task_dissimilarity"].items()):
            w.writerow([c, "task_mixture_tv", _f(s["mean"], 6), _f(s["median"], 6),
                        _f(s["min"], 6), _f(s["max"], 6), _f(s["p95"], 6), s["n"]])
        for c, s in sorted(diag["resource_dissimilarity"].items()):
            w.writerow([c, "resource_demand_tv", _f(s["mean"], 6), _f(s["median"], 6),
                        _f(s["min"], 6), _f(s["max"], 6), _f(s["p95"], 6), s["n"]])

    with open(os.path.join(TABLES, "baseline_alloc_distance.csv"), "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["policy", "scope", "mean_rel_l1", "median_rel_l1", "p95_rel_l1", "n"])
        for p in POLICIES:
            mc = diag["alloc_distance_mixed_combined"][p]
            w.writerow([p, "mixed_combined", _f(mc["mean"], 6), _f(mc["median"], 6),
                        _f(mc["p95"], 6), mc["n"]])
            for c, s in sorted(diag["alloc_distance"][p].items()):
                w.writerow([p, c, _f(s["mean"], 6), _f(s["median"], 6), _f(s["p95"], 6), s["n"]])

    with open(os.path.join(TABLES, "baseline_effect_sizes.csv"), "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["comparison", "scope", "completion_diff", "tasks_per_run", "n"])
        for name, d in sorted(diag["completion_effects"].items()):
            w.writerow([name, "mixed_pooled", _f(d["mixed_pooled_completion_diff"], 6),
                        _f(d["mixed_pooled_tasks_per_run"], 4), d["n_pooled"]])
            for c, pc in sorted(d["per_cell"].items()):
                w.writerow([name, c, _f(pc["completion_diff"], 6), _f(pc["tasks_per_run"], 4), pc["n"]])

    with open(os.path.join(TABLES, "baseline_joint_linear_floor.csv"), "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["cell", "n_agents", "frac_zero_completion", "frac_any_used_at_lower_bound",
                    "frac_all_used_above_lower_bound", "mean_completion_when_any_at_floor",
                    "mean_completion_when_all_above_floor", "alloc_distance_from_equal_mean",
                    "capacity_utilization_mean"])
        for c, s in sorted(diag["joint_linear_floor"].items()):
            w.writerow([c, s["n_agents"], _f(s["frac_zero_completion"], 4),
                        _f(s["frac_any_used_at_lower_bound"], 4), _f(s["frac_all_used_above_lower_bound"], 4),
                        _f(s["mean_completion_when_any_at_floor"], 4),
                        _f(s["mean_completion_when_all_above_floor"], 4),
                        _f(s["alloc_distance_from_equal_mean"], 6), _f(s["capacity_utilization_mean"], 4)])


def write_report(diag):
    ac = diag["archetype_coverage"]["table"]
    td = diag["task_dissimilarity"]
    rd = diag["resource_dissimilarity"]
    rmc = diag["resource_dissimilarity_mixed_combined"]
    tmc = diag["task_dissimilarity_mixed_combined"]
    ad = diag["alloc_distance_mixed_combined"]
    ce = diag["completion_effects"]
    jlf = diag["joint_linear_floor"]
    ap = diag["archetype_profiles"]

    L = []
    L.append("# Baseline diagnostic: the canonical mixed-bundle evaluation")
    L.append("")
    L.append("Generated by `diagnostic_baseline.py` from the canonical raw records at "
             "`experiments/platform_mediation/results/raw` plus deterministic workload "
             "regeneration. All numbers below are computed, not transcribed. This is a "
             "read-only diagnostic; it changes no canonical file.")
    L.append("")
    L.append("**Regeneration fidelity.** %d of %d canonical `(cell, seed)` scenarios were "
             "regenerated with a `scenario_hash` and `workload_hash` matching the raw records "
             "exactly, so the regenerated task queues, demands, bounds, and declarations are "
             "the same objects the Java harness executed."
             % (diag["regeneration"]["hash_ok"], diag["regeneration"]["hash_total"]))
    L.append("")
    L.append("Fixed design: %d resources, %d agents, %d tasks/agent (%d tasks per run), "
             "two contention levels, 100 seeds/cell, 7 policies." %
             (len(RESOURCES), N_AGENTS, TPA, TASKS_PER_RUN))
    L.append("")

    L.append("## 1. Archetype coverage by cell")
    L.append("")
    L.append("Count of agents (out of 600 per cell = 6 agents x 100 seeds) whose 8-task queue "
             "contains 2, 3, or all 4 archetypes, from the equal-policy records. Regeneration "
             "and the raw `archetype` field agree: %s." %
             ("yes" if diag["archetype_coverage"]["agreement"] else "NO -- see JSON"))
    L.append("")
    L.append("| cell | >=2 archetypes | >=3 archetypes | all 4 archetypes (regen = raw) |")
    L.append("|---|---|---|---|")
    for label, row in sorted(ac.items()):
        with2 = sum(row["regen_agents_with_%d" % k] for k in (2, 3, 4))
        with3 = sum(row["regen_agents_with_%d" % k] for k in (3, 4))
        L.append("| %s | %d | %d | %d = %d |" %
                 (label, with2, with3, row["regen_agents_with_4"], row["raw_agents_with_4"]))
    L.append("")
    L.append("Full-archetype coverage is *not* itself a measure of cross-agent asymmetry: in a "
             "homogeneous cell every agent can contain all four archetypes while every agent in "
             "a run is identical. Coverage counts the breadth of a single queue, not the "
             "differences between agents.")
    L.append("")

    L.append("## 2. Task-mixture dissimilarity by cell")
    L.append("")
    L.append("Each agent's normalized 4-element task-frequency vector; per scenario the mean "
             "pairwise total variation `0.5*L1`; aggregated over the 100 seeds of each cell.")
    L.append("")
    L.append("| cell | mean | median | min | max | p95 |")
    L.append("|---|---|---|---|---|---|")
    for c in sorted(td):
        s = td[c]
        L.append("| %s | %s | %s | %s | %s | %s |" %
                 (c, _f(s["mean"]), _f(s["median"]), _f(s["min"]), _f(s["max"]), _f(s["p95"])))
    L.append("")

    L.append("## 3. Resource-demand dissimilarity by cell")
    L.append("")
    L.append("Each agent's mandatory resource-demand vector normalized to sum to one; per "
             "scenario the mean pairwise total variation; aggregated over seeds.")
    L.append("")
    L.append("| cell | mean | median | min | max | p95 |")
    L.append("|---|---|---|---|---|---|")
    for c in sorted(rd):
        s = rd[c]
        L.append("| %s | %s | %s | %s | %s | %s |" %
                 (c, _f(s["mean"]), _f(s["median"]), _f(s["min"]), _f(s["max"]), _f(s["p95"])))
    L.append("")
    L.append("Across the two mixed cells combined, the mean pairwise resource-demand total "
             "variation is **%s** (task-mixture combined mean **%s**). Resource-demand "
             "dissimilarity is roughly %.1fx smaller than task-mixture dissimilarity: agents "
             "differ substantially in *which tasks* they run but much less in *aggregate "
             "resource shape*." %
             (_f(rmc["mean"]), _f(tmc["mean"]),
              (tmc["mean"] / rmc["mean"]) if rmc["mean"] else float("nan")))
    L.append("")

    L.append("## 4. Installed-allocation distance from equal quotas")
    L.append("")
    L.append("For each cell, seed, agent, and policy, `rel_l1 = sum_r |a_policy - a_equal| / "
             "max(sum_r a_equal, 1)` against the paired equal allocation. Means across the two "
             "mixed cells:")
    L.append("")
    L.append("| policy | mean rel L1 | median | p95 |")
    L.append("|---|---|---|---|")
    for p in POLICIES:
        if p == "equal":
            continue
        s = ad[p]
        L.append("| %s | %s | %s | %s |" % (p, _pct(s["mean"]), _pct(s["median"]), _pct(s["p95"])))
    L.append("")
    L.append("The three well-specified nonlinear declarations and the decomposed comparator move "
             "the installed allocation only single-digit percent away from equal quotas; DRF "
             "moves it about ten-plus percent; joint_linear (the misspecification stress test) "
             "moves it very far, because linear utility treats the resources as substitutes.")
    L.append("")

    L.append("## 5. Effect sizes in task units")
    L.append("")
    L.append("Every completion difference is reported both as a completion fraction and as "
             "`completion_diff x %d` completed tasks per run. Pooled across the two mixed cells:" % TASKS_PER_RUN)
    L.append("")
    L.append("| comparison | completion diff | tasks per run |")
    L.append("|---|---|---|")
    for name in ["joint_cobb_douglas_minus_drf", "joint_ces_minus_drf", "joint_leontief_minus_drf",
                 "joint_linear_minus_equal", "joint_linear_minus_drf"]:
        d = ce[name]
        L.append("| %s | %s | %s |" %
                 (name, _f(d["mixed_pooled_completion_diff"], 6), _f(d["mixed_pooled_tasks_per_run"], 3)))
    L.append("")
    L.append("The nonlinear advantages over DRF are on the order of a fraction of one task per "
             "48-task run. These are not large gains.")
    L.append("")

    L.append("## 6. Joint-linear floor diagnostics by cell")
    L.append("")
    L.append("| cell | frac zero-completion | frac any used resource at floor | frac all used above floor | mean completion (any at floor) | mean completion (all above) | alloc dist from equal | capacity util |")
    L.append("|---|---|---|---|---|---|---|---|")
    for c in sorted(jlf):
        s = jlf[c]
        L.append("| %s | %s | %s | %s | %s | %s | %s | %s |" %
                 (c, _f(s["frac_zero_completion"], 3), _f(s["frac_any_used_at_lower_bound"], 3),
                  _f(s["frac_all_used_above_lower_bound"], 3),
                  _f(s["mean_completion_when_any_at_floor"], 3),
                  _f(s["mean_completion_when_all_above_floor"], 3),
                  _pct(s["alloc_distance_from_equal_mean"]), _f(s["capacity_utilization_mean"], 3)))
    L.append("")
    L.append("Under the unit floor, joint_linear concentrates each agent's allocation onto a "
             "single resource and pins the agent's other used resources at their one-unit floor, "
             "so bundle-structured tasks that need several resources cannot complete.")
    L.append("")

    L.append("## 7. Exploratory association (not causal)")
    L.append("")
    L.append("Quartile splits of per-seed policy completion differences against realized "
             "resource-demand and task-mixture dissimilarity, by contention and pooled, are in "
             "`results/baseline_diagnostic.json` under `quartile_association`. They are "
             "descriptive only: quartiles are formed after seeing the data and no threshold is "
             "inferred.")
    L.append("")

    L.append("## Why the present design sits close to equal quotas")
    L.append("")
    L.append("The following are properties of the current experimental design (verified in code "
             "and in the numbers above), not implementation defects:")
    L.append("")
    L.append("- Eight i.i.d. draws from four archetypes usually give each agent a broad mixture "
             "(section 1: most agents contain all four archetypes).")
    L.append("- Three of the four archetypes have similar normalized mandatory resource "
             "profiles. Pairwise total variation of the archetype resource profiles:")
    L.append("")
    L.append("| archetype pair | resource-profile TV |")
    L.append("|---|---|")
    for k, v in sorted(ap["pairwise_tv"].items(), key=lambda kv: kv[1]):
        L.append("| %s | %s |" % (k.replace("|", " vs "), _f(v)))
    L.append("")
    L.append("- Mean pairwise resource-demand distance (section 3) is much smaller than the "
             "task-frequency distance (section 2).")
    L.append("- Every resource capacity is scaled from aggregate mandatory demand by the same "
             "contention ratio (`capacity_r = round(total_demand_r / ratio)`), so relative "
             "scarcity is uniform across resources.")
    L.append("- Every nonlinear declaration aggregates the agent's complete task queue into one "
             "normalized resource vector (`declaration_source = exact_pending_queue`).")
    L.append("- Operator priorities are equal, removing another source of asymmetry.")
    L.append("- Upper bounds and declarations are derived from the exact execution queue.")
    L.append("")
    L.append("Together these make an allocation close to equal quotas unsurprising, and they "
             "motivate the pilot's manipulation of cross-agent workload concentration.")
    L.append("")
    return "\n".join(L).rstrip("\n") + "\n"


def main():
    def log(m):
        print(m, flush=True)

    os.makedirs(RESULTS, exist_ok=True)
    runs, scenarios, hash_ok, hash_total = regenerate(log)
    agents = load_csv(os.path.join(CANON_RAW, "agents.csv"))

    cov_table, cov_agree = archetype_coverage(scenarios, agents)
    task_d, res_d, cent_d, res_mixed, task_mixed, per_seed = dissimilarity(scenarios)
    ad_table, ad_mixed = alloc_distance(agents)
    effects = completion_effects(runs)
    jlf = joint_linear_floor(runs, agents, scenarios)
    quart = quartile_association(runs, per_seed)
    profs = archetype_profiles()

    diag = {
        "regeneration": {"hash_ok": hash_ok, "hash_total": hash_total},
        "archetype_coverage": {"table": cov_table, "agreement": cov_agree},
        "task_dissimilarity": task_d,
        "resource_dissimilarity": res_d,
        "resource_centroid_distance": cent_d,
        "resource_dissimilarity_mixed_combined": res_mixed,
        "task_dissimilarity_mixed_combined": task_mixed,
        "alloc_distance": ad_table,
        "alloc_distance_mixed_combined": ad_mixed,
        "completion_effects": effects,
        "joint_linear_floor": jlf,
        "quartile_association": quart,
        "archetype_profiles": profs,
    }
    with open(os.path.join(RESULTS, "baseline_diagnostic.json"), "w") as f:
        json.dump(diag, f, indent=2)
    write_tables(diag)
    report = write_report(diag)
    with open(os.path.join(HERE, "BASELINE_DIAGNOSTIC.md"), "w") as f:
        f.write(report)

    log("baseline diagnostic written:")
    log("  archetype coverage agreement (regen vs raw): %s" % cov_agree)
    log("  mixed-combined resource-demand TV mean = %s" % _f(res_mixed["mean"], 6))
    log("  alloc distance from equal (mixed): " +
        ", ".join("%s=%s" % (p, _pct(ad_mixed[p]["mean"])) for p in POLICIES if p != "equal"))
    log("  CD-DRF pooled tasks/run = %s; CES-DRF = %s; Leontief-DRF = %s" %
        (_f(effects["joint_cobb_douglas_minus_drf"]["mixed_pooled_tasks_per_run"], 3),
         _f(effects["joint_ces_minus_drf"]["mixed_pooled_tasks_per_run"], 3),
         _f(effects["joint_leontief_minus_drf"]["mixed_pooled_tasks_per_run"], 3)))


if __name__ == "__main__":
    main()
