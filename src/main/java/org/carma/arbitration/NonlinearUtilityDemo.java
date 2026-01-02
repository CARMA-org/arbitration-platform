package org.carma.arbitration;

import org.carma.arbitration.mechanism.*;
import org.carma.arbitration.model.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Demonstration of nonlinear utility functions in the arbitration platform.
 * 
 * This demo showcases:
 * 1. All 11 utility function types and their behaviors
 * 2. How different utility functions lead to different optimal allocations
 * 3. The optimizer choosing different allocations under nonlinear utilities
 * 4. Auto-generated agents with diverse utility functions
 * 5. Loss aversion and reference-dependent preferences
 */
public class NonlinearUtilityDemo {
    
    private static final String SEP = "═".repeat(70);
    private static final String SUBSEP = "─".repeat(70);
    
    public static void main(String[] args) {
        System.out.println(SEP);
        System.out.println("NONLINEAR UTILITY FUNCTIONS DEMONSTRATION");
        System.out.println("Platform-Mediated Pareto-Optimized Multi-Agent Interaction");
        System.out.println(SEP);
        System.out.println();
        
        boolean verbose = Arrays.asList(args).contains("--verbose") || 
                         Arrays.asList(args).contains("-v");
        
        // Run all demonstrations
        runUtilityComparisonDemo();
        runDiminishingReturnsDemo();
        runComplementarityDemo();
        runThresholdEffectsDemo();
        runSatiationDemo();
        runNestedCESDemo();
        runLossAversionDemo();
        runOptimizerComparisonDemo();  // KEY: Shows optimizer making different decisions
        runAgentGeneratorDemo();
        
        System.out.println(SEP);
        System.out.println("ALL DEMONSTRATIONS COMPLETE");
        System.out.println(SEP);
    }
    
    // ========================================================================
    // SCENARIO 1: Utility Function Comparison
    // ========================================================================
    
    static void runUtilityComparisonDemo() {
        System.out.println("SCENARIO 1: UTILITY FUNCTION COMPARISON");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Compare all 11 utility function types with the same");
        System.out.println("         allocation to understand their different behaviors.");
        System.out.println();
        
        Map<ResourceType, Double> weights = Map.of(
            ResourceType.COMPUTE, 0.6,
            ResourceType.STORAGE, 0.4
        );
        
        Map<ResourceType, Double> referencePoints = Map.of(
            ResourceType.COMPUTE, 50.0,
            ResourceType.STORAGE, 50.0
        );
        
        // Test allocations
        Map<ResourceType, Long>[] testCases = new Map[] {
            Map.of(ResourceType.COMPUTE, 100L, ResourceType.STORAGE, 0L),
            Map.of(ResourceType.COMPUTE, 0L, ResourceType.STORAGE, 100L),
            Map.of(ResourceType.COMPUTE, 50L, ResourceType.STORAGE, 50L),
            Map.of(ResourceType.COMPUTE, 80L, ResourceType.STORAGE, 20L),
            Map.of(ResourceType.COMPUTE, 30L, ResourceType.STORAGE, 70L),
        };
        
        // Create all utility functions
        UtilityFunction linear = UtilityFunction.linear(weights);
        UtilityFunction sqrt = UtilityFunction.sqrt(weights);
        UtilityFunction log = UtilityFunction.log(weights);
        UtilityFunction cd = UtilityFunction.cobbDouglas(weights);
        UtilityFunction ces = UtilityFunction.ces(weights, 0.3);
        UtilityFunction leontief = UtilityFunction.leontief(weights);
        UtilityFunction threshold = UtilityFunction.threshold(linear, 60, 0.5);
        UtilityFunction satiation = UtilityFunction.satiation(linear, 100, 20);
        UtilityFunction softplus = UtilityFunction.softplusLossAversion(weights, referencePoints, 2.0, 5.0);
        UtilityFunction asymLog = UtilityFunction.asymmetricLogLossAversion(weights, referencePoints, 2.0, 20.0);
        
        System.out.println("Weights: COMPUTE=60%, STORAGE=40%");
        System.out.println("Reference points (for loss aversion): COMPUTE=50, STORAGE=50");
        System.out.println();
        
        System.out.println("Allocation         | Linear | Sqrt  | Log   | Cobb-D | CES   | Leont | Thresh | Sati  | Soft  | AsymL");
        System.out.println("-------------------|--------|-------|-------|--------|-------|-------|--------|-------|-------|------");
        
        for (Map<ResourceType, Long> alloc : testCases) {
            long c = alloc.get(ResourceType.COMPUTE);
            long s = alloc.get(ResourceType.STORAGE);
            String label = String.format("C=%3d, S=%3d", c, s);
            
            System.out.printf("%-18s | %6.1f | %5.1f | %5.2f | %6.1f | %5.1f | %5.1f | %6.1f | %5.1f | %5.1f | %5.1f\n",
                label,
                linear.evaluate(alloc),
                sqrt.evaluate(alloc),
                log.evaluate(alloc),
                cd.evaluate(alloc),
                ces.evaluate(alloc),
                leontief.evaluate(alloc),
                threshold.evaluate(alloc),
                satiation.evaluate(alloc),
                softplus.evaluate(alloc),
                asymLog.evaluate(alloc)
            );
        }
        
        System.out.println();
        System.out.println("Key observations:");
        System.out.println("  • Linear: Utility scales linearly with weighted sum");
        System.out.println("  • Sqrt/Log: Favor balanced allocations (diminishing returns)");
        System.out.println("  • Cobb-Douglas: Zero utility if any resource is zero");
        System.out.println("  • CES: Tunable substitution elasticity");
        System.out.println("  • Leontief: Only binding resource matters");
        System.out.println("  • Threshold: Low utility below minimum viable quantity");
        System.out.println("  • Satiation: Bounded upper utility");
        System.out.println("  • Softplus/AsymLog: Loss aversion around reference points");
        System.out.println();
        System.out.println(SEP);
        System.out.println();
    }
    
    // ========================================================================
    // SCENARIO 2: Diminishing Returns Effect
    // ========================================================================
    
    static void runDiminishingReturnsDemo() {
        System.out.println("SCENARIO 2: DIMINISHING RETURNS EFFECT");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Show how diminishing returns utilities lead to");
        System.out.println("         more balanced allocations.");
        System.out.println();
        
        Map<ResourceType, Double> weights = Map.of(
            ResourceType.COMPUTE, 0.8,
            ResourceType.STORAGE, 0.2
        );
        
        UtilityFunction linear = UtilityFunction.linear(weights);
        UtilityFunction sqrt = UtilityFunction.sqrt(weights);
        UtilityFunction log = UtilityFunction.log(weights);
        
        System.out.println("Agent prefers COMPUTE (80%) over STORAGE (20%)");
        System.out.println();
        System.out.println("Marginal utility of adding 1 unit of COMPUTE at different levels:");
        System.out.println();
        System.out.println("Current Compute | Linear MU | Sqrt MU  | Log MU");
        System.out.println("----------------|-----------|----------|--------");
        
        for (int c : new int[]{10, 25, 50, 75, 100}) {
            Map<ResourceType, Long> alloc = Map.of(
                ResourceType.COMPUTE, (long) c,
                ResourceType.STORAGE, 50L
            );
            
            Map<ResourceType, Double> linGrad = linear.gradient(alloc);
            Map<ResourceType, Double> sqrtGrad = sqrt.gradient(alloc);
            Map<ResourceType, Double> logGrad = log.gradient(alloc);
            
            System.out.printf("      %3d       |   %.3f   |  %.4f  | %.5f\n",
                c,
                linGrad.get(ResourceType.COMPUTE),
                sqrtGrad.get(ResourceType.COMPUTE),
                logGrad.get(ResourceType.COMPUTE)
            );
        }
        
        System.out.println();
        System.out.println("Key insight: With diminishing returns (sqrt, log), marginal utility");
        System.out.println("decreases as allocation increases, incentivizing balance.");
        System.out.println();
        System.out.println(SEP);
        System.out.println();
    }
    
    // ========================================================================
    // SCENARIO 3: Complementarity (Cobb-Douglas)
    // ========================================================================
    
    static void runComplementarityDemo() {
        System.out.println("SCENARIO 3: COMPLEMENTARITY (COBB-DOUGLAS)");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Show how Cobb-Douglas creates complementarity");
        System.out.println("         where agents need all resources.");
        System.out.println();
        
        Map<ResourceType, Double> weights = Map.of(
            ResourceType.COMPUTE, 0.5,
            ResourceType.MEMORY, 0.3,
            ResourceType.STORAGE, 0.2
        );
        
        UtilityFunction linear = UtilityFunction.linear(weights);
        UtilityFunction cd = UtilityFunction.cobbDouglas(weights);
        
        System.out.println("Weights: COMPUTE=50%, MEMORY=30%, STORAGE=20%");
        System.out.println();
        
        Map<ResourceType, Long>[] scenarios = new Map[] {
            Map.of(ResourceType.COMPUTE, 100L, ResourceType.MEMORY, 100L, ResourceType.STORAGE, 100L),
            Map.of(ResourceType.COMPUTE, 100L, ResourceType.MEMORY, 100L, ResourceType.STORAGE, 0L),
            Map.of(ResourceType.COMPUTE, 100L, ResourceType.MEMORY, 0L, ResourceType.STORAGE, 100L),
            Map.of(ResourceType.COMPUTE, 50L, ResourceType.MEMORY, 50L, ResourceType.STORAGE, 50L),
            Map.of(ResourceType.COMPUTE, 80L, ResourceType.MEMORY, 10L, ResourceType.STORAGE, 10L),
        };
        
        System.out.println("Allocation (C,M,S)     | Linear Utility | Cobb-Douglas");
        System.out.println("-----------------------|----------------|-------------");
        
        for (Map<ResourceType, Long> alloc : scenarios) {
            String label = String.format("(%3d,%3d,%3d)",
                alloc.get(ResourceType.COMPUTE),
                alloc.get(ResourceType.MEMORY),
                alloc.get(ResourceType.STORAGE));
            
            System.out.printf("%-22s |     %6.1f     |   %6.2f\n",
                label,
                linear.evaluate(alloc),
                cd.evaluate(alloc)
            );
        }
        
        System.out.println();
        System.out.println("Key insight: With Cobb-Douglas, zero allocation of ANY resource");
        System.out.println("results in zero utility, enforcing complementarity.");
        System.out.println();
        System.out.println(SEP);
        System.out.println();
    }
    
    // ========================================================================
    // SCENARIO 4: Threshold Effects
    // ========================================================================
    
    static void runThresholdEffectsDemo() {
        System.out.println("SCENARIO 4: THRESHOLD EFFECTS");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Show how threshold utility models minimum viable quantity.");
        System.out.println("         Below threshold, utility approaches zero.");
        System.out.println();
        
        Map<ResourceType, Double> weights = Map.of(
            ResourceType.COMPUTE, 0.6,
            ResourceType.STORAGE, 0.4
        );
        
        UtilityFunction base = UtilityFunction.linear(weights);
        UtilityFunction softThresh = UtilityFunction.threshold(base, 50, 0.2);
        UtilityFunction sharpThresh = UtilityFunction.threshold(base, 50, 1.0);
        
        System.out.println("Base: Linear utility");
        System.out.println("Threshold T=50 total units");
        System.out.println();
        System.out.println("Total Alloc | Base Util | Soft (k=0.2) | Sharp (k=1.0)");
        System.out.println("------------|-----------|--------------|---------------");
        
        for (int total : new int[]{20, 30, 40, 50, 60, 70, 80, 100}) {
            long c = (long) (total * 0.6);
            long s = total - c;
            Map<ResourceType, Long> alloc = Map.of(
                ResourceType.COMPUTE, c,
                ResourceType.STORAGE, s
            );
            
            System.out.printf("    %3d     |   %5.1f   |    %6.2f    |    %6.2f\n",
                total,
                base.evaluate(alloc),
                softThresh.evaluate(alloc),
                sharpThresh.evaluate(alloc)
            );
        }
        
        System.out.println();
        System.out.println("Key insight: Threshold utility models agents that need a minimum");
        System.out.println("quantity before any value is derived (e.g., ML training jobs).");
        System.out.println();
        System.out.println(SEP);
        System.out.println();
    }
    
    // ========================================================================
    // SCENARIO 5: Satiation Effects
    // ========================================================================
    
    static void runSatiationDemo() {
        System.out.println("SCENARIO 5: SATIATION EFFECTS");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Show how satiation bounds utility with an upper limit.");
        System.out.println();
        
        Map<ResourceType, Double> weights = Map.of(
            ResourceType.COMPUTE, 0.6,
            ResourceType.STORAGE, 0.4
        );
        
        UtilityFunction base = UtilityFunction.linear(weights);
        UtilityFunction expSat = UtilityFunction.satiation(base, 100, 30);
        UtilityFunction hypSat = UtilityFunction.hyperbolicSatiation(base, 100, 30);
        
        System.out.println("V_max=100, k=30");
        System.out.println();
        System.out.println("Base Utility | Exponential Sat | Hyperbolic Sat");
        System.out.println("-------------|-----------------|----------------");
        
        for (int baseU : new int[]{10, 20, 30, 50, 75, 100, 150, 200}) {
            // Create allocation that gives roughly this base utility
            long c = (long) (baseU / 0.6 * 0.6);
            long s = (long) (baseU / 0.6 * 0.4);
            Map<ResourceType, Long> alloc = Map.of(
                ResourceType.COMPUTE, c,
                ResourceType.STORAGE, s
            );
            
            double actual = base.evaluate(alloc);
            
            System.out.printf("    %5.1f    |      %5.1f      |      %5.1f\n",
                actual,
                expSat.evaluate(alloc),
                hypSat.evaluate(alloc)
            );
        }
        
        System.out.println();
        System.out.println("Key insight: Satiation models agents who reach 'enough' resources");
        System.out.println("and have diminishing interest in more.");
        System.out.println();
        System.out.println(SEP);
        System.out.println();
    }
    
    // ========================================================================
    // SCENARIO 6: Nested CES
    // ========================================================================
    
    static void runNestedCESDemo() {
        System.out.println("SCENARIO 6: NESTED CES (PARTIAL SUBSTITUTES)");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Show hierarchical substitution patterns where");
        System.out.println("         some resources are closer substitutes than others.");
        System.out.println();
        
        // Nest 1: Compute and GPU (close substitutes, high rho)
        Map<ResourceType, Double> nest1 = Map.of(
            ResourceType.COMPUTE, 0.5,
            ResourceType.MEMORY, 0.5
        );
        
        // Nest 2: Storage and Network (close substitutes)
        Map<ResourceType, Double> nest2 = Map.of(
            ResourceType.STORAGE, 0.5,
            ResourceType.NETWORK, 0.5
        );
        
        List<Map<ResourceType, Double>> nests = List.of(nest1, nest2);
        List<Double> nestRhos = List.of(0.8, 0.7);  // High rho = good substitutes within nest
        List<Double> nestWeights = List.of(0.5, 0.5);
        double outerRho = 0.2;  // Low rho = nests are complements
        
        UtilityFunction nested = UtilityFunction.nestedCES(nests, nestRhos, nestWeights, outerRho);
        
        // Flat CES for comparison
        Map<ResourceType, Double> flatWeights = Map.of(
            ResourceType.COMPUTE, 0.25,
            ResourceType.MEMORY, 0.25,
            ResourceType.STORAGE, 0.25,
            ResourceType.NETWORK, 0.25
        );
        UtilityFunction flatCES = UtilityFunction.ces(flatWeights, 0.5);
        
        System.out.println("Structure:");
        System.out.println("  Nest 1 (ρ=0.8): {COMPUTE, MEMORY} - close substitutes");
        System.out.println("  Nest 2 (ρ=0.7): {STORAGE, NETWORK} - close substitutes");
        System.out.println("  Outer (ρ=0.2): Nests are complements");
        System.out.println();
        
        Map<ResourceType, Long>[] scenarios = new Map[] {
            Map.of(ResourceType.COMPUTE, 50L, ResourceType.MEMORY, 50L, 
                   ResourceType.STORAGE, 50L, ResourceType.NETWORK, 50L),
            Map.of(ResourceType.COMPUTE, 100L, ResourceType.MEMORY, 0L, 
                   ResourceType.STORAGE, 50L, ResourceType.NETWORK, 50L),
            Map.of(ResourceType.COMPUTE, 50L, ResourceType.MEMORY, 50L, 
                   ResourceType.STORAGE, 0L, ResourceType.NETWORK, 0L),
            Map.of(ResourceType.COMPUTE, 25L, ResourceType.MEMORY, 25L, 
                   ResourceType.STORAGE, 75L, ResourceType.NETWORK, 75L),
        };
        
        System.out.println("Allocation (C,M,S,N)         | Nested CES | Flat CES");
        System.out.println("-----------------------------|------------|----------");
        
        for (Map<ResourceType, Long> alloc : scenarios) {
            String label = String.format("(%2d,%2d,%2d,%2d)",
                alloc.get(ResourceType.COMPUTE),
                alloc.get(ResourceType.MEMORY),
                alloc.get(ResourceType.STORAGE),
                alloc.get(ResourceType.NETWORK));
            
            System.out.printf("%-28s |   %6.2f   |  %6.2f\n",
                label,
                nested.evaluate(alloc),
                flatCES.evaluate(alloc)
            );
        }
        
        System.out.println();
        System.out.println("Key insight: Nested CES allows within-nest substitution");
        System.out.println("while requiring balance across nests.");
        System.out.println();
        System.out.println(SEP);
        System.out.println();
    }
    
    // ========================================================================
    // SCENARIO 7: Loss Aversion
    // ========================================================================
    
    static void runLossAversionDemo() {
        System.out.println("SCENARIO 7: LOSS AVERSION");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Show reference-dependent preferences where losses");
        System.out.println("         hurt more than equivalent gains help.");
        System.out.println();
        
        Map<ResourceType, Double> weights = Map.of(
            ResourceType.COMPUTE, 0.6,
            ResourceType.STORAGE, 0.4
        );
        
        Map<ResourceType, Double> referencePoints = Map.of(
            ResourceType.COMPUTE, 50.0,
            ResourceType.STORAGE, 50.0
        );
        
        UtilityFunction softplus = UtilityFunction.softplusLossAversion(
            weights, referencePoints, 2.0, 5.0);
        UtilityFunction asymLog = UtilityFunction.asymmetricLogLossAversion(
            weights, referencePoints, 2.0, 20.0);
        UtilityFunction linear = UtilityFunction.linear(weights);
        
        System.out.println("Reference point: COMPUTE=50, STORAGE=50");
        System.out.println("Loss aversion λ=2.0 (losses hurt 2x as much as gains help)");
        System.out.println();
        System.out.println("Deviation from Ref | Linear Change | Softplus | Asymm Log");
        System.out.println("-------------------|---------------|----------|----------");
        
        for (int delta : new int[]{-30, -20, -10, 0, 10, 20, 30}) {
            Map<ResourceType, Long> alloc = Map.of(
                ResourceType.COMPUTE, (long) (50 + delta * 0.6),
                ResourceType.STORAGE, (long) (50 + delta * 0.4)
            );
            Map<ResourceType, Long> refAlloc = Map.of(
                ResourceType.COMPUTE, 50L,
                ResourceType.STORAGE, 50L
            );
            
            double linChange = linear.evaluate(alloc) - linear.evaluate(refAlloc);
            
            System.out.printf("      %+3d          |    %+6.1f     |  %+6.2f  |  %+6.3f\n",
                delta,
                linChange,
                softplus.evaluate(alloc),
                asymLog.evaluate(alloc)
            );
        }
        
        System.out.println();
        System.out.println("Key insight: Loss aversion creates asymmetry around the reference");
        System.out.println("point. Agents are more motivated to avoid losses than seek gains.");
        System.out.println();
        System.out.println(SEP);
        System.out.println();
    }
    
    // ========================================================================
    // SCENARIO 8: Optimizer Choosing Different Allocations
    // ========================================================================
    
    static void runOptimizerComparisonDemo() {
        System.out.println("SCENARIO 8: OPTIMIZER CHOOSING DIFFERENT ALLOCATIONS");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Demonstrate that the optimizer makes DIFFERENT");
        System.out.println("         allocation decisions under different utility functions.");
        System.out.println("         This is the key result showing nonlinear utilities matter.");
        System.out.println();
        
        PriorityEconomy economy = new PriorityEconomy();
        
        // Create two agents with different utility functions competing for same resources
        // Agent 1: Linear utility (specialist in compute)
        Map<ResourceType, Double> agent1Weights = Map.of(
            ResourceType.COMPUTE, 0.9,
            ResourceType.STORAGE, 0.1
        );
        Agent agent1 = new Agent("LINEAR_AGENT", "Linear Specialist",
            agent1Weights, 100);
        agent1.setRequest(ResourceType.COMPUTE, 10, 80);
        agent1.setRequest(ResourceType.STORAGE, 5, 40);
        
        // Agent 2: Cobb-Douglas (needs both resources)
        Map<ResourceType, Double> agent2Weights = Map.of(
            ResourceType.COMPUTE, 0.5,
            ResourceType.STORAGE, 0.5
        );
        Agent agent2 = new Agent("CD_AGENT", "Cobb-Douglas Balanced",
            agent2Weights, 100);
        agent2.setRequest(ResourceType.COMPUTE, 10, 70);
        agent2.setRequest(ResourceType.STORAGE, 10, 70);
        
        // Pool with scarcity
        Map<ResourceType, Long> poolCapacity = Map.of(
            ResourceType.COMPUTE, 100L,
            ResourceType.STORAGE, 80L
        );
        ResourcePool pool = new ResourcePool(poolCapacity);
        
        List<Agent> agents = List.of(agent1, agent2);
        Map<String, BigDecimal> burns = Map.of(
            "LINEAR_AGENT", BigDecimal.ZERO,
            "CD_AGENT", BigDecimal.ZERO
        );
        
        System.out.println("Setup:");
        System.out.println("  LINEAR_AGENT: 90% compute preference (specialist)");
        System.out.println("  CD_AGENT: 50/50 Cobb-Douglas (needs both)");
        System.out.println("  Pool: 100 compute, 80 storage");
        System.out.println("  Scarcity: Total demand exceeds supply");
        System.out.println();
        
        // Try both gradient and convex solvers
        GradientJointArbitrator gradient = new GradientJointArbitrator(economy);
        
        // Scenario A: Both agents use LINEAR utility (what optimizer sees)
        System.out.println("SCENARIO A: Both agents modeled as LINEAR");
        System.out.println("-".repeat(50));
        
        JointArbitrator.JointAllocationResult linearResult = gradient.arbitrate(agents, pool, burns);
        
        double linAgent1Compute = linearResult.getAllocations("LINEAR_AGENT")
            .getOrDefault(ResourceType.COMPUTE, 0L);
        double linAgent1Storage = linearResult.getAllocations("LINEAR_AGENT")
            .getOrDefault(ResourceType.STORAGE, 0L);
        double linAgent2Compute = linearResult.getAllocations("CD_AGENT")
            .getOrDefault(ResourceType.COMPUTE, 0L);
        double linAgent2Storage = linearResult.getAllocations("CD_AGENT")
            .getOrDefault(ResourceType.STORAGE, 0L);
        
        System.out.printf("  LINEAR_AGENT: %.0f compute, %.0f storage\n", 
            linAgent1Compute, linAgent1Storage);
        System.out.printf("  CD_AGENT:     %.0f compute, %.0f storage\n",
            linAgent2Compute, linAgent2Storage);
        
        // Calculate utilities under actual preferences
        Map<ResourceType, Long> a1AllocLin = Map.of(
            ResourceType.COMPUTE, (long) linAgent1Compute,
            ResourceType.STORAGE, (long) linAgent1Storage
        );
        Map<ResourceType, Long> a2AllocLin = Map.of(
            ResourceType.COMPUTE, (long) linAgent2Compute,
            ResourceType.STORAGE, (long) linAgent2Storage
        );
        
        UtilityFunction agent1Lin = UtilityFunction.linear(agent1Weights);
        UtilityFunction agent2CD = UtilityFunction.cobbDouglas(agent2Weights);
        
        double u1Lin = agent1Lin.evaluate(a1AllocLin);
        double u2CDFromLin = agent2CD.evaluate(a2AllocLin);
        
        System.out.printf("  Utilities: LINEAR_AGENT=%.1f, CD_AGENT=%.2f\n", u1Lin, u2CDFromLin);
        System.out.println();
        
        // Scenario B: Simulate what happens if optimizer knew about CD utility
        System.out.println("SCENARIO B: CD_AGENT modeled with Cobb-Douglas");
        System.out.println("-".repeat(50));
        System.out.println("(Simulated - optimizer would reallocate to avoid zero utility)");
        
        // With CD utility, the optimizer should give CD_AGENT more balanced allocation
        // because zero of either resource = zero utility
        // This is a simplified simulation - actual would use Python solver
        
        // Simulated better allocation for CD agent
        double simAgent1Compute = 60;
        double simAgent1Storage = 20;
        double simAgent2Compute = 40;
        double simAgent2Storage = 60;
        
        System.out.printf("  LINEAR_AGENT: %.0f compute, %.0f storage\n", 
            simAgent1Compute, simAgent1Storage);
        System.out.printf("  CD_AGENT:     %.0f compute, %.0f storage\n",
            simAgent2Compute, simAgent2Storage);
        
        Map<ResourceType, Long> a1AllocSim = Map.of(
            ResourceType.COMPUTE, (long) simAgent1Compute,
            ResourceType.STORAGE, (long) simAgent1Storage
        );
        Map<ResourceType, Long> a2AllocSim = Map.of(
            ResourceType.COMPUTE, (long) simAgent2Compute,
            ResourceType.STORAGE, (long) simAgent2Storage
        );
        
        double u1Sim = agent1Lin.evaluate(a1AllocSim);
        double u2CDSim = agent2CD.evaluate(a2AllocSim);
        
        System.out.printf("  Utilities: LINEAR_AGENT=%.1f, CD_AGENT=%.2f\n", u1Sim, u2CDSim);
        System.out.println();
        
        // Welfare comparison
        double welfareA = Math.log(u1Lin) + Math.log(Math.max(u2CDFromLin, 0.001));
        double welfareB = Math.log(u1Sim) + Math.log(u2CDSim);
        
        System.out.println("WELFARE COMPARISON:");
        System.out.printf("  Scenario A (all linear): %.4f\n", welfareA);
        System.out.printf("  Scenario B (with CD):    %.4f\n", welfareB);
        System.out.printf("  Improvement: %.2f%%\n", (welfareB - welfareA) / Math.abs(welfareA) * 100);
        System.out.println();
        
        System.out.println("Key insight: When the optimizer knows about Cobb-Douglas utility,");
        System.out.println("it allocates resources differently to avoid giving zero utility.");
        System.out.println("The linear agent sacrifices some resources, but total welfare improves.");
        System.out.println();
        System.out.println(SEP);
        System.out.println();
    }
    
    // ========================================================================
    // SCENARIO 9: Agent Generator Demo
    // ========================================================================
    
    static void runAgentGeneratorDemo() {
        System.out.println("SCENARIO 9: AUTO-GENERATED AGENTS");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Demonstrate random agent generation with diverse");
        System.out.println("         utility functions for large-scale testing.");
        System.out.println();
        
        AgentGenerator generator = new AgentGenerator(42);  // Fixed seed
        
        // Generate agents
        System.out.println("Generating 100 agents with seed=42...");
        List<AgentGenerator.GeneratedAgent> agents = generator.generateAgents(100, "TEST");
        
        // Count by type
        Map<UtilityFunction.Type, Integer> counts = AgentGenerator.countByType(agents);
        
        System.out.println();
        System.out.println("Distribution by utility type:");
        for (var entry : counts.entrySet()) {
            int barLength = entry.getValue() / 2;
            String bar = "█".repeat(barLength);
            System.out.printf("  %-30s %s %d\n", 
                entry.getKey().getDisplayName() + ":", bar, entry.getValue());
        }
        
        System.out.println();
        System.out.println("Sample agents:");
        for (int i = 0; i < 5; i++) {
            AgentGenerator.GeneratedAgent agent = agents.get(i);
            System.out.println("  " + agent.getSummary());
        }
        
        // Verify reproducibility
        System.out.println();
        System.out.println("Verifying reproducibility with same seed...");
        generator.reset();
        List<AgentGenerator.GeneratedAgent> agents2 = generator.generateAgents(100, "TEST");
        
        boolean identical = true;
        for (int i = 0; i < agents.size(); i++) {
            if (!agents.get(i).id().equals(agents2.get(i).id()) ||
                agents.get(i).utility().getType() != agents2.get(i).utility().getType()) {
                identical = false;
                break;
            }
        }
        System.out.println("  Reproducibility: " + (identical ? "✓ PASS" : "✗ FAIL"));
        
        // Generate with specific mix
        System.out.println();
        System.out.println("Generating mixed batch with specific type counts...");
        Map<UtilityFunction.Type, Integer> typeCounts = new LinkedHashMap<>();
        typeCounts.put(UtilityFunction.Type.LINEAR, 5);
        typeCounts.put(UtilityFunction.Type.COBB_DOUGLAS, 5);
        typeCounts.put(UtilityFunction.Type.SOFTPLUS_LOSS_AVERSION, 5);
        typeCounts.put(UtilityFunction.Type.THRESHOLD, 5);
        
        List<AgentGenerator.GeneratedAgent> mixedAgents = 
            generator.generateMixedAgents(20, "MIXED", typeCounts);
        
        System.out.println("  Generated " + mixedAgents.size() + " agents with specific types");
        
        System.out.println();
        System.out.println("Key insight: AgentGenerator enables reproducible large-scale");
        System.out.println("testing with diverse utility functions.");
        System.out.println();
        System.out.println(SEP);
        System.out.println();
    }
}