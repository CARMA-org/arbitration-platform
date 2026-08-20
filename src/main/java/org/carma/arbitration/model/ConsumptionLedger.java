package org.carma.arbitration.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class ConsumptionLedger {

    private final String agentId;
    private final long version;
    private final String allocationId;
    private final Map<ResourceType, Long> bundle;
    private final Map<ResourceType, Long> consumed = new HashMap<>();
    private volatile boolean invalidated = false;

    public ConsumptionLedger(String agentId, long version, String allocationId,
                             Map<ResourceType, Long> bundle) {
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.version = version;
        this.allocationId = Objects.requireNonNull(allocationId, "allocationId");
        this.bundle = Collections.unmodifiableMap(new HashMap<>(
            Objects.requireNonNull(bundle, "bundle")));
    }

    public String getAgentId() { return agentId; }

    public long getVersion() { return version; }

    public String getAllocationId() { return allocationId; }

    public boolean isInvalidated() { return invalidated; }

    public void invalidate() { invalidated = true; }

    public synchronized long getConsumed(ResourceType type) {
        return consumed.getOrDefault(type, 0L);
    }

    public long getBundleAmount(ResourceType type) {
        return bundle.getOrDefault(type, 0L);
    }

    public synchronized long getRemaining(ResourceType type) {
        return bundle.getOrDefault(type, 0L) - consumed.getOrDefault(type, 0L);
    }

    public synchronized Map<ResourceType, Long> getConsumedBundle() {
        return new HashMap<>(consumed);
    }

    public Map<ResourceType, Long> getBundle() {
        return bundle;
    }

    public synchronized ResourceType chargeBundle(Map<ResourceType, Long> charge) {
        if (invalidated) {
            for (Map.Entry<ResourceType, Long> e : charge.entrySet()) {
                return e.getKey();
            }
            return ResourceType.API_CREDITS;
        }
        for (Map.Entry<ResourceType, Long> e : charge.entrySet()) {
            if (e.getValue() == null || e.getValue() < 0) {
                return e.getKey();
            }
        }
        for (Map.Entry<ResourceType, Long> e : charge.entrySet()) {
            long remaining = bundle.getOrDefault(e.getKey(), 0L)
                - consumed.getOrDefault(e.getKey(), 0L);
            if (e.getValue() > remaining) {
                return e.getKey();
            }
        }
        for (Map.Entry<ResourceType, Long> e : charge.entrySet()) {
            consumed.merge(e.getKey(), e.getValue(), Long::sum);
        }
        return null;
    }
}
