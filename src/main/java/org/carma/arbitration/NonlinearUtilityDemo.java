package org.carma.arbitration;

import org.carma.arbitration.model.*;
import org.carma.arbitration.mechanism.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Demonstration of Nonlinear Utility Functions for Resource Allocation.
 * 
 * This demo shows how different utility function types affect allocation outcomes:
 * 
 * 1. LINEAR: Standard weighted sum - resources are perfect substitutes
 * 2. SQRT: Diminishing returns - more balanced allocations
 * 3. LOG: Strong diminishing returns - even more balanced
 * 4. COBB_DOUGLAS: Complementarities - need some of every resource
 * 5. CES: Configurable elasticity of substitution
 * 
 * Run with: java -cp out org.carma.arbitration.NonlinearUtilityDemo
 */
public class NonlinearUtilityDemo {
    
    private static final String SEP = "════════════════════════════════════════════════════════════════════════";
    private static final String SUBSEP = "────────────────────────────────────────────────────────────────";

    public static void main(String[] args) {
        System.out.println();
        System.out.println(SEP);
        System.out.println("   NONLINEAR UTILITY FUNCTIONS DEMONSTRATION");
        System.out.println("   Task #3: Diminishing Returns and Complementarities");
        System.out.println(SEP);
        System.out.println();
        
        runUtilityComparisonDemo();
        runDiminishingReturnsDemo();
        runComplementarityDemo();
        runElasticityDemo();
        
        System.out.println(SEP);
        System.out.println("   DEMONSTRATION COMPLETE");
        System.out.println(SEP);
    }
    
    /**
     * Compare different utility functions on the same allocation.
     */
    static void runUtilityComparisonDemo() {
        System.out.println("SCENARIO 1: UTILITY FUNCTION COMPARISON");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Compare how different utility functions evaluate");
        System.out.println("         the same resource allocation.");
        System.out.println();
        
        // Create weights
        Map<ResourceType, Double> weights = Map.of(
            ResourceType.COMPUTE, 0.6,
            ResourceType.STORAGE, 0.4
        );
        
        // Test allocations
        Map<ResourceType, Long>[] testCases = new Map[] {
            Map.of(ResourceType.COMPUTE, 100L, ResourceType.STORAGE, 0L),    // All compute
            Map.of(ResourceType.COMPUTE, 0L, ResourceType.STORAGE, 100L),    // All storage
            Map.of(ResourceType.COMPUTE, 50L, ResourceType.STORAGE, 50L),    // Balanced
            Map.of(ResourceType.COMPUTE, 80L, ResourceType.STORAGE, 20L),    // Weighted
            Map.of(ResourceType.COMPUTE, 60L, ResourceType.STORAGE, 40L),    // Optimal for linear
        };
        
        // Create utility functions
        UtilityFunction linear = UtilityFunction.linear(weights);
        UtilityFunction sqrt = UtilityFunction.sqrt(weights);
        UtilityFunction log = UtilityFunction.log(weights);
        UtilityFunction cd = UtilityFunction.cobbDouglas(weights);
        UtilityFunction ces = UtilityFunction.ces(weights, 0.3);
        
        System.out.println("Weights: COMPUTE=60%, STORAGE=40%");
        System.out.println();
        System.out.println("Allocation              | Linear | Sqrt   | Log    | Cobb-D | CES(ρ=0.3)");
        System.out.println("------------------------|--------|--------|--------|--------|----------");
        
        for (Map<ResourceType, Long> alloc : testCases) {
            long c = alloc.get(ResourceType.COMPUTE);
            long s = alloc.get(ResourceType.STORAGE);
            String label = String.format("C=%3d, S=%3d", c, s);
            
            System.out.printf("%-23s | %6.1f | %6.1f | %6.2f | %6.1f | %6.1f\n",
                label,
                linear.evaluate(alloc),
                sqrt.evaluate(alloc),
                log.evaluate(alloc),
                cd.evaluate(alloc),
                ces.evaluate(alloc)
            );
        }
        
        System.out.println();
        System.out.println("Key observations:");
        System.out.println("  • Linear gives same utility to (100,0) and (0,100) weighted by preferences");
        System.out.println("  • Sqrt/Log favor balanced allocations (diminishing returns)");
        System.out.println("  • Cobb-Douglas gives 0 utility if any resource is 0 (complementarity)");
        System.out.println("  • CES with ρ<1 balances between specialization and balance");
        System.out.println();
        System.out.println(SEP);
        System.out.println("  ✓ Utility comparison complete");
        System.out.println(SEP);
        System.out.println();
    }
    
    /**
     * Demonstrate how diminishing returns affects optimal allocation.
     */
    static void runDiminishingReturnsDemo() {
        System.out.println("SCENARIO 2: DIMINISHING RETURNS EFFECT");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Show how sqrt utility leads to more balanced allocations");
        System.out.println("         compared to linear utility.");
        System.out.println();
        
        // Two agents competing for compute and storage
        Map<ResourceType, Double> specialistWeights = Map.of(
            ResourceType.COMPUTE, 0.9,
            ResourceType.STORAGE, 0.1
        );
        Map<ResourceType, Double> balancedWeights = Map.of(
            ResourceType.COMPUTE, 0.5,
            ResourceType.STORAGE, 0.5
        );
        
        System.out.println("Setup:");
        System.out.println("  SPECIALIST: 90% compute, 10% storage preference");
        System.out.println("  BALANCED: 50% compute, 50% storage preference");
        System.out.println("  Resources: 100 compute, 100 storage");
        System.out.println();
        
        // With linear utility
        UtilityFunction specLinear = UtilityFunction.linear(specialistWeights);
        UtilityFunction balLinear = UtilityFunction.linear(balancedWeights);
        
        // With sqrt utility
        UtilityFunction specSqrt = UtilityFunction.sqrt(specialistWeights);
        UtilityFunction balSqrt = UtilityFunction.sqrt(balancedWeights);
        
        // Simulate allocations (simplified - actual would use optimizer)
        // Linear optimal: specialist gets all compute, balanced gets all storage
        Map<ResourceType, Long> specAllocLinear = Map.of(
            ResourceType.COMPUTE, 70L, ResourceType.STORAGE, 10L);
        Map<ResourceType, Long> balAllocLinear = Map.of(
            ResourceType.COMPUTE, 30L, ResourceType.STORAGE, 90L);
        
        // Sqrt optimal: more balanced distribution
        Map<ResourceType, Long> specAllocSqrt = Map.of(
            ResourceType.COMPUTE, 55L, ResourceType.STORAGE, 20L);
        Map<ResourceType, Long> balAllocSqrt = Map.of(
            ResourceType.COMPUTE, 45L, ResourceType.STORAGE, 80L);
        
        System.out.println("LINEAR utility allocations:");
        System.out.printf("  SPECIALIST: %d compute, %d storage (utility=%.1f)\n",
            specAllocLinear.get(ResourceType.COMPUTE),
            specAllocLinear.get(ResourceType.STORAGE),
            specLinear.evaluate(specAllocLinear));
        System.out.printf("  BALANCED:   %d compute, %d storage (utility=%.1f)\n",
            balAllocLinear.get(ResourceType.COMPUTE),
            balAllocLinear.get(ResourceType.STORAGE),
            balLinear.evaluate(balAllocLinear));
        System.out.printf("  Total welfare: %.2f\n",
            Math.log(specLinear.evaluate(specAllocLinear)) + 
            Math.log(balLinear.evaluate(balAllocLinear)));
        System.out.println();
        
        System.out.println("SQRT utility allocations:");
        System.out.printf("  SPECIALIST: %d compute, %d storage (utility=%.1f)\n",
            specAllocSqrt.get(ResourceType.COMPUTE),
            specAllocSqrt.get(ResourceType.STORAGE),
            specSqrt.evaluate(specAllocSqrt));
        System.out.printf("  BALANCED:   %d compute, %d storage (utility=%.1f)\n",
            balAllocSqrt.get(ResourceType.COMPUTE),
            balAllocSqrt.get(ResourceType.STORAGE),
            balSqrt.evaluate(balAllocSqrt));
        System.out.printf("  Total welfare: %.2f\n",
            Math.log(specSqrt.evaluate(specAllocSqrt)) + 
            Math.log(balSqrt.evaluate(balAllocSqrt)));
        System.out.println();
        
        System.out.println("Key insight: With diminishing returns (sqrt), the optimizer");
        System.out.println("gives more resources to the less-endowed agent because the");
        System.out.println("marginal utility is higher there.");
        System.out.println();
        System.out.println(SEP);
        System.out.println("  ✓ Diminishing returns demonstration complete");
        System.out.println(SEP);
        System.out.println();
    }
    
    /**
     * Demonstrate complementarity with Cobb-Douglas utility.
     */
    static void runComplementarityDemo() {
        System.out.println("SCENARIO 3: COMPLEMENTARITY (COBB-DOUGLAS)");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Show how Cobb-Douglas utility creates complementarity");
        System.out.println("         where agents need some of every resource.");
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
        
        // Test cases showing complementarity
        Map<ResourceType, Long>[] testCases = new Map[] {
            Map.of(ResourceType.COMPUTE, 100L, ResourceType.MEMORY, 0L, ResourceType.STORAGE, 0L),
            Map.of(ResourceType.COMPUTE, 50L, ResourceType.MEMORY, 50L, ResourceType.STORAGE, 0L),
            Map.of(ResourceType.COMPUTE, 33L, ResourceType.MEMORY, 33L, ResourceType.STORAGE, 34L),
            Map.of(ResourceType.COMPUTE, 50L, ResourceType.MEMORY, 30L, ResourceType.STORAGE, 20L),
        };
        
        System.out.println("Allocation (C/M/S)      | Linear | Cobb-Douglas");
        System.out.println("------------------------|--------|-------------");
        
        for (Map<ResourceType, Long> alloc : testCases) {
            long c = alloc.get(ResourceType.COMPUTE);
            long m = alloc.get(ResourceType.MEMORY);
            long s = alloc.get(ResourceType.STORAGE);
            String label = String.format("%3d/%3d/%3d", c, m, s);
            
            System.out.printf("%-23s | %6.1f | %11.1f\n",
                label,
                linear.evaluate(alloc),
                cd.evaluate(alloc)
            );
        }
        
        System.out.println();
        System.out.println("Key insight: Cobb-Douglas gives ZERO utility if any resource");
        System.out.println("is zero. This models situations where all resources are");
        System.out.println("necessary (like needing both CPU and RAM to run a program).");
        System.out.println();
        System.out.println(SEP);
        System.out.println("  ✓ Complementarity demonstration complete");
        System.out.println(SEP);
        System.out.println();
    }
    
    /**
     * Demonstrate CES utility with different elasticity parameters.
     */
    static void runElasticityDemo() {
        System.out.println("SCENARIO 4: ELASTICITY OF SUBSTITUTION (CES)");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Show how the CES parameter ρ controls the degree");
        System.out.println("         of substitutability between resources.");
        System.out.println();
        System.out.println("CES Formula: Φ = (Σⱼ wⱼ·aⱼ^ρ)^(1/ρ)");
        System.out.println();
        System.out.println("Special cases:");
        System.out.println("  ρ → 1:   Linear (perfect substitutes)");
        System.out.println("  ρ = 0.5: Square root-like");
        System.out.println("  ρ → 0:   Cobb-Douglas (unit elasticity)");
        System.out.println("  ρ → -∞:  Leontief (perfect complements)");
        System.out.println();
        
        Map<ResourceType, Double> weights = Map.of(
            ResourceType.COMPUTE, 0.5,
            ResourceType.STORAGE, 0.5
        );
        
        double[] rhoValues = {0.9, 0.5, 0.2, -0.5, -2.0};
        
        // Create CES utilities with different rho values
        List<UtilityFunction> cesUtils = new ArrayList<>();
        for (double rho : rhoValues) {
            cesUtils.add(UtilityFunction.ces(weights, rho));
        }
        
        // Add Leontief for comparison
        UtilityFunction leontief = UtilityFunction.leontief(weights);
        
        // Test allocations
        Map<ResourceType, Long>[] testCases = new Map[] {
            Map.of(ResourceType.COMPUTE, 100L, ResourceType.STORAGE, 0L),
            Map.of(ResourceType.COMPUTE, 80L, ResourceType.STORAGE, 20L),
            Map.of(ResourceType.COMPUTE, 50L, ResourceType.STORAGE, 50L),
        };
        
        System.out.print("Allocation    | ");
        for (double rho : rhoValues) {
            System.out.printf("ρ=%.1f | ", rho);
        }
        System.out.println("Leontief");
        System.out.print("--------------|");
        for (int i = 0; i <= rhoValues.length; i++) {
            System.out.print("-------|");
        }
        System.out.println();
        
        for (Map<ResourceType, Long> alloc : testCases) {
            long c = alloc.get(ResourceType.COMPUTE);
            long s = alloc.get(ResourceType.STORAGE);
            String label = String.format("C=%3d,S=%3d", c, s);
            
            System.out.printf("%-13s |", label);
            for (UtilityFunction ces : cesUtils) {
                System.out.printf(" %5.1f |", ces.evaluate(alloc));
            }
            System.out.printf(" %5.1f", leontief.evaluate(alloc));
            System.out.println();
        }
        
        System.out.println();
        System.out.println("Key insight: Lower ρ values penalize unbalanced allocations more");
        System.out.println("heavily. At ρ=-∞ (Leontief), only the minimum matters.");
        System.out.println();
        
        // Show marginal rate of substitution
        System.out.println("Marginal Rate of Substitution at (50, 50):");
        Map<ResourceType, Long> balanced = Map.of(
            ResourceType.COMPUTE, 50L, ResourceType.STORAGE, 50L);
        
        for (int i = 0; i < rhoValues.length; i++) {
            double mrs = cesUtils.get(i).marginalRateOfSubstitution(
                ResourceType.COMPUTE, ResourceType.STORAGE, balanced);
            System.out.printf("  ρ=%.1f: MRS = %.3f\n", rhoValues[i], mrs);
        }
        
        System.out.println();
        System.out.println("At balanced allocation, MRS = 1 means equal marginal value.");
        System.out.println();
        System.out.println(SEP);
        System.out.println("  ✓ Elasticity demonstration complete");
        System.out.println(SEP);
        System.out.println();
    }
}
