package org.carma.arbitration.model;

import java.util.*;

/**
 * Represents an instance of an AI service available for agent composition.
 * 
 * Each AIService wraps a ServiceType with:
 * - Custom capacity limits
 * - Scaled resource requirements
 * - Runtime state (current load, availability)
 * - Quality of Service (QoS) parameters
 */
public class AIService {

    private final String serviceId;
    private final ServiceType type;
    private final String provider;
    private final Map<ResourceType, Long> resourceRequirements;
    private final int maxCapacity;
    private int currentLoad;
    private boolean available;
    private final QoSParameters qos;
    private final Map<String, Object> metadata;

    /**
     * Quality of Service parameters for the service.
     */
    public static class QoSParameters {
        private final int maxLatencyMs;
        private final double targetAvailability;
        private final int maxRetries;
        private final int timeoutMs;
        private final int rateLimitPerMinute;

        public QoSParameters(int maxLatencyMs, double targetAvailability, 
                            int maxRetries, int timeoutMs, int rateLimitPerMinute) {
            this.maxLatencyMs = maxLatencyMs;
            this.targetAvailability = targetAvailability;
            this.maxRetries = maxRetries;
            this.timeoutMs = timeoutMs;
            this.rateLimitPerMinute = rateLimitPerMinute;
        }

        public static QoSParameters defaults() {
            return new QoSParameters(5000, 0.99, 3, 30000, 1000);
        }

        public static QoSParameters lowLatency() {
            return new QoSParameters(1000, 0.999, 2, 5000, 500);
        }

        public static QoSParameters highThroughput() {
            return new QoSParameters(10000, 0.95, 5, 60000, 5000);
        }

        public int getMaxLatencyMs() { return maxLatencyMs; }
        public double getTargetAvailability() { return targetAvailability; }
        public int getMaxRetries() { return maxRetries; }
        public int getTimeoutMs() { return timeoutMs; }
        public int getRateLimitPerMinute() { return rateLimitPerMinute; }

        @Override
        public String toString() {
            return String.format("QoS[latency≤%dms, avail≥%.1f%%, retries=%d]",
                maxLatencyMs, targetAvailability * 100, maxRetries);
        }
    }

    /**
     * Builder for AIService.
     */
    public static class Builder {
        private final String serviceId;
        private final ServiceType type;
        private String provider = "default";
        private Map<ResourceType, Long> resourceRequirements;
        private Integer maxCapacity;
        private QoSParameters qos = QoSParameters.defaults();
        private Map<String, Object> metadata = new HashMap<>();

        public Builder(String serviceId, ServiceType type) {
            this.serviceId = serviceId;
            this.type = type;
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder resourceRequirements(Map<ResourceType, Long> requirements) {
            this.resourceRequirements = requirements;
            return this;
        }

        public Builder scaleResources(double factor) {
            this.resourceRequirements = new HashMap<>();
            for (var entry : type.getDefaultResourceRequirements().entrySet()) {
                this.resourceRequirements.put(entry.getKey(), 
                    (long) Math.ceil(entry.getValue() * factor));
            }
            return this;
        }

        public Builder maxCapacity(int capacity) {
            this.maxCapacity = capacity;
            return this;
        }

        public Builder qos(QoSParameters qos) {
            this.qos = qos;
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public AIService build() {
            if (resourceRequirements == null) {
                resourceRequirements = new HashMap<>(type.getDefaultResourceRequirements());
            }
            if (maxCapacity == null) {
                maxCapacity = type.getDefaultCapacity();
            }
            return new AIService(this);
        }
    }

    private AIService(Builder builder) {
        this.serviceId = builder.serviceId;
        this.type = builder.type;
        this.provider = builder.provider;
        this.resourceRequirements = Collections.unmodifiableMap(builder.resourceRequirements);
        this.maxCapacity = builder.maxCapacity;
        this.currentLoad = 0;
        this.available = true;
        this.qos = builder.qos;
        this.metadata = new HashMap<>(builder.metadata);
    }

    /**
     * Create a service with default settings.
     */
    public static AIService ofType(ServiceType type) {
        return new Builder(type.name() + "-" + UUID.randomUUID().toString().substring(0, 8), type)
            .build();
    }

    /**
     * Create a service with specified ID and type.
     */
    public static AIService create(String serviceId, ServiceType type) {
        return new Builder(serviceId, type).build();
    }

    // ========================================================================
    // Accessors
    // ========================================================================

    public String getServiceId() {
        return serviceId;
    }

    public ServiceType getType() {
        return type;
    }

    public String getProvider() {
        return provider;
    }

    public Map<ResourceType, Long> getResourceRequirements() {
        return resourceRequirements;
    }

    public long getResourceRequirement(ResourceType resource) {
        return resourceRequirements.getOrDefault(resource, 0L);
    }

    public int getMaxCapacity() {
        return maxCapacity;
    }

    public int getCurrentLoad() {
        return currentLoad;
    }

    public int getAvailableCapacity() {
        return Math.max(0, maxCapacity - currentLoad);
    }

    public boolean isAvailable() {
        return available && getAvailableCapacity() > 0;
    }

    public QoSParameters getQos() {
        return qos;
    }

    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    public Object getMetadata(String key) {
        return metadata.get(key);
    }

    // ========================================================================
    // State Management
    // ========================================================================

    /**
     * Reserve capacity for a request.
     * @return true if reservation successful, false if insufficient capacity
     */
    public synchronized boolean reserveCapacity(int slots) {
        if (!available || currentLoad + slots > maxCapacity) {
            return false;
        }
        currentLoad += slots;
        return true;
    }

    /**
     * Release previously reserved capacity.
     */
    public synchronized void releaseCapacity(int slots) {
        currentLoad = Math.max(0, currentLoad - slots);
    }

    /**
     * Set service availability.
     */
    public void setAvailable(boolean available) {
        this.available = available;
    }

    /**
     * Reset load to zero.
     */
    public synchronized void resetLoad() {
        this.currentLoad = 0;
    }

    // ========================================================================
    // Compatibility
    // ========================================================================

    /**
     * Check if this service can accept output from another service.
     */
    public boolean canAcceptFrom(AIService other) {
        return this.type.canAcceptOutputFrom(other.type);
    }

    /**
     * Check if this service can feed into another service.
     */
    public boolean canFeedInto(AIService other) {
        return other.type.canAcceptOutputFrom(this.type);
    }

    /**
     * Get input data types.
     */
    public Set<ServiceType.DataType> getInputTypes() {
        return type.getInputTypes();
    }

    /**
     * Get output data types.
     */
    public Set<ServiceType.DataType> getOutputTypes() {
        return type.getOutputTypes();
    }

    // ========================================================================
    // Resource Calculation
    // ========================================================================

    /**
     * Calculate total resource requirements for given number of concurrent requests.
     */
    public Map<ResourceType, Long> calculateTotalResources(int concurrentRequests) {
        Map<ResourceType, Long> total = new HashMap<>();
        for (var entry : resourceRequirements.entrySet()) {
            total.put(entry.getKey(), entry.getValue() * concurrentRequests);
        }
        return total;
    }

    /**
     * Estimate latency for current load level.
     */
    public int estimateLatencyMs() {
        double loadFactor = (double) currentLoad / maxCapacity;
        // Latency increases non-linearly with load
        double multiplier = 1.0 + Math.pow(loadFactor, 2);
        return (int) (type.getBaseLatencyMs() * multiplier);
    }

    /**
     * Get utilization ratio (0.0 to 1.0).
     */
    public double getUtilization() {
        return (double) currentLoad / maxCapacity;
    }

    // ========================================================================
    // Object Methods
    // ========================================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AIService aiService = (AIService) o;
        return Objects.equals(serviceId, aiService.serviceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serviceId);
    }

    @Override
    public String toString() {
        return String.format("AIService[%s: %s, %d/%d capacity, %s]",
            serviceId, type.getDisplayName(), currentLoad, maxCapacity,
            available ? "available" : "unavailable");
    }

    /**
     * Detailed status string.
     */
    public String toDetailedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("AIService[").append(serviceId).append("]\n");
        sb.append("  Type: ").append(type.getDisplayName()).append("\n");
        sb.append("  Provider: ").append(provider).append("\n");
        sb.append("  Capacity: ").append(currentLoad).append("/").append(maxCapacity).append("\n");
        sb.append("  Utilization: ").append(String.format("%.1f%%", getUtilization() * 100)).append("\n");
        sb.append("  Available: ").append(available).append("\n");
        sb.append("  Resources: ").append(resourceRequirements).append("\n");
        sb.append("  QoS: ").append(qos).append("\n");
        sb.append("  Est. Latency: ").append(estimateLatencyMs()).append("ms\n");
        sb.append("  Inputs: ").append(getInputTypes()).append("\n");
        sb.append("  Outputs: ").append(getOutputTypes());
        return sb.toString();
    }
}
