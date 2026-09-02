# Reproduction instructions

## Environment

- JDK 21, Maven 3.9+, Python 3.12.
- A Python interpreter with `cvxpy` and `clarabel` for the joint solver and the central
  reference. In this repository that is the git-ignored `experiments_venv_tmp/`; set
  `SOLVER_PYTHON=$(pwd)/experiments_venv_tmp/bin/python3`.
- Build the Java classes and the solver classpath:
  ```
  mvn -q -DskipTests package
  mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
  ```

## Order of operations (as preregistered)

From `experiments/platform_mediation_original_question/`, with `SOLVER_PYTHON` set:

```
$SOLVER_PYTHON run_original_question_closure.py preflight
$SOLVER_PYTHON run_original_question_closure.py architecture   # arch -> analysis -> carrier
# commit and push the architecture raw data, reports and DRIFT_CARRIER_DECISION.json
$SOLVER_PYTHON run_original_question_closure.py drift          # drift -> analysis
$SOLVER_PYTHON run_original_question_closure.py manifests
```

Each experiment driver is resumable: it writes raw rows incrementally per (cell, seed)
unit, skips completed units on restart, and rewrites the raw tables in one canonical sort
order at the end, so the committed data is byte-identical regardless of resumption. The
architecture experiment must be run, analyzed and committed before the drift experiment,
because the drift carrier is selected from the architecture raw data by the frozen rule.

## Determinism

All randomness derives from the repository `derive_seed` machinery (SHA-256 of
pipe-joined parts). Scenario seeds, latent distributions, drift targets, calibration
histories and execution queues each use a distinct stream. The paired bootstrap uses the
frozen seed 20260902 with 20000 resamples and percentile 95% intervals, seeded per
comparison by name. Non-timing fields reproduce exactly on a clean clone; solver
floating-point may differ only within the documented tolerance.

## Development validation (optional)

```
$SOLVER_PYTHON make_comparator_audit.py     # regenerates COMPARATOR_AUDIT.md, comparator_audit.json
$SOLVER_PYTHON validate_distributed.py      # regenerates distributed_validation.json
$SOLVER_PYTHON -m pytest tests -q            # the full test suite
```

These use only development-namespace seeds and constructed examples and never touch a
confirmatory seed.
