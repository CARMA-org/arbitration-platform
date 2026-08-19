# Part A — Before-fix audit (immutable record)

Captured against starting commit `513e1e9217c0965df35cb3c15fbc701c76346b8c`.
Everything in this directory records the behavior of the code **before** any repair
and must not be edited afterwards.

Files:

- `environment.json` — versions, solvers, OS, CPU.
- `joint_solver_ORIGINAL.py` — verbatim copy of `scripts/joint_solver.py` at the audited commit.
- `reproduce_solver_failures.py` / `solver_reproduction.json` — every advertised utility type on the fixed instance.
- `reproduce_rounding_and_consumption.py` / `rounding_and_consumption.json` — rounding and negative-consumption reproductions.

## Fixed instance

Two agents, two resources, weights `[[0.9,0.1],[0.1,0.9]]`, capacities `[100,100]`,
minimums all `1`, ideals all `100`, priorities all `1`.

## Advertised utility types on that instance

| Requested model | Returned status | Model actually used / reported | Column utilization | Finding |
|---|---|---|---|---|
| LINEAR | `optimal` | LINEAR | ~1.00, 1.00 | Works. |
| SQRT | `infeasible` | none (returns minimums) | 0.02, 0.02 | DCP construction failure mislabeled as an infeasible allocation problem; returns a fabricated minimums allocation. |
| LOG | `optimal` | LOG | ~1.00 | Works numerically, but LOG is not in the paper's supported list and is `1+a` shifted. |
| COBB_DOUGLAS | `infeasible` | none (returns minimums) | 0.02 | `exp(sum(w·log a))` placed in an invalid hypograph → not DCP → mislabeled infeasible. |
| CES ρ=0.5 | `infeasible` | none (returns minimums) | 0.02 | `power(sum(w·a^ρ),1/ρ)` construction fails DCP → mislabeled infeasible. |
| CES ρ=−1 | `optimal` | reported `CES` | ~1.00 | **Silent substitution.** Allocation is byte-identical to LINEAR; the constraint link falls through to `u == Σ w·a`. Solves linear, returns `optimal`, labels it CES. |
| LEONTIEF | `optimal` | reported `LEONTIEF` | ~1.00 | Requirement ratios inferred from linear weights; solved as a CES surrogate, not `min_j a_j/r_j`. |
| THRESHOLD | `infeasible` | none | 0.02 | Non-DCP sigmoid construction → mislabeled infeasible. |
| SATIATION | `optimal` | SATIATION | ~1.00 | Solves, but curvature/validation never checked against linear. |
| NESTED_CES | `infeasible` | none | 0.02 | Non-DCP → mislabeled infeasible. |
| SOFTPLUS_LOSS_AVERSION | `optimal` | reported | ~1.00 | Objective offset added; not validated. |
| ASYMMETRIC_LOG_LOSS_AVERSION | crash (exit 1) | none | — | `AttributeError: module 'cvxpy' has no attribute 'tanh'`. Advertised but non-functional. |

### Governing-principle violations demonstrated

1. **Silent model substitution** (CES ρ=−1): allocation identical to LINEAR yet reported as CES.
2. **`optimal` returned after solving a different model** (CES ρ=−1, LEONTIEF).
3. **DCP/model-construction failure labeled `infeasible`** (SQRT, COBB_DOUGLAS, CES ρ=0.5, THRESHOLD, NESTED_CES), each returning a fabricated minimums allocation.
4. **Advertised model crashes** (ASYMMETRIC_LOG_LOSS_AVERSION).

Net: of the eleven advertised types, only LINEAR (and numerically LOG/SATIATION) actually
solve their stated model; the rest are broken, mislabeled, or substituted.

## Rounding — aggregate capacity violation

`ConvexJointArbitrator.parseResult` rounds each cell independently with `Math.round`
(round-half-up). Applied to a feasible continuous allocation whose columns sum exactly
to capacity, this overflows: continuous column sums `[100.0, 100.0]` round to `[101, 100]`
— column 0 exceeds capacity 100. See `rounding_and_consumption.json`.

## Negative consumption reduces recorded use

`RealisticAgentFramework.tryConsumeResource` (lines ~694–711) tests `amount <= remaining`.
A negative `amount` passes and is added to `consumed`, so a request of `-30` against
`consumed=40` yields `consumed=10` and returns `true` — recorded consumption decreases.

## Unconditional PASS

`ServiceDemo.java:111` prints `✓ PASS: Service types defined with resource mappings`
with no assertion or predicate evaluated.

## Formula-derived numbers presented as measurements

- `GroupingPolicyDemo.java:169` — `speedup = unlimitedCost/totalCost` where cost = Σ n³.
  A closed-form estimate printed as "Estimated speedup: N.Nx".
- `GroupingSplitter.getEstimatedOptimalityLoss()` — returns `edgesCut / totalOriginalEdges`,
  i.e. the fraction of contention-graph edges cut, printed in an `OptLoss %` column as if it were a measured optimality gap.
- `ServiceType.estimateCriticalPathLatencyMs()` — a formula, printed as "Est. latency: N ms".

## The 0.382 / golden-ratio material

`ParetoRateStabilityTest.java` frames a ~38.2% weak-Pareto-improvement rate as "≈ 1 − φ"
and searches parameter grids for configurations near it. The constant is defined as
`ONE_MINUS_PHI = 1 - PHI + 1` (= 2 − φ = 0.381966), i.e. the value is right but the
label "1 − φ" is wrong: `0.381966… = 1/φ² = 2 − φ`, not `1 − φ`. The underlying statistic
also uses time-varying priority-weighted scores, so it is not a Pareto-improvement rate under fixed utilities.
