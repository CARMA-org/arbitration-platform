package org.carma.arbitration.model;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Registry/catalog of available AI services.
 * 
 * The ServiceRegistry maintains:
 * - All registered service instances
 * - Service discovery by type, provider, or capability
 * - Capacity tracking across services
 * - Health status monitoring
 */
public class ServiceRegistry {

    private final Map<String, AIService> services;
    private final Map<ServiceType, Set<String>> servicesByType;
    private final Map<String, Set<String>> servicesByProvider;
    private final Map<String, ServiceComposition> compositions;
    private final List<RegistryListener> listeners;

    /**
     * Listener for registry events.
     */
    public interface RegistryListener {
        default void onServiceRegistered(AIService service) {}
        default void onServiceDeregistered(AIService service) {}
        default void onServiceStatusChanged(AIService service, boolean available) {}
        default void onCompositionRegistered(ServiceComposition composition) {}
    }

    /**
     * Query builder for finding services.
     */
    public class ServiceQuery {
        private ServiceType type;
        private String provider;
        private boolean availableOnly = false;
        private int minCapacity = 0;
        private Set<ServiceType.DataType> requiredInputs;
        private Set<ServiceType.DataType> requiredOutputs;

        public ServiceQuery ofType(ServiceType type) {
            this.type = type;
            return this;
        }

        public ServiceQuery fromProvider(String provider) {
            this.provider = provider;
            return this;
        }

        public ServiceQuery availableOnly() {
            this.availableOnly = true;
            return this;
        }

        public ServiceQuery withMinCapacity(int capacity) {
            this.minCapacity = capacity;
            return this;
        }

        public ServiceQuery acceptingInput(ServiceType.DataType... types) {
            this.requiredInputs = Set.of(types);
            return this;
        }

        public ServiceQuery producingOutput(ServiceType.DataType... types) {
            this.requiredOutputs = Set.of(types);
            return this;
        }

        public List<AIService> find() {
            return services.values().stream()
                .filter(s -> type == null || s.getType() == type)
                .filter(s -> provider == null || s.getProvider().equals(provider))
                .filter(s -> !availableOnly || s.isAvailable())
                .filter(s -> s.getAvailableCapacity() >= minCapacity)
                .filter(s -> requiredInputs == null || 
                    s.getInputTypes().containsAll(requiredInputs))
                .filter(s -> requiredOutputs == null || 
                    s.getOutputTypes().containsAll(requiredOutputs))
                .collect(Collectors.toList());
        }

        public Optional<AIService> findFirst() {
            return find().stream().findFirst();
        }

        public Optional<AIService> findBestByCapacity() {
            return find().stream()
                .max(Comparator.comparingInt(AIService::getAvailableCapacity));
        }

        public Optional<AIService> findBestByLatency() {
            return find().stream()
                .min(Comparator.comparingInt(AIService::estimateLatencyMs));
        }
    }

    public ServiceRegistry() {
        this.services = new ConcurrentHashMap<>();
        this.servicesByType = new ConcurrentHashMap<>();
        this.servicesByProvider = new ConcurrentHashMap<>();
        this.compositions = new ConcurrentHashMap<>();
        this.listeners = new ArrayList<>();
    }

    // ========================================================================
    // Service Registration
    // ========================================================================

    /**
     * Register a new service.
     */
    public void register(AIService service) {
        services.put(service.getServiceId(), service);
        
        servicesByType.computeIfAbsent(service.getType(), k -> ConcurrentHashMap.newKeySet())
            .add(service.getServiceId());
        
        servicesByProvider.computeIfAbsent(service.getProvider(), k -> ConcurrentHashMap.newKeySet())
            .add(service.getServiceId());
        
        notifyListeners(l -> l.onServiceRegistered(service));
    }

    /**
     * Register multiple services.
     */
    public void registerAll(AIService... services) {
        for (AIService service : services) {
            register(service);
        }
    }

    /**
     * Register multiple services from a collection.
     */
    public void registerAll(Collection<AIService> services) {
        for (AIService service : services) {
            register(service);
        }
    }

    /**
     * Deregister a service.
     */
    public void deregister(String serviceId) {
        AIService service = services.remove(serviceId);
        if (service != null) {
            Set<String> typeSet = servicesByType.get(service.getType());
            if (typeSet != null) typeSet.remove(serviceId);
            
            Set<String> providerSet = servicesByProvider.get(service.getProvider());
            if (providerSet != null) providerSet.remove(serviceId);
            
            notifyListeners(l -> l.onServiceDeregistered(service));
        }
    }

    /**
     * Register a composition.
     */
    public void registerComposition(ServiceComposition composition) {
        ServiceComposition.ValidationResult result = composition.validate();
        if (!result.isValid()) {
            throw new IllegalArgumentException("Invalid composition: " + result.getErrors());
        }
        compositions.put(composition.getCompositionId(), composition);
        notifyListeners(l -> l.onCompositionRegistered(composition));
    }

    // ========================================================================
    // Service Lookup
    // ========================================================================

    /**
     * Get a service by ID.
     */
    public Optional<AIService> get(String serviceId) {
        return Optional.ofNullable(services.get(serviceId));
    }

    /**
     * Get all services.
     */
    public Collection<AIService> getAll() {
        return Collections.unmodifiableCollection(services.values());
    }

    /**
     * Get services by type.
     */
    public List<AIService> getByType(ServiceType type) {
        Set<String> ids = servicesByType.get(type);
        if (ids == null) return Collections.emptyList();
        return ids.stream()
            .map(services::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    /**
     * Get available services by type.
     */
    public List<AIService> getAvailableByType(ServiceType type) {
        return getByType(type).stream()
            .filter(AIService::isAvailable)
            .collect(Collectors.toList());
    }

    /**
     * Get services by provider.
     */
    public List<AIService> getByProvider(String provider) {
        Set<String> ids = servicesByProvider.get(provider);
        if (ids == null) return Collections.emptyList();
        return ids.stream()
            .map(services::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    /**
     * Start a service query.
     */
    public ServiceQuery query() {
        return new ServiceQuery();
    }

    /**
     * Find services matching a predicate.
     */
    public List<AIService> findAll(Predicate<AIService> predicate) {
        return services.values().stream()
            .filter(predicate)
            .collect(Collectors.toList());
    }

    /**
     * Get a composition by ID.
     */
    public Optional<ServiceComposition> getComposition(String compositionId) {
        return Optional.ofNullable(compositions.get(compositionId));
    }

    /**
     * Get all compositions.
     */
    public Collection<ServiceComposition> getAllCompositions() {
        return Collections.unmodifiableCollection(compositions.values());
    }

    // ========================================================================
    // Capacity Management
    // ========================================================================

    /**
     * Get total capacity for a service type.
     */
    public int getTotalCapacity(ServiceType type) {
        return getByType(type).stream()
            .mapToInt(AIService::getMaxCapacity)
            .sum();
    }

    /**
     * Get available capacity for a service type.
     */
    public int getAvailableCapacity(ServiceType type) {
        return getByType(type).stream()
            .filter(AIService::isAvailable)
            .mapToInt(AIService::getAvailableCapacity)
            .sum();
    }

    /**
     * Get utilization for a service type.
     */
    public double getUtilization(ServiceType type) {
        int total = getTotalCapacity(type);
        if (total == 0) return 0.0;
        int available = getAvailableCapacity(type);
        return 1.0 - ((double) available / total);
    }

    /**
     * Check if enough capacity is available for a composition.
     */
    public boolean hasCapacityFor(ServiceComposition composition) {
        for (ServiceComposition.CompositionNode node : composition.getNodes().values()) {
            int required = node.getParallelism();
            int available = getAvailableCapacity(node.getServiceType());
            if (available < required) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reserve capacity for a composition.
     * @return Map of nodeId to assigned serviceId, or empty if reservation failed
     */
    public Optional<Map<String, String>> reserveCapacityFor(ServiceComposition composition) {
        Map<String, String> assignments = new HashMap<>();
        List<AIService> reserved = new ArrayList<>();

        try {
            for (ServiceComposition.CompositionNode node : composition.getNodes().values()) {
                Optional<AIService> service = query()
                    .ofType(node.getServiceType())
                    .availableOnly()
                    .withMinCapacity(node.getParallelism())
                    .findBestByCapacity();

                if (service.isEmpty()) {
                    // Rollback reservations
                    for (AIService s : reserved) {
                        s.releaseCapacity(1);
                    }
                    return Optional.empty();
                }

                AIService s = service.get();
                if (!s.reserveCapacity(node.getParallelism())) {
                    // Rollback reservations
                    for (AIService rs : reserved) {
                        rs.releaseCapacity(1);
                    }
                    return Optional.empty();
                }

                assignments.put(node.getNodeId(), s.getServiceId());
                node.setAssignedServiceId(s.getServiceId());
                reserved.add(s);
            }

            return Optional.of(assignments);

        } catch (Exception e) {
            // Rollback on any error
            for (AIService s : reserved) {
                s.releaseCapacity(1);
            }
            return Optional.empty();
        }
    }

    /**
     * Release capacity for a composition.
     */
    public void releaseCapacityFor(ServiceComposition composition) {
        for (ServiceComposition.CompositionNode node : composition.getNodes().values()) {
            String serviceId = node.getAssignedServiceId();
            if (serviceId != null) {
                get(serviceId).ifPresent(s -> s.releaseCapacity(node.getParallelism()));
            }
        }
    }

    // ========================================================================
    // Health Management
    // ========================================================================

    /**
     * Set service availability.
     */
    public void setServiceAvailable(String serviceId, boolean available) {
        get(serviceId).ifPresent(service -> {
            service.setAvailable(available);
            notifyListeners(l -> l.onServiceStatusChanged(service, available));
        });
    }

    /**
     * Get all unavailable services.
     */
    public List<AIService> getUnavailableServices() {
        return services.values().stream()
            .filter(s -> !s.isAvailable())
            .collect(Collectors.toList());
    }

    /**
     * Reset all service loads (for testing/simulation reset).
     */
    public void resetAllLoads() {
        services.values().forEach(AIService::resetLoad);
    }

    // ========================================================================
    // Statistics
    // ========================================================================

    /**
     * Get registry statistics.
     */
    public RegistryStats getStats() {
        int totalServices = services.size();
        int availableServices = (int) services.values().stream()
            .filter(AIService::isAvailable).count();
        int totalCapacity = services.values().stream()
            .mapToInt(AIService::getMaxCapacity).sum();
        int usedCapacity = services.values().stream()
            .mapToInt(AIService::getCurrentLoad).sum();
        int totalCompositions = compositions.size();
        
        Map<ServiceType, Integer> serviceCountByType = new HashMap<>();
        for (ServiceType type : ServiceType.values()) {
            serviceCountByType.put(type, getByType(type).size());
        }

        return new RegistryStats(totalServices, availableServices, totalCapacity, 
            usedCapacity, totalCompositions, serviceCountByType);
    }

    public static class RegistryStats {
        public final int totalServices;
        public final int availableServices;
        public final int totalCapacity;
        public final int usedCapacity;
        public final int totalCompositions;
        public final Map<ServiceType, Integer> serviceCountByType;

        public RegistryStats(int totalServices, int availableServices, int totalCapacity,
                            int usedCapacity, int totalCompositions, 
                            Map<ServiceType, Integer> serviceCountByType) {
            this.totalServices = totalServices;
            this.availableServices = availableServices;
            this.totalCapacity = totalCapacity;
            this.usedCapacity = usedCapacity;
            this.totalCompositions = totalCompositions;
            this.serviceCountByType = serviceCountByType;
        }

        public double getOverallUtilization() {
            return totalCapacity > 0 ? (double) usedCapacity / totalCapacity : 0.0;
        }

        @Override
        public String toString() {
            return String.format(
                "RegistryStats[services=%d/%d available, capacity=%d/%d used (%.1f%%), compositions=%d]",
                availableServices, totalServices, usedCapacity, totalCapacity,
                getOverallUtilization() * 100, totalCompositions);
        }
    }

    // ========================================================================
    // Listeners
    // ========================================================================

    public void addListener(RegistryListener listener) {
        listeners.add(listener);
    }

    public void removeListener(RegistryListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(java.util.function.Consumer<RegistryListener> action) {
        for (RegistryListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                System.err.println("Error in registry listener: " + e.getMessage());
            }
        }
    }

    // ========================================================================
    // Factory Methods for Common Configurations
    // ========================================================================

    /**
     * Create a registry with default services for all types.
     */
    public static ServiceRegistry withDefaults() {
        ServiceRegistry registry = new ServiceRegistry();
        
        for (ServiceType type : ServiceType.values()) {
            AIService service = new AIService.Builder(type.name().toLowerCase() + "_default", type)
                .provider("default")
                .build();
            registry.register(service);
        }
        
        return registry;
    }

    /**
     * Create a registry with scaled services for testing.
     */
    public static ServiceRegistry forTesting(int servicesPerType) {
        ServiceRegistry registry = new ServiceRegistry();
        
        for (ServiceType type : ServiceType.values()) {
            for (int i = 0; i < servicesPerType; i++) {
                AIService service = new AIService.Builder(
                    type.name().toLowerCase() + "_test_" + i, type)
                    .provider("test")
                    .maxCapacity(type.getDefaultCapacity() * 2)
                    .build();
                registry.register(service);
            }
        }
        
        return registry;
    }

    // ========================================================================
    // Object Methods
    // ========================================================================

    public int size() {
        return services.size();
    }

    public boolean isEmpty() {
        return services.isEmpty();
    }

    public void clear() {
        services.clear();
        servicesByType.clear();
        servicesByProvider.clear();
        compositions.clear();
    }

    @Override
    public String toString() {
        return String.format("ServiceRegistry[%d services, %d compositions]",
            services.size(), compositions.size());
    }

    /**
     * Generate detailed registry report.
     */
    public String toDetailedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ServiceRegistry Report\n");
        sb.append("=".repeat(60)).append("\n");
        sb.append(getStats()).append("\n\n");
        
        sb.append("Services by Type:\n");
        for (ServiceType type : ServiceType.values()) {
            List<AIService> typeServices = getByType(type);
            if (!typeServices.isEmpty()) {
                sb.append("  ").append(type.getDisplayName()).append(":\n");
                for (AIService service : typeServices) {
                    sb.append("    - ").append(service).append("\n");
                }
            }
        }
        
        if (!compositions.isEmpty()) {
            sb.append("\nCompositions:\n");
            for (ServiceComposition comp : compositions.values()) {
                sb.append("  - ").append(comp).append("\n");
            }
        }
        
        return sb.toString();
    }
}
