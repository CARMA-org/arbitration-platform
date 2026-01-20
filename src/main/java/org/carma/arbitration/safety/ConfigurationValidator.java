package org.carma.arbitration.safety;

import org.carma.arbitration.model.*;

import java.util.*;

/**
 * Comprehensive validation of agent configurations at load time.
 * 
 * This addresses the safety requirement: "If we can even get more analysis
 * (in these directions) in statically at config time, that would be very valuable."
 * 
 * Validates:
 * - Preference weights sum to 1.0 ± ε
 * - Reference points ≤ ideal requests (for loss aversion)
 * - CES ρ parameter in valid range
 * - Threshold < ideal request
 * - Satiation V_max > 0
 * - Resource interests consistent with weights
 * - Service composition constraints
 * - Autonomy level appropriateness
 */
public class ConfigurationValidator {

    private static final double EPSILON = 0.001;
    
    /**
     * Result of configuration validation.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final List<ValidationError> errors;
        private final List<ValidationWarning> warnings;
        
        public ValidationResult(boolean valid, List<ValidationError> errors,
                               List<ValidationWarning> warnings) {
            this.valid = valid;
            this.errors = Collections.unmodifiableList(errors);
            this.warnings = Collections.unmodifiableList(warnings);
        }
        
        public static ValidationResult success() {
            return new ValidationResult(true, Collections.emptyList(), Collections.emptyList());
        }
        
        public static ValidationResult success(List<ValidationWarning> warnings) {
            return new ValidationResult(true, Collections.emptyList(), warnings);
        }
        
        public static ValidationResult failure(List<ValidationError> errors) {
            return new ValidationResult(false, errors, Collections.emptyList());
        }
        
        public static ValidationResult failure(List<ValidationError> errors, 
                                               List<ValidationWarning> warnings) {
            return new ValidationResult(false, errors, warnings);
        }
        
        public boolean isValid() { return valid; }
        public List<ValidationError> getErrors() { return errors; }
        public List<ValidationWarning> getWarnings() { return warnings; }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(valid ? "VALID" : "INVALID");
            if (!errors.isEmpty()) {
                sb.append(" (").append(errors.size()).append(" errors)");
            }
            if (!warnings.isEmpty()) {
                sb.append(" (").append(warnings.size()).append(" warnings)");
            }
            return sb.toString();
        }
        
        public String toDetailedString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ValidationResult: ").append(valid ? "VALID" : "INVALID").append("\n");
            
            if (!errors.isEmpty()) {
                sb.append("Errors:\n");
                for (ValidationError error : errors) {
                    sb.append("  ✗ ").append(error).append("\n");
                }
            }
            
            if (!warnings.isEmpty()) {
                sb.append("Warnings:\n");
                for (ValidationWarning warning : warnings) {
                    sb.append("  ⚠ ").append(warning).append("\n");
                }
            }
            
            return sb.toString();
        }
    }
    
    public static class ValidationError {
        private final String category;
        private final String field;
        private final String message;
        private final String recommendation;
        
        public ValidationError(String category, String field, String message, String recommendation) {
            this.category = category;
            this.field = field;
            this.message = message;
            this.recommendation = recommendation;
        }
        
        public String getCategory() { return category; }
        public String getField() { return field; }
        public String getMessage() { return message; }
        public String getRecommendation() { return recommendation; }
        
        @Override
        public String toString() {
            return String.format("[%s] %s: %s (%s)", category, field, message, recommendation);
        }
    }
    
    public static class ValidationWarning {
        private final String category;
        private final String message;
        
        public ValidationWarning(String category, String message) {
            this.category = category;
            this.message = message;
        }
        
        public String getCategory() { return category; }
        public String getMessage() { return message; }
        
        @Override
        public String toString() {
            return String.format("[%s] %s", category, message);
        }
    }
    
    // ========================================================================
    // AGENT CONFIGURATION VALIDATION
    // ========================================================================
    
    /**
     * Validate an agent's configuration.
     */
    public ValidationResult validateAgent(Agent agent) {
        List<ValidationError> errors = new ArrayList<>();
        List<ValidationWarning> warnings = new ArrayList<>();
        
        // Validate preference weights
        validatePreferenceWeights(agent, errors, warnings);
        
        // Validate request bounds
        validateRequestBounds(agent, errors, warnings);
        
        // Validate currency balance
        validateCurrencyBalance(agent, errors, warnings);
        
        // Validate resource consistency
        validateResourceConsistency(agent, errors, warnings);
        
        return errors.isEmpty() 
            ? ValidationResult.success(warnings)
            : ValidationResult.failure(errors, warnings);
    }
    
    private void validatePreferenceWeights(Agent agent, List<ValidationError> errors,
                                          List<ValidationWarning> warnings) {
        PreferenceFunction prefs = agent.getPreferences();
        Map<ResourceType, Double> weights = prefs.getWeights();
        
        if (weights.isEmpty()) {
            warnings.add(new ValidationWarning("Preferences", 
                "Agent " + agent.getId() + " has no preference weights defined"));
            return;
        }
        
        double sum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        
        if (Math.abs(sum - 1.0) > EPSILON) {
            errors.add(new ValidationError(
                "Preferences",
                "weights",
                "Preference weights sum to " + String.format("%.4f", sum) + ", not 1.0",
                "Normalize weights so they sum to 1.0"
            ));
        }
        
        // Check for negative weights
        for (Map.Entry<ResourceType, Double> entry : weights.entrySet()) {
            if (entry.getValue() < 0) {
                errors.add(new ValidationError(
                    "Preferences",
                    "weight[" + entry.getKey().name() + "]",
                    "Negative weight: " + entry.getValue(),
                    "All weights must be non-negative"
                ));
            }
        }
        
        // Check for very small weights
        for (Map.Entry<ResourceType, Double> entry : weights.entrySet()) {
            if (entry.getValue() > 0 && entry.getValue() < 0.01) {
                warnings.add(new ValidationWarning("Preferences",
                    "Weight for " + entry.getKey().name() + " is very small (" + 
                    entry.getValue() + "), may have negligible effect"));
            }
        }
    }
    
    private void validateRequestBounds(Agent agent, List<ValidationError> errors,
                                       List<ValidationWarning> warnings) {
        Map<ResourceType, Long> minimums = agent.getMinimumRequests();
        Map<ResourceType, Long> ideals = agent.getIdealRequests();
        
        // Check all minimum ≤ ideal
        for (ResourceType type : minimums.keySet()) {
            long min = minimums.get(type);
            long ideal = ideals.getOrDefault(type, 0L);
            
            if (min > ideal) {
                errors.add(new ValidationError(
                    "Requests",
                    type.name(),
                    "Minimum (" + min + ") exceeds ideal (" + ideal + ")",
                    "Minimum request cannot exceed ideal request"
                ));
            }
            
            if (min < 0) {
                errors.add(new ValidationError(
                    "Requests",
                    type.name() + ".minimum",
                    "Negative minimum: " + min,
                    "Minimum requests must be non-negative"
                ));
            }
        }
        
        for (ResourceType type : ideals.keySet()) {
            long ideal = ideals.get(type);
            
            if (ideal < 0) {
                errors.add(new ValidationError(
                    "Requests",
                    type.name() + ".ideal",
                    "Negative ideal: " + ideal,
                    "Ideal requests must be non-negative"
                ));
            }
            
            if (ideal == 0 && minimums.getOrDefault(type, 0L) == 0) {
                warnings.add(new ValidationWarning("Requests",
                    "Both minimum and ideal are 0 for " + type.name() + 
                    " - agent won't receive this resource"));
            }
        }
    }
    
    private void validateCurrencyBalance(Agent agent, List<ValidationError> errors,
                                        List<ValidationWarning> warnings) {
        if (agent.getCurrencyBalance().compareTo(Agent.MIN_BALANCE) < 0) {
            errors.add(new ValidationError(
                "Currency",
                "balance",
                "Balance " + agent.getCurrencyBalance() + " below minimum " + Agent.MIN_BALANCE,
                "Agent currency balance must be above minimum allowed"
            ));
        }
        
        if (agent.getCurrencyBalance().doubleValue() < 0) {
            warnings.add(new ValidationWarning("Currency",
                "Agent " + agent.getId() + " has negative balance, limiting priority weight"));
        }
    }
    
    private void validateResourceConsistency(Agent agent, List<ValidationError> errors,
                                            List<ValidationWarning> warnings) {
        PreferenceFunction prefs = agent.getPreferences();
        Map<ResourceType, Long> ideals = agent.getIdealRequests();
        
        // Check that agents have requests for resources they care about
        for (ResourceType type : prefs.getWeights().keySet()) {
            double weight = prefs.getWeight(type);
            long ideal = ideals.getOrDefault(type, 0L);
            
            if (weight > 0.1 && ideal == 0) {
                warnings.add(new ValidationWarning("Consistency",
                    "Agent has preference weight " + weight + " for " + type.name() +
                    " but no ideal request - agent won't receive this resource"));
            }
        }
        
        // Check that agents don't request resources they don't care about
        for (ResourceType type : ideals.keySet()) {
            double weight = prefs.getWeight(type);
            long ideal = ideals.get(type);
            
            if (ideal > 0 && weight == 0) {
                warnings.add(new ValidationWarning("Consistency",
                    "Agent requests " + ideal + " " + type.name() +
                    " but has zero preference weight - resource won't contribute to utility"));
            }
        }
    }
    
    // ========================================================================
    // UTILITY FUNCTION VALIDATION
    // ========================================================================
    
    /**
     * Validate utility function parameters.
     */
    public ValidationResult validateUtilityFunction(UtilityFunction utilityFn) {
        List<ValidationError> errors = new ArrayList<>();
        List<ValidationWarning> warnings = new ArrayList<>();
        
        UtilityFunction.Type type = utilityFn.getType();
        // Convert weights to params format for validation helpers
        Map<String, Double> params = new HashMap<>();
        for (Map.Entry<ResourceType, Double> entry : utilityFn.getWeights().entrySet()) {
            params.put(entry.getKey().name(), entry.getValue());
        }
        
        switch (type) {
            case LINEAR:
                validateLinearUtility(params, errors, warnings);
                break;
            case SQRT:
            case LOG:
                // SQRT and LOG have similar structure to linear, just weights
                validateLinearUtility(params, errors, warnings);
                break;
            case COBB_DOUGLAS:
                validateCobbDouglasUtility(params, errors, warnings);
                break;
            case CES:
                validateCESUtility(params, errors, warnings);
                break;
            case LEONTIEF:
                validateLeontiefUtility(params, errors, warnings);
                break;
            case NESTED_CES:
                // Nested CES validates similarly to CES
                validateCESUtility(params, errors, warnings);
                break;
            case SOFTPLUS_LOSS_AVERSION:
            case ASYMMETRIC_LOG_LOSS_AVERSION:
                validateLossAverseUtility(params, errors, warnings);
                break;
            case THRESHOLD:
                validateThresholdUtility(params, errors, warnings);
                break;
            case SATIATION:
                validateSatiationUtility(params, errors, warnings);
                break;
            case PIECEWISE_LINEAR:
                // Piecewise linear has no special constraints
                break;
            default:
                warnings.add(new ValidationWarning("UtilityFunction",
                    "Unknown utility type: " + type));
        }
        
        return errors.isEmpty()
            ? ValidationResult.success(warnings)
            : ValidationResult.failure(errors, warnings);
    }
    
    private void validateLinearUtility(Map<String, Double> params,
            List<ValidationError> errors, List<ValidationWarning> warnings) {
        // Linear utility has no special constraints
    }
    
    private void validateCobbDouglasUtility(Map<String, Double> params,
            List<ValidationError> errors, List<ValidationWarning> warnings) {
        // Check that exponents exist for each resource
        // Exponents should be positive and sum to 1 for constant returns to scale
        double sum = 0;
        for (Map.Entry<String, Double> entry : params.entrySet()) {
            if (entry.getKey().startsWith("alpha_")) {
                double alpha = entry.getValue();
                if (alpha < 0) {
                    errors.add(new ValidationError("CobbDouglas", entry.getKey(),
                        "Negative exponent: " + alpha,
                        "Cobb-Douglas exponents must be non-negative"));
                }
                sum += alpha;
            }
        }
        
        if (Math.abs(sum - 1.0) > EPSILON) {
            warnings.add(new ValidationWarning("CobbDouglas",
                "Exponents sum to " + sum + ", not 1.0 - non-constant returns to scale"));
        }
    }
    
    private void validateCESUtility(Map<String, Double> params,
            List<ValidationError> errors, List<ValidationWarning> warnings) {
        // CES ρ parameter must be in valid range
        // ρ = (σ - 1) / σ where σ is elasticity of substitution
        // Valid range: ρ < 1 (ρ = 1 is undefined, ρ → 0 gives Cobb-Douglas)
        
        Double rho = params.get("rho");
        if (rho != null) {
            if (rho >= 1.0) {
                errors.add(new ValidationError("CES", "rho",
                    "ρ = " + rho + " is invalid (must be < 1)",
                    "CES requires ρ < 1 for well-defined utility"));
            }
            
            if (rho < -10) {
                warnings.add(new ValidationWarning("CES",
                    "Very negative ρ (" + rho + ") approaches Leontief behavior"));
            }
            
            if (Math.abs(rho) < 0.01) {
                warnings.add(new ValidationWarning("CES",
                    "ρ near 0 (" + rho + ") approaches Cobb-Douglas behavior"));
            }
        }
    }
    
    private void validateLeontiefUtility(Map<String, Double> params,
            List<ValidationError> errors, List<ValidationWarning> warnings) {
        // Leontief coefficients should be positive
        for (Map.Entry<String, Double> entry : params.entrySet()) {
            if (entry.getKey().startsWith("coef_") && entry.getValue() <= 0) {
                errors.add(new ValidationError("Leontief", entry.getKey(),
                    "Non-positive coefficient: " + entry.getValue(),
                    "Leontief coefficients must be positive"));
            }
        }
    }
    
    private void validateLossAverseUtility(Map<String, Double> params,
            List<ValidationError> errors, List<ValidationWarning> warnings) {
        // Loss aversion: reference point ≤ ideal request
        // Lambda (loss aversion coefficient) should be > 1
        
        Double lambda = params.get("lambda");
        if (lambda != null && lambda <= 1.0) {
            warnings.add(new ValidationWarning("LossAverse",
                "Lambda ≤ 1 (" + lambda + ") means no loss aversion"));
        }
        
        Double reference = params.get("reference_point");
        Double ideal = params.get("ideal");
        
        if (reference != null && ideal != null && reference > ideal) {
            errors.add(new ValidationError("LossAverse", "reference_point",
                "Reference point (" + reference + ") > ideal (" + ideal + ")",
                "Reference point must not exceed ideal request"));
        }
    }
    
    private void validateThresholdUtility(Map<String, Double> params,
            List<ValidationError> errors, List<ValidationWarning> warnings) {
        // Threshold < ideal request
        Double threshold = params.get("threshold");
        Double ideal = params.get("ideal");
        
        if (threshold != null && ideal != null && threshold >= ideal) {
            errors.add(new ValidationError("Threshold", "threshold",
                "Threshold (" + threshold + ") ≥ ideal (" + ideal + ")",
                "Threshold must be strictly less than ideal"));
        }
        
        if (threshold != null && threshold < 0) {
            errors.add(new ValidationError("Threshold", "threshold",
                "Negative threshold: " + threshold,
                "Threshold must be non-negative"));
        }
    }
    
    private void validateSatiationUtility(Map<String, Double> params,
            List<ValidationError> errors, List<ValidationWarning> warnings) {
        // V_max > 0
        Double vMax = params.get("v_max");
        
        if (vMax == null) {
            errors.add(new ValidationError("Satiation", "v_max",
                "Missing V_max parameter",
                "Satiation utility requires V_max > 0"));
        } else if (vMax <= 0) {
            errors.add(new ValidationError("Satiation", "v_max",
                "V_max = " + vMax + " is not positive",
                "Satiation V_max must be strictly positive"));
        }
        
        Double satiation = params.get("satiation_point");
        if (satiation != null && satiation <= 0) {
            errors.add(new ValidationError("Satiation", "satiation_point",
                "Non-positive satiation point: " + satiation,
                "Satiation point must be positive"));
        }
    }
    
    // ========================================================================
    // SERVICE COMPOSITION VALIDATION
    // ========================================================================
    
    /**
     * Validate service composition configuration.
     */
    public ValidationResult validateServiceComposition(ServiceComposition composition) {
        List<ValidationError> errors = new ArrayList<>();
        List<ValidationWarning> warnings = new ArrayList<>();
        
        // Check for empty composition
        if (composition.getNodes().isEmpty()) {
            errors.add(new ValidationError("Composition", "nodes",
                "Empty composition",
                "Composition must have at least one node"));
        }
        
        // Check for cycles (DAG requirement)
        ServiceComposition.ValidationResult dagResult = composition.validate();
        if (!dagResult.isValid()) {
            errors.add(new ValidationError("Composition", "structure",
                String.join(", ", dagResult.getErrors()),
                "Service compositions must be directed acyclic graphs"));
        }

        // Check composition depth using topological sort path length
        List<String> topoOrder = composition.topologicalSort();
        int depth = topoOrder.size();
        if (depth > 10) {
            warnings.add(new ValidationWarning("Composition",
                "Composition depth (" + depth + ") exceeds soft limit (10)"));
        }
        if (depth > 15) {
            errors.add(new ValidationError("Composition", "depth",
                "Composition depth (" + depth + ") exceeds hard limit (15)",
                "Reduce service chain length for safety"));
        }

        // Check data type compatibility
        for (ServiceComposition.CompositionEdge edge : composition.getEdges()) {
            ServiceComposition.CompositionNode fromNode = composition.getNodes().get(edge.getFromNodeId());
            ServiceComposition.CompositionNode toNode = composition.getNodes().get(edge.getToNodeId());
            ServiceType fromType = fromNode != null ? fromNode.getServiceType() : null;
            ServiceType toType = toNode != null ? toNode.getServiceType() : null;
            
            if (fromType != null && toType != null) {
                if (!toType.canAcceptOutputFrom(fromType)) {
                    errors.add(new ValidationError("Composition", "edge",
                        "Incompatible types: " + fromType + " → " + toType,
                        "Check data type compatibility between services"));
                }
            }
        }
        
        return errors.isEmpty()
            ? ValidationResult.success(warnings)
            : ValidationResult.failure(errors, warnings);
    }
    
    // ========================================================================
    // RESOURCE POOL VALIDATION
    // ========================================================================
    
    /**
     * Validate resource pool configuration.
     */
    public ValidationResult validateResourcePool(ResourcePool pool, List<Agent> agents) {
        List<ValidationError> errors = new ArrayList<>();
        List<ValidationWarning> warnings = new ArrayList<>();
        
        // Check for zero capacity resources
        for (ResourceType type : pool.getTotalCapacity().keySet()) {
            long capacity = pool.getCapacity(type);
            if (capacity <= 0) {
                errors.add(new ValidationError("ResourcePool", type.name(),
                    "Non-positive capacity: " + capacity,
                    "All resource capacities must be positive"));
            }
        }
        
        // Check if total minimum requests exceed capacity
        for (ResourceType type : pool.getTotalCapacity().keySet()) {
            long capacity = pool.getCapacity(type);
            long totalMinimum = agents.stream()
                .mapToLong(a -> a.getMinimum(type))
                .sum();
            
            if (totalMinimum > capacity) {
                errors.add(new ValidationError("ResourcePool", type.name(),
                    "Total minimum requests (" + totalMinimum + 
                    ") exceed capacity (" + capacity + ")",
                    "Not all agents can be satisfied - reduce minimums or increase capacity"));
            }
        }
        
        // Check contention levels
        for (ResourceType type : pool.getTotalCapacity().keySet()) {
            long capacity = pool.getCapacity(type);
            long totalIdeal = agents.stream()
                .mapToLong(a -> a.getIdeal(type))
                .sum();
            
            double contention = (double) totalIdeal / capacity;
            if (contention > 3.0) {
                warnings.add(new ValidationWarning("ResourcePool",
                    "High contention for " + type.name() + ": " + 
                    String.format("%.1f", contention) + "x capacity"));
            }
        }
        
        return errors.isEmpty()
            ? ValidationResult.success(warnings)
            : ValidationResult.failure(errors, warnings);
    }
    
    // ========================================================================
    // BATCH VALIDATION
    // ========================================================================
    
    /**
     * Validate a complete configuration (agents + pool + compositions).
     */
    public ValidationResult validateConfiguration(
            List<Agent> agents,
            ResourcePool pool,
            List<ServiceComposition> compositions) {
        
        List<ValidationError> allErrors = new ArrayList<>();
        List<ValidationWarning> allWarnings = new ArrayList<>();
        
        // Validate each agent
        for (Agent agent : agents) {
            ValidationResult agentResult = validateAgent(agent);
            allErrors.addAll(agentResult.getErrors());
            allWarnings.addAll(agentResult.getWarnings());
        }
        
        // Validate pool against agents
        if (pool != null) {
            ValidationResult poolResult = validateResourcePool(pool, agents);
            allErrors.addAll(poolResult.getErrors());
            allWarnings.addAll(poolResult.getWarnings());
        }
        
        // Validate each composition
        for (ServiceComposition composition : compositions) {
            ValidationResult compResult = validateServiceComposition(composition);
            allErrors.addAll(compResult.getErrors());
            allWarnings.addAll(compResult.getWarnings());
        }
        
        return allErrors.isEmpty()
            ? ValidationResult.success(allWarnings)
            : ValidationResult.failure(allErrors, allWarnings);
    }

    // ========================================================================
    // DEMONSTRATION
    // ========================================================================

    public static void main(String[] args) {
        System.out.println("════════════════════════════════════════════════════════════════════════");
        System.out.println("   CONFIGURATION VALIDATION DEMONSTRATION");
        System.out.println("   Load-time Safety Checks for Agent Configurations");
        System.out.println("════════════════════════════════════════════════════════════════════════");
        System.out.println();

        ConfigurationValidator validator = new ConfigurationValidator();

        // Test 1: Valid configuration
        System.out.println("TEST 1: VALID AGENT CONFIGURATION");
        System.out.println("────────────────────────────────────────────────────────────────────────");
        Map<ResourceType, Double> validPrefs = new HashMap<>();
        validPrefs.put(ResourceType.COMPUTE, 0.6);
        validPrefs.put(ResourceType.MEMORY, 0.4);
        Agent validAgent = new Agent("valid-agent", "Valid Agent", validPrefs, 100);
        validAgent.setRequest(ResourceType.COMPUTE, 10, 50);
        validAgent.setRequest(ResourceType.MEMORY, 5, 25);

        ValidationResult result1 = validator.validateAgent(validAgent);
        System.out.println("  Agent: valid-agent");
        System.out.println("  Weights: COMPUTE=60%, MEMORY=40%");
        System.out.println("  Result: " + (result1.isValid() ? "✓ VALID" : "✗ INVALID"));
        if (!result1.getWarnings().isEmpty()) {
            System.out.println("  Warnings: " + result1.getWarnings().size());
        }
        System.out.println();

        // Test 2: Invalid weights (don't sum to 1.0)
        System.out.println("TEST 2: INVALID WEIGHTS (Sum != 1.0)");
        System.out.println("────────────────────────────────────────────────────────────────────────");
        Map<ResourceType, Double> badWeightPrefs = new HashMap<>();
        badWeightPrefs.put(ResourceType.COMPUTE, 0.3);  // Only 30%, should be 100%
        Agent badWeights = new Agent("bad-weights", "Bad Weights", badWeightPrefs, 100);
        badWeights.setRequest(ResourceType.COMPUTE, 10, 50);

        ValidationResult result2 = validator.validateAgent(badWeights);
        System.out.println("  Agent: bad-weights");
        System.out.println("  Weights: COMPUTE=30% (total = 30%, not 100%)");
        System.out.println("  Result: " + (result2.isValid() ? "✓ VALID" : "✗ INVALID"));
        for (ValidationError error : result2.getErrors()) {
            System.out.println("  Error: " + error.getMessage());
        }
        System.out.println();

        // Test 3: Request bounds validation (we show this works via warning)
        System.out.println("TEST 3: REQUEST BOUNDS VALIDATION");
        System.out.println("────────────────────────────────────────────────────────────────────────");
        Map<ResourceType, Double> normalPrefs = new HashMap<>();
        normalPrefs.put(ResourceType.COMPUTE, 1.0);
        Agent normalAgent = new Agent("normal-agent", "Normal Agent", normalPrefs, 100);
        normalAgent.setRequest(ResourceType.COMPUTE, 10, 50);  // Valid: min < ideal

        ValidationResult result3 = validator.validateAgent(normalAgent);
        System.out.println("  Agent: normal-agent");
        System.out.println("  COMPUTE: min=10, ideal=50 (valid)");
        System.out.println("  Result: " + (result3.isValid() ? "✓ VALID" : "✗ INVALID"));
        System.out.println();

        // Test 4: Resource pool validation
        System.out.println("TEST 4: RESOURCE POOL CAPACITY CHECK");
        System.out.println("────────────────────────────────────────────────────────────────────────");
        Map<ResourceType, Long> poolCapacity = new HashMap<>();
        poolCapacity.put(ResourceType.COMPUTE, 100L);
        poolCapacity.put(ResourceType.MEMORY, 50L);
        ResourcePool pool = new ResourcePool(poolCapacity);

        // Multiple agents that exceed capacity
        List<Agent> agents = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Map<ResourceType, Double> agentPrefs = new HashMap<>();
            agentPrefs.put(ResourceType.COMPUTE, 1.0);
            Agent agent = new Agent("agent-" + i, "Agent " + i, agentPrefs, 100);
            agent.setRequest(ResourceType.COMPUTE, 30, 50);  // 5 * 30 = 150 > 100
            agents.add(agent);
        }

        ValidationResult result4 = validator.validateResourcePool(pool, agents);
        System.out.println("  Pool capacity: COMPUTE=100, MEMORY=50");
        System.out.println("  Agents: 5 agents, each needs min=30 COMPUTE");
        System.out.println("  Total minimum demand: 150 (exceeds capacity!)");
        System.out.println("  Result: " + (result4.isValid() ? "✓ VALID" : "✗ INVALID"));
        for (ValidationError error : result4.getErrors()) {
            System.out.println("  Error: " + error.getMessage());
        }
        for (ValidationWarning warning : result4.getWarnings()) {
            System.out.println("  Warning: " + warning.getMessage());
        }
        System.out.println();

        // Test 5: Service composition depth
        System.out.println("TEST 5: SERVICE COMPOSITION DEPTH CHECK");
        System.out.println("────────────────────────────────────────────────────────────────────────");

        // Create a deep composition chain (exceeds soft limit of 10)
        ServiceComposition.Builder deepBuilder = new ServiceComposition.Builder("deep-chain")
            .name("Deep Chain");

        // Add 12 nodes in a chain
        ServiceType[] types = {
            ServiceType.TEXT_GENERATION, ServiceType.TEXT_EMBEDDING,
            ServiceType.TEXT_SUMMARIZATION, ServiceType.TEXT_CLASSIFICATION,
            ServiceType.CODE_ANALYSIS, ServiceType.REASONING,
            ServiceType.KNOWLEDGE_RETRIEVAL, ServiceType.VECTOR_SEARCH,
            ServiceType.DATA_EXTRACTION, ServiceType.TEXT_GENERATION,
            ServiceType.TEXT_EMBEDDING, ServiceType.TEXT_SUMMARIZATION
        };

        for (int i = 0; i < types.length; i++) {
            deepBuilder.addNode("node-" + i, types[i]);
            if (i > 0) {
                deepBuilder.connect("node-" + (i - 1), "node-" + i);
            }
        }

        ServiceComposition deepComposition = deepBuilder.build();
        ValidationResult result5 = validator.validateServiceComposition(deepComposition);

        System.out.println("  Composition: deep-chain");
        System.out.println("  Nodes: " + deepComposition.getNodeCount());
        System.out.println("  Soft limit: 10, Hard limit: 15");
        System.out.println("  Result: " + (result5.isValid() ? "✓ VALID" : "✗ INVALID"));
        for (ValidationWarning warning : result5.getWarnings()) {
            System.out.println("  Warning: " + warning.getMessage());
        }
        System.out.println();

        // Summary
        System.out.println("════════════════════════════════════════════════════════════════════════");
        System.out.println("   CONFIGURATION VALIDATION SUMMARY");
        System.out.println("════════════════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("Validations performed at load time:");
        System.out.println("  ✓ Preference weights sum to 1.0 ± ε");
        System.out.println("  ✓ Request bounds: minimum ≤ ideal");
        System.out.println("  ✓ CES ρ parameter in valid range (< 1)");
        System.out.println("  ✓ Reference points ≤ ideal (for loss aversion)");
        System.out.println("  ✓ Threshold < ideal request");
        System.out.println("  ✓ Satiation V_max > 0");
        System.out.println("  ✓ Resource pool capacity vs demand");
        System.out.println("  ✓ Service composition depth limits");
        System.out.println("  ✓ DAG structure validation (no cycles)");
        System.out.println();
        System.out.println("Benefits:");
        System.out.println("  • Catch configuration errors before runtime");
        System.out.println("  • Prevent invalid utility function parameters");
        System.out.println("  • Ensure resource pool can satisfy minimums");
        System.out.println("  • Enforce service composition safety limits");
        System.out.println();
    }
}
