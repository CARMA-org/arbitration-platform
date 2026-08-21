# Platform-Mediated Utility Alignment: Results

## Question

A platform allocates several bounded resources to agents running bundle-structured tasks, where a task completes only when every mandatory step is afforded and those steps jointly require several resources. This study measures how the semantics of an agent's utility declaration affect completed work in these synthetic workloads, and when the allocation computation needs cross-resource coordination. Each seed is an independent workload draw. Tasks are mock and resource requirements are synthetic.

## Design

Compositions ['homogeneous', 'mixed_bundle'] at contention {'moderate': 1.3, 'high': 1.9}. 6 agents, 8 tasks each. 100 test seeds per cell over 4 cells (2800 feasible runs, 0 infeasible, 16800 agent records). Distinct workload hashes per cell: {"homogeneous__moderate": 100, "homogeneous__high": 100, "mixed_bundle__moderate": 100, "mixed_bundle__high": 100}.

Realized contention ranges over the 100 seeds (min-max by resource): moderate cells and high cells respectively. Mixed cells: COMPUTE 1.30-1.30; MEMORY 1.30-1.30; API_CREDITS 1.30-1.30; DATASET 1.30-1.31 | COMPUTE 1.90-1.90; MEMORY 1.90-1.90; API_CREDITS 1.89-1.91; DATASET 1.89-1.91.

Each seed samples task types uniformly from the four archetypes; homogeneous agents share one sampled queue, mixed agents sample independently. The declaration primitive for linear, Cobb-Douglas, CES, and Leontief is each agent's normalized mandatory-demand vector from its exact queue; DRF receives the raw mandatory-demand vector; operator priorities are equal. Every policy in a cell-seed receives the same scenario hash. Paired differences use a stratified paired bootstrap over seeds (bootstrap seed 12345, 2000 resamples, seed as the resampling unit); the mixed aggregate weights the two mixed cells equally.

Solver status counts by joint policy: {"joint_linear": {"optimal": 400}, "joint_cobb_douglas": {"optimal": 400}, "joint_ces": {"optimal": 400}, "joint_leontief": {"optimal": 400}}. Fallback was disabled; fallback used: {}; infeasible runs: 0.

## Mixed-bundle results

Mean task completion by policy and mixed cell:

| Policy | mixed_bundle__high | mixed_bundle__moderate |
|--------|--------|--------|
| equal | 0.513 | 0.716 |
| drf | 0.523 | 0.749 |
| decomposed_cobb_douglas | 0.529 | 0.757 |
| joint_linear | 0.047 | 0.104 |
| joint_cobb_douglas | 0.529 | 0.757 |
| joint_ces | 0.537 | 0.752 |
| joint_leontief | 0.523 | 0.759 |

Mixed-bundle equal-weighted stratified paired completion differences (mean [95% CI], 100 seeds in each of 2 mixed cells):

- decomposed_cobb_douglas_minus_joint_cobb_douglas: 0.000 [+0.000, +0.000]
- joint_ces_minus_drf: 0.009 [+0.004, +0.014]
- joint_ces_minus_equal: 0.030 [+0.024, +0.036]
- joint_ces_minus_joint_linear: 0.569 [+0.562, +0.577]
- joint_cobb_douglas_minus_drf: 0.007 [+0.003, +0.011]
- joint_cobb_douglas_minus_equal: 0.028 [+0.023, +0.033]
- joint_cobb_douglas_minus_joint_linear: 0.568 [+0.560, +0.575]
- joint_leontief_minus_drf: 0.005 [+0.001, +0.009]
- joint_leontief_minus_equal: 0.026 [+0.021, +0.032]
- joint_leontief_minus_joint_linear: 0.566 [+0.558, +0.573]
- joint_linear_minus_drf: -0.561 [-0.568, -0.553]
- joint_linear_minus_equal: -0.539 [-0.548, -0.531]

The nonlinear joint policies (Cobb-Douglas, CES, Leontief) complete far more bundle-structured work than joint linear utility. Their advantage over equal quotas and standard DRF is small; equal quotas and DRF are strong comparators. Per-cell paired differences are in `tables/paired_differences.csv`.

## Homogeneous symmetry check

In the homogeneous composition all agents share one workload draw, so any policy difference is a rounding or tie-breaking artifact. The maximum completion spread across all policies is 0.0027.

## Cobb-Douglas decomposition

The Cobb-Douglas weighted-log objective separates across resource columns. A measured comparison of the exact bounded-log decomposed solver against the joint Cobb-Douglas solver over 600 bounded instances (seed 7788, box and capacity constraints binding) finds a maximum absolute continuous difference of 0.00123725 (maximum relative error 0.000223682) over the 557 instances where the joint solver reached a genuinely optimal, feasible solution. The joint solver returned optimal_inaccurate in 40 instances and failed in 3; the platform's capacity-preserving rounding clamps such solutions to feasibility.

In the primary experiment the installed integer allocations of decomposed and joint Cobb-Douglas differ by up to 1 unit(s) on 774 of 2400 agent records (32.2%) because rounding is applied independently, yet only 5 of 400 run-level completion outcomes differ (maximum completion difference 0.0208333). The mixed-aggregate completion difference is 0.000 [+0.000, +0.000]. The computation can be separated by resource for Cobb-Douglas; contract authority, installation, versioning, and enforcement remain coordinated platform operations.

Leontief constrains one utility value jointly by several resource ratios and remains cross-resource coupled in the optimization.

## Resource use and individual outcomes

| Policy | Capacity utilization | Allocation consumption |
|--------|----------------------|------------------------|
| equal | 0.924 | 0.924 |
| drf | 0.927 | 0.931 |
| decomposed_cobb_douglas | 0.930 | 0.930 |
| joint_linear | 0.503 | 0.503 |
| joint_cobb_douglas | 0.930 | 0.930 |
| joint_ces | 0.923 | 0.923 |
| joint_leontief | 0.931 | 0.931 |

Capacity utilization is total charged over total capacity; allocation consumption is total charged over total installed allocation.

Individual completion change versus equal quotas, over the mixed-bundle agent records evaluated (denominator n per policy):

- decomposed_cobb_douglas: mean 0.028, worst -0.250, fraction worse 0.098 (n=1200)
- drf: mean 0.021, worst -0.375, fraction worse 0.173 (n=1200)
- joint_ces: mean 0.030, worst -0.250, fraction worse 0.158 (n=1200)
- joint_cobb_douglas: mean 0.028, worst -0.250, fraction worse 0.098 (n=1200)
- joint_leontief: mean 0.026, worst -0.250, fraction worse 0.122 (n=1200)
- joint_linear: mean -0.539, worst -1.000, fraction worse 0.979 (n=1200)

The nonlinear joint policies make some individual agents worse off than equal quotas; this is not a Pareto improvement and no individual guarantee is claimed.

## Allocation-computation latency

Allocation-computation latency includes Python process startup, cvxpy model construction, solve, output parsing, and integer conversion on the recorded machine, measured during a primary run with no other solver-heavy work concurrent. By policy (count, median, p95, max ms):

| Policy | n | median | p95 | max |
|--------|---|--------|-----|-----|
| equal | 400 | 0 | 0 | 4 |
| drf | 400 | 0 | 0 | 0 |
| decomposed_cobb_douglas | 400 | 0 | 0 | 1 |
| joint_linear | 400 | 644 | 674 | 798 |
| joint_cobb_douglas | 400 | 644 | 670 | 724 |
| joint_ces | 400 | 649 | 678 | 811 |
| joint_leontief | 400 | 649 | 683 | 751 |

The four joint solver policies together: n=1600, median 646 ms, p95 678 ms, max 811 ms. The comparison rules (equal quotas, DRF, decomposed Cobb-Douglas) allocate in under a millisecond and are not pooled with solver policies. No capacity or bound violation occurred in 2800 runs.

## Interpretation

A linear declaration treats resources as substitutes. In the evaluated workloads it produced imbalanced bundles that left some mandatory resources near their minimum, so far fewer bundle-structured tasks completed. Cobb-Douglas is multiplicative and Leontief represents fixed-proportion requirements; both keep allocations balanced across each agent's bundle and complete more work. CES with rho=0.5 is also a substitutes model, but its concavity prevents the extreme concentration seen under joint linear utility in these workloads. Declared welfare is reported only within a utility family and is not compared across families. This is behaviour in the tested synthetic workloads, not a general advantage for joint optimization or a claim about real service performance.

## Dynamic contract behaviour (appendix simulation)

A secondary solver-level simulation over 100 seeds and 100 epochs with an agent-targeted event schedule and a capacity shock. It uses the same capacity-preserving rounding as the platform, preserves promised and applied floor maps separately, and verifies floors against the rounded allocation. It does not install runtime contracts or drive the runtime clock. Counts below are mean per 100-epoch seed with the total across all 100 seeds in parentheses.

| Policy | Protected agent-epochs | Infeasible-floor epochs | Discrete floor violations | Floor shortfall (total) |
|--------|------------------------|-------------------------|---------------------------|-------------------------|
| reoptimize | 0.0 (0) | 0.00 (0) | 0.00 (0) | 0.0 |
| permanent_floors | 483.0 (48301) | 21.56 (2156) | 115.95 (11595) | 233429.0 |
| leases | 442.0 (44196) | 3.34 (334) | 32.70 (3270) | 15790.6 |
| leases_shortfall | 441.6 (44161) | 3.34 (334) | 28.87 (2887) | 1009.1 |

Discrete floor violations arise because floors that hold for the continuous solution can be violated after integer rounding under a later capacity change. Capacity violations total 0.

## Enforcement

A deterministic fault-injection suite with explicit denominators per case (trials, operations, expected/observed successes and denials). Every invariant counter is zero. These are deterministic test outcomes, not estimates of operational failure rates or evidence of security against a hostile operator. Per-case denominators are in `experiments/enforcement/results/enforcement_cases_full.csv`.

## Scope of claims

The runtime converts agent declarations and exogenous operator priorities into a versioned allocation snapshot, conservation-checks it before installation, and enforces consumption through a shared per-version ledger; execution checks registration, version, expiry, context, and service-instance identity, and does not call the backend after a denial. Unsupported utility families are rejected rather than approximated, and solver timeouts fail closed. The test suite exercises these directly. Cobb-Douglas separates the allocation computation but does not by itself decentralize authority, policy selection, contract installation, or enforcement. The completion, distributional, latency, and dynamic numbers are results of controlled synthetic experiments with mock task outputs and synthetic service-cost constants. No claim is made about strategyproofness, truthful reporting, collusion resistance, individual rationality, protection against a hostile operator, or sandboxing of untrusted agent code.

## Reproduction

From the source revision in `EXPERIMENT_MANIFEST.json`: build the classpath, then run `run_sweep.py --full`, `validate_decomposition.py`, `make_headline.py`, `make_memo.py`, `figures.py`, `make_test_report.py`, `make_manifest.py`, and `check_consistency.py --with-manifest`, plus the enforcement and dynamic drivers. `SOLVER_PYTHON` must point at an interpreter with cvxpy and clarabel.
