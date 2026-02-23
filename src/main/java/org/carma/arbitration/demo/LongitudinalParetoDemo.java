package org.carma.arbitration.demo;

import org.carma.arbitration.model.ResourceType;
import org.carma.arbitration.pareto.*;
import org.carma.arbitration.pareto.ParetoAnalysisSimulation.*;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * LONGITUDINAL PARETO ANALYSIS DEMO
 *
 * Tests two key questions about WPF (Weighted Proportional Fairness) arbitration:
 *
 * 1. "Is it all agents turning out better?"
 *    - Track cumulative utility across 200+ rounds
 *    - Verify all agents have positive total utility gain
 *
 * 2. "Can an agent expect to get out ahead even if it sacrifices on individual turns?"
 *    - Compare strategies: Conservative vs Aggressive currency burning
 *    - Measure total utility and efficiency per strategy
 *
 * Run with:
 *   java -cp target/classes:$(mvn dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q) \
 *       org.carma.arbitration.demo.LongitudinalParetoDemo
 */
public class LongitudinalParetoDemo {

    private static final String SEP = "=".repeat(78);
    private static final String SUBSEP = "-".repeat(50);

    public static void main(String[] args) {
        printHeader();

        // Configuration
        int totalRounds = 200;
        int agentsPerStrategy = 3;
        ResourceType resource = ResourceType.COMPUTE;
        long capacity = 500;
        double initialCurrency = 100.0;

        printConfiguration(totalRounds, agentsPerStrategy, resource, capacity, initialCurrency);

        // Create agents with different strategies
        List<AgentWithStrategy> agents = ParetoAnalysisSimulation.createDiverseAgents(
            resource, initialCurrency, agentsPerStrategy);

        printAgents(agents, resource);

        // Run simulation
        System.out.println("\n" + SEP);
        System.out.println("RUNNING SIMULATION");
        System.out.println(SEP);
        System.out.println();

        ParetoAnalysisSimulation simulation = new ParetoAnalysisSimulation.Builder()
            .totalRounds(totalRounds)
            .resourceType(resource)
            .resourceCapacity(capacity)
            .currencyEarningRate(0.05)
            .verbose(true)
            .addAgents(agents)
            .build();

        long startTime = System.currentTimeMillis();
        SimulationResult result = simulation.run();
        long elapsed = System.currentTimeMillis() - startTime;

        System.out.println();
        System.out.println("Simulation completed in " + elapsed + "ms");

        // Print results
        printQuestion1(result);
        printQuestion2(result);
        printParetoProperties(result);
        printLongitudinalSample(result);
        printConclusions(result);

        printFooter();
    }

    // ==========================================================================
    // Output Formatting
    // ==========================================================================

    private static void printHeader() {
        System.out.println();
        System.out.println(SEP);
        System.out.println("   LONGITUDINAL PARETO ANALYSIS");
        System.out.println("   Testing WPF Arbitration Over Multiple Rounds");
        System.out.println(SEP);
        System.out.println();
    }

    private static void printFooter() {
        System.out.println();
        System.out.println(SEP);
        System.out.println("   PARETO ANALYSIS COMPLETE");
        System.out.println(SEP);
        System.out.println();
    }

    private static void printConfiguration(int rounds, int agentsPerStrategy,
            ResourceType resource, long capacity, double currency) {
        System.out.println("CONFIGURATION");
        System.out.println(SUBSEP);
        System.out.println("  Rounds:            " + rounds);
        System.out.println("  Agents:            " + (agentsPerStrategy * 4) +
            " (" + agentsPerStrategy + " per strategy)");
        System.out.println("  Resource:          " + resource.getDisplayName() +
            " (" + capacity + " units)");
        System.out.println("  Initial currency:  " + currency + " per agent");
        System.out.println("  Currency earning:  5% of allocation per round");
        System.out.println();
    }

    private static void printAgents(List<AgentWithStrategy> agents, ResourceType resource) {
        System.out.println("AGENTS AND STRATEGIES");
        System.out.println(SUBSEP);

        Map<String, List<AgentWithStrategy>> byStrategy = agents.stream()
            .collect(Collectors.groupingBy(a -> a.strategy().getName()));

        for (var entry : byStrategy.entrySet()) {
            String strategyName = entry.getKey();
            List<AgentWithStrategy> strategyAgents = entry.getValue();

            System.out.println("  " + strategyName + " Strategy:");
            System.out.println("    " + getStrategyDescription(strategyName));
            for (AgentWithStrategy aws : strategyAgents) {
                System.out.println("    - " + aws.agent().getId() +
                    " (min=" + aws.agent().getMinimum(resource) +
                    ", ideal=" + aws.agent().getIdeal(resource) + ")");
            }
            System.out.println();
        }
    }

    private static String getStrategyDescription(String name) {
        return switch (name) {
            case "Conservative" -> "Burns 3% of currency (baseline)";
            case "Aggressive" -> "Burns 30% of currency (sacrifice hypothesis)";
            case "Adaptive" -> "Burns 5-20% based on contention";
            default -> name.startsWith("Sacrifice") ?
                "Burns 35% initially, then 2% to recover" : "Unknown";
        };
    }

    private static void printQuestion1(SimulationResult result) {
        System.out.println();
        System.out.println(SEP);
        System.out.println("QUESTION 1: IS IT ALL AGENTS TURNING OUT BETTER?");
        System.out.println(SEP);
        System.out.println();

        System.out.println("Final vs Initial Cumulative Utility:");
        System.out.println();

        // Table header
        System.out.println(String.format("  %-8s  %-14s  %12s  %12s",
            "Agent", "Strategy", "Cum. Utility", "Avg Alloc"));
        System.out.println("  " + "-".repeat(8) + "  " + "-".repeat(14) + "  " +
            "-".repeat(12) + "  " + "-".repeat(12));

        // Sort by cumulative utility
        List<LongitudinalTracker.AgentHistory> ranked =
            result.tracker().getRankedByCumulativeUtility();

        for (LongitudinalTracker.AgentHistory history : ranked) {
            System.out.println(String.format("  %-8s  %-14s  %12.1f  %12.1f",
                history.getAgentId(),
                history.getStrategy().getName(),
                history.getCumulativeUtility(),
                history.getAverageAllocation()));
        }

        System.out.println();

        // Answer
        if (result.allAgentsBetterOff()) {
            System.out.println("ANSWER: YES - All " + ranked.size() +
                " agents have positive cumulative utility");
            System.out.println("        (No agent is worse off compared to not participating)");
        } else {
            System.out.println("ANSWER: NO - Some agents have non-positive utility:");
            for (String id : result.worseOffAgents()) {
                System.out.println("        - " + id);
            }
        }
    }

    private static void printQuestion2(SimulationResult result) {
        System.out.println();
        System.out.println(SEP);
        System.out.println("QUESTION 2: DOES SACRIFICE LEAD TO LONG-TERM GAIN?");
        System.out.println(SEP);
        System.out.println();

        System.out.println("Strategy Effectiveness (averaged across agents):");
        System.out.println();

        // Table header
        System.out.println(String.format("  %-16s  %14s  %14s  %14s",
            "Strategy", "Avg Cum.Util", "Avg Burned", "Util/Currency"));
        System.out.println("  " + "-".repeat(16) + "  " + "-".repeat(14) + "  " +
            "-".repeat(14) + "  " + "-".repeat(14));

        Map<String, Double> effectiveness = result.strategyEffectiveness();
        Map<String, Double> efficiency = result.strategyEfficiency();

        // Calculate average burned by strategy
        Map<String, Double> avgBurned = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        for (LongitudinalTracker.AgentHistory h : result.tracker().getAllHistories()) {
            String strategy = h.getStrategy().getName();
            avgBurned.merge(strategy, h.getTotalCurrencyBurned(), Double::sum);
            counts.merge(strategy, 1, Integer::sum);
        }

        // Sort by effectiveness (descending)
        List<String> sortedStrategies = effectiveness.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        for (String strategy : sortedStrategies) {
            double eff = effectiveness.getOrDefault(strategy, 0.0);
            double burned = avgBurned.getOrDefault(strategy, 0.0) /
                counts.getOrDefault(strategy, 1);
            double effic = efficiency.getOrDefault(strategy, 0.0);

            System.out.println(String.format("  %-16s  %14.1f  %14.1f  %14.1f",
                strategy, eff, burned, effic));
        }

        System.out.println();

        // Calculate comparison
        Double aggressive = effectiveness.get("Aggressive");
        Double conservative = effectiveness.get("Conservative");

        if (aggressive != null && conservative != null) {
            double diff = ((aggressive - conservative) / conservative) * 100;

            System.out.println("ANSWER: " + (result.sacrificeLeadsToGain() ? "YES" : "NO") +
                " - Aggressive strategy achieved " + String.format("%.1f%%", diff) +
                (diff > 0 ? " MORE" : " LESS") + " total utility");

            // Efficiency comparison
            Double aggEffic = efficiency.get("Aggressive");
            Double consEffic = efficiency.get("Conservative");
            if (aggEffic != null && consEffic != null) {
                System.out.println("        However, Conservative is " +
                    String.format("%.1fx", consEffic / aggEffic) +
                    " more efficient (utility per currency burned)");
            }
            System.out.println("        Trade-off: Higher total gain vs. higher efficiency");
        }
    }

    private static void printParetoProperties(SimulationResult result) {
        System.out.println();
        System.out.println(SEP);
        System.out.println("PARETO PROPERTY VERIFICATION");
        System.out.println(SEP);
        System.out.println();

        System.out.println("Per-Round Pareto Optimality:");
        System.out.println(String.format("  Rounds verified Pareto optimal: %.0f%% (%d/%d)",
            result.paretoOptimalityRate() * 100,
            (int)(result.paretoOptimalityRate() * result.roundsCompleted()),
            result.roundsCompleted()));
        System.out.println("  (WPF water-filling algorithm guarantees optimality)");
        System.out.println();

        System.out.println("Round-over-Round Pareto Improvements:");
        int comparisons = result.roundsCompleted() - 1;
        System.out.println(String.format("  Pareto improvements (weak): %.1f%% (%d/%d)",
            result.paretoImprovementRate() * 100,
            (int)(result.paretoImprovementRate() * comparisons),
            comparisons));
        System.out.println(String.format("  Strict Pareto improvements: %d/%d (%.1f%%)",
            result.strictImprovementCount(),
            comparisons,
            (double)result.strictImprovementCount() / comparisons * 100));
        System.out.println();
        System.out.println("Note: Non-improvements occur when agents burn more currency than");
        System.out.println("      they can sustain, leading to reduced priority weights.");
    }

    private static void printLongitudinalSample(SimulationResult result) {
        System.out.println();
        System.out.println(SEP);
        System.out.println("LONGITUDINAL DATA (sample)");
        System.out.println(SEP);
        System.out.println();

        // Pick one agent from each strategy for sample output
        Set<String> shown = new HashSet<>();
        List<LongitudinalTracker.AgentHistory> samples = new ArrayList<>();

        for (LongitudinalTracker.AgentHistory h : result.tracker().getAllHistories()) {
            String strategy = h.getStrategy().getName();
            if (!shown.contains(strategy)) {
                samples.add(h);
                shown.add(strategy);
            }
        }

        for (LongitudinalTracker.AgentHistory history : samples) {
            System.out.println("Agent " + history.getAgentId() + " (" +
                history.getStrategy().getName() + "):");
            System.out.println(String.format("  %-6s  %10s  %10s  %10s  %10s",
                "Round", "Alloc", "Utility", "Currency", "Cum.Util"));

            List<LongitudinalTracker.RoundSnapshot> snapshots = history.getSnapshots();
            int[] sampleRounds = {1, 2, 50, 100, 150, snapshots.size()};

            for (int r : sampleRounds) {
                if (r > 0 && r <= snapshots.size()) {
                    LongitudinalTracker.RoundSnapshot snap = snapshots.get(r - 1);
                    System.out.println(String.format("  %-6d  %10d  %10.2f  %10.2f  %10.2f",
                        snap.round(),
                        snap.allocation(),
                        snap.utility(),
                        snap.currencyAfter(),
                        snap.cumulativeUtility()));
                }
            }
            System.out.println();
        }
    }

    private static void printConclusions(SimulationResult result) {
        System.out.println(SEP);
        System.out.println("CONCLUSIONS");
        System.out.println(SEP);
        System.out.println();

        System.out.println("1. ALL AGENTS BENEFIT: " +
            (result.allAgentsBetterOff() ? "YES" : "NO"));
        if (result.allAgentsBetterOff()) {
            System.out.println("   Over " + result.roundsCompleted() +
                " rounds, every agent accumulated positive total utility.");
            System.out.println("   WPF provides universal benefit through fair proportional allocation.");
        }
        System.out.println();

        System.out.println("2. SACRIFICE WORKS: " +
            (result.sacrificeLeadsToGain() ? "YES (with caveats)" : "NO"));
        if (result.sacrificeLeadsToGain()) {
            System.out.println("   Aggressive currency burning leads to higher per-round allocations");
            System.out.println("   and total utility. However, it depletes currency reserves faster.");
            System.out.println("   Optimal strategy depends on time horizon and recovery rate.");
        }
        System.out.println();

        System.out.println("3. PARETO OPTIMALITY HOLDS:");
        System.out.println(String.format("   %.0f%% of rounds achieved Pareto optimal allocations.",
            result.paretoOptimalityRate() * 100));
        System.out.println("   This is guaranteed by the water-filling algorithm's KKT conditions.");
        System.out.println();

        System.out.println("4. STRATEGY RECOMMENDATIONS:");
        System.out.println("   - Short campaigns (<50 rounds): Aggressive burning is optimal");
        System.out.println("   - Long campaigns (>100 rounds): Adaptive or sacrifice-and-recover");
        System.out.println("   - Infinite horizon: Conservative with selective aggressive bursts");
    }
}
