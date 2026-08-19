# Experiments

The self-contained harness lives in `experiments/joint_allocation/`. It compares
allocation rules under the weighted-log objective and reports both objective regret and
non-objective outcome metrics. It does not depend on any values copied from a paper.

## Layout

    experiments/joint_allocation/
      requirements.txt        version-pinned dependencies
      lib/                    solver wrapper, generators, rules, metrics, rounding, seeds
      run_experiment{1..5}.py per-experiment drivers (--smoke for a fast pass)
      run_rounding_comparison.py
      run_all.py              runs everything and the figures
      aggregate via lib/aggregate.py -> tables/
      figures.py -> figures/
      results/                summaries and raw/ CSVs (raw is gitignored)
      tables/                 aggregated CSV tables (committed)

## Allocation rules compared

1. Equal shares and priority-weighted shares (bounded).
2. The original proportional-to-weight rule (`gamma = 1`).
3. A prespecified separable family: bounded per-column water-filling with scores
   `s_ij = c_i * max(beta_ij, eps)^gamma`, `gamma in {0, 0.25, 0.5, 1, 2, 4, 8, 16}`.
   Each resource is allocated independently using only that resource's weights,
   priorities, bounds, and capacity; residual capacity is redistributed after agents
   reach their caps.
4. Joint weighted-Nash allocation (the Python solver).

Reported separable comparators: the original `gamma = 1`; a single globally tuned
`gamma` selected on training seeds and evaluated on disjoint test seeds; and the per-cell
upper envelope over the family (an explicitly labelled oracle sensitivity bound). Tuning
and evaluation never share seeds.

## Metrics

The weighted-log objective is the criterion the joint rule optimizes, so a separable
rule's shortfall is reported as **objective regret** (joint minus rule); the joint rule's
100% win rate on this metric is definitional, not an empirical discovery. For every rule
the harness also reports non-objective metrics: arithmetic mean, minimum, and median of
normalized agent utility; fraction of agents worse off than equal shares and than the
strongest separable comparator; worst and fifth-percentile individual change; capacity
utilization; fraction of cells at lower bound, interior, and upper bound; and solve time.
Utility is normalized within each family by each agent's utility at its ideal bundle;
raw magnitudes are never compared across families. Distributions are summarized by
median, IQR, fifth and ninety-fifth percentiles.

## Experiments

- **Experiment 1** reproduces the five Dirichlet concentration settings and adds the new
  comparators and metrics, to see how much of the original near-uniform / interior-peak
  story survives a stronger resource-local comparator.
- **Experiment 2** independently varies breadth `B in {1.3, 2.0, 3.0, 3.8}` and an
  asymmetry parameter `lambda in {0, 0.25, 0.5, 0.75, 1}` using a shared/idiosyncratic
  construction with per-row temperature fitting so breadth is held fixed while
  orientation changes. Achieved breadth and pairwise cosine dissimilarity are recorded
  per cell. Primary output: a heatmap of tuned-separable objective regret.
- **Experiment 3** compares utility families (`CES rho=-1`, `COBB_DOUGLAS`,
  `CES rho=0.5`, `LINEAR`, and explicit `LEONTIEF` with requirement vector `r = beta`).
  It numerically verifies that joint Cobb-Douglas welfare equals the per-resource
  separable solution.
- **Experiment 4** varies caps (`h/Q`), floors (`l/Q`), and lognormal priority
  dispersion on a representative broad, asymmetric cell, using the priority-aware
  comparators.
- **Experiment 5** is a small reallocation study: preference drift, new-agent arrival,
  and capacity reduction, comparing unrestricted reoptimization with
  commitment-preserving reoptimization whose floors use the old accepted utility
  representation.

## Running

    pip install -r experiments/joint_allocation/requirements.txt
    cd experiments/joint_allocation
    python run_all.py --smoke      # fast pass
    python run_all.py              # full pass

Seeds are derived deterministically (SHA-256 of labels) in `lib/seeds.py`; train and
test seed sets are disjoint by construction.
