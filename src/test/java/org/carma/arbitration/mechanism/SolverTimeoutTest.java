package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SolverTimeoutTest {

    private final Path hungSolver = Paths.get("experiments/enforcement/fake_solvers/hung_solver.py");

    private List<Agent> twoAgents() {
        Map<ResourceType, Double> prefs = new HashMap<>();
        prefs.put(ResourceType.COMPUTE, 1.0);
        prefs.put(ResourceType.MEMORY, 1.0);
        Agent a = new Agent("a", "a", prefs, 10);
        Agent b = new Agent("b", "b", prefs, 10);
        a.setRequest(ResourceType.COMPUTE, 1, 20);
        a.setRequest(ResourceType.MEMORY, 1, 20);
        b.setRequest(ResourceType.COMPUTE, 1, 20);
        b.setRequest(ResourceType.MEMORY, 1, 20);
        return List.of(a, b);
    }

    private ResourcePool pool() {
        Map<ResourceType, Long> cap = new HashMap<>();
        cap.put(ResourceType.COMPUTE, 20L);
        cap.put(ResourceType.MEMORY, 20L);
        return new ResourcePool(cap);
    }

    @Test
    void hungSolverProcessIsTerminatedByJavaTimeout() {
        ConvexJointArbitrator arb =
            new ConvexJointArbitrator(new PriorityEconomy(), "python3", hungSolver)
                .setTimeoutMillis(1000);

        long start = System.currentTimeMillis();
        assertThrows(RuntimeException.class,
            () -> arb.arbitrate(twoAgents(), pool(), new HashMap<>()));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 8000,
            "Java caller must regain control shortly after the timeout, took " + elapsed + "ms");
    }

    @Test
    void explicitFallbackRecordsRequestedAndActualPolicy() {
        ConvexJointArbitrator arb =
            new ConvexJointArbitrator(new PriorityEconomy(), "python3", hungSolver)
                .setTimeoutMillis(1000)
                .setUseFallbackOnError(true);

        Map<String, BigDecimal> burns = new HashMap<>();
        JointArbitrator.JointAllocationResult r = arb.arbitrate(twoAgents(), pool(), burns);

        assertTrue(r.isFeasible(), "explicit fallback should yield a feasible allocation");
        assertTrue(r.getMessage().contains("requested="),
            "fallback must record the requested policy: " + r.getMessage());
        assertTrue(r.getMessage().contains("actual="),
            "fallback must record the actual policy: " + r.getMessage());
    }
}
