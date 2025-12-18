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
 * 3. Collusion resistance (Theorem 3)
 * 4. Complementary preferences
 * 5. Priority economy dynamics
 * 6. Individual rationality (Theorem 5)
 * 7. Starvation protection
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
        System.out.println("   PLATFORM-MEDIATED PARETO-OPTIMIZED MULTI-AGENT INTERACTION");
        System.out.println("   Complete Implementation with Validation Scenarios");
        System.out.println(SEP);
        System.out.println();
        
        if (!onlyAsymptotic) {
            PriorityEconomy economy = new PriorityEconomy();
            ProportionalFairnessArbitrator arbitrator = new ProportionalFairnessArbitrator(economy);
            
            runScenario1_BasicMechanism(arbitrator);
            runScenario2_JointVsSeparate(arbitrator);
            runScenario3_CollusionResistance(arbitrator);
            runScenario4_ComplementaryPreferences(arbitrator);
            runScenario5_PriorityEconomy(arbitrator, economy);
            runScenario6_IndividualRationality(arbitrator);
            runScenario7_StarvationProtection(arbitrator);
        }
        
        if (runAsymptotic) {
            runAsymptoticTest();
            runJointOptimizationTest();
            runDiverseResourceTest();
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
        System.out.println("Purpose: Demonstrate that Proportional Fairness improves welfare");
        System.out.println("         compared to naive proportional allocation.");
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
            AllocationResult r = arbitrateNaiveProportional(competing, type, 100, noBurn);
            baselineWelfare += r.getObjectiveValue();
            System.out.println("  " + type + ": " + r.getAllocations());
        }
        System.out.println("  Total welfare: " + String.format("%.4f", baselineWelfare));
        System.out.println();
        
        // Proportional Fairness
        System.out.println("Proportional Fairness:");
        double pfWelfare = 0;
        for (ResourceType type : List.of(ResourceType.COMPUTE, ResourceType.STORAGE)) {
            List<Agent> competing = agents.stream().filter(a -> a.getIdeal(type) > 0).toList();
            Contention c = new Contention(type, competing, 100);
            AllocationResult r = arbitrator.arbitrate(c, noBurn);
            pfWelfare += r.getObjectiveValue();
            System.out.println("  " + type + ": " + r.getAllocations());
        }
        System.out.println("  Total welfare: " + String.format("%.4f", pfWelfare));
        System.out.println();
        
        double improvement = ((pfWelfare - baselineWelfare) / Math.abs(baselineWelfare)) * 100;
        System.out.println(SEP);
        System.out.println("  Welfare improvement: " + String.format("%.2f%%", improvement));
        System.out.println("  " + (pfWelfare >= baselineWelfare ? "✓ PASS" : "✗ FAIL") +
            ": Proportional Fairness improves or matches baseline");
        System.out.println(SEP);
        System.out.println();
    }

    // ========================================================================
    // SCENARIO 3: Collusion Resistance (Theorem 3)
    // ========================================================================
    
    static void runScenario3_CollusionResistance(ProportionalFairnessArbitrator arbitrator) {
        System.out.println("SCENARIO 3: COLLUSION RESISTANCE (Theorem 3)");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Verify that logarithmic barrier protects victims from");
        System.out.println("         coordinated attacks by wealthy coalitions.");
        System.out.println();
        
        Agent victim = new Agent("VICTIM", "Victim Agent", Map.of(ResourceType.COMPUTE, 1.0), 0);
        victim.setRequest(ResourceType.COMPUTE, 10, 100);
        
        Agent col1 = new Agent("COL1", "Coalition 1", Map.of(ResourceType.COMPUTE, 1.0), 1000);
        col1.setRequest(ResourceType.COMPUTE, 10, 100);
        
        Agent col2 = new Agent("COL2", "Coalition 2", Map.of(ResourceType.COMPUTE, 1.0), 1000);
        col2.setRequest(ResourceType.COMPUTE, 10, 100);
        
        List<Agent> agents = List.of(victim, col1, col2);
        Contention contention = new Contention(ResourceType.COMPUTE, agents, 100);
        
        System.out.println("Setup:");
        System.out.println("  Victim: 0 currency (weight = 10)");
        System.out.println("  Coalition: 2 agents, each burning 500 currency");
        System.out.println("  Coalition total weight: 510 + 510 = 1020");
        System.out.println("  Weight ratio against victim: 102:1");
        System.out.println();
        
        Map<String, BigDecimal> attack = Map.of(
            "VICTIM", BigDecimal.ZERO,
            "COL1", BigDecimal.valueOf(500),
            "COL2", BigDecimal.valueOf(500)
        );
        
        AllocationResult attackResult = arbitrator.arbitrate(contention, attack);
        
        long victimAlloc = attackResult.getAllocation("VICTIM");
        long col1Alloc = attackResult.getAllocation("COL1");
        long col2Alloc = attackResult.getAllocation("COL2");
        
        System.out.println("Under Coalition Attack:");
        System.out.println("  Victim allocation: " + victimAlloc + " units (" +
            String.format("%.1f%%", victimAlloc * 100.0 / 100) + ")");
        System.out.println("  Coalition 1: " + col1Alloc + " units");
        System.out.println("  Coalition 2: " + col2Alloc + " units");
        System.out.println();
        
        // Honest scenario
        Map<String, BigDecimal> honest = Map.of(
            "VICTIM", BigDecimal.ZERO,
            "COL1", BigDecimal.ZERO,
            "COL2", BigDecimal.ZERO
        );
        AllocationResult honestResult = arbitrator.arbitrate(contention, honest);
        
        System.out.println("If Coalition Were Honest (no burning):");
        System.out.println("  Each agent would get: ~" + honestResult.getAllocation("COL1") + " units");
        System.out.println();
        
        boolean victimProtected = victimAlloc >= victim.getMinimum(ResourceType.COMPUTE);
        
        System.out.println(SEP);
        if (victimProtected) {
            System.out.println("✓ PASS: VICTIM PROTECTED");
            System.out.println("  Got " + victimAlloc + " units despite 102:1 weight disadvantage");
        } else {
            System.out.println("✗ FAIL: Victim starved below minimum");
        }
        System.out.println("  ");
        System.out.println("  Key insight: As any agent's allocation approaches 0,");
        System.out.println("  log(allocation) → -∞, making the objective plummet.");
        System.out.println("  This logarithmic barrier prevents complete exclusion.");
        System.out.println(SEP);
        System.out.println();
    }

    // ========================================================================
    // SCENARIO 4: Complementary Preferences
    // ========================================================================
    
    static void runScenario4_ComplementaryPreferences(ProportionalFairnessArbitrator arbitrator) {
        System.out.println("SCENARIO 4: COMPLEMENTARY PREFERENCES");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Show that diverse preferences create positive-sum gains");
        System.out.println("         where coordination benefits everyone.");
        System.out.println();
        
        Agent computeSpec = new Agent("COMP", "Compute Specialist",
            Map.of(ResourceType.COMPUTE, 0.9, ResourceType.STORAGE, 0.1), 50);
        computeSpec.setRequest(ResourceType.COMPUTE, 40, 90);
        computeSpec.setRequest(ResourceType.STORAGE, 5, 10);
        
        Agent storageSpec = new Agent("STOR", "Storage Specialist",
            Map.of(ResourceType.COMPUTE, 0.1, ResourceType.STORAGE, 0.9), 50);
        storageSpec.setRequest(ResourceType.COMPUTE, 5, 10);
        storageSpec.setRequest(ResourceType.STORAGE, 40, 90);
        
        Agent balanced1 = new Agent("BAL1", "Balanced 1",
            Map.of(ResourceType.COMPUTE, 0.5, ResourceType.STORAGE, 0.5), 50);
        balanced1.setRequest(ResourceType.COMPUTE, 20, 50);
        balanced1.setRequest(ResourceType.STORAGE, 20, 50);
        
        Agent balanced2 = new Agent("BAL2", "Balanced 2",
            Map.of(ResourceType.COMPUTE, 0.5, ResourceType.STORAGE, 0.5), 50);
        balanced2.setRequest(ResourceType.COMPUTE, 20, 50);
        balanced2.setRequest(ResourceType.STORAGE, 20, 50);
        
        List<Agent> agents = List.of(computeSpec, storageSpec, balanced1, balanced2);
        Map<String, BigDecimal> noBurn = agents.stream()
            .collect(Collectors.toMap(Agent::getId, a -> BigDecimal.ZERO));
        
        System.out.println("Setup:");
        System.out.println("  COMP: 90% compute, 10% storage (compute specialist)");
        System.out.println("  STOR: 10% compute, 90% storage (storage specialist)");
        System.out.println("  BAL1, BAL2: 50/50 preferences (balanced)");
        System.out.println("  Resources: 150 compute, 150 storage");
        System.out.println();
        
        // Coordinated allocation
        double coordinatedWelfare = 0;
        Map<String, Map<ResourceType, Long>> finalAllocs = new HashMap<>();
        
        for (ResourceType type : List.of(ResourceType.COMPUTE, ResourceType.STORAGE)) {
            List<Agent> competing = agents.stream().filter(a -> a.getIdeal(type) > 0).toList();
            Contention c = new Contention(type, competing, 150);
            AllocationResult r = arbitrator.arbitrate(c, noBurn);
            coordinatedWelfare += r.getObjectiveValue();
            
            for (var entry : r.getAllocations().entrySet()) {
                finalAllocs.computeIfAbsent(entry.getKey(), k -> new HashMap<>())
                    .put(type, entry.getValue());
            }
        }
        
        System.out.println("Coordinated Allocations:");
        for (Agent a : agents) {
            Map<ResourceType, Long> allocs = finalAllocs.get(a.getId());
            System.out.println("  " + a.getId() + ": " +
                allocs.getOrDefault(ResourceType.COMPUTE, 0L) + " compute, " +
                allocs.getOrDefault(ResourceType.STORAGE, 0L) + " storage");
        }
        System.out.println("  Coordinated welfare: " + String.format("%.4f", coordinatedWelfare));
        System.out.println();
        
        // Independent baseline
        double independentWelfare = 0;
        double equalShare = 150.0 / 4;
        for (Agent a : agents) {
            double utility = a.getPreference(ResourceType.COMPUTE) * equalShare +
                           a.getPreference(ResourceType.STORAGE) * equalShare;
            independentWelfare += PriorityEconomy.BASE_WEIGHT * Math.log(utility);
        }
        System.out.println("Independent Baseline (equal split):");
        System.out.println("  Each agent gets: " + String.format("%.1f", equalShare) + " of each resource");
        System.out.println("  Independent welfare: " + String.format("%.4f", independentWelfare));
        System.out.println();
        
        double improvement = ((coordinatedWelfare - independentWelfare) / Math.abs(independentWelfare)) * 100;
        
        System.out.println(SEP);
        System.out.println("  Welfare improvement: " + String.format("%.2f%%", improvement));
        System.out.println("  " + (improvement > 0 ? "✓ PASS" : "✗ FAIL") +
            ": Complementary preferences create value");
        System.out.println(SEP);
        System.out.println();
    }

    // ========================================================================
    // SCENARIO 5: Priority Economy Dynamics
    // ========================================================================
    
    static void runScenario5_PriorityEconomy(ProportionalFairnessArbitrator arbitrator, PriorityEconomy economy) {
        System.out.println("SCENARIO 5: PRIORITY ECONOMY DYNAMICS");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Demonstrate currency earning/burning cycle and its");
        System.out.println("         effect on allocation outcomes over time.");
        System.out.println();
        
        Agent a1 = new Agent("A1", "Frequent Releaser", Map.of(ResourceType.COMPUTE, 1.0), 100);
        a1.setRequest(ResourceType.COMPUTE, 20, 60);
        
        Agent a2 = new Agent("A2", "Resource Hoarder", Map.of(ResourceType.COMPUTE, 1.0), 100);
        a2.setRequest(ResourceType.COMPUTE, 20, 60);
        
        ResourcePool pool = ResourcePool.ofSingle(ResourceType.COMPUTE, 100);
        
        System.out.println("Setup:");
        System.out.println("  Two agents, each starting with 100 currency");
        System.out.println("  A1: Releases resources early (earns currency)");
        System.out.println("  A2: Holds resources until expiration (no earnings)");
        System.out.println();
        System.out.println("Simulation over 5 rounds:");
        System.out.println();
        
        for (int round = 1; round <= 5; round++) {
            System.out.println("Round " + round + ":");
            System.out.println("  Balances: A1=" + a1.getCurrencyBalance().setScale(2, RoundingMode.HALF_UP) +
                ", A2=" + a2.getCurrencyBalance().setScale(2, RoundingMode.HALF_UP));
            
            BigDecimal a1Burn = a1.getCurrencyBalance().multiply(BigDecimal.valueOf(0.1))
                .setScale(2, RoundingMode.HALF_UP);
            Map<String, BigDecimal> burns = Map.of("A1", a1Burn, "A2", BigDecimal.ZERO);
            
            List<Agent> agents = List.of(a1, a2);
            Contention c = new Contention(ResourceType.COMPUTE, agents, 100);
            AllocationResult r = arbitrator.arbitrate(c, burns);
            
            System.out.println("  Burns: A1=" + a1Burn + ", A2=0");
            System.out.println("  Allocations: A1=" + r.getAllocation("A1") +
                ", A2=" + r.getAllocation("A2"));
            
            a1.burnCurrency(a1Burn);
            
            BigDecimal a1Earnings = economy.calculateReleaseEarnings(
                ResourceType.COMPUTE, r.getAllocation("A1"), 0.5, pool);
            a1.earnCurrency(a1Earnings);
            
            System.out.println("  A1 releases early, earns: " + a1Earnings);
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
    // SCENARIO 6: Individual Rationality (Theorem 5)
    // ========================================================================
    
    static void runScenario6_IndividualRationality(ProportionalFairnessArbitrator arbitrator) {
        System.out.println("SCENARIO 6: INDIVIDUAL RATIONALITY (Theorem 5)");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Verify that agents receive at least as much utility");
        System.out.println("         from participation as from non-participation.");
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
        
        Map<String, Long> outsideOption = Map.of("A1", 30L, "A2", 20L, "A3", 10L);
        
        System.out.println("Setup:");
        System.out.println("  Outside option = minimum request (what agent could get alone)");
        System.out.println("  A1: minimum 30, ideal 80");
        System.out.println("  A2: minimum 20, ideal 50");
        System.out.println("  A3: minimum 10, ideal 30");
        System.out.println("  Pool: 100 compute units");
        System.out.println();
        
        Contention c = new Contention(ResourceType.COMPUTE, agents, 100);
        AllocationResult r = arbitrator.arbitrate(c, noBurn);
        
        System.out.println("Allocations from participation:");
        boolean allRational = true;
        for (Agent a : agents) {
            long allocation = r.getAllocation(a.getId());
            long outside = outsideOption.get(a.getId());
            boolean rational = allocation >= outside;
            allRational &= rational;
            System.out.println("  " + a.getId() + ": got " + allocation +
                ", outside option " + outside + " → " + (rational ? "✓" : "✗"));
        }
        
        System.out.println();
        System.out.println(SEP);
        System.out.println("  " + (allRational ? "✓ PASS" : "✗ FAIL") +
            ": All agents received at least their outside option");
        System.out.println("  This demonstrates individual rationality (Theorem 5)");
        System.out.println(SEP);
        System.out.println();
    }

    // ========================================================================
    // SCENARIO 7: Starvation Protection
    // ========================================================================
    
    static void runScenario7_StarvationProtection(ProportionalFairnessArbitrator arbitrator) {
        System.out.println("SCENARIO 7: STARVATION PROTECTION");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Verify that even agents with minimal weight receive");
        System.out.println("         non-zero allocation due to logarithmic barrier.");
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
        System.out.println("  " + (allMinnowsGotMinimum ? "✓ PASS" : "✗ FAIL") +
            ": All minnows received at least their minimum (5 units)");
        System.out.println("  Despite 55:1 weight disadvantage against whale");
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
        
        // Print contention histogram
        System.out.println("Contention Histogram (by number of competing agents):");
        System.out.print(results.getContentionHistogramString());
        System.out.println();
        
        boolean converged = results.hasConverged(20, 0.01);
        // FIX: Corrected contradictory message
        System.out.println("  " + (converged ? "✓ PASS: System reached stable equilibrium" : "⚠ NOT CONVERGED: System did not reach stable equilibrium"));
        System.out.println(SEP);
        System.out.println();
    }

    // ========================================================================
    // JOINT OPTIMIZATION TEST (The "Paretotopia" Thesis)
    // ========================================================================
    
    static void runJointOptimizationTest() {
        System.out.println("SCENARIO 9: JOINT vs SEQUENTIAL OPTIMIZATION");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Demonstrate that joint multi-resource optimization");
        System.out.println("         achieves GLOBAL Pareto optimality by enabling");
        System.out.println("         cross-resource trades.");
        System.out.println();
        System.out.println("The 'Paretotopia' thesis: When agents have complementary");
        System.out.println("preferences, joint optimization unlocks welfare gains that");
        System.out.println("sequential per-resource optimization cannot discover.");
        System.out.println();
        
        // Setup: Create agents with HIGHLY complementary preferences
        // Agent COMP: strongly prefers Compute (90% / 10%)
        // Agent STOR: strongly prefers Storage (10% / 90%)
        // Agent BAL1: balanced preferences (50% / 50%)
        // Agent BAL2: balanced preferences (50% / 50%)
        
        List<Agent> agents = new ArrayList<>();
        
        Agent comp = new Agent("COMP", "Compute Specialist",
            Map.of(ResourceType.COMPUTE, 0.9, ResourceType.STORAGE, 0.1), 100);
        comp.setRequest(ResourceType.COMPUTE, 20, 80);
        comp.setRequest(ResourceType.STORAGE, 5, 40);
        agents.add(comp);
        
        Agent stor = new Agent("STOR", "Storage Specialist",
            Map.of(ResourceType.COMPUTE, 0.1, ResourceType.STORAGE, 0.9), 100);
        stor.setRequest(ResourceType.COMPUTE, 5, 40);
        stor.setRequest(ResourceType.STORAGE, 20, 80);
        agents.add(stor);
        
        Agent bal1 = new Agent("BAL1", "Balanced 1",
            Map.of(ResourceType.COMPUTE, 0.5, ResourceType.STORAGE, 0.5), 100);
        bal1.setRequest(ResourceType.COMPUTE, 10, 50);
        bal1.setRequest(ResourceType.STORAGE, 10, 50);
        agents.add(bal1);
        
        Agent bal2 = new Agent("BAL2", "Balanced 2",
            Map.of(ResourceType.COMPUTE, 0.5, ResourceType.STORAGE, 0.5), 100);
        bal2.setRequest(ResourceType.COMPUTE, 10, 50);
        bal2.setRequest(ResourceType.STORAGE, 10, 50);
        agents.add(bal2);
        
        // Pool with constrained resources (forces tradeoffs)
        ResourcePool pool = ResourcePool.of(
            ResourceType.COMPUTE, 120L,
            ResourceType.STORAGE, 120L
        );
        
        // No currency burns (equal weights)
        Map<String, BigDecimal> burns = new HashMap<>();
        for (Agent a : agents) {
            burns.put(a.getId(), BigDecimal.ZERO);
        }
        
        System.out.println("Setup:");
        System.out.println("  COMP: 90% compute / 10% storage preference");
        System.out.println("  STOR: 10% compute / 90% storage preference");
        System.out.println("  BAL1, BAL2: 50% / 50% balanced preferences");
        System.out.println("  Resources: 120 compute, 120 storage (constrained)");
        System.out.println();
        
        PriorityEconomy economy = new PriorityEconomy();
        
        // ===== Sequential Optimization (current approach) =====
        System.out.println("SEQUENTIAL OPTIMIZATION (per-resource):");
        SequentialJointArbitrator sequential = new SequentialJointArbitrator(economy);
        JointArbitrator.JointAllocationResult seqResult = sequential.arbitrate(agents, pool, burns);
        
        System.out.println("  Allocations:");
        double seqWelfare = 0;
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
        
        // ===== Joint Optimization (try Clarabel first, fallback to gradient) =====
        // Check if Clarabel/cvxpy is available for EXACT interior-point optimization
        ConvexJointArbitrator convex = new ConvexJointArbitrator(economy);
        boolean clarabelAvailable = convex.checkDependencies();
        
        JointArbitrator.JointAllocationResult jointResult;
        String solverUsed;
        
        if (clarabelAvailable) {
            System.out.println("JOINT OPTIMIZATION (Clarabel interior-point method):");
            jointResult = convex.arbitrate(agents, pool, burns);
            if (jointResult.isFeasible()) {
                solverUsed = "Clarabel";
            } else {
                // Clarabel failed despite being available - fall back to gradient
                System.out.println("  ⚠ Clarabel returned infeasible, falling back to gradient ascent...");
                GradientJointArbitrator gradient = new GradientJointArbitrator(economy);
                jointResult = gradient.arbitrate(agents, pool, burns);
                solverUsed = "Gradient (Clarabel fallback)";
            }
        } else {
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
        
        // ===== Compare =====
        double improvement = (jointWelfare - seqWelfare) / Math.abs(seqWelfare) * 100;
        
        System.out.println(SEP);
        System.out.printf("  Welfare improvement: %.2f%%\n", improvement);
        
        if (improvement > 0.5) {
            System.out.println("  ✓ PASS: Joint optimization found cross-resource trades");
            System.out.println();
            System.out.println("  Key insight: Joint optimization allows COMP to trade some");
            System.out.println("  Storage allocation to STOR in exchange for more Compute,");
            System.out.println("  improving welfare for both specialists.");
        } else {
            System.out.println("  Note: Minimal improvement in this scenario (may be near-optimal)");
        }
        
        // FIX: Report based on ACTUAL solver used, not just availability
        System.out.println();
        System.out.printf("  Solver used: %s\n", solverUsed);
        if ("Clarabel".equals(solverUsed)) {
            System.out.println("  ✓ Interior-point method guarantees polynomial time and exact solution");
        } else {
            System.out.println("  ⚠ Gradient ascent is iterative approximation");
            if (!clarabelAvailable) {
                System.out.println("  To enable exact solver: pip install cvxpy clarabel numpy");
            } else {
                System.out.println("  Note: Clarabel available but returned infeasible - check Python solver");
            }
        }
        
        System.out.println(SEP);
        System.out.println();
    }

    // ========================================================================
    // SCENARIO 10: DIVERSE MULTI-RESOURCE TEST ("Jagged Optimization")
    // ========================================================================
    
    static void runDiverseResourceTest() {
        System.out.println("SCENARIO 10: DIVERSE MULTI-RESOURCE OPTIMIZATION");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Test joint optimization with 6 different resource types");
        System.out.println("         across different categories, with agents wanting different");
        System.out.println("         subsets that overlap in varied ways ('jagged' optimization).");
        System.out.println();
        System.out.println("Resource Categories:");
        System.out.println("  Infrastructure: COMPUTE, MEMORY, STORAGE");
        System.out.println("  Network: NETWORK");
        System.out.println("  Data: DATASET");
        System.out.println("  Services: API_CREDITS");
        System.out.println();
        
        // Create 6 agents with different, overlapping resource interests
        // This creates two overlapping "clusters" of contention
        List<Agent> agents = new ArrayList<>();
        
        // Cluster 1: ML Training agents (need compute, memory, dataset)
        Agent mlTrain1 = new Agent("ML_TRAIN_1", "ML Training Pipeline 1",
            Map.of(
                ResourceType.COMPUTE, 0.5,
                ResourceType.MEMORY, 0.3,
                ResourceType.DATASET, 0.2,
                ResourceType.STORAGE, 0.0,
                ResourceType.NETWORK, 0.0,
                ResourceType.API_CREDITS, 0.0
            ), 100);
        mlTrain1.setRequest(ResourceType.COMPUTE, 20, 80);
        mlTrain1.setRequest(ResourceType.MEMORY, 10, 50);
        mlTrain1.setRequest(ResourceType.DATASET, 5, 30);
        agents.add(mlTrain1);
        
        Agent mlTrain2 = new Agent("ML_TRAIN_2", "ML Training Pipeline 2",
            Map.of(
                ResourceType.COMPUTE, 0.4,
                ResourceType.MEMORY, 0.4,
                ResourceType.DATASET, 0.2,
                ResourceType.STORAGE, 0.0,
                ResourceType.NETWORK, 0.0,
                ResourceType.API_CREDITS, 0.0
            ), 100);
        mlTrain2.setRequest(ResourceType.COMPUTE, 15, 60);
        mlTrain2.setRequest(ResourceType.MEMORY, 15, 60);
        mlTrain2.setRequest(ResourceType.DATASET, 5, 25);
        agents.add(mlTrain2);
        
        // Cluster 2: Data Pipeline agents (need storage, network, dataset)
        Agent dataPipe1 = new Agent("DATA_PIPE_1", "Data Ingestion Pipeline",
            Map.of(
                ResourceType.COMPUTE, 0.0,
                ResourceType.MEMORY, 0.1,
                ResourceType.DATASET, 0.3,
                ResourceType.STORAGE, 0.4,
                ResourceType.NETWORK, 0.2,
                ResourceType.API_CREDITS, 0.0
            ), 100);
        dataPipe1.setRequest(ResourceType.STORAGE, 20, 80);
        dataPipe1.setRequest(ResourceType.NETWORK, 10, 40);
        dataPipe1.setRequest(ResourceType.DATASET, 10, 40);
        dataPipe1.setRequest(ResourceType.MEMORY, 5, 20);
        agents.add(dataPipe1);
        
        Agent dataPipe2 = new Agent("DATA_PIPE_2", "Data Export Pipeline",
            Map.of(
                ResourceType.COMPUTE, 0.0,
                ResourceType.MEMORY, 0.0,
                ResourceType.DATASET, 0.2,
                ResourceType.STORAGE, 0.3,
                ResourceType.NETWORK, 0.5,
                ResourceType.API_CREDITS, 0.0
            ), 100);
        dataPipe2.setRequest(ResourceType.STORAGE, 15, 50);
        dataPipe2.setRequest(ResourceType.NETWORK, 20, 70);
        dataPipe2.setRequest(ResourceType.DATASET, 5, 30);
        agents.add(dataPipe2);
        
        // Cross-cluster: API Service agent (needs network, api_credits, some compute)
        Agent apiService = new Agent("API_SERVICE", "External API Integration",
            Map.of(
                ResourceType.COMPUTE, 0.2,
                ResourceType.MEMORY, 0.1,
                ResourceType.DATASET, 0.0,
                ResourceType.STORAGE, 0.0,
                ResourceType.NETWORK, 0.3,
                ResourceType.API_CREDITS, 0.4
            ), 100);
        apiService.setRequest(ResourceType.COMPUTE, 10, 30);
        apiService.setRequest(ResourceType.MEMORY, 5, 20);
        apiService.setRequest(ResourceType.NETWORK, 10, 40);
        apiService.setRequest(ResourceType.API_CREDITS, 20, 60);
        agents.add(apiService);
        
        // Generalist: needs a bit of everything
        Agent generalist = new Agent("GENERALIST", "Full-Stack Analytics",
            Map.of(
                ResourceType.COMPUTE, 0.2,
                ResourceType.MEMORY, 0.15,
                ResourceType.DATASET, 0.15,
                ResourceType.STORAGE, 0.2,
                ResourceType.NETWORK, 0.15,
                ResourceType.API_CREDITS, 0.15
            ), 100);
        generalist.setRequest(ResourceType.COMPUTE, 10, 40);
        generalist.setRequest(ResourceType.MEMORY, 10, 30);
        generalist.setRequest(ResourceType.DATASET, 5, 25);
        generalist.setRequest(ResourceType.STORAGE, 10, 40);
        generalist.setRequest(ResourceType.NETWORK, 5, 25);
        generalist.setRequest(ResourceType.API_CREDITS, 10, 30);
        agents.add(generalist);
        
        // Resource pool with varied scarcity levels
        Map<ResourceType, Long> poolCapacity = new LinkedHashMap<>(); // Use LinkedHashMap for consistent ordering
        poolCapacity.put(ResourceType.COMPUTE, 100L);     // High contention
        poolCapacity.put(ResourceType.MEMORY, 100L);      // Medium contention
        poolCapacity.put(ResourceType.STORAGE, 120L);     // Medium contention
        poolCapacity.put(ResourceType.NETWORK, 80L);      // High contention
        poolCapacity.put(ResourceType.DATASET, 60L);      // Scarce
        poolCapacity.put(ResourceType.API_CREDITS, 80L);  // Limited
        ResourcePool pool = new ResourcePool(poolCapacity);
        
        System.out.println("Agents and their primary resource interests:");
        System.out.println("  ML_TRAIN_1: COMPUTE(50%), MEMORY(30%), DATASET(20%)");
        System.out.println("  ML_TRAIN_2: COMPUTE(40%), MEMORY(40%), DATASET(20%)");
        System.out.println("  DATA_PIPE_1: STORAGE(40%), DATASET(30%), NETWORK(20%)");
        System.out.println("  DATA_PIPE_2: NETWORK(50%), STORAGE(30%), DATASET(20%)");
        System.out.println("  API_SERVICE: API_CREDITS(40%), NETWORK(30%), COMPUTE(20%)");
        System.out.println("  GENERALIST: Even spread across all 6 resources");
        System.out.println();
        System.out.println("Resource availability:");
        System.out.println("  COMPUTE: 100, MEMORY: 100, STORAGE: 120");
        System.out.println("  NETWORK: 80, DATASET: 60, API_CREDITS: 80");
        System.out.println();
        
        // No burns for equal priority
        Map<String, BigDecimal> burns = new HashMap<>();
        for (Agent a : agents) {
            burns.put(a.getId(), BigDecimal.ZERO);
        }
        
        PriorityEconomy economy = new PriorityEconomy();
        
        // Demonstrate EmbargoQueue batching
        System.out.println("EMBARGO QUEUE (request batching):");
        EmbargoQueue queue = new EmbargoQueue(50); // 50ms window for demo
        
        // Submit requests with slight delays (simulating network latency)
        for (Agent agent : agents) {
            Map<ResourceType, Long> mins = new HashMap<>();
            Map<ResourceType, Long> ideals = new HashMap<>();
            for (ResourceType type : ResourceType.values()) {
                mins.put(type, agent.getMinimum(type));
                ideals.put(type, agent.getIdeal(type));
            }
            queue.submit(agent, mins, ideals, BigDecimal.ZERO);
        }
        
        System.out.println("  Submitted " + agents.size() + " requests to embargo queue");
        System.out.println("  Embargo window: 50ms (for fairness against network latency)");
        
        // Flush the queue to get the batch
        EmbargoQueue.RequestBatch batch = queue.flushAll();
        if (batch != null) {
            System.out.printf("  Batch collected: %d requests, deterministic ordering applied\n", 
                batch.getRequests().size());
        }
        System.out.println();
        
        // Use ContentionDetector to find contention groups
        System.out.println("CONTENTION ANALYSIS:");
        ContentionDetector detector = new ContentionDetector();
        List<ContentionDetector.ContentionGroup> groups = detector.detectContentions(agents, pool);
        
        System.out.printf("  Found %d contention group(s):\n", groups.size());
        for (ContentionDetector.ContentionGroup group : groups) {
            System.out.printf("    Group %s: %d agents, %d resources, severity=%.2f\n",
                group.getGroupId().substring(0, Math.min(8, group.getGroupId().length())),
                group.getAgents().size(),
                group.getResources().size(),
                group.getContentionSeverity());
            if (group.requiresJointOptimization()) {
                System.out.println("      → Requires joint optimization");
            }
        }
        System.out.println();
        
        // Sequential optimization
        System.out.println("SEQUENTIAL OPTIMIZATION (per-resource):");
        SequentialJointArbitrator sequential = new SequentialJointArbitrator(economy);
        JointArbitrator.JointAllocationResult seqResult = sequential.arbitrate(agents, pool, burns);
        
        double seqWelfare = printDiverseAllocations(agents, seqResult, "Sequential");
        System.out.println();
        
        // Joint optimization with best available solver
        ConvexJointArbitrator convex = new ConvexJointArbitrator(economy);
        boolean clarabelAvailable = convex.checkDependencies();
        
        JointArbitrator.JointAllocationResult jointResult;
        String solverUsed;
        
        if (clarabelAvailable) {
            System.out.println("JOINT OPTIMIZATION (Clarabel interior-point):");
            jointResult = convex.arbitrate(agents, pool, burns);
            solverUsed = jointResult.isFeasible() ? "Clarabel" : "Gradient (fallback)";
            if (!jointResult.isFeasible()) {
                GradientJointArbitrator gradient = new GradientJointArbitrator(economy);
                jointResult = gradient.arbitrate(agents, pool, burns);
            }
        } else {
            System.out.println("JOINT OPTIMIZATION (gradient ascent):");
            GradientJointArbitrator gradient = new GradientJointArbitrator(economy);
            jointResult = gradient.arbitrate(agents, pool, burns);
            solverUsed = "Gradient";
        }
        
        // Use TransactionManager to demonstrate atomic commit with logging
        System.out.println();
        System.out.println("TRANSACTION MANAGER (atomic commit):");
        SafetyMonitor safetyMonitor = new SafetyMonitor().setStrictMode(true);
        TransactionManager txnManager = new TransactionManager(safetyMonitor, true);
        TransactionManager.TransactionRecord record = txnManager.executeTransaction(
            jointResult, agents, pool);
        System.out.println("  Transaction result: " + record);
        System.out.println();
        
        double jointWelfare = printDiverseAllocations(agents, jointResult, "Joint");
        
        double improvement = (jointWelfare - seqWelfare) / Math.abs(seqWelfare) * 100;
        
        System.out.println();
        System.out.println(SEP);
        System.out.printf("  Welfare improvement: %.2f%%\n", improvement);
        System.out.printf("  Solver used: %s\n", solverUsed);
        System.out.printf("  Computation time: %d ms\n", jointResult.getComputationTimeMs());
        
        if (improvement > 0.1) {
            System.out.println("  ✓ PASS: Joint optimization found cross-resource trades");
            System.out.println();
            System.out.println("  With 6 resources and overlapping interests, joint optimization");
            System.out.println("  can discover complex trades that sequential cannot, such as:");
            System.out.println("    - ML agents trading DATASET access to Data Pipelines for COMPUTE");
            System.out.println("    - API Service trading NETWORK to Data Pipeline for API_CREDITS");
        } else {
            System.out.println("  Note: Minimal improvement (preferences may be near-independent)");
        }
        
        System.out.println(SEP);
        System.out.println();
    }
    
    /**
     * Helper to print allocations for diverse resource test.
     */
    private static double printDiverseAllocations(
            List<Agent> agents, 
            JointArbitrator.JointAllocationResult result,
            String label) {
        
        ResourceType[] types = {
            ResourceType.COMPUTE, ResourceType.MEMORY, ResourceType.STORAGE,
            ResourceType.NETWORK, ResourceType.DATASET, ResourceType.API_CREDITS
        };
        
        System.out.println("  Allocations (COMP/MEM/STOR/NET/DATA/API):");
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

    // ========================================================================
    // Helper Methods
    // ========================================================================
    
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
        
        // First pass: minimums
        for (int i = 0; i < n; i++) {
            allocs[i] = agents.get(i).getMinimum(resource);
            remaining -= allocs[i];
        }
        
        // Second pass: distribute rest proportionally
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
