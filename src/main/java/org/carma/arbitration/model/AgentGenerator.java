package org.carma.arbitration.model;

import java.util.*;

/**
 * Auto-generation of agents with random utility functions for testing.
 * 
 * Supports generating thousands of agents with diverse utility configurations
 * while maintaining reproducibility through static random seeds.
 * 
 * Features:
 * - Random sampling from all available utility function types
 * - Configurable probability distributions for utility types
 * - Random parameter generation within reasonable bounds
 * - Static seed for reproducibility
 * - Batch generation for large-scale testing
 */
public class AgentGenerator {

    private final Random random;
    private final long seed;
    
    // Default probabilities for each utility type (must sum to 1.0)
    private Map<UtilityFunction.Type, Double> typeProbabilities;
    
    // Resource types to consider
    private List<ResourceType> resourceTypes;
    
    // Parameter bounds
    private double minWeight = 0.1;
    private double maxWeight = 1.0;
    private double minRho = -5.0;
    private double maxRho = 0.9;
    private double minThreshold = 10.0;
    private double maxThreshold = 100.0;
    private double minSharpness = 0.5;
    private double maxSharpness = 10.0;
    private double minMaxUtility = 50.0;
    private double maxMaxUtility = 200.0;
    private double minSaturationParam = 5.0;
    private double maxSaturationParam = 50.0;
    private double minLambda = 1.0;
    private double maxLambda = 4.0;
    private double minTau = 0.5;
    private double maxTau = 5.0;
    private double minKappa = 5.0;
    private double maxKappa = 50.0;
    private double minReferencePoint = 10.0;
    private double maxReferencePoint = 80.0;

    /**
     * Create a generator with a specific seed for reproducibility.
     */
    public AgentGenerator(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
        this.resourceTypes = Arrays.asList(ResourceType.values());
        initDefaultProbabilities();
    }

    /**
     * Create a generator with the default seed (42).
     */
    public AgentGenerator() {
        this(42L);
    }

    private void initDefaultProbabilities() {
        typeProbabilities = new LinkedHashMap<>();
        // Distribute probability across all types
        typeProbabilities.put(UtilityFunction.Type.LINEAR, 0.15);
        typeProbabilities.put(UtilityFunction.Type.SQRT, 0.12);
        typeProbabilities.put(UtilityFunction.Type.LOG, 0.12);
        typeProbabilities.put(UtilityFunction.Type.COBB_DOUGLAS, 0.12);
        typeProbabilities.put(UtilityFunction.Type.LEONTIEF, 0.08);
        typeProbabilities.put(UtilityFunction.Type.CES, 0.10);
        typeProbabilities.put(UtilityFunction.Type.THRESHOLD, 0.08);
        typeProbabilities.put(UtilityFunction.Type.SATIATION, 0.08);
        typeProbabilities.put(UtilityFunction.Type.NESTED_CES, 0.05);
        typeProbabilities.put(UtilityFunction.Type.SOFTPLUS_LOSS_AVERSION, 0.05);
        typeProbabilities.put(UtilityFunction.Type.ASYMMETRIC_LOG_LOSS_AVERSION, 0.05);
    }

    // ========================================================================
    // Configuration Methods
    // ========================================================================

    /**
     * Set the probability distribution for utility types.
     */
    public AgentGenerator setTypeProbabilities(Map<UtilityFunction.Type, Double> probabilities) {
        // Validate probabilities sum to 1.0
        double sum = probabilities.values().stream().mapToDouble(Double::doubleValue).sum();
        if (Math.abs(sum - 1.0) > 0.01) {
            throw new IllegalArgumentException("Probabilities must sum to 1.0, got " + sum);
        }
        this.typeProbabilities = new LinkedHashMap<>(probabilities);
        return this;
    }

    /**
     * Set the resource types to use in generated utility functions.
     */
    public AgentGenerator setResourceTypes(List<ResourceType> types) {
        this.resourceTypes = new ArrayList<>(types);
        return this;
    }

    /**
     * Set parameter bounds for weight generation.
     */
    public AgentGenerator setWeightBounds(double min, double max) {
        this.minWeight = min;
        this.maxWeight = max;
        return this;
    }

    /**
     * Set parameter bounds for CES rho generation.
     */
    public AgentGenerator setRhoBounds(double min, double max) {
        this.minRho = min;
        this.maxRho = max;
        return this;
    }

    /**
     * Set parameter bounds for threshold generation.
     */
    public AgentGenerator setThresholdBounds(double min, double max) {
        this.minThreshold = min;
        this.maxThreshold = max;
        return this;
    }

    /**
     * Set parameter bounds for loss aversion lambda.
     */
    public AgentGenerator setLambdaBounds(double min, double max) {
        this.minLambda = Math.max(1.0, min);  // Lambda must be >= 1
        this.maxLambda = max;
        return this;
    }

    /**
     * Set parameter bounds for reference points.
     */
    public AgentGenerator setReferencePointBounds(double min, double max) {
        this.minReferencePoint = min;
        this.maxReferencePoint = max;
        return this;
    }

    /**
     * Reset the random generator to its initial seed.
     */
    public AgentGenerator reset() {
        this.random.setSeed(seed);
        return this;
    }

    // ========================================================================
    // Random Generation Methods
    // ========================================================================

    private double randomInRange(double min, double max) {
        return min + random.nextDouble() * (max - min);
    }

    private UtilityFunction.Type randomType() {
        double r = random.nextDouble();
        double cumulative = 0;
        for (var entry : typeProbabilities.entrySet()) {
            cumulative += entry.getValue();
            if (r <= cumulative) {
                return entry.getKey();
            }
        }
        return UtilityFunction.Type.LINEAR;  // Fallback
    }

    private Map<ResourceType, Double> randomWeights() {
        Map<ResourceType, Double> weights = new HashMap<>();
        
        // Randomly select a subset of resources (at least 2)
        int numResources = 2 + random.nextInt(resourceTypes.size() - 1);
        List<ResourceType> shuffled = new ArrayList<>(resourceTypes);
        Collections.shuffle(shuffled, random);
        
        for (int i = 0; i < numResources; i++) {
            weights.put(shuffled.get(i), randomInRange(minWeight, maxWeight));
        }
        
        // Normalize
        double sum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        for (ResourceType r : weights.keySet()) {
            weights.put(r, weights.get(r) / sum);
        }
        
        return weights;
    }

    private Map<ResourceType, Double> randomReferencePoints(Map<ResourceType, Double> weights) {
        Map<ResourceType, Double> refs = new HashMap<>();
        for (ResourceType r : weights.keySet()) {
            refs.put(r, randomInRange(minReferencePoint, maxReferencePoint));
        }
        return refs;
    }

    // ========================================================================
    // Utility Function Generation
    // ========================================================================

    /**
     * Generate a random utility function.
     */
    public UtilityFunction generateUtilityFunction() {
        return generateUtilityFunction(randomType());
    }

    /**
     * Generate a random utility function of a specific type.
     */
    public UtilityFunction generateUtilityFunction(UtilityFunction.Type type) {
        Map<ResourceType, Double> weights = randomWeights();
        
        return switch (type) {
            case LINEAR -> UtilityFunction.linear(weights);
            
            case SQRT -> UtilityFunction.sqrt(weights);
            
            case LOG -> UtilityFunction.log(weights);
            
            case COBB_DOUGLAS -> UtilityFunction.cobbDouglas(weights);
            
            case LEONTIEF -> UtilityFunction.leontief(weights);
            
            case CES -> UtilityFunction.ces(weights, randomInRange(minRho, maxRho));
            
            case THRESHOLD -> {
                UtilityFunction base = generateBaseUtilityForWrapper(weights);
                yield UtilityFunction.threshold(base, 
                    randomInRange(minThreshold, maxThreshold),
                    randomInRange(minSharpness, maxSharpness));
            }
            
            case SATIATION -> {
                UtilityFunction base = generateBaseUtilityForWrapper(weights);
                boolean hyperbolic = random.nextBoolean();
                if (hyperbolic) {
                    yield UtilityFunction.hyperbolicSatiation(base,
                        randomInRange(minMaxUtility, maxMaxUtility),
                        randomInRange(minSaturationParam, maxSaturationParam));
                } else {
                    yield UtilityFunction.satiation(base,
                        randomInRange(minMaxUtility, maxMaxUtility),
                        randomInRange(minSaturationParam, maxSaturationParam));
                }
            }
            
            case NESTED_CES -> generateNestedCES(weights);
            
            case SOFTPLUS_LOSS_AVERSION -> {
                Map<ResourceType, Double> refs = randomReferencePoints(weights);
                yield UtilityFunction.softplusLossAversion(weights, refs,
                    randomInRange(minLambda, maxLambda),
                    randomInRange(minTau, maxTau));
            }
            
            case ASYMMETRIC_LOG_LOSS_AVERSION -> {
                Map<ResourceType, Double> refs = randomReferencePoints(weights);
                yield UtilityFunction.asymmetricLogLossAversion(weights, refs,
                    randomInRange(minLambda, maxLambda),
                    randomInRange(minKappa, maxKappa));
            }
            
            default -> UtilityFunction.linear(weights);
        };
    }

    private UtilityFunction generateBaseUtilityForWrapper(Map<ResourceType, Double> weights) {
        // For wrapper utilities, pick a simple base type
        double r = random.nextDouble();
        if (r < 0.4) {
            return UtilityFunction.linear(weights);
        } else if (r < 0.7) {
            return UtilityFunction.sqrt(weights);
        } else {
            return UtilityFunction.log(weights);
        }
    }

    private UtilityFunction generateNestedCES(Map<ResourceType, Double> baseWeights) {
        // Create 2-3 nests from the available resources
        List<ResourceType> available = new ArrayList<>(baseWeights.keySet());
        Collections.shuffle(available, random);
        
        int numNests = Math.min(2 + random.nextInt(2), (available.size() + 1) / 2);
        List<Map<ResourceType, Double>> nests = new ArrayList<>();
        List<Double> nestRhos = new ArrayList<>();
        List<Double> nestWeights = new ArrayList<>();
        
        int idx = 0;
        for (int n = 0; n < numNests && idx < available.size(); n++) {
            Map<ResourceType, Double> nest = new HashMap<>();
            int nestSize = Math.min(2 + random.nextInt(2), available.size() - idx);
            
            double weightSum = 0;
            for (int i = 0; i < nestSize && idx < available.size(); i++) {
                double w = randomInRange(minWeight, maxWeight);
                nest.put(available.get(idx++), w);
                weightSum += w;
            }
            
            // Normalize nest weights
            for (ResourceType r : nest.keySet()) {
                nest.put(r, nest.get(r) / weightSum);
            }
            
            nests.add(nest);
            nestRhos.add(randomInRange(minRho, maxRho));
            nestWeights.add(randomInRange(0.3, 0.7));
        }
        
        // Normalize nest weights
        double sum = nestWeights.stream().mapToDouble(Double::doubleValue).sum();
        for (int i = 0; i < nestWeights.size(); i++) {
            nestWeights.set(i, nestWeights.get(i) / sum);
        }
        
        double outerRho = randomInRange(minRho, maxRho);
        
        return UtilityFunction.nestedCES(nests, nestRhos, nestWeights, outerRho);
    }

    // ========================================================================
    // Agent Generation
    // ========================================================================

    /**
     * Generate a random agent configuration.
     */
    public GeneratedAgent generateAgent(String idPrefix, int index) {
        String id = idPrefix + "_" + index;
        UtilityFunction utility = generateUtilityFunction();
        
        // Generate requests based on utility weights
        Map<ResourceType, Long> minimums = new HashMap<>();
        Map<ResourceType, Long> ideals = new HashMap<>();
        
        for (ResourceType r : utility.getWeights().keySet()) {
            double weight = utility.getWeight(r);
            // Scale requests by weight
            long min = (long) (5 + random.nextDouble() * 15 * weight);
            long ideal = (long) (min + 20 + random.nextDouble() * 60 * weight);
            minimums.put(r, min);
            ideals.put(r, ideal);
        }
        
        // Random currency balance
        double currency = random.nextDouble() * 200;
        
        return new GeneratedAgent(id, utility, minimums, ideals, currency);
    }

    /**
     * Generate multiple agents with reproducible randomness.
     */
    public List<GeneratedAgent> generateAgents(int count, String idPrefix) {
        List<GeneratedAgent> agents = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            agents.add(generateAgent(idPrefix, i));
        }
        return agents;
    }

    /**
     * Generate agents with a mix of specific utility types.
     */
    public List<GeneratedAgent> generateMixedAgents(
            int count, 
            String idPrefix,
            Map<UtilityFunction.Type, Integer> typeCounts) {
        
        List<GeneratedAgent> agents = new ArrayList<>();
        int index = 0;
        
        for (var entry : typeCounts.entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) {
                String id = idPrefix + "_" + index++;
                UtilityFunction utility = generateUtilityFunction(entry.getKey());
                
                Map<ResourceType, Long> minimums = new HashMap<>();
                Map<ResourceType, Long> ideals = new HashMap<>();
                
                for (ResourceType r : utility.getWeights().keySet()) {
                    double weight = utility.getWeight(r);
                    long min = (long) (5 + random.nextDouble() * 15 * weight);
                    long ideal = (long) (min + 20 + random.nextDouble() * 60 * weight);
                    minimums.put(r, min);
                    ideals.put(r, ideal);
                }
                
                double currency = random.nextDouble() * 200;
                agents.add(new GeneratedAgent(id, utility, minimums, ideals, currency));
            }
        }
        
        // Shuffle to mix types
        Collections.shuffle(agents, random);
        return agents;
    }

    // ========================================================================
    // Generated Agent Record
    // ========================================================================

    /**
     * Container for generated agent configuration.
     */
    public record GeneratedAgent(
            String id,
            UtilityFunction utility,
            Map<ResourceType, Long> minimums,
            Map<ResourceType, Long> ideals,
            double currency) {
        
        /**
         * Get a summary description of this agent.
         */
        public String getSummary() {
            return String.format("%s: %s, resources=%d, currency=%.1f",
                id, utility.getType(), utility.getWeights().size(), currency);
        }

        /**
         * Convert to an Agent instance.
         */
        public Agent toAgent() {
            Agent agent = new Agent(id, "Generated_" + utility.getType(),
                utility.getWeights(), (long) currency);
            for (var entry : minimums.entrySet()) {
                agent.setRequest(entry.getKey(), entry.getValue(), 
                    ideals.getOrDefault(entry.getKey(), entry.getValue()));
            }
            return agent;
        }
    }

    // ========================================================================
    // Statistics
    // ========================================================================

    /**
     * Generate a distribution summary for a batch of agents.
     */
    public static Map<UtilityFunction.Type, Integer> countByType(List<GeneratedAgent> agents) {
        Map<UtilityFunction.Type, Integer> counts = new EnumMap<>(UtilityFunction.Type.class);
        for (GeneratedAgent agent : agents) {
            counts.merge(agent.utility().getType(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Print a summary of generated agents.
     */
    public static void printSummary(List<GeneratedAgent> agents) {
        System.out.println("Generated " + agents.size() + " agents:");
        Map<UtilityFunction.Type, Integer> counts = countByType(agents);
        for (var entry : counts.entrySet()) {
            System.out.printf("  %s: %d (%.1f%%)\n",
                entry.getKey(), entry.getValue(),
                100.0 * entry.getValue() / agents.size());
        }
    }

    // ========================================================================
    // Main (for testing)
    // ========================================================================

    public static void main(String[] args) {
        System.out.println("Agent Generator Test");
        System.out.println("====================\n");
        
        // Generate with default settings
        AgentGenerator generator = new AgentGenerator(42);
        
        System.out.println("Generating 100 random agents...\n");
        List<GeneratedAgent> agents = generator.generateAgents(100, "TEST");
        printSummary(agents);
        
        System.out.println("\nSample agents:");
        for (int i = 0; i < 5; i++) {
            System.out.println("  " + agents.get(i).getSummary());
        }
        
        // Verify reproducibility
        System.out.println("\nVerifying reproducibility...");
        generator.reset();
        List<GeneratedAgent> agents2 = generator.generateAgents(100, "TEST");
        
        boolean identical = true;
        for (int i = 0; i < agents.size(); i++) {
            if (!agents.get(i).id().equals(agents2.get(i).id()) ||
                agents.get(i).utility().getType() != agents2.get(i).utility().getType()) {
                identical = false;
                break;
            }
        }
        System.out.println("  Reproducibility: " + (identical ? "✓ PASS" : "✗ FAIL"));
        
        // Generate with mixed types
        System.out.println("\nGenerating mixed agents with specific type counts...");
        Map<UtilityFunction.Type, Integer> typeCounts = new LinkedHashMap<>();
        typeCounts.put(UtilityFunction.Type.LINEAR, 10);
        typeCounts.put(UtilityFunction.Type.COBB_DOUGLAS, 10);
        typeCounts.put(UtilityFunction.Type.SOFTPLUS_LOSS_AVERSION, 10);
        typeCounts.put(UtilityFunction.Type.THRESHOLD, 10);
        
        List<GeneratedAgent> mixedAgents = generator.generateMixedAgents(40, "MIXED", typeCounts);
        printSummary(mixedAgents);
        
        System.out.println("\n✓ Agent generator working correctly");
    }
}
