package org.carma.arbitration.experiment;

import org.carma.arbitration.agent.RealisticAgentFramework.AgentRuntime;
import org.carma.arbitration.mechanism.*;
import org.carma.arbitration.model.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DeclarationRuntimePathTest {

    static class CapturingArbitrator implements JointArbitrator {
        final JointArbitrator delegate;
        final Map<String, Agent> captured = new LinkedHashMap<>();

        CapturingArbitrator(JointArbitrator delegate) { this.delegate = delegate; }

        @Override
        public JointAllocationResult arbitrate(
                ContentionDetector.ContentionGroup group, Map<String, BigDecimal> burns) {
            for (Agent a : group.getAgents()) captured.put(a.getId(), a);
            return delegate.arbitrate(group, burns);
        }

        @Override
        public JointAllocationResult arbitrate(List<Agent> agents, ResourcePool pool, Map<String, BigDecimal> burns) {
            return delegate.arbitrate(agents, pool, burns);
        }
    }

    private ConvexJointArbitrator convex() {
        String python = System.getenv().getOrDefault("SOLVER_PYTHON", "python3");
        return new ConvexJointArbitrator(new PriorityEconomy(), python, Paths.get("scripts/joint_solver.py"));
    }

    private ResourcePool scarcePool() {
        Map<ResourceType, Long> cap = new HashMap<>();
        cap.put(ResourceType.COMPUTE, 40L);
        cap.put(ResourceType.MEMORY, 40L);
        cap.put(ResourceType.API_CREDITS, 20L);
        return new ResourcePool(cap);
    }

    private AgentRuntime runtime(ServiceRegistry registry, ResourcePool pool) {
        return new AgentRuntime.Builder()
            .serviceArbitrator(new ServiceArbitrator(new PriorityEconomy(), registry))
            .serviceRegistry(registry).resourcePool(pool)
            .serviceBackend(new MockServiceBackend(registry)).build();
    }

    private TaskAgent agent(String id, UtilityDeclaration decl) {
        List<TaskAgent.Task> tasks = List.of(new TaskAgent.Task(
            id + "-t", List.of(ServiceType.TEXT_GENERATION), List.of(), 0.8, 0.0, Long.MAX_VALUE));
        Map<ResourceType, Double> prefs = new HashMap<>();
        prefs.put(ResourceType.COMPUTE, 0.5);
        prefs.put(ResourceType.MEMORY, 0.3);
        prefs.put(ResourceType.API_CREDITS, 0.2);
        return new TaskAgent.Builder(id).preferences(prefs).tasks(tasks)
            .utilityDeclaration(decl)
            .declaredMinimum(ResourceType.COMPUTE, 1).declaredUpperBound(ResourceType.COMPUTE, 25)
            .declaredMinimum(ResourceType.MEMORY, 1).declaredUpperBound(ResourceType.MEMORY, 25)
            .declaredMinimum(ResourceType.API_CREDITS, 1).declaredUpperBound(ResourceType.API_CREDITS, 12)
            .operatorPriority(1.0).build();
    }

    @Test
    void declaredFamilyAndBoundsArriveAtArbitrator() {
        assumeTrue(convex().checkDependencies(), "solver unavailable");
        Map<ResourceType, Double> leoReq = new HashMap<>();
        leoReq.put(ResourceType.COMPUTE, 0.5);
        leoReq.put(ResourceType.MEMORY, 0.3);
        leoReq.put(ResourceType.API_CREDITS, 0.2);

        ServiceRegistry registry = new ServiceRegistry();
        registry.register(new AIService.Builder("text-gen", ServiceType.TEXT_GENERATION).maxCapacity(50).build());
        AgentRuntime runtime = runtime(registry, scarcePool());
        runtime.register(agent("a1", UtilityDeclaration.leontief(leoReq)));
        runtime.register(agent("a2", UtilityDeclaration.cobbDouglas(leoReq)));

        CapturingArbitrator spy = new CapturingArbitrator(convex());
        runtime.runArbitration(new ContentionDetector(), spy, "joint_test", null);

        assertEquals(UtilityDeclaration.Family.LEONTIEF, spy.captured.get("a1").getUtilityDeclaration().getFamily());
        assertEquals(UtilityDeclaration.Family.COBB_DOUGLAS, spy.captured.get("a2").getUtilityDeclaration().getFamily());
        assertEquals(1L, spy.captured.get("a1").getMinimum(ResourceType.COMPUTE));
        assertEquals(25L, spy.captured.get("a1").getIdeal(ResourceType.COMPUTE));
        assertEquals(12L, spy.captured.get("a1").getIdeal(ResourceType.API_CREDITS));
    }

    @Test
    void changingOnlyTheDeclarationChangesTheInstalledAllocation() {
        assumeTrue(convex().checkDependencies(), "solver unavailable");
        Map<ResourceType, Double> weights = new HashMap<>();
        weights.put(ResourceType.COMPUTE, 0.5);
        weights.put(ResourceType.MEMORY, 0.3);
        weights.put(ResourceType.API_CREDITS, 0.2);

        ServiceRegistry r1 = new ServiceRegistry();
        r1.register(new AIService.Builder("text-gen", ServiceType.TEXT_GENERATION).maxCapacity(50).build());
        AgentRuntime linear = runtime(r1, scarcePool());
        linear.register(agent("a1", UtilityDeclaration.linear(weights)));
        linear.register(agent("a2", UtilityDeclaration.linear(weights)));
        linear.runArbitration(new ContentionDetector(), convex(), "joint_linear", null);
        Map<ResourceType, Long> linA1 = linear.getContract("a1").getBundle();

        ServiceRegistry r2 = new ServiceRegistry();
        r2.register(new AIService.Builder("text-gen", ServiceType.TEXT_GENERATION).maxCapacity(50).build());
        AgentRuntime leontief = runtime(r2, scarcePool());
        leontief.register(agent("a1", UtilityDeclaration.leontief(weights)));
        leontief.register(agent("a2", UtilityDeclaration.leontief(weights)));
        leontief.runArbitration(new ContentionDetector(), convex(), "joint_leontief", null);
        Map<ResourceType, Long> leoA1 = leontief.getContract("a1").getBundle();

        assertNotEquals(linA1, leoA1,
            "changing only the utility declaration must change the installed allocation");
    }
}
