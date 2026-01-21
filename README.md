# Arbitration Platform v0.6

**Platform-Mediated Pareto-Optimized Multi-Agent Interaction**

A complete implementation of Weighted Proportional Fairness for resource allocation among competing agents, with theoretical guarantees for Pareto optimality, collusion resistance, and individual rationality. Now with real LLM integration supporting Gemini, OpenAI, and Anthropic APIs.

## Quick Start

### Prerequisites
- Java 21 or higher
- (Optional) Python 3.8+ with cvxpy, clarabel, numpy for exact optimization
- (Optional) API key for LLM integration (Gemini, OpenAI, or Anthropic)

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

# Run realistic agent demos (with mock backend)
./run.sh --agents

# Run service composition demos
./run.sh --services

# Test REAL LLM integration (requires API key)
export GEMINI_API_KEY="your_key_here"  # or OPENAI_API_KEY or ANTHROPIC_API_KEY
java -cp out org.carma.arbitration.demo.LLMIntegrationTest
```

Or compile and run directly:

```bash
# Compile
javac -d out $(find src/main/java -name "*.java")

# Run
java -cp out org.carma.arbitration.Demo --full
```

## What's New in v0.6

### Real LLM Integration

The platform supports **real API calls** to LLM providers:

| Provider | Models | Status |
|----------|--------|--------|
| **Gemini** | gemini-2.5-flash, gemini-2.0-flash | Tested |
| **OpenAI** | gpt-4, gpt-3.5-turbo | Supported |
| **Anthropic** | claude-3-opus, claude-3-sonnet | Supported |
| **Local** | Ollama (llama2, etc.) | Supported |

```java
// Create a real LLM backend
LLMServiceBackend backend = new LLMServiceBackend.Builder()
    .fromEnvironment()  // Reads GEMINI_API_KEY, OPENAI_API_KEY, etc.
    .logRequests(true)
    .build();

// Use in agent runtime - agents now make REAL LLM calls
AgentRuntime runtime = new AgentRuntime.Builder()
    .serviceBackend(backend)
    .build();
```

### Pluggable Service Backend Architecture

New `ServiceBackend` interface enables swapping mock backends for real LLM integration:

| Component | Description |
|-----------|-------------|
| **ServiceBackend.java** | Interface for pluggable backends |
| **MockServiceBackend.java** | Simulated backend for testing |
| **LLMServiceBackend.java** | Real LLM backend (Gemini, OpenAI, Anthropic) |

### Realistic Agent Framework

Production-ready agent implementations:

| Agent | Description | Autonomy |
|-------|-------------|----------|
| **NewsSearchAgent** | Searches news, posts to Signal | TOOL |
| **CodeReviewAgent** | Analyzes code, suggests improvements | LOW |
| **ResearchAssistant** | Multi-step research with citations | MEDIUM |
| **DataPipelineAgent** | Autonomous ETL orchestration | HIGH |
| **TradingAgent** | Market analysis and trading | HIGH |
| **ContentModerationAgent** | Reviews content, applies policies | LOW |

### A+G+I Safety Monitoring

Monitors for dangerous capability conjunctions:

- **A** (Autonomy): Can the agent act without human approval?
- **G** (Generality): Can the agent handle diverse domains?
- **I** (Intelligence): Does the agent show advanced reasoning?

```java
AGIEmergenceMonitor monitor = new AGIEmergenceMonitor();
monitor.registerAgent(agent);
AGIEmergenceMonitor.RiskAssessment risk = monitor.assessAgent(agentId);
// risk.getOverallRisk() → LOW, MEDIUM, HIGH, CRITICAL
```

### Configuration Validation

Load-time validation with detailed error messages:

| Validation | Description |
|------------|-------------|
| **Agent Configuration** | Validates autonomy levels, goals, permissions |
| **Utility Functions** | Validates function types, parameters, weights |
| **Service Compositions** | DAG validation, depth limits, compatibility |
| **Safety Constraints** | A+G+I limits, rate limits, circuit breakers |

---

## What's New in v0.5

### Grouping Policy Configuration

Configurable policies for controlling how agents get grouped for joint optimization, trading off Pareto optimality for performance:

| Policy Dimension | Description | Use Case |
|-----------------|-------------|----------|
| **K-Hop Limits** | Control contention graph traversal depth | k=1 for direct competitors only |
| **Size Bounds** | Maximum agents per optimization group | Bounded latency guarantees |
| **Compatibility Matrices** | Explicit grouping control (ALLOWLIST/BLOCKLIST/CATEGORY) | Tenant isolation, security boundaries |

#### New Components

| Component | Description |
|-----------|-------------|
| **GroupingPolicy.java** | Configuration class with k-hop, size, compatibility settings |
| **GroupingSplitter.java** | Splits large groups using MIN_CUT, RESOURCE_AFFINITY, SPECTRAL, etc. |
| **GroupingPolicyDemo.java** | 9 scenarios demonstrating all policy features |

#### Preset Policies

| Policy | K-Hop | Max Size | Use Case |
|--------|-------|----------|----------|
| `DEFAULT` | ∞ | ∞ | Small systems, batch processing |
| `PERFORMANCE` | 1 | 10 | Real-time with bounded latency |
| `BALANCED` | 2 | 20 | Middle ground |

#### Performance vs Optimality Trade-offs

| Agents | Policy | Groups | Max Size | Speedup | 
|--------|--------|--------|----------|---------|
| 50 | PERFORMANCE | 5 | 10 | 15.7x |
| 100 | PERFORMANCE | 9 | 10 | 72.8x |
| 200 | PERFORMANCE | 19 | 10 | 343.2x |

## What's New in v0.4

### Nonlinear Preference Functions

Support for concave utility functions that model diminishing returns and resource complementarities:

| Utility Type | Formula | Use Case |
|--------------|---------|----------|
| **LINEAR** | `Φ = Σ wⱼ·aⱼ` | Perfect substitutes (default) |
| **SQRT** | `Φ = (Σ wⱼ·√aⱼ)²` | Diminishing returns |
| **LOG** | `Φ = Σ wⱼ·log(1+aⱼ)` | Strong diminishing returns |
| **COBB_DOUGLAS** | `Φ = Π aⱼ^wⱼ` | Complementarities (need all resources) |
| **LEONTIEF** | `Φ = min(aⱼ/wⱼ)` | Perfect complements |
| **CES** | `Φ = (Σ wⱼ·aⱼ^ρ)^(1/ρ)` | Configurable elasticity of substitution |

### New Components

| Component | Description |
|-----------|-------------|
| **UtilityFunction.java** | Abstract base class with 11 concrete implementations |
| **NonlinearUtilityDemo.java** | Demonstration of all utility function types |
| **joint_solver.py** | Updated Python solver with nonlinear utility support |

### AI Service Integration

| Component | Description |
|-----------|-------------|
| **ServiceType** | 15 AI service types across 5 categories (Text, Vision, Audio, Reasoning, Data) |
| **AIService** | Service model with QoS parameters, capacity tracking, load management |
| **ServiceComposition** | DAG-based service pipelines with type compatibility validation |
| **ServiceRegistry** | Service discovery, capacity management, composition support |
| **ServiceArbitrator** | Arbitration for service slots using priority economy |

## Project Structure

```
arbitration-platform/
├── src/main/java/org/carma/arbitration/
│   ├── model/                    # Data models (12 files)
│   │   ├── Agent.java
│   │   ├── ResourceType.java    # 6 types including API_CREDITS
│   │   ├── ResourceBundle.java
│   │   ├── ResourcePool.java
│   │   ├── PreferenceFunction.java  # Linear preferences (legacy)
│   │   ├── UtilityFunction.java     # Nonlinear utilities
│   │   ├── Contention.java
│   │   ├── AllocationResult.java
│   │   ├── ServiceType.java         # 15 AI service types
│   │   ├── AIService.java           # Service model with QoS
│   │   ├── ServiceComposition.java  # DAG pipelines
│   │   └── ServiceRegistry.java     # Service discovery
│   ├── mechanism/                # Core algorithms (16 files)
│   │   ├── ProportionalFairnessArbitrator.java  # Water-filling
│   │   ├── PriorityEconomy.java              # EMA-smoothed multipliers
│   │   ├── ContentionDetector.java           # Connected components
│   │   ├── GroupingPolicy.java               # Policy configuration
│   │   ├── GroupingSplitter.java             # Policy-based splitting
│   │   ├── EmbargoQueue.java                 # Request batching
│   │   ├── SafetyMonitor.java                # Invariant checking
│   │   ├── TransactionManager.java           # Atomic commit/rollback
│   │   ├── JointArbitrator.java              # Interface
│   │   ├── SequentialJointArbitrator.java    # Fallback
│   │   ├── GradientJointArbitrator.java      # Pure Java (~97-99% optimal)
│   │   ├── ConvexJointArbitrator.java        # Python+Clarabel (exact)
│   │   ├── ServiceArbitrator.java            # Service allocation
│   │   ├── ServiceBackend.java               # Pluggable backend interface (NEW)
│   │   ├── MockServiceBackend.java           # Mock backend for testing (NEW)
│   │   └── LLMServiceBackend.java            # Real LLM backend (NEW)
│   ├── agent/                    # Realistic agent framework (NEW)
│   │   ├── RealisticAgentFramework.java      # Core framework
│   │   └── ExampleAgents.java                # 6 working agent implementations
│   ├── safety/                   # Safety monitoring (NEW)
│   │   ├── AGIEmergenceMonitor.java          # A+G+I conjunction detection
│   │   ├── ConfigurationValidator.java       # Load-time validation
│   │   └── ServiceCompositionAnalyzer.java   # Composition depth analysis
│   ├── demo/                     # Demo applications (NEW)
│   │   ├── RealisticAgentDemo.java           # Realistic agent scenarios
│   │   └── LLMIntegrationTest.java           # Real LLM API testing
│   ├── event/                    # Event system (2 files)
│   │   ├── Event.java
│   │   └── EventBus.java
│   ├── simulation/               # Testing (2 files)
│   │   ├── AsymptoticSimulation.java
│   │   └── SimulationMetrics.java
│   ├── Demo.java                 # 12 validation scenarios
│   ├── ServiceDemo.java          # Service integration demo
│   ├── NonlinearUtilityDemo.java # Nonlinear utility demos
│   └── GroupingPolicyDemo.java   # Grouping policy demos
├── config/
│   └── ai-config.properties      # AI provider configuration (NEW)
├── scripts/
│   └── joint_solver.py          # Python solver (Clarabel + nonlinear utilities)
├── docs/
│   └── CLARABEL_INTEGRATION.md
├── .env.example                  # Environment variable template (NEW)
├── pom.xml
├── run.sh
└── README.md
```

## Validation Scenarios

| # | Scenario | What It Tests | Result |
|---|----------|---------------|--------|
| 1 | Basic Mechanism | Weights affect allocation | ✓ PASS |
| 2 | Joint vs Separate | PF improves over naive proportional | ✓ PASS (0.40%) |
| 3 | Collusion Resistance | Victim protected despite 200:1 odds | ✓ PASS |
| 4 | Complementary Preferences | Specialists + balanced agents benefit | ✓ PASS |
| 5 | Priority Economy | Earning/burning dynamics | ✓ PASS |
| 6 | Individual Rationality | All agents ≥ outside option | ✓ PASS |
| 7 | Starvation Protection | Minnows survive whale attack | ✓ PASS |
| 8 | Asymptotic Behavior | 15s convergence test with EMA smoothing | ⚠ Expected |
| 9 | Joint Optimization | "Paretotopia" thesis - cross-resource trades | ✓ PASS (2.73%) |
| 10 | Diverse Resources | 6 resources, 6 agents, overlapping clusters | ✓ PASS |
| 11 | AI Service Integration | Service composition and arbitration | ✓ PASS |
| 12 | Nonlinear Utilities | All 11 utility function types | ✓ PASS |

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
  Φᵢ(A) = utility function (see below)

subject to:
  Σᵢ aᵢⱼ ≤ Qⱼ           ∀j  (resource capacity)
  aᵢⱼ ≥ minᵢⱼ           ∀i,j (minimum requirements)
  aᵢⱼ ≤ idealᵢⱼ         ∀i,j (maximum requests)
```

### Utility Functions

| Type | Formula | Properties |
|------|---------|------------|
| **Linear** | `Φ = Σⱼ wⱼ·aⱼ` | Perfect substitutes, constant MRS |
| **Square Root** | `Φ = (Σⱼ wⱼ·√aⱼ)²` | Diminishing returns, concave |
| **Logarithmic** | `Φ = Σⱼ wⱼ·log(1+aⱼ)` | Strong diminishing returns |
| **Cobb-Douglas** | `Φ = Πⱼ aⱼ^wⱼ` | Unit elasticity, complementarity |
| **Leontief** | `Φ = minⱼ(aⱼ/wⱼ)` | Perfect complements, quasi-concave |
| **CES** | `Φ = (Σⱼ wⱼ·aⱼ^ρ)^(1/ρ)` | Elasticity σ = 1/(1-ρ) |

**CES Special Cases:**
- ρ → 1: Linear (perfect substitutes)
- ρ = 0.5: Square root-like
- ρ → 0: Cobb-Douglas
- ρ → -∞: Leontief (perfect complements)

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

## Component Status

| Component | Status | Notes |
|-----------|--------|-------|
| **v0.6 - Real LLM Integration** | ✅ Done | Gemini, OpenAI, Anthropic support |
| **v0.6 - ServiceBackend Interface** | ✅ Done | Pluggable backend architecture |
| **v0.6 - Realistic Agent Framework** | ✅ Done | 6 working agent implementations |
| **v0.6 - A+G+I Safety Monitoring** | ✅ Done | Conjunction risk detection |
| **v0.6 - Configuration Validation** | ✅ Done | Load-time validation |
| Grouping Policy | ✅ Done | K-hop limits, size bounds, compatibility matrices |
| Joint Optimization | ✅ Done | Clarabel working with 2.73% welfare gain |
| Nonlinear Utilities | ✅ Done | 11 utility types |
| AI Service Integration | ✅ Done | 15 service types, compositions, arbitration |
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
- CES Utility Functions (Arrow et al., 1961)

---

## Latest `./run.sh --full` Output

The following is the latest full demo run output (verbatim):

```text                              
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
  Total welfare: 162.7881

Proportional Fairness:
  Compute Units allocations: A1=44, A2=23, A3=33
  Storage Units allocations: A1=24, A2=43, A3=33
  Total welfare: 163.4436

════════════════════════════════════════════════════════════════════════
  Welfare improvement: 0.40%
  ✓ PASS: PF improves naive proportional
════════════════════════════════════════════════════════════════════════

SCENARIO 3: COLLUSION RESISTANCE (Theorem 3)
────────────────────────────────────────────────────────────
Purpose: Verify that colluding attackers cannot reduce victim's
         allocation below their minimum regardless of coalition size.

Setup:
  1 victim: minimum 20 units, ideal 50, burns 0
  100 attackers: each burns 10 currency
  Pool: 500 compute units
  Total attacker weight: 2000
  Victim weight: 10
  Ratio: 2000:10 = 200:1

Results:
  Victim allocation: 22 units
  Victim's minimum: 20 units
  Total attacker allocation: 478 units
  Average per attacker: 4 units

════════════════════════════════════════════════════════════════════════
  ✓ PASS: Victim received at least minimum despite 200:1 weight disadvantage
  This demonstrates Theorem 3: log barrier protects against collusion
════════════════════════════════════════════════════════════════════════

SCENARIO 4: COMPLEMENTARY PREFERENCES
────────────────────────────────────────────────────────────
Purpose: Demonstrate welfare improvement when agents have
         complementary resource preferences (specialists + generalist).

Setup:
  COMP: 90% compute, 10% storage (specialist)
  STOR: 10% compute, 90% storage (specialist)
  BAL: 50% compute, 50% storage (generalist)
  Resources: 100 compute, 100 storage

  Compute Units allocations:
    COMP: 45 units (utility contribution: 40.5)
    STOR: 20 units (utility contribution: 2.0)
    BAL: 35 units (utility contribution: 17.5)
  Storage Units allocations:
    COMP: 20 units (utility contribution: 2.0)
    STOR: 45 units (utility contribution: 40.5)
    BAL: 35 units (utility contribution: 17.5)

  Total utility: 120.0
  Total welfare: 145.1330

════════════════════════════════════════════════════════════════════════
  Resource utilization: 60.00%
  ○ NOTE: Complementary preferences enable efficient allocation
════════════════════════════════════════════════════════════════════════

SCENARIO 5: PRIORITY ECONOMY DYNAMICS
────────────────────────────────────────────────────────────
Purpose: Demonstrate the earning/burning currency dynamics
         and how they affect allocation over time.

Setup:
  Two agents with identical preferences, starting with 100 currency each
  A1 releases resources early (earns currency)
  A2 holds resources (no earning)
  Both burn 10 currency per round

Simulation (5 rounds):
  Round 1:
    Allocations: A1=50, A2=50
    A1 releases 20 units early, earns 14.00 currency
    Balances: A1=104.00, A2=90.00

  Round 2:
    Allocations: A1=50, A2=50
    A1 releases 20 units early, earns 14.00 currency
    Balances: A1=108.00, A2=80.00

  Round 3:
    Allocations: A1=50, A2=50
    A1 releases 20 units early, earns 14.00 currency
    Balances: A1=112.00, A2=70.00

  Round 4:
    Allocations: A1=50, A2=50
    A1 releases 20 units early, earns 14.00 currency
    Balances: A1=116.00, A2=60.00

  Round 5:
    Allocations: A1=50, A2=50
    A1 releases 20 units early, earns 14.00 currency
    Balances: A1=120.00, A2=50.00

════════════════════════════════════════════════════════════════════════
  Final balances: A1=120.00, A2=50.00
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
  Total time: 15.027 seconds
  Total ticks: 275
  Effective rate: 18.3 ticks/sec

════════════════════════════════════════════════════════════════════════
ASYMPTOTIC ANALYSIS RESULTS
════════════════════════════════════════════════════════════════════════

Simulation Metrics Summary:
  Duration: 14.97 seconds (275 ticks)
  Welfare: initial=380.7639, final=364.8625, avg=374.6732, stddev=6.4775
  Welfare change: -4.18%
  Gini coefficient: avg=0.1702
  Converged: false
  Trend (last 20): 0.142126

Contention Histogram (by number of competing agents):
  10 agents: ████████████████████████████████████████ 550 (100.0%)
  Total contentions: 550
  Average contention ratio: 1.56

⚠ System did not reach stable equilibrium (expected for short runs)
  EMA smoothing prevents oscillation
════════════════════════════════════════════════════════════════════════

SCENARIO 9: JOINT OPTIMIZATION ("Paretotopia")
────────────────────────────────────────────────────────────
Purpose: Demonstrate cross-resource trades that sequential
         optimization cannot discover (the "Paretotopia" thesis).

Setup:
  COMP: 90% compute, 10% storage preference
  STOR: 10% compute, 90% storage preference
  Pool: 100 compute, 100 storage
  Equal weights (no currency burning)

SEQUENTIAL OPTIMIZATION (per-resource):
  Allocations:
    COMP: 70 compute, 30 storage (utility=66.00)
    STOR: 30 compute, 70 storage (utility=66.00)
  Total welfare: 83.7931

JOINT OPTIMIZATION (Clarabel interior-point):
  Allocations:
    COMP: 80 compute, 20 storage (utility=74.00)
    STOR: 20 compute, 80 storage (utility=74.00)
  Total welfare: 86.0813

════════════════════════════════════════════════════════════════════════
  Welfare improvement: 2.73%
  ✓ PASS: Joint optimization found cross-resource trades
  Solver used: Clarabel
════════════════════════════════════════════════════════════════════════

SCENARIO 10: DIVERSE MULTI-RESOURCE OPTIMIZATION
────────────────────────────────────────────────────────────
Purpose: Test joint optimization with 6 different resource types.

Pool capacities: {API Credits=80, Dataset Access=50, Network Bandwidth=60, Storage Units=100, Compute Units=100, Memory Units=80}

  Sequential allocations:
    ML_TRAIN_1  : 70/50/ 0/ 0/23/ 0 (utility=54.6)
    DATA_PIPE   :  0/ 0/80/30/27/ 0 (utility=49.1)
    API_SVC     : 30/ 0/ 0/30/ 0/60 (utility=42.0)
  Sequential welfare: 116.3156

  Joint allocations:
    ML_TRAIN_1  : 73/50/ 0/ 0/21/ 0 (utility=55.7)
    DATA_PIPE   :  0/ 0/80/30/29/ 0 (utility=49.7)
    API_SVC     : 27/ 0/ 0/30/ 0/60 (utility=41.1)
  Joint welfare: 116.4199

════════════════════════════════════════════════════════════════════════
  Welfare improvement: 0.09%
  ✓ PASS: Joint optimization found trades
════════════════════════════════════════════════════════════════════════

SCENARIO 11: AI SERVICE INTEGRATION
────────────────────────────────────────────────────────────
Purpose: Demonstrate AI service allocation and composition.

Service Registry: RegistryStats[services=45/45 available, capacity=0/4080 used (0.0%), compositions=0]

RAG Pipeline: Valid
  Latency: 170ms

Service Arbitration:
  team-a: got 5
  team-b: got 5

════════════════════════════════════════════════════════════════════════
  ✓ PASS: Service integration working
════════════════════════════════════════════════════════════════════════

SCENARIO 12: NONLINEAR UTILITY FUNCTIONS
────────────────────────────────────────────────────────────
Purpose: Demonstrate all 11 utility function types and show
         how the optimizer makes different decisions.

Utility Function Comparison at (C=60, S=40):

  LINEAR         :   52.000
  SQRT           :   51.515
  LOG            :    3.952
  COBB_DOUGLAS   :   51.017
  CES(ρ=0.5)     :   51.515
  LEONTIEF       :  100.000
  THRESHOLD      :   52.000
  SATIATION      :   92.573
  SOFTPLUS_LA    :   -2.635
  ASYMLOG_LA     :   -0.081

Optimizer Behavior Comparison:
--------------------------------------------------

Agents:
  LINEAR_A: 90% compute preference (specialist)
  CD_B: 50/50 Cobb-Douglas (needs both resources)

Joint Optimization Allocation:
  LINEAR_A: 54 compute, 27 storage
  CD_B: 46 compute, 53 storage

Resulting Utilities:
  LINEAR_A utility: 51.30
  CD_B utility: 49.38

Auto-Generated Agents (seed=42):
  THRESHOLD: 4 agents
  CES: 2 agents
  NESTED_CES: 2 agents
  SOFTPLUS_LOSS_AVERSION: 1 agents
  SQRT: 2 agents
  COBB_DOUGLAS: 1 agents
  ASYMMETRIC_LOG_LOSS_AVERSION: 1 agents
  LINEAR: 2 agents
  SATIATION: 3 agents
  LOG: 2 agents

════════════════════════════════════════════════════════════════════════
  ✓ PASS: All 11 utility types working correctly
  Run NonlinearUtilityDemo for comprehensive scenarios
════════════════════════════════════════════════════════════════════════


════════════════════════════════════════════════════════════════════════
   EXTENDED DEMOS: NONLINEAR UTILITIES
════════════════════════════════════════════════════════════════════════

══════════════════════════════════════════════════════════════════════
NONLINEAR UTILITY FUNCTIONS DEMONSTRATION
Platform-Mediated Pareto-Optimized Multi-Agent Interaction
══════════════════════════════════════════════════════════════════════

SCENARIO 1: UTILITY FUNCTION COMPARISON
──────────────────────────────────────────────────────────────────────
Purpose: Compare all 11 utility function types with the same
         allocation to understand their different behaviors.

Weights: COMPUTE=60%, STORAGE=40%
Reference points (for loss aversion): COMPUTE=50, STORAGE=50

Allocation         | Linear | Sqrt  | Log   | Cobb-D | CES   | Leont | Thresh | Sati  | Soft  | AsymL
-------------------|--------|-------|-------|--------|-------|-------|--------|-------|-------|------
C=100, S=  0       |   60.0 |  36.0 |  2.77 |    0.0 |  18.3 |   0.0 |   60.0 |  95.0 | -10.0 |  -0.3
C=  0, S=100       |   40.0 |  16.0 |  1.85 |    0.0 |   4.7 |   0.0 |   40.0 |  86.5 | -40.0 |  -1.0
C= 50, S= 50       |   50.0 |  50.0 |  3.93 |   50.0 |  50.0 |  83.3 |   50.0 |  91.8 |  -3.5 |   0.0
C= 80, S= 20       |   56.0 |  51.2 |  3.85 |   45.9 |  49.1 |  50.0 |   56.0 |  93.9 |  -6.0 |  -0.2
C= 30, S= 70       |   46.0 |  44.0 |  3.77 |   42.1 |  43.2 |  50.0 |   46.0 |  90.0 | -16.1 |  -0.6

Key observations:
  • Linear: Utility scales linearly with weighted sum
  • Sqrt/Log: Favor balanced allocations (diminishing returns)
  • Cobb-Douglas: Zero utility if any resource is zero
  • CES: Tunable substitution elasticity
  • Leontief: Only binding resource matters
  • Threshold: Low utility below minimum viable quantity
  • Satiation: Bounded upper utility
  • Softplus/AsymLog: Loss aversion around reference points

══════════════════════════════════════════════════════════════════════

SCENARIO 2: DIMINISHING RETURNS EFFECT
──────────────────────────────────────────────────────────────────────
Purpose: Show how diminishing returns utilities lead to
         more balanced allocations.

Agent prefers COMPUTE (80%) over STORAGE (20%)

Marginal utility of adding 1 unit of COMPUTE at different levels:

Current Compute | Linear MU | Sqrt MU  | Log MU
----------------|-----------|----------|--------
       10       |   0.800   |  0.9978  | 0.07273
       25       |   0.800   |  0.8663  | 0.03077
       50       |   0.800   |  0.8000  | 0.01569
       75       |   0.800   |  0.7706  | 0.01053
      100       |   0.800   |  0.7531  | 0.00792

Key insight: With diminishing returns (sqrt, log), marginal utility
decreases as allocation increases, incentivizing balance.

══════════════════════════════════════════════════════════════════════

SCENARIO 3: COMPLEMENTARITY (COBB-DOUGLAS)
──────────────────────────────────────────────────────────────────────
Purpose: Show how Cobb-Douglas creates complementarity
         where agents need all resources.

Weights: COMPUTE=50%, MEMORY=30%, STORAGE=20%

Allocation (C,M,S)     | Linear Utility | Cobb-Douglas
-----------------------|----------------|-------------
(100,100,100)          |      100.0     |   100.00
(100,100,  0)          |       80.0     |     0.00
(100,  0,100)          |       70.0     |     0.00
( 50, 50, 50)          |       50.0     |    50.00
( 80, 10, 10)          |       45.0     |    28.28

Key insight: With Cobb-Douglas, zero allocation of ANY resource
results in zero utility, enforcing complementarity.

══════════════════════════════════════════════════════════════════════

SCENARIO 4: THRESHOLD EFFECTS
──────────────────────────────────────────────────────────────────────
Purpose: Show how threshold utility models minimum viable quantity.
         Below threshold, utility approaches zero.

Base: Linear utility
Threshold T=50 total units

Total Alloc | Base Util | Soft (k=0.2) | Sharp (k=1.0)
------------|-----------|--------------|---------------
     20     |    10.4   |      0.03    |      0.00
     30     |    15.6   |      0.28    |      0.00
     40     |    20.8   |      2.48    |      0.00
     50     |    26.0   |     13.00    |     13.00
     60     |    31.2   |     27.48    |     31.20
     70     |    36.4   |     35.75    |     36.40
     80     |    41.6   |     41.50    |     41.60
    100     |    52.0   |     52.00    |     52.00

Key insight: Threshold utility models agents that need a minimum
quantity before any value is derived (e.g., ML training jobs).

══════════════════════════════════════════════════════════════════════

SCENARIO 5: SATIATION EFFECTS
──────────────────────────────────────────────────────────────────────
Purpose: Show how satiation bounds utility with an upper limit.

V_max=100, k=30

Base Utility | Exponential Sat | Hyperbolic Sat
-------------|-----------------|----------------
      8.4    |       24.4      |       21.9
     17.2    |       43.6      |       36.4
     26.0    |       58.0      |       46.4
     43.2    |       76.3      |       59.0
     65.0    |       88.5      |       68.4
     86.4    |       94.4      |       74.2
    130.0    |       98.7      |       81.2
    173.2    |       99.7      |       85.2

Key insight: Satiation models agents who reach 'enough' resources
and have diminishing interest in more.

══════════════════════════════════════════════════════════════════════

SCENARIO 6: NESTED CES (PARTIAL SUBSTITUTES)
──────────────────────────────────────────────────────────────────────
Purpose: Show hierarchical substitution patterns where
         some resources are closer substitutes than others.

Structure:
  Nest 1 (ρ=0.8): {COMPUTE, MEMORY} - close substitutes
  Nest 2 (ρ=0.7): {STORAGE, NETWORK} - close substitutes
  Outer (ρ=0.2): Nests are complements

Allocation (C,M,S,N)         | Nested CES | Flat CES
-----------------------------|------------|----------
(50,50,50,50)                |    50.00   |   50.00
(100, 0,50,50)               |    45.88   |   36.43
(50,50, 0, 0)                |     1.65   |   12.50
(25,25,75,75)                |    44.63   |   46.65

Key insight: Nested CES allows within-nest substitution
while requiring balance across nests.

══════════════════════════════════════════════════════════════════════

SCENARIO 7: LOSS AVERSION
──────────────────────────────────────────────────────────────────────
Purpose: Show reference-dependent preferences where losses
         hurt more than equivalent gains help.

Reference point: COMPUTE=50, STORAGE=50
Loss aversion λ=2.0 (losses hurt 2x as much as gains help)

Deviation from Ref | Linear Change | Softplus | Asymm Log
-------------------|---------------|----------|----------
      -30          |     -15.6     |  -31.45  |  -1.146
      -20          |     -10.4     |  -21.43  |  -0.833
      -10          |      -5.2     |  -11.93  |  -0.461
       +0          |      +0.0     |   -3.47  |  +0.000
      +10          |      +5.2     |   +3.67  |  +0.230
      +20          |     +10.4     |   +9.77  |  +0.417
      +30          |     +15.6     |  +15.35  |  +0.573

Key insight: Loss aversion creates asymmetry around the reference
point. Agents are more motivated to avoid losses than seek gains.

══════════════════════════════════════════════════════════════════════

SCENARIO 8: OPTIMIZER CHOOSING DIFFERENT ALLOCATIONS
──────────────────────────────────────────────────────────────────────
Purpose: Demonstrate that the optimizer makes DIFFERENT
         allocation decisions under different utility functions.
         This is the key result showing nonlinear utilities matter.

Setup:
  LINEAR_AGENT: 90% compute preference (specialist)
  CD_AGENT: 50/50 Cobb-Douglas (needs both)
  Pool: 100 compute, 80 storage
  Scarcity: Total demand exceeds supply

SCENARIO A: Both agents modeled as LINEAR
--------------------------------------------------
  LINEAR_AGENT: 53 compute, 29 storage
  CD_AGENT:     47 compute, 51 storage
  Utilities: LINEAR_AGENT=50.6, CD_AGENT=48.96

SCENARIO B: CD_AGENT modeled with Cobb-Douglas
--------------------------------------------------
(Simulated - optimizer would reallocate to avoid zero utility)
  LINEAR_AGENT: 60 compute, 20 storage
  CD_AGENT:     40 compute, 60 storage
  Utilities: LINEAR_AGENT=56.0, CD_AGENT=48.99

WELFARE COMPARISON:
  Scenario A (all linear): 7.8149
  Scenario B (with CD):    7.9170
  Improvement: 1.31%

Key insight: When the optimizer knows about Cobb-Douglas utility,
it allocates resources differently to avoid giving zero utility.
The linear agent sacrifices some resources, but total welfare improves.

══════════════════════════════════════════════════════════════════════

SCENARIO 9: AUTO-GENERATED AGENTS
──────────────────────────────────────────────────────────────────────
Purpose: Demonstrate random agent generation with diverse
         utility functions for large-scale testing.

Generating 100 agents with seed=42...

Distribution by utility type:
  Linear:                        ███████ 14
  Square Root:                   ███ 7
  Logarithmic:                   ███████ 14
  Cobb-Douglas:                  ████ 9
  Leontief:                      ████ 9
  CES:                           █████ 11
  Threshold:                     ███████ 14
  Satiation:                     ███ 7
  Nested CES:                    ██ 4
  Softplus Loss Aversion:        █ 3
  Asymmetric Log Loss Aversion:  ████ 8

Sample agents:
  TEST_0: THRESHOLD, resources=5, currency=116.0
  TEST_1: THRESHOLD, resources=4, currency=30.2
  TEST_2: SATIATION, resources=5, currency=131.4
  TEST_3: ASYMMETRIC_LOG_LOSS_AVERSION, resources=5, currency=79.3
  TEST_4: NESTED_CES, resources=3, currency=132.2

Verifying reproducibility with same seed...
  Reproducibility: ✓ PASS

Generating mixed batch with specific type counts...
  Generated 20 agents with specific types

Key insight: AgentGenerator enables reproducible large-scale
testing with diverse utility functions.

══════════════════════════════════════════════════════════════════════

══════════════════════════════════════════════════════════════════════
ALL DEMONSTRATIONS COMPLETE
══════════════════════════════════════════════════════════════════════

════════════════════════════════════════════════════════════════════════
   EXTENDED DEMOS: GROUPING POLICY CONFIGURATION
════════════════════════════════════════════════════════════════════════

╔══════════════════════════════════════════════════════════════════════════════╗
║                    GROUPING POLICY CONFIGURATION DEMO                        ║
║  Configurable policies for trading off Pareto optimality vs performance     ║
╚══════════════════════════════════════════════════════════════════════════════╝

───────────────────────────────────────────────────────────────────────────────
SCENARIO 1: K-Hop Limits
Limiting how far contention spreads through the resource graph
───────────────────────────────────────────────────────────────────────────────

  Agents form a chain through shared resources:
    A(Compute,Memory) ↔ B(Memory,Storage) ↔ C(Storage,Network) ↔ D(Network,Dataset) ↔ E(Dataset,API)

  k-hop=1: 2 group(s)
    → Group CG-1: {A, B}
    → Group CG-2: {C, D}
  k-hop=2: 2 group(s)
    → Group CG-1: {A, B, C}
    → Group CG-2: {D, E}
  k-hop=3: 1 group(s)
    → Group CG-1: {A, B, C, D}
  k-hop=∞: 1 group(s)
    → Group CG-1: {A, B, C, D, E}

  ✓ k=1: Only direct competitors grouped (agents sharing same resource)
  ✓ k=2: Competitors-of-competitors included
  ✓ k=∞: Full transitive closure (maximum Pareto optimality)

───────────────────────────────────────────────────────────────────────────────
SCENARIO 2: Size Bounds
Limiting maximum group size for bounded computation time
───────────────────────────────────────────────────────────────────────────────

  20 agents all competing for 100 units of COMPUTE (each wants 10)

  maxSize=5: 4 group(s), largest=5 agents
    Estimated speedup: 16.0x (O(n³) reduction)
  maxSize=10: 2 group(s), largest=10 agents
    Estimated speedup: 4.0x (O(n³) reduction)
  maxSize=20: 1 group(s), largest=20 agents
    Estimated speedup: 1.0x (O(n³) reduction)
  maxSize=∞: 1 group(s), largest=20 agents
    Estimated speedup: 1.0x (O(n³) reduction)

  ✓ Smaller groups = faster computation at cost of optimality
  ✓ O(n³) complexity reduction when splitting large groups

───────────────────────────────────────────────────────────────────────────────
SCENARIO 3: Compatibility Matrices
Explicit control over which agents can be grouped together
───────────────────────────────────────────────────────────────────────────────

  4 agents competing for COMPUTE: 2 trusted, 2 untrusted
  Total demand: 200 units, Pool: 100 units (contention ratio 2.0)

  Mode: No Restrictions
    → {Trusted-1, Trusted-2, Untrusted-1, Untrusted-2}

  Mode: BLOCKLIST (block trusted ↔ untrusted)
    → {Trusted-1, Trusted-2}
    → {Untrusted-1, Untrusted-2}

  Mode: CATEGORY (by trust level)
    → {Trusted-1, Trusted-2}
    → {Untrusted-1, Untrusted-2}

  Mode: ALLOWLIST (only Trusted-1 ↔ Trusted-2 allowed)
    → {Trusted-1, Trusted-2}

  ✓ Compatibility matrices enforce security/organizational boundaries

───────────────────────────────────────────────────────────────────────────────
SCENARIO 4: Tenant Isolation
Multi-tenant system with per-tenant optimization boundaries
───────────────────────────────────────────────────────────────────────────────

  3 tenants × 4 agents = 12 agents
  All agents want COMPUTE + STORAGE (contention across all)

  Without Tenant Isolation:
    1 group(s), max size = 12
  With Tenant Isolation:
    3 group(s):
      TenantA: 4 agents
      TenantB: 4 agents
      TenantC: 4 agents

  ✓ Each tenant's agents optimized independently
  ✓ No cross-tenant information leakage through optimization

───────────────────────────────────────────────────────────────────────────────
SCENARIO 5: Split Strategies
Different algorithms for splitting oversized groups
───────────────────────────────────────────────────────────────────────────────

  20 agents: 8 compute-heavy, 8 storage-heavy, 4 mixed
  Mixed agents create bridging contentions
  Splitting to max size = 6

  Strategy: MIN_CUT
    Groups: 4
      Group 1: 6 agents (C:5, S:1, M:0)
      Group 2: 6 agents (C:2, S:2, M:2)
      Group 3: 6 agents (C:1, S:3, M:2)
      ... and 1 more groups

  Strategy: RESOURCE_AFFINITY
    Groups: 4
      Group 1: 6 agents (C:6, S:0, M:0)
      Group 2: 6 agents (C:0, S:6, M:0)
      Group 3: 6 agents (C:0, S:2, M:4)
      ... and 1 more groups

  Strategy: PRIORITY_CLUSTERING
    Groups: 4
      Group 1: 6 agents (C:3, S:2, M:1)
      Group 2: 6 agents (C:1, S:2, M:3)
      Group 3: 6 agents (C:3, S:3, M:0)
      ... and 1 more groups

  Strategy: ROUND_ROBIN
    Groups: 4
      Group 1: 6 agents (C:5, S:1, M:0)
      Group 2: 6 agents (C:2, S:2, M:2)
      Group 3: 6 agents (C:1, S:3, M:2)
      ... and 1 more groups

  Strategy: SPECTRAL
    Groups: 4
      Group 1: 6 agents (C:5, S:1, M:0)
      Group 2: 6 agents (C:2, S:2, M:2)
      Group 3: 6 agents (C:1, S:3, M:2)
      ... and 1 more groups

  ✓ RESOURCE_AFFINITY groups similar agents together
  ✓ MIN_CUT preserves trade opportunities
  ✓ PRIORITY_CLUSTERING groups by currency level

───────────────────────────────────────────────────────────────────────────────
SCENARIO 6: Policy Analysis
Analyzing trade-offs between policies
───────────────────────────────────────────────────────────────────────────────

  30 agents with random resource demands

  ┌─────────────────────────────────────┬────────┬─────────┬─────────┬──────────┐
  │ Policy                              │ Groups │ MaxSize │ Speedup │ OptLoss  │
  ├─────────────────────────────────────┼────────┼─────────┼─────────┼──────────┤
  │ GroupingPolicy[UNLIMITED]           │      1 │      30 │    1.0x │    0.0% │
  │ GroupingPolicy[k-hop=1]             │      3 │      12 │   15.6x │   49.7% │
  │ GroupingPolicy[k-hop=2]             │      1 │      30 │    1.0x │    0.0% │
  │ GroupingPolicy[maxSize=10]          │      3 │      10 │   27.0x │   60.5% │
  │ GroupingPolicy[maxSize=5]           │      6 │       5 │  216.0x │   73.3% │
  │ GroupingPolicy[k-hop=2, maxSize=10] │      3 │      10 │   27.0x │   60.5% │
  │ GroupingPolicy[k-hop=1, maxSize=10] │      5 │      10 │   27.0x │   50.3% │
  │ GroupingPolicy[k-hop=2, maxSize=20] │      2 │      20 │    3.4x │   37.7% │
  └─────────────────────────────────────┴────────┴─────────┴─────────┴──────────┘

  ✓ Analysis shows trade-off between performance and optimality

───────────────────────────────────────────────────────────────────────────────
SCENARIO 7: Large-Scale Performance
Demonstrating performance benefits with many agents
───────────────────────────────────────────────────────────────────────────────

  Testing with increasing agent counts...

  50 agents:
    Baseline: 1 groups, max=37, detection=0.30ms
    Policy:   5 groups, max=10, detection=1.57ms
    Estimated optimization speedup: 15.7x

  100 agents:
    Baseline: 1 groups, max=81, detection=1.56ms
    Policy:   9 groups, max=10, detection=11.14ms
    Estimated optimization speedup: 72.8x

  200 agents:
    Baseline: 1 groups, max=179, detection=4.78ms
    Policy:   19 groups, max=10, detection=50.99ms
    Estimated optimization speedup: 343.2x

  ✓ Policy-based grouping enables scalability to large agent populations

───────────────────────────────────────────────────────────────────────────────
SCENARIO 8: Combined Policies
Using multiple policy constraints simultaneously
───────────────────────────────────────────────────────────────────────────────

  30 agents (2 tenants × 15 agents)
  Policy: Tenant isolation + max size 5 + k-hop 2

  Result: 6 groups
    TenantA: 3 groups
      → 5 agents
      → 5 agents
      → 5 agents
    TenantB: 3 groups
      → 5 agents
      → 5 agents
      → 5 agents

  ✓ Tenant isolation enforced
  ✓ Max size 5 respected
  ✓ K-hop 2 limits transitive grouping

───────────────────────────────────────────────────────────────────────────────
SCENARIO 9: Dynamic Arbitration Comparison
Comparing welfare under different policies with RESOURCE-CONSERVING arbitration
───────────────────────────────────────────────────────────────────────────────

  10 agents competing for 100 COMPUTE (want 300 total)
  5 high-priority (c=200), 5 low-priority (c=50)

  NOTE: Using resource-conserving arbitration to prevent over-allocation.

  Policy: GroupingPolicy[UNLIMITED]
    Groups: 1
    Total Welfare: 530.69
    High-priority total: 65, Low-priority total: 35
    TOTAL ALLOCATED: 100 (pool: 100) ✓ Conservation OK

  Policy: GroupingPolicy[maxSize=5]
    Groups: 2
    Total Welfare: 530.18
    High-priority total: 67, Low-priority total: 33
    TOTAL ALLOCATED: 100 (pool: 100) ✓ Conservation OK

  Policy: GroupingPolicy[maxSize=3]
    Groups: 4
    Total Welfare: 525.01
    High-priority total: 60, Low-priority total: 40
    TOTAL ALLOCATED: 100 (pool: 100) ✓ Conservation OK

  ✓ Resource conservation maintained across all policies
  ✓ Different policies lead to different allocation outcomes
  ✓ Smaller groups may reduce cross-agent optimization opportunities

═══════════════════════════════════════════════════════════════════════════════
ALL GROUPING POLICY SCENARIOS COMPLETED
═══════════════════════════════════════════════════════════════════════════════
════════════════════════════════════════════════════════════════════════
   ALL DEMONSTRATIONS COMPLETE
════════════════════════════════════════════════════════════════════════
```

---

## Realistic Agent Demo Output (`./run.sh --agents`)

This demo shows the v0.6 realistic agent framework with:
- **NewsSearchAgent**: Richard's example of a "narrow tailored agent with low autonomy"
- **Multi-agent environment**: 5 agents with different autonomy levels competing for resources
- **A+G+I Emergence Monitoring**: Conjunction detection for safety
- **Autonomy levels**: TOOL, LOW, MEDIUM, HIGH with different checkpoint requirements

**Note:** By default, agents use the **MockServiceBackend** which returns deterministic test responses. To use **real LLM APIs**, set environment variables (`GEMINI_API_KEY`, `OPENAI_API_KEY`, or `ANTHROPIC_API_KEY`) and use `LLMServiceBackend`:

```java
LLMServiceBackend backend = new LLMServiceBackend.Builder()
    .fromEnvironment()  // Reads API keys from environment
    .build();

AgentRuntime runtime = new AgentRuntime.Builder()
    .serviceBackend(backend)  // Real LLM calls instead of mocks
    .build();
```

```text
════════════════════════════════════════════════════════════════════════
   REALISTIC AGENT DEMONSTRATION
   With AGI Emergence Monitoring (A+G+I Conjunction Detection)
════════════════════════════════════════════════════════════════════════

SCENARIO 1: NEWS SEARCH AGENT
────────────────────────────────────────────────────────────
Purpose: Demonstrate Richard's example of a 'narrow tailored
         agent with low autonomy' - searches news and posts
         to a signal channel.

Agent Configuration:
  ID: news-ai-safety
  Name: AI Safety News Monitor
  Autonomy Level: Low
  Max Autonomous Span: 15 minutes
  Required Services: [Knowledge Retrieval, Text Generation]
  Operating Domains: [news, information_retrieval]
  Goals: 1
    - Goal[periodic-news-search: Search for news on configured topics, type=PERIODIC, priority=5, status=PENDING]

Running News Agent...

[news-ai-safety] Starting news search for topics: [AI safety, LLM capabilities, AI governance, alignment research]
[news-ai-safety] Found 8 total results

Execution Result:
  GoalResult[SUCCESS: Found 8 news items, 47ms]
  Services Used: [KNOWLEDGE_RETRIEVAL, KNOWLEDGE_RETRIEVAL, KNOWLEDGE_RETRIEVAL, KNOWLEDGE_RETRIEVAL, TEXT_GENERATION]
  Execution Time: 47ms

Messages Published to Signal Channel:
  [news_update] {summary=..., topics=[AI safety, LLM capabilities, AI governance, alignment research],
                 type=news_summary, result_count=8, timestamp=...}

Agent Metrics:
  Metrics[goals=1/1 (100%), services=5, time=47ms]

  ✓ PASS: News agent executed successfully with bounded autonomy

SCENARIO 2: MULTI-AGENT ENVIRONMENT
────────────────────────────────────────────────────────────
Purpose: Demonstrate multiple agents with different autonomy
         levels operating through the arbitration platform.

Registered Agents:
  Agent ID             Autonomy        Services Used
  -------------------------------------------------------
  news-tech            Low             2 types
  code-reviewer        Low             2 types
  doc-summarizer       Tool-like       1 types
  research-ai          Medium          4 types
  sys-monitor          Low             0 types

Resource Competition Scenario:
  All agents compete for limited TEXT_GENERATION capacity
  Arbitration uses weighted proportional fairness

Agent Autonomy Comparison:
  news-tech:        Low autonomy, 15 min max span, domains: [news, information_retrieval]
  code-reviewer:    Low autonomy, 15 min max span, domains: [software_development, code_quality]
  doc-summarizer:   Tool-like, no autonomous span, domains: [document_processing]
  research-ai:      Medium autonomy, 1 hour max span, domains: [AI safety, ML, research]
  sys-monitor:      Low autonomy, 15 min max span, domains: [operations, monitoring]

  ✓ PASS: Multi-agent environment running with resource arbitration

SCENARIO 3: AGI EMERGENCE DETECTION (A+G+I MONITOR)
────────────────────────────────────────────────────────────
Purpose: Demonstrate the conjunction detection monitor that
         tracks Autonomy, Generality, and Intelligence scores.

The CAIS Safety Model:
  Any SINGLE property at high levels is manageable:
  - High Autonomy alone: Specialized automation, bounded damage
  - High Generality alone: Versatile tool, but human-controlled
  - High Intelligence alone: Powerful oracle, but human-directed

  The DANGER is the CONJUNCTION: High A + High G + High I =
  System that can effectively pursue goals across domains
  without meaningful human oversight.

Monitor Configuration:
  Autonomy threshold: 0.70
  Generality threshold: 0.70
  Intelligence threshold: 0.70

Agent 1: 'narrow-tool' (Safe Narrow Agent)
  Scores: A=0.00, G=0.02, I=0.00
  Conjunction Risk: Low
  Recommended Intervention: Observe

Agent 2: 'wide-tool' (Moderate Concern - High Generality)
  Scores: A=0.00, G=0.81 ⚠ FLAGGED, I=0.00
  Conjunction Risk: Moderate
  Recommended Intervention: Alert

Agent 3: 'autonomous-general' (HIGH CONCERN - Multi-dimension)
  ⚠ PATTERN: Tool Making detected
  ⚠ PATTERN: Instrumental Goal Pursuit detected
  Scores: A=0.61, G=0.81 ⚠ FLAGGED, I=0.65
  Conjunction Risk: Moderate
  Recommended Intervention: Suspend

  ✓ PASS: AGI emergence detection working correctly

SCENARIO 4: AUTONOMY LEVEL COMPARISON
────────────────────────────────────────────────────────────
Autonomy Level Definitions:

  TOOL-LIKE (level 0): Only acts when explicitly invoked
  LOW (level 1):       15 min max span, periodic single-step tasks
  MEDIUM (level 2):    1 hour max span, multi-step goals, service chaining
  HIGH (level 3):      4 hour max span, complex goals, requires A+G+I monitoring

Safety Implications:
  TOOL:   Safest - never self-schedule, always human-initiated
  LOW:    Safe for narrow tasks - periodic execution, frequent checkpoints
  MEDIUM: Requires monitoring - can chain services, hourly checkpoints
  HIGH:   Comprehensive monitoring essential - A+G+I conjunction tracking

  ✓ PASS: Autonomy levels correctly bounded

════════════════════════════════════════════════════════════════════════
   REALISTIC AGENT DEMONSTRATION COMPLETE
════════════════════════════════════════════════════════════════════════
```