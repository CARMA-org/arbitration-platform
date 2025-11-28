package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.Agent;
import org.carma.arbitration.model.ResourcePool;
import org.carma.arbitration.model.ResourceType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages the Priority Economy mechanism.
 * 
 * The Priority Economy provides dynamic resource pricing through:
 * - Currency earning: Agents earn by releasing resources early
 * - Currency burning: Agents burn currency to increase priority weight
 * 
 * Key formulas:
 * - Priority weight: cᵢ = BaseWeight + CurrencyBurned
 * - Release earnings: quantity × timeRemaining × demandMultiplier
 * - Demand multiplier: based on resource utilization
 */
public class PriorityEconomy {
    
    /** Base weight ensuring all agents can participate */
    public static final double BASE_WEIGHT = 10.0;
    
    /** Multiplier range */
    private static final double MIN_MULTIPLIER = 1.0;
    private static final double MAX_MULTIPLIER = 5.0;
    
    /** 
     * EMA smoothing factor (alpha) for demand multiplier.
     * Lower values = more smoothing = slower response but less oscillation.
     * Formula: smoothed = alpha * current + (1-alpha) * previous
     * 
     * 0.15 provides good damping while still responding to sustained changes.
     */
    private static final double EMA_ALPHA = 0.15;
    
    private final Map<ResourceType, Double> demandMultipliers;
    private final Map<ResourceType, Double> smoothedMultipliers;
    private final Map<ResourceType, Double> utilizationHistory;

    public PriorityEconomy() {
        this.demandMultipliers = new HashMap<>();
        this.smoothedMultipliers = new HashMap<>();
        this.utilizationHistory = new HashMap<>();
        
        // Initialize with base multipliers
        for (ResourceType type : ResourceType.values()) {
            demandMultipliers.put(type, MIN_MULTIPLIER);
            smoothedMultipliers.put(type, MIN_MULTIPLIER);
            utilizationHistory.put(type, 0.0);
        }
    }

    // ========================================================================
    // Priority Weight Calculation
    // ========================================================================

    /**
     * Calculate priority weight from base weight plus burned currency.
     * Formula: cᵢ = BaseWeight + CurrencyBurned
     */
    public double calculatePriorityWeight(BigDecimal currencyBurned) {
        return BASE_WEIGHT + currencyBurned.doubleValue();
    }

    /**
     * Calculate priority weight for an agent given their burn commitment.
     */
    public double calculatePriorityWeight(Agent agent, BigDecimal burnCommitment) {
        return BASE_WEIGHT + burnCommitment.doubleValue();
    }

    // ========================================================================
    // Currency Earning
    // ========================================================================

    /**
     * Calculate earnings for releasing resources early.
     * 
     * Formula: earnings = quantity × timeRemainingFraction × demandMultiplier
     * 
     * @param type Resource type being released
     * @param quantity Amount being released
     * @param timeRemainingFraction Fraction of lease time remaining (0.0 to 1.0)
     * @param pool Current resource pool state
     * @return Currency earned
     */
    public BigDecimal calculateReleaseEarnings(
            ResourceType type, 
            long quantity, 
            double timeRemainingFraction,
            ResourcePool pool) {
        
        if (quantity <= 0 || timeRemainingFraction <= 0) {
            return BigDecimal.ZERO;
        }
        
        double multiplier = getDemandMultiplier(type, pool);
        double earnings = quantity * timeRemainingFraction * multiplier;
        
        return BigDecimal.valueOf(earnings).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate demand multiplier based on resource scarcity with EMA smoothing.
     * Higher utilization = higher multiplier = more incentive to release.
     * 
     * Uses Exponential Moving Average to dampen oscillations:
     * smoothed = α × current + (1-α) × previous
     * 
     * This prevents the classic control theory problem where:
     * utilization spikes → multiplier spikes → agents release → 
     * supply floods → multiplier crashes → agents stop releasing → cycle repeats
     */
    public double getDemandMultiplier(ResourceType type, ResourcePool pool) {
        double utilization = pool.getUtilization(type);
        
        // Calculate instantaneous (raw) multiplier
        double rawMultiplier = MIN_MULTIPLIER + (MAX_MULTIPLIER - MIN_MULTIPLIER) * utilization;
        
        // Apply EMA smoothing: smoothed = alpha * current + (1-alpha) * previous
        double previousSmoothed = smoothedMultipliers.getOrDefault(type, MIN_MULTIPLIER);
        double smoothedMultiplier = EMA_ALPHA * rawMultiplier + (1 - EMA_ALPHA) * previousSmoothed;
        
        // Update cached values
        demandMultipliers.put(type, rawMultiplier);       // Raw for diagnostics
        smoothedMultipliers.put(type, smoothedMultiplier); // Smoothed for use
        utilizationHistory.put(type, utilization);
        
        return smoothedMultiplier;
    }

    /**
     * Get cached smoothed demand multiplier for a resource type.
     */
    public double getCachedMultiplier(ResourceType type) {
        return smoothedMultipliers.getOrDefault(type, MIN_MULTIPLIER);
    }
    
    /**
     * Get cached raw (unsmoothed) demand multiplier for diagnostics.
     */
    public double getRawMultiplier(ResourceType type) {
        return demandMultipliers.getOrDefault(type, MIN_MULTIPLIER);
    }
    
    /**
     * Get current EMA alpha value for external tuning.
     */
    public static double getEmaAlpha() {
        return EMA_ALPHA;
    }

    // ========================================================================
    // Currency Burning Strategy
    // ========================================================================

    /**
     * Suggest optimal burn amount based on competition and resources.
     * 
     * Strategy: Burn up to X% of balance where X depends on:
     * - Contention ratio (higher = burn more)
     * - Resource importance (preference weight)
     * 
     * @param agent The agent deciding how much to burn
     * @param contentionRatio Demand/supply ratio for contested resource
     * @param preferenceWeight Agent's preference weight for this resource
     * @return Suggested burn amount
     */
    public BigDecimal suggestBurnAmount(Agent agent, double contentionRatio, double preferenceWeight) {
        BigDecimal balance = agent.getCurrencyBalance();
        if (balance.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        
        // Base burn fraction: 5-20% based on contention
        double baseFraction = Math.min(0.20, 0.05 + 0.05 * (contentionRatio - 1.0));
        
        // Scale by preference weight (more important = willing to burn more)
        double scaledFraction = baseFraction * preferenceWeight;
        
        // Cap at 25% of balance per round
        scaledFraction = Math.min(0.25, scaledFraction);
        
        return balance.multiply(BigDecimal.valueOf(scaledFraction))
            .setScale(2, RoundingMode.HALF_UP);
    }

    // ========================================================================
    // State Management
    // ========================================================================

    /**
     * Update all demand multipliers based on current pool state.
     */
    public void updateMultipliers(ResourcePool pool) {
        for (ResourceType type : ResourceType.values()) {
            getDemandMultiplier(type, pool);
        }
    }

    /**
     * Get current state of the economy for reporting.
     */
    public Map<ResourceType, Double> getMultiplierSnapshot() {
        return new HashMap<>(demandMultipliers);
    }

    /**
     * Get utilization history for reporting.
     */
    public Map<ResourceType, Double> getUtilizationSnapshot() {
        return new HashMap<>(utilizationHistory);
    }

    // ========================================================================
    // Metrics
    // ========================================================================

    /**
     * Calculate total effective weight for a set of burn commitments.
     */
    public double calculateTotalWeight(Map<String, BigDecimal> burns) {
        double total = 0;
        for (BigDecimal burn : burns.values()) {
            total += calculatePriorityWeight(burn);
        }
        return total;
    }

    /**
     * Calculate weight share for an agent.
     */
    public double calculateWeightShare(BigDecimal agentBurn, Map<String, BigDecimal> allBurns) {
        double totalWeight = calculateTotalWeight(allBurns);
        if (totalWeight == 0) return 0;
        return calculatePriorityWeight(agentBurn) / totalWeight;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("PriorityEconomy[\n");
        sb.append("  BaseWeight: ").append(BASE_WEIGHT).append("\n");
        sb.append("  EMA Alpha: ").append(EMA_ALPHA).append("\n");
        sb.append("  Raw Multipliers: ").append(demandMultipliers).append("\n");
        sb.append("  Smoothed Multipliers: ").append(smoothedMultipliers).append("\n");
        sb.append("  Utilization: ").append(utilizationHistory).append("\n");
        sb.append("]");
        return sb.toString();
    }
}
