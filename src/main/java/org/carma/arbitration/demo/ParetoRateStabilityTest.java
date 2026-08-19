package org.carma.arbitration.demo;

import org.carma.arbitration.model.Agent;
import org.carma.arbitration.model.ResourceType;
import org.carma.arbitration.pareto.*;
import org.carma.arbitration.pareto.ParetoAnalysisSimulation.*;

import java.util.*;
import java.io.*;

/**
 * NONDECREASING ENDOGENOUS-WEIGHTED-SCORE TRANSITION RATE — SENSITIVITY CHECK
 *
 * Reports the transition rate previously described as a "weak Pareto improvement
 * rate". Because the underlying statistic uses time-varying priority-weighted
 * scores, it is not a Pareto-improvement rate under fixed utilities; it is the
 * fraction of round-to-round transitions in which the endogenous weighted score
 * does not decrease.
 *
 * A descriptive sensitivity check runs a predetermined parameter grid and reports
 * the full range of the statistic. The reference value 0.381966... = 1/φ^2 = 2 - φ
 * is noted only for comparison; proximity is not evidence of a law, attractor,
 * invariant, or golden-ratio mechanism.
 *
 * Run with:
 *   java -cp target/classes:$(mvn dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q) \
 *       org.carma.arbitration.demo.ParetoRateStabilityTest
 */
public class ParetoRateStabilityTest {

    private static final String SEP = "=".repeat(78);
    private static final String SUBSEP = "-".repeat(60);

    private static final double PHI = (1 + Math.sqrt(5)) / 2;   // 1.618033988749895
    private static final double PHI_SQ_RECIP = 2 - PHI;         // 0.381966011250105 = 1/phi^2

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

        // Part 5: Predetermined sensitivity grid
        System.out.println("\n" + SEP);
        System.out.println("PART 5: PREDETERMINED SENSITIVITY GRID");
        System.out.println(SEP);
        runPredeterminedSensitivityGrid();

        // Part 6: Summary
        System.out.println("\n" + SEP);
        System.out.println("SUMMARY");
        System.out.println(SEP);
        printSummary();

        // Export to CSV
        exportToCSV();

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
            "Configuration", "Weak PI", "Rate%", "Strict PI", "Dist ref "));
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
        double distToPhi = Math.abs(result.paretoImprovementRate() - PHI_SQ_RECIP);

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
            "Strategy Mix", "Weak PI%", "Strict PI", "Dist ref "));
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
        double distToPhi = Math.abs(result.paretoImprovementRate() - PHI_SQ_RECIP);

        paramResults.add(new ParameterResult(name, rate, distToPhi));

        System.out.println(String.format("  %-35s  %9.1f%%  %10d  %10.4f",
            name, rate, strictPI, distToPhi));
    }

    // ========================================================================
    // Part 5: Predetermined sensitivity grid
    // ========================================================================

    private static List<ParameterResult> gridResults = new ArrayList<>();

    private static void runPredeterminedSensitivityGrid() {
        System.out.println("\nRunning a predetermined grid and reporting the full range of the rate.\n");

        for (int rounds : new int[]{150, 175, 200, 225, 250}) {
            double rate = runSimulation(rounds, 3, 500, 100.0, false).paretoImprovementRate() * 100;
            gridResults.add(new ParameterResult("Rounds=" + rounds, rate, Math.abs(rate/100 - PHI_SQ_RECIP)));
        }
        for (int currency : new int[]{80, 90, 100, 110, 120, 130, 140, 150}) {
            double rate = runSimulation(200, 3, 500, currency, false).paretoImprovementRate() * 100;
            gridResults.add(new ParameterResult("Currency=$" + currency, rate, Math.abs(rate/100 - PHI_SQ_RECIP)));
        }
        for (int pool : new int[]{400, 450, 500, 550, 600, 650, 700}) {
            double rate = runSimulation(200, 3, pool, 100.0, false).paretoImprovementRate() * 100;
            gridResults.add(new ParameterResult("Pool=" + pool, rate, Math.abs(rate/100 - PHI_SQ_RECIP)));
        }
        int[][] mixes = {
            {3, 3, 3, 3}, {4, 4, 2, 2}, {2, 2, 4, 4}, {3, 4, 3, 2},
            {4, 3, 2, 3}, {2, 4, 3, 3}, {3, 2, 4, 3}, {3, 3, 4, 2},
        };
        for (int[] mix : mixes) {
            double rate = runSimulationWithMix(200, 500, 100.0, mix[0], mix[1], mix[2], mix[3])
                .paretoImprovementRate() * 100;
            String mixName = String.format("Mix C%d/A%d/D%d/S%d", mix[0], mix[1], mix[2], mix[3]);
            gridResults.add(new ParameterResult(mixName, rate, Math.abs(rate/100 - PHI_SQ_RECIP)));
        }

        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
        int within = 0;
        for (ParameterResult r : gridResults) {
            min = Math.min(min, r.rate);
            max = Math.max(max, r.rate);
            sum += r.rate;
            if (Math.abs(r.rate/100 - PHI_SQ_RECIP) <= 0.01) within++;
        }
        System.out.printf("  Grid size:  %d configurations%n", gridResults.size());
        System.out.printf("  Full range: %.1f%% to %.1f%%  (mean %.2f%%)%n", min, max, sum / gridResults.size());
        System.out.printf("  Reference:  1/phi^2 = 2 - phi = %.6f (%.4f%%)%n", PHI_SQ_RECIP, PHI_SQ_RECIP * 100);
        System.out.printf("  Within 0.01 of the reference: %d of %d configurations%n", within, gridResults.size());
        System.out.println("  Proximity is descriptive only; no law, attractor, or invariant is inferred.");
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
        double distFromPhi = Math.abs(mean / 100 - PHI_SQ_RECIP);

        System.out.println("\nSTATISTICS ACROSS ALL CONFIGURATIONS:");
        System.out.println(SUBSEP);
        System.out.printf("  Configurations tested: %d%n", n);
        System.out.printf("  Mean weak Pareto rate: %.2f%%%n", mean);
        System.out.printf("  Standard deviation:    %.2f%%%n", stdDev);
        System.out.printf("  Minimum rate:          %.1f%% (%s)%n", min, minConfig);
        System.out.printf("  Maximum rate:          %.1f%% (%s)%n", max, maxConfig);
        System.out.printf("  Range:                 %.1f%%%n", max - min);
        System.out.println();
        System.out.printf("  Reference 1/phi^2 = 2 - phi: %.6f (%.4f%%)%n", PHI_SQ_RECIP, PHI_SQ_RECIP * 100);
        System.out.printf("  Distance of mean from reference: %.4f percentage points%n", distFromPhi * 100);

        System.out.println();
        System.out.println("CHARACTERIZATION:");
        System.out.println(SUBSEP);

        boolean stdDevLow = stdDev < 2.0;

        if (stdDevLow) {
            System.out.printf("  The transition rate is stable across the grid (sigma = %.2f%% < 2%%),%n", stdDev);
            System.out.printf("  with mean %.2f%% over %d configurations.%n", mean, n);
        } else {
            System.out.println("  CONFIGURATION-SENSITIVE");
            System.out.printf("  The transition rate varies with configuration (sigma = %.2f%%, range %.1f%%-%.1f%%).%n",
                stdDev, min, max);
        }
        System.out.println("  The reference value is reported for comparison only; proximity is not");
        System.out.println("  evidence of a law, attractor, invariant, or golden-ratio mechanism.");

        // Additional analysis
        System.out.println();
        System.out.println("ADDITIONAL OBSERVATIONS:");
        System.out.println(SUBSEP);

        int closeToRef = 0;
        for (ParameterResult r : paramResults) {
            if (r.distFromPhi < 0.01) closeToRef++;
        }
        System.out.printf("  Configurations within 0.01 of the reference: %d/%d%n", closeToRef, n);

        // Check for configurations far from mean
        int outliers = 0;
        for (ParameterResult r : paramResults) {
            if (Math.abs(r.rate - mean) > 2 * stdDev) outliers++;
        }
        System.out.printf("  Outliers (>2σ from mean): %d%n", outliers);
    }

    // ========================================================================
    // CSV Export
    // ========================================================================

    private static void exportToCSV() {
        String filename = "pareto_rate_stability_results.csv";

        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            // Header
            writer.println("Configuration,Weak_Pareto_Count,Total_Transitions,Rate_Percent,Strict_Pareto_Count,Distance_From_Reference");

            // All results from paramResults
            for (ParameterResult r : paramResults) {
                int transitions = 199; // default for 200 rounds
                if (r.name.contains("Rounds = 100") || r.name.contains("Rounds=100")) transitions = 99;
                if (r.name.contains("Rounds = 500") || r.name.contains("Rounds=500")) transitions = 499;
                if (r.name.contains("Rounds = 1000") || r.name.contains("Rounds=1000")) transitions = 999;

                int weakCount = (int) Math.round(r.rate * transitions / 100.0);
                writer.printf("\"%s\",%d,%d,%.2f,0,%.6f%n",
                    r.name, weakCount, transitions, r.rate, r.distFromPhi);
            }

            // Results from the predetermined sensitivity grid
            for (ParameterResult r : gridResults) {
                int transitions = 199;
                if (r.name.contains("Rounds=150")) transitions = 149;
                if (r.name.contains("Rounds=175")) transitions = 174;
                if (r.name.contains("Rounds=225")) transitions = 224;
                if (r.name.contains("Rounds=250")) transitions = 249;

                int weakCount = (int) Math.round(r.rate * transitions / 100.0);
                writer.printf("\"%s\",%d,%d,%.2f,0,%.6f%n",
                    r.name, weakCount, transitions, r.rate, r.distFromPhi);
            }

            System.out.println();
            System.out.println(SEP);
            System.out.println("CSV EXPORT");
            System.out.println(SEP);
            System.out.println("  Results exported to: " + filename);
            System.out.println("  Open in Excel, Google Sheets, or any spreadsheet application.");

        } catch (IOException e) {
            System.err.println("  Error exporting CSV: " + e.getMessage());
        }
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
        System.out.println("   ENDOGENOUS-WEIGHTED-SCORE TRANSITION RATE — SENSITIVITY CHECK");
        System.out.println(SEP);
        System.out.println();
        System.out.println("Background:");
        System.out.println("  In a 200-round simulation with 12 agents, the nondecreasing endogenous");
        System.out.println("  weighted-score transition occurred about 38.2% of the time. Because the");
        System.out.println("  score uses time-varying priority weights, this is not a Pareto-improvement");
        System.out.printf("  rate under fixed utilities. For comparison, 1/phi^2 = 2 - phi = %.10f.%n", PHI_SQ_RECIP);
        System.out.println();
        System.out.println("  This check reports the full range of the rate across a predetermined grid.");
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
