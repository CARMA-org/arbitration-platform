package org.carma.arbitration.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServiceHandle {

    private final String serviceId;
    private final ServiceType serviceType;
    private final Map<ResourceType, Long> requirements;
    private final Runnable releaser;
    private final AtomicBoolean released = new AtomicBoolean(false);

    public ServiceHandle(String serviceId, ServiceType serviceType,
                         Map<ResourceType, Long> requirements, Runnable releaser) {
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId");
        this.serviceType = Objects.requireNonNull(serviceType, "serviceType");
        this.requirements = Collections.unmodifiableMap(new HashMap<>(
            Objects.requireNonNull(requirements, "requirements")));
        this.releaser = Objects.requireNonNull(releaser, "releaser");
    }

    public static ServiceHandle withoutRegistry(ServiceType serviceType) {
        return new ServiceHandle("no-registry", serviceType,
            serviceType.getDefaultResourceRequirements(), () -> {});
    }

    public String getServiceId() { return serviceId; }

    public ServiceType getServiceType() { return serviceType; }

    public Map<ResourceType, Long> getRequirements() { return requirements; }

    public void release() {
        if (released.compareAndSet(false, true)) {
            releaser.run();
        }
    }
}
