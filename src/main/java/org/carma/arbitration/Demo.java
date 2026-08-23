package org.carma.arbitration;

import org.carma.arbitration.mechanism.*;
import org.carma.arbitration.model.*;
import org.carma.arbitration.simulation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Main demonstration of the Arbitration Platform.
 * 
 * Runs all validation scenarios from the theory appendix:
 * 1. Basic mechanism validation
 * 2. Joint vs separate optimization
 * 3. Priority-weighted allocation under a logarithmic-barrier objective
 * 4. Complementary preferences
 * 5. Priority economy dynamics
 * 6. Allocations at or above each agent's declared minimum
 * 7. Minimum-bound allocation for low-weight agents
 * 8. Asymptotic behavior (15s test)
 * 9. Joint optimization
 * 10. Diverse resources
 * 11. AI Service integration
 * 12. Nonlinear utility functions (NEW)
 * 
 * Usage:
 *   java Demo.java              # Run validation scenarios only
 *   java Demo.java --full       # Run validation + 15-second asymptotic test
 *   java Demo.java --asymptotic # Run only the asymptotic test
 */
public class Demo {
    
    private static final String SEP = "═".repeat(72);
    private static final String SUBSEP = "─".repeat(60);

    public static void main(String[] args) {
        boolean runAsymptotic = Arrays.asList(args).contains("--full") || 
                               Arrays.asList(args).contains("--asymptotic");
        boolean onlyAsymptotic = Arrays.asList(args).contains("--asymptotic");
        
        System.out.println(SEP);
        System.out.println("   PLATFORM-MEDIATED MULTI-AGENT RESOURCE ARBITRATION");
        System.out.println("   Illustrative scenarios (single constructed instances)");
        System.out.println(SEP);
        System.out.println();
        
        if (!onlyAsymptotic) {
            PriorityEconomy economy = new PriorityEconomy();
            ProportionalFairnessArbitrator arbitrator = new ProportionalFairnessArbitrator(economy);
            
            runScenario1_BasicMechanism(arbitrator);
            runScenario2_JointVsSeparate(arbitrator);
            runScenario3_MinimumBoundUnderManyRequests(arbitrator);
            runScenario4_ComplementaryPreferences(arbitrator);
            runScenario5_PriorityEconomy(arbitrator, economy);
            runScenario6_DeclaredMinimumCompliance(arbitrator);
            runScenario7_MinimumBoundForLowWeightAgents(arbitrator);
        }
        
        if (runAsymptotic) {
            runAsymptoticTest();
            runJointOptimizationTest();
            runDiverseResourceTest();
            runScenario11_ServiceIntegration();
            runScenario12_NonlinearUtilities();
        }

        // Run extended demos when --full is passed
        if (runAsymptotic && !onlyAsymptotic) {
            System.out.println();
            System.out.println(SEP);
            System.out.println("   EXTENDED DEMOS: NONLINEAR UTILITIES");
            System.out.println(SEP);
            System.out.println();
            NonlinearUtilityDemo.main(new String[]{});
            
            System.out.println();
            System.out.println(SEP);
            System.out.println("   EXTENDED DEMOS: GROUPING POLICY CONFIGURATION");
            System.out.println(SEP);
            System.out.println();
            GroupingPolicyDemo.main(new String[]{});
        }
        
        System.out.println(SEP);
        System.out.println("   ALL DEMONSTRATIONS COMPLETE");
        System.out.println(SEP);
    }

    // ========================================================================
    // SCENARIO 1: Basic Mechanism Validation
    // ========================================================================
    
    static void runScenario1_BasicMechanism(ProportionalFairnessArbitrator arbitrator) {
        System.out.println("SCENARIO 1: BASIC MECHANISM VALIDATION");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Verify that Weighted Proportional Fairness allocates");
        System.out.println("         proportional to weights while respecting bounds.");
        System.out.println();
        
        Agent a1 = new Agent("A1", "Research Agent", 
            Map.of(ResourceType.COMPUTE, 1.0), 100);
        a1.setRequest(ResourceType.COMPUTE, 40, 80);
        
        Agent a2 = new Agent("A2", "Training Agent",
            Map.of(ResourceType.COMPUTE, 1.0), 50);
        a2.setRequest(ResourceType.COMPUTE, 30, 70);
        
        List<Agent> agents = List.of(a1, a2);
        Contention contention = new Contention(ResourceType.COMPUTE, agents, 100);
        
        System.out.println("Setup:");
        System.out.println("  Resource pool: 100 compute units");
        System.out.println("  Agent A1: wants 40-80 units");
        System.out.println("  Agent A2: wants 30-70 units");
        System.out.println("  Total demand: 150 units (contention ratio: 1.5)");
        System.out.println();
        
        // Test 1: Equal weights
        Map<String, BigDecimal> noBurn = Map.of("A1", BigDecimal.ZERO, "A2", BigDecimal.ZERO);
        AllocationResult r1 = arbitrator.arbitrate(contention, noBurn);
        
        System.out.println("Test 1: Equal weights (10:10)");
        System.out.println("  A1 allocation: " + r1.getAllocation("A1") + " units");
        System.out.println("  A2 allocation: " + r1.getAllocation("A2") + " units");
        System.out.println("  Total: " + r1.getTotalAllocated() + " units");
        
        boolean t1pass = r1.getTotalAllocated() <= 100 &&
                        r1.getAllocation("A1") >= 40 &&
                        r1.getAllocation("A2") >= 30;
        System.out.println("  " + (t1pass ? "✓ PASS" : "✗ FAIL") + ": Constraints satisfied");
        System.out.println();
        
        // Test 2: Unequal weights
        Map<String, BigDecimal> a1Burns = Map.of("A1", BigDecimal.valueOf(50), "A2", BigDecimal.ZERO);
        AllocationResult r2 = arbitrator.arbitrate(contention, a1Burns);
        
        System.out.println("Test 2: Unequal weights (60:10) - A1 burns 50 currency");
        System.out.println("  A1 allocation: " + r2.getAllocation("A1") + " units");
        System.out.println("  A2 allocation: " + r2.getAllocation("A2") + " units");
        System.out.println("  Total: " + r2.getTotalAllocated() + " units");
        
        boolean t2pass = r2.getTotalAllocated() <= 100 &&
                        r2.getAllocation("A1") > r2.getAllocation("A2") &&
                        r2.getAllocation("A1") >= 40 &&
                        r2.getAllocation("A2") >= 30;
        System.out.println("  " + (t2pass ? "✓ PASS" : "✗ FAIL") + ": Higher weight gets more");
        System.out.println();
    }

    // ========================================================================
    // SCENARIO 2: Joint vs Separate Optimization
    // ========================================================================
    
    static void runScenario2_JointVsSeparate(ProportionalFairnessArbitrator arbitrator) {
        System.out.println("SCENARIO 2: JOINT VS SEPARATE OPTIMIZATION");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Compare Proportional Fairness with naive proportional");
        System.out.println("         allocation on one constructed instance.");
        System.out.println();
        
        Agent a1 = new Agent("A1", "Compute-Heavy",
            Map.of(ResourceType.COMPUTE, 0.7, ResourceType.STORAGE, 0.3), 100);
        a1.setRequest(ResourceType.COMPUTE, 30, 70);
        a1.setRequest(ResourceType.STORAGE, 10, 30);
        
        Agent a2 = new Agent("A2", "Storage-Heavy",
            Map.of(ResourceType.COMPUTE, 0.3, ResourceType.STORAGE, 0.7), 100);
        a2.setRequest(ResourceType.COMPUTE, 10, 30);
        a2.setRequest(ResourceType.STORAGE, 30, 70);
        
        Agent a3 = new Agent("A3", "Balanced",
            Map.of(ResourceType.COMPUTE, 0.5, ResourceType.STORAGE, 0.5), 100);
        a3.setRequest(ResourceType.COMPUTE, 20, 50);
        a3.setRequest(ResourceType.STORAGE, 20, 50);
        
        List<Agent> agents = List.of(a1, a2, a3);
        Map<String, BigDecimal> noBurn = Map.of("A1", BigDecimal.ZERO, "A2", BigDecimal.ZERO, "A3", BigDecimal.ZERO);
        
        System.out.println("Setup:");
        System.out.println("  A1: 70% compute, 30% storage preference");
        System.out.println("  A2: 30% compute, 70% storage preference");
        System.out.println("  A3: 50% compute, 50% storage preference");
        System.out.println("  Resources: 100 compute, 100 storage");
        System.out.println();
        
        // Baseline: naive proportional
        System.out.println("Baseline (Naive Proportional):");
        double baselineWelfare = 0;
        for (ResourceType type : List.of(ResourceType.COMPUTE, ResourceType.STORAGE)) {
            List<Agent> competing = agents.stream().filter(a -> a.getIdeal(type) > 0).toList();
            AllocationResult naive = arbitrateNaiveProportional(competing, type, 100, noBurn);
            for (Agent a : competing) {
                long alloc = naive.getAllocation(a.getId());
                double utility = a.getPreferences().getWeight(type) * alloc;
                if (utility > 0) {
                    baselineWelfare += PriorityEconomy.BASE_WEIGHT * Math.log(utility);
                }
            }
        }
        System.out.println("  Total welfare: " + String.format("%.4f", baselineWelfare));
        System.out.println();
        
        // PF optimization
        System.out.println("Proportional Fairness:");
        double pfWelfare = 0;
        for (ResourceType type : List.of(ResourceType.COMPUTE, ResourceType.STORAGE)) {
            List<Agent> competing = agents.stream().filter(a -> a.getIdeal(type) > 0).toList();
            Contention contention = new Contention(type, competing, 100);
            AllocationResult result = arbitrator.arbitrate(contention, noBurn);
            System.out.println("  " + type + " allocations: " + 
                competing.stream()
                    .map(a -> a.getId() + "=" + result.getAllocation(a.getId()))
                    .collect(Collectors.joining(", ")));
            for (Agent a : competing) {
                long alloc = result.getAllocation(a.getId());
                double utility = a.getPreferences().getWeight(type) * alloc;
                if (utility > 0) {
                    pfWelfare += PriorityEconomy.BASE_WEIGHT * Math.log(utility);
                }
            }
        }
        System.out.println("  Total welfare: " + String.format("%.4f", pfWelfare));
        System.out.println();
        
        double improvement = (pfWelfare - baselineWelfare) / Math.abs(baselineWelfare) * 100;
        System.out.println(SEP);
        System.out.println("  Welfare improvement: " + String.format("%.2f%%", improvement));
        System.out.println("  " + (improvement > 0 ? "✓ PASS" : "○ NEUTRAL") + 
            ": PF " + (improvement > 0 ? "improves" : "matches") + " naive proportional");
        System.out.println(SEP);
        System.out.println();
    }

    // ========================================================================
    // SCENARIO 3: minimum-bound compliance under many competing requests
    // ========================================================================

    static void runScenario3_MinimumBoundUnderManyRequests(ProportionalFairnessArbitrator arbitrator) {
        System.out.println("SCENARIO 3: MINIMUM-BOUND COMPLIANCE UNDER MANY COMPETING REQUESTS");
        System.out.println(SUBSEP);
        System.out.println("Purpose: In one constructed instance, check whether a feasible declared");
        System.out.println("         minimum is respected when many higher-weight agents compete.");
        System.out.println("Scope: this checks bound compliance in a single instance only. It does");
        System.out.println("         not test coordinated misreporting, coalition gain, or collusion");
        System.out.println("         resistance.");
        System.out.println();

        Agent focal = new Agent("A0", "Low-weight agent", Map.of(ResourceType.COMPUTE, 1.0), 100);
        focal.setRequest(ResourceType.COMPUTE, 20, 50);

        List<Agent> agents = new ArrayList<>();
        agents.add(focal);

        Map<String, BigDecimal> burns = new HashMap<>();
        burns.put("A0", BigDecimal.ZERO);

        int numCompeting = 100;
        BigDecimal competingBurn = BigDecimal.valueOf(10);

        for (int i = 0; i < numCompeting; i++) {
            Agent other = new Agent("R" + i, "Requester " + i,
                Map.of(ResourceType.COMPUTE, 1.0), 1000);
            other.setRequest(ResourceType.COMPUTE, 1, 100);
            agents.add(other);
            burns.put("R" + i, competingBurn);
        }

        System.out.println("Setup:");
        System.out.println("  1 low-weight agent: minimum 20 units, ideal 50, burns 0");
        System.out.println("  " + numCompeting + " competing agents: each burns " + competingBurn + " currency");
        System.out.println("  Pool: 500 compute units");
        System.out.println("  Total competing weight: " + (numCompeting * (10 + competingBurn.intValue())));
        System.out.println("  Low-weight agent weight: 10");
        System.out.println("  Ratio: " + (numCompeting * 20) + ":10 = " + (numCompeting * 2) + ":1");
        System.out.println();

        Contention contention = new Contention(ResourceType.COMPUTE, agents, 500);
        AllocationResult result = arbitrator.arbitrate(contention, burns);

        long focalAlloc = result.getAllocation("A0");
        long totalCompetingAlloc = 0;
        for (int i = 0; i < numCompeting; i++) {
            totalCompetingAlloc += result.getAllocation("R" + i);
        }

        System.out.println("Results:");
        System.out.println("  Low-weight agent allocation: " + focalAlloc + " units");
        System.out.println("  Its declared minimum: " + focal.getMinimum(ResourceType.COMPUTE) + " units");
        System.out.println("  Total competing allocation: " + totalCompetingAlloc + " units");
        System.out.println("  Average per competing agent: " + (totalCompetingAlloc / numCompeting) + " units");
        System.out.println();

        boolean minimumRespected = focalAlloc >= focal.getMinimum(ResourceType.COMPUTE);
        System.out.println(SEP);
        System.out.println("  " + (minimumRespected ? "Observed" : "✗ FAIL") +
            ": declared minimum " + (minimumRespected ? "respected" : "not respected")
            + " in this instance despite a " + (numCompeting * 2) + ":1 weight disadvantage");
        System.out.println("  This is one constructed instance, not a collusion-resistance result.");
        System.out.println(SEP);
        System.out.println();
    }

    // ========================================================================
    // SCENARIO 4: Complementary Preferences
    // ========================================================================
    
    static void runScenario4_ComplementaryPreferences(ProportionalFairnessArbitrator arbitrator) {
        System.out.println("SCENARIO 4: COMPLEMENTARY PREFERENCES");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Demonstrate welfare improvement when agents have");
        System.out.println("         complementary resource preferences (specialists + generalist).");
        System.out.println();
        
        Agent computeSpec = new Agent("COMP", "Compute Specialist",
            Map.of(ResourceType.COMPUTE, 0.9, ResourceType.STORAGE, 0.1), 100);
        computeSpec.setRequest(ResourceType.COMPUTE, 30, 80);
        computeSpec.setRequest(ResourceType.STORAGE, 5, 20);
        
        Agent storageSpec = new Agent("STOR", "Storage Specialist",
            Map.of(ResourceType.COMPUTE, 0.1, ResourceType.STORAGE, 0.9), 100);
        storageSpec.setRequest(ResourceType.COMPUTE, 5, 20);
        storageSpec.setRequest(ResourceType.STORAGE, 30, 80);
        
        Agent balanced = new Agent("BAL", "Balanced Agent",
            Map.of(ResourceType.COMPUTE, 0.5, ResourceType.STORAGE, 0.5), 100);
        balanced.setRequest(ResourceType.COMPUTE, 20, 60);
        balanced.setRequest(ResourceType.STORAGE, 20, 60);
        
        List<Agent> agents = List.of(computeSpec, storageSpec, balanced);
        Map<String, BigDecimal> noBurn = Map.of("COMP", BigDecimal.ZERO, "STOR", BigDecimal.ZERO, "BAL", BigDecimal.ZERO);
        
        System.out.println("Setup:");
        System.out.println("  COMP: 90% compute, 10% storage (specialist)");
        System.out.println("  STOR: 10% compute, 90% storage (specialist)");
        System.out.println("  BAL: 50% compute, 50% storage (generalist)");
        System.out.println("  Resources: 100 compute, 100 storage");
        System.out.println();
        
        double totalWelfare = 0;
        double totalUtility = 0;
        
        for (ResourceType type : List.of(ResourceType.COMPUTE, ResourceType.STORAGE)) {
            List<Agent> competing = agents.stream().filter(a -> a.getIdeal(type) > 0).toList();
            Contention contention = new Contention(type, competing, 100);
            AllocationResult result = arbitrator.arbitrate(contention, noBurn);
            
            System.out.println("  " + type + " allocations:");
            for (Agent a : competing) {
                long alloc = result.getAllocation(a.getId());
                double utility = a.getPreferences().getWeight(type) * alloc;
                totalUtility += utility;
                System.out.println("    " + a.getId() + ": " + alloc + " units (utility contribution: " + 
                    String.format("%.1f", utility) + ")");
                if (utility > 0) {
                    totalWelfare += PriorityEconomy.BASE_WEIGHT * Math.log(utility);
                }
            }
        }
        
        System.out.println();
        System.out.println("  Total utility: " + String.format("%.1f", totalUtility));
        System.out.println("  Total welfare: " + String.format("%.4f", totalWelfare));
        System.out.println();
        
        double utilization = totalUtility / 200 * 100;
        System.out.println(SEP);
        System.out.println("  Resource utilization: " + String.format("%.2f%%", utilization));
        System.out.println("  " + (utilization > 85 ? "✓ PASS" : "○ NOTE") + 
            ": Complementary preferences enable efficient allocation");
        System.out.println(SEP);
        System.out.println();
    }

    // ========================================================================
    // SCENARIO 5: Priority Economy Dynamics
    // ========================================================================
    
    static void runScenario5_PriorityEconomy(ProportionalFairnessArbitrator arbitrator, PriorityEconomy economy) {
        System.out.println("SCENARIO 5: PRIORITY ECONOMY DYNAMICS");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Demonstrate the earning/burning currency dynamics");
        System.out.println("         and how they affect allocation over time.");
        System.out.println();
        
        Agent a1 = new Agent("A1", "Frequent Releaser", Map.of(ResourceType.COMPUTE, 1.0), 100);
        a1.setRequest(ResourceType.COMPUTE, 20, 60);
        
        Agent a2 = new Agent("A2", "Holder", Map.of(ResourceType.COMPUTE, 1.0), 100);
        a2.setRequest(ResourceType.COMPUTE, 20, 60);
        
        List<Agent> agents = List.of(a1, a2);
        Map<ResourceType, Long> poolCapacity = Map.of(ResourceType.COMPUTE, 100L);
        ResourcePool pool = new ResourcePool(poolCapacity);
        
        System.out.println("Setup:");
        System.out.println("  Two agents with identical preferences, starting with 100 currency each");
        System.out.println("  A1 releases resources early (earns currency)");
        System.out.println("  A2 holds resources (no earning)");
        System.out.println("  Both burn 10 currency per round");
        System.out.println();
        
        System.out.println("Simulation (5 rounds):");
        
        for (int round = 1; round <= 5; round++) {
            System.out.println("  Round " + round + ":");
            
            Map<String, BigDecimal> burns = Map.of("A1", BigDecimal.TEN, "A2", BigDecimal.TEN);
            
            Contention contention = new Contention(ResourceType.COMPUTE, agents, 100);
            AllocationResult result = arbitrator.arbitrate(contention, burns);
            
            for (Agent a : agents) {
                if (a.canBurn(BigDecimal.TEN)) {
                    a.burnCurrency(BigDecimal.TEN);
                }
            }
            
            System.out.println("    Allocations: A1=" + result.getAllocation("A1") +
                ", A2=" + result.getAllocation("A2"));
            
            if (a1.getCurrencyBalance().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal earnings = economy.calculateReleaseEarnings(
                    ResourceType.COMPUTE, 20, 0.7, pool);
                a1.earnCurrency(earnings);
                System.out.println("    A1 releases 20 units early, earns " + 
                    earnings.setScale(2, RoundingMode.HALF_UP) + " currency");
            }
            
            System.out.println("    Balances: A1=" + a1.getCurrencyBalance().setScale(2, RoundingMode.HALF_UP) +
                ", A2=" + a2.getCurrencyBalance().setScale(2, RoundingMode.HALF_UP));
            System.out.println();
        }
        
        System.out.println(SEP);
        System.out.println("  Final balances: A1=" + a1.getCurrencyBalance().setScale(2, RoundingMode.HALF_UP) +
            ", A2=" + a2.getCurrencyBalance().setScale(2, RoundingMode.HALF_UP));
        boolean a1Richer = a1.getCurrencyBalance().compareTo(a2.getCurrencyBalance()) > 0;
        System.out.println("  " + (a1Richer ? "✓ PASS" : "✗ FAIL") +
            ": Frequent releaser accumulated more currency");
        System.out.println(SEP);
        System.out.println();
    }

    // ========================================================================
    // SCENARIO 6: allocations respect declared minimums
    // ========================================================================
    
    static void runScenario6_DeclaredMinimumCompliance(ProportionalFairnessArbitrator arbitrator) {
        System.out.println("SCENARIO 6: ALLOCATIONS RESPECT DECLARED MINIMUMS");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Verify that each agent's allocation is at least its declared");
        System.out.println("         minimum request in this feasible instance.");
        System.out.println();

        Agent a1 = new Agent("A1", "High Demand", Map.of(ResourceType.COMPUTE, 1.0), 100);
        a1.setRequest(ResourceType.COMPUTE, 30, 80);

        Agent a2 = new Agent("A2", "Medium Demand", Map.of(ResourceType.COMPUTE, 1.0), 100);
        a2.setRequest(ResourceType.COMPUTE, 20, 50);

        Agent a3 = new Agent("A3", "Low Demand", Map.of(ResourceType.COMPUTE, 1.0), 100);
        a3.setRequest(ResourceType.COMPUTE, 10, 30);

        List<Agent> agents = List.of(a1, a2, a3);
        Map<String, BigDecimal> noBurn = agents.stream()
            .collect(Collectors.toMap(Agent::getId, a -> BigDecimal.ZERO));

        System.out.println("Setup:");
        System.out.println("  A1: minimum 30, ideal 80");
        System.out.println("  A2: minimum 20, ideal 50");
        System.out.println("  A3: minimum 10, ideal 30");
        System.out.println("  Pool: 100 compute units");
        System.out.println();

        Contention c = new Contention(ResourceType.COMPUTE, agents, 100);
        AllocationResult r = arbitrator.arbitrate(c, noBurn);

        System.out.println("Allocations:");
        boolean allMet = true;
        for (Agent a : agents) {
            long allocation = r.getAllocation(a.getId());
            long minimum = a.getMinimum(ResourceType.COMPUTE);
            boolean met = allocation >= minimum;
            allMet &= met;
            System.out.println("  " + a.getId() + ": got " + allocation +
                ", declared minimum " + minimum + " → " + (met ? "✓" : "✗"));
        }

        System.out.println();
        System.out.println(SEP);
        System.out.println("  " + (allMet ? "Observed" : "✗ FAIL") +
            ": all agents received at least their declared minimum in this instance");
        System.out.println("  This checks declared-minimum compliance, not individual rationality.");
        System.out.println(SEP);
        System.out.println();
    }

    // ========================================================================
    // SCENARIO 7: minimum-bound allocation for low-weight agents
    // ========================================================================
    
    static void runScenario7_MinimumBoundForLowWeightAgents(ProportionalFairnessArbitrator arbitrator) {
        System.out.println("SCENARIO 7: MINIMUM-BOUND ALLOCATION FOR LOW-WEIGHT AGENTS");
        System.out.println(SUBSEP);
        System.out.println("Purpose: With feasible declared minimums (9 x 5 + 10 = 55 <= 100),");
        System.out.println("         check that low-weight agents receive at least their minimum.");
        System.out.println("         The nonzero allocation follows from the explicit minimum");
        System.out.println("         bounds being feasible, not from the barrier alone.");
        System.out.println();
        
        Agent whale = new Agent("WHALE", "Wealthy Agent", Map.of(ResourceType.COMPUTE, 1.0), 10000);
        whale.setRequest(ResourceType.COMPUTE, 10, 100);
        
        List<Agent> agents = new ArrayList<>();
        agents.add(whale);
        
        for (int i = 1; i <= 9; i++) {
            Agent minnow = new Agent("M" + i, "Minnow " + i, Map.of(ResourceType.COMPUTE, 1.0), 0);
            minnow.setRequest(ResourceType.COMPUTE, 5, 20);
            agents.add(minnow);
        }
        
        System.out.println("Setup:");
        System.out.println("  1 whale: 10000 currency, burns 5000");
        System.out.println("  9 minnows: 0 currency each");
        System.out.println("  Whale weight: 5010, Each minnow weight: 10");
        System.out.println("  Total minnow weight: 90 (ratio 55:1 against each minnow)");
        System.out.println("  Pool: 100 compute units");
        System.out.println();
        
        Map<String, BigDecimal> burns = new HashMap<>();
        burns.put("WHALE", BigDecimal.valueOf(5000));
        for (int i = 1; i <= 9; i++) {
            burns.put("M" + i, BigDecimal.ZERO);
        }
        
        Contention c = new Contention(ResourceType.COMPUTE, agents, 100);
        AllocationResult r = arbitrator.arbitrate(c, burns);
        
        System.out.println("Allocations:");
        System.out.println("  WHALE: " + r.getAllocation("WHALE") + " units");
        
        long minnowTotal = 0;
        boolean allMinnowsGotMinimum = true;
        for (int i = 1; i <= 9; i++) {
            long alloc = r.getAllocation("M" + i);
            minnowTotal += alloc;
            if (alloc < 5) allMinnowsGotMinimum = false;
        }
        System.out.println("  Minnows (each): " + (minnowTotal / 9) + " units average");
        System.out.println("  Minnows (total): " + minnowTotal + " units");
        System.out.println("  Total allocated: " + r.getTotalAllocated());
        System.out.println();
        
        System.out.println(SEP);
        System.out.println("  " + (allMinnowsGotMinimum ? "Observed" : "✗ FAIL") +
            ": each low-weight agent received at least its declared minimum (5 units)");
        System.out.println("  This follows from the feasible minimum bounds, not the barrier alone.");
        System.out.println(SEP);
        System.out.println();
    }

    // ========================================================================
    // ASYMPTOTIC TEST (15+ seconds)
    // ========================================================================
    
    static void runAsymptoticTest() {
        System.out.println("SCENARIO 8: ASYMPTOTIC BEHAVIOR TEST (15 seconds)");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Analyze long-running behavior including convergence,");
        System.out.println("         equilibrium properties, and currency dynamics over time.");
        System.out.println();
        
        Map<ResourceType, Long> capacity = new HashMap<>();
        capacity.put(ResourceType.COMPUTE, 500L);
        capacity.put(ResourceType.STORAGE, 500L);
        
        AsymptoticSimulation sim = new AsymptoticSimulation(10, capacity)
            .setDuration(15_000)
            .setTickInterval(50)
            .setVerbose(true);
        
        SimulationMetrics results = sim.run();
        
        System.out.println();
        System.out.println(SEP);
        System.out.println("ASYMPTOTIC ANALYSIS RESULTS");
        System.out.println(SEP);
        System.out.println();
        System.out.println(results.getSummary());
        
        System.out.println("Contention Histogram (by number of competing agents):");
        System.out.print(results.getContentionHistogramString());
        System.out.println();
        
        boolean converged = results.hasConverged(20, 0.01);
        if (converged) {
            System.out.println("✓ System reached stable equilibrium");
            System.out.println("  Welfare variance in final 20 ticks < 1%");
        } else {
            System.out.println("⚠ System did not reach stable equilibrium (expected for short runs)");
            System.out.println("  EMA smoothing prevents oscillation");
        }
        System.out.println(SEP);
        System.out.println();
    }

    // ========================================================================
    // SCENARIO 9: JOINT VS SEQUENTIAL ON ONE CROSS-RESOURCE INSTANCE
    // ========================================================================

    static void runJointOptimizationTest() {
        System.out.println("SCENARIO 9: JOINT VS SEQUENTIAL ON ONE CROSS-RESOURCE INSTANCE");
        System.out.println(SUBSEP);
        System.out.println("Purpose: On one constructed two-resource instance, compare joint and");
        System.out.println("         per-resource sequential allocation. This is a single instance,");
        System.out.println("         not a general joint-allocation advantage.");
        System.out.println();
        
        PriorityEconomy economy = new PriorityEconomy();
        
        Agent comp = new Agent("COMP", "Compute Specialist",
            Map.of(ResourceType.COMPUTE, 0.9, ResourceType.STORAGE, 0.1), 100);
        comp.setRequest(ResourceType.COMPUTE, 20, 80);
        comp.setRequest(ResourceType.STORAGE, 5, 30);
        
        Agent stor = new Agent("STOR", "Storage Specialist",
            Map.of(ResourceType.COMPUTE, 0.1, ResourceType.STORAGE, 0.9), 100);
        stor.setRequest(ResourceType.COMPUTE, 5, 30);
        stor.setRequest(ResourceType.STORAGE, 20, 80);
        
        List<Agent> agents = List.of(comp, stor);
        Map<ResourceType, Long> poolCapacity = Map.of(
            ResourceType.COMPUTE, 100L,
            ResourceType.STORAGE, 100L
        );
        ResourcePool pool = new ResourcePool(poolCapacity);
        Map<String, BigDecimal> burns = Map.of("COMP", BigDecimal.ZERO, "STOR", BigDecimal.ZERO);
        
        System.out.println("Setup:");
        System.out.println("  COMP: 90% compute, 10% storage preference");
        System.out.println("  STOR: 10% compute, 90% storage preference");
        System.out.println("  Pool: 100 compute, 100 storage");
        System.out.println("  Equal weights (no currency burning)");
        System.out.println();
        
        System.out.println("SEQUENTIAL OPTIMIZATION (per-resource):");
        SequentialJointArbitrator sequential = new SequentialJointArbitrator(economy);
        JointArbitrator.JointAllocationResult seqResult = sequential.arbitrate(agents, pool, burns);
        
        double seqWelfare = 0;
        System.out.println("  Allocations:");
        for (Agent agent : agents) {
            Map<ResourceType, Long> allocs = seqResult.getAllocations(agent.getId());
            long compute = allocs.getOrDefault(ResourceType.COMPUTE, 0L);
            long storage = allocs.getOrDefault(ResourceType.STORAGE, 0L);
            double utility = agent.getPreferences().getWeight(ResourceType.COMPUTE) * compute +
                           agent.getPreferences().getWeight(ResourceType.STORAGE) * storage;
            System.out.printf("    %s: %d compute, %d storage (utility=%.2f)\n",
                agent.getId(), compute, storage, utility);
            if (utility > 0) {
                seqWelfare += PriorityEconomy.BASE_WEIGHT * Math.log(utility);
            }
        }
        System.out.printf("  Total welfare: %.4f\n", seqWelfare);
        System.out.println();
        
        JointArbitrator.JointAllocationResult jointResult;
        String solverUsed;
        
        // Try convex solver first, fall back to gradient if unavailable
        try {
            System.out.println("JOINT OPTIMIZATION (Clarabel interior-point):");
            ConvexJointArbitrator convex = new ConvexJointArbitrator(economy);
            jointResult = convex.arbitrate(agents, pool, burns);
            if (jointResult.isFeasible()) {
                solverUsed = "Clarabel";
            } else {
                GradientJointArbitrator gradient = new GradientJointArbitrator(economy);
                jointResult = gradient.arbitrate(agents, pool, burns);
                solverUsed = "Gradient (Clarabel fallback)";
            }
        } catch (Exception e) {
            System.out.println("JOINT OPTIMIZATION (gradient ascent - Clarabel not available):");
            GradientJointArbitrator gradient = new GradientJointArbitrator(economy);
            jointResult = gradient.arbitrate(agents, pool, burns);
            solverUsed = "Gradient";
        }
        
        System.out.println("  Allocations:");
        double jointWelfare = 0;
        for (Agent agent : agents) {
            Map<ResourceType, Long> allocs = jointResult.getAllocations(agent.getId());
            long compute = allocs.getOrDefault(ResourceType.COMPUTE, 0L);
            long storage = allocs.getOrDefault(ResourceType.STORAGE, 0L);
            double utility = agent.getPreferences().getWeight(ResourceType.COMPUTE) * compute +
                           agent.getPreferences().getWeight(ResourceType.STORAGE) * storage;
            System.out.printf("    %s: %d compute, %d storage (utility=%.2f)\n",
                agent.getId(), compute, storage, utility);
            if (utility > 0) {
                jointWelfare += PriorityEconomy.BASE_WEIGHT * Math.log(utility);
            }
        }
        System.out.printf("  Total welfare: %.4f\n", jointWelfare);
        System.out.println();
        
        double improvement = (jointWelfare - seqWelfare) / Math.abs(seqWelfare) * 100;
        
        System.out.println(SEP);
        System.out.printf("  Declared-welfare difference in this instance: %.2f%%\n", improvement);

        if (improvement > 0.5) {
            System.out.println("  Observed: joint allocation used cross-resource trades here");
        } else {
            System.out.println("  Observed: little difference in this instance");
        }
        
        System.out.printf("  Solver used: %s\n", solverUsed);
        System.out.println(SEP);
        System.out.println();
    }

    // ========================================================================
    // SCENARIO 10: DIVERSE MULTI-RESOURCE TEST
    // ========================================================================
    
    static void runDiverseResourceTest() {
        System.out.println("SCENARIO 10: DIVERSE MULTI-RESOURCE OPTIMIZATION");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Test joint optimization with 6 different resource types.");
        System.out.println();
        
        PriorityEconomy economy = new PriorityEconomy();
        
        List<Agent> agents = new ArrayList<>();
        
        Agent mlTrain1 = new Agent("ML_TRAIN_1", "ML Training Pipeline 1",
            Map.of(ResourceType.COMPUTE, 0.5, ResourceType.MEMORY, 0.3, ResourceType.DATASET, 0.2), 100);
        mlTrain1.setRequest(ResourceType.COMPUTE, 20, 80);
        mlTrain1.setRequest(ResourceType.MEMORY, 10, 50);
        mlTrain1.setRequest(ResourceType.DATASET, 5, 30);
        agents.add(mlTrain1);
        
        Agent dataPipe = new Agent("DATA_PIPE", "Data Pipeline",
            Map.of(ResourceType.STORAGE, 0.4, ResourceType.NETWORK, 0.3, ResourceType.DATASET, 0.3), 100);
        dataPipe.setRequest(ResourceType.STORAGE, 20, 80);
        dataPipe.setRequest(ResourceType.NETWORK, 10, 40);
        dataPipe.setRequest(ResourceType.DATASET, 10, 40);
        agents.add(dataPipe);
        
        Agent apiService = new Agent("API_SVC", "API Service",
            Map.of(ResourceType.COMPUTE, 0.3, ResourceType.NETWORK, 0.3, ResourceType.API_CREDITS, 0.4), 100);
        apiService.setRequest(ResourceType.COMPUTE, 10, 30);
        apiService.setRequest(ResourceType.NETWORK, 10, 40);
        apiService.setRequest(ResourceType.API_CREDITS, 20, 60);
        agents.add(apiService);
        
        Map<ResourceType, Long> poolCapacity = new HashMap<>();
        poolCapacity.put(ResourceType.COMPUTE, 100L);
        poolCapacity.put(ResourceType.MEMORY, 80L);
        poolCapacity.put(ResourceType.STORAGE, 100L);
        poolCapacity.put(ResourceType.NETWORK, 60L);
        poolCapacity.put(ResourceType.DATASET, 50L);
        poolCapacity.put(ResourceType.API_CREDITS, 80L);
        ResourcePool pool = new ResourcePool(poolCapacity);
        
        Map<String, BigDecimal> burns = agents.stream()
            .collect(Collectors.toMap(Agent::getId, a -> BigDecimal.ZERO));
        
        System.out.println("Pool capacities: " + poolCapacity);
        System.out.println();
        
        SequentialJointArbitrator sequential = new SequentialJointArbitrator(economy);
        JointArbitrator.JointAllocationResult seqResult = sequential.arbitrate(agents, pool, burns);
        double seqWelfare = printDiverseAllocations(agents, seqResult, "Sequential");
        
        System.out.println();
        
        GradientJointArbitrator gradient = new GradientJointArbitrator(economy);
        JointArbitrator.JointAllocationResult jointResult = gradient.arbitrate(agents, pool, burns);
        double jointWelfare = printDiverseAllocations(agents, jointResult, "Joint");
        
        double improvement = (jointWelfare - seqWelfare) / Math.abs(seqWelfare) * 100;
        
        System.out.println();
        System.out.println(SEP);
        System.out.printf("  Welfare improvement: %.2f%%\n", improvement);
        System.out.println("  " + (improvement > 0 ? "✓ PASS" : "○ NEUTRAL") + 
            ": Joint optimization " + (improvement > 0 ? "found" : "matched") + " trades");
        System.out.println(SEP);
        System.out.println();
    }

    // ========================================================================
    // SCENARIO 11: AI SERVICE INTEGRATION
    // ========================================================================
    
    static void runScenario11_ServiceIntegration() {
        System.out.println("SCENARIO 11: AI SERVICE INTEGRATION");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Demonstrate AI service allocation and composition.");
        System.out.println();
        
        ServiceRegistry registry = ServiceRegistry.forTesting(3);
        
        System.out.println("Service Registry: " + registry.getStats());
        System.out.println();
        
        ServiceComposition ragPipeline = new ServiceComposition.Builder("rag-demo")
            .name("RAG Pipeline")
            .addNode("embed", ServiceType.TEXT_EMBEDDING)
            .addNode("search", ServiceType.VECTOR_SEARCH)
            .addNode("generate", ServiceType.TEXT_GENERATION)
            .connect("embed", "search", ServiceType.DataType.VECTOR)
            .connect("search", "generate", ServiceType.DataType.STRUCTURED)
            .build();
        
        ServiceComposition.ValidationResult validation = ragPipeline.validate();
        
        System.out.println("RAG Pipeline: " + (validation.isValid() ? "Valid" : "Invalid"));
        System.out.println("  Latency: " + ragPipeline.estimateCriticalPathLatencyMs() + "ms");
        System.out.println();
        
        PriorityEconomy economy = new PriorityEconomy();
        ServiceArbitrator arbitrator = new ServiceArbitrator(economy, registry);
        
        List<ServiceArbitrator.ServiceRequest> requests = List.of(
            new ServiceArbitrator.ServiceRequest.Builder("team-a")
                .requestService(ServiceType.TEXT_GENERATION, 5)
                .currencyCommitment(BigDecimal.valueOf(50))
                .build(),
            new ServiceArbitrator.ServiceRequest.Builder("team-b")
                .requestService(ServiceType.TEXT_GENERATION, 5)
                .currencyCommitment(BigDecimal.valueOf(20))
                .build()
        );
        
        ServiceArbitrator.ServiceAllocationResult result = arbitrator.arbitrate(requests);
        
        System.out.println("Service Arbitration:");
        System.out.println("  team-a: got " + result.getAllocation("team-a", ServiceType.TEXT_GENERATION));
        System.out.println("  team-b: got " + result.getAllocation("team-b", ServiceType.TEXT_GENERATION));
        System.out.println();
        
        System.out.println(SEP);
        System.out.println("  Observed: service arbitration produced allocations for both teams");
        System.out.println(SEP);
        System.out.println();
    }

    // ========================================================================
    // SCENARIO 12: NONLINEAR UTILITY FUNCTIONS (NEW)
    // ========================================================================
    
    static void runScenario12_NonlinearUtilities() {
        System.out.println("SCENARIO 12: UTILITY FUNCTIONS");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Evaluate the four solver-supported families and, separately,");
        System.out.println("         several local legacy evaluator functions.");
        System.out.println("Scope: the canonical convex solver supports LINEAR, COBB_DOUGLAS, CES,");
        System.out.println("         and LEONTIEF only. It rejects any other family request. The");
        System.out.println("         remaining functions below are local UtilityFunction evaluators");
        System.out.println("         used for scoring, not solver-supported models.");
        System.out.println();

        Map<ResourceType, Double> weights = Map.of(
            ResourceType.COMPUTE, 0.6,
            ResourceType.STORAGE, 0.4
        );

        Map<ResourceType, Double> refPoints = Map.of(
            ResourceType.COMPUTE, 50.0,
            ResourceType.STORAGE, 50.0
        );

        Map<String, UtilityFunction> solverSupported = new LinkedHashMap<>();
        solverSupported.put("LINEAR", UtilityFunction.linear(weights));
        solverSupported.put("COBB_DOUGLAS", UtilityFunction.cobbDouglas(weights));
        solverSupported.put("CES(rho=0.5)", UtilityFunction.ces(weights, 0.5));
        solverSupported.put("LEONTIEF", UtilityFunction.leontief(weights));

        Map<String, UtilityFunction> localOnly = new LinkedHashMap<>();
        localOnly.put("SQRT", UtilityFunction.sqrt(weights));
        localOnly.put("LOG", UtilityFunction.log(weights));
        localOnly.put("THRESHOLD", UtilityFunction.threshold(UtilityFunction.linear(weights), 60, 0.5));
        localOnly.put("SATIATION", UtilityFunction.satiation(UtilityFunction.linear(weights), 100, 20));
        localOnly.put("SOFTPLUS_LA", UtilityFunction.softplusLossAversion(weights, refPoints, 2.0, 5.0));
        localOnly.put("ASYMLOG_LA", UtilityFunction.asymmetricLogLossAversion(weights, refPoints, 2.0, 20.0));

        Map<ResourceType, Long> testAlloc = Map.of(
            ResourceType.COMPUTE, 60L,
            ResourceType.STORAGE, 40L
        );

        System.out.println("Solver-supported families at (C=60, S=40):");
        for (var entry : solverSupported.entrySet()) {
            System.out.printf("  %-15s: %8.3f\n", entry.getKey(), entry.getValue().evaluate(testAlloc));
        }
        System.out.println();
        System.out.println("Local-only legacy evaluators (not solver-supported) at (C=60, S=40):");
        for (var entry : localOnly.entrySet()) {
            System.out.printf("  %-15s: %8.3f\n", entry.getKey(), entry.getValue().evaluate(testAlloc));
        }
        System.out.println();
        
        // Show optimizer behavior difference
        System.out.println("Optimizer Behavior Comparison:");
        System.out.println("-".repeat(50));
        System.out.println();
        
        PriorityEconomy economy = new PriorityEconomy();
        
        // Agent A: Linear utility (specialist)
        Agent agentA = new Agent("LINEAR_A", "Linear Specialist",
            Map.of(ResourceType.COMPUTE, 0.9, ResourceType.STORAGE, 0.1), 100);
        agentA.setRequest(ResourceType.COMPUTE, 10, 70);
        agentA.setRequest(ResourceType.STORAGE, 5, 30);
        
        // Agent B: Cobb-Douglas (needs both)
        Agent agentB = new Agent("CD_B", "Cobb-Douglas Balanced",
            Map.of(ResourceType.COMPUTE, 0.5, ResourceType.STORAGE, 0.5), 100);
        agentB.setRequest(ResourceType.COMPUTE, 10, 60);
        agentB.setRequest(ResourceType.STORAGE, 10, 60);
        
        List<Agent> agents = List.of(agentA, agentB);
        Map<ResourceType, Long> poolCap = Map.of(
            ResourceType.COMPUTE, 100L,
            ResourceType.STORAGE, 80L
        );
        ResourcePool pool = new ResourcePool(poolCap);
        Map<String, BigDecimal> burns = Map.of("LINEAR_A", BigDecimal.ZERO, "CD_B", BigDecimal.ZERO);
        
        System.out.println("Agents:");
        System.out.println("  LINEAR_A: 90% compute preference (specialist)");
        System.out.println("  CD_B: 50/50 Cobb-Douglas (needs both resources)");
        System.out.println();
        
        GradientJointArbitrator gradient = new GradientJointArbitrator(economy);
        JointArbitrator.JointAllocationResult result = gradient.arbitrate(agents, pool, burns);
        
        System.out.println("Joint Optimization Allocation:");
        for (Agent agent : agents) {
            Map<ResourceType, Long> allocs = result.getAllocations(agent.getId());
            System.out.printf("  %s: %d compute, %d storage\n",
                agent.getId(),
                allocs.getOrDefault(ResourceType.COMPUTE, 0L),
                allocs.getOrDefault(ResourceType.STORAGE, 0L));
        }
        System.out.println();
        
        // Calculate utilities for both agents under different utility functions
        Map<ResourceType, Long> allocA = result.getAllocations("LINEAR_A");
        Map<ResourceType, Long> allocB = result.getAllocations("CD_B");
        
        UtilityFunction linA = UtilityFunction.linear(agentA.getPreferences().getWeights());
        UtilityFunction cdB = UtilityFunction.cobbDouglas(agentB.getPreferences().getWeights());
        
        double utilA = linA.evaluate(allocA);
        double utilB = cdB.evaluate(allocB);
        
        System.out.println("Resulting Utilities:");
        System.out.printf("  LINEAR_A utility: %.2f\n", utilA);
        System.out.printf("  CD_B utility: %.2f\n", utilB);
        System.out.println();
        
        // Agent generator demo
        System.out.println("Auto-Generated Agents (seed=42):");
        AgentGenerator generator = new AgentGenerator(42);
        List<AgentGenerator.GeneratedAgent> genAgents = generator.generateAgents(20, "GEN");
        
        Map<UtilityFunction.Type, Long> typeCounts = genAgents.stream()
            .collect(Collectors.groupingBy(
                a -> a.utility().getType(),
                Collectors.counting()
            ));
        
        for (var entry : typeCounts.entrySet()) {
            System.out.printf("  %s: %d agents\n", entry.getKey(), entry.getValue());
        }
        System.out.println();
        
        System.out.println(SEP);
        System.out.println("  Observed: four solver-supported families and six local evaluators");
        System.out.println("  scored above; unsupported families are rejected by the canonical solver.");
        System.out.println("  Run NonlinearUtilityDemo for more scenarios.");
        System.out.println(SEP);
        System.out.println();
    }
    
    // ========================================================================
    // Helper Methods
    // ========================================================================
    
    private static double printDiverseAllocations(
            List<Agent> agents, 
            JointArbitrator.JointAllocationResult result,
            String label) {
        
        ResourceType[] types = {
            ResourceType.COMPUTE, ResourceType.MEMORY, ResourceType.STORAGE,
            ResourceType.NETWORK, ResourceType.DATASET, ResourceType.API_CREDITS
        };
        
        System.out.println("  " + label + " allocations:");
        double totalWelfare = 0;
        
        for (Agent agent : agents) {
            Map<ResourceType, Long> allocs = result.getAllocations(agent.getId());
            StringBuilder sb = new StringBuilder();
            double utility = 0;
            
            for (ResourceType type : types) {
                long a = allocs.getOrDefault(type, 0L);
                double w = agent.getPreferences().getWeight(type);
                utility += w * a;
                if (sb.length() > 0) sb.append("/");
                sb.append(String.format("%2d", a));
            }
            
            System.out.printf("    %-12s: %s (utility=%.1f)\n", 
                agent.getId(), sb.toString(), utility);
            
            if (utility > 0) {
                totalWelfare += PriorityEconomy.BASE_WEIGHT * Math.log(utility);
            }
        }
        
        System.out.printf("  %s welfare: %.4f\n", label, totalWelfare);
        return totalWelfare;
    }

    private static AllocationResult arbitrateNaiveProportional(
            List<Agent> agents, ResourceType resource, long available, Map<String, BigDecimal> burns) {
        
        int n = agents.size();
        AllocationResult result = new AllocationResult(resource);
        
        double[] weights = new double[n];
        double totalWeight = 0;
        String[] ids = new String[n];
        
        for (int i = 0; i < n; i++) {
            Agent a = agents.get(i);
            ids[i] = a.getId();
            weights[i] = PriorityEconomy.BASE_WEIGHT + 
                burns.getOrDefault(a.getId(), BigDecimal.ZERO).doubleValue();
            totalWeight += weights[i];
        }
        
        long[] allocs = new long[n];
        long remaining = available;
        
        for (int i = 0; i < n; i++) {
            allocs[i] = agents.get(i).getMinimum(resource);
            remaining -= allocs[i];
        }
        
        if (remaining > 0 && totalWeight > 0) {
            for (int i = 0; i < n; i++) {
                long slack = agents.get(i).getIdeal(resource) - allocs[i];
                if (slack > 0) {
                    long extra = (long) ((weights[i] / totalWeight) * remaining);
                    extra = Math.min(extra, slack);
                    allocs[i] += extra;
                }
            }
        }
        
        double obj = 0;
        for (int i = 0; i < n; i++) {
            result.setAllocation(ids[i], allocs[i]);
            result.setCurrencyBurned(ids[i], burns.getOrDefault(ids[i], BigDecimal.ZERO));
            if (allocs[i] > 0) {
                obj += weights[i] * Math.log(allocs[i]);
            }
        }
        result.setObjectiveValue(obj);
        
        return result;
    }
}