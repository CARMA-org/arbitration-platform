# Platform Evaluation

This document describes the canonical mediation path added on the
`platform-evaluation` branch and how to reproduce the platform evaluation. It
updates the description of how declarations and operator policy are turned into
enforceable multi-resource contracts and executed under contention.

## Canonical mediation path

The platform runs one mediation path end to end:

```
agent registration
  -> resource declarations (preferences, admitted minimums, per-agent upper bounds, priority tier)
  -> contention detection (connected components over shared, contended resources)
  -> joint allocation (one call per complete contention group)
  -> allocation installation (versioned AllocationContract, conservation-checked, atomic)
  -> constrained multi-resource service execution (full resource vector + service slot)
  -> accounting record (backend invocations, blocked calls, resources charged)
```

Key classes:

| Concern                    | Class / member |
|----------------------------|----------------|
| Runtime                    | `agent.RealisticAgentFramework.AgentRuntime` |
| Joint arbitration (canonical) | `mechanism.ConvexJointArbitrator` (convex solve via `scripts/joint_solver.py`) |
| Joint interface            | `mechanism.JointArbitrator` |
| Comparison policy (separable) | `mechanism.ProportionalFairnessArbitrator`, `mechanism.SequentialJointArbitrator` |
| Contract                   | `model.AllocationContract` (id, version/epoch, agent, bundle, issue/expiry, policy, solver status) |
| Constrained execution      | `AgentRuntime.ExecutionContext.invokeService` |
| Service capacity slot       | `model.ServiceRegistry.acquireSlot` / `releaseSlot` |

`AgentRuntime.runArbitration(detector, JointArbitrator)` calls the selected joint
arbitrator **once per complete contention group** over the whole resource set —
it does not loop over resource types with a single-resource allocator. The
canonical policy is `ConvexJointArbitrator`; the separable
(`ProportionalFairnessArbitrator` / `SequentialJointArbitrator`) allocators remain
available only as explicitly named comparison policies. Solver failure fails
closed (throws) unless fallback is explicitly enabled; an explicit fallback result
records both the requested and actual policy.

## Enforcement guarantees (mechanical)

`ExecutionContext.invokeService` atomically checks and consumes the complete
resource vector from `ServiceType.getDefaultResourceRequirements()` and acquires a
real service-capacity slot before the backend is invoked. If any resource or the
slot is unavailable it consumes nothing, does not invoke the backend, and returns
an explicit denial naming the exhausted resource. Over-quota and negative requests
never partially consume quota. Concurrent calls never collectively exceed the
agent bundle or the service capacity. Allocation installation is atomic,
conservation-checked, and rejects stale versions.

These are mechanical enforcement properties. They are **not** claims of
strategyproofness, truthful reporting, collusion resistance, or protection against
a malicious platform operator.

## Reproducing the evaluation

The joint solver needs Python with `cvxpy`, `clarabel`, and `numpy`:

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install cvxpy clarabel numpy matplotlib
export SOLVER_PYTHON=$(pwd)/.venv/bin/python
```

Build, run the tests, and produce the classpath used by the harness:

```bash
mvn -o clean test
mvn -o -q dependency:build-classpath -Dmdep.outputFile=cp.txt
```

Run the integrated demo (canonical path, ConvexJointArbitrator):

```bash
java -cp "target/classes:$(cat cp.txt)" org.carma.arbitration.demo.IntegratedArbitrationDemo
```

Smoke first, then full (full runs only after smoke invariants pass):

```bash
# Platform-mediation sweep
python experiments/platform_mediation/run_sweep.py --smoke
python experiments/platform_mediation/run_sweep.py --full
python experiments/platform_mediation/figures.py --mode full

# Dynamic allocation
python experiments/dynamic_allocation/run_dynamic.py --smoke
python experiments/dynamic_allocation/run_dynamic.py --full

# Enforcement fault injection
python experiments/enforcement/run_enforcement.py --smoke
python experiments/enforcement/run_enforcement.py --reps 100

# Test report + SHA-256 manifest
python experiments/platform_mediation/make_test_report.py
python experiments/platform_mediation/make_manifest.py
```

Results, tables, figures, logs, configuration copies, the machine-readable test
report, and the SHA-256 manifest are written under the respective experiment
directories. See `experiments/platform_mediation/RESULTS_FOR_PAPER.md` for the
findings, including the null and negative results and the individual-agent harms.
