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
| Historical single-resource allocators | `mechanism.ProportionalFairnessArbitrator`, `mechanism.SequentialJointArbitrator` (not used by the current primary experiment) |
| Contract                   | `model.AllocationContract` (id, version/epoch, agent, bundle, issue/expiry, policy, solver status) |
| Constrained execution      | `AgentRuntime.ExecutionContext.invokeService` |
| Service instance handle    | `model.ServiceRegistry.acquireHandle` returns a `model.ServiceHandle`; `ServiceHandle.release` frees it |

`AgentRuntime.runArbitration(detector, JointArbitrator)` calls the selected joint
arbitrator **once per complete contention group** over the whole resource set —
it does not loop over resource types with a single-resource allocator. The
canonical joint policy is `ConvexJointArbitrator`. The current primary experiment
compares equal quotas, standard unweighted DRF, an exact decomposed Cobb-Douglas
allocator (per-resource bounded-log water-filling), and joint linear,
Cobb-Douglas, CES, and Leontief. The `ProportionalFairnessArbitrator` and
`SequentialJointArbitrator` are historical single-resource allocators that the
primary experiment does not use. Solver failure fails closed (throws) unless
fallback is explicitly enabled; an explicit fallback result records both the
requested and actual policy.

## Enforcement guarantees (mechanical)

`ExecutionContext.invokeService` acquires a handle for a specific service
instance (`ServiceRegistry.acquireHandle`), atomically checks and charges that
instance's configured resource vector against the shared per-version ledger, and
invokes the backend on that same service id only after the charge succeeds. It
checks agent registration, contract version, expiry, and context binding first.
If any resource or the instance is unavailable it consumes nothing, does not
invoke the backend, releases the handle, and returns an explicit denial naming the
exhausted resource. Over-quota and negative requests never partially consume
quota. Concurrent calls never collectively exceed the agent bundle or the service
capacity. Allocation installation is atomic, conservation-checked, and rejects
stale versions. Unsupported utility families are rejected rather than
approximated, and solver timeouts fail closed.

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

Run the primary sweep alone (no other solver-heavy work concurrent), then the
enforcement and dynamic experiments, then generate reports in order:

```bash
# 1. Primary sweep (run alone for clean latency)
python experiments/platform_mediation/run_sweep.py --full

# 2. Enforcement fault injection
python experiments/enforcement/run_enforcement.py --reps 100

# 3. Dynamic allocation (secondary solver-level simulation)
python experiments/dynamic_allocation/run_dynamic.py --full

# 4. Decomposition validation, headline, memo, figures
python experiments/platform_mediation/validate_decomposition.py
python experiments/platform_mediation/make_headline.py
python experiments/platform_mediation/make_memo.py
python experiments/platform_mediation/figures.py

# 5. Test report, then manifest last, then consistency check
python experiments/platform_mediation/make_test_report.py
python experiments/platform_mediation/make_manifest.py
python experiments/platform_mediation/check_consistency.py --with-manifest
```

Results, tables, figures, logs, configuration copies, the machine-readable test
report, and the SHA-256 manifest are written under the respective experiment
directories. See `experiments/platform_mediation/RESULTS_FOR_PAPER.md` for the
findings, including the null and negative results and the individual-agent harms.
