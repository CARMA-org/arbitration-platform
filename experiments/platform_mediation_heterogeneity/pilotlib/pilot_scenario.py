"""Scenario construction for the heterogeneity pilot.

Given a workload (per-agent task queues + latent mixtures from ``workload.py``),
a contention level, and a floor regime, build the full scenario the canonical
Java harness consumes: capacities, per-agent declarations, lower/upper bounds,
task specs, and the two provenance hashes.

Declaration provenance is the *oracle-information* condition, identical to the
canonical evaluation: every declaration is derived from the agent's exact pending
mandatory-demand vector (``declaration_source = exact_pending_queue``). This
stands in for an agent truthfully declaring its pending workload; it does not test
truthful elicitation, utility-family selection, or strategic reporting.

The declaration primitive for every nonlinear family is the agent's normalized
mandatory-demand vector (Cobb-Douglas/CES weights and Leontief requirements); DRF
receives the raw mandatory-demand vector. These match the canonical experiment so
the pilot stays directly comparable.
"""
from lib import scenario as canon
from lib.archetypes import RESOURCES
from lib.scenario import _scenario_hash, build_tasks, demand_from_types

from . import floors

PRIORITY = canon.PRIORITY
DECLARATION_SOURCE = "exact_pending_queue"


def build_scenario(workload, contention_name, contention_ratio, floor_regime, cell):
    """Return a scenario dict compatible with ``lib.scenario.make_job`` plus pilot
    provenance fields (latent mixtures, floor regime, realized contention, ...)."""
    agent_types = workload["agent_task_types"]
    n = len(agent_types)

    mandatory_demand = [demand_from_types(t, False) for t in agent_types]
    full_demand = [demand_from_types(t, True) for t in agent_types]
    optional_demand = [{r: full_demand[i][r] - mandatory_demand[i][r] for r in RESOURCES}
                       for i in range(n)]

    total_mand = {r: sum(mandatory_demand[i][r] for i in range(n)) for r in RESOURCES}
    capacities = {r: max(1, int(round(total_mand[r] / contention_ratio))) for r in RESOURCES}
    realized_ratio = {r: (total_mand[r] / capacities[r] if capacities[r] > 0 else 0.0)
                      for r in RESOURCES}
    active_resources = [r for r in RESOURCES if total_mand[r] > 0]
    inactive_resources = [r for r in RESOURCES if total_mand[r] == 0]

    # Upper bounds first (needed to cap the floor apportionment), matching the
    # canonical rule: a used resource is capped at min(capacity, full demand).
    upper = []
    for i in range(n):
        md = mandatory_demand[i]
        up = {}
        for r in RESOURCES:
            uses = md[r] > 0
            up[r] = min(capacities[r], full_demand[i][r]) if uses else 0
        upper.append(up)

    lower, floor_fraction = floors.compute_floors(
        floor_regime, RESOURCES, mandatory_demand, upper, capacities)
    for i in range(n):
        for r in RESOURCES:
            if upper[i][r] < lower[i][r]:
                upper[i][r] = lower[i][r]

    agents = []
    used_services = set()
    for i in range(n):
        md = mandatory_demand[i]
        total_md = sum(md[r] for r in RESOURCES)
        util_weights = {r: (md[r] / total_md if total_md > 0 else 0.0) for r in RESOURCES}
        leontief_req = dict(util_weights)
        tasks = build_tasks(i, agent_types[i])
        for t in tasks:
            used_services.update(t["mandatory"])
            used_services.update(t["optional"])
        agents.append({
            "id": "a%d" % i,
            "task_types": agent_types[i],
            "latent_probs": workload["latent_probs"][i],
            "prefs": util_weights, "util_weights": util_weights,
            "leontief_req": leontief_req, "mandatory_demand": md,
            "optional_demand": optional_demand[i],
            "min": lower[i], "upper": upper[i], "priority": PRIORITY, "tasks": tasks,
        })

    # task_workload_hash: only task queues + latent task distributions (regime and
    # concentration). Independent of contention, capacities, floors, declarations.
    task_workload_payload = {
        "regime": workload["regime"],
        "concentration": workload["concentration"],
        "agents": [{"id": "a%d" % i,
                    "task_types": agent_types[i],
                    "latent_probs": [round(p, 12) for p in workload["latent_probs"][i]]}
                   for i in range(n)],
    }
    task_workload_hash = _scenario_hash(task_workload_payload)

    # scenario_hash: additionally capacities, contention, declarations, bounds,
    # floor regime, priorities. Identical across policies; changes with contention,
    # floors, or declarations.
    scenario_payload = {
        "task_workload_hash": task_workload_hash,
        "contention": contention_name,
        "contention_ratio": contention_ratio,
        "capacities": capacities,
        "floor_regime": floor_regime,
        "declaration_source": DECLARATION_SOURCE,
        "agents": [{
            "id": a["id"],
            "mandatory_demand": a["mandatory_demand"],
            "util_weights": a["util_weights"], "leontief_req": a["leontief_req"],
            "min": a["min"], "upper": a["upper"], "priority": a["priority"],
        } for a in agents],
    }
    scenario_hash = _scenario_hash(scenario_payload)

    services = {s: 100000 for s in sorted(used_services)}
    return {
        "capacities": capacities, "agents": agents, "services": services,
        "cell": cell, "regime": workload["regime"], "kind": workload["kind"],
        "concentration": workload["concentration"], "seed": workload["seed"],
        "contention": contention_name, "contention_ratio": contention_ratio,
        "floor_regime": floor_regime, "floor_fraction": floor_fraction,
        "realized_ratio": realized_ratio,
        "active_resources": active_resources, "inactive_resources": inactive_resources,
        "total_mandatory_demand": total_mand,
        "declaration_source": DECLARATION_SOURCE,
        "scenario_hash": scenario_hash, "task_workload_hash": task_workload_hash,
        # Aliases so lib.scenario.make_job (which reads workload_hash) works unchanged.
        "workload_hash": task_workload_hash,
        "redraws": 0,
    }
