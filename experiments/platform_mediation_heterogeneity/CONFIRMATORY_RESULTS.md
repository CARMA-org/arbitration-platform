# Confirmatory results (frozen protocol v1)

Run under the preregistered protocol (`CONFIRMATORY_PROTOCOL.md`,
`config/confirmatory_v1.json`, preregistration commit `0caa1807`). Seven workload
regimes x two contention levels x 200 fresh seeds x seven policies = **19,600 runs**,
117,600 agent records, 2,800 scenarios, all through the canonical Java runtime under
unit floors. **0 infeasible, 0 capacity violations, 0 bound violations, 0 solver
fallbacks.** Confirmatory seeds and task-workload hashes are disjoint from both the
canonical evaluation and the exploratory pilot (all four overlap counts 0).

Numbers derive from `results/confirmatory_v1/confirmatory_headline.json` and the
tables under `results/confirmatory_v1/tables/`. Completion is the mean fraction of an
agent's 8 mandatory task bundles completed; one run has 48 tasks, so tasks/run
`= 48 * completion`. Paired differences are by task-workload seed within a cell;
intervals are 95% paired bootstrap with 10,000 resamples at the fixed configured
seed 20260901. `queue_order` = the runtime's actual execution order;
`local_opt` = the policy-independent best feasible task subset under the same
installed bundle.

A post-preregistration import-order defect in `make_confirmatory_analysis.py` was
fixed in a separate, described commit; it did not touch the frozen configuration,
the driver, the confirmatory data, the bootstrap seed, the comparisons, or the
success rule (see `CONFIRMATORY_DECISION.md`).

## 1. Frozen success rule (co-primary cells, queue-order completion)

Primary policy joint Leontief; both Dirichlet 0.1 cells must satisfy all five
conditions separately.

| co-primary cell | Leontief compl | DRF compl | Leontief - DRF (tasks/run) | 95% CI (fraction) | 95% CI (tasks/run) | c1 mean>DRF | c2 CI>0 | c3 >=1 task | c4 no zero-compl increase | c5 zero events |
|---|---|---|---|---|---|---|---|---|---|---|
| dirichlet_0.1__moderate | 0.7750 | 0.7209 | **+2.595** | [0.0478, 0.0602] | [2.295, 2.890] | yes (0.775>0.721) | yes | yes | yes (0.000 vs 0.001) | yes |
| dirichlet_0.1__high | 0.5452 | 0.5083 | **+1.770** | [0.0314, 0.0426] | [1.505, 2.045] | yes (0.545>0.508) | yes | yes | yes (0.000 vs 0.004) | yes |

**Both co-primary cells pass all five conditions. The frozen primary rule is
satisfied.**

## 2. Queue-order vs locally-optimized, side by side (Leontief minus DRF, tasks/run)

The locally-optimized result is the required robustness check. The advantage is
preserved (and slightly larger) under optimal local task selection, so it is not an
artifact of the generated task order.

| cell | queue-order | 95% CI (frac) | locally-optimized | 95% CI (frac) |
|---|---|---|---|---|
| dirichlet_0.1__moderate | +2.595 | [0.0478, 0.0602] | +2.555 | [0.0470, 0.0594] |
| dirichlet_0.1__high | +1.770 | [0.0314, 0.0426] | +1.895 | [0.0339, 0.0451] |

Full queue-order and locally-optimized comparisons for every cell and every policy
pair are in `results/confirmatory_v1/tables/paired_comparisons.csv`.

## 3. Secondary response curve (Leontief minus DRF, queue-order tasks/run)

Reported regardless of result. "CI incl 0" marks a 95% interval that includes zero.

| regime | resource-demand TV | task entropy | moderate | high |
|---|---|---|---|---|
| homogeneous | 0.0000 | 0.817 | +0.025 (mostly ties) | +0.040 (mostly ties) |
| iid_uniform (current design) | 0.0362 | 0.842 | +0.045 (CI incl 0) | +0.085 (CI incl 0) |
| dirichlet_3.0 | 0.0480 | 0.764 | +0.195 (CI incl 0) | +0.370 |
| dirichlet_1.0 | 0.0650 | 0.643 | +0.790 | +0.330 |
| dirichlet_0.3 | 0.0924 | 0.424 | +1.655 | +1.190 |
| dirichlet_0.1 | 0.1167 | 0.222 | +2.595 | +1.770 |
| dirichlet_0.03 | 0.1248 | 0.072 | +3.155 | +1.795 |

The Leontief-minus-DRF difference rises roughly monotonically with realized
resource-demand dissimilarity. At the current design's dissimilarity (iid_uniform,
0.036) the interval includes zero at both contention levels, reproducing the
canonical and exploratory null. The interval clears zero per cell at both contention
from Dirichlet 1.0 (resource-demand TV 0.065); the one-task-per-run reference is met
at both contention from Dirichlet 0.3 (resource-demand TV 0.092). Realized
resource-demand dissimilarity saturates near 0.12-0.13 even as task concentration
keeps rising, because three of the four archetypes share similar resource profiles.
No dissimilarity threshold is inferred from the data; the tested generator settings
and their realized dissimilarity are reported as-is.

## 4. Distributional guardrails (co-primary cells, queue-order)

| cell | policy | mean | min | p5 | frac zero | frac worse than equal | mean change vs equal | worst change vs equal | cap util | unused installed |
|---|---|---|---|---|---|---|---|---|---|---|
| dir 0.1 / mod | equal | 0.7039 | 0.250 | 0.375 | 0.000 | 0.000 | 0.0000 | 0.000 | 0.879 | 199.6 |
| dir 0.1 / mod | drf | 0.7209 | 0.000 | 0.375 | 0.001 | 0.218 | +0.0171 | -0.625 | 0.903 | 85.5 |
| dir 0.1 / mod | joint_leontief | 0.7750 | 0.250 | 0.375 | 0.000 | 0.049 | +0.0711 | -0.375 | 0.950 | 80.0 |
| dir 0.1 / high | equal | 0.4733 | 0.125 | 0.250 | 0.000 | 0.000 | 0.0000 | 0.000 | 0.824 | 199.8 |
| dir 0.1 / high | drf | 0.5083 | 0.000 | 0.250 | 0.004 | 0.188 | +0.0350 | -0.500 | 0.864 | 102.5 |
| dir 0.1 / high | joint_leontief | 0.5452 | 0.125 | 0.250 | 0.000 | 0.070 | +0.0719 | -0.250 | 0.912 | 98.8 |

Direct reading of the tradeoff. Relative to DRF, Leontief has a higher mean, a
**higher observed minimum agent completion** (0.250 vs 0.000 at moderate; 0.125 vs
0.000 at high), no increase in zero-completion, a lower fraction of agents worse than
equal (0.049 vs 0.218; 0.070 vs 0.188), higher capacity utilization, and less unused
installed allocation. Relative to **equal**, Leontief is not a uniform improvement: a
minority of agents are worse than equal (4.9% moderate, 7.0% high), and the worst
single agent completes up to 0.375 (moderate) / 0.250 (high) fraction fewer tasks
than under equal. These are finite-sample observations at 200 seeds; the observed
minimum did not deteriorate relative to DRF, but no starvation, individual-rationality,
or Pareto claim is made.

## 5. Control policies

* **Cobb-Douglas is separable.** Joint and decomposed Cobb-Douglas agree to a maximum
  cell-mean difference of 0.010 tasks/run across all cells; a positive Cobb-Douglas
  effect is therefore not evidence that joint computation is required. Cobb-Douglas
  minus DRF is positive under concentration (e.g. +1.930 tasks/run at dir 0.1
  moderate) but tracks the decomposed comparator.
* **CES is not a general nonlinear success.** CES minus DRF turns negative under
  concentration (dir 0.1: -2.34 moderate, -2.55 high; dir 0.03: -2.39 moderate, -4.06
  high) and raises the fraction of agents worse than equal (0.263 / 0.320 in the co-
  primary cells) with a negative mean change vs equal.
* **Joint linear is a large misspecification loss** everywhere heterogeneous (about
  -22 to -31 tasks/run vs DRF), consistent with the exploratory pilot.

## 6. Statistical detail

For every effect the tables record the fraction difference, the tasks/run difference,
both 95% interval endpoints, the number of paired seeds (200), the standard deviation
of the paired task-count difference, and the fraction of seeds in which the policy
wins, ties, or loses, separately for queue-order and locally-optimized completion.
For Leontief minus DRF (queue-order) the per-seed win fraction rises with
concentration (dir 0.3: 0.73 win / 0.16 tie / 0.10 lose at moderate; dir 0.1: 0.81 /
0.10 / 0.09; dir 0.03: 0.81 / 0.14 / 0.06 at moderate), while at iid_uniform it is
near a three-way split (0.39 / 0.28 / 0.33). A pooled figure is not used as the
headline; the per-cell results above are primary.
