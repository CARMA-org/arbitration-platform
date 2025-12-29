package org.carma.arbitration.model;

import java.util.*;

/**
 * Nonlinear Preference Functions for Multi-Agent Resource Allocation.
 * 
 * This module extends the linear preference model to support:
 * - Square Root (CES ρ=0.5): Diminishing returns
 * - Logarithmic: Strong diminishing returns
 * - Cobb-Douglas: Complementarities (need all resources)
 * - Leontief (min): Perfect complements
 * - CES (general): Constant elasticity of substitution
 * - Piecewise Linear: Approximation for non-convex cases
 * 
 * Mathematical Foundation:
 * 
 * Linear:       Φ(A) = Σⱼ wⱼ · aⱼ
 * Square Root:  Φ(A) = (Σⱼ wⱼ · √aⱼ)²
 * Logarithmic:  Φ(A) = Σⱼ wⱼ · log(1 + aⱼ)
 * Cobb-Douglas: Φ(A) = Πⱼ aⱼ^wⱼ
 * Leontief:     Φ(A) = minⱼ(aⱼ / wⱼ)
 * CES:          Φ(A) = (Σⱼ wⱼ · aⱼ^ρ)^(1/ρ)
 * 
 * All implemented utility functions are concave (or quasi-concave for Leontief),
 * which preserves convexity of the optimization problem.
 * 
 * @author CARMA Arbitration Platform
 */
public abstract class UtilityFunction {

    /**
     * Type of utility function.
     */
    public enum Type {
        LINEAR("Linear", "Φ = Σ wⱼ·aⱼ", true),
        SQRT("Square Root", "Φ = (Σ wⱼ·√aⱼ)²", true),
        LOG("Logarithmic", "Φ = Σ wⱼ·log(1+aⱼ)", true),
        COBB_DOUGLAS("Cobb-Douglas", "Φ = Π aⱼ^wⱼ", true),
        LEONTIEF("Leontief", "Φ = min(aⱼ/wⱼ)", false),  // quasi-concave
        CES("CES", "Φ = (Σ wⱼ·aⱼ^ρ)^(1/ρ)", true),
        PIECEWISE_LINEAR("Piecewise Linear", "Φ = approx", true);

        private final String displayName;
        private final String formula;
        private final boolean strictlyConcave;

        Type(String displayName, String formula, boolean strictlyConcave) {
            this.displayName = displayName;
            this.formula = formula;
            this.strictlyConcave = strictlyConcave;
        }

        public String getDisplayName() { return displayName; }
        public String getFormula() { return formula; }
        public boolean isStrictlyConcave() { return strictlyConcave; }
    }

    protected final Map<ResourceType, Double> weights;
    protected final Type type;

    protected UtilityFunction(Map<ResourceType, Double> weights, Type type) {
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
        this.type = type;
    }

    /**
     * Evaluate utility from a resource allocation.
     */
    public abstract double evaluate(Map<ResourceType, Long> allocations);

    /**
     * Compute the gradient of utility with respect to each resource.
     * Used for gradient-based optimization.
     * 
     * @return Map of resource type to partial derivative ∂Φ/∂aⱼ
     */
    public abstract Map<ResourceType, Double> gradient(Map<ResourceType, Long> allocations);

    /**
     * Check if the utility function is convex-compatible for optimization.
     * (i.e., the log of utility is concave)
     */
    public boolean isConvexCompatible() {
        return type.isStrictlyConcave();
    }

    /**
     * Get the weight for a specific resource.
     */
    public double getWeight(ResourceType type) {
        return weights.getOrDefault(type, 0.0);
    }

    /**
     * Get all weights.
     */
    public Map<ResourceType, Double> getWeights() {
        return weights;
    }

    /**
     * Get the utility function type.
     */
    public Type getType() {
        return type;
    }

    /**
     * Get a linearized approximation at a given point.
     * Useful for piecewise linear approximation in solvers.
     */
    public LinearUtility linearize(Map<ResourceType, Long> point) {
        Map<ResourceType, Double> grad = gradient(point);
        double constant = evaluate(point);
        
        // Linearization: Φ ≈ Φ(x₀) + ∇Φ(x₀)·(x - x₀)
        //              = Φ(x₀) - ∇Φ(x₀)·x₀ + ∇Φ(x₀)·x
        double offset = constant;
        for (var entry : grad.entrySet()) {
            offset -= entry.getValue() * point.getOrDefault(entry.getKey(), 0L);
        }
        
        return new LinearUtility(grad, offset);
    }

    /**
     * Serialize to a format suitable for the Python solver.
     */
    public Map<String, Object> toSolverFormat() {
        Map<String, Object> result = new HashMap<>();
        result.put("type", type.name());
        result.put("weights", serializeWeights());
        addExtraParams(result);
        return result;
    }

    protected Map<String, Double> serializeWeights() {
        Map<String, Double> w = new HashMap<>();
        for (var entry : weights.entrySet()) {
            w.put(entry.getKey().name(), entry.getValue());
        }
        return w;
    }

    protected void addExtraParams(Map<String, Object> params) {
        // Override in subclasses to add extra parameters
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a linear utility function.
     */
    public static LinearUtility linear(Map<ResourceType, Double> weights) {
        return new LinearUtility(weights);
    }

    /**
     * Create a square root utility function.
     */
    public static SqrtUtility sqrt(Map<ResourceType, Double> weights) {
        return new SqrtUtility(weights);
    }

    /**
     * Create a logarithmic utility function.
     */
    public static LogUtility log(Map<ResourceType, Double> weights) {
        return new LogUtility(weights);
    }

    /**
     * Create a Cobb-Douglas utility function.
     */
    public static CobbDouglasUtility cobbDouglas(Map<ResourceType, Double> weights) {
        return new CobbDouglasUtility(weights);
    }

    /**
     * Create a Leontief (min) utility function.
     */
    public static LeontiefUtility leontief(Map<ResourceType, Double> weights) {
        return new LeontiefUtility(weights);
    }

    /**
     * Create a CES utility function with given elasticity parameter.
     * 
     * @param weights Resource weights
     * @param rho Elasticity parameter. Special cases:
     *            rho → 1: Linear
     *            rho = 0.5: Square root
     *            rho → 0: Cobb-Douglas
     *            rho → -∞: Leontief
     */
    public static CESUtility ces(Map<ResourceType, Double> weights, double rho) {
        return new CESUtility(weights, rho);
    }

    /**
     * Create from PreferenceFunction (backward compatibility).
     */
    public static LinearUtility fromPreferences(PreferenceFunction prefs) {
        return new LinearUtility(prefs.getWeights());
    }

    // ========================================================================
    // Linear Utility: Φ = Σ wⱼ·aⱼ
    // ========================================================================

    public static class LinearUtility extends UtilityFunction {
        private final double offset;

        public LinearUtility(Map<ResourceType, Double> weights) {
            this(weights, 0.0);
        }

        public LinearUtility(Map<ResourceType, Double> weights, double offset) {
            super(weights, Type.LINEAR);
            this.offset = offset;
        }

        @Override
        public double evaluate(Map<ResourceType, Long> allocations) {
            double utility = offset;
            for (var entry : weights.entrySet()) {
                utility += entry.getValue() * allocations.getOrDefault(entry.getKey(), 0L);
            }
            return utility;
        }

        @Override
        public Map<ResourceType, Double> gradient(Map<ResourceType, Long> allocations) {
            // Gradient is constant for linear function
            return new HashMap<>(weights);
        }

        /**
         * Convert to PreferenceFunction for backward compatibility.
         */
        public PreferenceFunction toPreferenceFunction() {
            return new PreferenceFunction(weights);
        }

        @Override
        public String toString() {
            return String.format("LinearUtility%s", weights);
        }
    }

    // ========================================================================
    // Square Root Utility: Φ = (Σ wⱼ·√aⱼ)²
    // ========================================================================

    public static class SqrtUtility extends UtilityFunction {
        private static final double EPSILON = 1e-8;

        public SqrtUtility(Map<ResourceType, Double> weights) {
            super(weights, Type.SQRT);
        }

        @Override
        public double evaluate(Map<ResourceType, Long> allocations) {
            double sum = 0;
            for (var entry : weights.entrySet()) {
                long a = allocations.getOrDefault(entry.getKey(), 0L);
                sum += entry.getValue() * Math.sqrt(Math.max(a, EPSILON));
            }
            return sum * sum;
        }

        @Override
        public Map<ResourceType, Double> gradient(Map<ResourceType, Long> allocations) {
            // ∂Φ/∂aⱼ = 2·(Σ wₖ·√aₖ)·(wⱼ / (2·√aⱼ)) = (Σ wₖ·√aₖ)·wⱼ/√aⱼ
            double sqrtSum = 0;
            for (var entry : weights.entrySet()) {
                long a = allocations.getOrDefault(entry.getKey(), 0L);
                sqrtSum += entry.getValue() * Math.sqrt(Math.max(a, EPSILON));
            }

            Map<ResourceType, Double> grad = new HashMap<>();
            for (var entry : weights.entrySet()) {
                long a = allocations.getOrDefault(entry.getKey(), 0L);
                double denom = Math.sqrt(Math.max(a, EPSILON));
                grad.put(entry.getKey(), sqrtSum * entry.getValue() / denom);
            }
            return grad;
        }

        @Override
        public String toString() {
            return String.format("SqrtUtility%s", weights);
        }
    }

    // ========================================================================
    // Logarithmic Utility: Φ = Σ wⱼ·log(1 + aⱼ)
    // ========================================================================

    public static class LogUtility extends UtilityFunction {
        private final double base;

        public LogUtility(Map<ResourceType, Double> weights) {
            this(weights, Math.E);  // Natural log by default
        }

        public LogUtility(Map<ResourceType, Double> weights, double base) {
            super(weights, Type.LOG);
            this.base = base;
        }

        @Override
        public double evaluate(Map<ResourceType, Long> allocations) {
            double utility = 0;
            double logBase = Math.log(base);
            for (var entry : weights.entrySet()) {
                long a = allocations.getOrDefault(entry.getKey(), 0L);
                utility += entry.getValue() * Math.log(1 + a) / logBase;
            }
            return utility;
        }

        @Override
        public Map<ResourceType, Double> gradient(Map<ResourceType, Long> allocations) {
            // ∂Φ/∂aⱼ = wⱼ / ((1 + aⱼ)·ln(base))
            Map<ResourceType, Double> grad = new HashMap<>();
            double logBase = Math.log(base);
            for (var entry : weights.entrySet()) {
                long a = allocations.getOrDefault(entry.getKey(), 0L);
                grad.put(entry.getKey(), entry.getValue() / ((1 + a) * logBase));
            }
            return grad;
        }

        @Override
        protected void addExtraParams(Map<String, Object> params) {
            params.put("base", base);
        }

        @Override
        public String toString() {
            return String.format("LogUtility%s", weights);
        }
    }

    // ========================================================================
    // Cobb-Douglas Utility: Φ = Π aⱼ^wⱼ
    // ========================================================================

    public static class CobbDouglasUtility extends UtilityFunction {
        private static final double EPSILON = 1e-8;

        public CobbDouglasUtility(Map<ResourceType, Double> weights) {
            super(weights, Type.COBB_DOUGLAS);
        }

        @Override
        public double evaluate(Map<ResourceType, Long> allocations) {
            double utility = 1.0;
            for (var entry : weights.entrySet()) {
                long a = allocations.getOrDefault(entry.getKey(), 0L);
                if (a <= 0 && entry.getValue() > 0) {
                    return 0;  // Zero allocation with positive weight → zero utility
                }
                utility *= Math.pow(Math.max(a, EPSILON), entry.getValue());
            }
            return utility;
        }

        @Override
        public Map<ResourceType, Double> gradient(Map<ResourceType, Long> allocations) {
            // ∂Φ/∂aⱼ = wⱼ · Φ / aⱼ
            double phi = evaluate(allocations);
            Map<ResourceType, Double> grad = new HashMap<>();
            for (var entry : weights.entrySet()) {
                long a = allocations.getOrDefault(entry.getKey(), 0L);
                double denom = Math.max(a, EPSILON);
                grad.put(entry.getKey(), entry.getValue() * phi / denom);
            }
            return grad;
        }

        @Override
        public String toString() {
            return String.format("CobbDouglasUtility%s", weights);
        }
    }

    // ========================================================================
    // Leontief (Min) Utility: Φ = min(aⱼ / wⱼ)
    // ========================================================================

    public static class LeontiefUtility extends UtilityFunction {
        private static final double EPSILON = 1e-8;

        public LeontiefUtility(Map<ResourceType, Double> weights) {
            super(weights, Type.LEONTIEF);
        }

        @Override
        public double evaluate(Map<ResourceType, Long> allocations) {
            double minRatio = Double.MAX_VALUE;
            for (var entry : weights.entrySet()) {
                if (entry.getValue() <= EPSILON) continue;
                long a = allocations.getOrDefault(entry.getKey(), 0L);
                double ratio = a / entry.getValue();
                minRatio = Math.min(minRatio, ratio);
            }
            return minRatio == Double.MAX_VALUE ? 0 : minRatio;
        }

        @Override
        public Map<ResourceType, Double> gradient(Map<ResourceType, Long> allocations) {
            // Gradient is 1/wⱼ for the binding resource, 0 otherwise
            // If multiple resources are binding, return subgradient
            double minRatio = Double.MAX_VALUE;
            ResourceType binding = null;
            
            for (var entry : weights.entrySet()) {
                if (entry.getValue() <= EPSILON) continue;
                long a = allocations.getOrDefault(entry.getKey(), 0L);
                double ratio = a / entry.getValue();
                if (ratio < minRatio) {
                    minRatio = ratio;
                    binding = entry.getKey();
                }
            }

            Map<ResourceType, Double> grad = new HashMap<>();
            for (ResourceType t : weights.keySet()) {
                grad.put(t, 0.0);
            }
            if (binding != null) {
                grad.put(binding, 1.0 / weights.get(binding));
            }
            return grad;
        }

        /**
         * Get the binding resource (the one determining the min).
         */
        public ResourceType getBindingResource(Map<ResourceType, Long> allocations) {
            double minRatio = Double.MAX_VALUE;
            ResourceType binding = null;
            
            for (var entry : weights.entrySet()) {
                if (entry.getValue() <= EPSILON) continue;
                long a = allocations.getOrDefault(entry.getKey(), 0L);
                double ratio = a / entry.getValue();
                if (ratio < minRatio) {
                    minRatio = ratio;
                    binding = entry.getKey();
                }
            }
            return binding;
        }

        @Override
        public boolean isConvexCompatible() {
            return false;  // Quasi-concave, not strictly concave
        }

        @Override
        public String toString() {
            return String.format("LeontiefUtility%s", weights);
        }
    }

    // ========================================================================
    // CES (Constant Elasticity of Substitution): Φ = (Σ wⱼ·aⱼ^ρ)^(1/ρ)
    // ========================================================================

    public static class CESUtility extends UtilityFunction {
        private static final double EPSILON = 1e-8;
        private final double rho;
        private final double elasticity;

        /**
         * Create CES utility with given elasticity parameter.
         * 
         * @param weights Resource weights
         * @param rho Elasticity parameter (typically between -10 and 1)
         *            rho → 1: Perfect substitutes (linear)
         *            rho = 0.5: Square root
         *            rho → 0: Cobb-Douglas
         *            rho → -∞: Perfect complements (Leontief)
         */
        public CESUtility(Map<ResourceType, Double> weights, double rho) {
            super(weights, Type.CES);
            this.rho = rho;
            this.elasticity = 1.0 / (1.0 - rho);  // σ = 1/(1-ρ)
        }

        @Override
        public double evaluate(Map<ResourceType, Long> allocations) {
            if (Math.abs(rho) < EPSILON) {
                // Limit case: Cobb-Douglas
                return evaluateCobbDouglas(allocations);
            }
            if (rho < -100) {
                // Limit case: Leontief
                return evaluateLeontief(allocations);
            }

            double sum = 0;
            for (var entry : weights.entrySet()) {
                long a = allocations.getOrDefault(entry.getKey(), 0L);
                if (a <= 0 && rho < 0) {
                    return 0;  // Zero allocation with ρ < 0 → zero utility
                }
                sum += entry.getValue() * Math.pow(Math.max(a, EPSILON), rho);
            }
            return Math.pow(sum, 1.0 / rho);
        }

        private double evaluateCobbDouglas(Map<ResourceType, Long> allocations) {
            double utility = 1.0;
            for (var entry : weights.entrySet()) {
                long a = allocations.getOrDefault(entry.getKey(), 0L);
                if (a <= 0) return 0;
                utility *= Math.pow(a, entry.getValue());
            }
            return utility;
        }

        private double evaluateLeontief(Map<ResourceType, Long> allocations) {
            double minRatio = Double.MAX_VALUE;
            for (var entry : weights.entrySet()) {
                if (entry.getValue() <= EPSILON) continue;
                long a = allocations.getOrDefault(entry.getKey(), 0L);
                minRatio = Math.min(minRatio, a / entry.getValue());
            }
            return minRatio == Double.MAX_VALUE ? 0 : minRatio;
        }

        @Override
        public Map<ResourceType, Double> gradient(Map<ResourceType, Long> allocations) {
            if (Math.abs(rho) < EPSILON) {
                return gradientCobbDouglas(allocations);
            }

            // ∂Φ/∂aⱼ = (1/ρ)·(Σ wₖ·aₖ^ρ)^(1/ρ - 1) · wⱼ·ρ·aⱼ^(ρ-1)
            //        = wⱼ · (Σ wₖ·aₖ^ρ)^(1/ρ - 1) · aⱼ^(ρ-1)
            double sum = 0;
            for (var entry : weights.entrySet()) {
                long a = allocations.getOrDefault(entry.getKey(), 0L);
                sum += entry.getValue() * Math.pow(Math.max(a, EPSILON), rho);
            }
            double factor = Math.pow(sum, (1.0 / rho) - 1);

            Map<ResourceType, Double> grad = new HashMap<>();
            for (var entry : weights.entrySet()) {
                long a = allocations.getOrDefault(entry.getKey(), 0L);
                double deriv = entry.getValue() * factor * Math.pow(Math.max(a, EPSILON), rho - 1);
                grad.put(entry.getKey(), deriv);
            }
            return grad;
        }

        private Map<ResourceType, Double> gradientCobbDouglas(Map<ResourceType, Long> allocations) {
            double phi = evaluateCobbDouglas(allocations);
            Map<ResourceType, Double> grad = new HashMap<>();
            for (var entry : weights.entrySet()) {
                long a = allocations.getOrDefault(entry.getKey(), 0L);
                grad.put(entry.getKey(), entry.getValue() * phi / Math.max(a, EPSILON));
            }
            return grad;
        }

        public double getRho() {
            return rho;
        }

        public double getElasticity() {
            return elasticity;
        }

        @Override
        protected void addExtraParams(Map<String, Object> params) {
            params.put("rho", rho);
            params.put("elasticity", elasticity);
        }

        @Override
        public String toString() {
            return String.format("CESUtility[ρ=%.2f, σ=%.2f]%s", rho, elasticity, weights);
        }
    }

    // ========================================================================
    // Piecewise Linear Approximation
    // ========================================================================

    /**
     * Piecewise linear approximation for non-convex utility functions.
     * Approximates any utility function using linear segments.
     */
    public static class PiecewiseLinearUtility extends UtilityFunction {
        private final UtilityFunction original;
        private final List<LinearUtility> segments;
        private final List<Map<ResourceType, Long>> breakpoints;

        public PiecewiseLinearUtility(UtilityFunction original, int numSegments) {
            super(original.weights, Type.PIECEWISE_LINEAR);
            this.original = original;
            this.segments = new ArrayList<>();
            this.breakpoints = new ArrayList<>();
            computeSegments(numSegments);
        }

        private void computeSegments(int numSegments) {
            // Generate breakpoints along the diagonal
            for (int i = 0; i <= numSegments; i++) {
                double fraction = (double) i / numSegments;
                Map<ResourceType, Long> point = new HashMap<>();
                for (ResourceType t : weights.keySet()) {
                    point.put(t, (long) (100 * fraction));  // Assume max of 100 for each
                }
                breakpoints.add(point);
            }

            // Compute linear approximation at each breakpoint
            for (var point : breakpoints) {
                segments.add(original.linearize(point));
            }
        }

        @Override
        public double evaluate(Map<ResourceType, Long> allocations) {
            // Return the minimum of all linear approximations (for concave envelope)
            double maxUtil = Double.NEGATIVE_INFINITY;
            for (var segment : segments) {
                maxUtil = Math.max(maxUtil, segment.evaluate(allocations));
            }
            return maxUtil;
        }

        @Override
        public Map<ResourceType, Double> gradient(Map<ResourceType, Long> allocations) {
            // Return gradient of the active segment
            int bestIdx = 0;
            double bestUtil = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < segments.size(); i++) {
                double util = segments.get(i).evaluate(allocations);
                if (util > bestUtil) {
                    bestUtil = util;
                    bestIdx = i;
                }
            }
            return segments.get(bestIdx).gradient(allocations);
        }

        public UtilityFunction getOriginal() {
            return original;
        }

        public List<LinearUtility> getSegments() {
            return Collections.unmodifiableList(segments);
        }

        @Override
        public String toString() {
            return String.format("PiecewiseLinear[%s, %d segments]", 
                original.getType(), segments.size());
        }
    }

    // ========================================================================
    // Builder for convenient construction
    // ========================================================================

    public static class Builder {
        private final Map<ResourceType, Double> weights = new HashMap<>();
        private Type type = Type.LINEAR;
        private double rho = 0.5;  // For CES

        public Builder weight(ResourceType resource, double weight) {
            weights.put(resource, weight);
            return this;
        }

        public Builder weights(Map<ResourceType, Double> w) {
            weights.putAll(w);
            return this;
        }

        public Builder linear() {
            this.type = Type.LINEAR;
            return this;
        }

        public Builder sqrt() {
            this.type = Type.SQRT;
            return this;
        }

        public Builder log() {
            this.type = Type.LOG;
            return this;
        }

        public Builder cobbDouglas() {
            this.type = Type.COBB_DOUGLAS;
            return this;
        }

        public Builder leontief() {
            this.type = Type.LEONTIEF;
            return this;
        }

        public Builder ces(double rho) {
            this.type = Type.CES;
            this.rho = rho;
            return this;
        }

        public UtilityFunction build() {
            if (weights.isEmpty()) {
                throw new IllegalStateException("Must specify at least one weight");
            }
            return switch (type) {
                case LINEAR -> new LinearUtility(weights);
                case SQRT -> new SqrtUtility(weights);
                case LOG -> new LogUtility(weights);
                case COBB_DOUGLAS -> new CobbDouglasUtility(weights);
                case LEONTIEF -> new LeontiefUtility(weights);
                case CES -> new CESUtility(weights, rho);
                default -> new LinearUtility(weights);
            };
        }
    }

    // ========================================================================
    // Utility Analysis Methods
    // ========================================================================

    /**
     * Calculate marginal rate of substitution between two resources.
     * MRS = (∂Φ/∂a₁) / (∂Φ/∂a₂)
     */
    public double marginalRateOfSubstitution(
            ResourceType r1, ResourceType r2, Map<ResourceType, Long> allocations) {
        Map<ResourceType, Double> grad = gradient(allocations);
        double grad1 = grad.getOrDefault(r1, 0.0);
        double grad2 = grad.getOrDefault(r2, 0.0);
        if (Math.abs(grad2) < 1e-10) {
            return Double.POSITIVE_INFINITY;
        }
        return grad1 / grad2;
    }

    /**
     * Calculate elasticity of substitution between two resources.
     * For CES utility, this is constant = 1/(1-ρ).
     */
    public double elasticityOfSubstitution(
            ResourceType r1, ResourceType r2, Map<ResourceType, Long> allocations) {
        if (type == Type.CES) {
            return ((CESUtility) this).getElasticity();
        }
        if (type == Type.LINEAR) {
            return Double.POSITIVE_INFINITY;  // Perfect substitutes
        }
        if (type == Type.LEONTIEF) {
            return 0;  // Perfect complements
        }
        if (type == Type.COBB_DOUGLAS) {
            return 1;  // Unit elasticity
        }
        // Numerical approximation for other cases
        return approximateElasticity(r1, r2, allocations);
    }

    private double approximateElasticity(
            ResourceType r1, ResourceType r2, Map<ResourceType, Long> allocations) {
        // Numerical differentiation
        double mrs = marginalRateOfSubstitution(r1, r2, allocations);
        
        Map<ResourceType, Long> shifted = new HashMap<>(allocations);
        long a1 = allocations.getOrDefault(r1, 0L);
        long a2 = allocations.getOrDefault(r2, 0L);
        
        // Small perturbation
        shifted.put(r1, a1 + 1);
        double mrsShifted = marginalRateOfSubstitution(r1, r2, shifted);
        
        double ratio = (double) a2 / Math.max(a1, 1);
        double dMRS = mrsShifted - mrs;
        
        if (Math.abs(dMRS) < 1e-10) {
            return Double.POSITIVE_INFINITY;
        }
        
        // σ ≈ d(ln(a2/a1)) / d(ln(MRS))
        return -ratio * mrs / (Math.max(a1, 1) * dMRS);
    }

    /**
     * Check if this utility function exhibits diminishing returns.
     */
    public boolean hasDiminishingReturns() {
        return type != Type.LINEAR;
    }

    /**
     * Check if this utility function requires all resources (complementarity).
     */
    public boolean hasComplementarity() {
        return type == Type.COBB_DOUGLAS || type == Type.LEONTIEF || 
               (type == Type.CES && ((CESUtility) this).getRho() < 0);
    }
}
