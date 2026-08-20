# Convex Solver Integration

The joint allocation policy solves one convex program over the full
agent-by-resource matrix using Python, cvxpy, and the Clarabel interior-point
solver.

## Path

`mechanism.ConvexJointArbitrator` serializes the contention group to JSON
(preferences, priority weights, capacities, per-cell minimums and ideals, and
per-agent `utility_configs`) and runs `scripts/joint_solver.py` as a subprocess.
Standard output and standard error are drained on separate threads to avoid a
pipe deadlock, and the process runs under a configurable hard timeout
(`setTimeoutMillis`); a process that exceeds the timeout is terminated and, if
necessary, forcibly terminated, and the caller receives an explicit timeout
failure.

The solver returns a continuous allocation; `ConvexJointArbitrator` converts it
to integers with bounded largest-remainder rounding applied per resource column,
which keeps every column within capacity and every cell within its bounds.

## Failure behaviour

The arbitrator fails closed: a solver error, malformed output, or timeout throws
unless `setUseFallbackOnError(true)` is set. When fallback is explicitly enabled,
the result records both the requested policy and the actual policy used.

## Utility families

`scripts/joint_solver.py` assembles a DCP-verified concave objective for linear,
Cobb–Douglas, CES, and Leontief utilities and rejects any other family rather
than substituting a linear surrogate. See `docs/MODEL_SUPPORT.md`.

## Dependencies

Python 3.10+, `cvxpy`, `clarabel`, `numpy`. Pinned versions are in
`docs/REPRODUCIBILITY.md`.
