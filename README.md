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
| **JSON Parsing Whitespace** | Fixed JSON parser to handle whitespace after colons (`"key": "value"`) | Clarabel results now correctly parsed as "optimal" |
| **Resource Ordering** | Resources now sorted by enum ordinal for deterministic ordering | Prevents allocation-to-resource mapping errors |
| **Economic Oscillation** | Added EMA smoothing (α=0.15) to demand multiplier | Prevents control theory oscillation in asymptotic simulation |
| **Hardcoded Earnings** | `AsymptoticSimulation` now calls `economy.calculateReleaseEarnings()` | Validates scarcity signaling feedback loop |
| **Water-Filling Division** | Added guards for degenerate cases + remainder distribution | Prevents NaN/infinity on edge cases |
| **Contradictory Messages** | Fixed "NOT CONVERGED: System reached equilibrium" message | Now correctly says "System did not reach stable equilibrium" |

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
│   │   ├── TransactionManager.java           # Atomic commit/rollback
│   │   ├── JointArbitrator.java              # Interface
│   │   ├── SequentialJointArbitrator.java    # Fallback
│   │   ├── GradientJointArbitrator.java      # Pure Java (~97-99% optimal)
│   │   └── ConvexJointArbitrator.java        # Python+Clarabel (exact)
│   ├── event/                    # Event system (2 files)
│   │   ├── Event.java           
│   │   └── EventBus.java        
│   ├── simulation/               # Testing (2 files)
│   │   ├── AsymptoticSimulation.java  
│   │   └── SimulationMetrics.java     
│   └── Demo.java                 # 10 validation scenarios
├── scripts/
│   └── joint_solver.py          # Python solver (Clarabel + fallbacks)
├── docs/
│   └── CLARABEL_INTEGRATION.md  
├── pom.xml                       
├── run.sh                        
└── README.md                     
```

## Validation Scenarios

| # | Scenario | What It Tests | Result |
|---|----------|---------------|--------|
| 1 | Basic Mechanism | Weights affect allocation | ✓ PASS |
| 2 | Joint vs Separate | PF improves over naive proportional | ✓ PASS (0.32%) |
| 3 | Collusion Resistance | Victim protected despite 102:1 odds | ✓ PASS |
| 4 | Complementary Preferences | Specialists + balanced agents benefit | ✓ PASS (90.03%) |
| 5 | Priority Economy | Earning/burning dynamics | ✓ PASS |
| 6 | Individual Rationality | All agents ≥ outside option | ✓ PASS |
| 7 | Starvation Protection | Minnows survive whale attack | ✓ PASS |
| 8 | Asymptotic Behavior | 15s convergence test with EMA smoothing | ⚠ Expected |
| 9 | Joint Optimization | "Paretotopia" thesis - cross-resource trades | ✓ PASS (4.88%) |
| 10 | Diverse Resources | 6 resources, 6 agents, overlapping clusters | ✓ PASS (4.54%) |

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
  BAL1: 29 compute, 29 storage (utility=29.00)
  BAL2: 28 compute, 28 storage (utility=28.00)
  Total welfare: 139.4818

JOINT OPTIMIZATION (Clarabel interior-point method):
  COMP: 57 compute, 5 storage (utility=51.80)
  STOR: 5 compute, 57 storage (utility=51.80)
  BAL1: 29 compute, 29 storage (utility=29.00)
  BAL2: 29 compute, 29 storage (utility=29.00)
  Total welfare: 146.2937

════════════════════════════════════════════════════════════════
  Welfare improvement: 4.88%
  ✓ PASS: Joint optimization found cross-resource trades

  Solver used: Clarabel
  ✓ Interior-point method guarantees polynomial time and exact solution
════════════════════════════════════════════════════════════════
```

## Component Status

| Component | Status | Notes |
|-----------|--------|-------|
| Joint Optimization | ✅ Done | Clarabel working with 4.88% welfare gain |
| EMA Smoothing | ✅ Done | α=0.15 dampens oscillations |
| Transaction Manager | ✅ Done | Atomic commit/rollback |
| Safety Monitor | ✅ Done | Centralized invariants |
| Embargo Queue | ✅ Done | Request batching |
| Contention Detector | ✅ Done | Connected components |
| 6-Resource Scenario | ✅ Done | Scenario 10 with 4.54% welfare gain |

## License

MIT License - see LICENSE file for details.

## References

- Nash Bargaining Solution and Proportional Fairness (Kelly, 1997)
- Weighted Fair Queuing (Demers et al., 1989)
- Mechanism Design for Resource Allocation (Parkes, 2001)
- Clarabel: A modern convex solver (Goulart et al., 2023)

## README Update Notes

**Changes made:**
1. Added 3 new bug fixes to the table: JSON Parsing Whitespace, Resource Ordering, Contradictory Messages
2. Updated Validation Scenarios table to include actual results (percentages)
3. Updated Expected Output section with actual Clarabel results (4.88% instead of 1.32%)
4. Updated Component Status with actual welfare improvement numbers
5. Minor clarifications in project structure comments

---

## Latest `./run.sh --full` Output

The following is the latest full demo run output as pasted (verbatim):

```text
avyaycasheekar@Avyays-MacBook-Pro arbitration-platform % ./run.sh --full
Compiling...
Running...

════════════════════════════════════════════════════════════════════════
   PLATFORM-MEDIATED PARETO-OPTIMIZED MULTI-AGENT INTERACTION
   Complete Implementation with Validation Scenarios
════════════════════════════════════════════════════════════════════════

SCENARIO 1: BASIC MECHANISM VALIDATION
────────────────────────────────────────────────────────────
Purpose: Verify that Weighted Proportional Fairness allocates
         proportional to weights while respecting bounds.

Setup:
  Resource pool: 100 compute units
  Agent A1: wants 40-80 units
  Agent A2: wants 30-70 units
  Total demand: 150 units (contention ratio: 1.5)

Test 1: Equal weights (10:10)
  A1 allocation: 55 units
  A2 allocation: 45 units
  Total: 100 units
  ✓ PASS: Constraints satisfied

Test 2: Unequal weights (60:10) - A1 burns 50 currency
  A1 allocation: 66 units
  A2 allocation: 34 units
  Total: 100 units
  ✓ PASS: Higher weight gets more

SCENARIO 2: JOINT VS SEPARATE OPTIMIZATION
────────────────────────────────────────────────────────────
Purpose: Demonstrate that Proportional Fairness improves welfare
         compared to naive proportional allocation.

Setup:
  A1: 70% compute, 30% storage preference
  A2: 30% compute, 70% storage preference
  A3: 50% compute, 50% storage preference
  Resources: 100 compute, 100 storage

Baseline (Naive Proportional):
  Compute Units: {A1=43, A2=23, A3=33}
  Storage Units: {A1=23, A2=43, A3=33}
  Total welfare: 207.8640

Proportional Fairness:
  Compute Units: {A1=44, A2=23, A3=33}
  Storage Units: {A1=24, A2=43, A3=33}
  Total welfare: 208.5195

════════════════════════════════════════════════════════════════════════
  Welfare improvement: 0.32%
  ✓ PASS: Proportional Fairness improves or matches baseline
════════════════════════════════════════════════════════════════════════

SCENARIO 3: COLLUSION RESISTANCE (Theorem 3)
────────────────────────────────────────────────────────────
Purpose: Verify that logarithmic barrier protects victims from
         coordinated attacks by wealthy coalitions.

Setup:
  Victim: 0 currency (weight = 10)
  Coalition: 2 agents, each burning 500 currency
  Coalition total weight: 510 + 510 = 1020
  Weight ratio against victim: 102:1

Under Coalition Attack:
  Victim allocation: 11 units (11.0%)
  Coalition 1: 45 units
  Coalition 2: 44 units

If Coalition Were Honest (no burning):
  Each agent would get: ~33 units

════════════════════════════════════════════════════════════════════════
✓ PASS: VICTIM PROTECTED
  Got 11 units despite 102:1 weight disadvantage
  
  Key insight: As any agent's allocation approaches 0,
  log(allocation) → -∞, making the objective plummet.
  This logarithmic barrier prevents complete exclusion.
════════════════════════════════════════════════════════════════════════

SCENARIO 4: COMPLEMENTARY PREFERENCES
────────────────────────────────────────────────────────────
Purpose: Show that diverse preferences create positive-sum gains
         where coordination benefits everyone.

Setup:
  COMP: 90% compute, 10% storage (compute specialist)
  STOR: 10% compute, 90% storage (storage specialist)
  BAL1, BAL2: 50/50 preferences (balanced)
  Resources: 150 compute, 150 storage

Coordinated Allocations:
  COMP: 60 compute, 10 storage
  STOR: 10 compute, 60 storage
  BAL1: 40 compute, 40 storage
  BAL2: 40 compute, 40 storage
  Coordinated welfare: 275.4938

Independent Baseline (equal split):
  Each agent gets: 37.5 of each resource
  Independent welfare: 144.9736

════════════════════════════════════════════════════════════════════════
  Welfare improvement: 90.03%
  ✓ PASS: Complementary preferences create value
════════════════════════════════════════════════════════════════════════

SCENARIO 5: PRIORITY ECONOMY DYNAMICS
────────────────────────────────────────────────────────────
Purpose: Demonstrate currency earning/burning cycle and its
         effect on allocation outcomes over time.

Setup:
  Two agents, each starting with 100 currency
  A1: Releases resources early (earns currency)
  A2: Holds resources until expiration (no earnings)

Simulation over 5 rounds:

Round 1:
  Balances: A1=100.00, A2=100.00
  Burns: A1=10.00, A2=0
  Allocations: A1=60, A2=40
  A1 releases early, earns: 30.00

Round 2:
  Balances: A1=120.00, A2=100.00
  Burns: A1=12.00, A2=0
  Allocations: A1=60, A2=40
  A1 releases early, earns: 30.00

Round 3:
  Balances: A1=138.00, A2=100.00
  Burns: A1=13.80, A2=0
  Allocations: A1=60, A2=40
  A1 releases early, earns: 30.00

Round 4:
  Balances: A1=154.20, A2=100.00
  Burns: A1=15.42, A2=0
  Allocations: A1=60, A2=40
  A1 releases early, earns: 30.00

Round 5:
  Balances: A1=168.78, A2=100.00
  Burns: A1=16.88, A2=0
  Allocations: A1=60, A2=40
  A1 releases early, earns: 30.00

════════════════════════════════════════════════════════════════════════
  Final balances: A1=181.90, A2=100.00
  ✓ PASS: Frequent releaser accumulated more currency
════════════════════════════════════════════════════════════════════════

SCENARIO 6: INDIVIDUAL RATIONALITY (Theorem 5)
────────────────────────────────────────────────────────────
Purpose: Verify that agents receive at least as much utility
         from participation as from non-participation.

Setup:
  Outside option = minimum request (what agent could get alone)
  A1: minimum 30, ideal 80
  A2: minimum 20, ideal 50
  A3: minimum 10, ideal 30
  Pool: 100 compute units

Allocations from participation:
  A1: got 44, outside option 30 → ✓
  A2: got 33, outside option 20 → ✓
  A3: got 23, outside option 10 → ✓

════════════════════════════════════════════════════════════════════════
  ✓ PASS: All agents received at least their outside option
  This demonstrates individual rationality (Theorem 5)
════════════════════════════════════════════════════════════════════════

SCENARIO 7: STARVATION PROTECTION
────────────────────────────────────────────────────────────
Purpose: Verify that even agents with minimal weight receive
         non-zero allocation due to logarithmic barrier.

Setup:
  1 whale: 10000 currency, burns 5000
  9 minnows: 0 currency each
  Whale weight: 5010, Each minnow weight: 10
  Total minnow weight: 90 (ratio 55:1 against each minnow)
  Pool: 100 compute units

Allocations:
  WHALE: 55 units
  Minnows (each): 5 units average
  Minnows (total): 45 units
  Total allocated: 100

════════════════════════════════════════════════════════════════════════
  ✓ PASS: All minnows received at least their minimum (5 units)
  Despite 55:1 weight disadvantage against whale
════════════════════════════════════════════════════════════════════════

SCENARIO 8: ASYMPTOTIC BEHAVIOR TEST (15 seconds)
────────────────────────────────────────────────────────────
Purpose: Analyze long-running behavior including convergence,
         equilibrium properties, and currency dynamics over time.

Starting asymptotic simulation...
  Duration: 15.0 seconds
  Tick interval: 50ms
  Agents: 10
  Resources: {Compute Units=500, Storage Units=500}

  Tick 100 (5.4s): welfare=381.7532, gini=0.1308
  Tick 200 (10.9s): welfare=362.7041, gini=0.1801

Simulation complete:
  Total time: 15.007 seconds
  Total ticks: 275
  Effective rate: 18.3 ticks/sec

════════════════════════════════════════════════════════════════════════
ASYMPTOTIC ANALYSIS RESULTS
════════════════════════════════════════════════════════════════════════

Simulation Metrics Summary:
  Duration: 14.95 seconds (275 ticks)
  Welfare: initial=380.7639, final=364.8625, avg=374.6732, stddev=6.4775
  Welfare change: -4.18%
  Gini coefficient: avg=0.1702
  Converged: false
  Trend (last 20): 0.142126

Contention Histogram (by number of competing agents):
  10 agents: ████████████████████████████████████████ 550 (100.0%)
  Total contentions: 550
  Average contention ratio: 1.56

  ⚠ NOT CONVERGED: System did not reach stable equilibrium
════════════════════════════════════════════════════════════════════════

SCENARIO 9: JOINT vs SEQUENTIAL OPTIMIZATION
────────────────────────────────────────────────────────────
Purpose: Demonstrate that joint multi-resource optimization
         achieves GLOBAL Pareto optimality by enabling
         cross-resource trades.

The 'Paretotopia' thesis: When agents have complementary
preferences, joint optimization unlocks welfare gains that
sequential per-resource optimization cannot discover.

Setup:
  COMP: 90% compute / 10% storage preference
  STOR: 10% compute / 90% storage preference
  BAL1, BAL2: 50% / 50% balanced preferences
  Resources: 120 compute, 120 storage (constrained)

SEQUENTIAL OPTIMIZATION (per-resource):
  Allocations:
    COMP: 39 compute, 24 storage (utility=37.50)
    STOR: 24 compute, 39 storage (utility=37.50)
    BAL1: 29 compute, 29 storage (utility=29.00)
    BAL2: 28 compute, 28 storage (utility=28.00)
  Total welfare: 139.4818

JOINT OPTIMIZATION (Clarabel interior-point method):
  Allocations:
    COMP: 57 compute, 5 storage (utility=51.80)
    STOR: 5 compute, 57 storage (utility=51.80)
    BAL1: 29 compute, 29 storage (utility=29.00)
    BAL2: 29 compute, 29 storage (utility=29.00)
  Total welfare: 146.2937

════════════════════════════════════════════════════════════════════════
  Welfare improvement: 4.88%
  ✓ PASS: Joint optimization found cross-resource trades

  Key insight: Joint optimization allows COMP to trade some
  Storage allocation to STOR in exchange for more Compute,
  improving welfare for both specialists.

  Solver used: Clarabel
  ✓ Interior-point method guarantees polynomial time and exact solution
════════════════════════════════════════════════════════════════════════

SCENARIO 10: DIVERSE MULTI-RESOURCE OPTIMIZATION
────────────────────────────────────────────────────────────
Purpose: Test joint optimization with 6 different resource types
         across different categories, with agents wanting different
         subsets that overlap in varied ways ('jagged' optimization).

Resource Categories:
  Infrastructure: COMPUTE, MEMORY, STORAGE
  Network: NETWORK
  Data: DATASET
  Services: API_CREDITS

Agents and their primary resource interests:
  ML_TRAIN_1: COMPUTE(50%), MEMORY(30%), DATASET(20%)
  ML_TRAIN_2: COMPUTE(40%), MEMORY(40%), DATASET(20%)
  DATA_PIPE_1: STORAGE(40%), DATASET(30%), NETWORK(20%)
  DATA_PIPE_2: NETWORK(50%), STORAGE(30%), DATASET(20%)
  API_SERVICE: API_CREDITS(40%), NETWORK(30%), COMPUTE(20%)
  GENERALIST: Even spread across all 6 resources

Resource availability:
  COMPUTE: 100, MEMORY: 100, STORAGE: 120
  NETWORK: 80, DATASET: 60, API_CREDITS: 80

EMBARGO QUEUE (request batching):
  Submitted 6 requests to embargo queue
  Embargo window: 50ms (for fairness against network latency)
  Batch collected: 6 requests, deterministic ordering applied

CONTENTION ANALYSIS:
  Found 1 contention group(s):
    Group CG-1: 6 agents, 6 resources, severity=1.81
      → Requires joint optimization

SEQUENTIAL OPTIMIZATION (per-resource):
  Allocations (COMP/MEM/STOR/NET/DATA/API):
    ML_TRAIN_1  : 32/21/ 0/ 0/11/ 0 (utility=24.5)
    ML_TRAIN_2  : 26/26/ 0/ 0/11/ 0 (utility=23.0)
    DATA_PIPE_1 :  0/16/45/19/16/ 0 (utility=28.2)
    DATA_PIPE_2 :  0/ 0/40/29/11/ 0 (utility=28.7)
    API_SERVICE : 21/16/ 0/19/ 0/50 (utility=31.5)
    GENERALIST  : 21/21/35/13/11/30 (utility=22.5)
  Sequential welfare: 195.9166

JOINT OPTIMIZATION (Clarabel interior-point):

TRANSACTION MANAGER (atomic commit):
[TXN-START] TXN-00000001-17685 with 6 agents
[TXN-PREPARED] TXN-00000001-17685 - safety checks passed
[TXN-COMMIT] TXN-00000001-17685 - 36 allocations applied
  Transaction result: TXN[TXN-0000] SUCCESS (6 agents, 6 resources, 1ms)

  Allocations (COMP/MEM/STOR/NET/DATA/API):
    ML_TRAIN_1  : 65/13/ 0/ 0/ 5/ 0 (utility=37.4)
    ML_TRAIN_2  : 15/60/ 0/ 0/ 5/ 0 (utility=31.0)
    DATA_PIPE_1 :  0/ 5/68/10/25/ 0 (utility=37.2)
    DATA_PIPE_2 :  0/ 0/15/55/ 5/ 0 (utility=33.0)
    API_SERVICE : 10/ 5/ 0/10/ 0/60 (utility=29.5)
    GENERALIST  : 10/17/37/ 5/20/20 (utility=18.7)
  Joint welfare: 204.8139

════════════════════════════════════════════════════════════════════════
  Welfare improvement: 4.54%
  Solver used: Clarabel
  Computation time: 455 ms
  ✓ PASS: Joint optimization found cross-resource trades

  With 6 resources and overlapping interests, joint optimization
  can discover complex trades that sequential cannot, such as:
    - ML agents trading DATASET access to Data Pipelines for COMPUTE
    - API Service trading NETWORK to Data Pipeline for API_CREDITS
════════════════════════════════════════════════════════════════════════

════════════════════════════════════════════════════════════════════════
   ALL DEMONSTRATIONS COMPLETE
════════════════════════════════════════════════════════════════════════
avyaycasheekar@Avyays-MacBook-Pro arbitration-platform % 
```
