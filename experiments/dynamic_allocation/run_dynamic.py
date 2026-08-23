#!/usr/bin/env python3
"""Dynamic allocation experiment (appendix, solver-level simulation).

Repeated allocation epochs over a prebuilt, agent-targeted event schedule
comparing four commitment policies: unrestricted reoptimization, permanent
accepted-utility floors, time-limited leases, and leases with proportional
shortfall. Continuous solutions are converted to integers with the same
capacity-preserving rounding the platform uses. Promised and solver floor maps
are kept separately, and floors are audited against the rounded allocation. This
is a solver-level simulation: it does not install runtime contracts or drive the
runtime clock.
"""
import argparse
import csv
import hashlib
import json
import os
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "scripts"))
sys.path.insert(0, os.path.join(ROOT, "experiments", "platform_mediation"))

import joint_solver  # noqa: E402
from lib import archetypes  # noqa: E402
from lib.capacity_rounding import capacity_preserving_round  # noqa: E402
from lib.seeds import derive_seed  # noqa: E402

RESOURCES = archetypes.RESOURCES
POLICIES = ["reoptimize", "permanent_floors", "leases", "leases_shortfall"]
TASK_TYPES = ["research", "code_review", "doc_processing", "monitoring"]
LEASE_LEN = 10
CAP_LOSS = 0.30
PRIORITY_TIERS = [1.0, 2.0, 4.0]
FLOOR_TOL = 1e-6

CFG = {
    "smoke": {"n_pool": 8, "n_base": 4, "epochs": 20, "seeds": 5, "tasks_per_agent": 4},
    "full": {"n_pool": 10, "n_base": 5, "epochs": 100, "seeds": 100, "tasks_per_agent": 5},
}


def sample_task_types(tpa, *parts):
    return [TASK_TYPES[derive_seed(*parts, "task", k) % len(TASK_TYPES)] for k in range(tpa)]


def profile_from_types(task_types):
    mand = {r: 0 for r in RESOURCES}
    for tt in task_types:
        fp = archetypes.archetype_footprint(tt, include_optional=False)
        for r in RESOURCES:
            mand[r] += fp[r]
    total = sum(mand[r] for r in RESOURCES)
    prefs = [mand[r] / total if total > 0 else 0.0 for r in RESOURCES]
    used = [mand[r] > 0 for r in RESOURCES]
    return {"task_types": task_types, "mand": [mand[r] for r in RESOURCES],
            "prefs": prefs, "used": used}


def build_pool(seed, cfg):
    pool = []
    for i in range(cfg["n_pool"]):
        tt = sample_task_types(cfg["tasks_per_agent"], "dyn_pool", seed, i)
        prof = profile_from_types(tt)
        pool.append({"id": "a%d" % i, "profile": prof,
                     "priority": PRIORITY_TIERS[derive_seed("dyn_prio", seed, i) % len(PRIORITY_TIERS)]})
    return pool


def base_capacities(pool, base_idx):
    caps = {}
    for j, r in enumerate(RESOURCES):
        dem = sum(pool[i]["profile"]["mand"][j] for i in base_idx)
        caps[r] = max(1, int(round(1.15 * dem)))
    return caps


def event_schedule(seed, cfg):
    epochs = cfg["epochs"]
    rng = np.random.default_rng(derive_seed("dyn_events", seed))
    events = [[] for _ in range(epochs)]
    cl, cr = int(round(epochs * 0.3)), int(round(epochs * 0.6))
    events[cl].append({"type": "capacity_loss"})
    events[cr].append({"type": "capacity_restore"})
    slots = [e for e in range(1, epochs) if e not in (cl, cr)]
    rng.shuffle(slots)
    cursor = [0]

    def take():
        e = slots[cursor[0] % len(slots)]
        cursor[0] += 1
        return e

    for agent in range(cfg["n_base"], cfg["n_pool"]):
        events[take()].append({"type": "arrival", "agent": agent})
    for agent in rng.choice(range(cfg["n_base"]), size=min(max(1, epochs // 12), cfg["n_base"]),
                            replace=False):
        events[take()].append({"type": "departure", "agent": int(agent)})
    for agent in rng.choice(range(cfg["n_pool"]), size=min(max(1, epochs // 8), cfg["n_pool"]),
                            replace=False):
        events[take()].append({"type": "preference_change", "agent": int(agent)})
    schedule_hash = hashlib.sha256(
        json.dumps(events, sort_keys=True).encode()).hexdigest()[:16]
    return events, schedule_hash


def solve(active, caps, profiles, solver_floors):
    n, m = len(active), len(RESOURCES)
    W = [profiles[i]["prefs"] for i in active]
    c = [10.0 + profiles[i]["priority"] for i in active]
    Q = [caps[r] for r in RESOURCES]
    mins = [[1 if profiles[i]["used"][j] else 0 for j in range(m)] for i in active]
    ideals = [[int(round(caps[RESOURCES[j]] * 0.55)) if profiles[i]["used"][j] else 0
               for j in range(m)] for i in active]
    data = {"n_agents": n, "n_resources": m, "preferences": W, "priority_weights": c,
            "capacities": Q, "minimums": mins, "ideals": ideals}
    fl = [solver_floors.get(i) for i in active]
    if any(f is not None for f in fl):
        data["utility_floors"] = fl
    res = joint_solver.solve_joint_allocation(data)
    return res, W, mins, ideals, Q


def proportional_shortfall(active, caps, profiles, promised, iters=22):
    lo, hi = 0.0, 1.0
    for _ in range(iters):
        mid = (lo + hi) / 2
        scaled = {i: promised[i] * mid for i in promised}
        res, _, _, _, _ = solve(active, caps, profiles, scaled)
        if res["status"] in ("optimal", "optimal_inaccurate"):
            lo = mid
        else:
            hi = mid
    return lo


def discrete_utils(alloc, W):
    return [float(np.dot(W[k], alloc[k])) for k in range(len(alloc))]


def simulate_policy(policy, pool, cfg, events, schedule_hash, seed, epoch_writer):
    m = len(RESOURCES)
    profiles = {i: dict(pool[i]["profile"], priority=pool[i]["priority"]) for i in range(cfg["n_pool"])}
    for i in profiles:
        profiles[i]["priority"] = pool[i]["priority"]
    base_idx = list(range(cfg["n_base"]))
    active = list(base_idx)
    arrived = set()
    pending = []
    base_caps = base_capacities(pool, base_idx)
    caps = dict(base_caps)
    promised = {}
    solver_floors = {}
    lease_expiry = {}
    prev_util = {}
    prev_alloc = {}
    waiting_time = {}
    just_changed = set()

    agg = defaultdict_counters()

    for e in range(cfg["epochs"]):
        just_changed = set()
        epoch_events = []
        for ev in events[e]:
            typ = ev["type"]
            target = ev.get("agent")
            noop = False
            if typ == "capacity_loss":
                caps = {r: max(1, int(round(base_caps[r] * (1 - CAP_LOSS)))) for r in RESOURCES}
            elif typ == "capacity_restore":
                caps = dict(base_caps)
            elif typ == "arrival":
                if target not in active and target not in pending and target not in arrived:
                    pending.append(target)
                    waiting_time[target] = 0
                else:
                    noop = True
            elif typ == "departure":
                if target in active and len(active) > 1:
                    active = [x for x in active if x != target]
                    promised.pop(target, None); solver_floors.pop(target, None)
                    lease_expiry.pop(target, None); prev_util.pop(target, None); prev_alloc.pop(target, None)
                else:
                    noop = True
            elif typ == "preference_change":
                if target in active:
                    new_types = sample_task_types(cfg["tasks_per_agent"], "dyn_prefchange", seed, e, target)
                    prof = profile_from_types(new_types)
                    prof["priority"] = pool[target]["priority"]
                    profiles[target] = prof
                    promised.pop(target, None); solver_floors.pop(target, None); lease_expiry.pop(target, None)
                    just_changed.add(target)
                else:
                    noop = True
            if noop:
                agg["noop_events"] += 1
            entry = {"type": typ, "noop": noop}
            if target is not None:
                entry["target"] = "a%d" % target
            epoch_events.append(entry)

        for i in list(lease_expiry.keys()):
            if lease_expiry[i] <= e and i in active:
                agg["lease_expiries"] += 1

        active_solver_floors = {}
        active_promised = {}
        if policy != "reoptimize":
            for i in active:
                if i in promised and (policy == "permanent_floors" or lease_expiry.get(i, 0) > e):
                    active_promised[i] = promised[i]
                    active_solver_floors[i] = solver_floors[i]

        res, W, mins, ideals, Q = solve(active, caps, profiles, active_solver_floors)
        original_status = res["status"]
        infeasible = res["status"] not in ("optimal", "optimal_inaccurate")
        original_floors_infeasible = bool(infeasible and active_promised)
        fallback_required = False
        shortfall_scale = 1.0
        if infeasible and active_promised:
            agg["infeasible_floor_epochs"] += 1
            if policy == "leases_shortfall":
                shortfall_scale = proportional_shortfall(active, caps, profiles, active_promised)
                agg["shortfall_epochs"] += 1
                agg["shortfall_scale_sum"] += shortfall_scale
                scaled = {i: active_promised[i] * shortfall_scale for i in active_promised}
                res, W, mins, ideals, Q = solve(active, caps, profiles, scaled)
                active_solver_floors = scaled
            if res["status"] not in ("optimal", "optimal_inaccurate"):
                res, W, mins, ideals, Q = solve(active, caps, profiles, {})
                active_solver_floors = {}
                fallback_required = True

        cont = [[max(0.0, v) for v in row] for row in res["allocations"]]
        disc = capacity_preserving_round(cont, mins, ideals, Q)

        admitted_now = []
        for cand in list(pending):
            trial = active + [cand]
            tres, tW, tmins, tideals, tQ = solve(trial, caps, profiles, active_solver_floors)
            if tres["status"] in ("optimal", "optimal_inaccurate"):
                active = trial
                admitted_now.append(cand); arrived.add(cand)
                agg["admissions"] += 1
                res, W, mins, ideals, Q = tres, tW, tmins, tideals, tQ
                cont = [[max(0.0, v) for v in row] for row in res["allocations"]]
                disc = capacity_preserving_round(cont, mins, ideals, Q)
            else:
                waiting_time[cand] = waiting_time.get(cand, 0) + 1
        for cand in admitted_now:
            pending.remove(cand)

        util = discrete_utils(disc, W)
        per_agent_shortfall = {}
        per_agent_residual = {}
        for k, i in enumerate(active):
            agg["active_floor_denominator"] += 1 if i in active_promised else 0
            if i in active_promised:
                agg["protected_agent_epochs"] += 1
                shortfall = active_promised[i] - util[k]
                if shortfall > FLOOR_TOL:
                    agg["floor_violations"] += 1
                    agg["floor_shortfall_total"] += shortfall
                    per_agent_shortfall[pool[i]["id"]] = shortfall
                if policy == "leases_shortfall" and shortfall_scale < 1.0:
                    scaled_short = active_promised[i] * shortfall_scale - util[k]
                    if scaled_short > FLOOR_TOL:
                        agg["scaled_floor_shortfall_total"] += scaled_short
                        per_agent_residual[pool[i]["id"]] = scaled_short
        if active_promised:
            agg["active_floor_epochs"] += 1

        for k, i in enumerate(active):
            if i in prev_util and i not in just_changed and i in prev_alloc:
                change = util[k] - prev_util[i]
                agg["incumbent_change_sum"] += change
                agg["incumbent_change_count"] += 1
                agg["worst_incumbent_loss"] = min(agg["worst_incumbent_loss"], change)

        totalcap = sum(caps[r] for r in RESOURCES)
        churn = 0
        for k, i in enumerate(active):
            prev = prev_alloc.get(i, [0] * m)
            churn += sum(abs(disc[k][j] - prev[j]) for j in range(m))
        agg["churn_frac_sum"] += churn / totalcap if totalcap else 0.0

        cap_viol = 0
        for j in range(m):
            if sum(disc[k][j] for k in range(len(active))) > caps[RESOURCES[j]]:
                cap_viol = 1
        agg["capacity_violations"] += cap_viol

        for k, i in enumerate(active):
            if i not in promised and policy != "reoptimize":
                promised[i] = util[k]; solver_floors[i] = util[k]
                if policy in ("leases", "leases_shortfall"):
                    lease_expiry[i] = e + LEASE_LEN
            elif policy in ("leases", "leases_shortfall") and lease_expiry.get(i, 0) <= e and i in promised:
                promised[i] = util[k]; solver_floors[i] = util[k]
                lease_expiry[i] = e + LEASE_LEN
            prev_util[i] = util[k]
            prev_alloc[i] = disc[k]

        epoch_writer({
            "seed": seed, "schedule_hash": schedule_hash, "epoch": e, "policy": policy,
            "events": json.dumps(epoch_events),
            "active": json.dumps([pool[i]["id"] for i in active]),
            "pending": json.dumps([pool[i]["id"] for i in pending]),
            "caps": json.dumps([caps[r] for r in RESOURCES]),
            "promised_floors": json.dumps({pool[i]["id"]: active_promised[i] for i in active_promised}),
            "solver_floors": json.dumps({pool[i]["id"]: active_solver_floors[i] for i in active_solver_floors}),
            "original_solver_status": original_status,
            "original_floors_infeasible": int(original_floors_infeasible),
            "final_solver_status": res["status"],
            "fallback_required": int(fallback_required),
            "shortfall_scale": repr(shortfall_scale),
            "continuous_alloc": json.dumps(cont),
            "discrete_alloc": json.dumps(disc),
            "achieved_utils": json.dumps(util),
            "floor_shortfall_from_promise": json.dumps(per_agent_shortfall),
            "residual_shortfall_from_scaled": json.dumps(per_agent_residual),
            "capacity_violation": cap_viol,
        })

    waits = [waiting_time[a] for a in waiting_time]
    row = {"seed": seed, "policy": policy, "schedule_hash": schedule_hash,
           "admissions": agg["admissions"], "entrants_offered": cfg["n_pool"] - cfg["n_base"],
           "mean_waiting_time": float(np.mean(waits)) if waits else 0.0,
           "protected_agent_epochs": agg["protected_agent_epochs"],
           "active_floor_epochs": agg["active_floor_epochs"],
           "infeasible_floor_epochs": agg["infeasible_floor_epochs"],
           "discrete_floor_violations": agg["floor_violations"],
           "floor_shortfall_total": agg["floor_shortfall_total"],
           "scaled_floor_shortfall_total": agg["scaled_floor_shortfall_total"],
           "mean_floor_shortfall": (agg["floor_shortfall_total"] / agg["floor_violations"]
                                    if agg["floor_violations"] else 0.0),
           "lease_expiries": agg["lease_expiries"],
           "mean_churn_frac": agg["churn_frac_sum"] / cfg["epochs"],
           "mean_incumbent_utility_change": (agg["incumbent_change_sum"] / agg["incumbent_change_count"]
                                             if agg["incumbent_change_count"] else 0.0),
           "worst_incumbent_loss": agg["worst_incumbent_loss"],
           "shortfall_epochs": agg["shortfall_epochs"],
           "mean_shortfall_scale": (agg["shortfall_scale_sum"] / agg["shortfall_epochs"]
                                    if agg["shortfall_epochs"] else 1.0),
           "noop_events": agg["noop_events"], "capacity_violations": agg["capacity_violations"]}
    return row


def defaultdict_counters():
    return {"admissions": 0, "noop_events": 0, "infeasible_floor_epochs": 0,
            "floor_violations": 0, "floor_shortfall_total": 0.0,
            "scaled_floor_shortfall_total": 0.0, "lease_expiries": 0,
            "capacity_violations": 0, "shortfall_epochs": 0, "shortfall_scale_sum": 0.0,
            "churn_frac_sum": 0.0, "incumbent_change_sum": 0.0, "incumbent_change_count": 0,
            "worst_incumbent_loss": 0.0, "protected_agent_epochs": 0,
            "active_floor_epochs": 0, "active_floor_denominator": 0}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--smoke", action="store_true")
    ap.add_argument("--full", action="store_true")
    args = ap.parse_args()
    if args.smoke == args.full:
        ap.error("choose exactly one of --smoke / --full")
    mode = "smoke" if args.smoke else "full"
    cfg = CFG[mode]

    os.makedirs(os.path.join(HERE, "results", "raw"), exist_ok=True)
    os.makedirs(os.path.join(HERE, "tables"), exist_ok=True)
    os.makedirs(os.path.join(HERE, "logs"), exist_ok=True)
    log = []

    def L(msg):
        line = "%s %s" % (time.strftime("%H:%M:%S"), msg)
        print(line, flush=True)
        log.append(line)

    L("Dynamic experiment (appendix simulation) mode=%s seeds=%d epochs=%d" %
      (mode, cfg["seeds"], cfg["epochs"]))
    seeds = [derive_seed("dynamic_seed", i) for i in range(cfg["seeds"])]

    epoch_fields = ["seed", "schedule_hash", "epoch", "policy", "events", "active", "pending",
                    "caps", "promised_floors", "solver_floors", "original_solver_status",
                    "original_floors_infeasible", "final_solver_status", "fallback_required",
                    "shortfall_scale", "continuous_alloc", "discrete_alloc", "achieved_utils",
                    "floor_shortfall_from_promise", "residual_shortfall_from_scaled",
                    "capacity_violation"]
    epoch_path = os.path.join(HERE, "results", "raw", "epochs_%s.csv" % mode)
    epoch_file = open(epoch_path, "w", newline="")
    epoch_csv = csv.DictWriter(epoch_file, fieldnames=epoch_fields)
    epoch_csv.writeheader()

    policy_rows = []
    for si, seed in enumerate(seeds):
        pool = build_pool(seed, cfg)
        events, schedule_hash = event_schedule(seed, cfg)
        for policy in POLICIES:
            row = simulate_policy(policy, pool, cfg, events, schedule_hash, seed, epoch_csv.writerow)
            policy_rows.append(row)
        if (si + 1) % max(1, cfg["seeds"] // 10) == 0:
            L("  completed %d/%d seeds" % (si + 1, cfg["seeds"]))
    epoch_file.close()

    fields = list(policy_rows[0].keys())
    with open(os.path.join(HERE, "results", "raw", "policy_seed_%s.csv" % mode), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(policy_rows)

    count_keys = ["admissions", "protected_agent_epochs", "active_floor_epochs",
                  "infeasible_floor_epochs", "discrete_floor_violations", "lease_expiries",
                  "shortfall_epochs", "noop_events", "capacity_violations"]
    sum_keys = ["floor_shortfall_total", "scaled_floor_shortfall_total"]
    mean_keys = ["mean_waiting_time", "mean_floor_shortfall", "mean_churn_frac",
                 "mean_incumbent_utility_change", "mean_shortfall_scale"]
    worst_keys = ["worst_incumbent_loss"]
    epochs = cfg["epochs"]
    n_seeds = cfg["seeds"]
    agg = []
    table_rows = []
    for policy in POLICIES:
        rows = [r for r in policy_rows if r["policy"] == policy]
        rec = {"policy": policy, "n_seeds": len(rows), "epochs_per_seed": epochs,
               "seed_epochs_denominator": len(rows) * epochs}
        flat = {"policy": policy, "n_seeds": len(rows)}
        for k in count_keys:
            total = int(sum(r[k] for r in rows))
            rec[k] = {"mean_per_seed": total / len(rows), "total": total,
                      "rate_per_seed_epoch": total / (len(rows) * epochs)}
            flat[k + "_mean_per_seed"] = total / len(rows)
            flat[k + "_total"] = total
        for k in sum_keys:
            total = float(sum(r[k] for r in rows))
            rec[k] = {"mean_per_seed": total / len(rows), "total": total}
            flat[k + "_mean_per_seed"] = total / len(rows)
            flat[k + "_total"] = total
        for k in mean_keys:
            rec[k] = float(np.mean([r[k] for r in rows]))
            flat[k] = rec[k]
        for k in worst_keys:
            rec[k] = float(np.min([r[k] for r in rows]))
            flat[k] = rec[k]
        agg.append(rec)
        table_rows.append(flat)
    with open(os.path.join(HERE, "tables", "policy_means_%s.csv" % mode), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(table_rows[0].keys()))
        w.writeheader()
        w.writerows(table_rows)

    cap_viol = sum(r["capacity_violations"] for r in policy_rows)
    summary = {"mode": mode, "seeds": n_seeds, "epochs": epochs, "policies": POLICIES,
               "lease_len": LEASE_LEN, "capacity_loss_frac": CAP_LOSS,
               "n_rows": len(policy_rows), "n_epoch_rows": len(policy_rows) * epochs,
               "denominator_note": ("counts are per policy across all seeds; mean_per_seed divides by "
                                    "n_seeds; rate_per_seed_epoch divides by n_seeds*epochs"),
               "capacity_violations_total": cap_viol, "aggregate": agg}
    with open(os.path.join(HERE, "results", "summary_%s.json" % mode), "w") as f:
        json.dump(summary, f, indent=2)
    with open(os.path.join(HERE, "results", "summary.json"), "w") as f:
        json.dump(summary, f, indent=2)
    L("Done: %d policy-seed rows, %d epoch rows; capacity_violations=%d"
      % (len(policy_rows), len(policy_rows) * cfg["epochs"], cap_viol))
    L("RUN COMPLETE: mode=%s policy_seed_rows=%d epoch_rows=%d expected_epoch_rows=%d" %
      (mode, len(policy_rows), len(policy_rows) * cfg["epochs"],
       len(POLICIES) * cfg["seeds"] * cfg["epochs"]))
    with open(os.path.join(HERE, "logs", "dynamic_%s.log" % mode), "w") as f:
        f.write("\n".join(log) + "\n")


if __name__ == "__main__":
    main()
