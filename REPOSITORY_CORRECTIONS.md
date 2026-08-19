# Repository corrections

Each row: an inaccurate claim or behavior in the audited baseline
(`513e1e9`), and how v0.9 corrects it. The pre-repair behavior is preserved in
`audit/before_fix/`.

## Solver correctness

| Inaccurate claim / behavior | Correction |
|---|---|
| CES `rho=-1` and Leontief returned `optimal` while actually solving a linear model (byte-identical allocation to linear). | Solver rewritten so `solved_utility` always equals `requested_utility`; CES uses a `pnorm` formulation, Leontief uses an explicit `min_j a_j/r_j` epigraph. No silent substitution. |
| SQRT, COBB_DOUGLAS, CES 0.5, THRESHOLD, NESTED_CES returned `infeasible` with a fabricated minimums allocation when the real cause was a non-DCP construction. | `problem.is_dcp()` is checked; construction failures return `model_error`, and unsupported types return `unsupported_model`. On any non-optimal status `allocations` is null. |
| Cobb-Douglas built `exp(sum(w log a))` in an invalid hypograph. | Cobb-Douglas optimizes `sum_j beta_j log(a_j)` directly (log domain). |
| ASYMMETRIC_LOG_LOSS_AVERSION called nonexistent `cvxpy.tanh` and crashed. | Removed from the supported set; returns `unsupported_model`. |
| "All eleven utility types work." | Only `LINEAR`, `COBB_DOUGLAS`, `CES`, `LEONTIEF` are supported and tested; the other seven are rejected. See `docs/MODEL_SUPPORT.md`. |
| Java caller treated only `optimal` as feasible and silently fell back to the sequential/linear allocator on any error. | Caller reads the structured status schema, surfaces `error_type`/`error_message` and warnings, and no longer falls back by default. Enabled fallback names both requested and actual model. |

## Enforcement and rounding

| Inaccurate claim / behavior | Correction |
|---|---|
| `tryConsumeResource` accepted negative amounts and reduced recorded consumption. | Negative and (for `canConsumeResource`) negative requests are rejected without changing state; tests cover negative/zero/valid/excess. |
| `ResourcePool.allocate`/`release` mishandled negative amounts. | Both reject negative amounts. |
| Independent cellwise `Math.round` could push a column sum above capacity. | Replaced by bounded largest-remainder rounding per column; zero capacity violations over 1000 instances; documented as feasibility-preserving, not discrete Pareto-optimal. |

## Documentation and output claims

| Inaccurate claim / behavior | Correction |
|---|---|
| README "complete implementation", blanket theoretical guarantees, "validation demos (mathematical proofs)". | Reworded as a research prototype; guarantees and "proof" framing removed. |
| Collusion-resistance / individual-rationality claims based only on minimum constraints. | Removed; no such property is proven in the repository. |
| Unconditional `✓ PASS` in `ServiceDemo`. | Now gated on an actual predicate (every service type has resource mappings). |
| "Estimated speedup" and `OptLoss` presented as measured benchmarks. | `OptLoss` column relabelled `EdgeCut%` (it is the fraction of contention-graph edges cut); speedup remains labelled an `O(n^3)` estimate, and the README no longer presents it as a measurement. |
| MIT license stated without authorization. | License section removed from README; licensing flagged for the owner (see below). |

## The 0.382 / golden-ratio material

| Inaccurate claim / behavior | Correction |
|---|---|
| `0.381966...` labelled `1 - phi`. | Corrected to the true identity `1/phi^2 = 2 - phi`. |
| "golden-ratio conjecture", "attractor", search for configurations near the target. | Renamed to the "nondecreasing endogenous-weighted-score transition rate"; Part 5 now runs a predetermined grid and reports the full range first, then counts configurations within 0.01 of the reference without inferring a law, attractor, or mechanism. |
| Statistic described as a Pareto-improvement rate. | Documented that it uses time-varying priority-weighted scores and is therefore not a Pareto-improvement rate under fixed utilities. |

## Deliberately left unresolved

- **Licensing.** The README previously asserted MIT without a `LICENSE` file or other
  authorization in the repository. The unsupported statement was removed; no license was
  added. The owner must decide the license explicitly.
- **CES `rho < 0` at scale.** Supported and validated on small instances, but omitted
  from Experiment 3 because its conic solve is only `optimal_inaccurate` at the 8x4 scale
  (see `RESULTS_FOR_PAPER.md`). A more robust conic reformulation is left for future work.
- **Java runtime nonlinear path.** The default Java arbitrator sends only linear configs;
  nonlinear models are exercised through the Python solver and harness, not the Java path.
