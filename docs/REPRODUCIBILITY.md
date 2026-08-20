# Reproducibility

## Environment

| Component | Version |
|---|---|
| Python | 3.12 |
| cvxpy | 1.5.3 |
| clarabel | 0.9.0 |
| numpy | 1.26.4 |
| scipy | 1.13.1 |
| pandas | 2.2.2 |
| Java (build/run) | 21 |
| Maven | 3.9.x |

The platform-mediation experiment manifest at
`experiments/platform_mediation/EXPERIMENT_MANIFEST.json` records the exact
Python executable, dependency versions, Java and Maven versions, operating
system, source commit, configuration hashes, and SHA-256 hashes of every
artifact.

## Python solver and tests

    python3 -m venv .venv && . .venv/bin/activate
    pip install -r experiments/joint_allocation/requirements.txt
    python -m pytest tests/python -q

The tests cover input validation, every supported utility family, rejection of
unsupported families with no silent substitution, the Cobb–Douglas closed form,
Leontief behaviour including zero-requirement resources, CES against multi-start
SciPy, random linear instances against an independent solver, and
capacity/bound preservation of the rounding rule.

## Java build and tests

    export SOLVER_PYTHON="$PWD/.venv/bin/python3"
    mvn -o test

The Java suite covers contract snapshot installation and conservation, the shared
consumption ledger under concurrent and repeated execution, execution binding
(expired, stale, and removed-agent denial), custom service resource charging, the
solver hard timeout against a genuinely hung process, capacity- and
bound-preserving rounding, and composition validation. The solver-dependent tests
run only when `SOLVER_PYTHON` points at an interpreter with cvxpy/clarabel.

## Experiments

    mvn -o -q dependency:build-classpath -Dmdep.outputFile=cp.txt
    cd experiments/platform_mediation && python3 run_sweep.py --full
    cd ../enforcement && python3 run_enforcement.py --reps 100
    cd ../dynamic_allocation && python3 run_dynamic.py --full

All randomness flows from each experiment's `lib/seeds.py`, which derives 32-bit
seeds by hashing string labels with SHA-256. Calibration and test seed sets are
disjoint, and every policy in a cell sees the same seed and scenario. Each
experiment writes raw per-run CSVs under `results/raw/`, aggregated tables under
`tables/`, a summary JSON under `results/`, and figures under `figures/`.

The historical v0.9 joint-allocation experiment is preserved under
`experiments/joint_allocation/` and is reproduced with `python run_all.py`.
