package org.carma.arbitration.model;

import java.util.*;

/**
 * Abstract base class for utility functions in the arbitration platform.
 * 
 * Supports multiple utility function types including:
 * - LINEAR: Perfect substitutes (Φ = Σ wⱼ·aⱼ)
 * - SQRT: Square root diminishing returns (Φ = (Σ wⱼ·√aⱼ)²)
 * - LOG: Logarithmic diminishing returns (Φ = Σ wⱼ·log(1+aⱼ))
 * - COBB_DOUGLAS: Complementarity (Φ = Π aⱼ^wⱼ)
 * - LEONTIEF: Perfect complements (Φ = min(aⱼ/wⱼ))
 * - CES: Constant elasticity of substitution (Φ = (Σ wⱼ·aⱼ^ρ)^(1/ρ))
 * - THRESHOLD: Soft threshold wrapper (σ(Σaⱼ - T) · Φ_base)
 * - SATIATION: Upper-bounded utility (V_max · (1 - e^(-Φ_base/k)))
 * - NESTED_CES: Hierarchical substitution patterns
 * - SOFTPLUS_LOSS_AVERSION: Reference-dependent with smooth transition
 * - ASYMMETRIC_LOG_LOSS_AVERSION: Reference-dependent with diminishing sensitivity
 * - PIECEWISE_LINEAR: Approximation for non-convex functions
 * 
 * All utility functions except Leontief are strictly concave, making them
 * suitable for convex optimization in the weighted proportional fairness framework.
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
        THRESHOLD("Threshold", "Φ = σ(Σaⱼ-T)·Φ_base", true),
        SATIATION("Satiation", "Φ = V_max·(1-e^(-Φ_base/k))", true),
        NESTED_CES("Nested CES", "Φ = f(nest₁, nest₂, ...)", true),
        SOFTPLUS_LOSS_AVERSION("Softplus Loss Aversion", "Φ = Σ wⱼ·g(aⱼ-rⱼ)", true),
        ASYMMETRIC_LOG_LOSS_AVERSION("Asymmetric Log Loss Aversion", "Φ = Σ wⱼ·h(aⱼ-rⱼ)", true),
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
     * Create a threshold utility function wrapper.
     * 
     * Φ_threshold(A) = σ(Σⱼ aⱼ - T) · Φ_base(A)
     * where σ(x) = 1/(1 + e^(-k·x)) is a soft threshold
     * 
     * @param base The base utility function to wrap
     * @param threshold T - the minimum viable quantity threshold
     * @param sharpness k - controls sharpness (higher = closer to hard threshold)
     */
    public static ThresholdUtility threshold(UtilityFunction base, double threshold, double sharpness) {
        return new ThresholdUtility(base, threshold, sharpness);
    }

    /**
     * Create a satiation utility function wrapper.
     * 
     * Φ_satiated(A) = V_max · (1 - e^(-Φ_base(A)/k))
     * 
     * @param base The base utility function to wrap
     * @param maxUtility V_max - maximum achievable utility
     * @param saturationRate k - controls how quickly satiation is approached
     */
    public static SatiationUtility satiation(UtilityFunction base, double maxUtility, double saturationRate) {
        return new SatiationUtility(base, maxUtility, saturationRate, false);
    }

    /**
     * Create a hyperbolic satiation utility function wrapper.
     * 
     * Φ_hyperbolic(A) = V_max · Φ_base(A) / (k + Φ_base(A))
     * 
     * @param base The base utility function to wrap
     * @param maxUtility V_max - maximum achievable utility
     * @param halfSaturation k - value at which utility reaches V_max/2
     */
    public static SatiationUtility hyperbolicSatiation(UtilityFunction base, double maxUtility, double halfSaturation) {
        return new SatiationUtility(base, maxUtility, halfSaturation, true);
    }

    /**
     * Create a nested CES utility function for partial substitutes.
     * 
     * @param nests List of resource groups (nests)
     * @param nestRhos Elasticity parameter for each nest
     * @param nestWeights Weights for combining nests
     * @param outerRho Elasticity parameter for combining nests
     */
    public static NestedCESUtility nestedCES(
            List<Map<ResourceType, Double>> nests,
            List<Double> nestRhos,
            List<Double> nestWeights,
            double outerRho) {
        return new NestedCESUtility(nests, nestRhos, nestWeights, outerRho);
    }

    /**
     * Create a softplus loss aversion utility function (Constraint Set 3).
     * 
     * g(x) = x - (λ - 1) · τ · ln(1 + exp(-x / τ))
     * Φ_i(A) = Σⱼ wⱼ · g(aⱼ - rⱼ)
     * 
     * @param weights Preference weights
     * @param referencePoints Reference point for each resource
     * @param lambda Loss aversion coefficient (λ > 1 means losses hurt more)
     * @param tau Temperature parameter (smaller = sharper transition)
     */
    public static SoftplusLossAversionUtility softplusLossAversion(
            Map<ResourceType, Double> weights,
            Map<ResourceType, Double> referencePoints,
            double lambda,
            double tau) {
        return new SoftplusLossAversionUtility(weights, referencePoints, lambda, tau);
    }

    /**
     * Create an asymmetric logarithmic loss aversion utility (Constraint Set 5).
     * 
     * g(x) = ln(1 + x/κ) if x ≥ 0
     * g(x) = -λ · ln(1 + |x|/κ) if x < 0
     * Φ_i(A) = Σⱼ wⱼ · g(aⱼ - rⱼ)
     * 
     * @param weights Preference weights
     * @param referencePoints Reference point for each resource
     * @param lambda Loss aversion coefficient (λ ≥ 1 required for concavity)
     * @param kappa Scaling parameter (larger = more linear behavior)
     */
    public static AsymmetricLogLossAversionUtility asymmetricLogLossAversion(
            Map<ResourceType, Double> weights,
            Map<ResourceType, Double> referencePoints,
            double lambda,
            double kappa) {
        return new AsymmetricLogLossAversionUtility(weights, referencePoints, lambda, kappa);
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
    // Threshold Utility: Φ_threshold(A) = σ(Σⱼ aⱼ - T) · Φ_base(A)
    // ========================================================================

    /**
     * Threshold utility wrapper that applies a soft sigmoid threshold.
     * 
     * Models minimum viable quantity - agents have a threshold below which
     * utility approaches zero regardless of normal preference weights.
     * 
     * Φ_threshold(A) = σ(Σⱼ aⱼ - T) · Φ_base(A)
     * where σ(x) = 1/(1 + e^(-k·x)) is a soft threshold
     * 
     * Properties:
     * - Smooth and differentiable everywhere
     * - Preserves concavity if base utility is concave
     * - k controls sharpness (higher = closer to hard threshold)
     */
    public static class ThresholdUtility extends UtilityFunction {
        private final UtilityFunction base;
        private final double threshold;
        private final double sharpness;

        public ThresholdUtility(UtilityFunction base, double threshold, double sharpness) {
            super(base.weights, Type.THRESHOLD);
            this.base = base;
            this.threshold = threshold;
            this.sharpness = sharpness;
        }

        /**
         * Sigmoid function: σ(x) = 1/(1 + e^(-x))
         */
        private double sigmoid(double x) {
            if (x > 20) return 1.0;
            if (x < -20) return 0.0;
            return 1.0 / (1.0 + Math.exp(-x));
        }

        /**
         * Derivative of sigmoid: σ'(x) = σ(x) · (1 - σ(x))
         */
        private double sigmoidDerivative(double x) {
            double s = sigmoid(x);
            return s * (1 - s);
        }

        private double totalAllocation(Map<ResourceType, Long> allocations) {
            return allocations.values().stream().mapToLong(Long::longValue).sum();
        }

        @Override
        public double evaluate(Map<ResourceType, Long> allocations) {
            double total = totalAllocation(allocations);
            double sigmoidArg = sharpness * (total - threshold);
            double sigmoidValue = sigmoid(sigmoidArg);
            return sigmoidValue * base.evaluate(allocations);
        }

        @Override
        public Map<ResourceType, Double> gradient(Map<ResourceType, Long> allocations) {
            double total = totalAllocation(allocations);
            double sigmoidArg = sharpness * (total - threshold);
            double sigmoidValue = sigmoid(sigmoidArg);
            double sigmoidDeriv = sigmoidDerivative(sigmoidArg);
            
            double baseValue = base.evaluate(allocations);
            Map<ResourceType, Double> baseGrad = base.gradient(allocations);
            
            // ∂Φ/∂aⱼ = σ'(k(total-T))·k·Φ_base + σ(k(total-T))·∂Φ_base/∂aⱼ
            Map<ResourceType, Double> grad = new HashMap<>();
            for (var entry : weights.entrySet()) {
                ResourceType r = entry.getKey();
                double dSigmoid = sigmoidDeriv * sharpness * baseValue;
                double dBase = sigmoidValue * baseGrad.getOrDefault(r, 0.0);
                grad.put(r, dSigmoid + dBase);
            }
            return grad;
        }

        public UtilityFunction getBase() {
            return base;
        }

        public double getThreshold() {
            return threshold;
        }

        public double getSharpness() {
            return sharpness;
        }

        @Override
        protected void addExtraParams(Map<String, Object> params) {
            params.put("threshold", threshold);
            params.put("sharpness", sharpness);
            params.put("base_utility", base.toSolverFormat());
        }

        @Override
        public String toString() {
            return String.format("ThresholdUtility[T=%.1f, k=%.1f, base=%s]", 
                threshold, sharpness, base.getType());
        }
    }

    // ========================================================================
    // Satiation Utility: Φ = V_max · (1 - e^(-Φ_base/k)) or hyperbolic
    // ========================================================================

    /**
     * Satiation utility wrapper that bounds utility with an upper limit.
     * 
     * Two variants:
     * - Exponential: Φ_satiated(A) = V_max · (1 - e^(-Φ_base(A)/k))
     * - Hyperbolic: Φ_hyperbolic(A) = V_max · Φ_base(A) / (k + Φ_base(A))
     * 
     * Properties:
     * - Models diminishing returns with asymptotic upper bound
     * - Preserves concavity if base utility is concave
     * - k controls how quickly satiation is approached
     */
    public static class SatiationUtility extends UtilityFunction {
        private static final double EPSILON = 1e-8;
        private final UtilityFunction base;
        private final double maxUtility;
        private final double saturationParam;
        private final boolean hyperbolic;

        public SatiationUtility(UtilityFunction base, double maxUtility, double saturationParam, boolean hyperbolic) {
            super(base.weights, Type.SATIATION);
            this.base = base;
            this.maxUtility = maxUtility;
            this.saturationParam = saturationParam;
            this.hyperbolic = hyperbolic;
        }

        @Override
        public double evaluate(Map<ResourceType, Long> allocations) {
            double baseValue = base.evaluate(allocations);
            if (hyperbolic) {
                // Φ_hyperbolic = V_max · Φ_base / (k + Φ_base)
                return maxUtility * baseValue / (saturationParam + baseValue + EPSILON);
            } else {
                // Φ_exponential = V_max · (1 - e^(-Φ_base/k))
                return maxUtility * (1 - Math.exp(-baseValue / saturationParam));
            }
        }

        @Override
        public Map<ResourceType, Double> gradient(Map<ResourceType, Long> allocations) {
            double baseValue = base.evaluate(allocations);
            Map<ResourceType, Double> baseGrad = base.gradient(allocations);
            
            double multiplier;
            if (hyperbolic) {
                // d/dx [V_max · x / (k + x)] = V_max · k / (k + x)²
                double denom = saturationParam + baseValue + EPSILON;
                multiplier = maxUtility * saturationParam / (denom * denom);
            } else {
                // d/dx [V_max · (1 - e^(-x/k))] = V_max · e^(-x/k) / k
                multiplier = maxUtility * Math.exp(-baseValue / saturationParam) / saturationParam;
            }
            
            Map<ResourceType, Double> grad = new HashMap<>();
            for (var entry : baseGrad.entrySet()) {
                grad.put(entry.getKey(), multiplier * entry.getValue());
            }
            return grad;
        }

        public UtilityFunction getBase() {
            return base;
        }

        public double getMaxUtility() {
            return maxUtility;
        }

        public double getSaturationParam() {
            return saturationParam;
        }

        public boolean isHyperbolic() {
            return hyperbolic;
        }

        @Override
        protected void addExtraParams(Map<String, Object> params) {
            params.put("max_utility", maxUtility);
            params.put("saturation_param", saturationParam);
            params.put("hyperbolic", hyperbolic);
            params.put("base_utility", base.toSolverFormat());
        }

        @Override
        public String toString() {
            String variant = hyperbolic ? "hyperbolic" : "exponential";
            return String.format("SatiationUtility[%s, V_max=%.1f, k=%.1f, base=%s]", 
                variant, maxUtility, saturationParam, base.getType());
        }
    }

    // ========================================================================
    // Nested CES Utility: Hierarchical substitution patterns
    // ========================================================================

    /**
     * Nested CES utility for partial substitutes with hierarchical structure.
     * 
     * Φ_nested = ((α₁·nest₁^ρ_outer + α₂·nest₂^ρ_outer)^(1/ρ_outer)
     * where:
     *   nest₁ = (w₁·a₁^ρ₁ + w₂·a₂^ρ₁)^(1/ρ₁)
     *   nest₂ = (w₃·a₃^ρ₂ + w₄·a₄^ρ₂)^(1/ρ₂)
     * 
     * This allows different substitution patterns between resource groups.
     * For example, compute and GPU might be close substitutes (high ρ),
     * while {compute, GPU} and {storage, memory} are complements (low ρ).
     */
    public static class NestedCESUtility extends UtilityFunction {
        private static final double EPSILON = 1e-8;
        private final List<Map<ResourceType, Double>> nests;
        private final List<Double> nestRhos;
        private final List<Double> nestWeights;
        private final double outerRho;

        public NestedCESUtility(
                List<Map<ResourceType, Double>> nests,
                List<Double> nestRhos,
                List<Double> nestWeights,
                double outerRho) {
            super(combineNestWeights(nests), Type.NESTED_CES);
            this.nests = new ArrayList<>(nests);
            this.nestRhos = new ArrayList<>(nestRhos);
            this.nestWeights = normalizeWeights(nestWeights);
            this.outerRho = outerRho;
        }

        private static Map<ResourceType, Double> combineNestWeights(List<Map<ResourceType, Double>> nests) {
            Map<ResourceType, Double> combined = new HashMap<>();
            for (Map<ResourceType, Double> nest : nests) {
                combined.putAll(nest);
            }
            return combined;
        }

        private static List<Double> normalizeWeights(List<Double> weights) {
            double sum = weights.stream().mapToDouble(Double::doubleValue).sum();
            if (sum > 0 && Math.abs(sum - 1.0) > 0.001) {
                return weights.stream().map(w -> w / sum).toList();
            }
            return new ArrayList<>(weights);
        }

        private double evaluateNest(int nestIdx, Map<ResourceType, Long> allocations) {
            Map<ResourceType, Double> nestWeights = nests.get(nestIdx);
            double rho = nestRhos.get(nestIdx);
            
            if (Math.abs(rho) < EPSILON) {
                // Cobb-Douglas limit
                double product = 1.0;
                for (var entry : nestWeights.entrySet()) {
                    long a = allocations.getOrDefault(entry.getKey(), 0L);
                    product *= Math.pow(Math.max(a, EPSILON), entry.getValue());
                }
                return product;
            }
            
            double sum = 0;
            for (var entry : nestWeights.entrySet()) {
                long a = allocations.getOrDefault(entry.getKey(), 0L);
                sum += entry.getValue() * Math.pow(Math.max(a, EPSILON), rho);
            }
            return Math.pow(Math.max(sum, EPSILON), 1.0 / rho);
        }

        @Override
        public double evaluate(Map<ResourceType, Long> allocations) {
            if (Math.abs(outerRho) < EPSILON) {
                // Outer Cobb-Douglas
                double product = 1.0;
                for (int i = 0; i < nests.size(); i++) {
                    double nestValue = evaluateNest(i, allocations);
                    product *= Math.pow(nestValue, nestWeights.get(i));
                }
                return product;
            }
            
            double sum = 0;
            for (int i = 0; i < nests.size(); i++) {
                double nestValue = evaluateNest(i, allocations);
                sum += nestWeights.get(i) * Math.pow(nestValue, outerRho);
            }
            return Math.pow(Math.max(sum, EPSILON), 1.0 / outerRho);
        }

        @Override
        public Map<ResourceType, Double> gradient(Map<ResourceType, Long> allocations) {
            Map<ResourceType, Double> grad = new HashMap<>();
            
            // Compute nest values and derivatives
            List<Double> nestValues = new ArrayList<>();
            for (int i = 0; i < nests.size(); i++) {
                nestValues.add(evaluateNest(i, allocations));
            }
            
            double outerValue = evaluate(allocations);
            
            for (int nestIdx = 0; nestIdx < nests.size(); nestIdx++) {
                Map<ResourceType, Double> nestW = nests.get(nestIdx);
                double nestRho = nestRhos.get(nestIdx);
                double nestValue = nestValues.get(nestIdx);
                double alpha = nestWeights.get(nestIdx);
                
                // d(outer)/d(nest_i)
                double dOuterDNest;
                if (Math.abs(outerRho) < EPSILON) {
                    // Cobb-Douglas outer
                    dOuterDNest = alpha * outerValue / Math.max(nestValue, EPSILON);
                } else {
                    double outerSum = 0;
                    for (int i = 0; i < nests.size(); i++) {
                        outerSum += nestWeights.get(i) * Math.pow(nestValues.get(i), outerRho);
                    }
                    dOuterDNest = alpha * Math.pow(outerSum, (1.0 / outerRho) - 1) 
                                * Math.pow(nestValue, outerRho - 1);
                }
                
                // d(nest_i)/d(a_j) for each resource in nest
                for (var entry : nestW.entrySet()) {
                    ResourceType r = entry.getKey();
                    double w = entry.getValue();
                    long a = allocations.getOrDefault(r, 0L);
                    
                    double dNestDA;
                    if (Math.abs(nestRho) < EPSILON) {
                        // Cobb-Douglas nest
                        dNestDA = w * nestValue / Math.max(a, EPSILON);
                    } else {
                        double nestSum = 0;
                        for (var e : nestW.entrySet()) {
                            long aa = allocations.getOrDefault(e.getKey(), 0L);
                            nestSum += e.getValue() * Math.pow(Math.max(aa, EPSILON), nestRho);
                        }
                        dNestDA = w * Math.pow(nestSum, (1.0 / nestRho) - 1) 
                                * Math.pow(Math.max(a, EPSILON), nestRho - 1);
                    }
                    
                    double dPhiDA = dOuterDNest * dNestDA;
                    grad.put(r, grad.getOrDefault(r, 0.0) + dPhiDA);
                }
            }
            
            return grad;
        }

        public List<Map<ResourceType, Double>> getNests() {
            return Collections.unmodifiableList(nests);
        }

        public List<Double> getNestRhos() {
            return Collections.unmodifiableList(nestRhos);
        }

        public List<Double> getNestWeights() {
            return Collections.unmodifiableList(nestWeights);
        }

        public double getOuterRho() {
            return outerRho;
        }

        @Override
        protected void addExtraParams(Map<String, Object> params) {
            List<Map<String, Double>> nestsForSolver = new ArrayList<>();
            for (var nest : nests) {
                Map<String, Double> n = new HashMap<>();
                for (var e : nest.entrySet()) {
                    n.put(e.getKey().name(), e.getValue());
                }
                nestsForSolver.add(n);
            }
            params.put("nests", nestsForSolver);
            params.put("nest_rhos", nestRhos);
            params.put("nest_weights", nestWeights);
            params.put("outer_rho", outerRho);
        }

        @Override
        public String toString() {
            return String.format("NestedCESUtility[%d nests, ρ_outer=%.2f]", nests.size(), outerRho);
        }
    }

    // ========================================================================
    // Softplus Loss Aversion Utility (Constraint Set 3)
    // ========================================================================

    /**
     * Softplus Loss Aversion utility for reference-dependent preferences.
     * 
     * g(x) = x - (λ - 1) · τ · ln(1 + exp(-x / τ))
     * Φ_i(A) = Σⱼ wⱼ · g(aⱼ - rⱼ)
     * 
     * Properties:
     * - Smooth transition between gains and losses (infinitely differentiable)
     * - λ > 1 means losses hurt more than equivalent gains help
     * - τ controls transition sharpness (smaller = more piecewise-linear)
     * - Globally concave when λ > 1
     * 
     * Asymptotic behavior:
     * - x >> 0: g(x) ≈ x (linear gains)
     * - x << 0: g(x) ≈ λ·x (steeper linear losses)
     */
    public static class SoftplusLossAversionUtility extends UtilityFunction {
        private final Map<ResourceType, Double> referencePoints;
        private final double lambda;
        private final double tau;

        public SoftplusLossAversionUtility(
                Map<ResourceType, Double> weights,
                Map<ResourceType, Double> referencePoints,
                double lambda,
                double tau) {
            super(weights, Type.SOFTPLUS_LOSS_AVERSION);
            this.referencePoints = Collections.unmodifiableMap(new HashMap<>(referencePoints));
            this.lambda = lambda;
            this.tau = tau;
        }

        /**
         * Value function: g(x) = x - (λ - 1) · τ · ln(1 + exp(-x / τ))
         */
        private double g(double x) {
            // Handle numerical overflow
            if (x / tau < -20) {
                // For very negative x: g(x) ≈ λ·x
                return lambda * x;
            }
            if (x / tau > 20) {
                // For very positive x: g(x) ≈ x
                return x;
            }
            return x - (lambda - 1) * tau * Math.log(1 + Math.exp(-x / tau));
        }

        /**
         * Derivative: g'(x) = 1 + (λ - 1) · sigmoid(-x/τ) = λ - (λ - 1) · sigmoid(x/τ)
         */
        private double gPrime(double x) {
            double sigmoidArg = x / tau;
            if (sigmoidArg > 20) return 1.0;
            if (sigmoidArg < -20) return lambda;
            double sigmoid = 1.0 / (1.0 + Math.exp(-sigmoidArg));
            return lambda - (lambda - 1) * sigmoid;
        }

        @Override
        public double evaluate(Map<ResourceType, Long> allocations) {
            double utility = 0;
            for (var entry : weights.entrySet()) {
                ResourceType r = entry.getKey();
                double w = entry.getValue();
                long a = allocations.getOrDefault(r, 0L);
                double ref = referencePoints.getOrDefault(r, 0.0);
                double x = a - ref;
                utility += w * g(x);
            }
            return utility;
        }

        @Override
        public Map<ResourceType, Double> gradient(Map<ResourceType, Long> allocations) {
            Map<ResourceType, Double> grad = new HashMap<>();
            for (var entry : weights.entrySet()) {
                ResourceType r = entry.getKey();
                double w = entry.getValue();
                long a = allocations.getOrDefault(r, 0L);
                double ref = referencePoints.getOrDefault(r, 0.0);
                double x = a - ref;
                grad.put(r, w * gPrime(x));
            }
            return grad;
        }

        public Map<ResourceType, Double> getReferencePoints() {
            return referencePoints;
        }

        public double getLambda() {
            return lambda;
        }

        public double getTau() {
            return tau;
        }

        @Override
        protected void addExtraParams(Map<String, Object> params) {
            Map<String, Double> refs = new HashMap<>();
            for (var e : referencePoints.entrySet()) {
                refs.put(e.getKey().name(), e.getValue());
            }
            params.put("reference_points", refs);
            params.put("lambda", lambda);
            params.put("tau", tau);
        }

        @Override
        public String toString() {
            return String.format("SoftplusLossAversionUtility[λ=%.2f, τ=%.2f]%s", lambda, tau, weights);
        }
    }

    // ========================================================================
    // Asymmetric Log Loss Aversion Utility (Constraint Set 5)
    // ========================================================================

    /**
     * Asymmetric Logarithmic Loss Aversion utility for reference-dependent preferences.
     * 
     * g(x) = ln(1 + x/κ)       if x ≥ 0
     * g(x) = -λ · ln(1 + |x|/κ) if x < 0
     * Φ_i(A) = Σⱼ wⱼ · g(aⱼ - rⱼ)
     * 
     * Properties:
     * - Diminishing sensitivity on both gains and losses
     * - Concave kink at reference point when λ ≥ 1
     * - λ controls loss aversion magnitude
     * - κ controls curvature (larger = more linear)
     * 
     * This models an agent who:
     * - Has satiation-like diminishing returns for surpluses
     * - Has diminishing sensitivity to increasingly severe shortfalls
     * - Still weights shortfalls more heavily than surpluses
     */
    public static class AsymmetricLogLossAversionUtility extends UtilityFunction {
        private static final double EPSILON = 1e-10;
        private final Map<ResourceType, Double> referencePoints;
        private final double lambda;
        private final double kappa;

        public AsymmetricLogLossAversionUtility(
                Map<ResourceType, Double> weights,
                Map<ResourceType, Double> referencePoints,
                double lambda,
                double kappa) {
            super(weights, Type.ASYMMETRIC_LOG_LOSS_AVERSION);
            this.referencePoints = Collections.unmodifiableMap(new HashMap<>(referencePoints));
            this.lambda = Math.max(1.0, lambda);  // Ensure λ ≥ 1 for concavity
            this.kappa = Math.max(EPSILON, kappa);
        }

        /**
         * Value function:
         * g(x) = ln(1 + x/κ) if x ≥ 0
         * g(x) = -λ · ln(1 + |x|/κ) if x < 0
         */
        private double g(double x) {
            if (x >= 0) {
                return Math.log(1 + x / kappa);
            } else {
                return -lambda * Math.log(1 + Math.abs(x) / kappa);
            }
        }

        /**
         * Derivative:
         * g'(x) = 1/(κ + x) if x ≥ 0
         * g'(x) = λ/(κ + |x|) if x < 0
         */
        private double gPrime(double x) {
            if (x >= 0) {
                return 1.0 / (kappa + x);
            } else {
                return lambda / (kappa + Math.abs(x));
            }
        }

        @Override
        public double evaluate(Map<ResourceType, Long> allocations) {
            double utility = 0;
            for (var entry : weights.entrySet()) {
                ResourceType r = entry.getKey();
                double w = entry.getValue();
                long a = allocations.getOrDefault(r, 0L);
                double ref = referencePoints.getOrDefault(r, 0.0);
                double x = a - ref;
                utility += w * g(x);
            }
            return utility;
        }

        @Override
        public Map<ResourceType, Double> gradient(Map<ResourceType, Long> allocations) {
            Map<ResourceType, Double> grad = new HashMap<>();
            for (var entry : weights.entrySet()) {
                ResourceType r = entry.getKey();
                double w = entry.getValue();
                long a = allocations.getOrDefault(r, 0L);
                double ref = referencePoints.getOrDefault(r, 0.0);
                double x = a - ref;
                grad.put(r, w * gPrime(x));
            }
            return grad;
        }

        public Map<ResourceType, Double> getReferencePoints() {
            return referencePoints;
        }

        public double getLambda() {
            return lambda;
        }

        public double getKappa() {
            return kappa;
        }

        @Override
        protected void addExtraParams(Map<String, Object> params) {
            Map<String, Double> refs = new HashMap<>();
            for (var e : referencePoints.entrySet()) {
                refs.put(e.getKey().name(), e.getValue());
            }
            params.put("reference_points", refs);
            params.put("lambda", lambda);
            params.put("kappa", kappa);
        }

        @Override
        public String toString() {
            return String.format("AsymmetricLogLossAversionUtility[λ=%.2f, κ=%.2f]%s", lambda, kappa, weights);
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
        private final Map<ResourceType, Double> referencePoints = new HashMap<>();
        private Type type = Type.LINEAR;
        private double rho = 0.5;  // For CES
        private double threshold = 0;
        private double sharpness = 1.0;
        private double maxUtility = 100;
        private double saturationParam = 10;
        private boolean hyperbolic = false;
        private double lambda = 2.0;  // Loss aversion
        private double tau = 1.0;     // Temperature
        private double kappa = 10.0;  // Curvature

        public Builder weight(ResourceType resource, double weight) {
            weights.put(resource, weight);
            return this;
        }

        public Builder weights(Map<ResourceType, Double> w) {
            weights.putAll(w);
            return this;
        }

        public Builder referencePoint(ResourceType resource, double ref) {
            referencePoints.put(resource, ref);
            return this;
        }

        public Builder referencePoints(Map<ResourceType, Double> refs) {
            referencePoints.putAll(refs);
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

        public Builder threshold(double threshold, double sharpness) {
            this.type = Type.THRESHOLD;
            this.threshold = threshold;
            this.sharpness = sharpness;
            return this;
        }

        public Builder satiation(double maxUtility, double saturationParam) {
            this.type = Type.SATIATION;
            this.maxUtility = maxUtility;
            this.saturationParam = saturationParam;
            this.hyperbolic = false;
            return this;
        }

        public Builder hyperbolicSatiation(double maxUtility, double halfSaturation) {
            this.type = Type.SATIATION;
            this.maxUtility = maxUtility;
            this.saturationParam = halfSaturation;
            this.hyperbolic = true;
            return this;
        }

        public Builder softplusLossAversion(double lambda, double tau) {
            this.type = Type.SOFTPLUS_LOSS_AVERSION;
            this.lambda = lambda;
            this.tau = tau;
            return this;
        }

        public Builder asymmetricLogLossAversion(double lambda, double kappa) {
            this.type = Type.ASYMMETRIC_LOG_LOSS_AVERSION;
            this.lambda = lambda;
            this.kappa = kappa;
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
                case THRESHOLD -> new ThresholdUtility(new LinearUtility(weights), threshold, sharpness);
                case SATIATION -> new SatiationUtility(new LinearUtility(weights), maxUtility, saturationParam, hyperbolic);
                case SOFTPLUS_LOSS_AVERSION -> new SoftplusLossAversionUtility(weights, referencePoints, lambda, tau);
                case ASYMMETRIC_LOG_LOSS_AVERSION -> new AsymmetricLogLossAversionUtility(weights, referencePoints, lambda, kappa);
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

    /**
     * Check if this utility function has loss aversion.
     */
    public boolean hasLossAversion() {
        return type == Type.SOFTPLUS_LOSS_AVERSION || type == Type.ASYMMETRIC_LOG_LOSS_AVERSION;
    }

    /**
     * Check if this is a reference-dependent utility function.
     */
    public boolean isReferenceDependentent() {
        return type == Type.SOFTPLUS_LOSS_AVERSION || type == Type.ASYMMETRIC_LOG_LOSS_AVERSION;
    }
}
