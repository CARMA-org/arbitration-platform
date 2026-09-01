# Pilot results: workload heterogeneity and floor sensitivity

This pilot tests whether cross-agent workload concentration creates enough
resource heterogeneity for the existing joint allocation policies to produce
task-completion gains over equal quotas and DRF, and separately how sensitive the
large joint-linear loss is to the resource floor. It does not replace the canonical
evaluation, does not alter the current results bundle, and does not tune the
benchmark toward any allocator. Every policy ran through the canonical Java runtime.

All numbers derive from `results/pilot_headline.json` and the tables under
`tables/`; the machine-generated digest is `results/PILOT_MEMO.md`. Completion is
the mean fraction of an agent's 8 tasks completed; one run has 48 tasks, so a
completion difference of `d` equals `48 * d` completed tasks per run. Policy
comparisons are paired by task-workload seed within a cell (30 development seeds),
with a 95% bootstrap interval at the documented seed. Runs: 2,940 workload +
3,240 floor, all feasible, zero capacity/bound violations, zero solver fallbacks.

## 1. The manipulation produces a resource-heterogeneity gradient

Holding the four archetypes and their footprints fixed, cross-agent workload
concentration was varied from a shared queue (homogeneous) through the current
mixed construction (`iid_uniform`) to increasingly concentrated symmetric-Dirichlet
mixtures. Realized dissimilarity (mean over 30 seeds; contention-independent):

| regime | resource-demand TV | task-mixture TV | task entropy | frac agents all 4 archetypes |
|---|---|---|---|---|
| homogeneous | 0.0000 | 0.000 | 0.813 | 0.57 |
| iid_uniform (= current design) | 0.0354 | 0.313 | 0.861 | 0.69 |
| dirichlet 3.0 | 0.0433 | 0.422 | 0.757 | 0.40 |
| dirichlet 1.0 | 0.0601 | 0.491 | 0.652 | 0.21 |
| dirichlet 0.3 | 0.0863 | 0.619 | 0.453 | 0.06 |
| dirichlet 0.1 | 0.1020 | 0.699 | 0.225 | 0.00 |
| dirichlet 0.03 | 0.1366 | 0.781 | 0.088 | 0.00 |

`iid_uniform` reproduces the current design's resource-demand dissimilarity
(0.0354, matching the canonical 0.0365). The Dirichlet regimes raise realized
resource-demand TV up to roughly 4x that value. Even at the most concentrated
setting the resource-demand TV (0.137) is far below the task-mixture TV (0.781):
agents that specialize by task still overlap heavily in aggregate resource shape,
because three of the four archetypes have similar normalized resource profiles.

## 2. Workload-concentration results (unit floor, all seven policies)

Paired difference in **completed tasks per 48-task run** against DRF and against
equal, with the 95% bootstrap interval on the completion fraction. DRF is the
stronger baseline (it beats equal in most cells).

| regime | cont | resTV | CD vs DRF | CD vs equal | CES vs DRF | Leontief vs DRF | Leontief vs equal | linear vs equal |
|---|---|---|---|---|---|---|---|---|
| homogeneous | mod | 0.000 | +0.03 | +0.00 | +0.03 | +0.03 | +0.00 | +0.00 |
| homogeneous | high | 0.000 | +0.13 | +0.00 | +0.13 | +0.13 | +0.00 | +0.00 |
| iid_uniform | mod | 0.035 | +0.27 [incl 0] | +1.77 | +0.27 [incl 0] | -0.03 [incl 0] | +1.47 | -29.2 |
| iid_uniform | high | 0.035 | +0.53 | +1.07 | +0.83 | +0.53 | +1.07 | -22.7 |
| dirichlet 1.0 | mod | 0.060 | -0.13 [incl 0] | +2.63 | -0.90 (neg) | +0.30 [incl 0] | +3.07 | -29.2 |
| dirichlet 1.0 | high | 0.060 | +0.93 | +2.10 | +1.03 | +0.83 | +2.00 | -21.2 |
| dirichlet 0.3 | mod | 0.086 | +1.33 | +3.80 | -0.77 [incl 0] | +1.43 | +3.90 | -28.8 |
| dirichlet 0.3 | high | 0.086 | +1.20 | +3.27 | +0.33 [incl 0] | +0.90 | +2.97 | -21.0 |
| dirichlet 0.1 | mod | 0.102 | +1.90 | +2.57 | -1.97 (neg) | +2.30 | +2.97 | -27.4 |
| dirichlet 0.1 | high | 0.102 | +1.77 | +3.33 | -1.87 (neg) | +1.63 | +3.20 | -19.7 |
| dirichlet 0.03 | mod | 0.137 | +3.03 | +2.77 | -3.60 (neg) | +3.73 | +3.47 | -27.6 |
| dirichlet 0.03 | high | 0.137 | +1.63 | +2.73 | -4.03 (neg) | +2.20 | +3.30 | -20.2 |

"[incl 0]" marks a 95% interval that includes zero; "(neg)" marks an interval
strictly below zero. Full intervals and all policies are in
`tables/cell_policy_effects.csv` and `results/PILOT_MEMO.md`.

Reading the table:

* **Homogeneous null.** With one shared queue every policy equals equal quotas
  (0.00 tasks/run difference). This is the expected null and a sanity check.
* **At the current design's dissimilarity (`iid_uniform`, resTV 0.035)** the
  nonlinear-vs-DRF differences are small and their intervals include zero at
  moderate contention, consistent with the canonical evaluation. The current
  workload is close to equal quotas because it has little resource heterogeneity.
* **Cobb-Douglas and Leontief** develop a difference over both equal and DRF that
  is positive at both contention levels and grows with realized resource-demand
  dissimilarity. Both clear zero per cell (not only pooled) against DRF from
  dirichlet 0.3 (resTV 0.086, about 2.4x the current design) onward, reaching about
  1-1.4 tasks/run at dirichlet 0.3, about 1.6-2.3 at dirichlet 0.1, and about
  1.6-3.7 at dirichlet 0.03.
* **CES does not share this pattern.** From dirichlet 1.0 onward its difference from
  DRF is frequently negative and its interval sits below zero at dirichlet 0.1 and
  0.03. CES (rho = 0.5) leans toward treating resources as substitutes, which is the
  wrong bias as agents specialize.
* **Joint-linear** is a large loss everywhere heterogeneous (-19 to -29 tasks/run):
  the misspecification stress test. It is never a rational policy for a developer who
  knows the work requires mandatory bundles.

### 2.1 The gains do not hide worst-agent harm

For the concentrated cells (unit floor), fraction of agents completing zero tasks,
fraction worse than equal, and the minimum agent completion:

| regime|cont | policy | frac zero | frac worse than equal | min agent completion |
|---|---|---|---|---|
| dir 0.3 / mod | drf | 0.000 | 0.189 | 0.250 |
| dir 0.3 / mod | joint_cobb_douglas | 0.000 | 0.033 | 0.375 |
| dir 0.3 / mod | joint_leontief | 0.000 | 0.056 | 0.375 |
| dir 0.3 / mod | joint_ces | 0.000 | 0.150 | 0.125 |
| dir 0.03 / high | drf | 0.006 | 0.217 | 0.000 |
| dir 0.03 / high | joint_cobb_douglas | 0.000 | 0.122 | 0.125 |
| dir 0.03 / high | joint_leontief | 0.000 | 0.061 | 0.250 |
| dir 0.03 / high | joint_ces | 0.061 | 0.394 | 0.000 |

Cobb-Douglas and Leontief reach their higher aggregate completion while leaving
**fewer** agents worse than equal than DRF does, with no increase in zero-completion
and equal-or-higher minimum completion. Their aggregate gain is not bought by
harming the worst agents. CES, by contrast, both loses aggregate completion and
raises the fraction of harmed and zero-completion agents.

### 2.2 Cobb-Douglas is separable; Leontief is the joint-computation result

Joint Cobb-Douglas and the exact decomposed per-resource Cobb-Douglas comparator
produce the same installed completion to within +/-0.0014 across all cells (see the
"joint vs decomposed" check). Under weighted proportional fairness the Cobb-Douglas
objective separates by resource, so the Cobb-Douglas gain is a property of the
**declaration shape and allocation policy**, not evidence that joint computation is
required. Leontief retains genuine cross-resource coupling in the joint solver and
is not reproduced by a per-resource rule; its stable advantage under concentration
is the result that specifically implicates joint, coupling-aware allocation as
having real structure to exploit when agents differ in resource shape.

This is not evidence that centralized authority is superior to decentralized
authority: equal quotas and DRF are also computed by the platform. The pilot shows
that a richer, bundle-aware allocation has something real to exploit once workloads
are heterogeneous, not that a central authority is required to capture it.

## 3. Floor-sensitivity results

A dedicated sweep varies the resource floor for `equal`, `drf`, and `joint_linear`
over three workload regimes and both contention levels. Joint-linear (the large
loss) under each floor:

| regime|cont | floor | joint_linear completion | vs equal (tasks) | frac zero | frac worse than equal | floor fraction |
|---|---|---|---|---|---|---|---|
| iid_uniform / mod | zero | 0.064 | -31.4 | 0.806 | 1.000 | 0.000 |
| iid_uniform / mod | unit | 0.110 | -29.2 | 0.422 | 1.000 | 0.020 |
| iid_uniform / mod | proportional_0.25 | 0.314 | -19.5 | 0.000 | 0.956 | 0.249 |
| iid_uniform / mod | proportional_0.50 | 0.467 | -12.5 | 0.000 | 0.933 | 0.499 |
| iid_uniform / mod | proportional_0.75 | 0.602 | -6.1 | 0.000 | 0.672 | 0.749 |
| dir 0.03 / high | zero | 0.039 | -21.2 | 0.878 | 1.000 | 0.000 |
| dir 0.03 / high | proportional_0.25 | 0.162 | -15.0 | 0.044 | 0.967 | 0.248 |
| dir 0.03 / high | proportional_0.75 | 0.413 | -3.0 | 0.000 | 0.583 | 0.748 |

Full results (all regimes, floors, and policies) are in `tables/floor_sensitivity.csv`.

Raising the floor monotonically shrinks the joint-linear loss (from about -31 to
about -6 tasks/run at moderate contention) and removes zero-completion by the
0.25 floor. But even the 0.75 floor leaves joint-linear well below equal and DRF
(-3 to -7 tasks/run), and a majority-to-large fraction of agents remain worse than
equal at every floor. Because the proportional floor is itself allocated in
proportion to mandatory demand, a heavier floor forces the installed allocation
toward proportional-to-demand regardless of the objective: at the 0.75 floor,
three-quarters of each resource is pre-committed proportionally and the linear
objective only distributes the remainder. The floor, not the linear objective, is
doing the work. A higher floor is a robustness mitigation that bounds the
worst-case loss; it is not evidence that linear utility has become correctly
specified. Equal and DRF are essentially flat across floors (they already respect
demand), and there were no capacity or bound violations and no infeasible runs at
any floor.

## 4. Decision-rule application

Against the pilot's stated criteria for a credible candidate positive regime, using
Leontief as the primary case (Cobb-Douglas behaves similarly but is separable):

* Beats **both** equal and DRF: yes, in the concentrated regimes.
* Direction stable at **both** contention levels: yes, from dirichlet 0.3 onward.
* Visible **per cell**, not only pooled: yes; per-cell intervals against DRF clear
  zero from dirichlet 0.3 (Cobb-Douglas) and dirichlet 0.3/0.1 (Leontief).
* Magnitude reported in **task units**: yes (about 1 to 3.7 tasks per run).
* Advantage **grows with realized resource-demand dissimilarity**: yes, roughly
  monotonically from resTV 0.086 to 0.137.
* Not solely a rounding artifact: the effect is 1-3.7 tasks per run and increases
  systematically with dissimilarity.
* Not created by a favorable floor chosen after the fact: all workload-sweep results
  use the current unit floor.
* Does not hide worst-agent or zero-completion harm: confirmed in section 2.1.

The one criterion that is only partially met at moderate concentration is the
one-task-per-run practical reference at **both** contention levels: Cobb-Douglas
clears it at both contention from dirichlet 0.3; Leontief clears it at both
contention from dirichlet 0.1 (at dirichlet 0.3 it is about 1.4 tasks/run at
moderate and about 0.9 at high). The robust, both-contention, at-or-above one
task/run effect therefore requires realized resource-demand dissimilarity roughly
0.09-0.14, about 2.4x-4x the current design.

## 5. What the pilot does and does not establish

* The current mixed evaluation sits close to equal quotas because its realized
  resource-demand dissimilarity is low (0.035). When that dissimilarity is raised by
  concentrating agents onto fewer archetypes, Cobb-Douglas and Leontief produce a
  stable, per-cell, worst-agent-safe completion advantage over equal and DRF that
  grows with dissimilarity. CES does not; it degrades under concentration.
* This is a conditional advantage. It requires workloads more concentrated than the
  current generator produces: the robust form appears once agents are largely
  specialized (dirichlet 0.1-0.03 have mean task entropy 0.09-0.23 and essentially no
  agent covering all four archetypes). Whether real agent systems exhibit this
  degree of cross-agent resource specialization is not established here and is not
  claimed.
* The primary condition is the oracle-information declaration
  (`declaration_source = exact_pending_queue`): the harness builds each declaration
  from the agent's exact pending mandatory demand. The pilot does not test truthful
  elicitation, developer utility-family selection, or stale/strategic declarations.
  `DECLARATION_STALENESS_DESIGN.md` specifies the follow-on that would.
* The Cobb-Douglas result is separable and does not by itself require joint
  computation; Leontief is the family whose advantage implicates joint,
  coupling-aware allocation.

Because a meaningful conditional positive regime was found, the contingent
task-aware admission design is not elaborated here; the recommended next step is the
fresh-seed confirmatory run specified in `NEXT_EXPERIMENT_DECISION.md`.
