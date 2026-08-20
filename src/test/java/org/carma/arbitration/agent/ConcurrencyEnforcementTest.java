package org.carma.arbitration.agent;

import org.carma.arbitration.agent.RealisticAgentFramework.ExecutionContext;
import org.carma.arbitration.agent.RealisticAgentFramework.ServiceResult;
import org.carma.arbitration.mechanism.MockServiceBackend;
import org.carma.arbitration.model.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyEnforcementTest {

    private static final int THREADS = 64;
    private static final int REPS = 100;

    @Test
    void concurrentCallsNeverExceedAgentQuota() throws Exception {
        ServiceType type = ServiceType.TEXT_GENERATION; // API_CREDITS cost 5 per call
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(new AIService.Builder("gen", type).maxCapacity(THREADS * 4).build());
        MockServiceBackend backend = new MockServiceBackend(registry, MockServiceBackend.MockConfig.fast());

        int allowedCalls = 20;
        Map<ResourceType, Long> alloc = new HashMap<>();
        alloc.put(ResourceType.COMPUTE, 100_000L);   // not the binding resource
        alloc.put(ResourceType.MEMORY, 100_000L);
        alloc.put(ResourceType.API_CREDITS, (long) allowedCalls * 5); // binds to 20 calls
        Map<ServiceType, Integer> slots = new HashMap<>();
        slots.put(type, 1);
        ExecutionContext ctx = new ExecutionContext(alloc, slots, registry, backend, s -> {}, 1000L);

        AtomicInteger successes = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS * 4; i++) {
            futures.add(pool.submit(() -> {
                ServiceResult r = ctx.invokeService(type, Map.of("prompt", "x"));
                if (r.isSuccess()) successes.incrementAndGet();
            }));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertEquals(allowedCalls, successes.get(), "exactly the affordable number of calls succeed");
        assertEquals((long) allowedCalls * 5, ctx.getCharged(ResourceType.API_CREDITS));
        assertTrue(ctx.getCharged(ResourceType.API_CREDITS) <= alloc.get(ResourceType.API_CREDITS),
            "agent API_CREDITS quota never exceeded");
        assertEquals(allowedCalls, backend.getInvocationCount(),
            "backend invoked exactly once per successful call");
    }

    @Test
    void concurrentCallsNeverExceedServiceCapacity() throws Exception {
        ServiceType type = ServiceType.TEXT_GENERATION;
        int capacity = 4;
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(new AIService.Builder("gen", type).maxCapacity(capacity).build());
        MockServiceBackend backend = new MockServiceBackend(registry, MockServiceBackend.MockConfig.fast());

        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        backend.registerHandler(type, input -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            active.decrementAndGet();
            return Map.of("text", "ok");
        });

        Map<ResourceType, Long> alloc = new HashMap<>();
        alloc.put(ResourceType.COMPUTE, 100_000_000L);   // quota never binds
        alloc.put(ResourceType.MEMORY, 100_000_000L);
        alloc.put(ResourceType.API_CREDITS, 100_000_000L);
        Map<ServiceType, Integer> slots = new HashMap<>();
        slots.put(type, 1);
        ExecutionContext ctx = new ExecutionContext(alloc, slots, registry, backend, s -> {}, 5000L);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < REPS; i++) {
            futures.add(pool.submit(() -> ctx.invokeService(type, Map.of("prompt", "x"))));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertTrue(maxActive.get() <= capacity,
            "concurrent backend executions " + maxActive.get() + " must not exceed capacity " + capacity);
        assertTrue(maxActive.get() >= 1);
    }
}
