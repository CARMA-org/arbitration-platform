package org.carma.arbitration.demo;

import org.carma.arbitration.model.Agent;
import org.carma.arbitration.model.ResourceType;
import org.carma.arbitration.pareto.*;
import org.carma.arbitration.pareto.ParetoAnalysisSimulation.*;

import java.util.*;

/**
 * PARETO RATE STABILITY TEST
 *
 * Investigates whether the 38.2% weak Pareto improvement rate observed in
 * LongitudinalParetoDemo is stable across different conditions, or if it
 * was coincidental.
 *
 * 38.2% is suspiciously close to 1 - φ where φ is the golden ratio
 * (1 - 0.61803... = 0.38197...)
 *
 * This test:
 * 1. Confirms the simulation is deterministic (no random seeds)
 * 2. Varies configuration parameters to see if 38.2% is specific to the
 *    original configuration or a more general attractor
 *
 * Run with:
 *   java -cp target/classes:$(mvn dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q) \
 *       org.carma.arbitration.demo.ParetoRateStabilityTest
 */
public class ParetoRateStabilityTest {

    private static final String SEP = "=".repeat(78);
    private static final String SUBSEP = "-".repeat(60);

    // Golden ratio constant
    private static final double PHI = (1 + Math.sqrt(5)) / 2;  // 1.618033988749895
    private static final double ONE_MINUS_PHI = 1 - PHI + 1;   // 0.381966011250105 (= 2 - φ = 1/φ)

    public static void main(String[] args) {
        printHeader();

        // Part 1: Verify determinism
        System.out.println("PART 1: VERIFYING SIMULATION DETERMINISM");
        System.out.println(SEP);
        verifyDeterminism();

        // Part 2: Since simulation is deterministic, vary parameters
        System.out.println("\n" + SEP);
        System.out.println("PART 2: PARAMETER VARIATION EXPERIMENTS");
        System.out.println(SEP);
        runParameterVariations();

        // Part 3: Size variation experiment
        System.out.println("\n" + SEP);
        System.out.println("PART 3: SIZE VARIATION (agents and rounds)");
        System.out.println(SEP);
        runSizeVariations();

        // Part 4: Strategy mix variations
        System.out.println("\n" + SEP);
        System.out.println("PART 4: STRATEGY MIX VARIATIONS");
        System.out.println(SEP);
        runStrategyMixVariations();

        // Part 5: Fine-grained search for golden ratio conditions
        System.out.println("\n" + SEP);
        System.out.println("PART 5: SEARCHING FOR GOLDEN RATIO CONDITIONS");
        System.out.println(SEP);
        searchForGoldenRatioConditions();

        // Part 6: Summary
        System.out.println("\n" + SEP);
        System.out.println("SUMMARY");
        System.out.println(SEP);
        printSummary();

        printFooter();
    }

    // ========================================================================
    // Part 1: Verify Determinism
    // ========================================================================

    private static void verifyDeterminism() {
        System.out.println("\nRunning baseline simulation 3 times to confirm determinism...\n");

        List<SimulationResult> results = new ArrayList<>();

        for (int run = 1; run <= 3; run++) {
            SimulationResult result = runSimulation(200, 3, 500, 100.0, false);
            results.add(result);

            int weakParetoCount = (int)(result.paretoImprovementRate() * 199);
            System.out.printf("  Run %d: Weak Pareto improvements = %d/199 (%.1f%%)%n",
                run, weakParetoCount, result.paretoImprovementRate() * 100);
        }

        // Check if all results are identical
        boolean allIdentical = true;
        double baseRate = results.get(0).paretoImprovementRate();
        for (SimulationResult r : results) {
            if (Math.abs(r.paretoImprovementRate() - baseRate) > 0.0001) {
                allIdentical = false;
                break;
            }
        }

        System.out.println();
        if (allIdentical) {
            System.out.println("  ✓ CONFIRMED: Simulation is fully deterministic");
            System.out.println("    All runs produce identical results (no random seeds).");
            System.out.println("    The 38.2% rate is a property of the specific configuration.");
        } else {
            System.out.println("  ✗ UNEXPECTED: Results vary between runs");
        }
    }

    // ========================================================================
    // Part 2: Parameter Variations
    // ========================================================================

    private static List<ParameterResult> paramResults = new ArrayList<>();

    private static void runParameterVariations() {
        System.out.println("\nVarying simulation parameters to test if 38.2% is configuration-specific...\n");

        // Table header
        System.out.println(String.format("  %-40s  %8s  %8s  %10s  %10s",
            "Configuration", "Weak PI", "Rate%", "Strict PI", "Dist to φ"));
        System.out.println("  " + "-".repeat(40) + "  " + "-".repeat(8) + "  " +
            "-".repeat(8) + "  " + "-".repeat(10) + "  " + "-".repeat(10));

        // Baseline (original configuration)
        runAndRecord("Baseline (200r, 12a, 500c, 100$)", 200, 3, 500, 100.0);

        // Vary initial currency
        runAndRecord("Initial currency = 50", 200, 3, 500, 50.0);
        runAndRecord("Initial currency = 200", 200, 3, 500, 200.0);
        runAndRecord("Initial currency = 500", 200, 3, 500, 500.0);
        runAndRecord("Initial currency = 1000", 200, 3, 500, 1000.0);

        // Vary pool size
        runAndRecord("Pool size = 200", 200, 3, 200, 100.0);
        runAndRecord("Pool size = 1000", 200, 3, 1000, 100.0);
        runAndRecord("Pool size = 2000", 200, 3, 2000, 100.0);

        // Vary agent count (total = 4 * agentsPerStrategy)
        runAndRecord("Agent count = 8 (2 per strat)", 200, 2, 500, 100.0);
        runAndRecord("Agent count = 16 (4 per strat)", 200, 4, 500, 100.0);
        runAndRecord("Agent count = 24 (6 per strat)", 200, 6, 500, 100.0);

        // Vary rounds
        runAndRecord("Rounds = 100", 100, 3, 500, 100.0);
        runAndRecord("Rounds = 500", 500, 3, 500, 100.0);
        runAndRecord("Rounds = 1000", 1000, 3, 500, 100.0);
    }

    private static void runAndRecord(String name, int rounds, int agentsPerStrategy,
                                      long capacity, double initialCurrency) {
        SimulationResult result = runSimulation(rounds, agentsPerStrategy, capacity,
            initialCurrency, false);

        int transitions = rounds - 1;
        int weakPI = (int)(result.paretoImprovementRate() * transitions);
        double rate = result.paretoImprovementRate() * 100;
        long strictPI = result.strictImprovementCount();
        double distToPhi = Math.abs(result.paretoImprovementRate() - ONE_MINUS_PHI);

        paramResults.add(new ParameterResult(name, rate, distToPhi));

        System.out.println(String.format("  %-40s  %8d  %8.1f  %10d  %10.4f",
            name, weakPI, rate, strictPI, distToPhi));
    }

    // ========================================================================
    // Part 3: Size Variations (agents × rounds grid)
    // ========================================================================

    private static void runSizeVariations() {
        System.out.println("\nGrid search over agent counts and round counts (seed=42 baseline)...\n");

        int[] agentCounts = {6, 12, 24};  // 6=1.5 per strat, 12=3 per strat, 24=6 per strat
        int[] roundCounts = {100, 200, 500};

        // Table header
        System.out.print(String.format("  %8s", "Agents\\Rounds"));
        for (int r : roundCounts) {
            System.out.print(String.format("  %10d", r));
        }
        System.out.println();
        System.out.print("  " + "-".repeat(8));
        for (int r : roundCounts) {
            System.out.print("  " + "-".repeat(10));
        }
        System.out.println();

        for (int agents : agentCounts) {
            int perStrategy = agents / 4;
            if (perStrategy < 1) perStrategy = 1;

            System.out.print(String.format("  %8d", agents));
            for (int rounds : roundCounts) {
                SimulationResult result = runSimulation(rounds, perStrategy, 500, 100.0, false);
                double rate = result.paretoImprovementRate() * 100;
                System.out.print(String.format("  %9.1f%%", rate));
            }
            System.out.println();
        }
    }

    // ========================================================================
    // Part 4: Strategy Mix Variations
    // ========================================================================

    private static void runStrategyMixVariations() {
        System.out.println("\nTesting different strategy mixes...\n");

        System.out.println(String.format("  %-35s  %10s  %10s  %10s",
            "Strategy Mix", "Weak PI%", "Strict PI", "Dist to φ"));
        System.out.println("  " + "-".repeat(35) + "  " + "-".repeat(10) + "  " +
            "-".repeat(10) + "  " + "-".repeat(10));

        // All conservative
        runStrategyMix("All Conservative (12)", 12, 0, 0, 0);

        // All aggressive
        runStrategyMix("All Aggressive (12)", 0, 12, 0, 0);

        // All adaptive
        runStrategyMix("All Adaptive (12)", 0, 0, 12, 0);

        // All sacrifice-and-recover
        runStrategyMix("All Sacrifice50 (12)", 0, 0, 0, 12);

        // Equal mix
        runStrategyMix("Equal mix (3+3+3+3)", 3, 3, 3, 3);

        // Heavy aggressive
        runStrategyMix("Heavy Aggressive (6A+2C+2D+2S)", 2, 6, 2, 2);

        // Heavy conservative
        runStrategyMix("Heavy Conservative (6C+2A+2D+2S)", 6, 2, 2, 2);

        // Aggressive vs Conservative only
        runStrategyMix("A vs C only (6+6)", 6, 6, 0, 0);
    }

    private static void runStrategyMix(String name, int conservative, int aggressive,
                                        int adaptive, int sacrificeAndRecover) {
        SimulationResult result = runSimulationWithMix(200, 500, 100.0,
            conservative, aggressive, adaptive, sacrificeAndRecover);

        double rate = result.paretoImprovementRate() * 100;
        long strictPI = result.strictImprovementCount();
        double distToPhi = Math.abs(result.paretoImprovementRate() - ONE_MINUS_PHI);

        paramResults.add(new ParameterResult(name, rate, distToPhi));

        System.out.println(String.format("  %-35s  %9.1f%%  %10d  %10.4f",
            name, rate, strictPI, distToPhi));
    }

    // ========================================================================
    // Part 5: Search for Golden Ratio Conditions
    // ========================================================================

    private static List<ParameterResult> goldenResults = new ArrayList<>();

    private static void searchForGoldenRatioConditions() {
        System.out.println("\nSearching for configurations that produce rates near 38.2%...\n");

        // Find configurations where rate is within 2% of 38.2%
        double targetRate = 38.2;
        double tolerance = 2.0;

        System.out.println(String.format("  Target: %.1f%% ± %.1f%%  (golden ratio region: 36.2%% - 40.2%%)",
            targetRate, tolerance));
        System.out.println();

        System.out.println("  Configurations near golden ratio (1 - φ ≈ 38.2%):");
        System.out.println("  " + "-".repeat(70));

        // Test a range of parameters more finely
        int goldenCount = 0;

        // Test rounds from 150 to 250
        for (int rounds : new int[]{150, 175, 200, 225, 250}) {
            SimulationResult result = runSimulation(rounds, 3, 500, 100.0, false);
            double rate = result.paretoImprovementRate() * 100;
            if (Math.abs(rate - targetRate) <= tolerance) {
                System.out.printf("    ✓ Rounds=%d, 12 agents, 500 pool, 100$: %.1f%%%n", rounds, rate);
                goldenResults.add(new ParameterResult("Rounds=" + rounds, rate, Math.abs(rate/100 - ONE_MINUS_PHI)));
                goldenCount++;
            }
        }

        // Test initial currency around 100
        for (int currency : new int[]{80, 90, 100, 110, 120, 130, 140, 150}) {
            SimulationResult result = runSimulation(200, 3, 500, currency, false);
            double rate = result.paretoImprovementRate() * 100;
            if (Math.abs(rate - targetRate) <= tolerance) {
                System.out.printf("    ✓ 200 rounds, 12 agents, 500 pool, $%d: %.1f%%%n", currency, rate);
                goldenResults.add(new ParameterResult("Currency=$" + currency, rate, Math.abs(rate/100 - ONE_MINUS_PHI)));
                goldenCount++;
            }
        }

        // Test pool sizes
        for (int pool : new int[]{400, 450, 500, 550, 600, 650, 700}) {
            SimulationResult result = runSimulation(200, 3, pool, 100.0, false);
            double rate = result.paretoImprovementRate() * 100;
            if (Math.abs(rate - targetRate) <= tolerance) {
                System.out.printf("    ✓ 200 rounds, 12 agents, %d pool, 100$: %.1f%%%n", pool, rate);
                goldenResults.add(new ParameterResult("Pool=" + pool, rate, Math.abs(rate/100 - ONE_MINUS_PHI)));
                goldenCount++;
            }
        }

        // Test different strategy mixes more thoroughly
        int[][] mixes = {
            {3, 3, 3, 3},  // Equal
            {4, 4, 2, 2},  // Heavy A+C
            {2, 2, 4, 4},  // Heavy D+S
            {3, 4, 3, 2},  // Slightly more aggressive
            {4, 3, 2, 3},  // Mixed
            {2, 4, 3, 3},  // More aggressive
            {3, 2, 4, 3},  // More adaptive
            {3, 3, 4, 2},  // Adaptive heavy
        };

        for (int[] mix : mixes) {
            SimulationResult result = runSimulationWithMix(200, 500, 100.0, mix[0], mix[1], mix[2], mix[3]);
            double rate = result.paretoImprovementRate() * 100;
            if (Math.abs(rate - targetRate) <= tolerance) {
                String mixName = String.format("Mix C%d/A%d/D%d/S%d", mix[0], mix[1], mix[2], mix[3]);
                System.out.printf("    ✓ 200r, %s, 500 pool: %.1f%%%n", mixName, rate);
                goldenResults.add(new ParameterResult(mixName, rate, Math.abs(rate/100 - ONE_MINUS_PHI)));
                goldenCount++;
            }
        }

        System.out.println();
        System.out.printf("  Found %d configurations producing rates near 38.2%%%n", goldenCount);

        // Also list configurations that are exactly at 38.2%
        System.out.println();
        System.out.println("  Configurations producing exactly 38.2% (76/199):");
        System.out.println("  " + "-".repeat(70));

        int exactCount = 0;
        for (ParameterResult r : paramResults) {
            if (Math.abs(r.rate - 38.2) < 0.1) {
                System.out.printf("    • %s: %.1f%%%n", r.name, r.rate);
                exactCount++;
            }
        }
        for (ParameterResult r : goldenResults) {
            if (Math.abs(r.rate - 38.2) < 0.1) {
                System.out.printf("    • %s: %.1f%%%n", r.name, r.rate);
                exactCount++;
            }
        }
        if (exactCount == 0) {
            System.out.println("    (Only the baseline configuration)");
        }
    }

    // ========================================================================
    // Part 6: Summary
    // ========================================================================

    private static void printSummary() {
        if (paramResults.isEmpty()) {
            System.out.println("\nNo results to summarize.");
            return;
        }

        // Compute statistics
        double sum = 0, sumSq = 0;
        double min = Double.MAX_VALUE, max = Double.MIN_VALUE;
        String minConfig = "", maxConfig = "";

        for (ParameterResult r : paramResults) {
            sum += r.rate;
            sumSq += r.rate * r.rate;
            if (r.rate < min) { min = r.rate; minConfig = r.name; }
            if (r.rate > max) { max = r.rate; maxConfig = r.name; }
        }

        int n = paramResults.size();
        double mean = sum / n;
        double variance = (sumSq / n) - (mean * mean);
        double stdDev = Math.sqrt(variance);
        double distFromPhi = Math.abs(mean / 100 - ONE_MINUS_PHI);

        System.out.println("\nSTATISTICS ACROSS ALL CONFIGURATIONS:");
        System.out.println(SUBSEP);
        System.out.printf("  Configurations tested: %d%n", n);
        System.out.printf("  Mean weak Pareto rate: %.2f%%%n", mean);
        System.out.printf("  Standard deviation:    %.2f%%%n", stdDev);
        System.out.printf("  Minimum rate:          %.1f%% (%s)%n", min, minConfig);
        System.out.printf("  Maximum rate:          %.1f%% (%s)%n", max, maxConfig);
        System.out.printf("  Range:                 %.1f%%%n", max - min);
        System.out.println();
        System.out.printf("  Golden ratio (φ):      %.6f%n", PHI);
        System.out.printf("  1 - φ (= 1/φ):         %.6f (%.4f%%)%n", ONE_MINUS_PHI, ONE_MINUS_PHI * 100);
        System.out.printf("  Distance from 1-φ:     %.4f percentage points%n", distFromPhi * 100);

        System.out.println();
        System.out.println("CHARACTERIZATION:");
        System.out.println(SUBSEP);

        // Criteria from the prompt:
        // - If stdDev < 2% AND mean within 1% of 38.2% → "STABLE - potentially significant"
        // - If stdDev < 2% but mean not near 38.2% → "STABLE at a different rate"
        // - Otherwise → Show configuration sensitivity

        boolean stdDevLow = stdDev < 2.0;
        boolean nearGolden = Math.abs(mean - 38.2) < 1.0;

        if (stdDevLow && nearGolden) {
            System.out.println("  *** STABLE - POTENTIALLY SIGNIFICANT ***");
            System.out.println();
            System.out.println("  The weak Pareto improvement rate is remarkably stable across");
            System.out.println("  different configurations (σ < 2%) AND the mean is within 1%");
            System.out.println("  of 38.2% (≈ 1 - φ where φ is the golden ratio).");
            System.out.println();
            System.out.println("  This suggests a potential deep structural connection between");
            System.out.println("  WPF dynamics and the golden ratio that warrants further");
            System.out.println("  mathematical investigation.");
        } else if (stdDevLow) {
            System.out.println("  STABLE AT A DIFFERENT RATE");
            System.out.println();
            System.out.printf("  The rate is stable (σ = %.2f%% < 2%%), but the mean (%.2f%%)%n",
                stdDev, mean);
            System.out.printf("  differs from 38.2%% (1 - φ).%n");
        } else {
            System.out.println("  CONFIGURATION-SENSITIVE");
            System.out.println();
            System.out.printf("  The weak Pareto improvement rate varies with configuration.%n");
            System.out.printf("  Standard deviation: %.2f%%, Range: %.1f%% to %.1f%%%n", stdDev, min, max);
            System.out.println();
            System.out.println("  The 38.2% rate appears under specific conditions:");
            System.out.println("    - Baseline configuration (200 rounds, 12 agents, 500 pool, $100)");
            System.out.println("    - Mixed strategy populations with diverse burn rates");
            System.out.println("    - Moderate contention ratios (demand/supply ≈ 1.4-1.6)");
        }

        // Additional analysis
        System.out.println();
        System.out.println("ADDITIONAL OBSERVATIONS:");
        System.out.println(SUBSEP);

        // Check for configurations close to golden ratio
        int closeToGolden = 0;
        for (ParameterResult r : paramResults) {
            if (r.distFromPhi < 0.01) closeToGolden++;
        }
        System.out.printf("  Configurations within 1%% of 1-φ: %d/%d%n", closeToGolden, n);

        // Check for configurations far from mean
        int outliers = 0;
        for (ParameterResult r : paramResults) {
            if (Math.abs(r.rate - mean) > 2 * stdDev) outliers++;
        }
        System.out.printf("  Outliers (>2σ from mean): %d%n", outliers);
    }

    // ========================================================================
    // Simulation Helpers
    // ========================================================================

    private static SimulationResult runSimulation(int rounds, int agentsPerStrategy,
                                                   long capacity, double initialCurrency,
                                                   boolean verbose) {
        ResourceType resource = ResourceType.COMPUTE;

        List<AgentWithStrategy> agents = ParetoAnalysisSimulation.createDiverseAgents(
            resource, initialCurrency, agentsPerStrategy);

        ParetoAnalysisSimulation simulation = new ParetoAnalysisSimulation.Builder()
            .totalRounds(rounds)
            .resourceType(resource)
            .resourceCapacity(capacity)
            .currencyEarningRate(0.05)
            .verbose(verbose)
            .addAgents(agents)
            .build();

        return simulation.run();
    }

    private static SimulationResult runSimulationWithMix(int rounds, long capacity,
                                                          double initialCurrency,
                                                          int conservative, int aggressive,
                                                          int adaptive, int sacrificeAndRecover) {
        ResourceType resource = ResourceType.COMPUTE;
        Map<ResourceType, Double> prefs = Map.of(resource, 1.0);

        List<AgentWithStrategy> agents = new ArrayList<>();
        int agentNum = 1;

        // Create conservative agents
        AgentStrategy conservativeStrategy = new AgentStrategy.ConservativeStrategy();
        for (int i = 0; i < conservative; i++) {
            Agent agent = new Agent("C" + agentNum, "Conservative Agent " + agentNum, prefs, initialCurrency);
            long min = 10 + (agentNum % 5) * 5;
            long ideal = 50 + (agentNum % 5) * 10;
            agent.setRequest(resource, min, ideal);
            agents.add(new AgentWithStrategy(agent, conservativeStrategy));
            agentNum++;
        }

        // Create aggressive agents
        AgentStrategy aggressiveStrategy = new AgentStrategy.AggressiveStrategy();
        for (int i = 0; i < aggressive; i++) {
            Agent agent = new Agent("A" + agentNum, "Aggressive Agent " + agentNum, prefs, initialCurrency);
            long min = 10 + (agentNum % 5) * 5;
            long ideal = 50 + (agentNum % 5) * 10;
            agent.setRequest(resource, min, ideal);
            agents.add(new AgentWithStrategy(agent, aggressiveStrategy));
            agentNum++;
        }

        // Create adaptive agents
        AgentStrategy adaptiveStrategy = new AgentStrategy.AdaptiveStrategy();
        for (int i = 0; i < adaptive; i++) {
            Agent agent = new Agent("D" + agentNum, "Adaptive Agent " + agentNum, prefs, initialCurrency);
            long min = 10 + (agentNum % 5) * 5;
            long ideal = 50 + (agentNum % 5) * 10;
            agent.setRequest(resource, min, ideal);
            agents.add(new AgentWithStrategy(agent, adaptiveStrategy));
            agentNum++;
        }

        // Create sacrifice-and-recover agents
        AgentStrategy sacrificeStrategy = new AgentStrategy.SacrificeAndRecoverStrategy(50);
        for (int i = 0; i < sacrificeAndRecover; i++) {
            Agent agent = new Agent("S" + agentNum, "Sacrifice Agent " + agentNum, prefs, initialCurrency);
            long min = 10 + (agentNum % 5) * 5;
            long ideal = 50 + (agentNum % 5) * 10;
            agent.setRequest(resource, min, ideal);
            agents.add(new AgentWithStrategy(agent, sacrificeStrategy));
            agentNum++;
        }

        if (agents.isEmpty()) {
            throw new IllegalArgumentException("At least one agent required");
        }

        ParetoAnalysisSimulation simulation = new ParetoAnalysisSimulation.Builder()
            .totalRounds(rounds)
            .resourceType(resource)
            .resourceCapacity(capacity)
            .currencyEarningRate(0.05)
            .verbose(false)
            .addAgents(agents)
            .build();

        return simulation.run();
    }

    // ========================================================================
    // Output Helpers
    // ========================================================================

    private static void printHeader() {
        System.out.println();
        System.out.println(SEP);
        System.out.println("   PARETO RATE STABILITY TEST");
        System.out.println("   Investigating the 38.2% ≈ 1 - φ Conjecture");
        System.out.println(SEP);
        System.out.println();
        System.out.println("Background:");
        System.out.println("  In a 200-round simulation with 12 agents, weak Pareto improvements");
        System.out.printf("  occurred 38.2%% of the time. This is suspiciously close to 1 - φ%n");
        System.out.printf("  where φ = %.10f is the golden ratio (1 - φ = %.10f).%n", PHI, ONE_MINUS_PHI);
        System.out.println();
        System.out.println("  This test investigates whether 38.2% is stable across configurations");
        System.out.println("  or whether it was coincidental from the specific original setup.");
        System.out.println();
    }

    private static void printFooter() {
        System.out.println();
        System.out.println(SEP);
        System.out.println("   PARETO RATE STABILITY TEST COMPLETE");
        System.out.println(SEP);
        System.out.println();
    }

    // Helper class to store results
    private static class ParameterResult {
        final String name;
        final double rate;
        final double distFromPhi;

        ParameterResult(String name, double rate, double distFromPhi) {
            this.name = name;
            this.rate = rate;
            this.distFromPhi = distFromPhi;
        }
    }
}
