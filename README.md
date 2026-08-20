# Arbitration Platform v0.9

**Platform-Mediated Multi-Agent Resource Arbitration**

A research prototype in which a platform mediates access to a shared, bounded
resource pool among competing agents. Agents declare resource demands and a
utility representation; an operator selects an allocation policy; the platform
computes an allocation, installs it as versioned multi-resource contracts, and
enforces those contracts across the allocation epoch, including concurrent and
repeated execution. The prototype is used to study how the choice of utility
representation affects the amount of completed bundle-structured work.

## Supported scope

- Bounded multi-resource pool (compute, memory, API credits, dataset units).
- Per-agent utility declarations: linear, Cobb–Douglas, CES, and Leontief.
- Joint allocation over the full agent-by-resource matrix via a convex program
  (Python/cvxpy/Clarabel), plus comparison rules: equal quotas, Dominant
  Resource Fairness, and a tuned separable water-filling family.
- Versioned allocation contracts with a shared per-contract consumption ledger,
  enforced by the runtime under a monotonic epoch and an injectable clock.
- Controlled synthetic experiments comparing utility representations and
  operational contract behaviour under dynamics and fault injection.

## Architecture

- `model/` — resource types, `AIService`, `ServiceRegistry`, `Agent`,
  `UtilityDeclaration`, `AllocationContract`, `ConsumptionLedger`,
  `AllocationSnapshot`, `ServiceHandle`.
- `agent/RealisticAgentFramework` — the `AgentRuntime` and `ExecutionContext`.
  The runtime holds one immutable `AllocationSnapshot`, published by a single
  atomic reference replacement under an installation lock. Each execution context
  is bound to an agent, an allocation id, a contract version, and the shared
  ledger for that version; every service call is gated on the active contract.
- `mechanism/ConvexJointArbitrator` — builds the solver input, runs
  `scripts/joint_solver.py` under a hard timeout draining stdout and stderr, and
  applies capacity-preserving integer rounding. It fails closed unless fallback
  is explicitly enabled.
- `scripts/joint_solver.py` — the convex solver for the four utility families.
- `experiment/` — the `PlatformMediationHarness`, `TaskAgent`, and
  `EnforcementFaultInjection` entry points used by the experiments.

## Installation

Requirements: Java 21+, Maven 3.6+, and Python 3.10+ with `cvxpy`, `clarabel`,
`numpy`, `scipy`, `pandas` for the solver and experiments. Pinned versions are
listed in `docs/REPRODUCIBILITY.md`.

```bash
mvn -q compile
python3 -m venv .venv && . .venv/bin/activate
pip install -r experiments/joint_allocation/requirements.txt
```

## Canonical demo

```bash
mvn -q exec:java -Dexec.mainClass=org.carma.arbitration.demo.IntegratedArbitrationDemo
```

This registers agents, runs joint arbitration through the runtime, installs
versioned contracts, and executes each agent through the enforced path.

## Tests

```bash
export SOLVER_PYTHON="$PWD/.venv/bin/python3"   # a Python with cvxpy/clarabel
mvn -o test                                     # Java suite
$SOLVER_PYTHON -m pytest tests/python -q         # Python solver suite
```

The solver-dependent Java tests require `SOLVER_PYTHON` to point at an
interpreter with cvxpy/clarabel installed; otherwise they are skipped.

## Experiment reproduction

```bash
mvn -o -q dependency:build-classpath -Dmdep.outputFile=cp.txt
cd experiments/platform_mediation && python3 run_sweep.py --full
cd ../enforcement && python3 run_enforcement.py --reps 100
cd ../dynamic_allocation && python3 run_dynamic.py --full
```

See `docs/EXPERIMENTS.md` for the design and `docs/REPRODUCIBILITY.md` for the
environment and commands. Results and figures are written under each
experiment's `results/`, `tables/`, and `figures/` directories.

## Supported utility families

| Family | Declaration | Solver term |
|--------|-------------|-------------|
| Linear | normalized resource weights | `Σ_j β_j a_j` |
| Cobb–Douglas | normalized weights summing to 1 | `Π_j a_j^{β_j}` |
| CES | weights and elasticity `ρ ≤ 1`, `ρ ≠ 0` | `(Σ_j β_j a_j^{ρ})^{1/ρ}` |
| Leontief | requirement vector `r_j ≥ 0`, at least one positive | `min_{j: r_j>0} a_j / r_j` |

Unsupported family names are rejected at validation rather than replaced by a
linear surrogate. See `docs/MODEL_SUPPORT.md`.

## Known limitations

See `docs/KNOWN_LIMITATIONS.md`. In brief: task execution and latency are
synthetic; the enforcement invariants are properties observed in deterministic
tests, not estimates of real-world failure rates; the runtime enforces
contracts against agents that call the provided execution path and does not
model strategic reporting, collusion, a hostile operator, or sandboxing of
untrusted agent code.

## License status

No license is currently declared. Until a `LICENSE` file is added, no license is
granted.

## References

- Kelly, Maulloo, Tan. Rate control for communication networks (1998).
- Ghodsi et al. Dominant Resource Fairness (2011).
- Parkes. Iterative Combinatorial Auctions (2001).
- Goulart, Chen. Clarabel: an interior-point solver for conic programs (2023).
