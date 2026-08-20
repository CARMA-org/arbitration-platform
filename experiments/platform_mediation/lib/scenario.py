"""Deterministic scenario construction for the platform-mediation sweep.

A scenario is policy-independent: the same agents, declarations, tasks, capacities
and priorities are handed to every policy for a given (cell, seed). Declarations
follow a fixed prespecified procedure derived from each agent's archetype workload
and the workload regime; they never see test-task outcomes.
"""
import numpy as np

from .archetypes import (RESOURCES, ARCHETYPES, archetype_footprint, SERVICE_FOOTPRINT)
from .seeds import derive_seed

PRIORITY_TIERS = [1.0, 2.0, 4.0]   # exogenous operator policy inputs


def assign_archetypes(regime, n):
    if regime == "identical":
        return ["research"] * n
    if regime == "complementary":
        order = ["research", "code_review", "monitoring", "doc_processing"]
        return [order[i % len(order)] for i in range(n)]
    base = ["research", "code_review", "doc_processing", "monitoring"]
    return [base[i % len(base)] for i in range(n)]


def pref_vector(regime, arc, rng):
    fp = archetype_footprint(arc, include_optional=True)
    vec = np.array([float(fp[r]) for r in RESOURCES])
    if vec.sum() <= 0:
        vec = np.ones(len(RESOURCES))
    if regime == "identical":
        w = vec
    elif regime == "nearly_specialized":
        w = np.power(vec, 8.0)
    elif regime == "broad_heterogeneous":
        base = vec / vec.sum()
        noise = rng.dirichlet(np.ones(len(RESOURCES)) * 2.0)
        w = 0.6 * base + 0.4 * noise
    elif regime == "complementary":
        w = np.power(vec, 3.0)
    else:
        raise ValueError("unknown regime " + regime)
    return w / w.sum()


def _tasks_for_agent(arc, regime, contention, seed, agent_idx, tpa):
    rng = derive_rng(regime, contention, seed, "tasks", agent_idx)
    a = ARCHETYPES[arc]
    tasks = []
    for k in range(tpa):
        jitter = float(rng.uniform(-0.05, 0.05))
        q = min(1.0, max(0.0, a["base_quality"] + jitter))
        tasks.append({
            "id": "a%d-t%d" % (agent_idx, k),
            "mandatory": list(a["mandatory"]),
            "optional": list(a["optional"]),
            "quality": q,
            "refinement": a["refinement"],
            "sloMs": a["slo_ms"],
        })
    return tasks


def derive_rng(*parts):
    return np.random.default_rng(derive_seed(*parts))


def base_scenario(regime, contention_name, contention_ratio, seed, cfg):
    n = cfg["n_agents"]
    tpa = cfg["tasks_per_agent"]
    upper_frac = cfg["upper_frac"]
    arcs = assign_archetypes(regime, n)

    # per-task mandatory-only footprint drives capacity sizing so that mandatory
    # demand alone exceeds supply by the contention ratio.
    mand_fp = [archetype_footprint(a, include_optional=False) for a in arcs]
    demand = {r: 0.0 for r in RESOURCES}
    for i in range(n):
        for r in RESOURCES:
            demand[r] += tpa * mand_fp[i][r]
    capacities = {}
    for r in RESOURCES:
        capacities[r] = max(1, int(round(demand[r] / contention_ratio)))

    agents = []
    used_services = set()
    for i in range(n):
        arc = arcs[i]
        prng = derive_rng(regime, contention_name, seed, "prefs", i)
        w = pref_vector(regime, arc, prng)
        prefs = {RESOURCES[j]: float(w[j]) for j in range(len(RESOURCES))}
        full_fp = archetype_footprint(arc, include_optional=True)
        mn, up = {}, {}
        for r in RESOURCES:
            uses = full_fp[r] > 0
            mn[r] = 1 if uses else 0
            up[r] = int(round(capacities[r] * upper_frac)) if uses else 0
        tier = PRIORITY_TIERS[derive_seed(regime, contention_name, seed, "prio", i) % len(PRIORITY_TIERS)]
        tasks = _tasks_for_agent(arc, regime, contention_name, seed, i, tpa)
        for t in tasks:
            used_services.update(t["mandatory"])
            used_services.update(t["optional"])
        agents.append({
            "id": "a%d" % i, "archetype": arc, "prefs": prefs,
            "min": mn, "upper": up, "priority": tier, "tasks": tasks,
        })

    services = {s: 100000 for s in sorted(used_services)}
    return {"capacities": capacities, "agents": agents, "services": services,
            "regime": regime, "contention": contention_name}


def make_job(scenario, cell, seed, policy, gamma, solver_python, execute):
    return {
        "cell": cell, "seed": int(seed), "policy": policy, "gamma": float(gamma),
        "solverPython": solver_python, "execute": bool(execute),
        "capacities": scenario["capacities"], "services": scenario["services"],
        "agents": [{
            "id": a["id"], "prefs": a["prefs"], "min": a["min"], "upper": a["upper"],
            "priority": a["priority"], "tasks": a["tasks"],
        } for a in scenario["agents"]],
    }
