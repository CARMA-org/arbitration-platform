# Experiments

Three current experiments run through the canonical Java runtime, plus one
preserved historical experiment. Seeds are derived deterministically by hashing
labels with SHA-256; calibration and test seed sets are disjoint, and every
policy in a cell sees the same seed and scenario.

## Platform mediation (`experiments/platform_mediation/`)

The primary experiment. It asks whether, when tasks require complementary
resource bundles, a utility declaration that represents complementarity produces
more completed work than a linear substitute-resource declaration.

- Four task archetypes (research, code review, document processing, monitoring),
  each with a mandatory service sequence and an optional refinement sequence. The
  exact resource bundles are derived from the selected service instances.
- Capacities are sized from the aggregate mandatory workload so that mandatory
  demand exceeds supply at a moderate (1.3) and a high (1.9) contention ratio;
  realized ratios after integer conversion are recorded.
- Policies: equal quotas; Dominant Resource Fairness on the mandatory demand
  bundle; a separable water-filling family whose exponent is tuned on calibration
  seeds only; and joint weighted proportional fairness under linear,
  Cobb–Douglas, CES (`ρ = 0.5`), and Leontief utilities. Linear, Cobb–Douglas,
  and CES use normalized resource-footprint weights; Leontief uses the mandatory
  bundle proportions as its requirement vector.
- Regimes: identical, nearly specialized, broad heterogeneous, and complementary
  archetypes.
- Primary outcomes: task completion rate, minimum and fifth-percentile per-agent
  completion, and optional refinement rate. Declared welfare is reported only
  within a utility family. Latency is a budget of service constants and is
  labelled latency-budget completion, not observed SLO attainment.
- Analysis: paired seed-level differences with 95% bootstrap confidence
  intervals for each joint model minus equal quotas, minus DRF, and each
  nonlinear joint model minus joint linear; individual-agent completion change,
  fraction of agents harmed, and worst per-agent loss.

Run: `python3 run_sweep.py --full` (use `--smoke` for a fast pass).

## Dynamic allocation (`experiments/dynamic_allocation/`)

A secondary experiment on operational contract behaviour under a prebuilt event
schedule (arrivals, departures, preference changes, capacity loss and
restoration, lease expiry). It compares unrestricted reoptimization, permanent
accepted-utility floors, time-limited leases, and leases with proportional
shortfall under capacity loss, and records floor violations, expired contracts,
stale-context denials, admissions, waiting time, churn, incumbent utility loss,
and shortfall magnitude.

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
