# Execution Path Map (verified from source, not README)

Starting point: `release/v0.9` @ `5f0fe8cf4ac450c393f9aaa7ceec4d5e84ac56ea`.
This map is the audit deliverable for the platform-mediation work. Every claim
below was read from source and cross-checked against the test suite.

## Named runtime concepts vs. actual classes

| Audit name              | Actual class / member                                                    |
|-------------------------|--------------------------------------------------------------------------|
| AgentRuntime            | `agent.RealisticAgentFramework.AgentRuntime`                             |
| ExecutionContext        | `agent.RealisticAgentFramework.ExecutionContext`                        |
| allocation storage      | `AgentRuntime.agentAllocations` (`Map<String,Map<ResourceType,Long>>`)  |
| service-slot allocation | `AIService.reserveCapacity/releaseCapacity`, `ServiceRegistry.*`         |
| resource consumption    | `ExecutionContext.tryConsumeResource` / `.invokeService`                 |
| backend invocation      | `ServiceBackend.invokeByType` (Mock/LLM)                                 |
| priority burns          | `PriorityEconomy.calculatePriorityWeight` (currency commitments)         |
| transaction handling    | `mechanism.TransactionManager`, `EmbargoQueue` (not on the exec path)    |
| allocation release      | `AgentRuntime.clearAllocations`; `ResourcePool.release`                  |

## Arbitration paths

1. **Per-resource, single-resource water-filling (NOT joint despite naming):**
   - `AgentRuntime.runArbitration(detector, ProportionalFairnessArbitrator)`:
     for each contention group, loops `for type in group.getResources()` and
     builds a single-resource `Contention` solved by `ProportionalFairnessArbitrator`
     (water-filling `max Σ cᵢ log aᵢ` per resource). No cross-resource coupling.
     Used by `IntegratedArbitrationDemo`.
   - `ServiceArbitrator.arbitrate(requests)` → `arbitrateServiceType(type,…)` per
     type, each via `ProportionalFairnessArbitrator`. Per-resource.
   - `ServiceArbitrator.arbitrateJoint(...)` hardcodes `new SequentialJointArbitrator(economy)`,
     which itself iterates resources independently. So even the method named
     "joint" is per-resource in v0.9.

2. **Genuine joint (global `max Σ cᵢ log Φᵢ(a_i)` over the full N×M problem):**
   - `ConvexJointArbitrator.arbitrate(group|agents,pool, burns)` → JSON of the full
     matrix → `scripts/joint_solver.py` (cvxpy/Clarabel) → capacity-preserving
     rounding. Fails closed (throws) unless `setUseFallbackOnError(true)`; the
     explicit fallback result is tagged `requested=JOINT_LINEAR,actual=SEQUENTIAL`.
   - In v0.9 this class is only exercised by `JointSolverIntegrationTest` and the
     Python `experiments/joint_allocation` harness (which calls `joint_solver`
     directly, not through the Java runtime).

## Service execution / consumption (v0.9 defects, pre-change)

`ExecutionContext.invokeService(type,input)`:
- checks `hasService(type)` (slot count > 0) but **does not acquire a real
  `ServiceRegistry` capacity slot** before invoking the backend;
- consumes **only `API_CREDITS`**, ignoring the `COMPUTE/MEMORY/DATASET` entries of
  `ServiceType.getDefaultResourceRequirements()`;
- `tryConsumeResource` on an over-quota amount sets `consumed = allocated`
  (**partial deduction of the remainder**) then returns `false`;
- backend picks "best available" `AIService` but never reserves/releases it, so
  **concurrent calls can exceed service capacity**.

Correct in v0.9: negative amounts already rejected without state change
(`tryConsumeResource`/`canConsumeResource`); `ResourcePool.allocate/release`
reject negatives.

## Contention detection

`ContentionDetector.detectContentions` → Union-Find connected components; a group
`requiresJointOptimization()` iff `resources > 1 && agents > 1`.

## Canonical path introduced by this work

`AgentRuntime.runArbitration(detector, JointArbitrator)` calls the selected joint
arbitrator **once per complete contention group**, validates conservation, then
installs versioned `AllocationContract`s atomically. `ExecutionContext.invokeService`
atomically checks+consumes the full resource vector and acquires/releases a real
service-capacity slot, failing closed on any shortfall. `ConvexJointArbitrator` is
the canonical policy; the `ProportionalFairnessArbitrator` overload and
`SequentialJointArbitrator` remain available only as explicitly named comparison
policies.
