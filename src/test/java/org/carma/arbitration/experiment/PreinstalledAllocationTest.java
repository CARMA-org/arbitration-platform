package org.carma.arbitration.experiment;

import org.yaml.snakeyaml.Yaml;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code preinstalledAllocation} job field lets a mechanism computed outside this
 * harness (the resource-local independent bundle max-min, the separable Leontief
 * relaxation, or the distributed price-mediated Leontief solve) install its precomputed
 * integer bundle through the identical canonical contract path used by every internally
 * computed policy. These tests exercise that path directly: verbatim installation and
 * execution, conservation rejection, and per-cell bound accounting.
 */
class PreinstalledAllocationTest {

    private static final Yaml YAML = new Yaml();

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        return (Map<String, Object>) YAML.load(json);
    }

    private Map<String, Object> task(String id) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id);
        t.put("mandatory", List.of("TEXT_GENERATION"));
        t.put("optional", List.of());
        t.put("quality", 0.8);
        t.put("refinement", 0.0);
        t.put("sloMs", Long.MAX_VALUE);
        return t;
    }

    private Map<String, Object> agent(String id, long[] bundle, long[] lower, long[] upper) {
        String[] res = {"COMPUTE", "MEMORY", "API_CREDITS"};
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", id);
        a.put("archetype", "text");
        Map<String, Object> uw = new LinkedHashMap<>();
        Map<String, Object> lr = new LinkedHashMap<>();
        Map<String, Object> md = new LinkedHashMap<>();
        Map<String, Object> mn = new LinkedHashMap<>();
        Map<String, Object> up = new LinkedHashMap<>();
        for (int j = 0; j < res.length; j++) {
            uw.put(res[j], 1.0 / 3.0);
            lr.put(res[j], 1.0 / 3.0);
            md.put(res[j], bundle[j]);
            mn.put(res[j], lower[j]);
            up.put(res[j], upper[j]);
        }
        a.put("utilWeights", uw);
        a.put("leontiefReq", lr);
        a.put("mandatoryDemand", md);
        a.put("min", mn);
        a.put("upper", up);
        a.put("priority", 1.0);
        a.put("tasks", List.of(task(id + "-t0")));
        return a;
    }

    private Map<String, Object> baseJob(String policy, long[] caps,
                                        List<Map<String, Object>> agents,
                                        List<Map<String, Object>> preinstalled) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("cell", "unit_test");
        job.put("seed", 1);
        job.put("policy", policy);
        job.put("solverPython", "python3");
        job.put("execute", true);
        job.put("fallbackAllowed", false);
        job.put("scenarioHash", "h");
        job.put("workloadHash", "w");
        Map<String, Object> capMap = new LinkedHashMap<>();
        capMap.put("COMPUTE", caps[0]);
        capMap.put("MEMORY", caps[1]);
        capMap.put("API_CREDITS", caps[2]);
        job.put("capacities", capMap);
        Map<String, Object> svc = new LinkedHashMap<>();
        svc.put("TEXT_GENERATION", 100000);
        job.put("services", svc);
        job.put("agents", agents);
        if (preinstalled != null) job.put("preinstalledAllocation", preinstalled);
        return job;
    }

    private Map<String, Object> bundle(long compute, long memory, long api) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("COMPUTE", compute);
        b.put("MEMORY", memory);
        b.put("API_CREDITS", api);
        return b;
    }

    @Test
    @SuppressWarnings("unchecked")
    void preinstalledBundleIsInstalledVerbatimAndExecuted() {
        long[] lower = {1, 1, 1};
        long[] upper = {30, 24, 15};
        List<Map<String, Object>> agents = List.of(
            agent("a0", new long[]{0, 0, 0}, lower, upper),
            agent("a1", new long[]{0, 0, 0}, lower, upper));
        // Enough headroom for one TEXT_GENERATION call (10/8/5) each.
        List<Map<String, Object>> pre = List.of(bundle(11, 9, 6), bundle(11, 9, 6));
        Map<String, Object> out = parse(PlatformMediationHarness.run(
            baseJob("independent_bundle_maxmin", new long[]{30, 24, 15}, agents, pre)));

        assertEquals(Boolean.TRUE, out.get("feasible"));
        assertEquals("preinstalled", out.get("solver_status"));
        assertEquals(0, ((Number) out.get("capacity_violation")).intValue());
        assertEquals(0, ((Number) out.get("bound_violation")).intValue());
        List<Map<String, Object>> recs = (List<Map<String, Object>>) out.get("agents");
        for (Map<String, Object> rec : recs) {
            Map<String, Object> allocated = (Map<String, Object>) rec.get("allocated");
            // Installed bundle equals the supplied bundle exactly (no re-optimization).
            assertEquals(11, ((Number) allocated.get("COMPUTE")).intValue());
            assertEquals(9, ((Number) allocated.get("MEMORY")).intValue());
            assertEquals(6, ((Number) allocated.get("API_CREDITS")).intValue());
            // The single mandatory task ran through the canonical execution path.
            assertEquals(1.0, ((Number) rec.get("completion")).doubleValue(), 1e-9);
            Map<String, Object> charged = (Map<String, Object>) rec.get("charged");
            assertEquals(10, ((Number) charged.get("COMPUTE")).intValue());
        }
    }

    @Test
    void overCapacityPreinstalledAllocationIsRejectedAsInfeasible() {
        long[] lower = {1, 1, 1};
        long[] upper = {30, 24, 15};
        List<Map<String, Object>> agents = List.of(
            agent("a0", new long[]{0, 0, 0}, lower, upper),
            agent("a1", new long[]{0, 0, 0}, lower, upper));
        // Column sum on COMPUTE is 10+10=20 > capacity 15: conservation must reject.
        List<Map<String, Object>> pre = List.of(bundle(10, 8, 5), bundle(10, 8, 5));
        Map<String, Object> out = parse(PlatformHarnessRun("central_diagnostic",
            new long[]{15, 24, 15}, agents, pre));

        assertEquals(Boolean.FALSE, out.get("feasible"));
        assertEquals("infeasible", out.get("solver_status"));
    }

    @Test
    void perCellBoundViolationIsStillAccountedForPreinstalled() {
        long[] lower = {1, 1, 1};
        long[] upper = {8, 24, 15};   // COMPUTE upper 8, below the installed 11
        List<Map<String, Object>> agents = List.of(
            agent("a0", new long[]{0, 0, 0}, lower, upper),
            agent("a1", new long[]{0, 0, 0}, lower, upper));
        List<Map<String, Object>> pre = List.of(bundle(11, 9, 6), bundle(11, 9, 6));
        Map<String, Object> out = parse(PlatformHarnessRun("separable_leontief_relaxation",
            new long[]{30, 24, 15}, agents, pre));

        assertEquals(Boolean.TRUE, out.get("feasible"));
        // Both agents exceed the COMPUTE upper bound of 8: the counter still fires.
        assertTrue(((Number) out.get("bound_violation")).intValue() >= 2);
    }

    private String PlatformHarnessRun(String policy, long[] caps,
                                      List<Map<String, Object>> agents,
                                      List<Map<String, Object>> pre) {
        return PlatformMediationHarness.run(baseJob(policy, caps, agents, pre));
    }
}
