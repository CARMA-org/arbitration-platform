package org.carma.arbitration.model;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Result of an arbitration process.
 * 
 * Contains:
 * - Allocations per agent
 * - Currency burned per agent
 * - Objective value achieved
 * - Computation metadata
 */
public class AllocationResult {
    
    private final ResourceType resourceType;
    private final Map<String, Long> allocations;
    private final Map<String, BigDecimal> currencyBurned;
    private double objectiveValue;
    private boolean feasible;
    private String message;
    private long computationTimeMs;

    public AllocationResult(ResourceType type) {
        this.resourceType = type;
        this.allocations = new HashMap<>();
        this.currencyBurned = new HashMap<>();
        this.objectiveValue = 0.0;
        this.feasible = true;
        this.message = "Success";
        this.computationTimeMs = 0;
    }

    // ========================================================================
    // Builder-style setters
    // ========================================================================

    public AllocationResult setAllocation(String agentId, long amount) {
        allocations.put(agentId, amount);
        return this;
    }

    public AllocationResult setCurrencyBurned(String agentId, BigDecimal amount) {
        currencyBurned.put(agentId, amount);
        return this;
    }

    public AllocationResult setObjectiveValue(double value) {
        this.objectiveValue = value;
        return this;
    }

    public AllocationResult setFeasible(boolean feasible) {
        this.feasible = feasible;
        return this;
    }

    public AllocationResult setMessage(String message) {
        this.message = message;
        return this;
    }

    public AllocationResult setComputationTimeMs(long ms) {
        this.computationTimeMs = ms;
        return this;
    }

    // ========================================================================
    // Accessors
    // ========================================================================

    public ResourceType getResourceType() {
        return resourceType;
    }

    public long getAllocation(String agentId) {
        return allocations.getOrDefault(agentId, 0L);
    }

    public Map<String, Long> getAllocations() {
        return new HashMap<>(allocations);
    }

    public BigDecimal getCurrencyBurned(String agentId) {
        return currencyBurned.getOrDefault(agentId, BigDecimal.ZERO);
    }

    public Map<String, BigDecimal> getCurrencyBurnedMap() {
        return new HashMap<>(currencyBurned);
    }

    public double getObjectiveValue() {
        return objectiveValue;
    }

    public boolean isFeasible() {
        return feasible;
    }

    public String getMessage() {
        return message;
    }

    public long getComputationTimeMs() {
        return computationTimeMs;
    }

    // ========================================================================
    // Computed Properties
    // ========================================================================

    public long getTotalAllocated() {
        return allocations.values().stream().mapToLong(Long::longValue).sum();
    }

    public BigDecimal getTotalCurrencyBurned() {
        return currencyBurned.values().stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public int getAgentCount() {
        return allocations.size();
    }

    public double getAverageAllocation() {
        if (allocations.isEmpty()) return 0.0;
        return (double) getTotalAllocated() / allocations.size();
    }

    /**
     * Calculate Gini coefficient of allocations (0 = perfect equality, 1 = maximum inequality).
     */
    public double getGiniCoefficient() {
        if (allocations.size() < 2) return 0.0;
        
        long[] values = allocations.values().stream().mapToLong(Long::longValue).sorted().toArray();
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

    // ========================================================================
    // Object Methods
    // ========================================================================

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("AllocationResult[").append(resourceType).append("]:\n");
        sb.append("  Feasible: ").append(feasible).append("\n");
        sb.append("  Objective: ").append(String.format("%.4f", objectiveValue)).append("\n");
        sb.append("  Total: ").append(getTotalAllocated()).append("\n");
        sb.append("  Allocations:\n");
        for (var entry : allocations.entrySet()) {
            sb.append("    ").append(entry.getKey()).append(": ").append(entry.getValue());
            BigDecimal burned = currencyBurned.get(entry.getKey());
            if (burned != null && burned.compareTo(BigDecimal.ZERO) > 0) {
                sb.append(" (burned ").append(burned).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
