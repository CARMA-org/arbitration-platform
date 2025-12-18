# Arbitration Platform v0.4

**Platform-Mediated Pareto-Optimized Multi-Agent Interaction**

A complete implementation of Weighted Proportional Fairness for resource allocation among competing agents, with theoretical guarantees for Pareto optimality, collusion resistance, and individual rationality.

## Quick Start

### Prerequisites
- Java (recent LTS) or higher
- (Optional) Python (modern) with cvxpy, clarabel, numpy for exact optimization

### Run the Demo

```bash
# Clone the repository
git clone https://github.com/CARMA-org/arbitration-platform.git
cd arbitration-platform

# Run validation scenarios only (fast)
./run.sh

# Run validation + asymptotic tests + joint optimization demos
./run.sh --full

# Run only the asymptotic test
./run.sh --asymptotic
```

Or compile and run directly:

```bash
# Compile
javac -d out $(find src/main/java -name "*.java")

# Run
java -cp out org.carma.arbitration.Demo --full
```

## What's New in this Release

### Bug Fixes

| Issue | Fix | Impact |
|-------|-----|--------|
| **Solver Identity Crisis** | Joint-optimization scenario now **uses** Clarabel when available, not just checks availability | Guarantees exact Pareto optimality via an interior-point method |
| **JSON Parsing Whitespace** | Fixed JSON parser to handle whitespace after colons (`"key": "value"`) | Clarabel results now correctly parsed as `"optimal"` |
| **Resource Ordering** | Resources now sorted by enum ordinal for deterministic ordering | Prevents allocation-to-resource mapping errors |
| **Economic Oscillation** | Added EMA smoothing (alpha equals fifteen hundredths) to demand multiplier | Prevents control theory oscillation in asymptotic simulation |
| **Hardcoded Earnings** | `AsymptoticSimulation` now calls `economy.calculateReleaseEarnings()` | Validates scarcity signaling feedback loop |
| **Water-Filling Division** | Added guards for degenerate cases + remainder distribution | Prevents NaN/infinity on edge cases |
| **Contradictory Messages** | Fixed `"NOT CONVERGED: System reached equilibrium"` message | Now correctly says `"System did not reach stable equilibrium"` |

### New Components

| Component | Description |
|-----------|-------------|
| **TransactionManager** | Atomic commit/rollback with explicit logging: `[TXN-START]`, `[TXN-COMMIT]`, `[TXN-ROLLBACK]` |
| **API_CREDITS ResourceType** | Additional resource type for diverse multi-resource scenarios |
| **Jagged Optimization scenario** | Stress test with multiple resources and overlapping agent clusters |

## Project Structure

```
arbitration-platform/
├── src/main/java/org/carma/arbitration/
│   ├── model/                    # Data models (seven files)
│   │   ├── Agent.java
│   │   ├── ResourceType.java    # Now includes API_CREDITS
│   │   ├── ResourceBundle.java
│   │   ├── ResourcePool.java
│   │   ├── PreferenceFunction.java
│   │   ├── Contention.java
│   │   └── AllocationResult.java
│   ├── mechanism/                # Core algorithms (ten files)
│   │   ├── ProportionalFairnessArbitrator.java  # Water-filling
│   │   ├── PriorityEconomy.java              # EMA-smoothed multipliers
│   │   ├── ContentionDetector.java           # Connected components
│   │   ├── EmbargoQueue.java                 # Request batching
│   │   ├── SafetyMonitor.java                # Invariant checking
│   │   ├── TransactionManager.java           # Atomic commit/rollback
│   │   ├── JointArbitrator.java              # Interface
│   │   ├── SequentialJointArbitrator.java    # Fallback
│   │   ├── GradientJointArbitrator.java      # Pure Java (about ninety-seven to ninety-nine percent optimal)
│   │   └── ConvexJointArbitrator.java        # Python + Clarabel (exact)
│   ├── event/                    # Event system (two files)
│   │   ├── Event.java
│   │   └── EventBus.java
│   ├── simulation/               # Testing (two files)
│   │   ├── AsymptoticSimulation.java
│   │   └── SimulationMetrics.java
│   └── Demo.java                 # Validation scenarios
├── scripts/
│   └── joint_solver.py          # Python solver (Clarabel + fallbacks)
├── docs/
│   └── CLARABEL_INTEGRATION.md
├── pom.xml
├── run.sh
└── README.md
```

## Validation Scenarios

| Scenario | What It Tests | Result |
|----------|---------------|--------|
| **One** | Basic Mechanism | Weights affect allocation — ✓ PASS |
| **Two** | Joint vs Separate | PF improves over naive proportional — ✓ PASS |
| **Three** | Collusion Resistance | Victim protected despite extreme odds — ✓ PASS |
| **Four** | Complementary Preferences | Specialists + balanced agents benefit — ✓ PASS |
| **Five** | Priority Economy | Earning/burning dynamics — ✓ PASS |
| **Six** | Individual Rationality | All agents meet or exceed outside option — ✓ PASS |
| **Seven** | Starvation Protection | Minnows survive whale attack — ✓ PASS |
| **Eight** | Asymptotic Behavior | Convergence test with EMA smoothing — ⚠ Expected |
| **Nine** | Joint Optimization | Cross-resource trades — ✓ PASS |
| **Ten** | Diverse Resources | Multiple resources, multiple agents, overlapping clusters — ✓ PASS |

## Mathematical Foundation

### Objective Function (Single Resource)

```
maximize: Σᵢ cᵢ · log(aᵢ)

where:
  cᵢ = BaseWeight + CurrencyBurnedᵢ  (priority weight)
  aᵢ = allocation to agent i

subject to:
  Σᵢ aᵢ ≤ Q        (resource limit)
  aᵢ ≥ minᵢ        (minimum requirements)
  aᵢ ≤ idealᵢ      (maximum requests)
```

### Joint Optimization (Multi-Resource)

```
maximize: Σᵢ cᵢ · log(Φᵢ(A))

where:
  Φᵢ(A) = Σⱼ wᵢⱼ · aᵢⱼ  (weighted utility across all resources)

subject to:
  Σᵢ aᵢⱼ ≤ Qⱼ           ∀j  (resource capacity)
  aᵢⱼ ≥ minᵢⱼ           ∀i,j (minimum requirements)
  aᵢⱼ ≤ idealᵢⱼ         ∀i,j (maximum requests)
```

This enables **cross-resource trades** that sequential optimization cannot discover.

## EMA Smoothing for Stability

The demand multiplier now uses Exponential Moving Average to prevent oscillation:

```java
smoothed = alpha × current + (one - alpha) × previous
where alpha equals fifteen hundredths
```

This dampens the control theory problem where:
- utilization spikes → multiplier spikes → agents release →
- supply floods → multiplier crashes → agents stop releasing → repeat

## Transaction Manager

All joint allocations are wrapped in atomic transactions:

```
[TXN-START] TXN-XXXXXXXX-YYYYY with multiple agents
[TXN-PREPARED] TXN-XXXXXXXX-YYYYY - safety checks passed
[TXN-COMMIT] TXN-XXXXXXXX-YYYYY - allocations applied
```

On failure:

```
[TXN-START] TXN-XXXXXXXX-YYYYY with multiple agents
[TXN-PREPARE-FAILED] TXN-XXXXXXXX-YYYYY - Safety check failed: ...
[TXN-ROLLBACK] TXN-XXXXXXXX-YYYYY - restoring previous state
```

## Enabling Clarabel (Recommended)

For exact joint optimization via an interior-point method:

```bash
pip install cvxpy clarabel numpy

# Verify installation
python -c "import cvxpy; import clarabel; print('OK')"
```

Without Clarabel, the system uses pure Java gradient ascent which achieves
about ninety-seven to ninety-nine percent of optimal welfare for typical problem sizes.

## Expected Output (Joint Optimization Scenario)

```
JOINT vs SEQUENTIAL OPTIMIZATION
────────────────────────────────────────────────────────────────

SEQUENTIAL OPTIMIZATION (per-resource):
  COMP: several compute, several storage (utility=...)
  STOR: several compute, several storage (utility=...)
  BAL:  several compute, several storage (utility=...)
  Total welfare: ...

JOINT OPTIMIZATION (Clarabel interior-point method):
  COMP: more compute, less storage (utility=...)
  STOR: less compute, more storage (utility=...)
  BAL:  balanced allocations (utility=...)
  Total welfare: ...

════════════════════════════════════════════════════════════════
  Welfare improvement: ... percent
  ✓ PASS: Joint optimization found cross-resource trades

  Solver used: Clarabel
  ✓ Interior-point method guarantees a globally optimal solution for this convex program
════════════════════════════════════════════════════════════════
```

## Component Status

| Component | Status | Notes |
|-----------|--------|-------|
| Joint Optimization | ✅ Done | Clarabel working with measurable welfare gain |
| EMA Smoothing | ✅ Done | Alpha dampens oscillations |
| Transaction Manager | ✅ Done | Atomic commit/rollback |
| Safety Monitor | ✅ Done | Centralized invariants |
| Embargo Queue | ✅ Done | Request batching |
| Contention Detector | ✅ Done | Connected components |
| Diverse-Resource Scenario | ✅ Done | Scenario includes multiple resources and overlapping clusters |

## License

MIT License — see LICENSE file for details.

## References

- Nash Bargaining Solution and Proportional Fairness (Kelly)
- Weighted Fair Queuing (Demers et al.)
- Mechanism Design for Resource Allocation (Parkes)
- Clarabel: A modern convex solver (Goulart et al.)
