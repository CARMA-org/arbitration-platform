package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JointSolverIntegrationTest {

    private ConvexJointArbitrator arbitrator() {
        String python = System.getenv().getOrDefault("SOLVER_PYTHON", "python3");
        Path script = Paths.get("scripts/joint_solver.py");
        return new ConvexJointArbitrator(new PriorityEconomy(), python, script);
    }

    @Test
    void jointLinearAllocationRespectsCapacity() {
        ConvexJointArbitrator arb = arbitrator();
        assumeTrue(arb.checkDependencies(),
            "cvxpy solver not available on SOLVER_PYTHON; skipping integration test");

        Map<ResourceType, Double> p1 = new HashMap<>();
        p1.put(ResourceType.COMPUTE, 0.9);
        p1.put(ResourceType.MEMORY, 0.1);
        Agent a1 = new Agent("a1", "a1", p1, 100.0);
        a1.setRequest(ResourceType.COMPUTE, 1, 100);
        a1.setRequest(ResourceType.MEMORY, 1, 100);

        Map<ResourceType, Double> p2 = new HashMap<>();
        p2.put(ResourceType.COMPUTE, 0.1);
        p2.put(ResourceType.MEMORY, 0.9);
        Agent a2 = new Agent("a2", "a2", p2, 100.0);
        a2.setRequest(ResourceType.COMPUTE, 1, 100);
        a2.setRequest(ResourceType.MEMORY, 1, 100);

        Map<ResourceType, Long> caps = new HashMap<>();
        caps.put(ResourceType.COMPUTE, 100L);
        caps.put(ResourceType.MEMORY, 100L);
        ResourcePool pool = new ResourcePool(caps);

        Map<String, BigDecimal> commitments = new HashMap<>();
        commitments.put("a1", BigDecimal.ONE);
        commitments.put("a2", BigDecimal.ONE);

        JointArbitrator.JointAllocationResult r =
            arb.arbitrate(Arrays.asList(a1, a2), pool, commitments);

        assertTrue(r.isFeasible(), "expected feasible: " + r.getMessage());
        long compTotal = r.getAllocation("a1", ResourceType.COMPUTE)
            + r.getAllocation("a2", ResourceType.COMPUTE);
        long memTotal = r.getAllocation("a1", ResourceType.MEMORY)
            + r.getAllocation("a2", ResourceType.MEMORY);
        assertTrue(compTotal <= 100, "compute capacity exceeded: " + compTotal);
        assertTrue(memTotal <= 100, "memory capacity exceeded: " + memTotal);
    }
}
