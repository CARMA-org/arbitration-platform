#!/usr/bin/env python3
"""Declaration-calibration-and-drift confirmatory driver (frozen protocol v1).

Applied to the mechanism selected by the architecture experiment under the frozen
adaptive carrier rule (read from DRIFT_CARRIER_DECISION.json). For every scenario seed,
drift level delta in {0, .25, .5, .75, 1} and contention level, a shared physical
scenario (realized queue with common random numbers across delta, frozen capacity from
baseline latent expected demand, frozen policy/declaration-independent bounds) is
executed under nine arms: equal, and DRF and the carrier under each of four declaration
sources (stale calibration, refreshed calibration, latent-distribution oracle, exact
execution-queue oracle). If the carrier is the distributed price solver, four central
Leontief technical-reference arms are added. Allocation uses the possibly-stale
declaration; execution always runs the true realized queue. All arms are enforced
through the canonical Java runtime. Resumable and canonically finalized like the
architecture driver.
"""
import argparse
import json
import os
import time

import numpy as np

import oqlib  # noqa: F401  (import first: sets up sys.path for pilotlib, lib)
from pilotlib import measures, local_opt
from lib import runner
from lib.archetypes import RESOURCES
from lib.scenario import mandatory_footprint

from oqlib import seeds_oq as S
from oqlib import drift_scenario as DS
from oqlib import declarations as DEC
from oqlib import mechanisms as MECH
from oqlib.jobs import make_native_job, make_preinstalled_job, scenario_arrays
from oqlib.central_ref import central_leontief_reference
from oqlib.execute import RawStore

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "results", "drift_v1")
LOGS = os.path.join(HERE, "logs")
TASK_TYPES = ["research", "code_review", "doc_processing", "monitoring"]
DECLS = ["stale_calibration", "refreshed_calibration", "latent_distribution_oracle", "execution_queue_oracle"]

# carrier name -> ("native", harness_policy) or ("python", mechanism_key)
CARRIER_KIND = {
    "central_joint_leontief": ("native", "joint_leontief"),
    "central_joint_leontief_diagnostic": ("native", "joint_leontief"),
    "independent_bundle_maxmin": ("python", "maxmin"),
    "separable_leontief_relaxation": ("python", "relax"),
    "distributed_price_leontief": ("python", "distributed"),
}

SCEN_FIELDS = [
    "cell", "delta", "contention", "contention_ratio", "seed", "task_workload_hash",
    "capacity_by_resource", "realized_contention_by_resource", "active_resource_count",
    "drift_source_total", "changed_identities_total",
    "task_mixture_tv_from_baseline_mean", "mand_demand_tv_mean_pairwise",
    "task_entropy_mean", "cross_agent_dissimilarity",
    "staleness_error_mean", "calibration_error_mean", "latent_oracle_error_mean",
]
RUN_FIELDS = [
    "cell", "delta", "contention", "seed", "arm", "policy_kind", "declaration_source",
    "solver_status", "feasible", "fallback_used",
    "queue_order_completion_mean", "queue_order_tasks_per_run",
    "best_subset_completion_mean", "best_subset_tasks_per_run",
    "frac_zero_qo", "frac_zero_bs", "capacity_utilization", "unused_installed_total",
    "capacity_violation", "bound_violation", "alloc_latency_ms",
]
AGENT_FIELDS = [
    "cell", "delta", "contention", "seed", "arm", "policy_kind", "declaration_source", "agent",
    "queue_order_completion", "best_subset_completion", "best_subset_count",
    "mandatory_failures", "allocated", "declared_demand", "realized_demand",
]
DECL_FIELDS = [
    "delta", "contention", "seed", "agent", "source", "declared_demand",
    "staleness_error", "calibration_error", "latent_oracle_error",
]
DIST_FIELDS = [
    "cell", "delta", "contention", "seed", "declaration_source",
    "central_status", "central_objective", "distributed_objective", "rel_obj_gap",
    "distributed_converged", "iterations", "capacity_residual", "bound_residual",
    "installed_alloc_l1_norm", "installed_outcome_disagreements", "technically_valid",
]
INFEAS_FIELDS = ["cell", "seed", "arm", "solver_status", "failure_reason"]

TABLES = {"scenarios": SCEN_FIELDS, "runs": RUN_FIELDS, "agents": AGENT_FIELDS,
          "declarations": DECL_FIELDS, "distributed": DIST_FIELDS, "infeasible": INFEAS_FIELDS}
UNIT_KEYS = {"scenarios": ("cell", "seed"), "runs": ("cell", "seed", "arm"),
             "agents": ("cell", "seed", "arm", "agent"),
             "declarations": ("delta", "contention", "seed", "agent", "source"),
             "distributed": ("cell", "seed", "declaration_source"),
             "infeasible": ("cell", "seed", "arm")}


def load_config():
    with open(os.path.join(HERE, "config", "drift_v1.json")) as f:
        return json.load(f)


def load_carrier():
    p = os.path.join(HERE, "DRIFT_CARRIER_DECISION.json")
    with open(p) as f:
        return json.load(f)["selected_carrier"]


def _demand_vec(dem):
    return np.array([dem[r] for r in RESOURCES], float)


def _l1(a, b):
    return float(np.abs(_demand_vec(a) - _demand_vec(b)).sum())


def _res_demand_vec(md):
    tot = sum(md[r] for r in RESOURCES)
    return [md[r] / tot if tot > 0 else 0.0 for r in RESOURCES]


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--solver-python", default=os.environ.get("SOLVER_PYTHON", "python3"))
    ap.add_argument("--block", type=int, default=25)
    ap.add_argument("--dev-seeds", type=int, default=0)
    ap.add_argument("--carrier", default=None, help="override carrier (dev only)")
    args = ap.parse_args(argv)
    cfg = load_config()
    carrier = args.carrier or load_carrier()
    if carrier not in CARRIER_KIND:
        raise SystemExit("unknown carrier: %s" % carrier)
    kind, ckey = CARRIER_KIND[carrier]
    is_distributed = (carrier == "distributed_price_leontief")

    out = OUT
    namespace = cfg["seed_namespace"]
    n_seeds = cfg["n_seeds"]
    if args.dev_seeds > 0:
        namespace = S.NS_DRIFT_DEV
        n_seeds = args.dev_seeds
        out = os.environ.get("OQ_DEV_OUT", os.path.join(HERE, "results", "_dev_drift_%s" % carrier))
    os.makedirs(LOGS, exist_ok=True)
    os.makedirs(out, exist_ok=True)
    sp = args.solver_python
    n, tpa = cfg["n_agents"], cfg["tasks_per_agent"]
    deltas = cfg["delta_levels"]
    contention = cfg["contention"]

    seeds = S.scenario_seeds(namespace, n_seeds)
    S.assert_disjoint_scenario_seeds(namespace, n_seeds)

    units = []
    for delta in deltas:
        for cname in contention:
            cell = "delta%.2f__%s" % (delta, cname)
            for seed in seeds:
                units.append((cell, seed, delta, cname))
    store = RawStore(out, TABLES, UNIT_KEYS)
    todo = [(c, s) for (c, s, d, cn) in units if not store.is_done((c, s))]
    unit_info = {(c, s): (d, cn) for (c, s, d, cn) in units}

    t0 = time.time()
    log_lines = []

    def log(m):
        line = "%s %s" % (time.strftime("%H:%M:%S"), m)
        print(line, flush=True)
        log_lines.append(line)

    n_arms = 9 + (4 if is_distributed else 0)
    log("drift v1: carrier=%s kind=%s solver=%s seeds=%d deltas=%d cont=%d arms=%d units_total=%d todo=%d"
        % (carrier, kind, sp, len(seeds), len(deltas), len(contention), n_arms, len(units), len(todo)))

    for start in range(0, len(todo), args.block):
        block = todo[start:start + args.block]
        jobs, jobmeta, precomp = [], [], {}
        for (cell, seed) in block:
            delta, cname = unit_info[(cell, seed)]
            ratio = contention[cname]
            phys = DS.physical(namespace, seed, delta, cname, ratio, n)
            scen_by_src = {src: DS.build_scenario(phys, src, n) for src in DECLS}
            # subset tables from the shared realized queue
            subs = {}
            for i in range(n):
                fps = [mandatory_footprint(t) for t in phys["exec_types"][i]]
                quals = [t["quality"] for t in scen_by_src[DECLS[0]]["agents"][i]["tasks"]]
                subs[i] = local_opt.build_subset_table(fps, quals, RESOURCES)
            carrier_alloc = {}
            dist_extra = {}
            for src in DECLS:
                sc = scen_by_src[src]
                if kind == "python":
                    if ckey == "maxmin":
                        carrier_alloc[src] = MECH.independent_bundle_maxmin_alloc(sc)
                    elif ckey == "relax":
                        carrier_alloc[src] = MECH.separable_leontief_relaxation_alloc(sc)
                    elif ckey == "distributed":
                        alloc, A, obj, info = MECH.distributed_price_leontief_full(sc)
                        carrier_alloc[src] = alloc
                        R, Q, mn, up, c = scenario_arrays(sc)
                        cref = central_leontief_reference(R, Q, mn, up, c)
                        dist_extra[src] = (A, obj, info, cref)
            precomp[(cell, seed)] = (phys, scen_by_src, subs, carrier_alloc, dist_extra)

            # jobs: equal once (declaration-independent), then per-declaration drf + carrier
            eq_sc = scen_by_src["execution_queue_oracle"]
            jobs.append(make_native_job(eq_sc, cell, seed, "equal", sp)); jobmeta.append((cell, seed, "equal", "equal", ""))
            for src in DECLS:
                sc = scen_by_src[src]
                jobs.append(make_native_job(sc, cell, seed, "drf", sp))
                jobmeta.append((cell, seed, "drf_%s" % src, "drf", src))
                arm = "carrier_%s" % src
                if kind == "native":
                    jobs.append(make_native_job(sc, cell, seed, ckey, sp))
                else:
                    jobs.append(make_preinstalled_job(sc, cell, seed, carrier, carrier_alloc[src], sp))
                jobmeta.append((cell, seed, arm, "carrier", src))
                if is_distributed:
                    jobs.append(make_native_job(sc, cell, seed, "joint_leontief", sp))
                    jobmeta.append((cell, seed, "central_ref_%s" % src, "central_ref", src))
        results = runner.run_jobs(jobs, chunk=cfg["chunk"])
        rby = {(c_, s_, a_): r for (c_, s_, a_, pk, src), r in zip(jobmeta, results)}
        meta_by = {(c_, s_, a_): (pk, src) for (c_, s_, a_, pk, src) in jobmeta}

        for (cell, seed) in block:
            delta, cname = unit_info[(cell, seed)]
            phys, scen_by_src, subs, carrier_alloc, dist_extra = precomp[(cell, seed)]
            rows = {t: [] for t in TABLES}
            # declaration errors (per agent): compare demand vectors
            latent_dem = [DEC.estimate_from_latent(phys["mixtures"][i]) for i in range(n)]
            exec_dem = [DEC.estimate_from_execution(phys["exec_types"][i]) for i in range(n)]
            stale_dem = [DEC.estimate_from_history(phys["baseline_cal"][i]) for i in range(n)]
            refr_dem = [DEC.estimate_from_history(phys["refreshed_cal"][i]) for i in range(n)]
            stale_err, calib_err, latent_err = [], [], []
            for i in range(n):
                stale_err.append(_l1(stale_dem[i], latent_dem[i]))
                calib_err.append(_l1(refr_dem[i], latent_dem[i]))
                latent_err.append(_l1(latent_dem[i], exec_dem[i]))
                for src, dem in (("stale_calibration", stale_dem[i]), ("refreshed_calibration", refr_dem[i]),
                                 ("latent_distribution_oracle", latent_dem[i]), ("execution_queue_oracle", exec_dem[i])):
                    rows["declarations"].append({
                        "delta": delta, "contention": cname, "seed": seed, "agent": "a%d" % i, "source": src,
                        "declared_demand": json.dumps({r: dem[r] for r in RESOURCES}),
                        "staleness_error": _l1(stale_dem[i], latent_dem[i]),
                        "calibration_error": _l1(refr_dem[i], latent_dem[i]),
                        "latent_oracle_error": _l1(latent_dem[i], exec_dem[i]),
                    })
            # drift/dissimilarity metrics
            base_mix = phys["p"]
            task_tv = np.mean([measures.tv(phys["mixtures"][i], base_mix[i]) for i in range(n)])
            realized_md = [DS.realized_demand(phys, i) for i in range(n)]
            res_vecs = [_res_demand_vec(md) for md in realized_md]
            mand_tv = measures.mean_pairwise_tv(res_vecs)
            entropy = np.mean([measures.normalized_entropy(phys["mixtures"][i]) for i in range(n)])
            cross = measures.mean_distance_to_centroid(res_vecs)
            cap = phys["capacity"]
            tot_real = {r: sum(realized_md[i][r] for i in range(n)) for r in RESOURCES}
            realized_contention = {r: (tot_real[r] / cap[r] if cap[r] > 0 else 0.0) for r in RESOURCES}
            active = sum(1 for r in RESOURCES if tot_real[r] > 0)
            rows["scenarios"].append({
                "cell": cell, "delta": delta, "contention": cname, "contention_ratio": contention[cname],
                "seed": seed, "task_workload_hash": scen_by_src[DECLS[0]]["task_workload_hash"],
                "capacity_by_resource": json.dumps(cap),
                "realized_contention_by_resource": json.dumps(realized_contention),
                "active_resource_count": active,
                "drift_source_total": int(sum(phys["drift_src"])),
                "changed_identities_total": int(sum(phys["changed"])),
                "task_mixture_tv_from_baseline_mean": float(task_tv),
                "mand_demand_tv_mean_pairwise": float(mand_tv), "task_entropy_mean": float(entropy),
                "cross_agent_dissimilarity": float(cross),
                "staleness_error_mean": float(np.mean(stale_err)),
                "calibration_error_mean": float(np.mean(calib_err)),
                "latent_oracle_error_mean": float(np.mean(latent_err)),
            })
            installed = {}
            for (c_, s_, arm) in [(cell, seed, a) for a in
                                  ["equal"] + sum([["drf_%s" % s, "carrier_%s" % s] +
                                   (["central_ref_%s" % s] if is_distributed else []) for s in DECLS], [])]:
                r = rby[(cell, seed, arm)]
                pk, src = meta_by[(cell, seed, arm)]
                if not r.get("feasible", False):
                    rows["infeasible"].append({"cell": cell, "seed": seed, "arm": arm,
                                               "solver_status": r.get("solver_status", ""),
                                               "failure_reason": r.get("failure_reason") or r.get("error") or ""})
                    continue
                qo, bs, per_agent = [], [], {}
                unused_total = 0
                for a in r["agents"]:
                    i = int(a["id"][1:])
                    alloc = a["allocated"]
                    cnt, _, _, _ = local_opt.select_from_table(subs[i], alloc, RESOURCES)
                    qoc = a["completion"]
                    qo.append(qoc); bs.append(cnt / tpa)
                    unused_total += sum(a.get("unused", {}).values())
                    per_agent[a["id"]] = (qoc, cnt, alloc)
                    rows["agents"].append({
                        "cell": cell, "delta": delta, "contention": cname, "seed": seed, "arm": arm,
                        "policy_kind": pk, "declaration_source": src, "agent": a["id"],
                        "queue_order_completion": qoc, "best_subset_completion": cnt / tpa, "best_subset_count": cnt,
                        "mandatory_failures": a.get("mandatory_failures", 0), "allocated": json.dumps(alloc),
                        "declared_demand": json.dumps(sc_declared(scen_by_src, src, i)),
                        "realized_demand": json.dumps({r: realized_md[i][r] for r in RESOURCES}),
                    })
                installed[arm] = per_agent
                rows["runs"].append({
                    "cell": cell, "delta": delta, "contention": cname, "seed": seed, "arm": arm,
                    "policy_kind": pk, "declaration_source": src, "solver_status": r.get("solver_status", ""),
                    "feasible": True, "fallback_used": r.get("fallback_used", False),
                    "queue_order_completion_mean": float(np.mean(qo)),
                    "queue_order_tasks_per_run": float(np.mean(qo)) * n * tpa,
                    "best_subset_completion_mean": float(np.mean(bs)),
                    "best_subset_tasks_per_run": float(np.mean(bs)) * n * tpa,
                    "frac_zero_qo": float(np.mean(np.array(qo) <= 1e-12)),
                    "frac_zero_bs": float(np.mean(np.array(bs) <= 1e-12)),
                    "capacity_utilization": r["capacity_utilization"], "unused_installed_total": unused_total,
                    "capacity_violation": r["capacity_violation"], "bound_violation": r["bound_violation"],
                    "alloc_latency_ms": r["allocation_latency_ms"],
                })
            if is_distributed:
                for src in DECLS:
                    A, obj, info, cref = dist_extra[src]
                    cobj = cref.get("objective_value")
                    gap = abs(obj - cobj) / max(abs(cobj), 1e-9) if cobj is not None else ""
                    da = installed.get("carrier_%s" % src, {})
                    ca = installed.get("central_ref_%s" % src, {})
                    il1 = disagree = ""
                    if da and ca:
                        Ad = np.array([[da[a][2][r] for r in RESOURCES] for a in sorted(da)])
                        Ac = np.array([[ca[a][2][r] for r in RESOURCES] for a in sorted(ca)])
                        il1 = float(np.abs(Ad - Ac).sum() / max(Ac.sum(), 1.0))
                        disagree = int(sum(1 for a in da if abs(da[a][0] - ca[a][0]) > 1e-12))
                    rows["distributed"].append({
                        "cell": cell, "delta": delta, "contention": cname, "seed": seed, "declaration_source": src,
                        "central_status": cref.get("status", ""), "central_objective": cobj if cobj is not None else "",
                        "distributed_objective": obj, "rel_obj_gap": gap,
                        "distributed_converged": info["converged"], "iterations": info["iterations"],
                        "capacity_residual": info["capacity_residual"], "bound_residual": info["bound_residual"],
                        "installed_alloc_l1_norm": il1, "installed_outcome_disagreements": disagree,
                        "technically_valid": bool(info["capacity_residual"] <= 1e-7 and info["bound_residual"] <= 1e-7),
                    })
            store.append((cell, seed), rows)
        log("block %d-%d done (%.1fs)" % (start, start + len(block), time.time() - t0))

    counts = store.finalize()
    log("finalized raw: %s" % counts)

    import csv as _csv
    with open(os.path.join(out, "raw", "runs.csv")) as f:
        run_rows = list(_csv.DictReader(f))
    with open(os.path.join(out, "raw", "infeasible.csv")) as f:
        infeas_rows = list(_csv.DictReader(f))
    summary = {
        "experiment": "declaration_drift", "version": "v1", "carrier": carrier, "carrier_kind": kind,
        "is_distributed_carrier": is_distributed, "n_seeds": len(seeds), "deltas": deltas,
        "contention": list(contention), "n_arms_per_unit": n_arms,
        "feasible_runs": len(run_rows), "infeasible_runs": len(infeas_rows),
        "capacity_violations_total": sum(int(r["capacity_violation"]) for r in run_rows),
        "bound_violations_total": sum(int(r["bound_violation"]) for r in run_rows),
        "fallback_used_total": sum(1 for r in run_rows if str(r["fallback_used"]).lower() == "true"),
        "bootstrap_seed": cfg["bootstrap_seed"], "n_bootstrap": cfg["n_bootstrap"],
        "seed_namespace": cfg["seed_namespace"], "co_primary_cells": cfg["co_primary_cells"],
    }
    with open(os.path.join(out, "summary.json"), "w") as f:
        json.dump(summary, f, indent=2)
    with open(os.path.join(LOGS, "run_declaration_drift_v1.log"), "w") as f:
        f.write("\n".join(log_lines) + "\n")
    log("summary: feasible=%d infeasible=%d capviol=%d bndviol=%d fallback=%d"
        % (len(run_rows), len(infeas_rows), summary["capacity_violations_total"],
           summary["bound_violations_total"], summary["fallback_used_total"]))


def sc_declared(scen_by_src, src, i):
    if src not in scen_by_src:      # the equal arm is declaration-independent
        return {}
    return scen_by_src[src]["agents"][i]["mandatory_demand"]


if __name__ == "__main__":
    main()
