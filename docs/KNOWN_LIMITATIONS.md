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

- The default Java runtime path (`ConvexJointArbitrator`) performs **continuous joint
  linear** optimization by shelling out to the Python solver; it does not send nonlinear
  utility configs. Nonlinear models are exercised through the Python solver directly and
  the experiment harness.
- `ConvexJointArbitrator` no longer falls back silently. Fallback to the per-resource
  sequential allocator must be explicitly enabled; when enabled the result message names
  both the requested and the actual model.
- The bundled JSON parsing in the Java caller is minimal and intended only for the
  solver's own output format.

## Rounding

- Integer conversion uses bounded largest-remainder rounding per resource column. It is
  a feasibility-preserving rule (never exceeds capacity or bounds); it is **not** a proof
  of discrete Pareto optimality. In near-degenerate columns the chosen integer point is
  one of several with equal welfare.

## Experiments

- Experiment scale is documented per run (`--smoke` vs full) in
  `EXPERIMENT_MANIFEST.json`. Results are seed-deterministic but reflect the specific
  instance generators in `experiments/joint_allocation/lib/generators.py`, not any
  external dataset.
- Comparator tuning (the single global `gamma`) is done on training seeds and evaluated
  on disjoint test seeds. The per-cell upper envelope over the `gamma` family is an
  oracle sensitivity bound, not an achievable rule.
