# Platform-Mediated Utility Alignment: Results

## Question

A platform allocates several bounded resources to agents running bundle-structured tasks, where a task completes only when every mandatory step is afforded and those steps jointly require several resources. This study measures how the semantics of an agent's utility declaration affect completed work, and when the allocation computation needs cross-resource coordination.

## Design

Compositions ['homogeneous', 'mixed_bundle'] at contention {'moderate': 1.3, 'high': 1.9}. 6 agents, 8 tasks each. 100 test seeds per cell over 4 cells (3200 evaluated runs, 19200 agent records). The appendix separable exponent gamma=2.0 was tuned on calibration seeds against declared linear welfare, not completion.

Realized contention ratios after integer capacity construction: {"homogeneous__moderate": {"COMPUTE": 1.300398406374502, "MEMORY": 1.2997198879551821, "API_CREDITS": 1.2993630573248407, "DATASET": 1.3016949152542372}, "homogeneous__high": {"COMPUTE": 1.8998835855646101, "MEMORY": 1.8990450204638472, "API_CREDITS": 1.902097902097902, "DATASET": 1.900990099009901}, "mixed_bundle__moderate": {"COMPUTE": 1.300275482093664, "MEMORY": 1.3004926108374384, "API_CREDITS": 1.2991452991452992, "DATASET": 1.2972972972972974}, "mixed_bundle__high": {"COMPUTE": 1.8993963782696177, "MEMORY": 1.8992805755395683, "API_CREDITS": 1.9, "DATASET": 1.900990099009901}}.

Every policy in a cell receives the same agents, tasks, priorities, bounds, and scenario hash. The declaration primitive for linear, Cobb-Douglas, CES, and Leontief is each agent's normalized mandatory-demand vector derived from its task queue; DRF receives the raw mandatory-demand vector; operator priorities are equal across agents.

## Headline completion

| Policy | Mean task completion |
|--------|----------------------|
| equal | 0.620 |
| drf | 0.609 |
| decomposed_cobb_douglas | 0.661 |
| joint_linear | 0.323 |
| joint_cobb_douglas | 0.661 |
| joint_ces | 0.609 |
| joint_leontief | 0.672 |

Paired seed-level completion differences (mean, 95% bootstrap CI):

- joint_ces_minus_drf: +0.000 [+0.000, +0.000] (n=400)
- joint_ces_minus_equal: -0.010 [-0.013, -0.007] (n=400)
- joint_ces_minus_joint_linear: +0.286 [+0.257, +0.315] (n=400)
- joint_cobb_douglas_minus_drf: +0.052 [+0.047, +0.057] (n=400)
- joint_cobb_douglas_minus_equal: +0.042 [+0.037, +0.046] (n=400)
- joint_cobb_douglas_minus_joint_linear: +0.339 [+0.304, +0.372] (n=400)
- joint_leontief_minus_drf: +0.062 [+0.056, +0.069] (n=400)
- joint_leontief_minus_equal: +0.052 [+0.047, +0.057] (n=400)
- joint_leontief_minus_joint_linear: +0.349 [+0.313, +0.383] (n=400)
- joint_linear_minus_drf: -0.286 [-0.315, -0.257] (n=400)
- joint_linear_minus_equal: -0.297 [-0.326, -0.265] (n=400)

## Homogeneous null and Cobb-Douglas decomposition

In the homogeneous composition the maximum completion spread across all policies is 0.0000, i.e. no policy has an advantage when agents are identical.

The exact decomposed Cobb-Douglas comparator, which solves each resource independently without the joint solver, matches joint Cobb-Douglas to a maximum absolute completion difference of 0.0000 across all cells; the paired difference is +0.000 [+0.000, +0.000] (n=400). Cobb-Douglas weighted proportional fairness therefore decomposes by resource, so centralized authority does not require centralized computation for this family.

## Resource use and distribution

| Policy | Capacity utilization | Allocation consumption |
|--------|----------------------|------------------------|
| equal | 0.916 | 0.916 |
| drf | 0.910 | 0.945 |
| decomposed_cobb_douglas | 0.945 | 0.945 |
| joint_linear | 0.492 | 0.492 |
| joint_cobb_douglas | 0.945 | 0.945 |
| joint_ces | 0.894 | 0.894 |
| joint_leontief | 0.951 | 0.952 |

Capacity utilization is total charged over total capacity; allocation consumption is total charged over total installed allocation.

Individual completion change versus equal quotas (averaged over cells):

- drf: mean -0.010, worst -0.375, fraction worse 0.083
- joint_ces: mean -0.010, worst -0.250, fraction worse 0.125
- joint_cobb_douglas: mean 0.042, worst 0.000, fraction worse 0.000
- joint_leontief: mean 0.052, worst 0.000, fraction worse 0.000
- joint_linear: mean -0.297, worst -1.000, fraction worse 0.500

Allocation latency: median 261 ms, p95 709 ms, max 954 ms. Comparison rules allocate in under a millisecond. No capacity or bound violation occurred in 3200 runs.

## Interpretation

A linear declaration treats resources as substitutes and concentrates each agent on its single highest-weight resource, starving the complementary resources its bundle-structured tasks also require; it completes the least work in the mixed composition. Cobb-Douglas is multiplicative and Leontief represents fixed-proportion requirements; both keep allocations balanced across each agent's bundle and complete more work. CES with rho=0.5 remains a substitutes model and is intermediate. The Cobb-Douglas result is achievable with per-resource computation; the Leontief result retains cross-resource coupling in the solver.

## Dynamic contract behaviour (appendix simulation)

A solver-level simulation over 100 seeds and 100 epochs with an agent-targeted event schedule and a capacity shock. Floors are taken from the installed discrete allocation and verified after integer rounding. This simulation does not drive the runtime clock.

| Policy | Admissions | Mean wait | Commitment infeasibility | Discrete floor violations | Lease expiries |
|--------|-----------|-----------|--------------------------|---------------------------|----------------|
| reoptimize | 5.00 | 0.00 | 0.00 | 0.00 | 0.00 |
| permanent_floors | 4.99 | 0.82 | 15.35 | 136.70 | 0.00 |
| leases | 4.99 | 0.21 | 0.55 | 118.67 | 42.49 |
| leases_shortfall | 4.99 | 0.21 | 0.37 | 119.54 | 42.49 |

Discrete floor violations are nonzero for every committed policy: floors that hold for the continuous solution can be violated after integer rounding under a later capacity change. Capacity violations total 0.

## Enforcement

A deterministic fault-injection suite over 100 repetitions of the concurrency cases plus single-shot cases reports every invariant counter at zero (backend-after-denial=0, quota=0, capacity=0, partial-deduction=0, silent-fallback=0, incorrect-success=0). These are test results, not estimates of real-world failure rates.

## Scope of claims

The runtime enforces contract authority, the shared per-version consumption ledger, execution binding, service-instance billing, and the solver timeout, and the test suite exercises these directly. The completion, distributional, latency, and dynamic numbers are results of controlled synthetic experiments with mock task outputs and a latency budget of service constants reported as latency-budget completion. No claim is made about strategyproofness, truthful reporting, collusion resistance, protection against a hostile operator, or sandboxing of untrusted agent code.

## Reproduction

From the source revision in `EXPERIMENT_MANIFEST.json`: build the classpath, then run `run_sweep.py --full`, `make_headline.py`, `make_memo.py`, `make_test_report.py`, `make_manifest.py`, and the enforcement and dynamic drivers. `SOLVER_PYTHON` must point at an interpreter with cvxpy and clarabel.
