# Original-question closure: public preregistration (frozen protocol v1)

This document publicly preregisters two experiments and one adaptive decision rule. It is
committed and pushed before any confirmatory seed or outcome is generated. Nothing in this
protocol may be edited after confirmatory execution begins; an outcome-relevant defect
discovered later requires a versioned replacement protocol with a wholly new seed
namespace, pushed before restarting, and preservation of the aborted attempt.

## 0. Terminal question and prior result

The original empirical question is: can platform-mediated allocation improve aggregate
task completion when agents require heterogeneous, complementary bundles of several
constrained resources? A prior verified heterogeneity experiment (experimental commit
`073a5d6`, verified at `473d707`) established a conditional existence result under exact
pending-queue declarations: joint Leontief minus DRF was +2.595 tasks per 48-task run
(95% CI [2.295, 2.890]) at moderate contention and +1.770 ([1.505, 2.045]) at high
contention, both passing a frozen five-condition rule, with a mean gain over equal quotas,
about 4.9% and 7.0% of agents worse than equal quotas (not a Pareto improvement), and a
generally-increasing-but-not-strictly-monotone heterogeneity response. That result was
conditional on synthetic sufficiently heterogeneous workloads and exact declarations and
did not test declaration error, decentralized authority, or real-world prevalence.

This closure determines, at the tested scope: (1) whether the result survives declarations
estimated from finite calibration and modest workload drift; (2) whether the gain comes
from cross-resource coordination rather than another objective or declaration difference;
(3) whether the joint Leontief outcome requires centralized computation; (4) whether
central and distributed implementations impose losses on the same agents; and (5) whether
consequential authority remains centralized in installation and enforcement even if
allocation computation distributes.

## 1. Transparency disclosures

- Development-seed effect estimates recorded in the development checkpoint were viewed
  during engineering de-risking. They are exploratory: development-seed distributed-minus-
  central completion about +0.0125, central-minus-independent-max-min about +3.16, and
  central-minus-DRF about +2.39 tasks per run on sampled development scenarios.
- Development data were used only to validate the solvers and comparators (objective
  equivalence, feasibility, distinctness, and the relaxation collapse), never to select an
  algorithm, tolerance, threshold, delta level, primary cell, or carrier by task-completion
  outcome.
- No confirmatory seed or outcome existed before this preregistration. Both experiments and
  the adaptive carrier rule are frozen before any confirmatory execution.

## 2. Shared experimental rules

Six agents, eight execution tasks per agent, four resources, the existing mandatory task
archetypes, equal agent priority weights, moderate and high contention at ratios 1.3 and
1.9, 200 fresh paired confirmatory seeds per primary cell. Within a paired seed the
scenario, task queues, capacities, floors, upper bounds, ordering, rounding and enforcement
path are identical across arms. Queue-order completion is the primary outcome; exact
best-subset completion by direct enumeration of all 256 subsets of each eight-task queue is
a robustness outcome. Completion differences are reported in tasks per 48-task run. The
scenario seed is the paired resampling unit. The paired bootstrap uses 20000 resamples,
bootstrap seed 20260902, and percentile 95% intervals (the prior frozen convention). No
result-conditioned redraw, exclusion, seed replacement, arm removal, or scenario
suppression is permitted. Confirmatory seeds and task-workload hashes are disjoint from the
canonical, pilot, prior-confirmatory, comparator-development and distributed-validation
seeds (`CONFIRMATORY_SEED_MANIFEST.json`). Every installed allocation is enforced through
the canonical Java runtime contract path; the Python-computed mechanisms are installed
verbatim through the harness `preinstalledAllocation` field, which uses the identical
`installContracts` and execution path as the internally computed policies.

## 3. Comparator audit (frozen; see COMPARATOR_AUDIT.md)

Four concepts are distinguished: equal quotas, DRF (dominant-resource-share coupling
across the full demand vector), the independent bundle max-min (resource-local weighted
progressive filling of bundle progress `x_ir / a_ir`, keeping the declared complementarity
coefficient), and the separable weighted-log Leontief relaxation (which drops the
cross-resource utility consensus so the coefficient magnitude cancels and, under equal
weights and inactive bounds, collapses to equal quotas). The audit establishes, on
constructed and 120 randomized development scenarios, that the independent bundle max-min
is mathematically and empirically distinct from DRF (differs in 120/120 scenarios) and
that the separable relaxation equals equal quotas (120/120) while unequal weights or active
bounds break the collapse. The independent bundle max-min is therefore the strongest tested
uncoordinated resource-local comparator; the separable relaxation is retained only as a
structural control and is not the primary comparator.

## 4. Architecture experiment (frozen; config/architecture_v1.json)

Fresh Dirichlet(0.1) workloads under the confirmed heterogeneity scenario and capacity
construction, exact pending-queue declarations, unit floors, two contention levels, 200
paired seeds per cell. Six arms per scenario: `equal`, `drf`, `central_joint_leontief`
(the verified joint Leontief mechanism, giving a fresh-seed replication), and the
resource-local `independent_bundle_maxmin`, structural `separable_leontief_relaxation`, and
`distributed_price_leontief`. Total 2 x 200 x 6 = 2400 policy runs.

The central continuous problem maximizes `sum_i c_i log(u_i)` subject to `u_i <= a_ij/r_ij`
for `r_ij>0`, floors, upper bounds, and capacities, with equal weights `c_i`. The
distributed price solver targets the same objective by price-mediated tatonnement with no
central-solver call; its algorithm and every numerical parameter are frozen in
DISTRIBUTED_SOLVER.md and validated to a maximum relative objective gap of 3.65e-5 and
maximum feasibility residual 1e-9 over 575 well-posed development scenarios.

### 4.1 Frozen five-condition rule

For a treatment-minus-base comparison in a contention cell, using queue-order completion:
(1) treatment mean higher than base; (2) paired 95% percentile bootstrap interval strictly
above zero; (3) point estimate at least +1.000 task per run; (4) treatment does not
increase the observed zero-completion fraction; (5) both arms have zero infeasibility,
fallback, capacity and bound violations.

- Fresh replication: `central_joint_leontief - drf`, both cells must pass.
- Coordination test: `central_joint_leontief - independent_bundle_maxmin`, both cells must
  pass, and fresh replication must pass, and the audit must establish the independent
  mechanism distinct from DRF.
- Independent mechanism vs DRF: `independent_bundle_maxmin - drf`, both cells (secondary).
- Central–independent equivalence: absolute mean difference at most 0.25 tasks per run and
  paired 95% interval within [-0.5, +0.5].
- Separable-relaxation structural result: allocation- and outcome-equality rates versus
  equal quotas, DRF, the independent mechanism and central Leontief.

### 4.2 Distributed equivalence classification

`OBJECTIVE_EQUIVALENT` requires zero capacity/bound violations, maximum feasibility
residual at most 1e-7, relative objective gap at most 1e-4 in at least 99% of scenarios,
and maximum relative gap at most 1e-3. `OUTCOME_EQUIVALENT` requires absolute distributed-
minus-central mean completion at most 0.25 tasks per run with paired 95% interval within
[-0.5, +0.5]. Reported: mean/median/95th/max objective gap; mean/median/95th/max allocation
distance (L1 normalized and L-infinity); iteration, message and runtime counts; primal and
dual residuals; nonconvergence count; installed task-outcome disagreement. Final label is
one of `OBJECTIVE_AND_OUTCOME_EQUIVALENT`, `OBJECTIVE_EQUIVALENT_OUTCOME_DIFFERENT`,
`NOT_EQUIVALENT`, `TECHNICALLY_INVALID`.

### 4.3 Distributional and harmed-set analysis

For every arm, relative to equal quotas and DRF: number and fraction of agents completing
fewer tasks, mean/median/worst loss among harmed agents, number and fraction and mean/
median gain among beneficiaries, zero-completion fraction, observed minimum, bottom decile,
mean completion, and utilization. For central versus distributed Leontief: exact harmed-set
equality, harm-indicator agreement, harmed-set Jaccard, central-only and distributed-only
harmed counts, precision and recall (central as reference), per-agent completion equality,
maximum and mean absolute per-agent completion difference, bottom-decile membership
agreement. `DISTRIBUTIONALLY_EQUIVALENT` requires harm-indicator agreement at least 99%,
harmed-set Jaccard at least 0.95, harmed-fraction absolute difference at most 0.5
percentage points, and per-agent completion equality at least 99%. If aggregate
equivalence passes but harmed-set equivalence fails, the report states that the distributed
method reproduced the aggregate result while altering who bore the losses.

## 5. Frozen adaptive carrier rule (select_drift_carrier.py)

Computed only from the architecture raw data, with no manual override. Define
`replication_pass` (central beats DRF in both cells), `coordination_pass` (central beats
the independent mechanism in both cells and `replication_pass`), `independent_positive`
(independent beats DRF in both cells), `independent_noninferior` (independent-minus-central
mean no worse than -0.25 with paired interval within [-0.5, +0.5] in both cells), and
`distributed_equivalent` (both `OBJECTIVE_EQUIVALENT` and `OUTCOME_EQUIVALENT`). Priority:

1. If `independent_positive` and `independent_noninferior`: `independent_bundle_maxmin`.
2. Else if `coordination_pass`: `distributed_price_leontief` if `distributed_equivalent`,
   else `central_joint_leontief`.
3. Else if `replication_pass`: `independent_bundle_maxmin` if `independent_noninferior`,
   else `central_joint_leontief`.
4. Else `central_joint_leontief_diagnostic`.

`DRIFT_CARRIER_DECISION.json` records every input estimate and interval, every condition
with pass/fail, the selected carrier and interpretation, the architecture raw-data hashes,
the public preregistration hash, and the script hash. The architecture result and carrier
decision are committed and pushed before drift execution begins.

## 6. Declaration-calibration-and-drift experiment (frozen; config/drift_v1.json)

Applied to the selected carrier. For each seed and agent draw a baseline distribution `p_i`
and an independent drift target `q_i` from Dirichlet(0.1), fixed across delta and
contention. Each execution task is drawn from `p_i` with probability `1 - delta` and from
`q_i` with probability `delta`, using common random numbers across delta levels (a single
per-draw uniform decides the source; single per-draw baseline and target archetypes are
fixed), so raising delta only switches draws whose uniform falls below delta. Delta ranges
over {0, 0.25, 0.5, 0.75, 1}. Recorded separately: drift-source draw count, changed task
identities, task-composition total variation, mandatory-demand total variation, per-agent
entropy and cross-agent dissimilarity. A drift-source draw may reproduce the original
archetype, so delta 0.25 produces two drift-source draws in expectation over eight tasks
rather than necessarily two changed identities.

Declarations are constructed from a fixed baseline 48-task calibration history
(`stale_calibration`), a refreshed 48-task history from the current mixture
(`refreshed_calibration`), the current latent mixture (`latent_distribution_oracle`), and
the exact realized queue (`execution_queue_oracle`), using the same aggregation,
normalization and zero handling as the confirmed exact-pending implementation. The
execution-oracle declaration is identical to the confirmed declaration for an identical
queue (tested). Physical capacity is a function of baseline latent expected demand and the
contention ratio only, frozen across every delta and declaration condition; floors and
upper bounds are policy- and declaration-independent and derived from baseline expected
demand, so no execution information leaks through capacity or a bound. Realized execution
demand over fixed capacity is recorded as an outcome.

Arms: `equal`, and `drf_<source>` and `carrier_<source>` for each of the four declaration
sources (9 arms; 18000 policy runs). If the carrier is `distributed_price_leontief`, four
`central_ref_<source>` technical-reference arms verify objective, allocation and outcome
equivalence as coefficients drift.

Co-primary cells: delta 0.25 at moderate and high contention. Primary comparison
`carrier_stale_calibration - drf_stale_calibration` under the same five-condition rule; both
cells must pass for `ROBUST_AT_MODEST_DRIFT`. Secondary, per delta and contention: stale,
refreshed, latent-oracle and execution-oracle carrier-minus-DRF; stale-minus-refreshed for
carrier and DRF; the difference-in-differences `(stale carrier - stale DRF) - (refreshed
carrier - refreshed DRF)`; calibration, staleness and latent-oracle declaration errors;
queue-order and exact best-subset completion; distributional outcomes; utilization;
realized contention; dissimilarity. Classification is one of `ROBUST_AT_MODEST_DRIFT`,
`REFRESH_DEPENDENT`, `ORACLE_DEPENDENT`, `NO_MATERIAL_ADVANTAGE_IN_NEW_DESIGN`. Strict
monotonicity over delta is neither required nor claimed.

## 7. Verification

After both experiments are committed, an independent verification branch reconstructs from
the raw data — without importing the experiment's analysis, decision, bootstrap, manifest,
local-task-optimizer or carrier-selection modules — the row counts, keys, pairing, workload
identity across arms, seed and hash disjointness, architecture means, paired differences and
intervals, every five-condition decision, the comparator difference from DRF, the separable-
relaxation structural result, the distributed objective and allocation gaps, convergence,
every distributional-loss statistic, harmed-set membership, the architecture carrier
selection, the drift means, intervals and classifications, and the exact best-subset
completion by direct enumeration of all 256 subsets. The distributed code is inspected to
confirm it never invokes the central solver. A full clean clone (`git clone --no-hardlinks`)
reruns both experiments, their analyses, the carrier selection and the manifests and
requires exact equality of all non-timing fields.

## 8. Scope of the closure

After these experiments are completed and verified, the original ARB empirical question is
closed at the tested scope: synthetic Dirichlet(0.1) heterogeneous workloads, six agents,
four resources, the tested declaration sources and drift levels, and installation and
enforcement through the canonical runtime. Strategic reporting, collusion, real deployment
distributions, governance, and contract remedies remain separate questions, not unfinished
controls of this experiment.
