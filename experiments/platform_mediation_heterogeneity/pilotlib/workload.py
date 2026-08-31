"""Deterministic workload generation for the heterogeneity pilot.

A *workload* is the set of per-agent task queues for one scenario, plus the latent
archetype distribution each queue was drawn from. Workloads depend only on the
generator regime and the workload seed; they are independent of contention, floor
regime, capacities, and declarations, so the same workload is reused at both
contention levels (factorial control).

Regimes
-------
* ``homogeneous`` -- every agent receives one shared queue (the current null
  condition). Reuses the canonical generator directly.
* ``iid_uniform`` -- every task of every agent is drawn independently and
  uniformly over the four archetypes (the current mixed-bundle construction, and
  the fixed-uniform limit of the Dirichlet family). Reuses the canonical
  generator directly.
* ``dirichlet`` -- each agent draws a latent archetype distribution
  ``p_i ~ Dirichlet(alpha * 1_4)`` and then draws its ``tasks_per_agent``
  execution tasks i.i.d. from ``p_i``.

RNG discipline: every stochastic draw comes from a ``numpy`` Generator seeded from
the repository's ``derive_seed`` machinery. No process-global random state is used.
Workloads are never rejected or redrawn on the basis of realized dissimilarity.
"""
import numpy as np

from lib import scenario
from lib.seeds import derive_seed

TASK_TYPES = list(scenario.TASK_TYPES)
N_ARCH = len(TASK_TYPES)
UNIFORM_PROBS = [1.0 / N_ARCH] * N_ARCH


def dev_seeds(seed_label, n_dev_seeds):
    """The frozen list of development workload seeds for the pilot.

    Derived from a pilot-specific label so they are disjoint from the canonical
    ``*__*`` test seeds (asserted in the tests)."""
    return [derive_seed(seed_label, "dev", i) for i in range(n_dev_seeds)]


def _agent_rng(seed_label, regime_name, seed, agent_idx):
    return np.random.default_rng(derive_seed(seed_label, regime_name, seed, "agent", agent_idx))


def generate_workload(regime, seed, n_agents, tasks_per_agent, seed_label):
    """Return the per-agent task queues and latent mixtures for one workload.

    ``regime`` is a dict with ``name``, ``kind`` and (for Dirichlet) ``concentration``.
    Returns a dict with ``agent_task_types`` (list of lists of archetype names),
    ``latent_probs`` (list of 4-vectors) and provenance fields.
    """
    kind = regime["kind"]
    name = regime["name"]
    concentration = regime.get("concentration")

    if kind == "homogeneous":
        # Reuse the canonical construction: one shared queue for every agent.
        agent_task_types = scenario._agent_task_types("homogeneous", seed, n_agents, tasks_per_agent, 0)
        latent_probs = [list(UNIFORM_PROBS) for _ in range(n_agents)]
    elif kind == "iid_uniform":
        # Reuse the canonical mixed-bundle construction (attempt 0, no rejection):
        # every task of every agent drawn independently and uniformly.
        agent_task_types = scenario._agent_task_types("mixed_bundle", seed, n_agents, tasks_per_agent, 0)
        latent_probs = [list(UNIFORM_PROBS) for _ in range(n_agents)]
    elif kind == "dirichlet":
        if concentration is None or float(concentration) <= 0:
            raise ValueError("dirichlet regime %r needs a positive concentration" % name)
        alpha = float(concentration) * np.ones(N_ARCH)
        agent_task_types = []
        latent_probs = []
        for i in range(n_agents):
            rng = _agent_rng(seed_label, name, seed, i)
            p = rng.dirichlet(alpha)
            picks = rng.choice(N_ARCH, size=tasks_per_agent, p=p)
            agent_task_types.append([TASK_TYPES[k] for k in picks])
            latent_probs.append([float(x) for x in p])
    else:
        raise ValueError("unknown workload kind: %r" % kind)

    return {
        "regime": name,
        "kind": kind,
        "concentration": concentration,
        "seed": int(seed),
        "n_agents": n_agents,
        "tasks_per_agent": tasks_per_agent,
        "agent_task_types": agent_task_types,
        "latent_probs": latent_probs,
    }
