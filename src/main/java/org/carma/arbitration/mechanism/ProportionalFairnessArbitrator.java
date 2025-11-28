package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Implements Weighted Proportional Fairness arbitration.
 * 
 * Mathematical formulation:
 *   maximize: Σᵢ cᵢ · log(aᵢ)
 *   subject to: 
 *     Σᵢ aᵢ ≤ Q        (resource limit)
 *     aᵢ ≥ minᵢ        (minimum requirements)
 *     aᵢ ≤ idealᵢ      (maximum requests)
 * 
 * Where:
 *   cᵢ = BaseWeight + CurrencyBurned_i (priority weight)
 *   aᵢ = allocation to agent i
 *   Q = total available quantity
 * 
 * Theoretical properties:
 * - Theorem 1: Pareto optimality
 * - Theorem 2: Polynomial time complexity
 * - Theorem 3: Collusion resistance (via logarithmic barrier)
 * - Theorem 5: Individual rationality
 */
public class ProportionalFairnessArbitrator {
    
    private static final double EPSILON = 1e-9;
    private static final int MAX_ITERATIONS = 100;
    
    private final PriorityEconomy economy;

    public ProportionalFairnessArbitrator(PriorityEconomy economy) {
        this.economy = economy;
    }

    public ProportionalFairnessArbitrator() {
        this(new PriorityEconomy());
    }

    // ========================================================================
    // Main Arbitration Method
    // ========================================================================

    /**
     * Arbitrate a single-resource contention.
     * 
     * @param contention The contention to resolve
     * @param currencyCommitments Map of agent ID to currency they're willing to burn
     * @return AllocationResult with optimal allocations
     */
    public AllocationResult arbitrate(Contention contention, Map<String, BigDecimal> currencyCommitments) {
        long startTime = System.currentTimeMillis();
        
        List<Agent> agents = contention.getCompetingAgents();
        int n = agents.size();
        ResourceType resource = contention.getResourceType();
        long available = contention.getAvailableQuantity();
        
        AllocationResult result = new AllocationResult(resource);
        
        // Check feasibility
        if (!contention.isFeasible()) {
            return result
                .setFeasible(false)
                .setMessage("Infeasible: total minimums (" + contention.getTotalMinimum() + 
                    ") exceed available supply (" + available + ")")
                .setComputationTimeMs(System.currentTimeMillis() - startTime);
        }
        
        // Trivial case: single agent
        if (n == 1) {
            Agent agent = agents.get(0);
            long alloc = Math.min(agent.getIdeal(resource), available);
            return result
                .setAllocation(agent.getId(), alloc)
                .setCurrencyBurned(agent.getId(), BigDecimal.ZERO)
                .setObjectiveValue(PriorityEconomy.BASE_WEIGHT * Math.log(Math.max(1, alloc)))
                .setComputationTimeMs(System.currentTimeMillis() - startTime);
        }
        
        // Collect agent data into arrays for efficient computation
        double[] weights = new double[n];
        long[] mins = new long[n];
        long[] ideals = new long[n];
        String[] ids = new String[n];
        BigDecimal[] burns = new BigDecimal[n];
        
        for (int i = 0; i < n; i++) {
            Agent a = agents.get(i);
            ids[i] = a.getId();
            mins[i] = a.getMinimum(resource);
            ideals[i] = a.getIdeal(resource);
            burns[i] = currencyCommitments.getOrDefault(a.getId(), BigDecimal.ZERO);
            weights[i] = economy.calculatePriorityWeight(burns[i]);
        }
        
        // Solve using water-filling algorithm
        double[] solution = waterFilling(weights, mins, ideals, available);
        
        // Round to integers while maintaining feasibility
        long[] intAlloc = roundToIntegers(solution, mins, ideals, available);
        
        // Build result
        double objectiveValue = 0;
        for (int i = 0; i < n; i++) {
            result.setAllocation(ids[i], intAlloc[i]);
            result.setCurrencyBurned(ids[i], burns[i]);
            if (intAlloc[i] > 0) {
                objectiveValue += weights[i] * Math.log(intAlloc[i]);
            }
        }
        
        return result
            .setObjectiveValue(objectiveValue)
            .setComputationTimeMs(System.currentTimeMillis() - startTime);
    }

    // ========================================================================
    // Water-Filling Algorithm
    // ========================================================================

    /**
     * Water-filling algorithm for Weighted Proportional Fairness.
     * 
     * This finds the exact optimal solution to:
     *   max Σ wᵢ·log(aᵢ) s.t. Σaᵢ ≤ Q, minᵢ ≤ aᵢ ≤ idealᵢ
     * 
     * Algorithm:
     * 1. Allocate minimums to everyone
     * 2. Distribute remaining supply proportional to weights
     * 3. When an agent hits their ideal, freeze them and redistribute
     * 4. Repeat until all supply distributed or all agents frozen
     */
    private double[] waterFilling(double[] weights, long[] mins, long[] ideals, long available) {
        int n = weights.length;
        double[] alloc = new double[n];
        boolean[] frozen = new boolean[n];
        
        // Step 1: Allocate minimums
        double remaining = available;
        for (int i = 0; i < n; i++) {
            alloc[i] = mins[i];
            remaining -= mins[i];
        }
        
        if (remaining < EPSILON) {
            return alloc; // Only minimums fit
        }
        
        // Step 2: Iteratively distribute remaining supply
        for (int iter = 0; iter < MAX_ITERATIONS && remaining > EPSILON; iter++) {
            // Calculate active weight (agents that can receive more)
            double activeWeight = 0;
            int activeCount = 0;
            List<Integer> activeAgents = new ArrayList<>();
            
            for (int i = 0; i < n; i++) {
                if (!frozen[i] && alloc[i] < ideals[i]) {
                    activeWeight += weights[i];
                    activeCount++;
                    activeAgents.add(i);
                }
            }
            
            // BUGFIX: Robust check for degenerate cases
            if (activeCount == 0) break;
            if (activeWeight < EPSILON) {
                // All active agents have near-zero weight - distribute equally
                double equalShare = remaining / activeCount;
                for (int i : activeAgents) {
                    double slack = ideals[i] - alloc[i];
                    alloc[i] += Math.min(equalShare, slack);
                }
                remaining = 0;
                break;
            }
            
            // Find the agent that will hit their ideal first
            double minFillRatio = Double.MAX_VALUE;
            int bottleneckAgent = -1;
            
            for (int i = 0; i < n; i++) {
                if (frozen[i] || alloc[i] >= ideals[i]) continue;
                
                double slack = ideals[i] - alloc[i];
                // BUGFIX: Additional guard against division issues
                double share = (activeWeight > EPSILON) 
                    ? (weights[i] / activeWeight) * remaining 
                    : remaining / activeCount;
                
                if (share > slack + EPSILON) {
                    double fillRatio = slack / share;
                    if (fillRatio < minFillRatio) {
                        minFillRatio = fillRatio;
                        bottleneckAgent = i;
                    }
                }
            }
            
            if (bottleneckAgent >= 0 && minFillRatio < 1.0 - EPSILON) {
                // Distribute up to the bottleneck point
                double toDistribute = remaining * minFillRatio;
                for (int i = 0; i < n; i++) {
                    if (!frozen[i] && alloc[i] < ideals[i]) {
                        double share = (activeWeight > EPSILON)
                            ? (weights[i] / activeWeight) * toDistribute
                            : toDistribute / activeCount;
                        alloc[i] += share;
                    }
                }
                remaining -= toDistribute;
                
                // Freeze the bottleneck agent at their ideal
                alloc[bottleneckAgent] = ideals[bottleneckAgent];
                frozen[bottleneckAgent] = true;
            } else {
                // No bottleneck - distribute all remaining
                for (int i = 0; i < n; i++) {
                    if (!frozen[i] && alloc[i] < ideals[i]) {
                        double share = (activeWeight > EPSILON)
                            ? (weights[i] / activeWeight) * remaining
                            : remaining / activeCount;
                        alloc[i] += share;
                    }
                }
                remaining = 0;
                break;
            }
        }
        
        // BUGFIX: Dump any remaining supply to agents with slack (ensures Σ allocations == available)
        if (remaining > EPSILON) {
            for (int i = 0; i < n; i++) {
                double slack = ideals[i] - alloc[i];
                if (slack > EPSILON) {
                    double toAdd = Math.min(remaining, slack);
                    alloc[i] += toAdd;
                    remaining -= toAdd;
                    if (remaining < EPSILON) break;
                }
            }
        }
        
        // Final bounds check
        for (int i = 0; i < n; i++) {
            alloc[i] = Math.max(mins[i], Math.min(ideals[i], alloc[i]));
        }
        
        return alloc;
    }

    // ========================================================================
    // Integer Rounding
    // ========================================================================

    /**
     * Round continuous solution to integers while maintaining feasibility.
     * Uses largest-remainder method for fair rounding.
     */
    private long[] roundToIntegers(double[] solution, long[] mins, long[] ideals, long available) {
        int n = solution.length;
        long[] result = new long[n];
        long total = 0;
        
        // Floor all values (respecting minimums)
        for (int i = 0; i < n; i++) {
            result[i] = Math.max(mins[i], Math.min(ideals[i], (long) Math.floor(solution[i])));
            total += result[i];
        }
        
        // Distribute remaining units by fractional part (largest first)
        long remaining = available - total;
        if (remaining > 0) {
            // Create list of (index, fractional part) pairs
            List<int[]> fractionals = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (result[i] < ideals[i]) {
                    double frac = solution[i] - Math.floor(solution[i]);
                    fractionals.add(new int[]{i, (int) (frac * 10000)});
                }
            }
            // Sort by fractional part descending
            fractionals.sort((a, b) -> b[1] - a[1]);
            
            for (int[] f : fractionals) {
                if (remaining <= 0) break;
                int i = f[0];
                if (result[i] < ideals[i]) {
                    result[i]++;
                    remaining--;
                }
            }
        }
        
        // Safety check: if we exceeded available, scale back
        total = 0;
        for (long r : result) total += r;
        if (total > available) {
            long excess = total - available;
            // Remove from agents with most slack above minimum
            List<int[]> slacks = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                long slack = result[i] - mins[i];
                if (slack > 0) {
                    slacks.add(new int[]{i, (int) slack});
                }
            }
            slacks.sort((a, b) -> b[1] - a[1]);
            
            for (int[] s : slacks) {
                if (excess <= 0) break;
                int i = s[0];
                long canRemove = Math.min(result[i] - mins[i], excess);
                result[i] -= canRemove;
                excess -= canRemove;
            }
        }
        
        return result;
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Calculate total welfare for a set of allocations.
     * Welfare = Σᵢ cᵢ · log(Φᵢ(A))
     */
    public double calculateWelfare(List<Agent> agents, Map<String, BigDecimal> burns) {
        double welfare = 0;
        for (Agent agent : agents) {
            double utility = agent.getCurrentUtility();
            if (utility > 0) {
                double weight = economy.calculatePriorityWeight(
                    burns.getOrDefault(agent.getId(), BigDecimal.ZERO));
                welfare += weight * Math.log(utility);
            }
        }
        return welfare;
    }

    /**
     * Calculate welfare from allocation result.
     */
    public double calculateWelfare(AllocationResult result, List<Agent> agents) {
        double welfare = 0;
        for (Agent agent : agents) {
            long alloc = result.getAllocation(agent.getId());
            if (alloc > 0) {
                BigDecimal burned = result.getCurrencyBurned(agent.getId());
                double weight = economy.calculatePriorityWeight(burned);
                welfare += weight * Math.log(alloc);
            }
        }
        return welfare;
    }

    public PriorityEconomy getEconomy() {
        return economy;
    }
}
