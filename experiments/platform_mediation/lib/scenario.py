"""Deterministic scenario construction for the platform-mediation sweep.

A scenario is policy-independent: the same agents, declarations, tasks,
capacities, and priorities are handed to every policy for a given (cell, seed).
The declaration primitive for every utility family is the agent's normalized
mandatory-demand vector derived from its exact task queue.
"""
import hashlib
import json

import numpy as np

from .archetypes import RESOURCES, ARCHETYPES, archetype_footprint
from .seeds import derive_seed

COMPOSITIONS = ["homogeneous", "mixed_bundle"]
MIXED_ORDER = ["research", "code_review", "doc_processing", "monitoring"]


def assign_archetypes(composition, n):
    if composition == "homogeneous":
        return ["research"] * n
    return [MIXED_ORDER[i % len(MIXED_ORDER)] for i in range(n)]


def derive_rng(*parts):
    return np.random.default_rng(derive_seed(*parts))


def _tasks_for_agent(arc, composition, seed, agent_idx, tpa):
    a = ARCHETYPES[arc]
    tasks = []
    for k in range(tpa):
        if composition == "homogeneous":
            q = a["base_quality"]
        else:
            rng = derive_rng(composition, seed, "quality", agent_idx, k)
            q = min(1.0, max(0.0, a["base_quality"] + float(rng.uniform(-0.05, 0.05))))
        tasks.append({
            "id": "a%d-t%d" % (agent_idx, k),
            "mandatory": list(a["mandatory"]),
            "optional": list(a["optional"]),
            "quality": q,
            "refinement": a["refinement"],
            "sloMs": a["slo_ms"],
        })
    return tasks


def _scenario_hash(payload):
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode()).hexdigest()[:16]


def base_scenario(composition, contention_name, contention_ratio, seed, cfg):
    n = cfg["n_agents"]
    tpa = cfg["tasks_per_agent"]
    arcs = assign_archetypes(composition, n)

    mand_fp = [archetype_footprint(a, include_optional=False) for a in arcs]
    full_fp = [archetype_footprint(a, include_optional=True) for a in arcs]
    mandatory_demand = [{r: tpa * mand_fp[i][r] for r in RESOURCES} for i in range(n)]
    full_demand = [{r: tpa * full_fp[i][r] for r in RESOURCES} for i in range(n)]

    total_mand = {r: sum(mandatory_demand[i][r] for i in range(n)) for r in RESOURCES}
    capacities = {r: max(1, int(round(total_mand[r] / contention_ratio))) for r in RESOURCES}
    realized_ratio = {r: (total_mand[r] / capacities[r] if capacities[r] > 0 else 0.0)
                      for r in RESOURCES}

    agents = []
    used_services = set()
    for i in range(n):
        md = mandatory_demand[i]
        total_md = sum(md[r] for r in RESOURCES)
        util_weights = {r: (md[r] / total_md if total_md > 0 else 0.0) for r in RESOURCES}
        leontief_req = dict(util_weights)
        mn, up = {}, {}
        for r in RESOURCES:
            uses = md[r] > 0
            mn[r] = 1 if uses else 0
            up[r] = min(capacities[r], full_demand[i][r]) if uses else 0
            if uses and up[r] < mn[r]:
                up[r] = mn[r]
        priority = 1.0
        tasks = _tasks_for_agent(arcs[i], composition, seed, i, tpa)
        for t in tasks:
            used_services.update(t["mandatory"])
            used_services.update(t["optional"])
        agents.append({
            "id": "a%d" % i, "archetype": arcs[i],
            "prefs": util_weights, "util_weights": util_weights,
            "leontief_req": leontief_req, "mandatory_demand": md,
            "min": mn, "upper": up, "priority": priority, "tasks": tasks,
        })

    hash_payload = {
        "capacities": capacities,
        "agents": [{
            "id": a["id"], "archetype": a["archetype"],
            "mandatory_demand": a["mandatory_demand"], "util_weights": a["util_weights"],
            "leontief_req": a["leontief_req"], "min": a["min"], "upper": a["upper"],
            "priority": a["priority"],
            "mandatory_seq": [t["mandatory"] for t in a["tasks"]],
            "optional_seq": [t["optional"] for t in a["tasks"]],
        } for a in agents],
    }
    scenario_hash = _scenario_hash(hash_payload)

    services = {s: 100000 for s in sorted(used_services)}
    return {"capacities": capacities, "agents": agents, "services": services,
            "composition": composition, "contention": contention_name,
            "realized_ratio": realized_ratio, "scenario_hash": scenario_hash}


def make_job(scenario, cell, seed, policy, gamma, solver_python, execute):
    return {
        "cell": cell, "seed": int(seed), "policy": policy, "gamma": float(gamma),
        "solverPython": solver_python, "execute": bool(execute),
        "scenarioHash": scenario["scenario_hash"],
        "capacities": scenario["capacities"], "services": scenario["services"],
        "agents": [{
            "id": a["id"], "archetype": a["archetype"], "prefs": a["prefs"],
            "utilWeights": a["util_weights"], "leontiefReq": a["leontief_req"],
            "mandatoryDemand": a["mandatory_demand"], "min": a["min"], "upper": a["upper"],
            "priority": a["priority"], "tasks": a["tasks"],
        } for a in scenario["agents"]],
    }
