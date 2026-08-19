# Results for paper

Exact results only. Findings are separated from interpretation. Every number links
to a generated file under `experiments/joint_allocation/`. The paper will be written
from these results; do not reuse any prior headline figure.

Scale: full runs use the seed counts in each summary JSON (Experiments 1, 2 use 100
test seeds per cell; 3 uses 50; 4 uses 60; 5 uses 100 seeds per event). Training and
test seeds are disjoint. Comparator `gamma` is tuned on training seeds only.

Objective = weighted-log welfare `sum_i c_i log(Phi_i)`. "Objective regret" of a rule =
joint welfare minus that rule's welfare; it is >= 0 by construction because the joint
rule optimizes this objective. Utility is normalized within each family by each agent's
utility at its ideal bundle.

---

## Rounding (Part C) — `results/rounding_comparison.json`

1000 linear instances (6 agents, 3 resources, five Dirichlet settings).

| Rounding rule | Capacity violations | Max excess | Bound violations | Median geomean utility loss |
|---|---|---|---|---|
| Naive per-cell `Math.round` | 161 | 1 | 0 | 2.0e-5 |
| Bounded largest-remainder | 0 | 0 | 0 | 2.5e-5 |

Finding: independent cellwise rounding violated capacity in 161 of 3000 columns; the
capacity-preserving rule produced zero violations at negligible welfare cost.

---

## Experiment 1 — reinterpreting the original sweep

Files: `results/experiment1_full_summary.json`, `tables/experiment1_full.csv`,
`figures/experiment1_regret_full.png`. Six agents, three resources, five Dirichlet
concentrations. Globally tuned separable exponent: `gamma = 2.0`.

Median objective regret vs joint, by rule and Dirichlet alpha:

| alpha | proportional (gamma=1, original) | tuned (gamma=2) | oracle envelope |
|---|---|---|---|
| 0.1 | 0.281 | 0.155 | 0.138 |
| 0.3 | 0.685 | 0.337 | 0.336 |
| 1.0 | 1.037 | 0.549 | 0.485 |
| 3.0 | 1.114 | 0.682 | 0.475 |
| 10.0 | 0.866 | 0.647 | 0.425 |

Non-objective metrics (pooled medians), `tables/experiment1_full.csv`:

| rule | fraction of agents worse than equal shares | median normalized min utility | median normalized mean utility |
|---|---|---|---|
| equal shares | 0.000 | 0.167 | 0.167 |
| proportional (gamma=1) | 0.000 | 0.177 | 0.229 |
| tuned (gamma=2) | 0.167 | 0.162 | 0.258 |
| joint | 0.000 | 0.192 | 0.274 |

Findings:
- The stronger tuned separable comparator (`gamma=2`) roughly halves the objective
  regret of the original proportional rule at every concentration (e.g. at `alpha=1.0`,
  1.037 -> 0.549).
- Regret peaks at intermediate concentration (`alpha=3.0`) for both the original and
  the tuned comparator; the peak persists but shrinks with the stronger comparator.
- On the objective, joint wins by construction. On a metric not identical to the
  objective, joint alone keeps every agent at or above equal shares (0.000 worse) while
  also having the highest minimum normalized utility (0.192).
- The tuned separable rule buys objective at a distributional cost: it pushes 16.7% of
  agents below their equal-shares utility.

Interpretation (not a finding): a substantial part of the previously reported
proportional-vs-joint gap reflects the weakness of proportional splitting rather than a
fundamental need for cross-resource joint optimization.

---

## Experiment 2 — breadth vs asymmetry (primary)

Files: `results/experiment2_full_summary.json`, `tables/experiment2_full.csv`,
`figures/experiment2_heatmap_full.png`. Eight agents, four resources, equal priorities.
Achieved breadth equals target to <0.01. Tuned separable exponent: `gamma = 4.0`.

Achieved cosine dissimilarity by asymmetry lambda: 0.00, 0.107, 0.285, 0.412, 0.430
(it rises with lambda and is not identical across breadths).

Median objective regret of the tuned separable rule vs joint (rows = breadth B,
columns = asymmetry lambda):

| B \ lambda | 0.00 | 0.25 | 0.50 | 0.75 | 1.00 |
|---|---|---|---|---|---|
| 1.3 | 0.000 | 0.056 | 0.034 | 0.010 | 0.003 |
| 2.0 | 0.000 | 0.457 | 0.438 | 0.300 | 0.359 |
| 3.0 | 0.000 | 0.551 | 0.652 | 0.536 | 0.491 |
| 3.8 | 0.000 | 0.430 | 0.645 | 0.616 | 0.615 |

Findings:
- At `lambda = 0` (all agents share one preference vector) the tuned separable rule
  matches joint exactly (regret 0.000) at every breadth.
- At fixed positive lambda, regret increases with breadth from B=1.3 (near zero) to
  B=3.0-3.8 (~0.5-0.65).
- At fixed breadth >= 2.0, regret is substantial once lambda > 0 but does not increase
  monotonically in lambda; it is largest in the mid-to-high range.
- There is no persistent interior peak in breadth; regret rises with breadth and
  saturates near B=3.0-3.8.

Interpretation (not a finding): the joint rule's advantage over the best separable rule
requires both breadth (agents value several resources) and asymmetry (agents differ);
neither factor alone produces it.

---

## Experiment 3 — utility-family sensitivity

Files: `results/experiment3_full_summary.json`, `tables/experiment3_*_full.csv`.
Eight agents, four resources.

Cobb-Douglas joint-vs-separable check (`cobb_douglas_joint_vs_separable`):
- welfare absolute difference: 3.9e-7
- allocation max difference on non-degenerate columns: 5.9e-3

Finding: for Cobb-Douglas the joint solution equals the per-resource separable solution
to solver tolerance; joint control adds no allocative value because the weighted-log
objective separates across resources. Allocations can differ only in columns where every
weight is ~0, which does not change welfare (allocation non-uniqueness).

Tuned separable exponent and its mean regret by family:

| family | tuned gamma | mean regret at gamma=1 | mean regret at tuned gamma |
|---|---|---|---|
| COBB_DOUGLAS | 1.0 | 0.017 | 0.017 |
| CES rho=0.5 | 2.0 | 0.283 | 0.027 |
| LINEAR | 4.0 | 1.199 | 0.398 |
| LEONTIEF (r = beta) | 1.0 | 0.453 | 0.453 |

Findings:
- The regret-minimizing separable exponent differs by family (1, 2, 4, 1); there is no
  universal best separable rule.
- For Cobb-Douglas the original `gamma=1` rule is already near-optimal (regret 0.017).
- For Leontief, increasing `gamma` sharply worsens regret (0.45 at gamma=1 rising to
  >13 at gamma=16), consistent with Leontief favoring balanced rather than concentrated
  bundles.

Omitted: `CES rho=-1` (recorded in `omitted_families`). It is supported and validated on
small instances (the test suite matches multi-start SciPy) but at the 8x4 scale its
conic formulation returns only `optimal_inaccurate` solutions and occasionally points
worse than the separable comparator, so its regret is not reliable; it was omitted rather
than reported from an unreliable solve.

---

## Experiment 4 — bounds and priorities

Files: `results/experiment4_full_summary.json`, `tables/experiment4_bounds_full.csv`,
`tables/experiment4_priorities_full.csv`. Representative cell B=3.0, lambda=0.5.

Tuned separable exponent: `gamma = 4.0` for both the bounds sweep and the priority
sweep, the same value found in Experiment 2.

Finding: introducing meaningful caps and floors (`h/Q in {1,0.5,0.25}`,
`l/Q in {0,0.01,0.05}`) and lognormal priority dispersion (`s in {0,0.5,1}`) does not
change the tuned separable exponent; the ordering of rules is stable. Per-cell detail is
in the two tables.

---

## Experiment 5 — reallocation (compact)

File: `results/experiment5_full_summary.json`,
`results/raw/experiment5_full.csv`. Six incumbents, four resources, 100 seeds per event.
Commitment-preserving reoptimization uses floors `u0_i(a_i) >= u0_i(a0_i)` under the old
accepted (linear) utility representation.

| event | commitment feasibility rate | median objective cost of commitment | entrant admission (unrestricted) | median worst incumbent loss under unrestricted reopt |
|---|---|---|---|---|
| preference drift | 1.00 | 0.082 | n/a | -0.149 |
| new-agent arrival | 0.00 | n/a | 1.00 | n/a |
| capacity reduction (-30%) | 0.00 | n/a | n/a | n/a |

Findings:
- Under preference drift, honoring commitments is always feasible; its median objective
  cost is 0.082, and its floors bind for essentially all incumbents. Without commitments,
  the worst incumbent loses ~15% of its old-representation utility.
- Under new-agent arrival and under a 30% capacity reduction, commitment-preserving
  reoptimization is infeasible in every seed: incumbents saturate capacity, so preserving
  all incumbent utilities leaves no room for an entrant or for a smaller pool. The report
  does not invent a loss-sharing rule for these cases.
