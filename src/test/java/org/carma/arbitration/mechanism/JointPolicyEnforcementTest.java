package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JointPolicyEnforcementTest {

    private ConvexJointArbitrator convex() {
        String python = System.getenv().getOrDefault("SOLVER_PYTHON", "python3");
        return new ConvexJointArbitrator(new PriorityEconomy(), python, Paths.get("scripts/joint_solver.py"));
    }

    private List<Agent> twoAgents(long minCompute, long idealCompute) {
        Agent a1 = new Agent("a1", "a1", Map.of(ResourceType.COMPUTE, 1.0), 100.0);
        a1.setRequest(ResourceType.COMPUTE, minCompute, idealCompute);
        Agent a2 = new Agent("a2", "a2", Map.of(ResourceType.COMPUTE, 1.0), 100.0);
        a2.setRequest(ResourceType.COMPUTE, minCompute, idealCompute);
        return List.of(a1, a2);
    }

    private Map<String, BigDecimal> burns() {
        Map<String, BigDecimal> b = new HashMap<>();
        b.put("a1", BigDecimal.ONE);
        b.put("a2", BigDecimal.ONE);
        return b;
    }

    @Test
    void solverErrorIsExplicitAndFailsClosed() {
        // Non-existent interpreter forces a solver failure. Default policy fails closed.
        ConvexJointArbitrator bad = new ConvexJointArbitrator(
            new PriorityEconomy(), "definitely-not-python-xyz", Paths.get("scripts/joint_solver.py"));
        ResourcePool pool = ResourcePool.ofSingle(ResourceType.COMPUTE, 100);
        assertThrows(RuntimeException.class,
            () -> bad.arbitrate(twoAgents(1, 100), pool, burns()),
            "solver failure must throw, not silently substitute a policy");
    }

    @Test
    void explicitFallbackRecordsRequestedAndActualPolicy() {
        ConvexJointArbitrator bad = new ConvexJointArbitrator(
            new PriorityEconomy(), "definitely-not-python-xyz", Paths.get("scripts/joint_solver.py"))
            .setUseFallbackOnError(true);
        ResourcePool pool = ResourcePool.ofSingle(ResourceType.COMPUTE, 100);
        JointArbitrator.JointAllocationResult r = bad.arbitrate(twoAgents(1, 100), pool, burns());
        assertTrue(r.getMessage().contains("requested=JOINT_LINEAR"), r.getMessage());
        assertTrue(r.getMessage().contains("actual=SEQUENTIAL"), r.getMessage());
    }

    @Test
    void oversubscribedLowerBoundsRejectedBeforeExecution() {
        ConvexJointArbitrator convex = convex();
        assumeTrue(convex.checkDependencies(), "cvxpy solver unavailable; skipping");
        // Two agents each demanding a minimum of 60 on a capacity of 100 -> 120 > 100.
        ResourcePool pool = ResourcePool.ofSingle(ResourceType.COMPUTE, 100);
        JointArbitrator.JointAllocationResult r = convex.arbitrate(twoAgents(60, 100), pool, burns());
        assertFalse(r.isFeasible(), "oversubscribed minimums must be reported infeasible: " + r.getMessage());
    }
}
