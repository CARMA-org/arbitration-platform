#!/usr/bin/env python3
"""Dynamic allocation experiment.

Runs repeated allocation epochs with prespecified events (arrivals, departures,
preference changes, lease expirations, a 30% capacity loss and a later capacity
restoration) and compares four commitment policies:

  1. reoptimize            full reoptimization every epoch, no protected status quo
  2. permanent_floors      permanent accepted-utility floors
  3. leases                time-limited leases (floors until expiry, then free)
  4. leases_shortfall      leases plus a proportional-shortfall rule when a
                           capacity loss makes all floors infeasible

Commitment floors are lower bounds on declared LINEAR utility (the supported
representation here); they are not claimed to apply to any other utility model.
Allocations are produced by the same convex joint solver used by
ConvexJointArbitrator (extended with optional LINEAR utility floors). Task
outcomes are measured through the canonical Java runtime via the harness's
precomputed-allocation path. The allocator never reads task outcomes.

The purpose is to test whether leases are a useful middle ground between
permanent floors and unrestricted reoptimization; it does not assume they are.
"""
import argparse
import csv
import json
import os
import subprocess
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "experiments", "platform_mediation"))
sys.path.insert(0, os.path.join(ROOT, "scripts"))

import joint_solver  # noqa: E402
from lib import scenario, archetypes  # noqa: E402
from lib.seeds import derive_seed  # noqa: E402

RESOURCES = archetypes.RESOURCES
POLICIES = ["reoptimize", "permanent_floors", "leases", "leases_shortfall"]
LEASE_LEN = 10
CAP_LOSS = 0.30
HARNESS = "org.carma.arbitration.experiment.PlatformMediationHarness"

CFG = {
    "smoke": {"n_pool": 8, "n_base": 4, "epochs": 20, "seeds": 5, "tasks_per_agent": 4, "chunk": 300},
    "full": {"n_pool": 10, "n_base": 5, "epochs": 100, "seeds": 100, "tasks_per_agent": 5, "chunk": 400},
}


def classpath():
    with open(os.path.join(ROOT, "cp.txt")) as f:
        deps = f.read().strip()
    return os.path.join(ROOT, "target", "classes") + os.pathsep + deps


def build_pool(seed, cfg):
    """A fixed candidate pool of agents with declarations and task queues."""
    rng = np.random.default_rng(derive_seed("dynamic_pool", seed))
    arcs = ["research", "code_review", "doc_processing", "monitoring"]
    pool = []
    for i in range(cfg["n_pool"]):
        arc = arcs[i % len(arcs)]
        prng = np.random.default_rng(derive_seed("dynamic_prefs", seed, i))
        w = scenario.pref_vector("broad_heterogeneous", arc, prng)
        full_fp = archetypes.archetype_footprint(arc, include_optional=True)
        a = archetypes.ARCHETYPES[arc]
        tasks = []
        trng = np.random.default_rng(derive_seed("dynamic_tasks", seed, i))
        for k in range(cfg["tasks_per_agent"]):
            jitter = float(trng.uniform(-0.05, 0.05))
            tasks.append({"id": "a%d-t%d" % (i, k), "mandatory": list(a["mandatory"]),
                          "optional": list(a["optional"]),
                          "quality": min(1.0, max(0.0, a["base_quality"] + jitter)),
                          "refinement": a["refinement"], "sloMs": a["slo_ms"]})
        pool.append({
            "id": "a%d" % i, "archetype": arc,
            "prefs": [float(w[j]) for j in range(len(RESOURCES))],
            "mandatory_fp": [full_fp[r] if r in [rr for rr in RESOURCES] else 0 for r in RESOURCES],
            "priority": scenario.PRIORITY_TIERS[derive_seed("dynamic_prio", seed, i) % 3],
            "tasks": tasks, "used": [archetypes.archetype_footprint(arc)[r] > 0 for r in RESOURCES],
        })
    return pool


def base_capacities(pool, active_idx, cfg):
    caps = {}
    for j, r in enumerate(RESOURCES):
        dem = sum(cfg["tasks_per_agent"] * archetypes.archetype_footprint(pool[i]["archetype"],
                  include_optional=False)[r] for i in active_idx)
        caps[r] = max(1, int(round(1.15 * dem)))
    return caps


def event_schedule(seed, epochs):
    rng = np.random.default_rng(derive_seed("dynamic_events", seed))
    events = [[] for _ in range(epochs)]
    cl, cr = int(round(epochs * 0.3)), int(round(epochs * 0.6))
    events[cl].append(("capacity_loss",))
    events[cr].append(("capacity_restore",))
    others = [e for e in range(1, epochs) if e not in (cl, cr)]
    def pick(k):
        k = min(k, len(others))
        return set(int(x) for x in rng.choice(others, size=k, replace=False)) if k else set()
    arrivals = pick(max(1, epochs // 8))
    departures = pick(max(1, epochs // 12))
    prefchanges = pick(max(1, epochs // 8))
    for e in arrivals:
        events[e].append(("arrival",))
    for e in departures:
        events[e].append(("departure",))
    for e in prefchanges:
        events[e].append(("preference_change",))
    return events


def solve(pool, active, caps, prefs_override, floors):
    ids = [pool[i]["id"] for i in active]
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
    res = joint_solver.solve_joint_allocation(data)
    return res, ids, W, mins, ideals


def utilities(alloc, W):
    return [float(np.dot(W[i], alloc[i])) for i in range(len(alloc))]


def simulate_policy(policy, pool, cfg, events):
    m = len(RESOURCES)
    active = list(range(cfg["n_base"]))
    waiting = list(range(cfg["n_base"], cfg["n_pool"]))
    pending = []
    base_caps = base_capacities(pool, active, cfg)
    caps = dict(base_caps)
    prefs_override = {}
    floors = {}          # agent idx -> accepted utility floor
    lease_expiry = {}    # agent idx -> epoch when lease expires
    prev_alloc = {}      # agent idx -> vector
    admitted_epoch = {i: 0 for i in active}
    arrival_epoch = {}
    waiting_time = {}
    epoch_jobs = []      # (epoch, active_ids, alloc_map, tasks, caps) for Java execution
    per_epoch = []

    for e in range(cfg["epochs"]):
        for ev in events[e]:
            if ev[0] == "capacity_loss":
                caps = {r: max(1, int(round(base_caps[r] * (1 - CAP_LOSS)))) for r in RESOURCES}
            elif ev[0] == "capacity_restore":
                caps = dict(base_caps)
            elif ev[0] == "arrival":
                if waiting:
                    cand = waiting.pop(0)
                    pending.append(cand)
                    arrival_epoch[cand] = e
                    waiting_time[cand] = 0
            elif ev[0] == "departure":
                if len(active) > 1:
                    dep = active[-1]
                    active = [x for x in active if x != dep]
                    floors.pop(dep, None)
                    lease_expiry.pop(dep, None)
                    prev_alloc.pop(dep, None)
            elif ev[0] == "preference_change":
                if active:
                    idx = active[e % len(active)]
                    prng = np.random.default_rng(derive_seed("dynamic_prefchange", e, idx))
                    prefs_override[idx] = list(scenario.pref_vector(
                        "broad_heterogeneous", pool[idx]["archetype"], prng))
                    floors.pop(idx, None)          # re-declare -> re-accept
                    lease_expiry.pop(idx, None)

        # Active floors depend on the policy.
        active_floors = {}
        if policy != "reoptimize":
            for i in active:
                if i in floors:
                    if policy == "permanent_floors" or lease_expiry.get(i, 0) > e:
                        active_floors[i] = floors[i]

        # Maintain incumbents.
        res, ids, W, mins, ideals = solve(pool, active, caps, prefs_override, active_floors)
        infeasible = res["status"] not in ("optimal", "optimal_inaccurate")
        commitment_infeasible = 1 if (infeasible and active_floors) else 0
        if infeasible:
            if policy == "leases_shortfall" and active_floors:
                s = proportional_shortfall(pool, active, caps, prefs_override, active_floors)
                scaled = {i: active_floors[i] * s for i in active_floors}
                res, ids, W, mins, ideals = solve(pool, active, caps, prefs_override, scaled)
            if res["status"] not in ("optimal", "optimal_inaccurate"):
                res, ids, W, mins, ideals = solve(pool, active, caps, prefs_override, {})
        alloc = np.floor(np.maximum(np.asarray(res["allocations"], float), 0)).astype(int)

        # Try to admit pending agents (only if it keeps incumbents' floors feasible).
        admitted_now = []
        for cand in list(pending):
            trial = active + [cand]
            tres = solve(pool, trial, caps, prefs_override, active_floors)
            if tres[0]["status"] in ("optimal", "optimal_inaccurate"):
                active = trial
                admitted_now.append(cand)
                admitted_epoch[cand] = e
                res, ids, W, mins, ideals = tres
                alloc = np.floor(np.maximum(np.asarray(res["allocations"], float), 0)).astype(int)
            else:
                waiting_time[cand] = waiting_time.get(cand, 0) + 1
        for cand in admitted_now:
            pending.remove(cand)

        util = utilities(alloc, W)
        for k, i in enumerate(active):
            # New admissions accept their granted utility as a floor (leased/permanent).
            if i not in floors and (policy != "reoptimize"):
                floors[i] = util[k]
                if policy in ("leases", "leases_shortfall"):
                    lease_expiry[i] = e + LEASE_LEN
            elif policy in ("leases", "leases_shortfall") and lease_expiry.get(i, 0) <= e and i in floors:
                # lease expired: renew floor to current granted utility, new lease
                floors[i] = util[k]
                lease_expiry[i] = e + LEASE_LEN

        # Churn and binding commitments.
        churn = 0
        totalcap = sum(caps[r] for r in RESOURCES)
        for k, i in enumerate(active):
            prev = prev_alloc.get(i, np.zeros(m, int))
            churn += int(np.abs(alloc[k] - prev).sum())
            prev_alloc[i] = alloc[k]
        binding = 0
        for k, i in enumerate(active):
            if i in active_floors and abs(util[k] - active_floors[i]) <= 1e-3 * max(1.0, active_floors[i]):
                binding += 1

        # Build a Java execution job for this epoch (measures task outcomes).
        alloc_map = {}
        agents_spec = []
        for k, i in enumerate(active):
            b = {RESOURCES[j]: int(alloc[k][j]) for j in range(m)}
            alloc_map[pool[i]["id"]] = b
            agents_spec.append({
                "id": pool[i]["id"],
                "prefs": {RESOURCES[j]: (prefs_override.get(i, pool[i]["prefs"]))[j] for j in range(m)},
                "min": {RESOURCES[j]: mins[k][j] for j in range(m)},
                "upper": {RESOURCES[j]: ideals[k][j] for j in range(m)},
                "priority": pool[i]["priority"], "tasks": pool[i]["tasks"]})
        used_services = sorted({s for i in active for t in pool[i]["tasks"]
                                for s in t["mandatory"] + t["optional"]})
        job = {"cell": "%s@e%d" % (policy, e), "seed": 0, "policy": "given", "gamma": 1.0,
               "execute": True, "capacities": {r: caps[r] for r in RESOURCES},
               "services": {s: 100000 for s in used_services},
               "allocation": alloc_map, "agents": agents_spec}
        epoch_jobs.append(job)

        cap_alloc_violation = 0
        for j in range(m):
            if alloc[:, j].sum() > caps[RESOURCES[j]]:
                cap_alloc_violation = 1
        per_epoch.append({
            "epoch": e, "n_active": len(active), "churn_units": churn,
            "churn_frac": churn / totalcap if totalcap else 0.0,
            "binding_commitments": binding, "commitment_infeasible": commitment_infeasible,
            "capacity_violation": cap_alloc_violation,
            "active_ids": [pool[i]["id"] for i in active]})

    return per_epoch, epoch_jobs, waiting_time, arrival_epoch, admitted_epoch


def proportional_shortfall(pool, active, caps, prefs_override, floors, iters=18):
    lo, hi = 0.0, 1.0
    for _ in range(iters):
        mid = (lo + hi) / 2
        scaled = {i: floors[i] * mid for i in floors}
        res, _, _, _, _ = solve(pool, active, caps, prefs_override, scaled)
        if res["status"] in ("optimal", "optimal_inaccurate"):
            lo = mid
        else:
            hi = mid
    return lo


def run_java(jobs, chunk):
    payload_all = []
    cp = classpath()
    results = []
    for start in range(0, len(jobs), chunk):
        batch = jobs[start:start + chunk]
        payload = "\n".join(json.dumps(j) for j in batch) + "\n"
        proc = subprocess.run(["java", "-cp", cp, HARNESS], input=payload,
                              capture_output=True, text=True, cwd=ROOT)
        lines = [ln for ln in proc.stdout.splitlines() if ln.strip()]
        if len(lines) != len(batch):
            raise RuntimeError("java returned %d for %d; stderr:\n%s"
                               % (len(lines), len(batch), proc.stderr[-1500:]))
        results.extend(json.loads(ln) for ln in lines)
    return results


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
    log = []

    def L(msg):
        line = "%s %s" % (time.strftime("%H:%M:%S"), msg)
        print(line, flush=True)
        log.append(line)

    L("Dynamic experiment mode=%s seeds=%d epochs=%d" % (mode, cfg["seeds"], cfg["epochs"]))
    seeds = [derive_seed("dynamic_seed", i) for i in range(cfg["seeds"])]

    epoch_rows = []
    policy_rows = []
    for si, seed in enumerate(seeds):
        pool = build_pool(seed, cfg)
        events = event_schedule(seed, cfg["epochs"])
        for policy in POLICIES:
            per_epoch, jobs, waiting_time, arrival_epoch, admitted_epoch = simulate_policy(
                policy, pool, cfg, events)
            outcomes = run_java(jobs, cfg["chunk"])
            # completion per agent per epoch
            comp_by_epoch = []
            qual_by_epoch = []
            agent_comp = {}   # (epoch)-> {id:completion}
            for e, res in enumerate(outcomes):
                cmap = {a["id"]: a["completion"] for a in res["agents"]}
                qmap = {a["id"]: a["quality"] for a in res["agents"]}
                agent_comp[e] = cmap
                comp_by_epoch.append(np.mean(list(cmap.values())) if cmap else 0.0)
                qual_by_epoch.append(np.mean(list(qmap.values())) if qmap else 0.0)
            # worst incumbent task-outcome loss across consecutive epochs
            worst_loss = 0.0
            for e in range(1, len(outcomes)):
                prev = agent_comp[e - 1]
                cur = agent_comp[e]
                common = set(prev) & set(cur)
                for aid in common:
                    worst_loss = min(worst_loss, cur[aid] - prev[aid])
            # admission / waiting for entrants (agents that arrived)
            entrants = list(arrival_epoch.keys())
            admitted = [i for i in entrants if i in admitted_epoch]
            waits = [waiting_time.get(i, 0) for i in entrants]
            for pe in per_epoch:
                row = {"mode": mode, "seed": seed, "policy": policy}
                row.update(pe)
                row["mean_completion"] = comp_by_epoch[pe["epoch"]]
                row["mean_quality"] = qual_by_epoch[pe["epoch"]]
                row.pop("active_ids", None)
                epoch_rows.append(row)
            policy_rows.append({
                "seed": seed, "policy": policy,
                "task_completion": float(np.mean(comp_by_epoch)),
                "task_quality": float(np.mean(qual_by_epoch)),
                "entrant_admission_rate": (len(admitted) / len(entrants)) if entrants else 1.0,
                "mean_waiting_time": float(np.mean(waits)) if waits else 0.0,
                "worst_incumbent_loss": worst_loss,
                "mean_churn_frac": float(np.mean([pe["churn_frac"] for pe in per_epoch])),
                "mean_binding_commitments": float(np.mean([pe["binding_commitments"] for pe in per_epoch])),
                "commitment_infeasibility": int(sum(pe["commitment_infeasible"] for pe in per_epoch)),
                "capacity_violations": int(sum(pe["capacity_violation"] for pe in per_epoch)),
                "mean_utilization": float(np.mean([
                    o["utilization"] and np.mean(list(o["utilization"].values())) or 0.0
                    for o in outcomes])),
            })
        if (si + 1) % max(1, cfg["seeds"] // 10) == 0:
            L("  completed %d/%d seeds" % (si + 1, cfg["seeds"]))

    raw_dir = os.path.join(HERE, "results", "raw")
    with open(os.path.join(raw_dir, "epochs_%s.csv" % mode), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(epoch_rows[0].keys()))
        w.writeheader()
        w.writerows(epoch_rows)
    with open(os.path.join(raw_dir, "policy_seed_%s.csv" % mode), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(policy_rows[0].keys()))
        w.writeheader()
        w.writerows(policy_rows)

    # Aggregate per policy (mean over seeds) with paired bootstrap vs reoptimize.
    from lib.stats import paired_diff_ci
    metrics = ["task_completion", "task_quality", "entrant_admission_rate",
               "mean_waiting_time", "worst_incumbent_loss", "mean_churn_frac",
               "mean_binding_commitments", "commitment_infeasibility", "mean_utilization"]
    by_policy = {p: [r for r in policy_rows if r["policy"] == p] for p in POLICIES}
    agg = []
    for p in POLICIES:
        rec = {"policy": p, "n_seeds": len(by_policy[p])}
        for mkey in metrics:
            rec[mkey] = float(np.mean([r[mkey] for r in by_policy[p]]))
        rec["capacity_violations_total"] = int(sum(r["capacity_violations"] for r in by_policy[p]))
        agg.append(rec)
    paired = []
    seeds_sorted = sorted(seeds)
    for p in POLICIES:
        if p == "reoptimize":
            continue
        for mkey in metrics:
            a = [next(r[mkey] for r in by_policy[p] if r["seed"] == s) for s in seeds_sorted]
            b = [next(r[mkey] for r in by_policy["reoptimize"] if r["seed"] == s) for s in seeds_sorted]
            ci = paired_diff_ci(a, b)
            paired.append({"comparison": "%s_minus_reoptimize" % p, "metric": mkey,
                           "mean_diff": ci["mean"], "ci_lo": ci["lo"], "ci_hi": ci["hi"],
                           "n_pairs": ci["n"]})
    os.makedirs(os.path.join(HERE, "tables"), exist_ok=True)
    with open(os.path.join(HERE, "tables", "policy_means_%s.csv" % mode), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(agg[0].keys()))
        w.writeheader()
        w.writerows(agg)
    with open(os.path.join(HERE, "tables", "paired_vs_reoptimize_%s.csv" % mode), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(paired[0].keys()))
        w.writeheader()
        w.writerows(paired)

    summary = {"mode": mode, "seeds": cfg["seeds"], "epochs": cfg["epochs"],
               "policies": POLICIES, "lease_len": LEASE_LEN, "capacity_loss_frac": CAP_LOSS,
               "capacity_violations_total": int(sum(r["capacity_violations"] for r in policy_rows)),
               "aggregate": agg}
    with open(os.path.join(HERE, "results", "summary_%s.json" % mode), "w") as f:
        json.dump(summary, f, indent=2)
    os.makedirs(os.path.join(HERE, "logs"), exist_ok=True)
    with open(os.path.join(HERE, "logs", "dynamic_%s.log" % mode), "w") as f:
        f.write("\n".join(log) + "\n")
    L("Done: %d policy-seed rows; capacity_violations=%d" %
      (len(policy_rows), summary["capacity_violations_total"]))


if __name__ == "__main__":
    main()
