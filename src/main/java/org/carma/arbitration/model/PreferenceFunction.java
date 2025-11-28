package org.carma.arbitration.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Linear weighted preference function over resources.
 * 
 * For agent i with weights wᵢⱼ, utility from allocation A is:
 *   Φᵢ(A) = Σⱼ wᵢⱼ · aᵢⱼ
 * 
 * Weights are automatically normalized to sum to 1.0.
 */
public final class PreferenceFunction {
    private final Map<ResourceType, Double> weights;

    public PreferenceFunction(Map<ResourceType, Double> weights) {
        // Normalize weights to sum to 1.0
        double sum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum > 0 && Math.abs(sum - 1.0) > 0.001) {
            Map<ResourceType, Double> normalized = new HashMap<>();
            for (var entry : weights.entrySet()) {
                normalized.put(entry.getKey(), entry.getValue() / sum);
            }
            this.weights = Collections.unmodifiableMap(normalized);
        } else {
            this.weights = Collections.unmodifiableMap(new HashMap<>(weights));
        }
    }

    /**
     * Create uniform preference over the given resource types.
     */
    public static PreferenceFunction uniform(ResourceType... types) {
        Map<ResourceType, Double> w = new HashMap<>();
        double weight = 1.0 / types.length;
        for (ResourceType t : types) {
            w.put(t, weight);
        }
        return new PreferenceFunction(w);
    }

    /**
     * Create preference function from varargs pairs.
     */
    public static PreferenceFunction of(Object... args) {
        if (args.length % 2 != 0) {
            throw new IllegalArgumentException("Arguments must be (ResourceType, Double) pairs");
        }
        Map<ResourceType, Double> w = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            ResourceType type = (ResourceType) args[i];
            Double weight = ((Number) args[i + 1]).doubleValue();
            w.put(type, weight);
        }
        return new PreferenceFunction(w);
    }

    public double getWeight(ResourceType type) {
        return weights.getOrDefault(type, 0.0);
    }

    public Map<ResourceType, Double> getWeights() {
        return weights;
    }

    /**
     * Evaluate utility from a resource allocation.
     * Φᵢ(A) = Σⱼ wᵢⱼ · aᵢⱼ
     */
    public double evaluate(ResourceBundle allocation) {
        double utility = 0.0;
        for (var entry : weights.entrySet()) {
            utility += entry.getValue() * allocation.get(entry.getKey());
        }
        return utility;
    }

    /**
     * Evaluate utility from individual allocations map.
     */
    public double evaluate(Map<ResourceType, Long> allocations) {
        double utility = 0.0;
        for (var entry : weights.entrySet()) {
            utility += entry.getValue() * allocations.getOrDefault(entry.getKey(), 0L);
        }
        return utility;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PreferenceFunction that = (PreferenceFunction) o;
        return Objects.equals(weights, that.weights);
    }

    @Override
    public int hashCode() {
        return Objects.hash(weights);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Preferences[");
        weights.forEach((k, v) -> sb.append(k.name()).append("=").append(String.format("%.0f%%", v * 100)).append(", "));
        if (!weights.isEmpty()) {
            sb.setLength(sb.length() - 2);
        }
        sb.append("]");
        return sb.toString();
    }
}
