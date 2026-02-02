package org.carma.arbitration.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an agent participating in the arbitration platform.
 * 
 * Each agent has:
 * - Unique identifier and name
 * - Preference function over resources
 * - Currency balance for the Priority Economy
 * - Minimum and ideal resource requests
 * - Current allocations
 */

public class Agent {
    
    /** Base weight for all agents, ensuring participation even with zero currency */
    public static final double BASE_WEIGHT = 10.0;
    
    /** Minimum allowed currency balance (small debt allowed) */
    public static final BigDecimal MIN_BALANCE = BigDecimal.valueOf(-100);

    private final String id;
    private final String name;
    private final PreferenceFunction preferences;
    private BigDecimal currencyBalance;
    private final Map<ResourceType, Long> minimumRequests;
    private final Map<ResourceType, Long> idealRequests;
    private final Map<ResourceType, Long> currentAllocations;

    public Agent(String id, String name, PreferenceFunction preferences, double initialCurrency) {
        this.id = Objects.requireNonNull(id, "Agent ID cannot be null");
        this.name = Objects.requireNonNull(name, "Agent name cannot be null");
        this.preferences = Objects.requireNonNull(preferences, "Preferences cannot be null");
        this.currencyBalance = BigDecimal.valueOf(initialCurrency);
        this.minimumRequests = new HashMap<>();
        this.idealRequests = new HashMap<>();
        this.currentAllocations = new HashMap<>();
    }

    /**
     * Convenience constructor with preference map.
     */
    public Agent(String id, String name, Map<ResourceType, Double> prefs, double initialCurrency) {
        this(id, name, new PreferenceFunction(prefs), initialCurrency);
    }

    // ========================================================================
    // Request Management
    // ========================================================================

    /**
     * Set resource request bounds.
     * @param type Resource type
     * @param min Minimum required quantity
     * @param ideal Maximum desired quantity
     */
    public void setRequest(ResourceType type, long min, long ideal) {
        if (min < 0) throw new IllegalArgumentException("Minimum cannot be negative");
        if (ideal < 0) throw new IllegalArgumentException("Ideal cannot be negative");
        if (min > ideal) throw new IllegalArgumentException("Minimum cannot exceed ideal");
        minimumRequests.put(type, min);
        idealRequests.put(type, ideal);
    }

    public long getMinimum(ResourceType type) {
        return minimumRequests.getOrDefault(type, 0L);
    }

    public long getIdeal(ResourceType type) {
        return idealRequests.getOrDefault(type, 0L);
    }

    public Map<ResourceType, Long> getMinimumRequests() {
        return new HashMap<>(minimumRequests);
    }

    public Map<ResourceType, Long> getIdealRequests() {
        return new HashMap<>(idealRequests);
    }

    // ========================================================================
    // Allocation Management
    // ========================================================================

    public void setAllocation(ResourceType type, long amount) {
        if (amount < 0) throw new IllegalArgumentException("Allocation cannot be negative");
        currentAllocations.put(type, amount);
    }

    public long getAllocation(ResourceType type) {
        return currentAllocations.getOrDefault(type, 0L);
    }

    public Map<ResourceType, Long> getCurrentAllocations() {
        return new HashMap<>(currentAllocations);
    }

    public void clearAllocations() {
        currentAllocations.clear();
    }

    /**
     * Calculate current utility from allocations.
     * Φᵢ(A) = Σⱼ wᵢⱼ · aᵢⱼ
     */
    public double getCurrentUtility() {
        return preferences.evaluate(currentAllocations);
    }

    // ========================================================================
    // Currency Management
    // ========================================================================

    public BigDecimal getCurrencyBalance() {
        return currencyBalance;
    }

    /**
     * Calculate priority weight: BaseWeight + CurrencyBurned
     */
    public double getPriorityWeight(BigDecimal currencyBurned) {
        return BASE_WEIGHT + currencyBurned.doubleValue();
    }

    /**
     * Check if agent can burn the specified amount.
     */
    public boolean canBurn(BigDecimal amount) {
        return currencyBalance.subtract(amount).compareTo(MIN_BALANCE) >= 0;
    }

    /**
     * Burn currency to increase priority weight.
     * Currency is destroyed, not transferred.
     */
    public void burnCurrency(BigDecimal amount) {
        if (!canBurn(amount)) {
            throw new IllegalStateException(
                "Insufficient currency: balance=" + currencyBalance + ", requested=" + amount);
        }
        currencyBalance = currencyBalance.subtract(amount);
    }

    /**
     * Earn currency (e.g., from releasing resources early).
     */
    public void earnCurrency(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Cannot earn negative currency");
        }
        currencyBalance = currencyBalance.add(amount);
    }

    /**
     * Set currency balance directly (for simulation purposes).
     */
    public void setCurrencyBalance(BigDecimal balance) {
        this.currencyBalance = balance;
    }

    // ========================================================================
    // Accessors
    // ========================================================================

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public PreferenceFunction getPreferences() {
        return preferences;
    }

    public double getPreference(ResourceType type) {
        return preferences.getWeight(type);
    }

    // ========================================================================
    // Object Methods
    // ========================================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Agent agent = (Agent) o;
        return Objects.equals(id, agent.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format("Agent[%s: %s, balance=%.2f]", id, name, currencyBalance);
    }

    /**
     * Create a detailed status string for debugging.
     */
    public String toDetailedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Agent[").append(id).append(": ").append(name).append("]\n");
        sb.append("  Currency: ").append(currencyBalance.setScale(2, RoundingMode.HALF_UP)).append("\n");
        sb.append("  Preferences: ").append(preferences).append("\n");
        sb.append("  Requests: min=").append(minimumRequests).append(", ideal=").append(idealRequests).append("\n");
        sb.append("  Allocations: ").append(currentAllocations).append("\n");
        sb.append("  Utility: ").append(String.format("%.4f", getCurrentUtility()));
        return sb.toString();
    }
}
