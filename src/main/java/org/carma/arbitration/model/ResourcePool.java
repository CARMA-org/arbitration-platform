package org.carma.arbitration.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages a pool of resources with capacity tracking and allocation/release operations.
 * 
 * The resource pool tracks:
 * - Total capacity per resource type
 * - Currently available quantity
 * - Utilization metrics
 */
public class ResourcePool {
    
    private final Map<ResourceType, Long> totalCapacity;
    private final Map<ResourceType, Long> available;

    public ResourcePool(Map<ResourceType, Long> capacity) {
        this.totalCapacity = new HashMap<>(capacity);
        this.available = new HashMap<>(capacity);
    }

    /**
     * Create a resource pool with single resource type.
     */
    public static ResourcePool ofSingle(ResourceType type, long capacity) {
        return new ResourcePool(Map.of(type, capacity));
    }

    /**
     * Create a resource pool with two resource types.
     */
    public static ResourcePool of(ResourceType t1, long c1, ResourceType t2, long c2) {
        Map<ResourceType, Long> cap = new HashMap<>();
        cap.put(t1, c1);
        cap.put(t2, c2);
        return new ResourcePool(cap);
    }

    // ========================================================================
    // Capacity Queries
    // ========================================================================

    public long getCapacity(ResourceType type) {
        return totalCapacity.getOrDefault(type, 0L);
    }

    public long getAvailable(ResourceType type) {
        return available.getOrDefault(type, 0L);
    }

    public long getAllocated(ResourceType type) {
        return getCapacity(type) - getAvailable(type);
    }

    public Map<ResourceType, Long> getTotalCapacity() {
        return new HashMap<>(totalCapacity);
    }

    public Map<ResourceType, Long> getAvailableMap() {
        return new HashMap<>(available);
    }

    // ========================================================================
    // Utilization Metrics
    // ========================================================================

    /**
     * Get utilization ratio for a resource type (0.0 to 1.0).
     */
    public double getUtilization(ResourceType type) {
        long cap = getCapacity(type);
        if (cap == 0) return 0.0;
        return 1.0 - (double) getAvailable(type) / cap;
    }

    /**
     * Get overall utilization across all resource types.
     */
    public double getOverallUtilization() {
        long totalCap = totalCapacity.values().stream().mapToLong(Long::longValue).sum();
        long totalAvail = available.values().stream().mapToLong(Long::longValue).sum();
        if (totalCap == 0) return 0.0;
        return 1.0 - (double) totalAvail / totalCap;
    }

    // ========================================================================
    // Allocation Operations
    // ========================================================================

    /**
     * Check if sufficient resources are available.
     */
    public boolean hasSufficient(ResourceType type, long amount) {
        return getAvailable(type) >= amount;
    }

    /**
     * Allocate resources from the pool.
     * @throws IllegalStateException if insufficient resources
     */
    public void allocate(ResourceType type, long amount) {
        long current = getAvailable(type);
        if (amount > current) {
            throw new IllegalStateException(
                "Insufficient " + type + ": requested=" + amount + ", available=" + current);
        }
        available.put(type, current - amount);
    }

    /**
     * Release resources back to the pool.
     */
    public void release(ResourceType type, long amount) {
        long current = getAvailable(type);
        long newAvailable = current + amount;
        // Cap at total capacity
        long cap = getCapacity(type);
        if (newAvailable > cap) {
            newAvailable = cap;
        }
        available.put(type, newAvailable);
    }

    /**
     * Reset pool to full capacity.
     */
    public void reset() {
        available.clear();
        available.putAll(totalCapacity);
    }

    /**
     * Set capacity for a resource type.
     */
    public void setCapacity(ResourceType type, long capacity) {
        totalCapacity.put(type, capacity);
        // Adjust available if it exceeds new capacity
        if (available.getOrDefault(type, 0L) > capacity) {
            available.put(type, capacity);
        }
    }

    // ========================================================================
    // Object Methods
    // ========================================================================

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ResourcePool[\n");
        for (ResourceType type : totalCapacity.keySet()) {
            long cap = getCapacity(type);
            long avail = getAvailable(type);
            sb.append(String.format("  %s: %d/%d (%.1f%% utilized)\n", 
                type.name(), avail, cap, getUtilization(type) * 100));
        }
        sb.append("]");
        return sb.toString();
    }
}
