# Experiments

The primary and enforcement experiments exercise the canonical Java runtime. The
dynamic experiment is a secondary solver-level simulation and does not install
runtime contracts or drive the runtime clock. A historical joint-allocation
experiment is preserved separately. Seeds are derived deterministically by
hashing labels with SHA-256; every policy in a cell-seed sees the same workload
and scenario hash.

## Platform mediation (`experiments/platform_mediation/`)

The primary experiment. It measures how the semantics of an agent's utility
declaration affect completed bundle-structured work, and when the allocation
computation requires cross-resource coordination.

- Four task archetypes (research, code review, document processing, monitoring),
  each with a mandatory service sequence and an optional refinement sequence.
- Each seed is a workload draw: a task queue is a sequence of task types sampled
  uniformly from the four archetypes. Each agent's declaration primitive is one
  normalized mandatory-demand vector from its exact sampled queue. Linear weights,
  Cobb–Douglas exponents, and CES weights all use this same primitive; Leontief
  uses the mandatory proportions as its requirement vector. Optional refinements
  are excluded from the primary declaration and reported separately.
- Two workload compositions: `homogeneous`, in which every agent shares one
  sampled queue and identical bounds, utility parameters, and operator priority
  (a symmetry check); and `mixed_bundle`, in which each agent samples
  independently and a degenerate draw is redrawn. Each seed builds one scenario,
  identified by a scenario hash over every outcome-relevant field, that every
  policy reuses unchanged.
- Two contention levels, moderate (about 1.3) and high (about 1.9); capacities are
  sized from aggregate mandatory demand and realized ratios are recorded.
- Seven policies: equal quotas; standard unweighted Dominant Resource Fairness on
  the mandatory-demand vector; an exact decomposed Cobb–Douglas comparator that
  solves each resource independently; and joint weighted proportional fairness
  under linear, Cobb–Douglas, CES (`ρ = 0.5`), and Leontief declarations. Every
  policy's matrix is installed and executed through the same runtime contract
  path.
- Primary outcomes: task completion rate, minimum and fifth-percentile per-agent
  completion, optional refinement rate, capacity utilization, and allocation
  consumption (these last two are distinct fields). Declared welfare is reported
  only within a utility family; `decomposed_cobb_douglas` is labelled Cobb–Douglas
  for welfare. Latency is a budget of service constants and is reported per policy.
- Analysis: paired seed-level differences with 95% bootstrap confidence intervals.
  Mixed-bundle results are primary, with a stratified paired bootstrap that
  resamples seeds within each mixed cell and averages the two cell means; the
  homogeneous composition is reported separately as a symmetry check. The
  Cobb–Douglas decomposition is checked by `validate_decomposition.py`, which
  reports the measured maximum continuous difference between the exact decomposed
  solver and the joint solver over a bounded test set, together with the joint
  solver's status counts, and the run-level installed-integer and completion
  differences.

The headline results file, memo, and figures are generated from the raw CSVs by
`make_headline.py`, `make_memo.py`, and `figures.py`; `check_consistency.py`
reconstructs the headline, mixed-cell bootstrap intervals, solver-status counts,
decomposition validation, and dynamic epoch aggregates from the raw records and
runs in CI.

Run the primary sweep alone (no other solver-heavy work concurrent) so latency is
measured cleanly: `python3 run_sweep.py --full`, then `validate_decomposition.py`,
`make_headline.py`, `make_memo.py`, `figures.py`, `make_test_report.py`,
`make_manifest.py` (last), and `check_consistency.py --with-manifest` (use
`--smoke` for a fast pass).

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
