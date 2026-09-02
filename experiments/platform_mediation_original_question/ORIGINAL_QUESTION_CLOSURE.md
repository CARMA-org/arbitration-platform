# Original ARB empirical question: closure at the tested scope

## 1. The original empirical question

Can platform-mediated allocation improve aggregate task completion when agents require heterogeneous,
complementary bundles of several constrained resources? This report closes that question at the tested
scope using two preregistered experiments and one preregistered adaptive decision rule.

## 2. The verified prior result

The prior heterogeneity experiment (experimental commit `073a5d6`, independently verified at `473d707`)
found, under exact pending-queue declarations with six agents, eight tasks, four resources and Dirichlet(0.1)
workloads, that joint Leontief minus DRF was +2.595 tasks per 48-task run (95% CI [2.295, 2.890]) at moderate
contention and +1.770 ([1.505, 2.045]) at high contention, both passing a frozen five-condition rule, with a
mean gain over equal quotas, about 4.9% and 7.0% of agents worse than equal quotas (not a Pareto
improvement), and a generally-increasing-but-not-strictly-monotone heterogeneity response.

## 3. Comparator audit

Four concepts were distinguished (COMPARATOR_AUDIT.md): equal quotas, DRF (dominant-resource-share coupling),
independent bundle max-min (resource-local weighted progressive filling of bundle progress, keeping the
declared complementarity coefficient), and the separable weighted-log Leontief relaxation. Over 120 randomized
development scenarios the independent bundle max-min differed from DRF in 120 of 120 (normalized L1 mean 0.1935,
L-infinity up to 71 units), and the separable relaxation equalled equal quotas in 120 of 120.
The independent bundle max-min is therefore the strongest tested uncoordinated resource-local comparator.

## 4. Why the separable relaxation collapses to equal quotas

Dropping the cross-resource utility consensus leaves each resource owner maximizing sum_i w_i log(x_ir).
Substituting u_ir = x_ir / a_ir gives sum_i w_i log(x_ir) minus a constant, so the coefficient magnitude a_ir
cancels and, under equal weights with inactive special bounds, the owner allocates an equal share to the
participating agents. Unequal weights or active bounds break the collapse (shown in the audit).

## 5. Architecture design

Six arms per scenario (equal, DRF, central joint Leontief, independent bundle max-min, separable Leontief
relaxation, distributed price Leontief), fresh Dirichlet(0.1) workloads under the confirmed scenario and
capacity construction, exact pending declarations, unit floors, moderate and high contention, 200 paired
seeds per cell, all enforced through the canonical Java runtime. Queue-order completion is primary; exact
best-subset completion (all 256 subsets) is a robustness outcome.

## 6. Architecture results (queue-order, tasks per 48-task run)

* **dirichlet_0.1__moderate**: equal 33.83, DRF 34.59, central Leontief 37.25, independent max-min 36.56, separable relaxation 33.83,
  distributed price Leontief 37.24.
  * central Leontief minus DRF: +2.655 tasks/run (95% CI [+2.360, +2.950]) (five-condition pass: True)
  * central Leontief minus independent max-min: +0.685 tasks/run (95% CI [+0.495, +0.880]) (pass: False)
  * independent max-min minus DRF: +1.970 tasks/run (95% CI [+1.650, +2.285]) (pass: True)
  * separable relaxation equals equal quotas allocation rate: 1.000
* **dirichlet_0.1__high**: equal 23.11, DRF 24.64, central Leontief 26.46, independent max-min 26.11, separable relaxation 23.11,
  distributed price Leontief 26.46.
  * central Leontief minus DRF: +1.825 tasks/run (95% CI [+1.545, +2.100]) (five-condition pass: True)
  * central Leontief minus independent max-min: +0.355 tasks/run (95% CI [+0.150, +0.560]) (pass: False)
  * independent max-min minus DRF: +1.470 tasks/run (95% CI [+1.185, +1.755]) (pass: True)
  * separable relaxation equals equal quotas allocation rate: 1.000

Frozen flags: replication_pass=True, coordination_pass=False, independent_positive=True, independent_noninferior=False.

## 7. Distributed objective and allocation comparison

Distributed classification: **OBJECTIVE_AND_OUTCOME_EQUIVALENT**. Relative objective gap versus the central solver: mean 7.94e-07, median 1.26e-09,
95th percentile 3.71e-06, maximum 7.57e-05; 100.0% of scenarios at most 1e-4. Maximum continuous feasibility residual
1.0e-09; nonconvergences 0. Installed allocation L1 distance mean 0.0031, L-infinity max 8; installed task-outcome
disagreements total 44. Development validation (DISTRIBUTED_SOLVER.md): maximum relative objective gap 3.65e-05 over
575 well-posed scenarios, maximum feasibility residual 1.0e-09.

## 8. Distribution of gains and losses

* **dirichlet_0.1__moderate**, central Leontief vs equal quotas: 4.4% of agents harmed (mean loss 1.189 tasks), 44.4% better
  (mean gain 1.403); zero-completion fraction 0.000. Not a Pareto improvement.
* **dirichlet_0.1__high**, central Leontief vs equal quotas: 6.7% of agents harmed (mean loss 1.113 tasks), 45.2% better
  (mean gain 1.400); zero-completion fraction 0.000. Not a Pareto improvement.

## 9. Harmed-set comparison (central vs distributed Leontief)

* **dirichlet_0.1__moderate** (harmed relative to equal quotas): harm-indicator agreement 0.999, harmed-set Jaccard 0.981,
  per-agent completion equality 0.988, exact harmed-set equal: False, max per-agent completion difference 2.000 tasks.
* **dirichlet_0.1__high** (harmed relative to equal quotas): harm-indicator agreement 0.993, harmed-set Jaccard 0.908,
  per-agent completion equality 0.975, exact harmed-set equal: False, max per-agent completion difference 2.000 tasks.

## 10. Selected drift carrier

The frozen adaptive rule selected **central_joint_leontief** (branch 3). The prior existence result replicates; central Leontief carries the drift test.

## 11. Drift design and 12. Drift results

The carrier was retested with declarations from stale calibration, refreshed calibration, the latent
distribution oracle and the exact execution-queue oracle, over delta in {0, .25, .5, .75, 1} with common
random numbers, frozen capacity from baseline latent demand, and policy/declaration-independent bounds.
Co-primary decision (carrier stale minus DRF stale at delta 0.25):
* delta0.25__moderate: +1.605 tasks/run (95% CI [+1.315, +1.895]), pass=True
* delta0.25__high: +1.500 tasks/run (95% CI [+1.230, +1.775]), pass=True
Declaration-robustness classification: **ROBUST_AT_MODEST_DRIFT**.

## 13. Exact safe central claim

> The tested coordinated Leontief outcome did not require centralized computation. Resource-price
> coordination reproduced its continuous objective and aggregate completion. Installation and enforcement
> nevertheless remained platform-controlled.
> The distributed method reproduced the aggregate result; where the harmed sets are not exactly equal,
> it altered which agents bore the losses (Section 9).

## 14. Claim matrix

| claim | supported | evidence |
|---|---|---|
| Prior existence result replicates on fresh seeds | True | central Leontief minus DRF, both cells |
| Cross-resource coordination beats the strongest uncoordinated resource-local mechanism | False | central minus independent max-min |
| Positive result does not require joint computation | False | independent max-min positive and noninferior |
| Joint outcome does not require centralized computation | True | distributed equivalence |

## 15. Material limitations

Synthetic Dirichlet(0.1) workloads; six agents, four resources; the tested declaration sources and drift
levels; installation and enforcement through the canonical runtime. Not tested: strategic reporting,
collusion, real deployment distributions, governance, contract remedies. Not a Pareto improvement; no
individual-rationality, strategyproofness or collusion-resistance claim is made.

## 16. Immutable GitHub links

* Preregistration: https://github.com/CARMA-org/arbitration-platform/commit/7ebf8b70366b8b68a90554a722f097d8acea3f01
* Architecture result: https://github.com/CARMA-org/arbitration-platform/commit/2f9fa1b05a38d941511491e030d3e964232350eb
* Carrier decision: recorded in DRIFT_CARRIER_DECISION.json at the architecture result commit
* Drift result: https://github.com/CARMA-org/arbitration-platform/commit/3204646f74901bb357f614e2f5ab4c1b276fb449
* Verification: https://github.com/CARMA-org/arbitration-platform/commit/<verify>

## 17. Why the question is now closed at the tested scope

The fresh-seed replication, the coordination test against the strongest tested uncoordinated resource-local
mechanism, the distributed-versus-central objective, completion and harmed-set comparison, and the
declaration-calibration-and-drift stress test together answer, at the tested scope, whether the ARB
principle holds, whether coordination is the cause, whether centralized computation is required, whether
central and distributed implementations harm the same agents, and where consequential authority remains.
Strategic reporting, collusion, real prevalence, governance and contract remedies are separate questions,
not unfinished controls of this experiment.

