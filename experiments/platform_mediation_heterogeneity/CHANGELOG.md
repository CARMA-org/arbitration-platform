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

_(added in the results commit)_
