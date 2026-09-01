# Next-experiment decision

## Selected conclusion

**Existing nonlinear allocation shows a credible conditional advantage and warrants
a fresh-seed confirmatory run** -- scoped to **Leontief** (primary) and
**Cobb-Douglas** (a separable declaration/allocation comparator), **not CES**, and
conditional on realized resource-demand dissimilarity roughly at or above 0.086
(about 2.4x the current design). This is the first of the four candidate
conclusions.

## Exact evidence

From the workload-concentration sweep (unit floor, 30 seeds/cell, paired by seed,
95% bootstrap intervals; all 2,940 runs feasible, no violations, no fallback):

1. **Beats both baselines, per cell, at both contention, from moderate
   concentration.** Difference in completed tasks per 48-task run vs DRF (the
   stronger baseline), with intervals clearing zero:
   * Leontief vs DRF: dirichlet 0.3 +1.43 (mod) / +0.90 (high); dirichlet 0.1
     +2.30 / +1.63; dirichlet 0.03 +3.73 / +2.20. Intervals clear zero per cell from
     dirichlet 0.3.
   * Cobb-Douglas vs DRF: dirichlet 0.3 +1.33 / +1.20; dirichlet 0.1 +1.90 / +1.77;
     dirichlet 0.03 +3.03 / +1.63. Intervals clear zero per cell from dirichlet 0.3.
   * vs equal, both are strongly positive throughout (e.g. Leontief +2.97 to +3.90
     tasks/run in the dirichlet 0.3-0.1 cells).
2. **Grows with realized resource-demand dissimilarity.** The vs-DRF advantage rises
   roughly monotonically as resource-demand TV increases from 0.086 (dirichlet 0.3)
   to 0.137 (dirichlet 0.03). At the current design's dissimilarity (iid_uniform,
   resTV 0.035) the vs-DRF difference is small and its interval includes zero at
   moderate contention -- consistent with the canonical evaluation.
3. **No hidden worst-agent harm.** In the concentrated cells, Leontief and
   Cobb-Douglas leave fewer agents worse than equal than DRF does (e.g. dirichlet 0.3
   moderate: Leontief 5.6%, Cobb-Douglas 3.3%, DRF 18.9%), with zero increase in
   zero-completion and equal-or-higher minimum agent completion.
4. **Not a floor artifact.** All results use the current unit floor; the floor
   regime was not chosen after inspecting completion.
5. **CES is excluded.** From dirichlet 1.0 onward CES is frequently below DRF and its
   interval is strictly negative at dirichlet 0.1 and 0.03, with a rising fraction of
   harmed and zero-completion agents. CES leans toward substitutes, which is the
   wrong bias under specialization.
6. **Cobb-Douglas is separable.** Joint and decomposed per-resource Cobb-Douglas
   agree to within +/-0.0014 completion in every cell, so the Cobb-Douglas gain does
   not require joint computation. Leontief keeps genuine cross-resource coupling and
   is the family whose advantage implicates joint, coupling-aware allocation.

## Why not the other conclusions

* **Not "separates only under an extreme synthetic regime."** The credible advantage
  (beats both baselines, stable at both contention, per-cell intervals clearing zero,
  growing with dissimilarity, worst-agent-safe) appears from dirichlet 0.3, where
  agents average two-to-three of four archetypes (mean task entropy 0.45) -- more
  concentrated than the current design but not a single-archetype pathology. The most
  extreme setting (dirichlet 0.03) sharpens the effect; it is not the only setting
  that shows it. The honest qualification is that the robust one-task-per-run
  reference at both contention needs dirichlet 0.1-0.03 (resTV 0.10-0.14).
* **Not "too close to equal/DRF; go straight to task-aware contracts."** The smooth
  aggregate-utility policies do separate from both baselines once heterogeneity is
  present, with a stable sign and growing magnitude, so the aggregate-utility path is
  not exhausted. (Task-aware admission remains a worthwhile later comparison, but the
  pilot does not force that pivot now.)
* **Not "technically inconclusive."** The sweep is complete and clean: full row
  counts, no infeasible runs, no capacity/bound violations, no solver fallback, and
  headline reconstructible from raw (integration-tested). The signal is not a
  measurement defect.

## Specification of the confirmatory run

The pilot used 30 exploratory development seeds; the confirmatory run must use
**fresh, disjoint seeds** and pre-registered comparisons so it is not "measure the
same thing that was used to pick the regime."

* **Seeds.** New label, e.g. `derive_seed("heterogeneity_confirm", "test", i)` for a
  larger `i` range (200 suggested for tighter intervals), asserted disjoint from both
  the pilot development seeds and the canonical test seeds.
* **Regimes.** homogeneous (null), iid_uniform (negative control at current
  dissimilarity), and dirichlet {0.3, 0.1, 0.03} (the candidate positive regime).
  Both contention levels. Factorial control unchanged (one workload per (regime,
  seed), reused across contention).
* **Policies.** All seven, to stay comparable; but pre-register the **primary**
  comparisons as Leontief vs {equal, DRF} and Cobb-Douglas vs {equal, DRF}, and keep
  the decomposed Cobb-Douglas comparator to re-confirm separability. CES and
  joint_linear are reported as pre-declared negative/misspecification references.
* **Pre-registered decision rule.** Positive iff the primary policy's paired interval
  vs both equal and DRF excludes zero per cell at both contention, the point estimate
  is at least one completed task per 48-task run at both contention, the advantage
  does not shrink as dissimilarity rises, and the worst-agent guardrails
  (zero-completion, fraction worse than equal, minimum completion) are no worse than
  DRF. Fix and document the bootstrap seed in advance.
* **Held-fixed.** Unit floor (do not sweep floors in the confirmatory run; the floor
  study is separate), oracle declaration, equal priorities, the canonical runtime and
  rounding. Report realized resource-demand dissimilarity per cell as the covariate.
* **Reporting.** Per-cell first; pooled only as a secondary summary. Task-unit
  magnitudes throughout. Keep the raw points for the advantage-vs-dissimilarity
  relationship; do not infer a threshold post hoc and present it as preregistered.

## Deeper follow-on (separate from the confirmatory run)

Even a positive confirmatory run would be an oracle-declaration result. The more
informative next test is the calibration-versus-execution drift design in
`DECLARATION_STALENESS_DESIGN.md`, which measures completion on a held-out execution
queue the declaration never saw and builds upper bounds without leaking it. That
separates "a bundle-aware allocation helps when the platform knows the pending work"
from "a declared bundle-aware allocation still helps when the work is only
predicted."
