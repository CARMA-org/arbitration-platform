package org.carma.arbitration.event;

import org.carma.arbitration.model.ResourceType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Base interface for all platform events.
 * Events provide an audit trail and enable reactive processing.
 */
public sealed interface Event permits 
        Event.ResourceRequestEvent,
        Event.ContentionDetectedEvent,
        Event.ArbitrationCompleteEvent,
        Event.AllocationEnforcedEvent,
        Event.ResourceReleaseEvent,
        Event.CurrencyMintedEvent,
        Event.CurrencyBurnedEvent,
        Event.SimulationTickEvent {
    
    Instant timestamp();
    String eventType();

    // ========================================================================
    // Event Types
    // ========================================================================

    /**
     * Agent requested resources.
     */
    record ResourceRequestEvent(
            Instant timestamp,
            String agentId,
            ResourceType resourceType,
            long minimumQuantity,
            long idealQuantity
    ) implements Event {
        public String eventType() { return "RESOURCE_REQUEST"; }
    }

    /**
     * Contention detected between agents.
     */
    record ContentionDetectedEvent(
            Instant timestamp,
            ResourceType resourceType,
            List<String> competingAgentIds,
            long availableQuantity,
            long totalDemand
    ) implements Event {
        public String eventType() { return "CONTENTION_DETECTED"; }
    }

    /**
     * Arbitration completed with results.
     */
    record ArbitrationCompleteEvent(
            Instant timestamp,
            ResourceType resourceType,
            Map<String, Long> allocations,
            Map<String, BigDecimal> currencyBurned,
            double objectiveValue,
            long computationTimeMs
    ) implements Event {
        public String eventType() { return "ARBITRATION_COMPLETE"; }
    }

    /**
     * Allocation enforced on an agent.
     */
    record AllocationEnforcedEvent(
            Instant timestamp,
            String agentId,
            ResourceType resourceType,
            long quantity
    ) implements Event {
        public String eventType() { return "ALLOCATION_ENFORCED"; }
    }

    /**
     * Agent released resources.
     */
    record ResourceReleaseEvent(
            Instant timestamp,
            String agentId,
            ResourceType resourceType,
            long quantity,
            String reason
    ) implements Event {
        public String eventType() { return "RESOURCE_RELEASE"; }
    }

    /**
     * Currency minted for an agent.
     */
    record CurrencyMintedEvent(
            Instant timestamp,
            String agentId,
            BigDecimal amount,
            String reason
    ) implements Event {
        public String eventType() { return "CURRENCY_MINTED"; }
    }

    /**
     * Currency burned by an agent.
     */
    record CurrencyBurnedEvent(
            Instant timestamp,
            String agentId,
            BigDecimal amount,
            String reason
    ) implements Event {
        public String eventType() { return "CURRENCY_BURNED"; }
    }

    /**
     * Simulation tick for time-based simulations.
     */
    record SimulationTickEvent(
            Instant timestamp,
            long tickNumber,
            long elapsedMs
    ) implements Event {
        public String eventType() { return "SIMULATION_TICK"; }
    }
}
