# Confirmatory protocol v1 (preregistered)

This protocol and `config/confirmatory_v1.json` are frozen **before** any
confirmatory outcome is generated. The preregistration commit hash is recorded in
`CHANGELOG.md` and in the confirmatory manifest. If an implementation defect later
requires a change, a new commit will describe the defect and the affected run will
be treated as void rather than silently regenerated. The sample size is fixed at
200 fresh seeds per cell and will not be changed after examining outcomes.

## Purpose

The exploratory 30-seed pilot suggested that raising cross-agent workload
concentration raises realized resource-demand dissimilarity, and that joint
Leontief then completes more tasks than equal quotas and DRF while CES deteriorates
and joint linear stays far worse. Those are development-seed findings. This
experiment runs one frozen, fresh-seed confirmation of the Leontief result.

## Frozen design (`config/confirmatory_v1.json`)

* 6 agents, 8 tasks/agent, 4 resources, the 4 existing archetypes and service
  footprints, 2 contention levels (1.3, 1.9), equal operator priorities.
* Unit floors as the single frozen contract definition. The floor sweep is **not**
  rerun as a confirmatory factor; the exploratory floor sweep already answered the
  floor-robustness question.
* Exact-pending-queue declarations (`declaration_source = exact_pending_queue`).
* The canonical Java allocation, contract installation, and execution path;
  fallback disabled; capacity-preserving integer rounding.
* All seven policies: equal, DRF, decomposed Cobb-Douglas, joint linear, joint
  Cobb-Douglas, joint CES, joint Leontief.
* All seven workload regimes: homogeneous, iid_uniform, and Dirichlet
  {3.0, 1.0, 0.3, 0.1, 0.03}.
* 200 fresh seeds per cell from the new namespace `heterogeneity_confirmatory_v1`,
  asserted disjoint from canonical and exploratory seeds and task-workload hashes.
* The same task workload is paired across contention levels (identical
  `task_workload_hash`); the same scenario is paired across policies (identical
  `scenario_hash`).

## Two completion measures

* `queue_order_completion`: what the canonical runtime completes executing an
  agent's tasks in generated order under the installed contract (the exploratory
  metric, preserved).
* `locally_optimized_completion`: a policy-independent measure of what a locally
  rational agent could complete under the **same** installed bundle by choosing the
  best feasible subset of its own tasks (all 256 subsets enumerated; selection by
  max count, then max summed base quality, then min total mandatory consumption,
  then lexicographic task indices). Computed identically for every policy. It is a
  measurement, not a new allocation policy.

## Hypotheses

**Primary policy: joint Leontief.** Cobb-Douglas is a structural comparison (it is
separable: joint and decomposed agree, so a positive Cobb-Douglas effect is not
evidence that joint computation is required). CES and joint linear are negative /
misspecification controls. The nonlinear policies are not treated as one class.

**Co-primary cells: Dirichlet 0.1 at moderate contention and Dirichlet 0.1 at high
contention.** The confirmatory Leontief result succeeds only if, in **both** cells
separately, using queue-order completion:

1. joint Leontief mean completion is higher than DRF;
2. the paired 95% bootstrap interval for Leontief minus DRF lies strictly above
   zero;
3. the point estimate is at least one additional completed task per 48-task run;
4. the observed zero-completion fraction does not increase relative to DRF;
5. capacity, bound, fallback, and infeasibility counts are all zero.

A pooled interval cannot substitute for failure in one co-primary cell.

**Locally-optimized completion is a required robustness result**, not part of the
formal success rule (it was added after the exploratory pilot). It controls
interpretation: if the advantage remains positive and practically material under
local optimization the evidence concerns allocation quality; if it disappears the
primary queue-order result must be described as task-order-dependent.

**Secondary cells** (reported regardless of result): Dirichlet 0.3 (where the effect
begins), Dirichlet 0.03 (stronger, more synthetic boundary), Dirichlet 3.0 and 1.0
(response curve), iid_uniform and homogeneous (low-heterogeneity controls). No sharp
dissimilarity threshold is inferred; realized resource-demand dissimilarity
distributions are reported per cell.

**Secondary policy comparisons** (reported per cell): Leontief minus equal, Leontief
minus DRF, joint Cobb-Douglas minus DRF, decomposed Cobb-Douglas minus DRF, joint
minus decomposed Cobb-Douglas, CES minus DRF, joint linear minus DRF.

## Statistics

Paired bootstrap over fresh workload seeds, 10,000 resamples, fixed recorded
bootstrap seed (`config.bootstrap_seed = 20260901`). Each cell reported separately.
For every effect: completion-fraction difference; difference in completed tasks per
48-task run; exact 95% interval endpoints in both units; number of paired seeds;
standard deviation of the paired task-count difference; fraction of seeds in which
the policy wins, ties, or loses. Computed separately for queue-order and
locally-optimized completion. A pooled figure may appear only after all constituent
cells are visible and is labelled secondary. Estimates and intervals are stated
directly; "statistically significant" is not used as a substitute. No
resource-dissimilarity cutoff is chosen after seeing the data.

## Distributional reporting

For every Leontief and DRF cell: mean, minimum, and p5 agent completion; fraction
completing zero tasks; fraction worse than equal; mean and worst paired change from
equal; capacity utilization; unused installed allocation; allocation distance from
equal. If aggregate completion improves while any distributional outcome worsens,
the tradeoff is shown directly. No claim of individual rationality, Pareto
improvement, starvation protection, or worst-agent safety is made.

## Decision categories

`CONFIRMATORY_DECISION.md` will state exactly one of:

1. Confirmed under the frozen primary rule.
2. Positive but failed the frozen practical threshold.
3. Positive only under queue-order execution and not robust to local task selection.
4. Failed to replicate in one co-primary cell.
5. Failed to replicate in both co-primary cells.
6. Technically invalid because of a specified defect.

No new category will be invented after seeing the result.

## Bounded interpretation on success

A successful result would support only: *in the tested synthetic workloads, when
agents' mandatory resource profiles are sufficiently dissimilar, a
complementarity-aware Leontief allocation completes more tasks than equal quotas and
DRF under the platform's exact-pending-queue declaration condition; the advantage
appears separately at both tested contention levels and survives a policy-independent
local task-selection check.* Even if confirmed, the experiment does not show that
centralized authority beats decentralized coordination (equal and DRF are also
platform-computed), that developers report truthfully, that the platform knows the
correct utility family, that the tested concentration resembles deployed agent
populations, that Leontief is universally superior, that Cobb-Douglas requires joint
computation, that the result holds under stale declarations, that agents are
individually protected, or that the platform is strategyproof or collusion
resistant. The next experiment after confirmation would be declaration calibration
and drift (designed in `DECLARATION_STALENESS_DESIGN.md`); it is not run here.
