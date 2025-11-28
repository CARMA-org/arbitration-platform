package org.carma.arbitration.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable collection of resource quantities.
 * Used to represent allocations, requests, and capacities.
 */
public final class ResourceBundle {
    private final Map<ResourceType, Long> quantities;

    public ResourceBundle(Map<ResourceType, Long> quantities) {
        this.quantities = Collections.unmodifiableMap(new HashMap<>(quantities));
    }

    public static ResourceBundle empty() {
        return new ResourceBundle(Map.of());
    }

    public static ResourceBundle of(ResourceType type, long amount) {
        return new ResourceBundle(Map.of(type, amount));
    }

    public static ResourceBundle of(ResourceType t1, long a1, ResourceType t2, long a2) {
        Map<ResourceType, Long> map = new HashMap<>();
        map.put(t1, a1);
        map.put(t2, a2);
        return new ResourceBundle(map);
    }

    public long get(ResourceType type) {
        return quantities.getOrDefault(type, 0L);
    }

    public Map<ResourceType, Long> getQuantities() {
        return quantities;
    }

    public ResourceBundle add(ResourceType type, long amount) {
        Map<ResourceType, Long> newQuantities = new HashMap<>(quantities);
        newQuantities.merge(type, amount, Long::sum);
        return new ResourceBundle(newQuantities);
    }

    public ResourceBundle subtract(ResourceType type, long amount) {
        return add(type, -amount);
    }

    public ResourceBundle merge(ResourceBundle other) {
        Map<ResourceType, Long> newQuantities = new HashMap<>(quantities);
        for (var entry : other.quantities.entrySet()) {
            newQuantities.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
        return new ResourceBundle(newQuantities);
    }

    public long total() {
        return quantities.values().stream().mapToLong(Long::longValue).sum();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ResourceBundle that = (ResourceBundle) o;
        return Objects.equals(quantities, that.quantities);
    }

    @Override
    public int hashCode() {
        return Objects.hash(quantities);
    }

    @Override
    public String toString() {
        return quantities.toString();
    }
}
