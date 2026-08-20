package org.carma.arbitration.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class UtilityDeclaration {

    public enum Family { LINEAR, COBB_DOUGLAS, CES, LEONTIEF }

    private final Family family;
    private final Map<ResourceType, Double> weights;
    private final Map<ResourceType, Double> requirements;
    private final Double rho;

    private UtilityDeclaration(Family family, Map<ResourceType, Double> weights,
                               Map<ResourceType, Double> requirements, Double rho) {
        this.family = Objects.requireNonNull(family, "family");
        this.weights = weights == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(weights));
        this.requirements = requirements == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(requirements));
        this.rho = rho;
    }

    public static UtilityDeclaration linear() {
        return new UtilityDeclaration(Family.LINEAR, null, null, null);
    }

    public static UtilityDeclaration linear(Map<ResourceType, Double> weights) {
        return new UtilityDeclaration(Family.LINEAR, weights, null, null);
    }

    public static UtilityDeclaration cobbDouglas(Map<ResourceType, Double> weights) {
        return new UtilityDeclaration(Family.COBB_DOUGLAS, weights, null, null);
    }

    public static UtilityDeclaration ces(Map<ResourceType, Double> weights, double rho) {
        return new UtilityDeclaration(Family.CES, weights, null, rho);
    }

    public static UtilityDeclaration leontief(Map<ResourceType, Double> requirements) {
        boolean anyPositive = requirements != null
            && requirements.values().stream().anyMatch(v -> v != null && v > 0.0);
        if (!anyPositive) {
            throw new IllegalArgumentException(
                "LEONTIEF requires at least one strictly positive requirement");
        }
        return new UtilityDeclaration(Family.LEONTIEF, null, requirements, null);
    }

    public Family getFamily() { return family; }

    public Map<ResourceType, Double> getWeights() { return weights; }

    public Map<ResourceType, Double> getRequirements() { return requirements; }

    public Double getRho() { return rho; }

    public double getWeight(ResourceType type) {
        return weights.getOrDefault(type, 0.0);
    }

    public double getRequirement(ResourceType type) {
        return requirements.getOrDefault(type, 0.0);
    }

    @Override
    public String toString() {
        switch (family) {
            case CES:
                return "CES(rho=" + rho + ", weights=" + weights + ")";
            case LEONTIEF:
                return "LEONTIEF(requirements=" + requirements + ")";
            case COBB_DOUGLAS:
                return "COBB_DOUGLAS(weights=" + weights + ")";
            default:
                return "LINEAR(weights=" + weights + ")";
        }
    }
}
