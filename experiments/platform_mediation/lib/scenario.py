"""Deterministic scenario construction for the platform-mediation sweep.

Each seed is a workload draw. A task queue is a sequence of task types sampled
uniformly from the four archetypes. In a homogeneous scenario every agent shares
one sampled sequence; in a mixed-bundle scenario each agent samples independently.
The declaration primitive for every utility family is the agent's normalized
mandatory-demand vector derived from its exact sampled queue.
"""
import hashlib
import json

from .archetypes import RESOURCES, ARCHETYPES, archetype_footprint
from .seeds import derive_seed

COMPOSITIONS = ["homogeneous", "mixed_bundle"]
TASK_TYPES = ["research", "code_review", "doc_processing", "monitoring"]
PRIORITY = 1.0


def sample_task_types(tpa, *seed_parts):
    seq = []
    for k in range(tpa):
        pick = derive_seed(*seed_parts, "task", k) % len(TASK_TYPES)
        seq.append(TASK_TYPES[pick])
    return seq


def mandatory_footprint(task_type):
    return archetype_footprint(task_type, include_optional=False)


def optional_footprint(task_type):
    full = archetype_footprint(task_type, include_optional=True)
    mand = archetype_footprint(task_type, include_optional=False)
    return {r: full[r] - mand[r] for r in RESOURCES}


def build_tasks(agent_idx, task_types):
    tasks = []
    for k, tt in enumerate(task_types):
        a = ARCHETYPES[tt]
        tasks.append({
            "id": "a%d-t%d" % (agent_idx, k),
            "type": tt,
            "mandatory": list(a["mandatory"]),
            "optional": list(a["optional"]),
            "quality": a["base_quality"],
            "refinement": a["refinement"],
            "sloMs": a["slo_ms"],
        })
    return tasks


def demand_from_types(task_types, include_optional):
    d = {r: 0 for r in RESOURCES}
    for tt in task_types:
        fp = archetype_footprint(tt, include_optional=include_optional)
        for r in RESOURCES:
            d[r] += fp[r]
    return d


def _agent_task_types(composition, seed, n, tpa, attempt):
    if composition == "homogeneous":
        shared = sample_task_types(tpa, composition, seed, attempt, "shared")
        return [list(shared) for _ in range(n)]
    return [sample_task_types(tpa, composition, seed, attempt, "agent", i) for i in range(n)]


def _degenerate_mixed(per_agent_mandatory):
    vectors = [tuple(md[r] for r in RESOURCES) for md in per_agent_mandatory]
    if len(set(vectors)) == 1:
        return True
    aggregate = {r: sum(md[r] for md in per_agent_mandatory) for r in RESOURCES}
    return any(aggregate[r] == 0 for r in RESOURCES)


def _scenario_hash(payload):
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode()).hexdigest()[:16]


def base_scenario(composition, contention_name, contention_ratio, seed, cfg):
    n = cfg["n_agents"]
    tpa = cfg["tasks_per_agent"]

    attempt = 0
    while True:
        agent_types = _agent_task_types(composition, seed, n, tpa, attempt)
        mandatory_demand = [demand_from_types(t, False) for t in agent_types]
        if composition != "mixed_bundle" or not _degenerate_mixed(mandatory_demand):
            break
        attempt += 1

    full_demand = [demand_from_types(t, True) for t in agent_types]
    optional_demand = [{r: full_demand[i][r] - mandatory_demand[i][r] for r in RESOURCES}
                       for i in range(n)]

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
        tasks = build_tasks(i, agent_types[i])
        for t in tasks:
            used_services.update(t["mandatory"])
            used_services.update(t["optional"])
        agents.append({
            "id": "a%d" % i, "composition": composition,
            "task_types": agent_types[i],
            "prefs": util_weights, "util_weights": util_weights,
            "leontief_req": leontief_req, "mandatory_demand": md,
            "optional_demand": optional_demand[i],
            "min": mn, "upper": up, "priority": PRIORITY, "tasks": tasks,
        })

    workload_payload = {
        "composition": composition, "contention": contention_name,
        "capacities": capacities,
        "agents": [{
            "id": a["id"], "task_types": a["task_types"],
            "mandatory_seq": [t["mandatory"] for t in a["tasks"]],
            "optional_seq": [t["optional"] for t in a["tasks"]],
            "mandatory_demand": a["mandatory_demand"], "optional_demand": a["optional_demand"],
        } for a in agents],
    }
    workload_hash = _scenario_hash(workload_payload)

    hash_payload = {
        "workload": workload_payload,
        "agents": [{
            "id": a["id"],
            "quality": [t["quality"] for t in a["tasks"]],
            "refinement": [t["refinement"] for t in a["tasks"]],
            "slo": [t["sloMs"] for t in a["tasks"]],
            "min": a["min"], "upper": a["upper"], "util_weights": a["util_weights"],
            "leontief_req": a["leontief_req"], "priority": a["priority"],
        } for a in agents],
    }
    scenario_hash = _scenario_hash(hash_payload)

    services = {s: 100000 for s in sorted(used_services)}
    return {"capacities": capacities, "agents": agents, "services": services,
            "composition": composition, "contention": contention_name,
            "realized_ratio": realized_ratio, "scenario_hash": scenario_hash,
            "workload_hash": workload_hash, "redraws": attempt}


def make_job(scenario, cell, seed, policy, solver_python, execute):
    return {
        "cell": cell, "seed": int(seed), "policy": policy,
        "solverPython": solver_python, "execute": bool(execute),
        "fallbackAllowed": False,
        "scenarioHash": scenario["scenario_hash"],
        "workloadHash": scenario["workload_hash"],
        "capacities": scenario["capacities"], "services": scenario["services"],
        "agents": [{
            "id": a["id"], "archetype": "+".join(sorted(set(a["task_types"]))),
            "prefs": a["prefs"],
            "utilWeights": a["util_weights"], "leontiefReq": a["leontief_req"],
            "mandatoryDemand": a["mandatory_demand"], "min": a["min"], "upper": a["upper"],
            "priority": a["priority"], "tasks": a["tasks"],
        } for a in scenario["agents"]],
    }
