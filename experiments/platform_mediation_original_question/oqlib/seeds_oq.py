"""Deterministic seed namespaces for the closure experiments and disjointness checks.

All seeds derive from the canonical ``derive_seed`` machinery (SHA-256 of pipe-joined
parts, low 64 bits mod 2**32). Confirmatory namespaces are disjoint from the canonical
evaluation seeds, the exploratory heterogeneity pilot development seeds, and the prior
heterogeneity confirmatory seeds. Separate ``/development`` namespaces exist for
engineering-only checks and are never used in confirmatory results.
"""
from lib.seeds import derive_seed

# confirmatory namespaces
NS_DRIFT_CONF = "arb_original_question_closure_v1/declaration_drift/confirmatory"
NS_ARCH_CONF = "arb_original_question_closure_v1/architecture/confirmatory"
# development namespaces (engineering only)
NS_DRIFT_DEV = "arb_original_question_closure_v1/declaration_drift/development"
NS_ARCH_DEV = "arb_original_question_closure_v1/architecture/development"

# per-agent stream sub-labels used within a scenario seed (kept separate so latent
# distributions, calibration histories, drift targets, and execution queues never share
# a random stream)
STREAM_LATENT = "latent"
STREAM_DRIFT_TARGET = "drift_target"
STREAM_CALIBRATION_BASELINE = "calibration_baseline"
STREAM_CALIBRATION_REFRESH = "calibration_refresh"
STREAM_EXECUTION = "execution"

BOOTSTRAP_SEED = 20260902
N_BOOTSTRAP = 20000


def scenario_seeds(namespace, n):
    """The confirmatory or development scenario seeds for a namespace."""
    return [derive_seed(namespace, "scenario", i) for i in range(n)]


def stream_seed(namespace, scenario_seed, agent_idx, stream):
    """A distinct integer seed for one random stream of one agent in one scenario."""
    return derive_seed(namespace, "scenario", int(scenario_seed), "agent", int(agent_idx), stream)


def canonical_seed_universe():
    """All seeds used by the canonical evaluation, the heterogeneity pilot development
    phase, and the heterogeneity confirmatory phase, for disjointness checks."""
    seeds = set()
    for comp in ("homogeneous", "mixed_bundle"):
        for cont in ("moderate", "high"):
            for i in range(100):
                seeds.add(derive_seed("%s__%s" % (comp, cont), "test", i))
    for i in range(30):
        seeds.add(derive_seed("heterogeneity_pilot", "dev", i))
    for i in range(200):
        seeds.add(derive_seed("heterogeneity_confirmatory_v1", "test", i))
    return seeds


def assert_disjoint_scenario_seeds(namespace, n):
    """Raise if this namespace's scenario seeds overlap the prior work."""
    ours = set(scenario_seeds(namespace, n))
    prior = canonical_seed_universe()
    overlap = ours & prior
    assert not overlap, "scenario seeds for %s overlap prior work: %d" % (namespace, len(overlap))
    return len(ours)
