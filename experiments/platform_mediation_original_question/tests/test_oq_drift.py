"""Drift generation: common random numbers, drift-source vs changed-identity counts,
refreshed/baseline calibration discipline, oracle-declaration equality, no leakage."""
import numpy as np

from oqlib import driftgen as G, declarations as DEC, drift_scenario as DS, seeds_oq as S
from lib.archetypes import RESOURCES
from lib.scenario import demand_from_types as canon_demand
from pilotlib import pilot_scenario, workload as wlgen

NS = S.NS_DRIFT_DEV
SEED = S.scenario_seeds(NS, 1)[0]


def test_common_random_numbers_monotone_in_delta():
    p, q = G.latent_and_target(NS, SEED, 6)
    prev_changed = None
    prev_set = None
    for delta in (0.0, 0.25, 0.5, 0.75, 1.0):
        t, ds, ch = G.execution_queue(NS, SEED, 0, p[0], q[0], delta)
        # raising delta only switches draws whose uniform < delta: drift-source count is
        # nondecreasing, and the delta=0 queue is the pure baseline draw.
        if prev_changed is not None:
            assert ds >= prev_changed
        prev_changed = ds
        prev_set = t


def test_delta_zero_is_pure_baseline():
    p, q = G.latent_and_target(NS, SEED, 6)
    for i in range(6):
        t, ds, ch = G.execution_queue(NS, SEED, i, p[i], q[i], 0.0)
        assert ds == 0 and ch == 0


def test_drift_source_at_least_changed():
    p, q = G.latent_and_target(NS, SEED, 6)
    for delta in (0.25, 0.5, 0.75, 1.0):
        for i in range(6):
            t, ds, ch = G.execution_queue(NS, SEED, i, p[i], q[i], delta)
            # a drift-source draw may reproduce the original archetype, so changed <= source
            assert ch <= ds


def test_baseline_calibration_fixed_across_delta():
    p, q = G.latent_and_target(NS, SEED, 6)
    base = G.baseline_calibration(NS, SEED, 6, p)
    base2 = G.baseline_calibration(NS, SEED, 6, p)
    assert base == base2  # independent of delta by construction (no delta argument)


def test_refreshed_calibration_varies_with_delta():
    p, q = G.latent_and_target(NS, SEED, 6)
    r0 = G.refreshed_calibration(NS, SEED, 0, p[0], q[0], 0.0)
    r1 = G.refreshed_calibration(NS, SEED, 0, p[0], q[0], 1.0)
    assert len(r0) == 48 and len(r1) == 48
    assert r0 != r1  # mixture changes with delta


def test_execution_oracle_declaration_equals_exact_pending():
    """The execution-queue-oracle declaration for a queue must be identical to the exact
    pending-queue declaration the verified heterogeneity scenario builds for that queue."""
    regime = {"name": "dirichlet_0.1", "kind": "dirichlet", "concentration": 0.1}
    wseed = S.scenario_seeds(S.NS_ARCH_DEV, 1)[0]
    wl = wlgen.generate_workload(regime, wseed, 6, 8, S.NS_ARCH_DEV)
    sc = pilot_scenario.build_scenario(wl, "high", 1.9, "unit", "c")
    for i, a in enumerate(sc["agents"]):
        queue = a["task_types"]
        # exact pending declaration used by the verified scenario:
        md = canon_demand(queue, False)
        total = sum(md[r] for r in RESOURCES)
        exact_req = {r: (md[r] / total if total > 0 else 0.0) for r in RESOURCES}
        # execution-oracle declaration:
        dem = DEC.estimate_from_execution(queue)
        drf_demand, leontief_req, _ = DEC.declaration_vectors(dem)
        assert leontief_req == exact_req
        assert drf_demand == {r: md[r] for r in RESOURCES}


def test_capacity_and_bounds_independent_of_declaration_source():
    phys = DS.physical(NS, SEED, 0.5, "high", 1.9, 6)
    scen = {src: DS.build_scenario(phys, src, 6) for src in
            ("stale_calibration", "refreshed_calibration", "latent_distribution_oracle", "execution_queue_oracle")}
    caps = [s["capacities"] for s in scen.values()]
    assert all(c == caps[0] for c in caps)  # no leakage through capacity
    for i in range(6):
        mins = [s["agents"][i]["min"] for s in scen.values()]
        ups = [s["agents"][i]["upper"] for s in scen.values()]
        assert all(m == mins[0] for m in mins)   # no leakage through floors
        assert all(u == ups[0] for u in ups)     # no leakage through upper bounds


def test_capacity_not_derived_from_realized_queue():
    # Capacity is the same at delta 0 and delta 1 (frozen from baseline latent), even though
    # the realized queue changes with delta.
    p0 = DS.physical(NS, SEED, 0.0, "moderate", 1.3, 6)
    p1 = DS.physical(NS, SEED, 1.0, "moderate", 1.3, 6)
    assert p0["capacity"] == p1["capacity"]
    assert p0["floors"] == p1["floors"] and p0["uppers"] == p1["uppers"]


def test_execution_queue_shared_task_workload_hash_across_sources():
    phys = DS.physical(NS, SEED, 0.5, "high", 1.9, 6)
    hashes = {DS.build_scenario(phys, src, 6)["task_workload_hash"] for src in
              ("stale_calibration", "refreshed_calibration", "latent_distribution_oracle", "execution_queue_oracle")}
    assert len(hashes) == 1  # all arms in a unit pair on the same physical scenario
