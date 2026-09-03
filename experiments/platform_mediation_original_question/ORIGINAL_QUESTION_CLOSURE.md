# Original ARB empirical question: closure at the tested scope

## 0. The conditional claim this closure supports

> In synthetic six-agent, four-resource workloads with strongly heterogeneous complementary
> requirements, a platform-enforced Leontief allocation increased aggregate task completion relative
> to DRF by 2.655 tasks per 48-task run at moderate contention and 1.825 at high contention. With
> declarations estimated from a fixed calibration history before 25% task-source drift, the
> corresponding advantages were 1.605 and 1.500 tasks. A resource-local bundle-progress mechanism also
> beat DRF and captured most of the central mechanism's gain. The additional central-versus-local
> increment was positive but failed the preregistered one-task materiality test. A price-mediated
> implementation reproduced the centralized objective and aggregate completion without invoking the
> central optimizer, although it did not reproduce the exact distribution of losses. These experiments
> establish an allocation-rule existence result within a platform; they do not compare platform
> authority against the absence of a platform.

The starting ARB principle -- that a complementarity-aware, platform-enforced allocation rule can
raise aggregate completion under heterogeneous complementary demand -- is therefore **supported as an
existence result for an allocation rule within a platform**, at the tested scope. It is not converted
here into a universal claim, a causal claim about platform authority, a Pareto claim, or a
strategyproofness, privacy or deployed-decentralization claim.

## 1. The original empirical question

Can platform-mediated allocation improve aggregate task completion when agents require heterogeneous,
complementary bundles of several constrained resources? This report closes that question at the tested
scope using two preregistered experiments and one preregistered adaptive decision rule. Because every
arm -- equal quotas, DRF, and each Leontief variant -- is installed and enforced through the identical
platform runtime, the experiments compare *allocation rules within a platform*; they do not estimate
the causal value of platform authority itself, which is never the varied factor (Section 15).

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
The independent bundle max-min is the strongest tested resource-local comparator -- the strongest
uncoordinated mechanism among those constructed and tested here, not a universally strongest mechanism.

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
  * central Leontief minus independent max-min: +0.685 tasks/run (95% CI [+0.495, +0.880]) (materiality pass: False)
  * independent max-min minus DRF: +1.970 tasks/run (95% CI [+1.650, +2.285]) (pass: True)
  * separable relaxation equals equal quotas allocation rate: 1.000
* **dirichlet_0.1__high**: equal 23.11, DRF 24.64, central Leontief 26.46, independent max-min 26.11, separable relaxation 23.11,
  distributed price Leontief 26.46.
  * central Leontief minus DRF: +1.825 tasks/run (95% CI [+1.545, +2.100]) (five-condition pass: True)
  * central Leontief minus independent max-min: +0.355 tasks/run (95% CI [+0.150, +0.560]) (materiality pass: False)
  * independent max-min minus DRF: +1.470 tasks/run (95% CI [+1.185, +1.755]) (pass: True)
  * separable relaxation equals equal quotas allocation rate: 1.000

Frozen flags: replication_pass=True, coordination_pass=False, independent_positive=True, independent_noninferior=False.

Reading of the coordination test: central Leontief minus the independent resource-local mechanism is a
*statistically positive but immaterial* increment. Its paired interval is above zero in both cells
(+0.685 and +0.355 tasks/run), but the point estimate is below the frozen +1.000-task materiality bar, so the
preregistered `coordination_pass` condition is **False** -- and that machine-checked flag, not the sign of
the increment, is authoritative. The independent mechanism captured about 74% of central Leontief's
advantage over DRF at moderate contention and about 81% at high contention. The evidence therefore does
**not** establish that a positive result over DRF requires joint cross-resource computation, and it does
**not** establish that cross-resource coordination is the cause of the gain; most of the gain is available
from a resource-local rule that never couples the resources.

## 7. Distributed objective and allocation comparison

Distributed classification: **OBJECTIVE_AND_OUTCOME_EQUIVALENT**. Relative objective gap versus the central solver: mean 7.94e-07, median 1.26e-09,
95th percentile 3.71e-06, maximum 7.57e-05; 100.0% of scenarios at most 1e-4. Maximum continuous feasibility residual
1.0e-09; nonconvergences 0. Installed allocation L1 distance mean 0.0031, L-infinity max 8; installed task-outcome
disagreements total 44. Development validation (DISTRIBUTED_SOLVER.md): maximum relative objective gap 3.65e-05 over
575 well-posed scenarios, maximum feasibility residual 1.0e-09.
This distributed arm is a single-process simulation of a price-decomposed algorithm that never calls the
central convex optimizer; it establishes central-solver dispensability, not a deployed distributed system,
privacy, or the absence of all cross-resource communication (DISTRIBUTED_SOLVER.md, Section 6).

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

`ROBUST_AT_MODEST_DRIFT` means only that the frozen *relative* comparison of the stale carrier against
stale DRF passed the five-condition rule at delta 0.25, in both contention cells. It does **not** mean the
mechanism is robust to severe drift. Severe drift is not covered by this headline: at higher delta, stale
declarations reduce absolute completion substantially, and the stale carrier can fall below equal quotas.
For example, at delta 1.00 the stale carrier completes 23.53 vs equal 26.73 (moderate) and 17.65 vs equal 18.95
(high) tasks/run -- below equal quotas in both -- even though the stale-carrier-minus-stale-DRF *relative*
advantage remains positive. The robustness headline is a statement about the relative comparison against
DRF at modest drift, not an absolute-performance or severe-drift guarantee. The preregistered secondary
drift outputs (utilization, distributional outcomes, declaration errors, dissimilarity, realized
contention) are emitted in `results/drift_v1/preregistered_secondary_completion/`.

## 13. Exact safe conditional claim

> In synthetic six-agent, four-resource workloads with strongly heterogeneous complementary
> requirements, a platform-enforced Leontief allocation increased aggregate task completion relative
> to DRF by 2.655 tasks per 48-task run at moderate contention and 1.825 at high contention. With
> declarations estimated from a fixed calibration history before 25% task-source drift, the
> corresponding advantages were 1.605 and 1.500 tasks. A resource-local bundle-progress mechanism also
> beat DRF and captured most of the central mechanism's gain. The additional central-versus-local
> increment was positive but failed the preregistered one-task materiality test. A price-mediated
> implementation reproduced the centralized objective and aggregate completion without invoking the
> central optimizer, although it did not reproduce the exact distribution of losses. These experiments
> establish an allocation-rule existence result within a platform; they do not compare platform
> authority against the absence of a platform.
>
> Where the central and distributed harmed sets are not exactly equal, the distributed method
> reproduced the aggregate result while altering which agents bore the losses (Section 9).

## 14. Claim matrix

| claim | supported | evidence |
|---|---|---|
| Fresh-seed existence result replicates | True | central Leontief minus DRF passes both cells |
| Material cross-resource coordination advantage established | False | central minus independent max-min fails the +1-task materiality bar (coordination_pass) |
| A positive result over DRF requires joint cross-resource computation | False | independent max-min alone is positive over DRF in both cells |
| Independent mechanism fully reproduces central or is noninferior | False | independent minus central is materially negative (independent_noninferior) |
| Central objective and aggregate outcome require the centralized convex solver | False | distributed price solver reproduces objective and aggregate completion |
| Central and distributed implementations impose identical individual losses | False | exact harmed-set equality fails in at least one cell (Section 9) |
| Relative advantage survives the preregistered modest-drift cell | True | carrier stale minus DRF stale passes at delta 0.25, both cells |

## 15. Material limitations and where consequential authority remains

Synthetic Dirichlet(0.1) workloads; six agents, four resources; the tested declaration sources and drift
levels; installation and enforcement through the canonical runtime. Not tested: strategic reporting,
collusion, real deployment distributions, governance, contract remedies. Not a Pareto improvement; no
individual-rationality, strategyproofness or collusion-resistance claim is made.

Consequential platform authority remains **outside** what these experiments varied. All six arms are
installed and enforced by the same platform runtime through the identical contract path, so the study
compares allocation *rules* holding platform authority fixed. It does not compare a platform against no
platform, does not decentralize installation or enforcement (only the *computation* of the Leontief
allocation was shown to be decomposable), and therefore does not estimate the causal value of platform
authority. The distributed arm computes the allocation by price-mediated tatonnement but still relies on
the platform to install and enforce the resulting contracts. Whether platform authority is itself
beneficial, and how it should be governed, remain open questions rather than settled by this closure.

## 16. Immutable GitHub links

* Preregistration: https://github.com/CARMA-org/arbitration-platform/commit/7ebf8b70366b8b68a90554a722f097d8acea3f01
* Architecture result: https://github.com/CARMA-org/arbitration-platform/commit/2f9fa1b05a38d941511491e030d3e964232350eb
* Carrier decision: recorded in DRIFT_CARRIER_DECISION.json at the architecture result commit
* Drift result: https://github.com/CARMA-org/arbitration-platform/commit/3204646f74901bb357f614e2f5ab4c1b276fb449
* First independent verification: https://github.com/CARMA-org/arbitration-platform/commit/d2d77dbe33c4a5b6f9770f225b19ee68b45f1514
* Comprehensive verification (v2): https://github.com/CARMA-org/arbitration-platform/commit/c678a0a96aba563ceff52e4d6b889fb90db316ec
* Final consolidated independent verification: see `VERIFICATION_SUMMARY_FINAL.md`, `VERIFICATION_FINAL_AUDIT.md` and `verify_oq_final.py` in this directory

## 17. Why the question is closed at the tested scope

The fresh-seed replication, the coordination/materiality test against the strongest tested resource-local
mechanism, the distributed-versus-central objective, completion and harmed-set comparison, and the
declaration-calibration-and-drift stress test together answer, at the tested scope: the ARB principle is
supported as an allocation-rule existence result within a platform; a *material* cross-resource
coordination advantage was not established; a positive result over DRF does not require joint computation;
the central objective and aggregate completion do not require the centralized convex solver; central and
distributed implementations do not impose identical individual losses; and the relative advantage survives
modest but not severe declaration drift. Consequential platform authority was held fixed across all arms
and so was not evaluated. Strategic reporting, collusion, real prevalence, governance, contract remedies,
and the value of platform authority itself are separate questions, not unfinished controls of this
experiment; this closure settles the tested allocation-rule questions without asserting that no further
empirical question could matter.

