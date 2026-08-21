# Known limitations

This repository is an implementation artifact and research prototype, not a product or
a separate scholarly publication. Known limitations of the current code:

## Solver and models

- Only four utility families are supported (`LINEAR`, `COBB_DOUGLAS`, `CES`,
  `LEONTIEF`). Seven historical type names are rejected as `unsupported_model`
  (see `docs/MODEL_SUPPORT.md`).
- `CES` with `rho < 0` and `COBB_DOUGLAS` require strictly positive allocations on
  positively-weighted resources; instances that force such a cell to zero are rejected
  by input validation.
- The objective is the weighted-log (proportional-fairness) welfare. This is a modeling
  choice, not a theorem about fairness; no individual-rationality, strategyproofness, or
  collusion-resistance property is proven anywhere in the code, and none is claimed.
- Allocation non-uniqueness: on resources where every agent's weight is ~0 the optimal
  allocation is indeterminate. This does not affect the welfare or utility vector but can
  affect which integer allocation rounding produces.

## Runtime paths

- A registered agent's utility declaration (linear, Cobb–Douglas, CES, Leontief) and its
  declared minimum and upper bounds are carried through the runtime into the arbitration
  model and the solver input; the runtime does not regenerate declarations from service
  defaults when explicit declarations are present.
- `ConvexJointArbitrator` shells out to the Python solver under a hard timeout and does not
  fall back silently. Fallback to the per-resource sequential allocator must be explicitly
  enabled; when enabled the result message names both the requested and the actual model.
- The bundled JSON parsing in the Java caller is minimal and intended only for the
  solver's own output format.

## Rounding

- Integer conversion uses bounded largest-remainder rounding per resource column. It is
  a feasibility-preserving rule (never exceeds capacity or bounds); it is **not** a proof
  of discrete Pareto optimality. In near-degenerate columns the chosen integer point is
  one of several with equal welfare.

## Experiments

- Experiment scale is documented per run (`--smoke` vs full) in
  `EXPERIMENT_MANIFEST.json`, which enumerates and hashes the result artifacts it lists
  rather than every file in the bundle. Results are seed-deterministic and reflect the
  specific synthetic task model, not any external dataset. Task outputs are mock and
  resource requirements are synthetic service-cost constants. Reported allocation-
  computation latency is the time to compute an allocation (process startup, model
  construction, solve, parsing, integer conversion), not a task-completion metric.
- The primary experiment uses two workload compositions (`homogeneous`, `mixed_bundle`)
  and two contention levels, with each seed an independent workload draw. The homogeneous
  composition is a symmetry check, not evidence of a general effect. Priorities are held
  equal to isolate utility semantics.
- The dynamic experiment is a secondary solver-level simulation. It uses an agent-targeted
  event schedule and verifies commitment floors against the rounded allocation, but it
  does not install runtime contracts or drive the runtime clock and is not a runtime-timing
  validation.
- Continuous joint and decomposed Cobb–Douglas solutions agree up to the joint
  solver's numerical accuracy (measured by `validate_decomposition.py`), but their
  installed integer matrices can differ by a unit because rounding is applied
  independently. The joint solver occasionally returns an inaccurate continuous
  solution that the capacity-preserving rounding then clamps to feasibility.
