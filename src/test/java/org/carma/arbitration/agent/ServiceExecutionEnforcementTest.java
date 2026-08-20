package org.carma.arbitration.agent;

import org.carma.arbitration.agent.RealisticAgentFramework.ExecutionContext;
import org.carma.arbitration.agent.RealisticAgentFramework.ServiceResult;
import org.carma.arbitration.mechanism.MockServiceBackend;
import org.carma.arbitration.model.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ServiceExecutionEnforcementTest {

    private ServiceRegistry registryWith(ServiceType type, int capacity) {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(new AIService.Builder("svc-" + type.name(), type)
            .maxCapacity(capacity).build());
        return registry;
    }

    private ExecutionContext context(Map<ResourceType, Long> alloc, ServiceType type,
                                     ServiceRegistry registry, MockServiceBackend backend) {
        Map<ServiceType, Integer> slots = new HashMap<>();
        slots.put(type, 1);
        return new ExecutionContext(alloc, slots, registry, backend, s -> {}, 1000L);
    }

    @Test
    void everyServiceCallChargesFullResourceVector() {
        ServiceType type = ServiceType.TEXT_GENERATION; // {COMPUTE10, MEMORY8, API_CREDITS5}
        ServiceRegistry registry = registryWith(type, 5);
        MockServiceBackend backend = new MockServiceBackend(registry);
        Map<ResourceType, Long> alloc = new HashMap<>();
        alloc.put(ResourceType.COMPUTE, 10L);
        alloc.put(ResourceType.MEMORY, 8L);
        alloc.put(ResourceType.API_CREDITS, 5L);
        ExecutionContext ctx = context(alloc, type, registry, backend);

        ServiceResult r = ctx.invokeService(type, Map.of("prompt", "hi"));
        assertTrue(r.isSuccess(), "call should succeed: " + r.getError());

        assertEquals(10L, ctx.getCharged(ResourceType.COMPUTE));
        assertEquals(8L, ctx.getCharged(ResourceType.MEMORY));
        assertEquals(5L, ctx.getCharged(ResourceType.API_CREDITS));
        assertEquals(1, ctx.getBackendInvocations());
        assertEquals(0, ctx.getBlockedCalls());
        assertEquals(1, backend.getInvocationCount());
    }

    @Test
    void failedMultiResourceChargeChangesNoCounterAndNeverReachesBackend() {
        ServiceType type = ServiceType.TEXT_GENERATION;
        ServiceRegistry registry = registryWith(type, 5);
        MockServiceBackend backend = new MockServiceBackend(registry);
        Map<ResourceType, Long> alloc = new HashMap<>();
        alloc.put(ResourceType.COMPUTE, 10L);
        alloc.put(ResourceType.MEMORY, 0L);       // short on exactly one resource
        alloc.put(ResourceType.API_CREDITS, 5L);
        ExecutionContext ctx = context(alloc, type, registry, backend);

        ServiceResult r = ctx.invokeService(type, Map.of("prompt", "hi"));
        assertFalse(r.isSuccess());
        assertTrue(r.isDenied());
        assertEquals(ResourceType.MEMORY, r.getExhaustedResource());

        // nothing consumed on any resource
        assertEquals(0L, ctx.getCharged(ResourceType.COMPUTE));
        assertEquals(0L, ctx.getCharged(ResourceType.MEMORY));
        assertEquals(0L, ctx.getCharged(ResourceType.API_CREDITS));
        assertEquals(0L, ctx.getConsumedResource(ResourceType.COMPUTE));
        assertEquals(0, ctx.getBackendInvocations());
        assertEquals(1, ctx.getBlockedCalls());
        assertEquals(0, backend.getInvocationCount(), "denied call must not reach backend");
    }

    @Test
    void serviceSlotExhaustionDeniesWithoutConsumingOrInvoking() {
        ServiceType type = ServiceType.TEXT_GENERATION;
        ServiceRegistry registry = registryWith(type, 1);
        MockServiceBackend backend = new MockServiceBackend(registry);
        // Externally hold the only slot so the context cannot acquire one.
        assertTrue(registry.acquireSlot(type).isPresent());

        Map<ResourceType, Long> alloc = new HashMap<>();
        alloc.put(ResourceType.COMPUTE, 100L);
        alloc.put(ResourceType.MEMORY, 100L);
        alloc.put(ResourceType.API_CREDITS, 100L);
        ExecutionContext ctx = context(alloc, type, registry, backend);

        ServiceResult r = ctx.invokeService(type, Map.of("prompt", "hi"));
        assertFalse(r.isSuccess());
        assertTrue(r.isDenied());
        assertEquals(0L, ctx.getCharged(ResourceType.COMPUTE));
        assertEquals(0, ctx.getBackendInvocations());
        assertEquals(0, backend.getInvocationCount());
    }

    @Test
    void negativeBundleComponentChangesNoCounter() {
        ServiceRegistry registry = registryWith(ServiceType.TEXT_GENERATION, 5);
        MockServiceBackend backend = new MockServiceBackend(registry);
        Map<ResourceType, Long> alloc = new HashMap<>();
        alloc.put(ResourceType.API_CREDITS, 100L);
        ExecutionContext ctx = context(alloc, ServiceType.TEXT_GENERATION, registry, backend);

        Map<ResourceType, Long> negative = new HashMap<>();
        negative.put(ResourceType.API_CREDITS, -5L);
        ResourceType offending = ctx.tryConsumeBundle(negative);
        assertEquals(ResourceType.API_CREDITS, offending);
        assertEquals(0L, ctx.getConsumedResource(ResourceType.API_CREDITS));
        assertEquals(0L, ctx.getCharged(ResourceType.API_CREDITS));
    }

    @Test
    void overQuotaSingleResourceDoesNotPartiallyConsume() {
        ServiceRegistry registry = registryWith(ServiceType.TEXT_GENERATION, 5);
        MockServiceBackend backend = new MockServiceBackend(registry);
        Map<ResourceType, Long> alloc = new HashMap<>();
        alloc.put(ResourceType.API_CREDITS, 10L);
        ExecutionContext ctx = context(alloc, ServiceType.TEXT_GENERATION, registry, backend);

        assertTrue(ctx.tryConsumeResource(ResourceType.API_CREDITS, 7));
        assertFalse(ctx.tryConsumeResource(ResourceType.API_CREDITS, 5)); // only 3 remain
        assertEquals(7L, ctx.getConsumedResource(ResourceType.API_CREDITS),
            "over-quota request must not consume the remainder");
    }
}
