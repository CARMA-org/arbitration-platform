# Change log: workload-heterogeneity pilot

A protected, isolated pilot testing whether cross-agent workload concentration
creates enough resource heterogeneity for the existing joint allocation policies to
produce task-completion gains over equal quotas and DRF. It adds only new files
under `experiments/platform_mediation_heterogeneity/`; it changes no canonical
evaluation code, data, table, report, bundle, checksum, tag, or license.

## Source and tests

New directory `experiments/platform_mediation_heterogeneity/`:

* `config/pilot.json` -- frozen pilot configuration (fixed 6 agents, 8 tasks/agent,
  4 resources, 2 contention levels, 7 policies; workload regimes; floor regimes;
  30 development seeds; bootstrap seed).
* `pilotlib/` -- pilot library, importing the canonical `lib` without modifying it:
  * `workload.py` -- deterministic workload generator (homogeneous and iid_uniform
    reuse the canonical construction; symmetric-Dirichlet mixtures on the frozen
    grid); records per-agent latent mixtures; no dissimilarity-based rejection.
  * `floors.py` -- zero / unit / proportional floor regimes; proportional floors use
    deterministic largest-remainder apportionment with upper caps and a per-resource
    budget of `floor(f * capacity)`.
  * `pilot_scenario.py` -- scenario builder (capacities, declarations, bounds,
    `task_workload_hash`, `scenario_hash`, inactive-resource handling,
    `declaration_source = exact_pending_queue`); reuses the canonical `make_job`.
  * `measures.py` -- total-variation dissimilarity measures (task-mixture,
    resource-demand, centroid distance, normalized entropy, coverage).
  * `pilot_analysis.py` -- paired-diff and bootstrap helpers over the raw records.
* `run_pilot.py` -- sweep driver for the workload-concentration and floor sweeps,
  with factorial control (one workload per (regime, seed) reused at both contention
  levels); every policy runs through the canonical Java harness.
* `diagnostic_baseline.py` -- read-only reconstruction of the canonical generator
  and allocation diagnostics from canonical raw + deterministic regeneration
  (validated by scenario/workload hash equality); emits `BASELINE_DIAGNOSTIC.md`.
* `make_pilot_tables.py`, `make_pilot_memo.py`, `make_pilot_manifest.py` --
  per-cell / dissimilarity / floor tables, a machine-generated memo, and an artifact
  manifest with SHA-256 hashes.
* `tests/` -- unit tests (determinism, canonical preservation, TV correctness,
  Dirichlet, floors, hashing, factorial control, no-rejection) and live-harness
  integration tests (capacity preservation, no fallback, complete row counts, policy
  coverage, tasks-per-run conversion, summary reconstructibility).
* `REPRODUCIBILITY.md`, `DECLARATION_STALENESS_DESIGN.md` -- exact commands and
  metric definitions; the design (only) of the later declaration-drift experiment.

## Results

Generated pilot artifacts (from source at the previous commit; canonical evaluation
untouched):

* `BASELINE_DIAGNOSTIC.md` + `results/baseline_diagnostic.json` +
  `tables/baseline_*.csv` -- read-only reconstruction of the canonical evaluation.
  Regeneration matches the canonical raw records on 400/400 scenario/workload hashes;
  reproduces resource-demand TV 0.036532, archetype coverage 356/600 (mixed,
  moderate) and 396/600 (homogeneous, moderate), and the allocation distances DRF
  11.49% / decomposed CD 4.24% / joint CD 4.25% / CES 7.77% / Leontief 6.96% /
  linear 83.62%.
* `results/raw/workload_*.csv`, `results/summary_workload.json` -- workload-
  concentration sweep: 2,940 runs, 17,640 agent records, 0 infeasible, 0 capacity/
  bound violations, 0 fallback.
* `results/raw/floor_*.csv`, `results/summary_floor.json` -- floor-sensitivity
  sweep: 3,240 runs, 19,440 agent records, 0 infeasible, 0 violations, 0 fallback.
* `tables/cell_policy_effects.csv`, `tables/workload_dissimilarity.csv`,
  `tables/floor_sensitivity.csv`, `results/pilot_headline.json`,
  `results/PILOT_MEMO.md` -- per-cell effects, dissimilarity, floors, and the
  machine-generated memo.
* `PILOT_RESULTS.md`, `NEXT_EXPERIMENT_DECISION.md` -- findings and the selected
  next-experiment decision.
* `EXPERIMENT_MANIFEST.json` -- SHA-256 manifest of the pilot artifacts.

Finding (summary): at the current design's realized resource-demand dissimilarity
(0.035) the nonlinear-vs-DRF differences are small with intervals including zero, as
in the canonical evaluation. As cross-agent concentration raises resource-demand
dissimilarity, Cobb-Douglas and Leontief develop a stable, per-cell, worst-agent-safe
completion advantage over both equal and DRF that grows with dissimilarity (about 1
to 3.7 tasks per 48-task run in the concentrated cells); CES degrades under
concentration; joint_linear is a large loss throughout. Cobb-Douglas is separable
(joint = decomposed); Leontief is the joint-coupling result. The floor sweep shows a
higher floor bounds the joint_linear loss but never makes it competitive and does so
by forcing near-proportional allocation. Decision: credible conditional advantage,
warranting a fresh-seed confirmatory run (see `NEXT_EXPERIMENT_DECISION.md`).
