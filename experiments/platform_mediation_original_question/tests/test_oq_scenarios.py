"""Identical paired scenarios across arms, and hash discipline."""
from oqlib import seeds_oq as S
from oqlib.jobs import make_native_job, make_preinstalled_job
from oqlib import mechanisms as MECH
from pilotlib import workload as wlgen, pilot_scenario


def _scn():
    regime = {"name": "dirichlet_0.1", "kind": "dirichlet", "concentration": 0.1}
    seed = S.scenario_seeds(S.NS_ARCH_DEV, 1)[0]
    wl = wlgen.generate_workload(regime, seed, 6, 8, S.NS_ARCH_DEV)
    return pilot_scenario.build_scenario(wl, "high", 1.9, "unit", "c"), seed


def test_all_arms_share_scenario_and_workload_hash():
    sc, seed = _scn()
    mm = MECH.independent_bundle_maxmin_alloc(sc)
    jobs = [make_native_job(sc, "c", seed, "equal", "python3"),
            make_native_job(sc, "c", seed, "drf", "python3"),
            make_native_job(sc, "c", seed, "joint_leontief", "python3"),
            make_preinstalled_job(sc, "c", seed, "independent_bundle_maxmin", mm, "python3")]
    shashes = {j["scenarioHash"] for j in jobs}
    whashes = {j["workloadHash"] for j in jobs}
    assert len(shashes) == 1 and len(whashes) == 1


def test_preinstalled_job_carries_allocation_and_same_bounds():
    sc, seed = _scn()
    mm = MECH.independent_bundle_maxmin_alloc(sc)
    job = make_preinstalled_job(sc, "c", seed, "independent_bundle_maxmin", mm, "python3")
    assert "preinstalledAllocation" in job
    assert len(job["preinstalledAllocation"]) == 6
    # bounds are the scenario bounds, identical to the native job
    native = make_native_job(sc, "c", seed, "drf", "python3")
    for i in range(6):
        assert job["agents"][i]["min"] == native["agents"][i]["min"]
        assert job["agents"][i]["upper"] == native["agents"][i]["upper"]


def test_workload_hash_independent_of_contention():
    regime = {"name": "dirichlet_0.1", "kind": "dirichlet", "concentration": 0.1}
    seed = S.scenario_seeds(S.NS_ARCH_DEV, 1)[0]
    wl = wlgen.generate_workload(regime, seed, 6, 8, S.NS_ARCH_DEV)
    a = pilot_scenario.build_scenario(wl, "moderate", 1.3, "unit", "m")
    b = pilot_scenario.build_scenario(wl, "high", 1.9, "unit", "h")
    assert a["task_workload_hash"] == b["task_workload_hash"]  # same workload, paired across contention
    assert a["scenario_hash"] != b["scenario_hash"]            # capacities differ
