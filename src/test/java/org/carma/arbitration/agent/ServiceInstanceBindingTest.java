package org.carma.arbitration.agent;

import org.carma.arbitration.agent.ExampleAgents.NewsSearchAgent;
import org.carma.arbitration.agent.RealisticAgentFramework.AgentRuntime;
import org.carma.arbitration.agent.RealisticAgentFramework.ExecutionContext;
import org.carma.arbitration.agent.RealisticAgentFramework.ServiceResult;
import org.carma.arbitration.mechanism.MockServiceBackend;
import org.carma.arbitration.mechanism.PriorityEconomy;
import org.carma.arbitration.mechanism.ServiceArbitrator;
import org.carma.arbitration.model.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ServiceInstanceBindingTest {

    @Test
    void chargedInstanceIsTheInvokedInstance() {
        ServiceType type = ServiceType.TEXT_SUMMARIZATION;
        ServiceRegistry registry = new ServiceRegistry();
        Map<ResourceType, Long> vecOne = new HashMap<>();
        vecOne.put(ResourceType.COMPUTE, 9L);
        vecOne.put(ResourceType.MEMORY, 9L);
        vecOne.put(ResourceType.API_CREDITS, 9L);
        Map<ResourceType, Long> vecTwo = new HashMap<>();
        vecTwo.put(ResourceType.COMPUTE, 2L);
        vecTwo.put(ResourceType.MEMORY, 1L);
        vecTwo.put(ResourceType.API_CREDITS, 1L);
        registry.register(new AIService.Builder("svc-1", type).resourceRequirements(vecOne).maxCapacity(1).build());
        registry.register(new AIService.Builder("svc-2", type).resourceRequirements(vecTwo).maxCapacity(1).build());

        MockServiceBackend backend = new MockServiceBackend(registry);
        Map<ResourceType, Long> cap = new HashMap<>();
        cap.put(ResourceType.COMPUTE, 100L);
        cap.put(ResourceType.MEMORY, 100L);
        cap.put(ResourceType.API_CREDITS, 100L);
        AgentRuntime runtime = new AgentRuntime.Builder()
            .serviceArbitrator(new ServiceArbitrator(new PriorityEconomy(), registry))
            .serviceRegistry(registry).resourcePool(new ResourcePool(cap))
            .serviceBackend(backend).build();
        runtime.register(new NewsSearchAgent.Builder("a1").topics(List.of("AI")).initialCurrency(10).build());
        Map<String, Map<ResourceType, Long>> alloc = new HashMap<>();
        Map<ResourceType, Long> bundle = new HashMap<>();
        bundle.put(ResourceType.COMPUTE, 100L);
        bundle.put(ResourceType.MEMORY, 100L);
        bundle.put(ResourceType.API_CREDITS, 100L);
        alloc.put("a1", bundle);
        runtime.installContracts(alloc, "test", "optimal", null);

        registry.setServiceAvailable("svc-1", false);

        Map<ServiceType, Integer> slots = new HashMap<>();
        slots.put(type, 8);
        ExecutionContext ctx = runtime.createExecutionContext("a1", slots);
        ServiceResult r = ctx.invokeService(type, Map.of("text", "x"));
        assertTrue(r.isSuccess(), "call should route to the second instance: " + r.getError());

        assertEquals(2L, ctx.getCharged(ResourceType.COMPUTE), "charged the invoked instance's vector");
        assertEquals(1L, ctx.getCharged(ResourceType.MEMORY));
        assertEquals(1L, ctx.getCharged(ResourceType.API_CREDITS));

        assertEquals(1, backend.getInvocations("svc-2").size(), "svc-2 was invoked");
        assertEquals(0, backend.getInvocations("svc-1").size(), "the held instance was not invoked");
        assertEquals(1, registry.get("svc-2").get().getAvailableCapacity(), "svc-2 slot released after the call");
    }
}
