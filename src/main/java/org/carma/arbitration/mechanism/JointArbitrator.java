package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Interface for joint multi-resource arbitration.
 * 
 * Unlike single-resource arbitration which optimizes each resource independently,
 * joint arbitration considers all resources simultaneously to find globally
 * Pareto-optimal allocations that enable cross-resource trades.
 * 
 * Mathematical formulation:
 *   maximize: Σᵢ cᵢ · log(Φᵢ(A))
 *   
 *   where Φᵢ(A) = Σⱼ wᵢⱼ · aᵢⱼ  (weighted sum across ALL resources j)
 *   
 *   subject to:
 *     Σᵢ aᵢⱼ ≤ Qⱼ           ∀j  (resource capacity constraints)
 *     aᵢⱼ ≥ minᵢⱼ           ∀i,j (minimum requirements)
 *     aᵢⱼ ≤ idealᵢⱼ         ∀i,j (maximum requests)
 *     aᵢⱼ ≥ 0               ∀i,j (non-negativity)
 */
public interface JointArbitrator {

    /**
     * Result of joint arbitration across multiple resources.
     */
    class JointAllocationResult {
        private final Map<String, Map<ResourceType, Long>> allocations;
        private final Map<String, BigDecimal> currencyBurned;
        private final double objectiveValue;
        private final boolean feasible;
        private final String message;
        private final long computationTimeMs;

        public JointAllocationResult(
                Map<String, Map<ResourceType, Long>> allocations,
                Map<String, BigDecimal> currencyBurned,
                double objectiveValue,
                boolean feasible,
                String message,
                long computationTimeMs) {
            this.allocations = allocations;
            this.currencyBurned = currencyBurned;
            this.objectiveValue = objectiveValue;
            this.feasible = feasible;
            this.message = message;
            this.computationTimeMs = computationTimeMs;
        }

        public Map<ResourceType, Long> getAllocations(String agentId) {
            return allocations.getOrDefault(agentId, Collections.emptyMap());
        }

        public long getAllocation(String agentId, ResourceType type) {
            return getAllocations(agentId).getOrDefault(type, 0L);
        }

        public Map<String, Map<ResourceType, Long>> getAllAllocations() {
            return Collections.unmodifiableMap(allocations);
        }

        public BigDecimal getCurrencyBurned(String agentId) {
            return currencyBurned.getOrDefault(agentId, BigDecimal.ZERO);
        }

        public double getObjectiveValue() { return objectiveValue; }
        public boolean isFeasible() { return feasible; }
        public String getMessage() { return message; }
        public long getComputationTimeMs() { return computationTimeMs; }

        /**
         * Calculate total welfare achieved.
         */
        public double calculateWelfare(List<Agent> agents, PriorityEconomy economy) {
            double welfare = 0;
            for (Agent agent : agents) {
                Map<ResourceType, Long> agentAllocs = getAllocations(agent.getId());
                double utility = 0;
                for (var entry : agentAllocs.entrySet()) {
                    utility += agent.getPreferences().getWeight(entry.getKey()) * entry.getValue();
                }
                if (utility > 0) {
                    double weight = economy.calculatePriorityWeight(
                        currencyBurned.getOrDefault(agent.getId(), BigDecimal.ZERO));
                    welfare += weight * Math.log(utility);
                }
            }
            return welfare;
        }

        @Override
        public String toString() {
            return String.format("JointAllocationResult[feasible=%s, objective=%.4f, time=%dms]",
                feasible, objectiveValue, computationTimeMs);
        }
    }

    /**
     * Perform joint arbitration across all resources for a contention group.
     * 
     * @param group The contention group to arbitrate
     * @param currencyCommitments Currency each agent is willing to burn
     * @return Joint allocation result
     */
    JointAllocationResult arbitrate(
        ContentionDetector.ContentionGroup group,
        Map<String, BigDecimal> currencyCommitments);

    /**
     * Perform joint arbitration for a list of agents and resource pool.
     */
    JointAllocationResult arbitrate(
        List<Agent> agents,
        ResourcePool pool,
        Map<String, BigDecimal> currencyCommitments);
}
