# Declaration-drift evaluation protocol (draft)

Status: design draft only. Not implemented, not frozen, not run. This document contains no experimental results.

This draft plans the next ARB experiment. It builds on the design sketch in
`DECLARATION_STALENESS_DESIGN.md` and refines it. It does not change that file, any
experiment code, any configuration, any raw data, any report, any manifest, or any
verification artifact. It proposes a generator, a set of conditions, an outcome set,
a pairing scheme, and an analysis plan for later human approval.

## What the completed experiment established, and what it did not

The completed heterogeneity experiment built each agent's declaration from that
agent's exact pending execution queue. Under that oracle-information condition, the
joint Leontief allocator completed more tasks than DRF in the Dirichlet 0.1 regime
at both tested contention levels. Reconstructed from the committed raw data, the
Leontief mean was 0.7750 against DRF 0.7209 at moderate contention, a paired
difference of 2.595 tasks per 48-task run with a 95 percent interval of
[2.295, 2.890], and 0.5452 against 0.5083 at high contention, a difference of 1.770
with interval [1.505, 2.045]. An exact offline best-subset task-selection control did
not remove the advantage. The advantage over equal quotas was a secondary mean
comparison and was not a per-agent improvement, because about 4.9 percent of agents
at moderate contention and 7.0 percent at high contention completed fewer tasks under
Leontief than under equal quotas. The comparison varied the allocation rule inside
one platform and did not vary the locus of authority.

That result does not tell us whether a declaration stays useful once the work
changes. The declaration there was a perfect description of the queue that ran. A
deployed platform installs a contract before the exact future work is known. The
purpose of the next experiment is to measure what happens when the declaration comes
from earlier work and the executed work then differs.

## Purpose of the next experiment

The experiment should answer whether the conditional task-completion advantage of
complementarity-aware allocation survives when declarations are calibrated from
earlier work and the execution workload subsequently differs.

Five questions follow from that purpose and the design keeps them apart.

1. Can complementarity-aware allocation help when the platform has exact information
   about the execution queue. This repeats the confirmed condition and serves as a
   positive control.
2. How much of that help remains when the declaration is estimated from a separate
   calibration queue that is drawn from the same latent workload distribution as the
   execution queue. This isolates finite-sample estimation error with no change in the
   underlying distribution.
3. How does additional distributional drift between calibration and execution change
   performance.
4. Whether rebuilding the declaration from the drifted execution queue recovers the
   oracle advantage.
5. At what observed calibration-to-execution distance stale Leontief stops
   outperforming DRF or equal quotas.

The fifth question describes a measurement, not a prediction. This draft does not
claim that such a stopping point exists.

## Who declares

The modeled declarant is an agent developer or platform integrator. That person
selects the utility family and derives its parameters from a calibration workload
that is available when the contract is installed. The person is not modeled as
choosing a declaration to gain an advantage over other agents. The experiment
measures calibration error and workload drift and does not model strategic reporting.

The exact-pending-queue condition remains an information upper bound. It stands for
the case where the declaration matches the executed work. It is not presented as the
ordinary deployed condition.

Five stages of the declarant workflow are modeled separately so that the source of any
performance loss can be attributed correctly. The first stage is utility-family
selection, meaning the choice of DRF-style demand, Leontief requirements, or another
family. The second stage is parameter estimation, meaning the numeric requirement
vector derived from the calibration queue. The third stage is declaration
installation, meaning the contract that fixes bounds and capacities before execution.
The fourth stage is workload execution against that installed contract. The fifth
stage is declaration refresh, meaning a rebuild of the declaration from the workload
that actually ran. This draft varies parameter estimation, execution drift, and
refresh. It holds family selection fixed within each policy, because the completed
work already characterizes family choice.

## Required experimental conditions

Four conditions are conceptually distinct and the design includes all four.

### 1. Exact oracle

Declarations, demand vectors, and admissible upper bounds come from the actual
execution queue. This reproduces the information condition under which the
heterogeneity result was confirmed and serves as a positive control and an upper
bound on achievable performance.

### 2. Calibration queue with no distributional drift

The declaration comes from an independent calibration queue that is sampled from the
same latent per-agent distribution as the execution queue. The execution queue is a
fresh independent sample from that same distribution. This condition carries the
sampling and estimation error that arises from describing an eight-task workload from
a different eight-task draw, even though the latent distribution has not moved. This
condition is not the same as the exact-pending-queue condition, because the
declaration describes a different finite queue.

### 3. Stale declaration under controlled drift

For each agent the generator draws a latent calibration distribution `p_i`, a fixed
independent drift-target distribution `q_i`, a calibration queue from `p_i`, and an
execution queue from a mixed distribution

`p_i(delta) = (1 - delta) * p_i + delta * q_i`.

The nominal drift levels are `delta in {0, 0.25, 0.5, 0.75, 1.0}`. The target `q_i` is
drawn once per agent and seed from a seed namespace that is separate from the
calibration and execution namespaces, and it stays fixed across all delta levels for
that agent and seed. Holding `q_i` fixed makes the five delta levels a single coherent
drift path within a seed rather than five unrelated perturbations.

The drift target is an independently drawn Dirichlet vector with the same concentration
family as the starting distribution. The target is not the uniform distribution.
Drifting toward uniform would remove cross-agent heterogeneity at the same time as it
introduced staleness, and the two effects would then be impossible to separate.
Drifting toward another peaked Dirichlet draw keeps the workload heterogeneous while
the calibration and execution queues move apart.

### 4. Refreshed declaration

After the drifted execution queue exists, the generator rebuilds the declaration from
that execution queue and allocates again under the same physical capacity. This is an
oracle refresh. Comparing the stale and refreshed conditions on the same execution
workload measures the cost of declaration staleness with the allocation rule and the
executed work held fixed.

## Workload and capacity controls

The primary scientific regime is Dirichlet concentration 0.1, because that is where
the fresh-seed heterogeneity result passed its frozen rule. The primary regime runs at
moderate contention and at high contention.

A secondary set of regimes may be included for context. Dirichlet 0.3 gives a
lower-dissimilarity boundary condition. The `iid_uniform` construction gives a
low-heterogeneity negative control. These secondary regimes stay outside the primary
rule unless a later version of this document gives a specific reason to promote one of
them.

For each seed the generator holds several things constant across policies and across
declaration conditions. The latent distributions, the calibration queues, the drift
targets, the execution queues, and the physical capacities are shared across the
policies within a scenario. The execution queue is shared across the stale and
refreshed comparisons. Physical capacity stays fixed across declaration conditions
for that scenario. Capacity and upper bounds are never sized using information that
only one policy holds. A stale policy never sees the execution queue. Each scenario
records realized contention after drift, realized cross-agent resource-demand
dissimilarity, the calibration-to-execution demand distance, and the latent
distribution distance kept separate from the finite-queue realized distance.

Capacity sizing needs care so that a change in declaration quality is not mistaken for
a change in the physical problem. In the completed experiment capacity was a function
of aggregate mandatory demand and the contention ratio. If capacity in the drift
experiment were sized from the execution demand, then a stale policy would face a
capacity that already reflected information it was not supposed to have, and refreshed
and stale runs would face different physical problems. To avoid this, capacity is
sized once per scenario from the calibration aggregate demand and stays fixed across
the exact-oracle, stale, and refreshed conditions for that scenario. Realized
contention after drift is then a recorded outcome rather than a controlled input, and
the analysis reports it. The design states plainly that capacity is a function of
calibration demand and contention ratio and of nothing that varies by policy.

## Policies

The primary comparison stays narrow. It uses equal quotas, standard unweighted DRF,
and joint Leontief. For DRF and Leontief the design runs both the stale and the
refreshed declaration conditions. Equal quotas do not depend on any declaration, so
the design does not duplicate an equal-quota arm across declaration conditions.

Joint Cobb-Douglas may appear as a secondary structural comparison, because its
continuous solution separates by resource and its behavior is already understood from
the completed work. It is not co-primary unless a later version gives a concrete
reason. CES and joint linear are left out. Their roles are already characterized in
the completed experiment and they do not address the staleness question.

## Outcome measures

Completion is measured only on the execution queue. The design records queue-order
completion and completed tasks per 48-task run. It also records the exact offline
best-subset completion, which remains an offline robustness measure of what a locally
rational agent could complete under the installed allocation and is not a live
task-selection policy.

The design records the following paired differences per cell and per delta. It records
stale Leontief minus stale DRF, refreshed Leontief minus refreshed DRF, stale Leontief
minus equal, refreshed Leontief minus equal, and stale minus refreshed within each of
DRF and Leontief. It records the fraction of agents worse than equal, the
zero-completion fraction with its denominator, the minimum and fifth-percentile agent
completion, capacity utilization, unused installed allocation, allocation distance from
equal, realized contention, cross-agent resource-demand dissimilarity, and the
calibration-to-execution resource-demand distance. Every completion figure is reported
both as a fraction and in task units per 48-task run.

## Pairing and seed requirements

The generator uses separate deterministic seed namespaces for the latent calibration
distributions, the calibration queues, the drift-target distributions, the execution
queues, and the bootstrap resampling. The confirmatory seeds are disjoint from the
canonical platform-evaluation seeds, the heterogeneity pilot development seeds, and the
heterogeneity confirmatory seeds. The same scenario is paired across policies and
across declaration conditions. The same execution workload is paired across the stale
and refreshed declarations.

A sample size of about 200 seeds per cell is a reasonable starting recommendation, and
the design shows why rather than copying the previous count. The confirmed Dirichlet
0.1 run gives a per-seed paired difference standard deviation of about 2.18 tasks per
run at moderate contention and about 2.01 at high contention. The half-width of a 95
percent interval is about `1.96 * s / sqrt(n)`, where `s` is that per-seed standard
deviation. At `n` near 200 the half-width is about 0.30 tasks per run at moderate
contention and about 0.28 at high contention. That precision resolves a one-task-per-run
effect well clear of zero when the effect is near the confirmed size. The stale effect
is expected to be smaller than the oracle effect, because staleness removes
information. If the anticipated stale effect sits close to the one-task-per-run
reference, then the interval needs to stay clear of both zero and the reference, which
calls for a smaller half-width and therefore a larger `n`. The design recommends
computing the required `n` from the confirmed `s` and the anticipated stale effect
before freezing, and it recommends raising `n` above 200 if the anticipated stale
effect approaches the reference.

## Analysis plan

Reporting is per cell. Pooling across cells is not used for the decision. The draft
proposes a primary rule for later human approval and does not silently freeze it. The
proposed primary rule uses drift level `delta = 0.25`. Both contention levels must pass
on their own. In each cell the stale Leontief mean completion must exceed the stale DRF
mean, the paired 95 percent interval for stale Leontief minus stale DRF must lie above
zero, the point estimate must be at least one additional task per 48-task run, the
zero-completion fraction must not increase relative to DRF, and the capacity, bound,
fallback, and infeasibility counts must be zero.

The choice of `delta = 0.25` has a concrete reading. Each agent runs eight tasks. A
drift level of 0.25 mixes one quarter of the execution distribution toward the drift
target, so in expectation about two of the eight executed tasks come from the drifted
target rather than from the calibration distribution. That corresponds to changing
about a quarter of the workload. The level is interpretable in task terms and is fixed
in advance rather than chosen after seeing outcomes.

The full drift curve across all five delta levels is a pre-specified secondary
analysis. Every delta is reported even if the primary cell fails. The design does not
require the curve to be monotone in delta. The completed heterogeneity response across
concentration was strictly increasing at moderate contention but reversed once at high
contention, where the Leontief-minus-DRF effect fell from 0.370 tasks at Dirichlet 3.0
to 0.330 at Dirichlet 1.0 before rising again. A strict-monotonicity requirement would
be brittle for the same reason here, so the design reports the curve and any reversals
directly and reads the result as generally changing with drift rather than as a
monotone path.

The interval procedure is a paired bootstrap over execution-workload seeds with a fixed
recorded bootstrap seed, matching the resampling unit used and reconstructed in the
completed experiment. Multiplicity is handled by naming the primary comparison and
primary delta in advance and by treating the remaining deltas, the refreshed arms, the
equal-quota comparisons, and the secondary regimes as secondary analyses that describe
the response rather than gate the decision. The decision logic is written out in full
before any confirmatory data are generated, so that the reported outcome cannot be
reshaped after the fact.

## Development and public anchoring plan

The future sequence separates development from confirmation so that outcomes do not
shape the design.

1. Implement the generator and tests on development-only seeds.
2. Use the development runs only to find technical defects, information leakage,
   infeasibility, or broken manipulation checks.
3. Do not use development completion outcomes to choose drift levels, policies,
   thresholds, or primary cells.
4. Freeze the final protocol and configuration in a dedicated commit.
5. Push that protocol commit publicly before any confirmatory outcome is generated.
6. Record the public commit hash and the GitHub timestamp for that push.
7. Only then run fresh confirmatory seeds.
8. Store the raw data, scenario hashes, seed manifests, environment information, test
   logs, and reconstruction scripts.
9. Verify the results independently before any promotion to platform-evaluation.

This public anchoring matters because it makes the timing checkable by a third party.
The present document is a draft and is not a preregistration.

## Required tests for future implementation

These tests are listed for the future implementation and are not implemented here.

- The calibration and execution random streams are independent.
- Results reproduce exactly from seeds.
- The calibration, execution, drift-target, and bootstrap seed namespaces are disjoint
  from each other and from the canonical, pilot, and confirmatory namespaces.
- No execution-queue information reaches a stale declaration.
- No execution-queue information reaches a stale upper bound.
- Physical capacity is identical across the stale and refreshed conditions.
- The execution queue is identical across policies within a scenario.
- The execution queue is identical across the stale and refreshed comparisons.
- At `delta = 0` the execution queue uses the same latent distribution as calibration
  and is an independent draw.
- Oracle declarations match the execution queue exactly.
- Refreshed declarations match the drifted execution queue exactly.
- The drift target stays fixed across delta levels for a given agent and seed.
- Realized drift generally responds to nominal delta.
- The scenario hash includes the calibration queue, the execution queue, the drift
  target, delta, the declaration condition, and the capacity.
- Allocation bounds and resource capacities stay valid.
- The local subset-selection measure stays policy-independent.
- No solver fallback is used without being recorded.

## Required caveats

A successful drift experiment would not establish any of the following. It would not
show strategic truthfulness. It would not show that a developer chooses the correct
utility family. It would not show robustness to adversarial misreporting. It would not
show that centralized authority beats decentralized coordination, because the design
varies the allocation rule inside one platform. It would not show that the tested drift
is common in deployed agent populations. It would not show universal Leontief
superiority. It would not show individual rationality. It would not show a Pareto
improvement over equal quotas. It would not show collusion resistance.
