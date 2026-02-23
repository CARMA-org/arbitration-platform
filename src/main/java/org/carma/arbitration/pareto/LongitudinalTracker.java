package org.carma.arbitration.pareto;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tracks per-agent metrics across all rounds for longitudinal analysis.
 */
public class LongitudinalTracker {

    /**
     * Snapshot of an agent's state at a single round.
     */
    public record RoundSnapshot(
        int round,
        long allocation,
        double utility,          // weighted log utility
        double currencyBefore,
        double currencyBurned,
        double currencyAfter,
        double satisfaction,     // allocation / ideal
        double cumulativeUtility // running sum
    ) {}

    /**
     * Complete history for a single agent.
     */
    public static class AgentHistory {
        private final String agentId;
        private final AgentStrategy strategy;
        private final List<RoundSnapshot> snapshots;
        private final long idealAllocation;

        public AgentHistory(String agentId, AgentStrategy strategy, long idealAllocation) {
            this.agentId = agentId;
            this.strategy = strategy;
            this.snapshots = new ArrayList<>();
            this.idealAllocation = idealAllocation;
        }

        public void addSnapshot(RoundSnapshot snapshot) {
            snapshots.add(snapshot);
        }

        public String getAgentId() { return agentId; }
        public AgentStrategy getStrategy() { return strategy; }
        public List<RoundSnapshot> getSnapshots() { return Collections.unmodifiableList(snapshots); }
        public long getIdealAllocation() { return idealAllocation; }

        public double getCumulativeUtility() {
            if (snapshots.isEmpty()) return 0;
            return snapshots.get(snapshots.size() - 1).cumulativeUtility();
        }

        public double getTotalAllocation() {
            return snapshots.stream().mapToLong(RoundSnapshot::allocation).sum();
        }

        public double getAverageAllocation() {
            return snapshots.stream().mapToLong(RoundSnapshot::allocation).average().orElse(0);
        }

        public double getTotalCurrencyBurned() {
            return snapshots.stream().mapToDouble(RoundSnapshot::currencyBurned).sum();
        }

        public double getFinalCurrency() {
            if (snapshots.isEmpty()) return 0;
            return snapshots.get(snapshots.size() - 1).currencyAfter();
        }

        public double getInitialCurrency() {
            if (snapshots.isEmpty()) return 0;
            return snapshots.get(0).currencyBefore();
        }

        public double getAverageSatisfaction() {
            return snapshots.stream().mapToDouble(RoundSnapshot::satisfaction).average().orElse(0);
        }

        /**
         * Utility per currency burned (efficiency).
         */
        public double getUtilityPerCurrency() {
            double burned = getTotalCurrencyBurned();
            if (burned < 0.01) return Double.MAX_VALUE; // Avoid division by zero
            return getCumulativeUtility() / burned;
        }

        /**
         * Compare final cumulative utility to first round.
         */
        public double getUtilityGrowthFactor() {
            if (snapshots.isEmpty()) return 0;
            double first = snapshots.get(0).utility();
            if (first <= 0) return getCumulativeUtility();
            return getCumulativeUtility() / first;
        }
    }

    // ==========================================================================
    // Fields
    // ==========================================================================

    private final Map<String, AgentHistory> histories;
    private final List<ParetoVerifier.ParetoComparison> roundComparisons;
    private final List<Boolean> paretoOptimalityChecks;

    public LongitudinalTracker() {
        this.histories = new LinkedHashMap<>(); // Preserve insertion order
        this.roundComparisons = new ArrayList<>();
        this.paretoOptimalityChecks = new ArrayList<>();
    }

    // ==========================================================================
    // Registration
    // ==========================================================================

    public void registerAgent(String agentId, AgentStrategy strategy, long idealAllocation) {
        histories.put(agentId, new AgentHistory(agentId, strategy, idealAllocation));
    }

    // ==========================================================================
    // Recording
    // ==========================================================================

    public void recordRound(
            String agentId,
            int round,
            long allocation,
            double utility,
            double currencyBefore,
            double currencyBurned,
            double currencyAfter,
            double satisfaction) {

        AgentHistory history = histories.get(agentId);
        if (history == null) {
            throw new IllegalArgumentException("Unknown agent: " + agentId);
        }

        double previousCumulative = history.getCumulativeUtility();
        double newCumulative = previousCumulative + utility;

        history.addSnapshot(new RoundSnapshot(
            round, allocation, utility, currencyBefore, currencyBurned,
            currencyAfter, satisfaction, newCumulative
        ));
    }

    public void recordParetoOptimality(boolean isOptimal) {
        paretoOptimalityChecks.add(isOptimal);
    }

    public void recordRoundComparison(ParetoVerifier.ParetoComparison comparison) {
        roundComparisons.add(comparison);
    }

    // ==========================================================================
    // Analysis
    // ==========================================================================

    public AgentHistory getHistory(String agentId) {
        return histories.get(agentId);
    }

    public Collection<AgentHistory> getAllHistories() {
        return histories.values();
    }

    /**
     * Get agents ranked by cumulative utility (highest first).
     */
    public List<AgentHistory> getRankedByCumulativeUtility() {
        return histories.values().stream()
            .sorted((a, b) -> Double.compare(b.getCumulativeUtility(), a.getCumulativeUtility()))
            .collect(Collectors.toList());
    }

    /**
     * Get agents that are better off (positive cumulative utility).
     */
    public List<String> getBetterOffAgents() {
        return histories.values().stream()
            .filter(h -> h.getCumulativeUtility() > 0)
            .map(AgentHistory::getAgentId)
            .collect(Collectors.toList());
    }

    /**
     * Get agents that are worse off (negative or zero cumulative utility).
     */
    public List<String> getWorseOffAgents() {
        return histories.values().stream()
            .filter(h -> h.getCumulativeUtility() <= 0)
            .map(AgentHistory::getAgentId)
            .collect(Collectors.toList());
    }

    /**
     * Are ALL agents better off?
     */
    public boolean areAllAgentsBetterOff() {
        return histories.values().stream()
            .allMatch(h -> h.getCumulativeUtility() > 0);
    }

    /**
     * Get strategy effectiveness (average cumulative utility per strategy).
     */
    public Map<String, Double> getStrategyEffectiveness() {
        return histories.values().stream()
            .collect(Collectors.groupingBy(
                h -> h.getStrategy().getName(),
                Collectors.averagingDouble(AgentHistory::getCumulativeUtility)
            ));
    }

    /**
     * Get average utility per currency by strategy.
     */
    public Map<String, Double> getStrategyEfficiency() {
        Map<String, List<AgentHistory>> byStrategy = histories.values().stream()
            .collect(Collectors.groupingBy(h -> h.getStrategy().getName()));

        Map<String, Double> result = new LinkedHashMap<>();
        for (var entry : byStrategy.entrySet()) {
            double totalUtil = entry.getValue().stream()
                .mapToDouble(AgentHistory::getCumulativeUtility).sum();
            double totalBurned = entry.getValue().stream()
                .mapToDouble(AgentHistory::getTotalCurrencyBurned).sum();
            result.put(entry.getKey(), totalBurned > 0.01 ? totalUtil / totalBurned : 0);
        }
        return result;
    }

    /**
     * Did aggressive strategies (higher burn) achieve higher total utility?
     */
    public boolean didSacrificingAgentsGainMore() {
        Map<String, Double> effectiveness = getStrategyEffectiveness();
        Double aggressive = effectiveness.get("Aggressive");
        Double conservative = effectiveness.get("Conservative");
        if (aggressive == null || conservative == null) return false;
        return aggressive > conservative;
    }

    /**
     * Percentage of rounds that were Pareto optimal.
     */
    public double getParetoOptimalityRate() {
        if (paretoOptimalityChecks.isEmpty()) return 0;
        long optimal = paretoOptimalityChecks.stream().filter(b -> b).count();
        return (double) optimal / paretoOptimalityChecks.size();
    }

    /**
     * Percentage of round transitions that were Pareto improvements.
     */
    public double getParetoImprovementRate() {
        if (roundComparisons.isEmpty()) return 0;
        long improvements = roundComparisons.stream()
            .filter(ParetoVerifier.ParetoComparison::isParetoImprovement).count();
        return (double) improvements / roundComparisons.size();
    }

    /**
     * Count of strict Pareto improvements (all agents better off).
     */
    public long getStrictImprovementCount() {
        return roundComparisons.stream()
            .filter(ParetoVerifier.ParetoComparison::isStrictImprovement).count();
    }

    // ==========================================================================
    // CSV Export
    // ==========================================================================

    public String generateCSV() {
        StringBuilder csv = new StringBuilder();
        csv.append("round,agent_id,strategy,allocation,utility,currency_before,");
        csv.append("currency_burned,currency_after,satisfaction,cumulative_utility\n");

        for (AgentHistory history : histories.values()) {
            for (RoundSnapshot snap : history.getSnapshots()) {
                csv.append(snap.round()).append(",");
                csv.append(history.getAgentId()).append(",");
                csv.append(history.getStrategy().getName()).append(",");
                csv.append(snap.allocation()).append(",");
                csv.append(String.format("%.4f", snap.utility())).append(",");
                csv.append(String.format("%.2f", snap.currencyBefore())).append(",");
                csv.append(String.format("%.2f", snap.currencyBurned())).append(",");
                csv.append(String.format("%.2f", snap.currencyAfter())).append(",");
                csv.append(String.format("%.4f", snap.satisfaction())).append(",");
                csv.append(String.format("%.4f", snap.cumulativeUtility())).append("\n");
            }
        }

        return csv.toString();
    }
}
