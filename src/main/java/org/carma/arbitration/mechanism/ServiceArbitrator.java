package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Arbitrates service allocation among competing agents.
 * 
 * The ServiceArbitrator extends the resource arbitration framework to handle
 * AI service allocation by:
 * 1. Treating service slots as resources with capacity constraints
 * 2. Mapping service requirements to underlying resources (compute, memory, etc.)
 * 3. Enabling joint optimization across both services and resources
 * 4. Supporting service compositions as atomic allocation units
 */
public class ServiceArbitrator {

    private final PriorityEconomy economy;
    private final ProportionalFairnessArbitrator resourceArbitrator;
    private final ServiceRegistry registry;
    private final CompositionMonitor monitor;
    private final boolean enableJointOptimization;

    /**
     * Result of service arbitration.
     */
    public static class ServiceAllocationResult {
        private final Map<String, Map<ServiceType, Integer>> allocations;
        private final Map<String, Map<String, String>> compositionAssignments;
        private final Map<String, BigDecimal> currencyBurned;
        private final double objectiveValue;
        private final boolean feasible;
        private final String message;
        private final long computationTimeMs;

        public ServiceAllocationResult(
                Map<String, Map<ServiceType, Integer>> allocations,
                Map<String, Map<String, String>> compositionAssignments,
                Map<String, BigDecimal> currencyBurned,
                double objectiveValue,
                boolean feasible,
                String message,
                long computationTimeMs) {
            this.allocations = allocations;
            this.compositionAssignments = compositionAssignments;
            this.currencyBurned = currencyBurned;
            this.objectiveValue = objectiveValue;
            this.feasible = feasible;
            this.message = message;
            this.computationTimeMs = computationTimeMs;
        }

        public Map<ServiceType, Integer> getAllocations(String agentId) {
            return allocations.getOrDefault(agentId, Collections.emptyMap());
        }

        public int getAllocation(String agentId, ServiceType type) {
            return getAllocations(agentId).getOrDefault(type, 0);
        }

        public Map<String, String> getCompositionAssignments(String agentId) {
            return compositionAssignments.getOrDefault(agentId, Collections.emptyMap());
        }

        public Map<String, Map<ServiceType, Integer>> getAllAllocations() {
            return Collections.unmodifiableMap(allocations);
        }

        public double getObjectiveValue() { return objectiveValue; }
        public boolean isFeasible() { return feasible; }
        public String getMessage() { return message; }
        public long getComputationTimeMs() { return computationTimeMs; }

        @Override
        public String toString() {
            return String.format("ServiceAllocationResult[feasible=%s, objective=%.4f, time=%dms]",
                feasible, objectiveValue, computationTimeMs);
        }
    }

    /**
     * Request for service allocation from an agent.
     */
    public static class ServiceRequest {
        private final String agentId;
        private final Map<ServiceType, Integer> serviceRequests;
        private final List<String> compositionIds;
        private final BigDecimal currencyCommitment;
        private final Map<ServiceType, Double> servicePreferences;

        public ServiceRequest(String agentId, Map<ServiceType, Integer> serviceRequests,
                             List<String> compositionIds, BigDecimal currencyCommitment,
                             Map<ServiceType, Double> servicePreferences) {
            this.agentId = agentId;
            this.serviceRequests = new HashMap<>(serviceRequests);
            this.compositionIds = compositionIds != null ? new ArrayList<>(compositionIds) : new ArrayList<>();
            this.currencyCommitment = currencyCommitment;
            this.servicePreferences = servicePreferences != null ? 
                new HashMap<>(servicePreferences) : new HashMap<>();
        }

        public String getAgentId() { return agentId; }
        public Map<ServiceType, Integer> getServiceRequests() { return serviceRequests; }
        public List<String> getCompositionIds() { return compositionIds; }
        public BigDecimal getCurrencyCommitment() { return currencyCommitment; }
        public Map<ServiceType, Double> getServicePreferences() { return servicePreferences; }

        /**
         * Builder for ServiceRequest.
         */
        public static class Builder {
            private final String agentId;
            private Map<ServiceType, Integer> serviceRequests = new HashMap<>();
            private List<String> compositionIds = new ArrayList<>();
            private BigDecimal currencyCommitment = BigDecimal.ZERO;
            private Map<ServiceType, Double> servicePreferences = new HashMap<>();

            public Builder(String agentId) {
                this.agentId = agentId;
            }

            public Builder requestService(ServiceType type, int slots) {
                serviceRequests.put(type, slots);
                return this;
            }

            public Builder requestComposition(String compositionId) {
                compositionIds.add(compositionId);
                return this;
            }

            public Builder currencyCommitment(BigDecimal amount) {
                this.currencyCommitment = amount;
                return this;
            }

            public Builder preference(ServiceType type, double weight) {
                servicePreferences.put(type, weight);
                return this;
            }

            public ServiceRequest build() {
                return new ServiceRequest(agentId, serviceRequests, compositionIds,
                    currencyCommitment, servicePreferences);
            }
        }
    }

    public ServiceArbitrator(PriorityEconomy economy, ServiceRegistry registry) {
        this(economy, registry, true);
    }

    public ServiceArbitrator(PriorityEconomy economy, ServiceRegistry registry, 
                            boolean enableJointOptimization) {
        this.economy = economy;
        this.resourceArbitrator = new ProportionalFairnessArbitrator(economy);
        this.registry = registry;
        this.monitor = new CompositionMonitor(registry);
        this.enableJointOptimization = enableJointOptimization;
    }

    // ========================================================================
    // Main Arbitration Methods
    // ========================================================================

    /**
     * Arbitrate service requests from multiple agents.
     */
    public ServiceAllocationResult arbitrate(List<ServiceRequest> requests) {
        long startTime = System.currentTimeMillis();

        if (requests.isEmpty()) {
            return new ServiceAllocationResult(
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                0.0, true, "No requests", System.currentTimeMillis() - startTime);
        }

        // Collect all requested service types
        Set<ServiceType> requestedTypes = new HashSet<>();
        for (ServiceRequest req : requests) {
            requestedTypes.addAll(req.getServiceRequests().keySet());
            // Add types from compositions
            for (String compId : req.getCompositionIds()) {
                registry.getComposition(compId).ifPresent(comp -> {
                    for (ServiceComposition.CompositionNode node : comp.getNodes().values()) {
                        requestedTypes.add(node.getServiceType());
                    }
                });
            }
        }

        // Build capacity map for each service type
        Map<ServiceType, Long> serviceCapacities = new HashMap<>();
        for (ServiceType type : requestedTypes) {
            serviceCapacities.put(type, (long) registry.getAvailableCapacity(type));
        }

        // Build agent models for arbitration
        List<Agent> agents = new ArrayList<>();
        Map<String, ServiceRequest> requestMap = new HashMap<>();

        for (ServiceRequest req : requests) {
            requestMap.put(req.getAgentId(), req);
            
            // Create agent with service preferences
            Map<ResourceType, Double> resourcePrefs = new HashMap<>();
            // Convert service preferences to a form compatible with our system
            // We'll use a virtual mapping for now
            for (var entry : req.getServicePreferences().entrySet()) {
                // Map to first available resource type as placeholder
                resourcePrefs.put(ResourceType.API_CREDITS, entry.getValue());
            }
            if (resourcePrefs.isEmpty()) {
                resourcePrefs.put(ResourceType.API_CREDITS, 1.0);
            }

            Agent agent = new Agent(req.getAgentId(), "Service Agent " + req.getAgentId(),
                resourcePrefs, 100);
            agents.add(agent);
        }

        // Arbitrate each service type
        Map<String, Map<ServiceType, Integer>> allocations = new HashMap<>();
        Map<String, Map<String, String>> compositionAssignments = new HashMap<>();
        Map<String, BigDecimal> currencyBurned = new HashMap<>();
        double totalObjective = 0;
        boolean allFeasible = true;
        StringBuilder messages = new StringBuilder();

        for (ServiceRequest req : requests) {
            allocations.put(req.getAgentId(), new HashMap<>());
            compositionAssignments.put(req.getAgentId(), new HashMap<>());
            currencyBurned.put(req.getAgentId(), req.getCurrencyCommitment());
        }

        // Arbitrate individual service type requests
        for (ServiceType type : requestedTypes) {
            ServiceAllocationResult typeResult = arbitrateServiceType(type, requests, serviceCapacities);
            
            if (!typeResult.isFeasible()) {
                allFeasible = false;
                messages.append(type.name()).append(": ").append(typeResult.getMessage()).append("; ");
            }

            totalObjective += typeResult.getObjectiveValue();

            // Merge allocations
            for (String agentId : typeResult.getAllAllocations().keySet()) {
                int slots = typeResult.getAllocation(agentId, type);
                if (slots > 0) {
                    allocations.get(agentId).put(type, slots);
                }
            }
        }

        // Handle composition requests
        for (ServiceRequest req : requests) {
            for (String compId : req.getCompositionIds()) {
                Optional<ServiceComposition> compOpt = registry.getComposition(compId);
                if (compOpt.isPresent()) {
                    ServiceComposition comp = compOpt.get();
                    Optional<Map<String, String>> assignments = registry.reserveCapacityFor(comp);
                    
                    if (assignments.isPresent()) {
                        compositionAssignments.get(req.getAgentId()).putAll(assignments.get());
                        monitor.startComposition(req.getAgentId(), comp);
                    } else {
                        allFeasible = false;
                        messages.append("Composition ").append(compId).append(" infeasible; ");
                    }
                }
            }
        }

        return new ServiceAllocationResult(
            allocations,
            compositionAssignments,
            currencyBurned,
            totalObjective,
            allFeasible,
            allFeasible ? "Success" : messages.toString(),
            System.currentTimeMillis() - startTime
        );
    }

    /**
     * Arbitrate a single service type among competing requests.
     */
    private ServiceAllocationResult arbitrateServiceType(
            ServiceType type,
            List<ServiceRequest> requests,
            Map<ServiceType, Long> capacities) {

        // Filter to requests that want this service type
        List<ServiceRequest> competing = requests.stream()
            .filter(r -> r.getServiceRequests().containsKey(type) && 
                        r.getServiceRequests().get(type) > 0)
            .collect(Collectors.toList());

        if (competing.isEmpty()) {
            return new ServiceAllocationResult(
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                0.0, true, "No demand", 0);
        }

        long capacity = capacities.getOrDefault(type, 0L);
        if (capacity == 0) {
            return new ServiceAllocationResult(
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                0.0, false, "No capacity for " + type, 0);
        }

        // Build agents for this contention
        List<Agent> typeAgents = new ArrayList<>();
        Map<String, BigDecimal> burns = new HashMap<>();

        for (ServiceRequest req : competing) {
            int requested = req.getServiceRequests().get(type);
            Agent agent = new Agent(req.getAgentId(), req.getAgentId(),
                Map.of(ResourceType.API_CREDITS, 1.0), 100);
            // Use slot count as min/ideal
            agent.setRequest(ResourceType.API_CREDITS, 1, requested);
            typeAgents.add(agent);
            burns.put(req.getAgentId(), req.getCurrencyCommitment());
        }

        // Create contention and arbitrate
        Contention contention = new Contention(ResourceType.API_CREDITS, typeAgents, capacity);
        AllocationResult result = resourceArbitrator.arbitrate(contention, burns);

        // Convert to service allocations
        Map<String, Map<ServiceType, Integer>> allocations = new HashMap<>();
        for (ServiceRequest req : competing) {
            long slots = result.getAllocation(req.getAgentId());
            Map<ServiceType, Integer> agentAllocs = new HashMap<>();
            agentAllocs.put(type, (int) slots);
            allocations.put(req.getAgentId(), agentAllocs);
        }

        return new ServiceAllocationResult(
            allocations,
            Collections.emptyMap(),
            burns,
            result.getObjectiveValue(),
            result.isFeasible(),
            result.getMessage(),
            result.getComputationTimeMs()
        );
    }

    // ========================================================================
    // Joint Resource-Service Arbitration
    // ========================================================================

    /**
     * Perform joint arbitration considering both services and underlying resources.
     * This enables cross-service trades while respecting resource constraints.
     */
    public ServiceAllocationResult arbitrateJoint(
            List<ServiceRequest> serviceRequests,
            List<Agent> resourceAgents,
            ResourcePool resourcePool) {

        long startTime = System.currentTimeMillis();

        if (!enableJointOptimization) {
            // Fall back to sequential
            return arbitrate(serviceRequests);
        }

        // Calculate resource requirements from service requests
        Map<String, Map<ResourceType, Long>> serviceResourceNeeds = new HashMap<>();
        
        for (ServiceRequest req : serviceRequests) {
            Map<ResourceType, Long> needs = new HashMap<>();
            
            for (var entry : req.getServiceRequests().entrySet()) {
                ServiceType svcType = entry.getKey();
                int slots = entry.getValue();
                
                Map<ResourceType, Long> perSlot = svcType.getDefaultResourceRequirements();
                for (var resEntry : perSlot.entrySet()) {
                    needs.merge(resEntry.getKey(), resEntry.getValue() * slots, Long::sum);
                }
            }
            
            serviceResourceNeeds.put(req.getAgentId(), needs);
        }

        // Combine with direct resource requests
        Map<String, BigDecimal> allBurns = new HashMap<>();
        for (ServiceRequest req : serviceRequests) {
            allBurns.put(req.getAgentId(), req.getCurrencyCommitment());
        }
        for (Agent agent : resourceAgents) {
            allBurns.putIfAbsent(agent.getId(), BigDecimal.ZERO);
        }

        // Create combined agent list
        List<Agent> combinedAgents = new ArrayList<>(resourceAgents);
        Set<String> existingIds = resourceAgents.stream()
            .map(Agent::getId).collect(Collectors.toSet());

        for (ServiceRequest req : serviceRequests) {
            if (!existingIds.contains(req.getAgentId())) {
                Map<ResourceType, Long> needs = serviceResourceNeeds.get(req.getAgentId());
                Map<ResourceType, Double> prefs = new HashMap<>();
                
                // Derive preferences from resource needs
                long totalNeed = needs.values().stream().mapToLong(Long::longValue).sum();
                if (totalNeed > 0) {
                    for (var entry : needs.entrySet()) {
                        prefs.put(entry.getKey(), (double) entry.getValue() / totalNeed);
                    }
                }
                
                Agent agent = new Agent(req.getAgentId(), "Service Agent",
                    prefs.isEmpty() ? Map.of(ResourceType.COMPUTE, 1.0) : prefs, 100);
                
                for (var entry : needs.entrySet()) {
                    agent.setRequest(entry.getKey(), entry.getValue() / 2, entry.getValue());
                }
                
                combinedAgents.add(agent);
            }
        }

        // Use joint arbitrator for resources
        JointArbitrator jointArbitrator = new SequentialJointArbitrator(economy);
        JointArbitrator.JointAllocationResult resourceResult = 
            jointArbitrator.arbitrate(combinedAgents, resourcePool, allBurns);

        // Map resource allocations back to service slots
        Map<String, Map<ServiceType, Integer>> serviceAllocations = new HashMap<>();
        
        for (ServiceRequest req : serviceRequests) {
            Map<ResourceType, Long> allocated = resourceResult.getAllocations(req.getAgentId());
            Map<ServiceType, Integer> svcAllocs = new HashMap<>();
            
            for (var entry : req.getServiceRequests().entrySet()) {
                ServiceType svcType = entry.getKey();
                int requested = entry.getValue();
                
                // Calculate how many slots we can fulfill based on resources
                Map<ResourceType, Long> perSlot = svcType.getDefaultResourceRequirements();
                int maxSlots = Integer.MAX_VALUE;
                
                for (var resEntry : perSlot.entrySet()) {
                    long available = allocated.getOrDefault(resEntry.getKey(), 0L);
                    long needed = resEntry.getValue();
                    if (needed > 0) {
                        maxSlots = Math.min(maxSlots, (int) (available / needed));
                    }
                }
                
                svcAllocs.put(svcType, Math.min(requested, maxSlots == Integer.MAX_VALUE ? 0 : maxSlots));
            }
            
            serviceAllocations.put(req.getAgentId(), svcAllocs);
        }

        return new ServiceAllocationResult(
            serviceAllocations,
            Collections.emptyMap(),
            allBurns,
            resourceResult.getObjectiveValue(),
            resourceResult.isFeasible(),
            resourceResult.getMessage(),
            System.currentTimeMillis() - startTime
        );
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if requests can be satisfied.
     */
    public boolean canSatisfy(List<ServiceRequest> requests) {
        for (ServiceRequest req : requests) {
            for (var entry : req.getServiceRequests().entrySet()) {
                if (registry.getAvailableCapacity(entry.getKey()) < entry.getValue()) {
                    return false;
                }
            }
            for (String compId : req.getCompositionIds()) {
                Optional<ServiceComposition> comp = registry.getComposition(compId);
                if (comp.isEmpty() || !registry.hasCapacityFor(comp.get())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Estimate total resource requirements for requests.
     */
    public Map<ResourceType, Long> estimateResourceRequirements(List<ServiceRequest> requests) {
        Map<ResourceType, Long> total = new HashMap<>();
        
        for (ServiceRequest req : requests) {
            for (var entry : req.getServiceRequests().entrySet()) {
                Map<ResourceType, Long> perSlot = entry.getKey().getDefaultResourceRequirements();
                int slots = entry.getValue();
                
                for (var resEntry : perSlot.entrySet()) {
                    total.merge(resEntry.getKey(), resEntry.getValue() * slots, Long::sum);
                }
            }
        }
        
        return total;
    }

    /**
     * Get the composition monitor.
     */
    public CompositionMonitor getMonitor() {
        return monitor;
    }

    /**
     * Get the service registry.
     */
    public ServiceRegistry getRegistry() {
        return registry;
    }

    @Override
    public String toString() {
        return String.format("ServiceArbitrator[registry=%s, joint=%s]",
            registry, enableJointOptimization);
    }
}
