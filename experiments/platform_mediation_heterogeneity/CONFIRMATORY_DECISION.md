# Confirmatory decision (frozen protocol v1)

## Selected category

**1. Confirmed under the frozen primary rule.**

The primary policy, joint Leontief, satisfies all five preregistered conditions in
**both** co-primary cells (Dirichlet 0.1 at moderate and high contention),
separately, using queue-order completion; and the advantage survives the required
policy-independent locally-optimized task-selection check.

## Exact evidence

Preregistration commit `0caa1807`. Confirmatory run: 19,600 runs, 0 infeasible, 0
capacity/bound violations, 0 solver fallbacks; confirmatory seeds and task-workload
hashes disjoint from canonical and exploratory data (all overlap counts 0). Paired
bootstrap 10,000 resamples, fixed seed 20260901, 200 paired seeds per cell.

Joint Leontief minus DRF, queue-order completion:

* **dirichlet_0.1__moderate**: +2.595 tasks per 48-task run; 95% interval
  [0.0478, 0.0602] fraction = [2.295, 2.890] tasks/run.
  - c1 mean higher than DRF: 0.7750 > 0.7209 — yes.
  - c2 interval strictly above zero: yes.
  - c3 at least one task/run: 2.595 >= 1 — yes.
  - c4 no increase in zero-completion vs DRF: 0.000 vs 0.001 — yes.
  - c5 capacity/bound/fallback/infeasibility all zero: yes.
* **dirichlet_0.1__high**: +1.770 tasks/run; 95% interval [0.0314, 0.0426] fraction
  = [1.505, 2.045] tasks/run.
  - c1: 0.5452 > 0.5083 — yes. c2: yes. c3: 1.770 >= 1 — yes.
  - c4: 0.000 vs 0.004 — yes. c5: yes.

Both cells pass all five conditions. A pooled interval was not used.

## Robustness to local task selection

Locally-optimized completion (best feasible task subset under the same installed
bundle, computed identically for every policy) preserves the advantage:

* dirichlet_0.1__moderate: Leontief minus DRF +2.555 tasks/run, interval
  [0.0470, 0.0594] fraction.
* dirichlet_0.1__high: +1.895 tasks/run, interval [0.0339, 0.0451] fraction.

The queue-order result is therefore **not** task-order-dependent; the evidence
concerns allocation quality, not execution order.

## Why not the other categories

* **Not 2 (positive but failed the practical threshold).** The point estimate is
  above one task/run at both contention levels (2.595 and 1.770).
* **Not 3 (queue-order only).** The advantage is preserved under locally-optimized
  selection (+2.555 and +1.895), with intervals still strictly above zero.
* **Not 4 or 5 (failed to replicate in one/both co-primary cells).** Both co-primary
  cells satisfy the full rule.
* **Not 6 (technically invalid).** The run is complete and clean (full row counts, no
  infeasible/violation/fallback events, disjoint seeds and hashes). A
  post-preregistration import-order defect in the analysis script
  (`make_confirmatory_analysis.py`) was fixed in a separate, described commit; it did
  not touch the frozen configuration, the driver, the confirmatory data, the
  bootstrap seed, the comparisons, or the success rule, so no data run is void.

## Scope and controls

* The effect is **conditional on realized resource-demand dissimilarity**. At the
  current design's dissimilarity (iid_uniform, 0.036) Leontief minus DRF does not
  clear zero at either contention level, reproducing the canonical and exploratory
  null. The Leontief advantage rises roughly monotonically with concentration and
  reaches one task/run at both contention from Dirichlet 0.3.
* **Cobb-Douglas is separable** (joint and decomposed agree to 0.010 tasks/run at
  the cell mean); a positive Cobb-Douglas effect is not evidence that joint
  computation is required. Leontief keeps genuine cross-resource coupling and is the
  family whose advantage implicates joint, coupling-aware allocation.
* **CES is not confirmed**; it turns negative versus DRF under concentration.
* **Joint linear** remains a large misspecification loss.

## Distributional tradeoff (stated directly, not hidden)

Relative to DRF, Leontief in the co-primary cells has a higher mean and a higher
observed minimum agent completion (0.250 vs 0.000 moderate; 0.125 vs 0.000 high),
no increase in zero-completion, and a lower fraction of agents worse than equal
(0.049 vs 0.218; 0.070 vs 0.188). Relative to **equal**, Leontief is not a uniform
improvement: 4.9% (moderate) / 7.0% (high) of agents are worse than equal, and the
worst single agent completes up to 0.375 / 0.250 fraction fewer tasks than under
equal. These are finite-sample observations.

## Bounded conclusion

In the tested synthetic workloads, when agents' mandatory resource profiles are
sufficiently dissimilar, a complementarity-aware Leontief allocation completes more
tasks than equal quotas and DRF under the platform's exact-pending-queue declaration
condition. The advantage appears separately at both tested contention levels and
survives a policy-independent local task-selection check.

This does **not** show that centralized authority beats decentralized coordination
(equal and DRF are also platform-computed), that developers report truthfully, that
the platform knows the correct utility family, that the tested concentration
resembles deployed agent populations, that Leontief is universally superior, that
Cobb-Douglas requires joint computation, that the result holds under stale
declarations, that agents are individually protected, or that the platform is
strategyproof or collusion resistant.

## Next experiment

The next experiment after this confirmation would be declaration calibration and
drift (designed in `DECLARATION_STALENESS_DESIGN.md`): declarations and upper bounds
built from a calibration queue, completion measured on an independently drawn,
possibly drifted execution queue, with the same information supplied to DRF and the
nonlinear policies. It is not run here. The contract-breach-remedies project remains
out of scope.
