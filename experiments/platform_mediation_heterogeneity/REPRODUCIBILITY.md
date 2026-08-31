# Reproducibility: workload-heterogeneity pilot

This pilot is isolated from the canonical evaluation. It reuses stable code from
`experiments/platform_mediation/lib/` and runs every policy through the canonical
Java runtime `org.carma.arbitration.experiment.PlatformMediationHarness`. It does
not modify, replace, or regenerate any canonical artifact.

## Environment

Same environment as the canonical evaluation (see `docs/REPRODUCIBILITY.md`):
Java 21+, Maven, and a Python 3.10+ interpreter with `cvxpy`, `clarabel`, `numpy`,
`scipy`, `pandas`. In this repository the solver interpreter is the git-ignored
`experiments_venv_tmp/` venv.

```bash
cd <repo root>
export SOLVER_PYTHON="$PWD/experiments_venv_tmp/bin/python3"   # cvxpy/clarabel present
mvn -o -q compile                                              # if target/classes is stale
mvn -o -q dependency:build-classpath -Dmdep.outputFile=cp.txt  # if cp.txt is missing
```

## Exact commands

```bash
cd experiments/platform_mediation_heterogeneity

# 1. Read-only baseline diagnostics of the canonical evaluation (regenerates the
#    canonical workloads deterministically and validates their hashes against raw).
$SOLVER_PYTHON diagnostic_baseline.py

# 2. Workload-concentration sweep (7 regimes x 2 contention x 30 seeds x 7 policies).
$SOLVER_PYTHON run_pilot.py --sweep workload

# 3. Floor-sensitivity sweep (6 floors x 3 regimes x 2 contention x 30 seeds x 3 policies).
$SOLVER_PYTHON run_pilot.py --sweep floor

# 4. Tables + headline + machine-generated memo.
$SOLVER_PYTHON make_pilot_tables.py
$SOLVER_PYTHON make_pilot_memo.py

# 5. Artifact manifest (records source commit and per-file SHA-256).
SOURCE_COMMIT=$(git -C ../.. rev-parse HEAD) $SOLVER_PYTHON make_pilot_manifest.py

# 6. Pilot tests (fast unit tests + live-harness integration tests).
$SOLVER_PYTHON -m pytest tests -q
```

Full existing suites, from the repository root:

```bash
mvn -o test                                # Java suite
$SOLVER_PYTHON -m pytest tests/python -q   # canonical Python suite
$SOLVER_PYTHON experiments/platform_mediation/check_consistency.py --with-manifest
$SOLVER_PYTHON experiments/platform_mediation/claim_scan.py
```

## Seed derivation

`derive_seed(*parts)` is SHA-256 of the pipe-joined string parts, low 64 bits mod
`2**32` (the canonical machinery). The 30 development workload seeds are
`derive_seed("heterogeneity_pilot", "dev", i)` for `i in 0..29`; they are disjoint
from the canonical `"<cell>__<contention>", "test", i` seeds (asserted in
`tests/test_workload.py`). Dirichlet draws use per-agent `numpy.random.default_rng`
instances seeded from `derive_seed("heterogeneity_pilot", <regime>, <seed>,
"agent", i)`; no process-global random state is used. The paired bootstrap uses the
documented fixed seed in `config/pilot.json` (`bootstrap_seed`).

## Factorial control and hashes

A workload (per-agent task queues + latent mixtures) depends only on the regime and
the workload seed, not on contention, floor regime, capacities, or declarations.

* `task_workload_hash` = SHA-256 (16 hex) of the regime, concentration, per-agent
  task queues, and per-agent latent probabilities. It is **identical at both
  contention levels** for the same workload.
* `scenario_hash` = SHA-256 of the `task_workload_hash` plus contention, capacities,
  floor regime, per-agent declarations (Cobb-Douglas/CES weights and Leontief
  requirements), bounds, and priorities. It is **identical across all policies**
  within a cell and seed, and **changes** when contention, floors, or declarations
  change.

## Metric definitions

All probability-vector distances are total variation `TV(p,q) = 0.5 * sum_k |p_k - q_k|`.

* **Task-mixture vector**: an agent's normalized frequency over the four task
  archetypes. **Task-mixture dissimilarity** of a scenario is the mean pairwise TV
  over its agents.
* **Resource-demand vector**: an agent's mandatory resource-demand vector normalized
  to sum to one over the four resources. **Resource-demand dissimilarity** is the
  mean pairwise TV over agents; this is the pilot's primary dissimilarity measure.
* **Resource-centroid distance**: mean TV from each agent's resource-demand vector to
  the population centroid.
* **Normalized task entropy**: Shannon entropy of the task-mixture vector divided by
  `log 4` (in `[0, 1]`); 1 is a perfectly even mixture, 0 a single archetype.
* **Archetype coverage**: number of distinct archetypes present in an agent's 8-task
  queue.
* **Allocation distance from equal** (per agent, per policy):
  `rel_l1 = sum_r |a_policy[r] - a_equal[r]| / max(sum_r a_equal[r], 1)`, against the
  paired equal allocation in the same scenario.
* **Completed tasks per run**: `completion_mean * n_agents * tasks_per_agent`
  (`= completion_mean * 48`). Every completion difference is also reported as
  `diff * 48` tasks per run.
* **Floor fraction**: for each resource, the total installed lower bound divided by
  the resource capacity.

## Floor regimes

* `zero` -- no lower bound.
* `unit` -- one unit on every resource an agent uses (the current canonical bound).
* `proportional_<f>` -- an aggregate budget of at most `floor(f * capacity_r)` units
  per resource, apportioned across agents in proportion to raw mandatory demand by
  deterministic largest-remainder (Hamilton) with per-agent upper caps. Zero-demand
  agents receive no floor; the total per resource never exceeds its budget, hence
  never exceeds capacity.

## Dirichlet regimes

For concentration `alpha`, each agent draws `p_i ~ Dirichlet(alpha * 1_4)` and then
draws its 8 execution tasks i.i.d. from `p_i`. Small `alpha` concentrates an agent on
few archetypes; `iid_uniform` is the fixed-uniform (`alpha -> infinity`) limit and is
generated by the canonical mixed-bundle construction rather than a finite Dirichlet
value. The frozen grid is `{3.0, 1.0, 0.3, 0.1, 0.03}`.

## What is not tested here

The primary condition is the oracle-information declaration
(`declaration_source = exact_pending_queue`): the harness builds each declaration
from the agent's exact pending mandatory demand. This does not test truthful
elicitation, utility-family selection by a developer, or strategic reporting. A
later calibration-versus-execution drift design is specified in
`DECLARATION_STALENESS_DESIGN.md`; it is not run here.
