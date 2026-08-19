package org.carma.arbitration.agent;

import org.carma.arbitration.model.ResourceType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NegativeConsumptionTest {

    private RealisticAgentFramework.ExecutionContext context(long allocated) {
        Map<ResourceType, Long> alloc = new HashMap<>();
        alloc.put(ResourceType.API_CREDITS, allocated);
        return new RealisticAgentFramework.ExecutionContext(
            alloc, new HashMap<>(), null, null, s -> {}, 1000L);
    }

    @Test
    void negativeRequestRejectedAndConsumptionUnchanged() {
        RealisticAgentFramework.ExecutionContext ctx = context(100);
        assertTrue(ctx.tryConsumeResource(ResourceType.API_CREDITS, 40));
        long before = ctx.getConsumedResource(ResourceType.API_CREDITS);
        assertFalse(ctx.tryConsumeResource(ResourceType.API_CREDITS, -30));
        assertEquals(before, ctx.getConsumedResource(ResourceType.API_CREDITS));
    }

    @Test
    void zeroRequestSucceedsAndDoesNotChangeConsumption() {
        RealisticAgentFramework.ExecutionContext ctx = context(100);
        long before = ctx.getConsumedResource(ResourceType.API_CREDITS);
        assertTrue(ctx.tryConsumeResource(ResourceType.API_CREDITS, 0));
        assertEquals(before, ctx.getConsumedResource(ResourceType.API_CREDITS));
    }

    @Test
    void validPositiveRequestConsumes() {
        RealisticAgentFramework.ExecutionContext ctx = context(100);
        assertTrue(ctx.tryConsumeResource(ResourceType.API_CREDITS, 60));
        assertEquals(60, ctx.getConsumedResource(ResourceType.API_CREDITS));
    }

    @Test
    void excessiveRequestRejected() {
        RealisticAgentFramework.ExecutionContext ctx = context(100);
        assertFalse(ctx.tryConsumeResource(ResourceType.API_CREDITS, 150));
    }

    @Test
    void canConsumeRejectsNegative() {
        RealisticAgentFramework.ExecutionContext ctx = context(100);
        assertFalse(ctx.canConsumeResource(ResourceType.API_CREDITS, -1));
        assertTrue(ctx.canConsumeResource(ResourceType.API_CREDITS, 50));
    }
}
