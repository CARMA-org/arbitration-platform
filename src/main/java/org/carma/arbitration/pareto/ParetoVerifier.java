package org.carma.arbitration.pareto;

import org.carma.arbitration.model.Agent;
import org.carma.arbitration.model.AllocationResult;
import org.carma.arbitration.model.Contention;

import java.math.BigDecimal;
import java.util.*;

/**
 * Mathematical verification of Pareto properties.
 *
 * Pareto Optimality: No agent can be made better off without making another worse off.
 * Pareto Improvement: At least one agent better off, no agent worse off.
 */
public class ParetoVerifier {

    private static final double EPSILON = 1e-9;

    /**
     * Comparison result between two allocation states.
     */
    public record ParetoComparison(
        boolean isParetoImprovement,    // At least one better, none worse
        boolean isStrictImprovement,    // ALL agents better
        List<String> betterOffAgents,
        List<String> worseOffAgents,
        List<String> unchangedAgents,
        double totalUtilityChange
    ) {
        public int countBetterOff() { return betterOffAgents.size(); }
        public int countWorseOff() { return worseOffAgents.size(); }
        public int countUnchanged() { return unchangedAgents.size(); }
    }

    /**
     * Verify Pareto optimality of a single-round allocation.
     *
     * An allocation is Pareto optimal if no agent can be made better off
     * without making another agent worse off.
     *
     * For WPF with water-filling, this is guaranteed by the KKT conditions,
     * but we verify empirically by checking all pairwise transfers.
     */
    public boolean isParetoOptimal(
            Map<String, Long> allocations,
            Map<String, Double> weights,
            long totalAvailable) {

        List<String> agents = new ArrayList<>(allocations.keySet());

        // For each pair of agents, check if transferring 1 unit helps one without hurting other
        for (int i = 0; i < agents.size(); i++) {
            for (int j = 0; j < agents.size(); j++) {
                if (i == j) continue;

                String agentI = agents.get(i);
                String agentJ = agents.get(j);

                long allocI = allocations.get(agentI);
                long allocJ = allocations.get(agentJ);

                // Can't transfer if donor has nothing
                if (allocI <= 0) continue;

                double weightI = weights.getOrDefault(agentI, 1.0);
                double weightJ = weights.getOrDefault(agentJ, 1.0);

                // Utility before (log-based WPF utility)
                double utilityIBefore = allocI > 0 ? weightI * Math.log(allocI) : Double.NEGATIVE_INFINITY;
                double utilityJBefore = allocJ > 0 ? weightJ * Math.log(allocJ) : Double.NEGATIVE_INFINITY;

                // Utility after transferring 1 unit from I to J
                double utilityIAfter = (allocI - 1) > 0 ? weightI * Math.log(allocI - 1) : Double.NEGATIVE_INFINITY;
                double utilityJAfter = weightJ * Math.log(allocJ + 1);

                // Check if J improves AND I doesn't worsen (Pareto improvement exists)
                if (utilityJAfter > utilityJBefore + EPSILON &&
                    utilityIAfter >= utilityIBefore - EPSILON) {
                    return false; // Found a Pareto improvement, so not optimal
                }
            }
        }
        return true;
    }

    /**
     * Compare two states (e.g., round N vs round N-1) for Pareto improvement.
     * A Pareto improvement exists if at least one agent is better off
     * and no agent is worse off.
     *
     * @param before agent -> utility at earlier time
     * @param after agent -> utility at later time
     */
    public ParetoComparison compareParetoStates(
            Map<String, Double> before,
            Map<String, Double> after) {

        List<String> betterOff = new ArrayList<>();
        List<String> worseOff = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        double totalChange = 0.0;

        Set<String> allAgents = new HashSet<>();
        allAgents.addAll(before.keySet());
        allAgents.addAll(after.keySet());

        for (String agentId : allAgents) {
            double utilBefore = before.getOrDefault(agentId, 0.0);
            double utilAfter = after.getOrDefault(agentId, 0.0);
            double diff = utilAfter - utilBefore;
            totalChange += diff;

            if (diff > EPSILON) {
                betterOff.add(agentId);
            } else if (diff < -EPSILON) {
                worseOff.add(agentId);
            } else {
                unchanged.add(agentId);
            }
        }

        boolean isParetoImprovement = !betterOff.isEmpty() && worseOff.isEmpty();
        boolean isStrictImprovement = betterOff.size() == allAgents.size() && worseOff.isEmpty();

        return new ParetoComparison(
            isParetoImprovement,
            isStrictImprovement,
            betterOff,
            worseOff,
            unchanged,
            totalChange
        );
    }

    /**
     * Check if state B is a strict Pareto improvement over state A.
     * (All agents strictly better off)
     */
    public boolean isStrictParetoImprovement(
            Map<String, Double> before,
            Map<String, Double> after) {
        return compareParetoStates(before, after).isStrictImprovement();
    }

    /**
     * Check if state B is a weak Pareto improvement over state A.
     * (At least one better off, none worse off)
     */
    public boolean isParetoImprovement(
            Map<String, Double> before,
            Map<String, Double> after) {
        return compareParetoStates(before, after).isParetoImprovement();
    }

    /**
     * Calculate total weighted welfare (sum of weighted log utilities).
     */
    public double calculateWelfare(
            Map<String, Long> allocations,
            Map<String, Double> weights) {

        double welfare = 0.0;
        for (var entry : allocations.entrySet()) {
            double weight = weights.getOrDefault(entry.getKey(), 1.0);
            long alloc = entry.getValue();
            if (alloc > 0) {
                welfare += weight * Math.log(alloc);
            }
        }
        return welfare;
    }

    /**
     * Calculate Gini coefficient for allocation fairness.
     * 0 = perfect equality, 1 = maximum inequality
     */
    public double calculateGini(Map<String, Long> allocations) {
        if (allocations.size() < 2) return 0.0;

        long[] values = allocations.values().stream()
            .mapToLong(Long::longValue).sorted().toArray();
        int n = values.length;
        double sum = 0;
        double total = 0;

        for (int i = 0; i < n; i++) {
            sum += (2 * (i + 1) - n - 1) * values[i];
            total += values[i];
        }

        if (total == 0) return 0.0;
        return sum / (n * total);
    }
}
