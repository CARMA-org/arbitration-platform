# Design: declaration-staleness (calibration vs. execution drift)

**Status: design only. Not implemented, not run. No results in this document are
real; none are claimed.**

The pilot's primary condition is an oracle-information declaration: the harness
builds each agent's declaration from its exact pending mandatory-demand vector
(`declaration_source = exact_pending_queue`). That condition answers "if the
platform knew each agent's exact pending workload, would a bundle-aware allocation
help?" It deliberately does **not** test whether a declaration made *before* the
work is known is still useful when the realized work differs. This document
specifies that follow-on experiment.

## Question

When an agent (or its developer) declares a utility/demand from a *calibration*
workload and then executes a *fresh* workload drawn from the same latent
distribution but subject to controlled drift, do bundle-aware joint policies retain
any completion advantage over equal quotas and DRF, and does that advantage decay
with drift? The declaration and the upper bounds must not leak the execution queue.

## Factors

1. **Calibration queue** `Q_cal(i)` per agent, drawn from the agent's latent
   archetype distribution `p_i` (for Dirichlet regimes) or the shared/uniform
   distribution (for homogeneous/iid_uniform).
2. **Execution queue** `Q_exec(i)`, drawn **independently** from a possibly drifted
   distribution `p_i'` (below). Completion is measured only on `Q_exec`.
3. **Drift parameter** `delta in [0, 1]`. Define
   `p_i'(delta) = (1 - delta) * p_i + delta * u_i`, where `u_i` is a fixed
   per-agent drift target (e.g. a permutation of `p_i`, or the uniform vector, or a
   single-archetype spike). `delta = 0` reproduces "same distribution, fresh draw";
   `delta = 1` is full drift to `u_i`. Sweep `delta in {0, 0.25, 0.5, 0.75, 1.0}`.
4. Everything else fixed as in the current pilot: 6 agents, 8 tasks/agent, 4
   resources, existing archetypes and footprints, two contention levels, equal
   priorities, the seven policies, the canonical runtime and rounding.

## Declarations and bounds (no leakage)

* Declarations (Cobb-Douglas/CES weights, Leontief requirements) and the DRF
  demand vector are built from `Q_cal` only.
* **Upper bounds** are built from `Q_cal` only (e.g. `min(capacity, full demand of
  Q_cal)`), or from a distribution-level cap derived from `p_i` and the horizon --
  never from `Q_exec`. This is the key change from the current pilot, whose upper
  bounds use the exact execution queue.
* Capacities are sized from the aggregate calibration demand (the platform sizes the
  pool from what it was told), not from the realized execution demand. Realized
  execution contention is then a measured outcome, recorded per resource.
* The **same** calibration-derived information is supplied to DRF and to the
  nonlinear policies, so no policy gets privileged access to the execution queue.

## Metrics

Completion is measured on `Q_exec`. Report, per regime/concentration, contention,
and `delta`, paired by workload seed: mean completion and completed tasks per run;
paired differences from equal and DRF with 95% bootstrap intervals; fraction of
agents worse than equal; fraction completing zero tasks; realized execution
contention; and the realized calibration-vs-execution resource-demand distance (a
manipulation check that drift actually moved the execution workload). The headline
comparison is completion advantage vs. `delta`: does any advantage present at
`delta = 0` decay toward zero (or below) as drift increases?

## Code changes required

The pilot is structured so this is an additive change; no canonical file changes.

* `pilotlib/workload.py`: add `generate_calibration_and_execution(regime, seed, ...,
  delta, drift_target)` returning two independent queues per agent from
  `p_i` and `p_i'(delta)`, using separate `derive_seed(..., "calibration"/"execution")`
  RNG streams. Record `p_i`, `p_i'`, both queues, and `delta`.
* `pilotlib/pilot_scenario.py`: add a builder that takes calibration demand for
  declarations, upper bounds, and capacities, and execution tasks for the run; add a
  `declaration_source = "calibration_queue"` marker and a `drift` field; extend
  `scenario_hash` to include `delta` and the calibration/execution split.
* `run_pilot.py`: add `--sweep staleness` iterating `delta`, writing the calibration
  and execution provenance and the realized calibration-vs-execution distance into
  the workload row.
* `pilotlib/measures.py`: add the calibration-vs-execution resource-demand distance
  (already expressible with the existing `tv`).
* Tests: independence of calibration and execution draws; upper bounds and
  declarations are a function of the calibration queue only (no execution leakage);
  `delta = 0` reproduces a same-distribution fresh draw; `scenario_hash` changes with
  `delta`; DRF and nonlinear policies receive identical calibration information.

## Guardrails

* Do not select `delta`, drift target, or seeds after seeing completion.
* Report the realized drift (execution distribution distance from calibration), not
  only the nominal `delta`.
* Held-out execution prevents the evaluation from reducing to "optimize the reported
  metric and then measure the same metric": the metric is measured on a queue the
  declaration never saw.
