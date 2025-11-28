package org.carma.arbitration.model;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a resource contention where multiple agents compete
 * for limited resources.
 * 
 * A contention exists when: totalDemand > availableSupply
 */
public class Contention {
    
    private final ResourceType resourceType;
    private final List<Agent> competingAgents;
    private final long availableQuantity;
    private final long totalDemand;
    private final long totalMinimum;

    public Contention(ResourceType type, List<Agent> agents, long available) {
        this.resourceType = type;
        this.competingAgents = new ArrayList<>(agents);
        this.availableQuantity = available;
        this.totalDemand = agents.stream()
            .mapToLong(a -> a.getIdeal(type))
            .sum();
        this.totalMinimum = agents.stream()
            .mapToLong(a -> a.getMinimum(type))
            .sum();
    }

    // ========================================================================
    // Accessors
    // ========================================================================

    public ResourceType getResourceType() {
        return resourceType;
    }

    public List<Agent> getCompetingAgents() {
        return new ArrayList<>(competingAgents);
    }

    public int getAgentCount() {
        return competingAgents.size();
    }

    public long getAvailableQuantity() {
        return availableQuantity;
    }

    public long getTotalDemand() {
        return totalDemand;
    }

    public long getTotalMinimum() {
        return totalMinimum;
    }

    // ========================================================================
    // Analysis
    // ========================================================================

    /**
     * Get contention ratio: totalDemand / availableSupply
     * Higher values indicate more severe contention.
     */
    public double getContentionRatio() {
        if (availableQuantity == 0) return Double.MAX_VALUE;
        return (double) totalDemand / availableQuantity;
    }

    /**
     * Check if contention is feasible (minimums can be satisfied).
     */
    public boolean isFeasible() {
        return totalMinimum <= availableQuantity;
    }

    /**
     * Check if there is actual contention (demand exceeds supply).
     */
    public boolean hasContention() {
        return totalDemand > availableQuantity;
    }

    /**
     * Check if contention is severe (ratio > 2.0).
     */
    public boolean isSevere() {
        return getContentionRatio() > 2.0;
    }

    /**
     * Get slack (available minus minimum requirements).
     */
    public long getSlack() {
        return availableQuantity - totalMinimum;
    }

    /**
     * Get list of agent IDs in this contention.
     */
    public List<String> getAgentIds() {
        return competingAgents.stream()
            .map(Agent::getId)
            .collect(Collectors.toList());
    }

    // ========================================================================
    // Object Methods
    // ========================================================================

    @Override
    public String toString() {
        return String.format("Contention[%s: %d agents, demand=%d, available=%d, ratio=%.2f, feasible=%s]",
            resourceType.name(), competingAgents.size(), totalDemand, availableQuantity,
            getContentionRatio(), isFeasible());
    }

    /**
     * Generate detailed report of the contention.
     */
    public String toDetailedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Contention Report: ").append(resourceType).append("\n");
        sb.append("  Available: ").append(availableQuantity).append("\n");
        sb.append("  Total Demand: ").append(totalDemand).append("\n");
        sb.append("  Total Minimum: ").append(totalMinimum).append("\n");
        sb.append("  Contention Ratio: ").append(String.format("%.2f", getContentionRatio())).append("\n");
        sb.append("  Feasible: ").append(isFeasible()).append("\n");
        sb.append("  Agents:\n");
        for (Agent agent : competingAgents) {
            sb.append(String.format("    %s: min=%d, ideal=%d\n",
                agent.getId(), agent.getMinimum(resourceType), agent.getIdeal(resourceType)));
        }
        return sb.toString();
    }
}
