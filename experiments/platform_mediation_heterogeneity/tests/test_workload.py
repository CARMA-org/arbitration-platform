"""Workload-generation tests: determinism, canonical preservation, Dirichlet."""
from pilotlib import workload
from lib import scenario as canon

SEED_LABEL = "heterogeneity_pilot"
N, TPA = 6, 8

HOMO = {"name": "homogeneous", "kind": "homogeneous", "concentration": None}
IID = {"name": "iid_uniform", "kind": "iid_uniform", "concentration": None}
DIR003 = {"name": "dirichlet_0.03", "kind": "dirichlet", "concentration": 0.03}
DIR10 = {"name": "dirichlet_1.0", "kind": "dirichlet", "concentration": 1.0}


def test_deterministic_reproduction():
    seed = workload.dev_seeds(SEED_LABEL, 5)[2]
    a = workload.generate_workload(DIR003, seed, N, TPA, SEED_LABEL)
    b = workload.generate_workload(DIR003, seed, N, TPA, SEED_LABEL)
    assert a["agent_task_types"] == b["agent_task_types"]
    assert a["latent_probs"] == b["latent_probs"]


def test_iid_uniform_preserves_canonical_construction():
    for seed in workload.dev_seeds(SEED_LABEL, 5):
        w = workload.generate_workload(IID, seed, N, TPA, SEED_LABEL)
        canon_types = canon._agent_task_types("mixed_bundle", seed, N, TPA, 0)
        assert w["agent_task_types"] == canon_types


def test_homogeneous_preserves_canonical_and_shares_queue():
    for seed in workload.dev_seeds(SEED_LABEL, 5):
        w = workload.generate_workload(HOMO, seed, N, TPA, SEED_LABEL)
        canon_types = canon._agent_task_types("homogeneous", seed, N, TPA, 0)
        assert w["agent_task_types"] == canon_types
        assert all(q == w["agent_task_types"][0] for q in w["agent_task_types"])


def test_dirichlet_latent_probs_sum_to_one():
    for seed in workload.dev_seeds(SEED_LABEL, 10):
        for reg in (DIR003, DIR10):
            w = workload.generate_workload(reg, seed, N, TPA, SEED_LABEL)
            assert len(w["latent_probs"]) == N
            for p in w["latent_probs"]:
                assert abs(sum(p) - 1.0) < 1e-9
                assert all(x >= 0 for x in p)


def test_dirichlet_draws_from_own_latent_support():
    # Each agent's tasks must only use archetypes with positive latent probability.
    w = workload.generate_workload(DIR003, workload.dev_seeds(SEED_LABEL, 1)[0], N, TPA, SEED_LABEL)
    for types, p in zip(w["agent_task_types"], w["latent_probs"]):
        for t in types:
            k = workload.TASK_TYPES.index(t)
            assert p[k] > 0


def test_task_counts():
    w = workload.generate_workload(DIR10, workload.dev_seeds(SEED_LABEL, 1)[0], N, TPA, SEED_LABEL)
    assert all(len(q) == TPA for q in w["agent_task_types"])
    assert len(w["agent_task_types"]) == N


def test_dev_seeds_disjoint_from_canonical_test_seeds():
    pilot = set(workload.dev_seeds(SEED_LABEL, 30))
    canon_seeds = set()
    for comp in ("homogeneous", "mixed_bundle"):
        for cont in ("moderate", "high"):
            label = "%s__%s" % (comp, cont)
            for i in range(100):
                canon_seeds.add(canon.derive_seed(label, "test", i))
    assert not (pilot & canon_seeds)


def test_no_rejection_on_low_dissimilarity():
    # generate_workload has no dissimilarity-based rejection; a homogeneous regime
    # (dissimilarity exactly zero) is produced without redraws or exceptions.
    for seed in workload.dev_seeds(SEED_LABEL, 20):
        w = workload.generate_workload(HOMO, seed, N, TPA, SEED_LABEL)
        assert len(w["agent_task_types"]) == N  # always returned, never rejected
