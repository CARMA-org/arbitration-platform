"""Deterministic generation of latent distributions, drift targets, calibration
histories, and drifted execution queues for the declaration-drift experiment.

Every random draw comes from a distinct numpy Generator seeded from the repository's
``derive_seed`` machinery. Latent distributions, drift targets, calibration histories,
and execution queues never share a stream. The execution queue uses common random
numbers across drift levels so the drift path is paired within a scenario seed: a
single per-draw uniform decides the source, and a single per-draw archetype is drawn
from each of the baseline and the drift-target distributions. Raising the drift level
only switches draws whose uniform falls below the level, so the number of drift-source
draws is separate from the number of task identities that actually change.
"""
import numpy as np

from lib import scenario as canon
from lib.scenario import mandatory_footprint
from lib.archetypes import RESOURCES

from . import seeds_oq as S

TASK_TYPES = list(canon.TASK_TYPES)
N_ARCH = len(TASK_TYPES)
ALPHA = 0.1
TPA = 8
CAL_HISTORY = 48


def _rng(namespace, seed, agent_idx, stream):
    return np.random.default_rng(S.stream_seed(namespace, seed, agent_idx, stream))


def latent_and_target(namespace, seed, n_agents):
    """Draw the fixed baseline distribution p_i and drift target q_i for each agent."""
    p = []
    q = []
    for i in range(n_agents):
        p.append(_rng(namespace, seed, i, S.STREAM_LATENT).dirichlet(ALPHA * np.ones(N_ARCH)))
        q.append(_rng(namespace, seed, i, S.STREAM_DRIFT_TARGET).dirichlet(ALPHA * np.ones(N_ARCH)))
    return [list(map(float, pi)) for pi in p], [list(map(float, qi)) for qi in q]


def baseline_calibration(namespace, seed, n_agents, p):
    """A fixed 48-task calibration history drawn from p_i (used for the stale
    declaration; independent of drift level)."""
    out = []
    for i in range(n_agents):
        picks = _rng(namespace, seed, i, S.STREAM_CALIBRATION_BASELINE).choice(N_ARCH, size=CAL_HISTORY, p=p[i])
        out.append([TASK_TYPES[k] for k in picks])
    return out


def _execution_primitives(namespace, seed, i, p_i, q_i):
    """Common random numbers for agent i: one uniform and one baseline-source and one
    target-source archetype per execution draw, all fixed across drift levels."""
    rng = _rng(namespace, seed, i, S.STREAM_EXECUTION)
    u = rng.random(TPA)
    a_p = rng.choice(N_ARCH, size=TPA, p=p_i)
    a_q = rng.choice(N_ARCH, size=TPA, p=q_i)
    return u, a_p, a_q


def execution_queue(namespace, seed, i, p_i, q_i, delta):
    """The 8-task execution queue for agent i at a drift level, plus drift diagnostics.

    Returns (task_types, drift_source_draws, changed_identities)."""
    u, a_p, a_q = _execution_primitives(namespace, seed, i, p_i, q_i)
    types = []
    drift_source = 0
    changed = 0
    for k in range(TPA):
        from_target = u[k] < delta
        pick = a_q[k] if from_target else a_p[k]
        types.append(TASK_TYPES[pick])
        if from_target:
            drift_source += 1
            if a_q[k] != a_p[k]:
                changed += 1
    return types, drift_source, changed


def refreshed_calibration(namespace, seed, i, p_i, q_i, delta):
    """A fresh 48-task calibration history drawn from the current drift mixture (used
    for the refreshed declaration). Fixed per (agent, seed, delta)."""
    rng = np.random.default_rng(S.stream_seed(namespace, seed, i, S.STREAM_CALIBRATION_REFRESH + "|%.2f" % delta))
    u = rng.random(CAL_HISTORY)
    a_p = rng.choice(N_ARCH, size=CAL_HISTORY, p=p_i)
    a_q = rng.choice(N_ARCH, size=CAL_HISTORY, p=q_i)
    return [TASK_TYPES[a_q[j] if u[j] < delta else a_p[j]] for j in range(CAL_HISTORY)]


def mixture(p_i, q_i, delta):
    """The latent drift mixture p'_i(delta) = (1-delta) p_i + delta q_i."""
    return [(1.0 - delta) * p_i[a] + delta * q_i[a] for a in range(N_ARCH)]


def demand_from_types(types):
    """Aggregate mandatory resource demand of a list of archetype tasks."""
    d = {r: 0 for r in RESOURCES}
    for t in types:
        fp = mandatory_footprint(t)
        for r in RESOURCES:
            d[r] += fp[r]
    return d


def expected_per_task_demand(dist):
    """Expected mandatory demand of one task under an archetype distribution."""
    d = {r: 0.0 for r in RESOURCES}
    for a in range(N_ARCH):
        fp = mandatory_footprint(TASK_TYPES[a])
        for r in RESOURCES:
            d[r] += dist[a] * fp[r]
    return d
