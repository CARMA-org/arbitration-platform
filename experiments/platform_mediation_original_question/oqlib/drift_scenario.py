"""Drift scenario construction.

For one scenario seed, drift level ``delta`` and contention level, the *physical*
scenario is fixed and shared by every arm: the realized 8-task execution queue (drawn
with common random numbers across delta), the physical capacity (frozen from baseline
latent expected demand and the contention ratio, never from the realized queue), and the
policy- and declaration-independent floors and upper bounds (frozen from baseline latent
expected demand). Only the declarations differ across arms, according to the declaration
source. Execution always runs the true realized queue; the allocation is computed from
the possibly-stale declaration. This is the drift mechanism: allocate on the declaration,
execute on the truth.
"""
from lib.scenario import build_tasks, _scenario_hash, mandatory_footprint
from lib.archetypes import RESOURCES

from . import driftgen as G
from . import declarations as DEC

N_AGENTS = 6


def physical(namespace, seed, delta, cname, ratio, n_agents=N_AGENTS):
    """Build the shared physical scenario (queue, capacity, bounds) for one seed/delta."""
    p, q = G.latent_and_target(namespace, seed, n_agents)
    baseline_cal = G.baseline_calibration(namespace, seed, n_agents, p)
    exec_types, drift_src, changed = [], [], []
    for i in range(n_agents):
        t, ds, ch = G.execution_queue(namespace, seed, i, p[i], q[i], delta)
        exec_types.append(t)
        drift_src.append(ds)
        changed.append(ch)
    refreshed_cal = [G.refreshed_calibration(namespace, seed, i, p[i], q[i], delta)
                     for i in range(n_agents)]
    capacity = DEC.build_capacity(p, ratio, n_agents)
    floors, uppers = DEC.build_bounds(p, capacity, n_agents)
    mixtures = [G.mixture(p[i], q[i], delta) for i in range(n_agents)]
    return {
        "p": p, "q": q, "baseline_cal": baseline_cal, "refreshed_cal": refreshed_cal,
        "exec_types": exec_types, "drift_src": drift_src, "changed": changed,
        "capacity": capacity, "floors": floors, "uppers": uppers, "mixtures": mixtures,
        "delta": delta, "contention": cname, "contention_ratio": ratio, "seed": seed,
    }


def declared_demand(phys, i, source):
    """Estimated 8-task mandatory demand vector for agent i under a declaration source."""
    if source == "stale_calibration":
        return DEC.estimate_from_history(phys["baseline_cal"][i])
    if source == "refreshed_calibration":
        return DEC.estimate_from_history(phys["refreshed_cal"][i])
    if source == "latent_distribution_oracle":
        return DEC.estimate_from_latent(phys["mixtures"][i])
    if source == "execution_queue_oracle":
        return DEC.estimate_from_execution(phys["exec_types"][i])
    raise ValueError("unknown declaration source: %r" % source)


def build_scenario(phys, source, n_agents=N_AGENTS):
    """Assemble a make_job-compatible scenario for one declaration source over the shared
    physical scenario. Execution uses the true realized queue; declarations use the
    source estimate; capacity and bounds are the frozen physical values."""
    agents = []
    used = set()
    for i in range(n_agents):
        dem = declared_demand(phys, i, source)
        drf_demand, leontief_req, util_weights = DEC.declaration_vectors(dem)
        tasks = build_tasks(i, phys["exec_types"][i])
        for t in tasks:
            used.update(t["mandatory"])
            used.update(t["optional"])
        agents.append({
            "id": "a%d" % i, "task_types": phys["exec_types"][i],
            "latent_probs": phys["mixtures"][i],
            "prefs": util_weights, "util_weights": util_weights, "leontief_req": leontief_req,
            "mandatory_demand": drf_demand, "min": phys["floors"][i], "upper": phys["uppers"][i],
            "priority": 1.0, "tasks": tasks,
        })
    # task_workload_hash: the physical scenario (queue + latent + delta + capacity + bounds),
    # identical across declaration sources and arms so arms pair within a seed.
    twp = {
        "delta": round(phys["delta"], 2), "contention": phys["contention"],
        "exec_types": phys["exec_types"],
        "capacity": phys["capacity"], "floors": phys["floors"], "uppers": phys["uppers"],
    }
    task_workload_hash = _scenario_hash(twp)
    sp = {
        "task_workload_hash": task_workload_hash, "declaration_source": source,
        "agents": [{"id": a["id"], "leontief_req": a["leontief_req"],
                    "mandatory_demand": a["mandatory_demand"], "min": a["min"], "upper": a["upper"]}
                   for a in agents],
    }
    scenario_hash = _scenario_hash(sp)
    services = {s: 100000 for s in sorted(used)}
    return {
        "capacities": phys["capacity"], "agents": agents, "services": services,
        "cell": "delta%.2f__%s" % (phys["delta"], phys["contention"]),
        "contention": phys["contention"], "contention_ratio": phys["contention_ratio"],
        "seed": phys["seed"], "delta": phys["delta"], "declaration_source": source,
        "scenario_hash": scenario_hash, "task_workload_hash": task_workload_hash,
        "workload_hash": task_workload_hash,
    }


def realized_demand(phys, i):
    """Exact mandatory demand of agent i's realized execution queue (an outcome, not an
    input to capacity)."""
    return G.demand_from_types(phys["exec_types"][i])
