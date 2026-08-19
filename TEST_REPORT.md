# Test report

Environment: Python 3.12 (cvxpy 1.5.3, clarabel 0.9.0, numpy 1.26.4, scipy 1.13.1);
Java 21; Maven 3.9.x. Commands: `python -m pytest tests/python -q` and
`SOLVER_PYTHON=<python-with-cvxpy> mvn test`.

## Summary

| Suite | Tests | Passed | Failed | Skipped |
|---|---|---|---|---|
| Python (pytest) | 27 | 27 | 0 | 0 |
| Java (JUnit 5) | 13 | 13 | 0 | 0 |
| Total | 40 | 40 | 0 | 0 |

No tests skipped. The Java integration test runs (not skipped) when `SOLVER_PYTHON`
points to an interpreter with the solver installed; otherwise it self-skips via a
JUnit assumption (that path is not counted above because the reported run supplied the
solver).

## Python tests (`tests/python`)

Solver contract (`test_solver.py`):
- `test_validation_rejects[...]` (6 cases): priorities positive, weights nonnegative,
  minimums <= capacity, minimums <= ideals, shape agreement, finiteness.
- `test_missing_field`: missing required field -> validation_error.
- `test_linear_optimal`: linear solves and respects capacity.
- `test_cobb_douglas_closed_form`: matches `a_ij ~ c_i beta_ij` closed form.
- `test_cobb_douglas_joint_equals_separable`: per-resource separable closed form.
- `test_ces_matches_scipy`: CES rho in {-1, 0.5} welfare matches multi-start SciPy.
- `test_leontief_balanced_bundle`: binding resource and achieved utility (with the
  documented slack non-uniqueness).
- `test_ces_rho1_is_linear_special_case`: CES rho=1 equals linear, reported transparently.
- `test_no_silent_substitution_ces_negative`: CES(-1) allocation differs from linear.
- `test_unsupported_models_rejected[...]` (8 cases): SQRT, LOG, THRESHOLD, SATIATION,
  NESTED_CES, SOFTPLUS_LOSS_AVERSION, ASYMMETRIC_LOG_LOSS_AVERSION, and an unknown type
  all return `unsupported_model` with null allocations.
- `test_random_linear_matches_independent_solver`: 10 random instances vs SciPy.

Rounding (`test_rounding.py`):
- `test_naive_can_violate_capacity`: naive rounding overflows a column.
- `test_largest_remainder_preserves_capacity_random`: 500 random cases, capacity and
  bounds preserved.
- `test_largest_remainder_respects_lower_bounds`.
- `test_largest_remainder_deterministic`.

## Java tests (`src/test/java`)

- `NegativeConsumptionTest` (5): negative rejected without state change, zero, valid
  positive, excessive rejected, `canConsumeResource` rejects negative.
- `CapacityPreservingRoundingTest` (4): capacity never exceeded (incl. 2000 randomized
  cases), lower/upper bounds respected, deterministic output.
- `ResourcePoolBoundaryTest` (3): negative allocate/release rejected, valid path works.
- `JointSolverIntegrationTest` (1): end-to-end joint linear solve respects capacity
  (runs when `SOLVER_PYTHON` has the solver; otherwise self-skips).
