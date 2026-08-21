# Utility model support

The joint solver (`scripts/joint_solver.py`) maximizes the weighted-log objective

    maximize  sum_i c_i * log(Phi_i(a_i))

subject to per-resource capacity, per-cell minimums, and per-cell ideals. A model
is *supported* only if it is a mathematically valid concave (or concave-representable)
formulation, it passes a CVXPY DCP check, a test distinguishes it from linear utility,
and a small instance is independently verified. Every other historical type name is
returned with status `unsupported_model` and is **never** silently replaced by a linear
surrogate.

## Supported

| Model | Definition | Representation | Notes |
|---|---|---|---|
| `LINEAR` | `Phi = sum_j beta_j a_j` | `log(beta . a)` | — |
| `COBB_DOUGLAS` | `Phi = prod_j a_j^beta_j`, `sum_j beta_j = 1` | `sum_j beta_j log(a_j)` (log optimized directly) | requires every positively-weighted resource to admit a strictly positive allocation |
| `CES` | `Phi = (sum_j beta_j a_j^rho)^(1/rho)`, `rho < 1`, `rho != 0` | `log(pnorm(scale ⊙ a, rho))` with `scale_j = beta_j^(1/rho)` | `rho = 1` handled as `LINEAR`; `rho -> 0` handled as `COBB_DOUGLAS`; `rho < 0` requires strictly positive allocations |
| `LEONTIEF` | `Phi = min_j a_j / r_j`, `r_j > 0` | aux var `u <= a_j / r_j` for each `j` | requires an explicit `requirements` vector; ratios are never inferred from weights |

### CES representation detail

For `beta_j >= 0` and `a_j >= 0`, with `x_j = beta_j^(1/rho) a_j`,

    (sum_j beta_j a_j^rho)^(1/rho) = (sum_j x_j^rho)^(1/rho) = pnorm(x, rho).

CVXPY's `pnorm(x, p)` is concave on the nonnegative orthant for `p < 1, p != 0`
(including negative `p`), so `log(pnorm(...))` is a valid concave objective term.
CVXPY rationalizes the exponent `p`; the rational actually used is recorded in the
result under `meta.ces_pnorm_p`. Every assembled problem is checked with
`problem.is_dcp()` before solving; a non-DCP assembly returns `model_error` rather
than a substituted model.

Tested at `rho in {-1, 0.5, 1}` against multi-start SciPy SLSQP (see
`tests/python/test_solver.py`).

## Unsupported (removed from the supported list)

`SQRT`, `LOG`, `THRESHOLD`, `SATIATION`, `NESTED_CES`, `SOFTPLUS_LOSS_AVERSION`,
`ASYMMETRIC_LOG_LOSS_AVERSION`.

Reasons:

- `SQRT` equals `CES` with `rho = 0.5` (`(sum w a^0.5)^2 = (sum w a^rho)^(1/rho)`),
  so it is redundant; use `CES rho=0.5`.
- `LOG` (`sum_j w_j log(1+a_j)`) is concave but is outside the studied model set for
  v0.9 and has no validating test.
- `THRESHOLD`, `SATIATION`, and `NESTED_CES` do not have a DCP-valid concave assembly
  in this solver.
- `SOFTPLUS_LOSS_AVERSION` and `ASYMMETRIC_LOG_LOSS_AVERSION` do not have a valid
  concave hypograph construction in this solver.

The solver rejects these families at validation rather than substituting an
approximation. A family can join the supported list if a DCP-valid,
independently validated formulation is added with a test.

## Status schema

Every solve returns:

`status`, `requested_utility`, `solved_utility`, `solver`, `objective_value`,
`allocations`, `utilities`, `warnings`, `error_type`, `error_message`, `meta`.

`status` is one of `optimal`, `optimal_inaccurate`, `infeasible`, `unbounded`,
`unsupported_model`, `model_error`, `solver_error`, `validation_error`. On any
non-optimal status `allocations` is `null` (no fabricated allocation). `solved_utility`
always equals `requested_utility` for a supported optimal solve; the `rho = 1` and
`rho = 0` CES special cases are reported transparently (e.g. `CES(rho=1)->LINEAR`).
