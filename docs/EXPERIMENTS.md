# Experiments

Three current experiments run through the canonical Java runtime, plus one
preserved historical experiment. Seeds are derived deterministically by hashing
labels with SHA-256; calibration and test seed sets are disjoint, and every
policy in a cell sees the same seed and scenario.

## Platform mediation (`experiments/platform_mediation/`)

The primary experiment. It measures how the semantics of an agent's utility
declaration affect completed bundle-structured work, and when the allocation
computation requires cross-resource coordination.

- Four task archetypes (research, code review, document processing, monitoring),
  each with a mandatory service sequence and an optional refinement sequence.
- Each agent's declaration primitive is one normalized mandatory-demand vector
  derived from its exact task queue. Linear weights, Cobb–Douglas exponents, and
  CES weights all use this same primitive; Leontief uses the mandatory proportions
  as its requirement vector. Optional refinements are excluded from the primary
  declaration and reported separately.
- Two workload compositions: `homogeneous`, in which every agent has the same
  archetype, demand, bounds, utility parameters, and operator priority (a genuine
  null); and `mixed_bundle`, in which agents draw from the four archetypes. Each
  seed builds one scenario, identified by a scenario hash over all
  allocation-relevant inputs, that every policy reuses unchanged.
- Two contention levels, moderate (about 1.3) and high (about 1.9); capacities are
  sized from aggregate mandatory demand and realized ratios are recorded.
- Policies: equal quotas; standard unweighted Dominant Resource Fairness on the
  mandatory-demand vector; an exact decomposed Cobb–Douglas comparator that solves
  each resource independently; and joint weighted proportional fairness under
  linear, Cobb–Douglas, CES (`ρ = 0.5`), and Leontief declarations, all installed
  and executed through the same runtime contract path. An appendix separable
  water-filling family is tuned on calibration seeds against declared linear
  welfare, not completion.
- Primary outcomes: task completion rate, minimum and fifth-percentile per-agent
  completion, optional refinement rate, capacity utilization, and allocation
  consumption (these last two are distinct fields). Declared welfare is reported
  only within a utility family. Latency is a budget of service constants and is
  labelled latency-budget completion.
- Analysis: paired seed-level differences with 95% bootstrap confidence intervals
  per cell and an equally weighted aggregate across the four cells; each joint
  model minus equal quotas and minus DRF; each nonlinear joint model minus joint
  linear; decomposed minus joint Cobb–Douglas; and individual-agent harm.

The headline results file, memo, and figures are generated from the raw CSVs by
`make_headline.py`, `make_memo.py`, and `figures.py`; `check_consistency.py`
verifies the memo against the raw data and runs in CI.

Run: `python3 run_sweep.py --full` then `make_headline.py`, `make_memo.py`,
`figures.py`, `check_consistency.py` (use `--smoke` for a fast pass).

## Dynamic allocation (`experiments/dynamic_allocation/`)

An appendix allocation-policy simulation on operational contract behaviour. A
complete event schedule is precomputed per seed with an explicit target agent for
every event; an event whose target is inactive under a policy is recorded as a
no-op rather than retargeted. Commitment floors are lower bounds on declared
linear utility taken from the installed discrete allocation and verified after
integer rounding, so discrete floor violations are counted directly. It compares
unrestricted reoptimization, permanent floors, time-limited leases, and leases
with proportional shortfall, and records admissions, waiting time, commitment
infeasibility, discrete floor violations and magnitude, lease expiries, churn,
incumbent utility change, and capacity violations. This is a solver-level
simulation and does not drive the runtime clock; it is not a runtime-timing
validation.

Run: `python3 run_dynamic.py --full`.

## Enforcement fault injection (`experiments/enforcement/`)

A deterministic fault-injection suite over the runtime and solver: negative
bundles, repeated and concurrent over-quota calls, stale-context and duplicate
calls, invalid and cyclic compositions, malformed solver output, a genuinely
hung solver process terminated by the Java timeout, oversubscribed minimums,
one-resource-short calls, and unsupported utility declarations. Each case reports
invariant counters with explicit denominators; the targets are all zero. These
are test results, not estimates of real-world failure rates, and are not evidence
of strategyproofness, collusion resistance, or protection against a hostile
operator.

Run: `python3 run_enforcement.py --reps 100`.

## Historical joint allocation (`experiments/joint_allocation/`)

The v0.9 joint-allocation study is preserved unchanged as a historical artifact.
Run: `python run_all.py`.
