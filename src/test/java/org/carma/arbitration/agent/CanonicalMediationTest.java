package org.carma.arbitration.agent;

import org.carma.arbitration.agent.ExampleAgents.*;
import org.carma.arbitration.agent.RealisticAgentFramework.*;
import org.carma.arbitration.mechanism.*;
import org.carma.arbitration.model.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CanonicalMediationTest {

    /** Wraps a real joint arbitrator and records how it was invoked. */
    static class CountingJointArbitrator implements JointArbitrator {
        final JointArbitrator delegate;
        final List<Set<ResourceType>> groupResources = new ArrayList<>();
        final List<JointAllocationResult> results = new ArrayList<>();

        CountingJointArbitrator(JointArbitrator delegate) { this.delegate = delegate; }

        @Override
        public JointAllocationResult arbitrate(
                ContentionDetector.ContentionGroup group, Map<String, BigDecimal> burns) {
            groupResources.add(new HashSet<>(group.getResources()));
            JointAllocationResult r = delegate.arbitrate(group, burns);
            results.add(r);
            return r;
        }

        @Override
        public JointAllocationResult arbitrate(
                List<Agent> agents, ResourcePool pool, Map<String, BigDecimal> burns) {
            return delegate.arbitrate(agents, pool, burns);
        }
    }

    private ConvexJointArbitrator convex() {
        String python = System.getenv().getOrDefault("SOLVER_PYTHON", "python3");
        Path script = Paths.get("scripts/joint_solver.py");
        return new ConvexJointArbitrator(new PriorityEconomy(), python, script);
    }

    private AgentRuntime scarceRuntimeWithThreeAgents(ResourcePool pool) {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(new AIService.Builder("text-gen", ServiceType.TEXT_GENERATION).maxCapacity(20).build());
        registry.register(new AIService.Builder("knowledge", ServiceType.KNOWLEDGE_RETRIEVAL).maxCapacity(30).build());
        registry.register(new AIService.Builder("summarize", ServiceType.TEXT_SUMMARIZATION).maxCapacity(15).build());
        PriorityEconomy economy = new PriorityEconomy();
        AgentRuntime runtime = new AgentRuntime.Builder()
            .serviceArbitrator(new ServiceArbitrator(economy, registry))
            .serviceRegistry(registry)
            .resourcePool(pool)
            .serviceBackend(new MockServiceBackend(registry))
            .build();
        runtime.register(new NewsSearchAgent.Builder("news-agent")
            .topics(List.of("AI")).initialCurrency(100).build());
        runtime.register(new DocumentSummarizerAgent.Builder("summarizer-agent")
            .initialCurrency(80).build());
        runtime.register(new ResearchAssistantAgent.Builder("research-agent")
            .researchDomains(List.of("AI")).initialCurrency(60).build());
        return runtime;
    }

    private ResourcePool scarcePool() {
        Map<ResourceType, Long> cap = new HashMap<>();
        cap.put(ResourceType.COMPUTE, 45L);
        cap.put(ResourceType.API_CREDITS, 20L);
        cap.put(ResourceType.MEMORY, 45L);
        cap.put(ResourceType.DATASET, 25L);
        return new ResourcePool(cap);
    }

    @Test
    void joinArbitratorInvokedOncePerCompleteContentionGroup() {
        ConvexJointArbitrator convex = convex();
        assumeTrue(convex.checkDependencies(), "cvxpy solver unavailable; skipping");

        AgentRuntime runtime = scarceRuntimeWithThreeAgents(scarcePool());
        CountingJointArbitrator spy = new CountingJointArbitrator(convex);

        runtime.runArbitration(new ContentionDetector(), spy, "ConvexJointArbitrator", null);

        // The three agents share compute under scarcity, forming exactly one group,
        // which is arbitrated with a single call over the complete resource set.
        assertEquals(1, spy.groupResources.size(),
            "expected exactly one joint call, one per contention group");
        Set<ResourceType> resources = spy.groupResources.get(0);
        assertTrue(resources.size() > 1, "group must be multi-resource: " + resources);
        assertTrue(resources.containsAll(List.of(
            ResourceType.COMPUTE, ResourceType.MEMORY,
            ResourceType.API_CREDITS, ResourceType.DATASET)));
    }

    @Test
    void installedContractsEqualJointArbitrationResult() {
        ConvexJointArbitrator convex = convex();
        assumeTrue(convex.checkDependencies(), "cvxpy solver unavailable; skipping");

        AgentRuntime runtime = scarceRuntimeWithThreeAgents(scarcePool());
        CountingJointArbitrator spy = new CountingJointArbitrator(convex);
        runtime.runArbitration(new ContentionDetector(), spy, "ConvexJointArbitrator", null);

        assertEquals(1, spy.results.size());
        JointArbitrator.JointAllocationResult r = spy.results.get(0);
        for (String agentId : List.of("news-agent", "summarizer-agent", "research-agent")) {
            AllocationContract c = runtime.getContract(agentId);
            assertNotNull(c, "contract must be installed for " + agentId);
            Map<ResourceType, Long> fromArb = r.getAllocations(agentId);
            for (ResourceType t : fromArb.keySet()) {
                assertEquals(fromArb.get(t), c.getResource(t),
                    "installed " + t + " for " + agentId + " must equal arbitration result");
            }
            assertEquals("ConvexJointArbitrator", c.getPolicyName());
        }
    }

    @Test
    void staleAllocationVersionsRejected() {
        AgentRuntime runtime = scarceRuntimeWithThreeAgents(scarcePool());
        Map<ResourceType, Long> bundle = Map.of(ResourceType.COMPUTE, 5L);

        AllocationContract v5 = new AllocationContract(
            "AC-5-a", 5, "a", bundle, 0L, null, "test", "optimal");
        assertTrue(runtime.installContract(v5));

        AllocationContract v3 = new AllocationContract(
            "AC-3-a", 3, "a", bundle, 0L, null, "test", "optimal");
        assertFalse(runtime.installContract(v3), "older version must be rejected");
        assertEquals(5, runtime.getContract("a").getVersion());

        AllocationContract v5again = new AllocationContract(
            "AC-5b-a", 5, "a", bundle, 0L, null, "test", "optimal");
        assertFalse(runtime.installContract(v5again), "equal version must be rejected");

        AllocationContract v6 = new AllocationContract(
            "AC-6-a", 6, "a", bundle, 0L, null, "test", "optimal");
        assertTrue(runtime.installContract(v6));
        assertEquals(6, runtime.getContract("a").getVersion());
    }

    @Test
    void conservationViolationRejectedBeforeInstall() {
        AgentRuntime runtime = scarceRuntimeWithThreeAgents(scarcePool());
        Map<String, Map<ResourceType, Long>> over = new HashMap<>();
        over.put("a", Map.of(ResourceType.COMPUTE, 40L));
        over.put("b", Map.of(ResourceType.COMPUTE, 40L)); // 80 > capacity 45
        assertThrows(IllegalStateException.class,
            () -> runtime.installContracts(over, "test", "optimal", null));
        assertFalse(runtime.hasContract("a"), "nothing installed on conservation failure");
        assertFalse(runtime.hasContract("b"));
    }
}
