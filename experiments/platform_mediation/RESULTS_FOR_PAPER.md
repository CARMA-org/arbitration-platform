# Platform-Mediated Utility Alignment: Results

## Question

A platform allocates several bounded resources to agents running bundle-structured tasks, where a task completes only when every mandatory step is afforded and those steps jointly require several resources. This study measures how the semantics of an agent's utility declaration affect completed work, and when the allocation computation needs cross-resource coordination. Each seed is an independent workload draw.

## Design

Compositions ['homogeneous', 'mixed_bundle'] at contention {'moderate': 1.3, 'high': 1.9}. 6 agents, 8 tasks each. 100 test seeds per cell over 4 cells (2800 runs, 16800 agent records). Distinct workload hashes per cell: {"homogeneous__moderate": 100, "homogeneous__high": 100, "mixed_bundle__moderate": 100, "mixed_bundle__high": 100}.

Realized contention ratios after integer capacity construction: {"homogeneous__moderate": {"COMPUTE": 1.2998678996036988, "MEMORY": 1.2998266897746966, "API_CREDITS": 1.2986301369863014, "DATASET": 1.2972972972972974}, "homogeneous__high": {"COMPUTE": 1.9005524861878453, "MEMORY": 1.8995633187772927, "API_CREDITS": 1.9036144578313252, "DATASET": 1.9047619047619047}, "mixed_bundle__moderate": {"COMPUTE": 1.2994505494505495, "MEMORY": 1.2990196078431373, "API_CREDITS": 1.3, "DATASET": 1.3012048192771084}, "mixed_bundle__high": {"COMPUTE": 1.8982300884955752, "MEMORY": 1.8991825613079019, "API_CREDITS": 1.9, "DATASET": 1.8888888888888888}}.

Each seed samples task types uniformly from the four archetypes; homogeneous agents share one sampled queue, mixed agents sample independently. The declaration primitive for linear, Cobb-Douglas, CES, and Leontief is each agent's normalized mandatory-demand vector from its exact queue; DRF receives the raw mandatory-demand vector; operator priorities are equal. Every policy in a cell-seed receives the same scenario hash. Paired differences use a stratified paired bootstrap (bootstrap seed 12345, 2000 resamples), reported as 100 paired workload draws per cell.

## Mixed-bundle results

Mean task completion by policy and mixed cell:

| Policy | mixed_bundle__high | mixed_bundle__moderate |
|--------|--------|--------|
| equal | 0.513 | 0.716 |
| drf | 0.523 | 0.749 |
| decomposed_cobb_douglas | 0.529 | 0.756 |
| joint_linear | 0.047 | 0.104 |
| joint_cobb_douglas | 0.529 | 0.757 |
| joint_ces | 0.537 | 0.752 |
| joint_leontief | 0.523 | 0.759 |

Mixed-bundle equal-weighted stratified paired completion differences (mean [95% CI], 100 seeds in each of 2 mixed cells):

- decomposed_cobb_douglas_minus_joint_cobb_douglas: -0.000 [-0.001, +0.001]
- joint_ces_minus_drf: 0.009 [+0.004, +0.014]
- joint_ces_minus_equal: 0.030 [+0.024, +0.036]
- joint_ces_minus_joint_linear: 0.569 [+0.562, +0.577]
- joint_cobb_douglas_minus_drf: 0.007 [+0.003, +0.011]
- joint_cobb_douglas_minus_equal: 0.028 [+0.023, +0.033]
- joint_cobb_douglas_minus_joint_linear: 0.568 [+0.560, +0.575]
- joint_leontief_minus_drf: 0.005 [+0.001, +0.009]
- joint_leontief_minus_equal: 0.026 [+0.020, +0.032]
- joint_leontief_minus_joint_linear: 0.566 [+0.558, +0.573]
- joint_linear_minus_drf: -0.561 [-0.569, -0.553]
- joint_linear_minus_equal: -0.539 [-0.547, -0.531]

Per-cell paired differences are in `tables/paired_differences.csv`; the mixed cells are reported individually there so any contention interaction is visible.

## Homogeneous symmetry check

In the homogeneous composition all agents share one workload draw, so any policy difference is a rounding or tie-breaking artifact. The maximum completion spread across all policies is 0.0027.

## Cobb-Douglas decomposition

The Cobb-Douglas weighted-log objective separates across resource columns. The continuous joint and decomposed solutions agree within a tolerance of 0.001 (verified by a randomized solver test). The installed integer allocations are not identical: they differ by up to 2 unit(s) on 1136 of 2400 agent records (47.3%) due to independent rounding tie-breaking, while the mixed-aggregate completion difference is -0.000 [-0.001, +0.001]. This shows the allocation computation can be separated by resource for Cobb-Douglas; it does not decentralize authority, policy selection, contract installation, or enforcement.

Leontief constrains one utility value jointly by several resource ratios and remains cross-resource coupled in the optimization.

## Resource use and individual outcomes

| Policy | Capacity utilization | Allocation consumption |
|--------|----------------------|------------------------|
| equal | 0.924 | 0.924 |
| drf | 0.927 | 0.931 |
| decomposed_cobb_douglas | 0.931 | 0.931 |
| joint_linear | 0.503 | 0.503 |
| joint_cobb_douglas | 0.930 | 0.930 |
| joint_ces | 0.923 | 0.923 |
| joint_leontief | 0.931 | 0.931 |

Capacity utilization is total charged over total capacity; allocation consumption is total charged over total installed allocation.

Individual completion change versus equal quotas, over the mixed-bundle agent records evaluated:

- decomposed_cobb_douglas: mean 0.028, worst -0.250, fraction worse 0.095 (n=1200)
- drf: mean 0.021, worst -0.375, fraction worse 0.173 (n=1200)
- joint_ces: mean 0.030, worst -0.250, fraction worse 0.158 (n=1200)
- joint_cobb_douglas: mean 0.028, worst -0.250, fraction worse 0.098 (n=1200)
- joint_leontief: mean 0.026, worst -0.250, fraction worse 0.122 (n=1200)
- joint_linear: mean -0.539, worst -1.000, fraction worse 0.979 (n=1200)

Where the worst observed change is zero, no sampled agent had lower completion under that policy than under equal quotas in the evaluated workload draws; this is an observation, not a general guarantee.

## Allocation latency

Measured latency includes Python process startup, cvxpy model construction, solve time, output parsing, and integer conversion on the recorded machine. By policy (count, median, p95, max ms):

| Policy | n | median | p95 | max |
|--------|---|--------|-----|-----|
| equal | 400 | 0 | 0 | 4 |
| drf | 400 | 0 | 0 | 0 |
| decomposed_cobb_douglas | 400 | 0 | 0 | 0 |
| joint_linear | 400 | 679 | 762 | 940 |
| joint_cobb_douglas | 400 | 680 | 759 | 882 |
| joint_ces | 400 | 682 | 773 | 946 |
| joint_leontief | 400 | 684 | 763 | 908 |

The four joint solver policies together: n=1600, median 681 ms, p95 763 ms, max 946 ms. Comparison rules allocate in under a millisecond and are not pooled with solver policies. No capacity or bound violation occurred in 2800 runs.

## Interpretation

A linear declaration treats resources as substitutes. In the evaluated contention it produced imbalanced bundles that left some mandatory resources near their minimum, so fewer bundle-structured tasks completed. Cobb-Douglas is multiplicative and Leontief represents fixed-proportion requirements; both keep allocations balanced across each agent's bundle and complete more work. CES with rho=0.5 remains a substitutes model and is intermediate. Declared welfare is reported only within a utility family and is not compared across families.

## Dynamic contract behaviour (appendix simulation)

A secondary solver-level simulation over 100 seeds and 100 epochs with an agent-targeted event schedule and a capacity shock. It uses the same capacity-preserving rounding as the platform, preserves promised and solver floor maps separately, and verifies floors against the rounded allocation. It does not install runtime contracts or drive the runtime clock.

| Policy | Protected agent-epochs | Active-floor epochs | Infeasible-floor epochs | Discrete floor violations | Mean shortfall |
|--------|------------------------|---------------------|-------------------------|---------------------------|----------------|
| reoptimize | 0.0 | 0.0 | 0.00 | 0.00 | 0.000 |
| permanent_floors | 483.0 | 99.0 | 21.56 | 115.95 | 16.662 |
| leases | 442.0 | 97.9 | 3.34 | 32.70 | 4.098 |
| leases_shortfall | 441.6 | 97.9 | 3.34 | 28.87 | 0.326 |

Discrete floor violations arise because floors that hold for the continuous solution can be violated after integer rounding under a later capacity change. Capacity violations total 0.

## Enforcement

A deterministic fault-injection suite with explicit denominators per case (trials, operations, expected/observed successes and denials). Every invariant counter is zero. These are test results, not estimates of operational failure rates. Per-case denominators are in `experiments/enforcement/results/enforcement_cases_full.csv`.

## Scope of claims

The runtime enforces contract authority, the shared per-version consumption ledger, execution binding, service-instance billing, and the solver timeout, and the test suite exercises these directly. Cobb-Douglas separates the allocation computation but does not by itself decentralize authority, policy selection, contract installation, or enforcement. The completion, distributional, latency, and dynamic numbers are results of controlled synthetic experiments with mock task outputs and a latency budget of service constants. No claim is made about strategyproofness, truthful reporting, collusion resistance, protection against a hostile operator, or sandboxing of untrusted agent code.

## Reproduction

From the source revision in `EXPERIMENT_MANIFEST.json`: build the classpath, then run `run_sweep.py --full`, `make_headline.py`, `make_memo.py`, `figures.py`, `make_test_report.py`, `make_manifest.py`, and `check_consistency.py --with-manifest`, plus the enforcement and dynamic drivers. `SOLVER_PYTHON` must point at an interpreter with cvxpy and clarabel.
