"""Scenario-construction tests: hashing, factorial control, no rejection."""
from pilotlib import workload, pilot_scenario
from lib.scenario import _scenario_hash, make_job

SEED_LABEL = "heterogeneity_pilot"
N, TPA = 6, 8
DIR03 = {"name": "dirichlet_0.3", "kind": "dirichlet", "concentration": 0.3}
POLICIES = ["equal", "drf", "decomposed_cobb_douglas", "joint_linear",
            "joint_cobb_douglas", "joint_ces", "joint_leontief"]


def _wl(seed, regime=DIR03):
    return workload.generate_workload(regime, seed, N, TPA, SEED_LABEL)


def test_task_workload_hash_identical_across_contention():
    seed = workload.dev_seeds(SEED_LABEL, 1)[0]
    wl = _wl(seed)
    sm = pilot_scenario.build_scenario(wl, "moderate", 1.3, "unit", "c__moderate")
    sh = pilot_scenario.build_scenario(wl, "high", 1.9, "unit", "c__high")
    assert sm["task_workload_hash"] == sh["task_workload_hash"]
    assert sm["scenario_hash"] != sh["scenario_hash"]  # contention differs


def test_scenario_hash_changes_with_floor():
    seed = workload.dev_seeds(SEED_LABEL, 1)[0]
    wl = _wl(seed)
    a = pilot_scenario.build_scenario(wl, "moderate", 1.3, "unit", "c")
    b = pilot_scenario.build_scenario(wl, "moderate", 1.3, "proportional_0.50", "c")
    assert a["task_workload_hash"] == b["task_workload_hash"]  # same workload
    assert a["scenario_hash"] != b["scenario_hash"]            # floors differ


def test_scenario_hash_sensitive_to_declarations():
    seed = workload.dev_seeds(SEED_LABEL, 1)[0]
    sc = pilot_scenario.build_scenario(_wl(seed), "moderate", 1.3, "unit", "c")
    base_payload = {
        "task_workload_hash": sc["task_workload_hash"], "contention": "moderate",
        "contention_ratio": 1.3, "capacities": sc["capacities"], "floor_regime": "unit",
        "declaration_source": sc["declaration_source"],
        "agents": [{"id": a["id"], "mandatory_demand": a["mandatory_demand"],
                    "util_weights": a["util_weights"], "leontief_req": a["leontief_req"],
                    "min": a["min"], "upper": a["upper"], "priority": a["priority"]}
                   for a in sc["agents"]],
    }
    h1 = _scenario_hash(base_payload)
    # Perturb one declaration weight; the scenario hash must change.
    base_payload["agents"][0]["util_weights"] = {r: 0.25 for r in base_payload["capacities"]}
    h2 = _scenario_hash(base_payload)
    assert h1 != h2
    assert h1 == sc["scenario_hash"]


def test_all_policies_share_scenario_and_workload_hash():
    seed = workload.dev_seeds(SEED_LABEL, 1)[0]
    sc = pilot_scenario.build_scenario(_wl(seed), "moderate", 1.3, "unit", "c__moderate")
    jobs = [make_job(sc, "c__moderate", seed, p, "python3", True) for p in POLICIES]
    assert len({j["scenarioHash"] for j in jobs}) == 1
    assert len({j["workloadHash"] for j in jobs}) == 1
    assert [j["policy"] for j in jobs] == POLICIES
    assert all(j["scenarioHash"] == sc["scenario_hash"] for j in jobs)


def test_no_rejection_all_identical_workload():
    # A workload where every agent is identical (dissimilarity 0) is built without
    # any degenerate-mixed rejection or redraw.
    seed = workload.dev_seeds(SEED_LABEL, 1)[0]
    wl = workload.generate_workload(
        {"name": "homogeneous", "kind": "homogeneous", "concentration": None},
        seed, N, TPA, SEED_LABEL)
    sc = pilot_scenario.build_scenario(wl, "moderate", 1.3, "unit", "h__moderate")
    assert sc["redraws"] == 0


def test_inactive_resource_recorded():
    wl = {"regime": "forced", "kind": "iid_uniform", "concentration": None, "seed": 1,
          "n_agents": N, "tasks_per_agent": TPA,
          "agent_task_types": [["code_review"] * TPA for _ in range(N)],
          "latent_probs": [[0, 1, 0, 0] for _ in range(N)]}
    sc = pilot_scenario.build_scenario(wl, "moderate", 1.3, "unit", "f__moderate")
    assert "DATASET" in sc["inactive_resources"]
    assert len(sc["active_resources"]) == 3
    assert sc["total_mandatory_demand"]["DATASET"] == 0


def test_tasks_per_run_identity():
    # completion_mean * n_agents * tasks_per_agent == sum of per-agent completed tasks
    comps = [0.5, 0.25, 1.0, 0.0, 0.75, 0.5]
    mean = sum(comps) / len(comps)
    tasks_per_run = mean * N * TPA
    assert abs(tasks_per_run - sum(comps) * TPA) < 1e-12
    assert N * TPA == 48


def test_declaration_source_marked():
    seed = workload.dev_seeds(SEED_LABEL, 1)[0]
    sc = pilot_scenario.build_scenario(_wl(seed), "moderate", 1.3, "unit", "c")
    assert sc["declaration_source"] == "exact_pending_queue"
