# Arbitration Platform v0.3

**Platform-Mediated Pareto-Optimized Multi-Agent Interaction**

A complete implementation of Weighted Proportional Fairness for resource allocation among competing agents, with theoretical guarantees for Pareto optimality, collusion resistance, and individual rationality.

## Quick Start

### Prerequisites
- Java 21 or higher
- (Optional) Python 3.8+ with cvxpy, clarabel, numpy for exact optimization

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

## What's New in v0.3

### Bug Fixes

| Issue | Fix | Impact |
|-------|-----|--------|
| **Solver Identity Crisis** | Scenario 9 now USES Clarabel when available, not just checks availability | Guarantees exact Pareto optimality via interior-point method |
| **Economic Oscillation** | Added EMA smoothing (α=0.15) to demand multiplier | Prevents control theory oscillation in asymptotic simulation |
| **Hardcoded Earnings** | `AsymptoticSimulation` now calls `economy.calculateReleaseEarnings()` | Validates scarcity signaling feedback loop |
| **Water-Filling Division** | Added guards for degenerate cases + remainder distribution | Prevents NaN/infinity on edge cases |

### New Components

| Component | Description |
|-----------|-------------|
| **TransactionManager** | Atomic commit/rollback with explicit logging: `[TXN-START]`, `[TXN-COMMIT]`, `[TXN-ROLLBACK]` |
| **API_CREDITS ResourceType** | 6th resource type for diverse multi-resource scenarios |
| **Scenario 10** | "Jagged Optimization" test with 6 resources and overlapping agent clusters |

## Project Structure

```
arbitration-platform/
├── src/main/java/org/carma/arbitration/
│   ├── model/                    # Data models (7 files)
│   │   ├── Agent.java           
│   │   ├── ResourceType.java    # Now with 6 types including API_CREDITS
│   │   ├── ResourceBundle.java  
│   │   ├── ResourcePool.java    
│   │   ├── PreferenceFunction.java  
│   │   ├── Contention.java      
│   │   └── AllocationResult.java 
│   ├── mechanism/                # Core algorithms (10 files)
│   │   ├── ProportionalFairnessArbitrator.java  # Water-filling
│   │   ├── PriorityEconomy.java              # EMA-smoothed multipliers
│   │   ├── ContentionDetector.java           # Connected components
│   │   ├── EmbargoQueue.java                 # Request batching
│   │   ├── SafetyMonitor.java                # Invariant checking
│   │   ├── TransactionManager.java           # Atomic commit/rollback ✨NEW
│   │   ├── JointArbitrator.java              # Interface
│   │   ├── SequentialJointArbitrator.java    # Fallback
│   │   ├── GradientJointArbitrator.java      # Pure Java
│   │   └── ConvexJointArbitrator.java        # Python+Clarabel
│   ├── event/                    # Event system (2 files)
│   │   ├── Event.java           
│   │   └── EventBus.java        
│   ├── simulation/               # Testing (2 files)
│   │   ├── AsymptoticSimulation.java  
│   │   └── SimulationMetrics.java     
│   └── Demo.java                 # 10 validation scenarios
├── scripts/
│   └── joint_solver.py          # Python solver
├── docs/
│   └── CLARABEL_INTEGRATION.md  
├── pom.xml                       
├── run.sh                        
└── README.md                     
```

## Validation Scenarios

| # | Scenario | What It Tests |
|---|----------|---------------|
| 1 | Basic Mechanism | Weights affect allocation |
| 2 | Joint vs Separate | PF improves over naive proportional |
| 3 | Collusion Resistance | Victim protected despite 102:1 odds |
| 4 | Complementary Preferences | Specialists + balanced agents benefit |
| 5 | Priority Economy | Earning/burning dynamics |
| 6 | Individual Rationality | All agents ≥ outside option |
| 7 | Starvation Protection | Minnows survive whale attack |
| 8 | Asymptotic Behavior | 15s convergence test with EMA smoothing |
| 9 | Joint Optimization | "Paretotopia" thesis - cross-resource trades |
| 10 | Diverse Resources | 6 resources, 6 agents, overlapping clusters |

## Mathematical Foundation

### Objective Function (Single Resource)

```
maximize: Σᵢ cᵢ · log(aᵢ)

where:
  cᵢ = BaseWeight + CurrencyBurned_i  (priority weight)
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
  Φᵢ(A) = Σⱼ wᵢⱼ · aᵢⱼ  (weighted utility across ALL resources)

subject to:
  Σᵢ aᵢⱼ ≤ Qⱼ           ∀j  (resource capacity)
  aᵢⱼ ≥ minᵢⱼ           ∀i,j (minimum requirements)
  aᵢⱼ ≤ idealᵢⱼ         ∀i,j (maximum requests)
```

This enables **cross-resource trades** that sequential optimization cannot discover.

## EMA Smoothing for Stability

The demand multiplier now uses Exponential Moving Average to prevent oscillation:

```java
smoothed = α × current + (1-α) × previous
where α = 0.15
```

This dampens the control theory problem where:
- utilization spikes → multiplier spikes → agents release → 
- supply floods → multiplier crashes → agents stop releasing → repeat

## Transaction Manager

All joint allocations are wrapped in atomic transactions:

```
[TXN-START] TXN-00000001-12345 with 6 agents
[TXN-PREPARED] TXN-00000001-12345 - safety checks passed
[TXN-COMMIT] TXN-00000001-12345 - 36 allocations applied
```

On failure:
```
[TXN-START] TXN-00000002-12345 with 6 agents
[TXN-PREPARE-FAILED] TXN-00000002-12345 - Safety check failed: ...
[TXN-ROLLBACK] TXN-00000002-12345 - restoring previous state
```

## Enabling Clarabel (Recommended)

For exact joint optimization via interior-point method:

```bash
pip install cvxpy clarabel numpy

# Verify installation
python3 -c "import cvxpy; import clarabel; print('OK')"
```

Without Clarabel, the system uses pure Java gradient ascent which achieves
~97-99% of optimal welfare for typical problem sizes.

## Expected Output (Scenario 9)

```
SCENARIO 9: JOINT vs SEQUENTIAL OPTIMIZATION
────────────────────────────────────────────────────────────────

SEQUENTIAL OPTIMIZATION (per-resource):
  COMP: 39 compute, 24 storage (utility=37.50)
  STOR: 24 compute, 39 storage (utility=37.50)
  ...

JOINT OPTIMIZATION (Clarabel interior-point method):
  COMP: 46 compute, 20 storage (utility=43.40)
  STOR: 20 compute, 46 storage (utility=43.40)
  ...

════════════════════════════════════════════════════════════════
  Welfare improvement: 1.32%
  ✓ PASS: Joint optimization found cross-resource trades

  Solver used: Clarabel
  ✓ Interior-point method guarantees polynomial time and exact solution
════════════════════════════════════════════════════════════════
```

## Component Status

| Component | Status | Notes |
|-----------|--------|-------|
| Joint Optimization | ✅ Done | Uses Clarabel when available |
| EMA Smoothing | ✅ Done | α=0.15 dampens oscillations |
| Transaction Manager | ✅ Done | Atomic commit/rollback |
| Safety Monitor | ✅ Done | Centralized invariants |
| Embargo Queue | ✅ Done | Request batching |
| Contention Detector | ✅ Done | Connected components |
| 6-Resource Scenario | ✅ Done | Scenario 10 |

## License

MIT License - see LICENSE file for details.

## References

- Nash Bargaining Solution and Proportional Fairness (Kelly, 1997)
- Weighted Fair Queuing (Demers et al., 1989)
- Mechanism Design for Resource Allocation (Parkes, 2001)
- Clarabel: A modern convex solver (Goulart et al., 2023)
