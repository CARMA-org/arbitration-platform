# Execution Path Map

This map describes the current platform-mediation path from agent declarations to
enforced execution.

## Runtime concepts and classes

| Concept | Class / member |
|---------|----------------|
| Runtime | `agent.RealisticAgentFramework.AgentRuntime` |
| Execution context | `agent.RealisticAgentFramework.ExecutionContext` |
| Active allocation | `model.AllocationSnapshot` (one immutable snapshot per epoch) |
| Per-agent contract | `model.AllocationContract` (id, version, bundle, issue/expiry) |
| Consumption ledger | `model.ConsumptionLedger` (shared per agent and contract version) |
| Service capacity handle | `model.ServiceHandle`; `ServiceRegistry.acquireHandle` |
| Utility declaration | `model.UtilityDeclaration` (linear, Cobb–Douglas, CES, Leontief) |
| Backend invocation | `ServiceBackend.invoke(serviceId, input)` on the acquired handle's service (mock or LLM) |
| Priority weighting | `PriorityEconomy.calculatePriorityWeight` |

## Allocation path

1. Agents are converted to arbitration-model `Agent`s with per-resource
   minimum/ideal requests and a `UtilityDeclaration`.
2. `ContentionDetector` groups agents that share contended resources.
3. For each group, `ConvexJointArbitrator.arbitrate` builds one solver input over
   the complete agent-by-resource matrix, including each agent's `utility_configs`,
   runs `scripts/joint_solver.py` under a hard timeout, and applies
   capacity-preserving integer rounding. It throws on failure unless fallback is
   explicitly enabled, in which case the result records the requested and actual
   policy.
4. `AgentRuntime.installContracts` checks aggregate conservation against pool
   capacity, assigns a monotonically increasing epoch, builds one contract and one
   fresh ledger per agent, and publishes the complete `AllocationSnapshot` by a
   single atomic reference replacement. Agents absent from the new snapshot are no
   longer executable.

Comparison policies (equal quotas, DRF, decomposed Cobb-Douglas) are computed by
`PlatformMediationHarness` and installed and executed through the same runtime.

## Enforcement path

`AgentRuntime.createExecutionContext` binds a context to an agent id, allocation
id, contract version, and the shared ledger. Before every service call the
context validates that the agent is still registered, the contract still exists,
the bound allocation id and version still match the active contract, and the
contract has not expired under the injected clock. On success it acquires a
handle for a specific service instance, charges that instance's configured
resource vector against the shared ledger all-or-nothing, invokes the backend on
that same service id, and releases the handle in all outcomes. A denied call
consumes nothing and never reaches the backend.

## Computation, authority, installation, enforcement

These are separate. Computation is which allocation matrix a policy produces:
the joint policies call the convex solver, while equal quotas, DRF, and
decomposed Cobb-Douglas compute their matrices directly, and the Cobb-Douglas
objective separates across resource columns so its computation needs no joint
solver. Authority is that only the platform installs contracts. Installation is
the atomic snapshot replacement under conservation. Enforcement is the gated,
ledger-charged execution above. That Cobb-Douglas separates the computation does
not decentralize authority, installation, or enforcement, which remain platform
operations for every policy.
