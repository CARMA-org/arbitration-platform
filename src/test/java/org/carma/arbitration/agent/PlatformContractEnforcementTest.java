package org.carma.arbitration.agent;

import org.carma.arbitration.agent.ExampleAgents.NewsSearchAgent;
import org.carma.arbitration.agent.RealisticAgentFramework.AgentRuntime;
import org.carma.arbitration.agent.RealisticAgentFramework.ExecutionContext;
import org.carma.arbitration.agent.RealisticAgentFramework.ServiceResult;
import org.carma.arbitration.mechanism.MockServiceBackend;
import org.carma.arbitration.mechanism.ServiceArbitrator;
import org.carma.arbitration.mechanism.PriorityEconomy;
import org.carma.arbitration.model.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PlatformContractEnforcementTest {

    private ResourcePool pool() {
        Map<ResourceType, Long> cap = new HashMap<>();
        cap.put(ResourceType.COMPUTE, 400L);
        cap.put(ResourceType.MEMORY, 400L);
        cap.put(ResourceType.API_CREDITS, 200L);
        cap.put(ResourceType.DATASET, 100L);
        return new ResourcePool(cap);
    }

    private AgentRuntime runtime(ServiceRegistry registry, ResourcePool pool) {
        PriorityEconomy economy = new PriorityEconomy();
        return new AgentRuntime.Builder()
            .serviceArbitrator(new ServiceArbitrator(economy, registry))
            .serviceRegistry(registry)
            .resourcePool(pool)
            .serviceBackend(new MockServiceBackend(registry))
            .build();
    }

    private void registerAgent(AgentRuntime runtime, String id) {
        runtime.register(new NewsSearchAgent.Builder(id)
            .topics(List.of("AI")).initialCurrency(50).build());
    }

    private ServiceRegistry registryWith(ServiceType type, int capacity) {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(new AIService.Builder("svc-" + type.name(), type)
            .maxCapacity(capacity).build());
        return registry;
    }

    private Map<ServiceType, Integer> slots(ServiceType type) {
        Map<ServiceType, Integer> s = new HashMap<>();
        s.put(type, 8);
        return s;
    }

    private Map<ResourceType, Long> bundle(long compute, long memory, long api) {
        Map<ResourceType, Long> b = new HashMap<>();
        b.put(ResourceType.COMPUTE, compute);
        b.put(ResourceType.MEMORY, memory);
        b.put(ResourceType.API_CREDITS, api);
        return b;
    }

    @Test
    void twoContextsUnderOneContractCannotJointlyOverspend() {
        ServiceType type = ServiceType.TEXT_GENERATION;
        ServiceRegistry registry = registryWith(type, 8);
        AgentRuntime runtime = runtime(registry, pool());
        registerAgent(runtime, "a1");
        Map<String, Map<ResourceType, Long>> alloc = new HashMap<>();
        alloc.put("a1", bundle(100, 100, 8));
        runtime.installContracts(alloc, "test", "optimal", null);

        ExecutionContext ctx1 = runtime.createExecutionContext("a1", slots(type));
        ExecutionContext ctx2 = runtime.createExecutionContext("a1", slots(type));

        assertTrue(ctx1.invokeService(type, Map.of("prompt", "x")).isSuccess());
        ServiceResult second = ctx2.invokeService(type, Map.of("prompt", "y"));
        assertFalse(second.isSuccess(), "second context must not spend beyond shared budget");
        assertEquals(ResourceType.API_CREDITS, second.getExhaustedResource());

        ConsumptionLedger ledger = runtime.getSnapshot().getLedger("a1");
        assertEquals(5L, ledger.getConsumed(ResourceType.API_CREDITS));
    }

    @Test
    void manyConcurrentContextsCannotJointlyOverspend() throws Exception {
        ServiceType type = ServiceType.TEXT_GENERATION;
        ServiceRegistry registry = registryWith(type, 16);
        AgentRuntime runtime = runtime(registry, pool());
        registerAgent(runtime, "a1");
        Map<String, Map<ResourceType, Long>> alloc = new HashMap<>();
        alloc.put("a1", bundle(300, 300, 30));
        runtime.installContracts(alloc, "test", "optimal", null);

        int threads = 20;
        ExecutorService poolExec = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(poolExec.submit(() -> {
                ExecutionContext ctx = runtime.createExecutionContext("a1", slots(type));
                start.await();
                return ctx.invokeService(type, Map.of("prompt", "x")).isSuccess();
            }));
        }
        start.countDown();
        int successes = 0;
        for (Future<Boolean> f : futures) if (f.get()) successes++;
        poolExec.shutdown();

        assertEquals(6, successes, "API budget 30 / 5 per call permits exactly 6 calls");
        ConsumptionLedger ledger = runtime.getSnapshot().getLedger("a1");
        assertEquals(30L, ledger.getConsumed(ResourceType.API_CREDITS));
        assertTrue(ledger.getConsumed(ResourceType.API_CREDITS) <= 30L);
    }

    @Test
    void customServiceResourceVectorIsChargedNotEnumDefault() {
        ServiceType type = ServiceType.TEXT_SUMMARIZATION;
        ServiceRegistry registry = new ServiceRegistry();
        Map<ResourceType, Long> custom = new HashMap<>();
        custom.put(ResourceType.COMPUTE, 3L);
        custom.put(ResourceType.MEMORY, 2L);
        custom.put(ResourceType.API_CREDITS, 1L);
        registry.register(new AIService.Builder("svc-custom", type)
            .resourceRequirements(custom).maxCapacity(4).build());
        AgentRuntime runtime = runtime(registry, pool());
        registerAgent(runtime, "a1");
        Map<String, Map<ResourceType, Long>> alloc = new HashMap<>();
        alloc.put("a1", bundle(50, 50, 50));
        runtime.installContracts(alloc, "test", "optimal", null);

        ExecutionContext ctx = runtime.createExecutionContext("a1", slots(type));
        assertTrue(ctx.invokeService(type, Map.of("text", "x")).isSuccess());

        assertEquals(3L, ctx.getCharged(ResourceType.COMPUTE));
        assertEquals(2L, ctx.getCharged(ResourceType.MEMORY));
        assertEquals(1L, ctx.getCharged(ResourceType.API_CREDITS));
    }

    @Test
    void expiredContractDeniedBeforeBackend() {
        ServiceType type = ServiceType.TEXT_GENERATION;
        ServiceRegistry registry = registryWith(type, 8);
        MockServiceBackend backend = new MockServiceBackend(registry);
        PriorityEconomy economy = new PriorityEconomy();
        AgentRuntime runtime = new AgentRuntime.Builder()
            .serviceArbitrator(new ServiceArbitrator(economy, registry))
            .serviceRegistry(registry).resourcePool(pool())
            .serviceBackend(backend).build();
        AtomicLong fakeClock = new AtomicLong(1000L);
        runtime.setClock(fakeClock::get);
        registerAgent(runtime, "a1");
        Map<String, Map<ResourceType, Long>> alloc = new HashMap<>();
        alloc.put("a1", bundle(100, 100, 100));
        runtime.installContracts(alloc, "test", "optimal", 500L);

        ExecutionContext ctx = runtime.createExecutionContext("a1", slots(type));
        fakeClock.set(5000L);

        ServiceResult r = ctx.invokeService(type, Map.of("prompt", "x"));
        assertFalse(r.isSuccess());
        assertTrue(r.getError().contains("expired"));
        assertEquals(0L, ctx.getCharged(ResourceType.API_CREDITS));
        assertEquals(0, ctx.getBackendInvocations());
        assertEquals(0, backend.getInvocationCount());
    }

    @Test
    void staleContextDeniedAfterNewerContractInstalled() {
        ServiceType type = ServiceType.TEXT_GENERATION;
        ServiceRegistry registry = registryWith(type, 8);
        AgentRuntime runtime = runtime(registry, pool());
        registerAgent(runtime, "a1");
        Map<String, Map<ResourceType, Long>> alloc = new HashMap<>();
        alloc.put("a1", bundle(100, 100, 100));
        runtime.installContracts(alloc, "test", "optimal", null);
        ExecutionContext staleCtx = runtime.createExecutionContext("a1", slots(type));

        runtime.updateContract("a1", bundle(80, 80, 80), "test", "optimal", null);

        ServiceResult r = staleCtx.invokeService(type, Map.of("prompt", "x"));
        assertFalse(r.isSuccess());
        assertTrue(r.getError().contains("stale"));
        assertEquals(0, staleCtx.getBackendInvocations());
    }

    @Test
    void removedAgentCannotExecuteThroughOldContext() {
        ServiceType type = ServiceType.TEXT_GENERATION;
        ServiceRegistry registry = registryWith(type, 8);
        AgentRuntime runtime = runtime(registry, pool());
        registerAgent(runtime, "a1");
        Map<String, Map<ResourceType, Long>> alloc = new HashMap<>();
        alloc.put("a1", bundle(100, 100, 100));
        runtime.installContracts(alloc, "test", "optimal", null);
        ExecutionContext ctx = runtime.createExecutionContext("a1", slots(type));

        runtime.unregister("a1");

        ServiceResult r = ctx.invokeService(type, Map.of("prompt", "x"));
        assertFalse(r.isSuccess());
        assertEquals(0, ctx.getBackendInvocations());
        assertFalse(runtime.hasContract("a1"));
    }

    @Test
    void completeBatchIsAtomicallyVisibleToConcurrentReaders() throws Exception {
        ServiceRegistry registry = registryWith(ServiceType.TEXT_GENERATION, 8);
        AgentRuntime runtime = runtime(registry, pool());
        List<String> ids = List.of("a1", "a2", "a3", "a4");
        for (String id : ids) registerAgent(runtime, id);

        AtomicReference<String> violation = new AtomicReference<>(null);
        AtomicInteger installs = new AtomicInteger(0);
        Thread reader = new Thread(() -> {
            while (installs.get() < 50) {
                AllocationSnapshot snap = runtime.getSnapshot();
                int present = 0;
                long ver = -2;
                boolean consistent = true;
                for (String id : ids) {
                    AllocationContract c = snap.getContract(id);
                    if (c != null) {
                        present++;
                        if (ver == -2) ver = c.getVersion();
                        else if (ver != c.getVersion()) consistent = false;
                    }
                }
                if (present != 0 && present != ids.size()) violation.set("partial batch: " + present);
                if (!consistent) violation.set("mixed versions in snapshot");
            }
        });
        reader.start();
        for (int k = 0; k < 50; k++) {
            Map<String, Map<ResourceType, Long>> alloc = new HashMap<>();
            for (String id : ids) alloc.put(id, bundle(10, 10, 10));
            runtime.installContracts(alloc, "test", "optimal", null);
            installs.incrementAndGet();
        }
        reader.join(5000);
        assertNull(violation.get(), "reader observed " + violation.get());
    }

    @Test
    void singleContractUpdateCannotExceedCapacity() {
        ServiceRegistry registry = registryWith(ServiceType.TEXT_GENERATION, 8);
        AgentRuntime runtime = runtime(registry, pool());
        registerAgent(runtime, "a1");
        registerAgent(runtime, "a2");
        Map<String, Map<ResourceType, Long>> alloc = new HashMap<>();
        alloc.put("a1", bundle(100, 100, 100));
        alloc.put("a2", bundle(100, 100, 100));
        runtime.installContracts(alloc, "test", "optimal", null);
        long a2Version = runtime.getContract("a2").getVersion();

        assertThrows(IllegalStateException.class,
            () -> runtime.updateContract("a2", bundle(350, 100, 100), "test", "optimal", null));
        assertEquals(a2Version, runtime.getContract("a2").getVersion(),
            "rejected update must leave the prior contract intact");
    }

    @Test
    void failedBatchInstallationLeavesEntireOldSnapshotUnchanged() {
        ServiceRegistry registry = registryWith(ServiceType.TEXT_GENERATION, 8);
        AgentRuntime runtime = runtime(registry, pool());
        registerAgent(runtime, "a1");
        registerAgent(runtime, "a2");
        Map<String, Map<ResourceType, Long>> good = new HashMap<>();
        good.put("a1", bundle(100, 100, 50));
        good.put("a2", bundle(100, 100, 50));
        runtime.installContracts(good, "test", "optimal", null);
        AllocationSnapshot before = runtime.getSnapshot();
        long beforeVersion = before.getVersion();
        ConsumptionLedger a1Ledger = before.getLedger("a1");

        Map<String, Map<ResourceType, Long>> bad = new HashMap<>();
        bad.put("a1", bundle(300, 100, 50));
        bad.put("a2", bundle(300, 100, 50));
        assertThrows(IllegalStateException.class,
            () -> runtime.installContracts(bad, "test", "optimal", null));

        assertEquals(beforeVersion, runtime.getSnapshot().getVersion());
        assertSame(a1Ledger, runtime.getSnapshot().getLedger("a1"));
        assertFalse(a1Ledger.isInvalidated(), "failed install must not invalidate old ledger");
    }

    @Test
    void contractReplacementCreatesNewLedgerOnlyForNewVersion() {
        ServiceRegistry registry = registryWith(ServiceType.TEXT_GENERATION, 8);
        AgentRuntime runtime = runtime(registry, pool());
        registerAgent(runtime, "a1");
        registerAgent(runtime, "a2");
        Map<String, Map<ResourceType, Long>> alloc = new HashMap<>();
        alloc.put("a1", bundle(100, 100, 50));
        alloc.put("a2", bundle(100, 100, 50));
        runtime.installContracts(alloc, "test", "optimal", null);
        ConsumptionLedger a1v1 = runtime.getSnapshot().getLedger("a1");
        ConsumptionLedger a2v1 = runtime.getSnapshot().getLedger("a2");

        runtime.updateContract("a1", bundle(90, 90, 40), "test", "optimal", null);

        assertNotSame(a1v1, runtime.getSnapshot().getLedger("a1"));
        assertTrue(a1v1.isInvalidated(), "old ledger version must be invalidated");
        assertSame(a2v1, runtime.getSnapshot().getLedger("a2"), "unaffected agent keeps its ledger");
        assertFalse(a2v1.isInvalidated());
    }

    @Test
    void batchInstallationRejectsNegativeComponentsAndInvalidatesNoLedger() {
        ServiceRegistry registry = registryWith(ServiceType.TEXT_GENERATION, 8);
        AgentRuntime runtime = runtime(registry, pool());
        registerAgent(runtime, "a1");
        registerAgent(runtime, "a2");
        Map<String, Map<ResourceType, Long>> good = new HashMap<>();
        good.put("a1", bundle(50, 50, 50));
        good.put("a2", bundle(50, 50, 50));
        runtime.installContracts(good, "test", "optimal", null);
        ConsumptionLedger a1Ledger = runtime.getSnapshot().getLedger("a1");
        long beforeVersion = runtime.getSnapshot().getVersion();

        Map<String, Map<ResourceType, Long>> bad = new HashMap<>();
        bad.put("a1", bundle(-5, 50, 50));
        bad.put("a2", bundle(50, 50, 50));
        assertThrows(IllegalArgumentException.class,
            () -> runtime.installContracts(bad, "test", "optimal", null));

        assertEquals(beforeVersion, runtime.getSnapshot().getVersion());
        assertSame(a1Ledger, runtime.getSnapshot().getLedger("a1"));
        assertFalse(a1Ledger.isInvalidated(), "rejected batch must not invalidate any existing ledger");
    }

    @Test
    void concurrentContractReplacementAndConsumptionNeverOverspends() throws Exception {
        ServiceType type = ServiceType.TEXT_GENERATION;
        ServiceRegistry registry = registryWith(type, 16);
        AgentRuntime runtime = runtime(registry, pool());
        registerAgent(runtime, "a1");
        Map<String, Map<ResourceType, Long>> alloc = new HashMap<>();
        alloc.put("a1", bundle(300, 300, 40));
        runtime.installContracts(alloc, "test", "optimal", null);

        AtomicReference<Throwable> failure = new AtomicReference<>(null);
        Thread replacer = new Thread(() -> {
            try {
                for (int k = 0; k < 40; k++) {
                    Map<String, Map<ResourceType, Long>> a = new HashMap<>();
                    a.put("a1", bundle(300, 300, 40));
                    runtime.installContracts(a, "test", "optimal", null);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        replacer.start();
        for (int k = 0; k < 200; k++) {
            ExecutionContext ctx = runtime.createExecutionContext("a1", slots(type));
            ctx.invokeService(type, Map.of("prompt", "x"));
        }
        replacer.join(5000);

        assertNull(failure.get(), "no exception during concurrent replacement");
        ConsumptionLedger current = runtime.getSnapshot().getLedger("a1");
        assertTrue(current.getConsumed(ResourceType.API_CREDITS) <= 40L,
            "no ledger version exceeds its contract bundle");
    }

    @Test
    void duplicateIdenticalCallsAreEachChargedSeparately() {
        ServiceType type = ServiceType.TEXT_GENERATION;
        ServiceRegistry registry = registryWith(type, 8);
        AgentRuntime runtime = runtime(registry, pool());
        registerAgent(runtime, "a1");
        Map<String, Map<ResourceType, Long>> alloc = new HashMap<>();
        alloc.put("a1", bundle(100, 100, 100));
        runtime.installContracts(alloc, "test", "optimal", null);
        ExecutionContext ctx = runtime.createExecutionContext("a1", slots(type));

        Map<String, Object> identical = Map.of("prompt", "same");
        assertTrue(ctx.invokeService(type, identical).isSuccess());
        assertTrue(ctx.invokeService(type, identical).isSuccess());

        assertEquals(2, ctx.getBackendInvocations());
        assertEquals(10L, ctx.getCharged(ResourceType.API_CREDITS));
    }
}
