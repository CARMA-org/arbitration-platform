# Reproducibility

## Environment (audited)

| Component | Version |
|---|---|
| Python | 3.12 |
| cvxpy | 1.5.3 |
| clarabel | 0.9.0 |
| numpy | 1.26.4 |
| scipy | 1.13.1 |
| pandas | 2.2.2 |
| matplotlib | 3.9.2 |
| Java (build/run) | 21 |
| Maven | 3.9.x |

Exact versions and the audited machine are recorded in `audit/before_fix/environment.json`
and in `EXPERIMENT_MANIFEST.json`.

## Python solver and tests

    python -m venv venv && source venv/bin/activate
    pip install -r experiments/joint_allocation/requirements.txt
    python -m pytest tests/python -q

The tests cover input validation, every supported model, rejection of unsupported models,
absence of silent model substitution, the Cobb-Douglas closed form, Leontief
balanced-bundle behavior, CES against multi-start SciPy, random linear instances against
an independent solver, and capacity/bound preservation of the rounding rule.

## Java build and tests

    mvn test

The Java suite covers negative-consumption rejection, capacity- and bound-preserving
rounding, and `ResourcePool` boundary checks. A guarded integration test exercises the
Python solver end to end; point it at an interpreter that has the solver installed with
`SOLVER_PYTHON=/path/to/python mvn test`.

## Experiments

    cd experiments/joint_allocation
    python run_all.py --smoke     # quick smoke pass first
    python run_all.py             # full pass

All randomness flows from `lib/seeds.py`, which derives 32-bit seeds by hashing string
labels with SHA-256. Training and test seed sets are disjoint. Each experiment writes:

- raw per-instance CSV under `results/raw/` (regenerable; gitignored),
- aggregated tables under `tables/` (committed),
- a summary JSON under `results/`,
- figures under `figures/`.

`EXPERIMENT_MANIFEST.json` records configurations, seeds, versions, SHA-256 hashes of
committed tables, and the exact commands. Re-running the commands on the pinned
environment reproduces the committed tables bit-for-bit for deterministic steps.

## Before-fix audit

`audit/before_fix/` is an immutable record of the pre-repair behavior (the original
solver, its outputs on every advertised utility type, and the rounding / negative-consumption
reproductions). Re-running `audit/before_fix/reproduce_solver_failures.py` against the
preserved `joint_solver_ORIGINAL.py` reproduces those records.
