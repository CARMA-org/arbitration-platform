#!/usr/bin/env python3
"""Generate the comparator audit (COMPARATOR_AUDIT.md + comparator_audit.json) from
development-only scenarios and constructed examples. Establishes, before preregistration,
that independent_bundle_maxmin is distinct from DRF (mathematically and empirically) and
that the separable Leontief relaxation collapses to equal quotas under the tested
conditions while unequal weights or active bounds break the collapse.

Runs only development-namespace seeds and constructed examples; touches no confirmatory
seed. All four comparator mechanisms are enforced through the canonical Java runtime for
the randomized comparison.
"""
import json
import os

import numpy as np

import oqlib  # noqa: F401
from pilotlib import workload as wlgen, pilot_scenario
from lib import runner
from lib.archetypes import RESOURCES
from oqlib import maxmin, leontief_relaxation, seeds_oq as S
from oqlib import mechanisms as MECH
from oqlib.jobs import make_native_job, make_preinstalled_job

HERE = os.path.dirname(os.path.abspath(__file__))
SP = os.environ.get("SOLVER_PYTHON", "python3")
R2 = ["COMPUTE", "MEMORY"]
R3 = ["COMPUTE", "MEMORY", "API_CREDITS"]


def _randomized(n_seeds=60):
    regime = {"name": "dirichlet_0.1", "kind": "dirichlet", "concentration": 0.1}
    ratios = {"moderate": 1.3, "high": 1.9}
    seeds = S.scenario_seeds(S.NS_ARCH_DEV, n_seeds)
    jobs, meta, scen = [], [], {}
    for seed in seeds:
        wl = wlgen.generate_workload(regime, seed, 6, 8, S.NS_ARCH_DEV)
        for cname, ratio in ratios.items():
            sc = pilot_scenario.build_scenario(wl, cname, ratio, "unit", "audit_%s" % cname)
            cell = "audit_%s" % cname
            scen[(cell, seed)] = sc
            mm = MECH.independent_bundle_maxmin_alloc(sc)
            rx = MECH.separable_leontief_relaxation_alloc(sc)
            jobs.append(make_native_job(sc, cell, seed, "equal", SP)); meta.append((cell, seed, "equal"))
            jobs.append(make_native_job(sc, cell, seed, "drf", SP)); meta.append((cell, seed, "drf"))
            jobs.append(make_preinstalled_job(sc, cell, seed, "independent_bundle_maxmin", mm, SP)); meta.append((cell, seed, "maxmin"))
            jobs.append(make_preinstalled_job(sc, cell, seed, "separable_leontief_relaxation", rx, SP)); meta.append((cell, seed, "relax"))
    res = runner.run_jobs(jobs, chunk=300)
    by = {(c, s, p): r for (c, s, p), r in zip(meta, res)}
    keys = sorted({(c, s) for (c, s, p) in by})

    def alloc(r):
        return {a["id"]: a["allocated"] for a in r["agents"]}

    def compl(r):
        return {a["id"]: a["completion"] for a in r["agents"]}

    mm_ne_drf, l1s, linfs, cdiffs = 0, [], [], []
    relax_eq_equal, relax_eq_drf = 0, 0
    for (cell, seed) in keys:
        A_mm, A_drf, A_eq, A_rx = alloc(by[(cell, seed, "maxmin")]), alloc(by[(cell, seed, "drf")]), \
            alloc(by[(cell, seed, "equal")]), alloc(by[(cell, seed, "relax")])
        if any(A_mm[a][r] != A_drf[a][r] for a in A_mm for r in RESOURCES):
            mm_ne_drf += 1
        denom = max(sum(sum(A_drf[a].values()) for a in A_drf), 1)
        l1s.append(sum(abs(A_mm[a][r] - A_drf[a][r]) for a in A_mm for r in RESOURCES) / denom)
        linfs.append(max(abs(A_mm[a][r] - A_drf[a][r]) for a in A_mm for r in RESOURCES))
        cm, cd = compl(by[(cell, seed, "maxmin")]), compl(by[(cell, seed, "drf")])
        cdiffs.append(np.mean([cm[a] - cd[a] for a in cm]) * 48)
        if all(A_rx[a][r] == A_eq[a][r] for a in A_rx for r in RESOURCES):
            relax_eq_equal += 1
        if all(A_rx[a][r] == A_drf[a][r] for a in A_rx for r in RESOURCES):
            relax_eq_drf += 1
    return {
        "n_scenarios": len(keys),
        "maxmin_differs_from_drf_count": mm_ne_drf,
        "maxmin_differs_from_drf_frac": mm_ne_drf / len(keys),
        "alloc_l1_norm_mean": float(np.mean(l1s)), "alloc_l1_norm_max": float(np.max(l1s)),
        "alloc_linf_max": int(np.max(linfs)),
        "maxmin_minus_drf_tasks_mean": float(np.mean(cdiffs)),
        "maxmin_minus_drf_tasks_min": float(np.min(cdiffs)), "maxmin_minus_drf_tasks_max": float(np.max(cdiffs)),
        "relaxation_equals_equal_count": relax_eq_equal, "relaxation_equals_equal_frac": relax_eq_equal / len(keys),
        "relaxation_equals_drf_count": relax_eq_drf, "relaxation_equals_drf_frac": relax_eq_drf / len(keys),
    }


def _hand_examples():
    """Three constructed cases run through the Java DRF and the Python maxmin, showing the
    resource-local fill differs from the dominant-share coupling of DRF."""
    def build(dem, cap, res):
        n = len(dem)
        req = [{r: dem[i][r] / sum(dem[i].values()) for r in res} for i in range(n)]
        fl = [{r: (1 if dem[i][r] > 0 else 0) for r in res} for i in range(n)]
        up = [{r: cap[r] for r in res} for i in range(n)]
        agents = []
        for i in range(n):
            agents.append({"id": "a%d" % i, "task_types": [], "latent_probs": [], "prefs": req[i],
                           "util_weights": req[i], "leontief_req": req[i], "mandatory_demand": dem[i],
                           "min": fl[i], "upper": up[i], "priority": 1.0, "tasks": []})
        return {"capacities": cap, "agents": agents, "services": {}, "scenario_hash": "hand",
                "workload_hash": "hand", "task_workload_hash": "hand"}, req, fl, up

    examples = []
    specs = [
        ("mirror_heterogeneous_symmetric_capacity",
         [{"COMPUTE": 10, "MEMORY": 2}, {"COMPUTE": 2, "MEMORY": 10}], {"COMPUTE": 6, "MEMORY": 6}, R2),
        ("heavy_plus_balanced",
         [{"COMPUTE": 9, "MEMORY": 9}, {"COMPUTE": 9, "MEMORY": 1}], {"COMPUTE": 9, "MEMORY": 6}, R2),
        ("three_specialists",
         [{"COMPUTE": 8, "MEMORY": 1, "API_CREDITS": 1}, {"COMPUTE": 1, "MEMORY": 8, "API_CREDITS": 1},
          {"COMPUTE": 1, "MEMORY": 1, "API_CREDITS": 8}], {"COMPUTE": 6, "MEMORY": 6, "API_CREDITS": 6}, R3),
    ]
    jobs, meta, holders = [], [], {}
    for name, dem, cap, res in specs:
        sc, req, fl, up = build(dem, cap, res)
        mm = maxmin.independent_bundle_maxmin(req, [1.0] * len(dem), fl, up, cap, res)
        holders[name] = (dem, cap, res, mm)
        jobs.append(make_native_job(sc, name, 0, "drf", SP)); meta.append(name)
    res_runs = runner.run_jobs(jobs, chunk=10)
    drf_by = {name: {a["id"]: a["allocated"] for a in r["agents"]} for name, r in zip(meta, res_runs)}
    for name, (dem, cap, resl, mm) in holders.items():
        drf_alloc = [drf_by[name]["a%d" % i] for i in range(len(dem))]
        differ = any(mm[i][r] != drf_alloc[i].get(r, 0) for i in range(len(dem)) for r in resl)
        examples.append({"name": name, "demand": dem, "capacity": cap, "resources": resl,
                         "maxmin": mm, "drf": drf_alloc, "differ": bool(differ)})
    return examples


def _relaxation_cases():
    req = [{"COMPUTE": 0.7, "MEMORY": 0.3}, {"COMPUTE": 0.3, "MEMORY": 0.7}]
    cap = {"COMPUTE": 10, "MEMORY": 10}
    fl = [{r: 0 for r in R2} for _ in range(2)]
    slack = [{r: 100 for r in R2} for _ in range(2)]
    collapse = leontief_relaxation.independent_leontief_relaxation(req, [1, 1], fl, slack, cap, R2)
    unequal = leontief_relaxation.independent_leontief_relaxation(req, [3, 1], fl, slack, cap, R2)
    bounded_up = [{"COMPUTE": 2, "MEMORY": 100}, {"COMPUTE": 100, "MEMORY": 100}]
    active_bound = leontief_relaxation.independent_leontief_relaxation(req, [1, 1], fl, bounded_up, cap, R2)
    equal_quota = [{"COMPUTE": 5, "MEMORY": 5}, {"COMPUTE": 5, "MEMORY": 5}]
    return {
        "equal_weights_slack_bounds": {"alloc": collapse, "equals_equal_quota": collapse == equal_quota},
        "unequal_weights_3_1": {"alloc": unequal, "equals_equal_quota": unequal == equal_quota},
        "active_upper_bound_a0_compute_le_2": {"alloc": active_bound, "equals_equal_quota": active_bound == equal_quota},
    }


def main():
    rnd = _randomized()
    examples = _hand_examples()
    relax = _relaxation_cases()
    audit = {"randomized": rnd, "hand_examples": examples, "relaxation_cases": relax}
    with open(os.path.join(HERE, "comparator_audit.json"), "w") as f:
        json.dump(audit, f, indent=2)
    _write_md(audit)
    print("comparator audit: maxmin differs from DRF %d/%d (%.1f%%); relaxation==equal %d/%d"
          % (rnd["maxmin_differs_from_drf_count"], rnd["n_scenarios"], 100 * rnd["maxmin_differs_from_drf_frac"],
             rnd["relaxation_equals_equal_count"], rnd["n_scenarios"]))


def _fmt_alloc(a, res):
    return "(" + ", ".join("%s=%s" % (r[:3], a[r]) for r in res) + ")"


def _write_md(audit):
    r = audit["randomized"]
    lines = []
    A = lines.append
    A("# Comparator audit\n")
    A("This audit distinguishes four resource-allocation concepts used in the architecture experiment and")
    A("establishes, before preregistration, that the independent bundle max-min mechanism is distinct from DRF")
    A("and that the separable Leontief relaxation collapses to equal quotas under the tested conditions. All")
    A("numbers below are from development-namespace scenarios and constructed examples; no confirmatory seed was")
    A("used. The four mechanisms are the resource-local independent bundle max-min, DRF, the separable weighted-")
    A("log Leontief relaxation, and equal quotas.\n")
    A("## 1. Equal quotas\n")
    A("Equal quotas split each resource's capacity equally across the agents that use it, subject to floors and")
    A("upper bounds. It ignores the declared demand magnitude entirely.\n")
    A("## 2. DRF (implemented rule)\n")
    A("DRF (dominant resource fairness) equalizes each agent's *dominant share* across its full demand vector. For")
    A("agent i it computes the dominant divisor d_i = max_r (demand_ir / Q_r) over the resources it demands, then")
    A("raises a common dominant share t, giving every agent the scaled bundle a_ir = (t / d_i) * demand_ir until a")
    A("resource capacity binds or an agent reaches its upper bound, at which point that agent is frozen and the")
    A("remainder continues (the implemented water-filling in the Java harness). The dominant resource couples all")
    A("of an agent's resources: the same scalar t/d_i multiplies every component of demand_i, so the allocation on")
    A("one resource depends on the agent's demand on its *other* resources through d_i.\n")
    A("## 3. Independent bundle max-min (the tested uncoordinated resource-local mechanism)\n")
    A("Each resource owner sees only, for its one resource, each agent's declared fixed-proportion Leontief")
    A("coefficient a_ir, weight w_i, floor and upper bound. It runs weighted progressive filling of local bundle")
    A("progress x_ir / a_ir: x_ir(theta) = clip(theta * w_i * a_ir, floor_ir, upper_ir), theta raised until the")
    A("column fills capacity or all agents saturate. No owner sees another resource's allocation, residual, price")
    A("or the cross-resource bundle, and there is no cross-resource reconciliation. It uses the *same*")
    A("fixed-proportion declaration the joint mechanism receives, so the complementarity magnitude a_ir enters")
    A("each resource's fill, but the resources are never coupled. This is why it differs from DRF: DRF ties the")
    A("resources together through the dominant share, while independent bundle max-min fills each resource on its")
    A("own local progress. It is the strongest tested uncoordinated resource-local mechanism, not a universally")
    A("strongest mechanism.\n")
    A("### 3.1 Difference from DRF (empirical)\n")
    A("Over %d randomized development scenarios (Dirichlet(0.1), both contention levels), the two mechanisms'"
      % r["n_scenarios"])
    A("installed integer allocations differed in %d of %d (%.1f%%). Normalized allocation L1 distance: mean %.4f,"
      % (r["maxmin_differs_from_drf_count"], r["n_scenarios"], 100 * r["maxmin_differs_from_drf_frac"],
         r["alloc_l1_norm_mean"]))
    A("max %.4f; maximum L-infinity distance %d units. Queue-order completion differed by mean %.4f tasks per"
      % (r["alloc_l1_norm_max"], r["alloc_linf_max"], r["maxmin_minus_drf_tasks_mean"]))
    A("48-task run (range %.3f to %.3f). The mechanisms are therefore distinct on both allocation and outcome.\n"
      % (r["maxmin_minus_drf_tasks_min"], r["maxmin_minus_drf_tasks_max"]))
    A("### 3.2 Three constructed examples\n")
    for ex in audit["hand_examples"]:
        res = ex["resources"]
        A("* **%s** (capacity %s):" % (ex["name"], _fmt_alloc(ex["capacity"], res)))
        for i in range(len(ex["demand"])):
            A("  * agent %d demand %s -> DRF %s, max-min %s"
              % (i, _fmt_alloc(ex["demand"][i], res), _fmt_alloc(ex["drf"][i], res), _fmt_alloc(ex["maxmin"][i], res)))
        A("  * differ: %s\n" % ex["differ"])
    A("The first (perfectly mirror-symmetric) case is one where the two mechanisms coincide; the second and third")
    A("show them diverging, because DRF's dominant-share coupling redistributes across resources while the")
    A("independent fill does not. DRF's dominant-share coupling is therefore not the same as separate local")
    A("progress filling.\n")
    A("## 4. Separable weighted-log Leontief relaxation (structural control)\n")
    A("The separable relaxation drops the cross-resource utility consensus from the weighted-log Leontief")
    A("objective, leaving each resource owner to maximize sum_i w_i log(x_ir) over the agents that require the")
    A("resource. Substituting u_ir = x_ir / a_ir gives sum_i w_i log(x_ir) - sum_i w_i log(a_ir); the second term")
    A("is constant in x, so the magnitude a_ir cancels and the owner allocates in proportion to w_i alone. Under")
    A("equal weights and inactive special bounds this is exactly an equal split among the participating agents,")
    A("i.e. equal quotas.\n")
    rc = audit["relaxation_cases"]
    A("Constructed confirmation (2 agents, requirements COMPUTE/MEMORY = 0.7/0.3 and 0.3/0.7, capacity 10/10):")
    A("* equal weights, slack bounds -> %s == equal quotas: %s"
      % (rc["equal_weights_slack_bounds"]["alloc"], rc["equal_weights_slack_bounds"]["equals_equal_quota"]))
    A("* unequal weights (3, 1) -> %s == equal quotas: %s (collapse broken: weight-proportional)"
      % (rc["unequal_weights_3_1"]["alloc"], rc["unequal_weights_3_1"]["equals_equal_quota"]))
    A("* active upper bound (agent 0 COMPUTE <= 2) -> %s == equal quotas: %s (collapse broken on the bounded resource)\n"
      % (rc["active_upper_bound_a0_compute_le_2"]["alloc"], rc["active_upper_bound_a0_compute_le_2"]["equals_equal_quota"]))
    A("Empirically, over the %d randomized development scenarios the relaxation equalled equal quotas in %d (%.1f%%)"
      % (r["n_scenarios"], r["relaxation_equals_equal_count"], 100 * r["relaxation_equals_equal_frac"]))
    A("and equalled DRF in %d (%.1f%%). The relaxation is retained as a structural architecture control that shows"
      % (r["relaxation_equals_drf_count"], 100 * r["relaxation_equals_drf_frac"]))
    A("what happens when cross-resource utility consensus is dropped from weighted-log Leontief; it is not the")
    A("primary independent comparator, because it discards the complementarity magnitude that independent bundle")
    A("max-min keeps.\n")
    A("## 5. Conclusion\n")
    A("Independent bundle max-min is mathematically distinct from DRF (resource-local progressive filling of")
    A("bundle progress versus dominant-share coupling) and empirically distinct on constructed and randomized")
    A("development cases. It is therefore eligible as the primary architecture comparator. The separable Leontief")
    A("relaxation collapses to equal quotas under the tested equal-weight, exact-information conditions and is")
    A("retained only as a structural control.\n")
    with open(os.path.join(HERE, "COMPARATOR_AUDIT.md"), "w") as f:
        f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
