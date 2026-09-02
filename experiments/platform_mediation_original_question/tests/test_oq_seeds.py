"""Seed derivation, disjointness, and determinism."""
from oqlib import seeds_oq as S


def test_scenario_seeds_deterministic():
    a = S.scenario_seeds(S.NS_ARCH_CONF, 200)
    b = S.scenario_seeds(S.NS_ARCH_CONF, 200)
    assert a == b
    assert len(set(a)) == 200


def test_confirmatory_namespaces_disjoint_from_each_other():
    arch = set(S.scenario_seeds(S.NS_ARCH_CONF, 200))
    drift = set(S.scenario_seeds(S.NS_DRIFT_CONF, 200))
    assert len(arch & drift) == 0


def test_confirmatory_seeds_disjoint_from_prior_work():
    assert S.assert_disjoint_scenario_seeds(S.NS_ARCH_CONF, 200) == 200
    assert S.assert_disjoint_scenario_seeds(S.NS_DRIFT_CONF, 200) == 200


def test_dev_and_confirmatory_namespaces_disjoint():
    for conf, dev in ((S.NS_ARCH_CONF, S.NS_ARCH_DEV), (S.NS_DRIFT_CONF, S.NS_DRIFT_DEV)):
        c = set(S.scenario_seeds(conf, 200))
        d = set(S.scenario_seeds(dev, 200))
        assert len(c & d) == 0


def test_per_agent_streams_distinct():
    seeds = S.scenario_seeds(S.NS_ARCH_CONF, 5)
    used = set()
    for sd in seeds:
        for i in range(6):
            for stream in (S.STREAM_LATENT, S.STREAM_DRIFT_TARGET, S.STREAM_CALIBRATION_BASELINE,
                           S.STREAM_EXECUTION):
                s = S.stream_seed(S.NS_DRIFT_CONF, sd, i, stream)
                assert s not in used
                used.add(s)


def test_bootstrap_constants_frozen():
    assert S.BOOTSTRAP_SEED == 20260902
    assert S.N_BOOTSTRAP == 20000
