#!/usr/bin/env python3
"""Dynamic allocation experiment (appendix, allocation-policy simulation).

Repeated allocation epochs over a prebuilt, agent-targeted event schedule
(arrivals, departures, preference changes, a capacity loss and restoration, and
lease expirations) comparing four commitment policies: unrestricted
reoptimization, permanent accepted-utility floors, time-limited leases, and
leases with proportional shortfall. Floors are lower bounds on declared linear
utility taken from the installed discrete allocation and verified after integer
rounding. Outcomes are operational (admissions, waiting, commitment
infeasibility, discrete floor violations, churn, incumbent utility change). This
is a solver-level simulation; it does not drive the runtime clock and is not a
runtime-timing validation.
"""
import argparse
import csv
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
from lib.seeds import derive_seed  # noqa: E402

RESOURCES = archetypes.RESOURCES
POLICIES = ["reoptimize", "permanent_floors", "leases", "leases_shortfall"]
ARCS = ["research", "code_review", "doc_processing", "monitoring"]
LEASE_LEN = 10
CAP_LOSS = 0.30
PRIORITY_TIERS = [1.0, 2.0, 4.0]

CFG = {
    "smoke": {"n_pool": 8, "n_base": 4, "epochs": 20, "seeds": 5, "tasks_per_agent": 4},
    "full": {"n_pool": 10, "n_base": 5, "epochs": 100, "seeds": 100, "tasks_per_agent": 5},
}


def normalized_mandatory(arc):
    fp = archetypes.archetype_footprint(arc, include_optional=False)
    total = sum(fp[r] for r in RESOURCES)
    return [fp[r] / total if total > 0 else 0.0 for r in RESOURCES]


def build_pool(seed, cfg):
    pool = []
    for i in range(cfg["n_pool"]):
        arc = ARCS[i % len(ARCS)]
        w = normalized_mandatory(arc)
        pool.append({
            "id": "a%d" % i, "archetype": arc, "arc_index": i % len(ARCS),
            "prefs": w,
            "used": [w[j] > 0 for j in range(len(RESOURCES))],
            "priority": PRIORITY_TIERS[derive_seed("dyn_prio", seed, i) % len(PRIORITY_TIERS)],
            "mand": [cfg["tasks_per_agent"] * archetypes.archetype_footprint(arc, include_optional=False)[r]
                     for r in RESOURCES],
        })
    return pool


def base_capacities(pool, base_idx, cfg):
    caps = {}
    for j, r in enumerate(RESOURCES):
        dem = sum(pool[i]["mand"][j] for i in base_idx)
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
    cursor = 0

    def take():
        nonlocal cursor
        e = slots[cursor % len(slots)]
        cursor += 1
        return e

    for agent in range(cfg["n_base"], cfg["n_pool"]):
        events[take()].append({"type": "arrival", "agent": agent})
    n_dep = max(1, epochs // 12)
    dep_agents = list(rng.choice(range(cfg["n_base"]), size=min(n_dep, cfg["n_base"]), replace=False))
    for agent in dep_agents:
        events[take()].append({"type": "departure", "agent": int(agent)})
    n_pref = max(1, epochs // 8)
    pref_agents = list(rng.choice(range(cfg["n_pool"]), size=min(n_pref, cfg["n_pool"]), replace=False))
    for agent in pref_agents:
        events[take()].append({"type": "preference_change", "agent": int(agent)})
    return events


def solve(pool, active, caps, prefs_override, floors):
    n, m = len(active), len(RESOURCES)
    W = [prefs_override.get(i, pool[i]["prefs"]) for i in active]
    c = [10.0 + pool[i]["priority"] for i in active]
    Q = [caps[r] for r in RESOURCES]
    mins = [[1 if pool[i]["used"][j] else 0 for j in range(m)] for i in active]
    ideals = [[int(round(caps[RESOURCES[j]] * 0.55)) if pool[i]["used"][j] else 0
               for j in range(m)] for i in active]
    data = {"n_agents": n, "n_resources": m, "preferences": W, "priority_weights": c,
            "capacities": Q, "minimums": mins, "ideals": ideals}
    fl = [floors.get(i) for i in active]
    if any(f is not None for f in fl):
        data["utility_floors"] = fl
    return joint_solver.solve_joint_allocation(data), W, mins, ideals


def discrete_utilities(alloc, W):
    return [float(np.dot(W[k], alloc[k])) for k in range(len(alloc))]


def proportional_shortfall(pool, active, caps, prefs_override, floors, iters=20):
    lo, hi = 0.0, 1.0
    for _ in range(iters):
        mid = (lo + hi) / 2
        scaled = {i: floors[i] * mid for i in floors}
        res, _, _, _ = solve(pool, active, caps, prefs_override, scaled)
        if res["status"] in ("optimal", "optimal_inaccurate"):
            lo = mid
        else:
            hi = mid
    return lo


def simulate_policy(policy, pool, cfg, events):
    m = len(RESOURCES)
    base_idx = list(range(cfg["n_base"]))
    active = list(base_idx)
    arrived = set()
    pending = []
    base_caps = base_capacities(pool, base_idx, cfg)
    caps = dict(base_caps)
    prefs_override = {}
    floors = {}
    lease_expiry = {}
    prev_util = {}
    prev_alloc = {}
    arrival_epoch = {}
    waiting_time = {}

    metrics = {"admissions": 0, "noop_events": 0, "commitment_infeasibility": 0,
               "floor_violations": 0, "floor_shortfall": 0.0, "lease_expiries": 0,
               "capacity_violations": 0, "shortfall_epochs": 0, "shortfall_scale_sum": 0.0,
               "churn_frac_sum": 0.0, "binding_sum": 0, "incumbent_change_sum": 0.0,
               "incumbent_change_count": 0, "worst_incumbent_loss": 0.0, "epochs": cfg["epochs"]}

    for e in range(cfg["epochs"]):
        for ev in events[e]:
            typ = ev["type"]
            if typ == "capacity_loss":
                caps = {r: max(1, int(round(base_caps[r] * (1 - CAP_LOSS)))) for r in RESOURCES}
            elif typ == "capacity_restore":
                caps = dict(base_caps)
            elif typ == "arrival":
                agent = ev["agent"]
                if agent not in active and agent not in pending and agent not in arrived:
                    pending.append(agent)
                    arrival_epoch[agent] = e
                    waiting_time[agent] = 0
                else:
                    metrics["noop_events"] += 1
            elif typ == "departure":
                agent = ev["agent"]
                if agent in active and len(active) > 1:
                    active = [x for x in active if x != agent]
                    floors.pop(agent, None)
                    lease_expiry.pop(agent, None)
                    prev_util.pop(agent, None)
                    prev_alloc.pop(agent, None)
                else:
                    metrics["noop_events"] += 1
            elif typ == "preference_change":
                agent = ev["agent"]
                if agent in active:
                    alt = ARCS[(pool[agent]["arc_index"] + 1) % len(ARCS)]
                    prefs_override[agent] = normalized_mandatory(alt)
                    floors.pop(agent, None)
                    lease_expiry.pop(agent, None)
                else:
                    metrics["noop_events"] += 1

        for i in list(lease_expiry.keys()):
            if lease_expiry[i] <= e and i in active:
                metrics["lease_expiries"] += 1

        active_floors = {}
        if policy != "reoptimize":
            for i in active:
                if i in floors and (policy == "permanent_floors" or lease_expiry.get(i, 0) > e):
                    active_floors[i] = floors[i]

        res, W, mins, ideals = solve(pool, active, caps, prefs_override, active_floors)
        infeasible = res["status"] not in ("optimal", "optimal_inaccurate")
        if infeasible and active_floors:
            metrics["commitment_infeasibility"] += 1
            if policy == "leases_shortfall":
                s = proportional_shortfall(pool, active, caps, prefs_override, active_floors)
                metrics["shortfall_epochs"] += 1
                metrics["shortfall_scale_sum"] += s
                scaled = {i: active_floors[i] * s for i in active_floors}
                res, W, mins, ideals = solve(pool, active, caps, prefs_override, scaled)
                active_floors = scaled
            if res["status"] not in ("optimal", "optimal_inaccurate"):
                res, W, mins, ideals = solve(pool, active, caps, prefs_override, {})
                active_floors = {}

        alloc = np.floor(np.maximum(np.asarray(res["allocations"], float), 0)).astype(int)

        admitted_now = []
        for cand in list(pending):
            trial = active + [cand]
            tres, tW, tmins, tideals = solve(pool, trial, caps, prefs_override, active_floors)
            if tres["status"] in ("optimal", "optimal_inaccurate"):
                active = trial
                admitted_now.append(cand)
                arrived.add(cand)
                metrics["admissions"] += 1
                res, W, mins, ideals = tres, tW, tmins, tideals
                alloc = np.floor(np.maximum(np.asarray(res["allocations"], float), 0)).astype(int)
            else:
                waiting_time[cand] = waiting_time.get(cand, 0) + 1
        for cand in admitted_now:
            pending.remove(cand)

        util = discrete_utilities(alloc, W)

        for k, i in enumerate(active):
            if i in active_floors and util[k] < active_floors[i] - 1e-6:
                metrics["floor_violations"] += 1
                metrics["floor_shortfall"] += float(active_floors[i] - util[k])

        for k, i in enumerate(active):
            if i in prev_util:
                change = util[k] - prev_util[i]
                metrics["incumbent_change_sum"] += change
                metrics["incumbent_change_count"] += 1
                metrics["worst_incumbent_loss"] = min(metrics["worst_incumbent_loss"], change)

        totalcap = sum(caps[r] for r in RESOURCES)
        churn = 0
        for k, i in enumerate(active):
            prev = prev_alloc.get(i, np.zeros(m, int))
            churn += int(np.abs(alloc[k] - prev).sum())
        metrics["churn_frac_sum"] += churn / totalcap if totalcap else 0.0

        for k, i in enumerate(active):
            if i in active_floors and abs(util[k] - active_floors[i]) <= 1e-3 * max(1.0, active_floors[i]):
                metrics["binding_sum"] += 1

        for j in range(m):
            if alloc[:, j].sum() > caps[RESOURCES[j]]:
                metrics["capacity_violations"] += 1

        for k, i in enumerate(active):
            if i not in floors and policy != "reoptimize":
                floors[i] = util[k]
                if policy in ("leases", "leases_shortfall"):
                    lease_expiry[i] = e + LEASE_LEN
            elif policy in ("leases", "leases_shortfall") and lease_expiry.get(i, 0) <= e and i in floors:
                floors[i] = util[k]
                lease_expiry[i] = e + LEASE_LEN
            prev_util[i] = util[k]
            prev_alloc[i] = alloc[k]

    entrant_wait = [waiting_time[a] for a in waiting_time]
    row = {
        "policy": policy,
        "admissions": metrics["admissions"],
        "entrants_offered": cfg["n_pool"] - cfg["n_base"],
        "mean_waiting_time": float(np.mean(entrant_wait)) if entrant_wait else 0.0,
        "commitment_infeasibility": metrics["commitment_infeasibility"],
        "floor_violations": metrics["floor_violations"],
        "floor_shortfall_total": metrics["floor_shortfall"],
        "lease_expiries": metrics["lease_expiries"],
        "mean_churn_frac": metrics["churn_frac_sum"] / metrics["epochs"],
        "binding_commitments": metrics["binding_sum"],
        "mean_incumbent_utility_change": (metrics["incumbent_change_sum"] / metrics["incumbent_change_count"]
                                          if metrics["incumbent_change_count"] else 0.0),
        "worst_incumbent_loss": metrics["worst_incumbent_loss"],
        "shortfall_epochs": metrics["shortfall_epochs"],
        "mean_shortfall_scale": (metrics["shortfall_scale_sum"] / metrics["shortfall_epochs"]
                                 if metrics["shortfall_epochs"] else 1.0),
        "noop_events": metrics["noop_events"],
        "capacity_violations": metrics["capacity_violations"],
    }
    return row


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

    policy_rows = []
    fields = ["seed", "policy", "admissions", "entrants_offered", "mean_waiting_time",
              "commitment_infeasibility", "floor_violations", "floor_shortfall_total",
              "lease_expiries", "mean_churn_frac", "binding_commitments",
              "mean_incumbent_utility_change", "worst_incumbent_loss", "shortfall_epochs",
              "mean_shortfall_scale", "noop_events", "capacity_violations"]
    for si, seed in enumerate(seeds):
        pool = build_pool(seed, cfg)
        events = event_schedule(seed, cfg)
        for policy in POLICIES:
            row = simulate_policy(policy, pool, cfg, events)
            row = {"seed": seed, **row}
            policy_rows.append(row)
        if (si + 1) % max(1, cfg["seeds"] // 10) == 0:
            L("  completed %d/%d seeds" % (si + 1, cfg["seeds"]))

    with open(os.path.join(HERE, "results", "raw", "policy_seed_%s.csv" % mode), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(policy_rows)

    agg = []
    metric_keys = [k for k in fields if k not in ("seed", "policy", "entrants_offered")]
    for policy in POLICIES:
        rows = [r for r in policy_rows if r["policy"] == policy]
        rec = {"policy": policy, "n_seeds": len(rows)}
        for k in metric_keys:
            rec[k] = float(np.mean([r[k] for r in rows]))
        agg.append(rec)
    with open(os.path.join(HERE, "tables", "policy_means_%s.csv" % mode), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(agg[0].keys()))
        w.writeheader()
        w.writerows(agg)

    cap_viol = sum(r["capacity_violations"] for r in policy_rows)
    summary = {"mode": mode, "seeds": cfg["seeds"], "epochs": cfg["epochs"],
               "policies": POLICIES, "lease_len": LEASE_LEN, "capacity_loss_frac": CAP_LOSS,
               "n_rows": len(policy_rows), "capacity_violations_total": cap_viol,
               "aggregate": agg}
    with open(os.path.join(HERE, "results", "summary_%s.json" % mode), "w") as f:
        json.dump(summary, f, indent=2)
    with open(os.path.join(HERE, "results", "summary.json"), "w") as f:
        json.dump(summary, f, indent=2)
    with open(os.path.join(HERE, "logs", "dynamic_%s.log" % mode), "w") as f:
        f.write("\n".join(log) + "\n")
    L("Done: %d policy-seed rows; capacity_violations=%d" % (len(policy_rows), cap_viol))


if __name__ == "__main__":
    main()
