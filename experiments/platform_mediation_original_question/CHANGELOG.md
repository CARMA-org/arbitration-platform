# Changelog — original-question closure

## v1 (preregistration)

Frozen before any confirmatory seed or outcome was generated.

- Added the architecture experiment (six arms: equal, DRF, central joint Leontief,
  independent bundle max-min, separable Leontief relaxation, distributed price Leontief)
  and its frozen configuration `config/architecture_v1.json`.
- Added the declaration-calibration-and-drift experiment (applied to the frozen-rule
  carrier) and its configuration `config/drift_v1.json`.
- Added `oqlib/` mechanisms: `maxmin.py` (independent bundle max-min, rewritten from the
  development draft to fill each resource in proportion to weight times the declared
  Leontief coefficient), `leontief_relaxation.py` (separable relaxation),
  `distributed.py` (rewritten from the development draft: exact floor-aware closed-form
  agent best response, bounded multiplicative price update, single global-scale
  feasibility repair), `central.py` and `central_ref.py` (central references),
  `declarations.py`, `driftgen.py`, `drift_scenario.py`, `jobs.py`, `mechanisms.py`,
  `execute.py`, `seeds_oq.py`.
- Added the Java harness `preinstalledAllocation` job field (additive) so the Python-
  computed mechanisms are installed and enforced through the identical canonical runtime
  contract path used by every internally computed policy, with a Java test.
- Added the frozen analysis `make_oq_analysis.py`, the frozen adaptive carrier rule
  `select_drift_carrier.py`, the manifest generator `make_oq_manifest.py`, the resumable
  pipeline `run_original_question_closure.py`, and the test suite under `tests/`.
- Added the comparator audit (`COMPARATOR_AUDIT.md`, `comparator_audit.json`,
  `make_comparator_audit.py`) and the distributed solver derivation and validation
  (`DISTRIBUTED_SOLVER.md`, `distributed_validation.json`, `validate_distributed.py`).

### Development observations disclosed (exploratory only, not confirmatory)

The development-seed effect estimates recorded in the development checkpoint were viewed
during engineering de-risking and are disclosed in the protocol. Development data were
used only to validate the solvers and comparators (objective equivalence, feasibility,
distinctness, collapse), not to choose any algorithm, tolerance, threshold, delta level,
primary cell, or carrier by task-completion outcome. No confirmatory seed or outcome
existed before this preregistration.

### Repairs relative to the development checkpoint

- The development `maxmin.py` clamped allocations to the normalized coefficient magnitude
  and collapsed to floors; it was replaced with the correct weighted bundle-progress fill
  before preregistration.
- The development `distributed.py` overflowed to NaN on adversarial cases and diverged on
  binding-floor cases; it was replaced with the exact floor-aware best response, a bounded
  price update, and a global-scale feasibility repair, validated to a maximum relative
  objective gap of 3.65e-5 over 575 well-posed development scenarios.
