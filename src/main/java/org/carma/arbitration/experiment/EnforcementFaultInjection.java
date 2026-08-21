package org.carma.arbitration.experiment;

import org.carma.arbitration.agent.RealisticAgentFramework.*;
import org.carma.arbitration.mechanism.*;
import org.carma.arbitration.model.*;

import java.math.BigDecimal;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class EnforcementFaultInjection {

    static final class Counters {
        int backendAfterDenial, quotaViolations, capacityViolations,
            partialDeductions, silentFallbacks, incorrectSuccess;
        int trials, operations, expectedSuccesses, observedSuccesses,
            expectedDenials, observedDenials;
        boolean singleShot;

        void denominators(int trials, int operations, int expectedSuccesses, int observedSuccesses) {
            this.trials = trials;
            this.operations = operations;
            this.expectedSuccesses = expectedSuccesses;
            this.observedSuccesses = observedSuccesses;
            this.expectedDenials = operations - expectedSuccesses;
            this.observedDenials = operations - observedSuccesses;
            this.singleShot = trials <= 1;
        }

        Map<String, Object> invariantMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("backend_after_denial", backendAfterDenial);
            m.put("quota_violations", quotaViolations);
            m.put("capacity_violations", capacityViolations);
            m.put("partial_deductions", partialDeductions);
            m.put("silent_fallbacks", silentFallbacks);
            m.put("incorrect_success", incorrectSuccess);
            return m;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trials", trials);
            m.put("operations", operations);
            m.put("expected_successes", expectedSuccesses);
            m.put("observed_successes", observedSuccesses);
            m.put("expected_denials", expectedDenials);
            m.put("observed_denials", observedDenials);
            m.put("single_shot", singleShot);
            m.putAll(invariantMap());
            return m;
        }

        void add(Counters c) {
            backendAfterDenial += c.backendAfterDenial;
            quotaViolations += c.quotaViolations;
            capacityViolations += c.capacityViolations;
            partialDeductions += c.partialDeductions;
            silentFallbacks += c.silentFallbacks;
            incorrectSuccess += c.incorrectSuccess;
        }
    }

    private final String solverPython;
    private final String malformedScript;
    private final String slowScript;
    private final int reps;
    private final List<Map<String, Object>> caseReports = new ArrayList<>();

    EnforcementFaultInjection(String solverPython, String malformedScript, String slowScript, int reps) {
        this.solverPython = solverPython;
        this.malformedScript = malformedScript;
        this.slowScript = slowScript;
        this.reps = reps;
    }

    public static void main(String[] args) {
        String solverPython = args.length > 0 ? args[0] : "python3";
        String malformed = args.length > 1 ? args[1] : "";
        String slow = args.length > 2 ? args[2] : "";
        int reps = args.length > 3 ? Integer.parseInt(args[3]) : 100;
        EnforcementFaultInjection fx = new EnforcementFaultInjection(solverPython, malformed, slow, reps);
        System.out.println(fx.run());
    }

    private ServiceRegistry registry(ServiceType type, int cap) {
        ServiceRegistry r = new ServiceRegistry();
        r.register(new AIService.Builder("svc-" + type.name(), type).maxCapacity(cap).build());
        return r;
    }

    private ExecutionContext ctx(Map<ResourceType, Long> alloc, ServiceType type,
                                 ServiceRegistry registry, MockServiceBackend backend) {
        Map<ServiceType, Integer> slots = new HashMap<>();
        slots.put(type, 1);
        return new ExecutionContext(alloc, slots, registry, backend, s -> {}, 1000L);
    }

    String run() {
        Counters totals = new Counters();

        record("negative_resource_requests", negativeResourceRequests(), totals);
        record("repeated_over_quota_calls", repeatedOverQuota(), totals);
        record("concurrent_over_quota_calls", concurrentOverQuota(), totals);
        record("duplicate_calls_each_charged", duplicateRequests(), totals);
        record("stale_context_execution", staleReplay(), totals);
        record("invalid_service_composition", invalidComposition(), totals);
        record("cyclic_service_composition", cyclicComposition(), totals);
        record("malformed_solver_output", craftedSolver(malformedScript, "malformed"), totals);
        record("hung_solver_process", craftedSolver(slowScript, "hung"), totals);
        record("oversubscribed_minimums", oversubscribedMinimums(), totals);
        record("one_exhausted_resource", oneExhaustedResource(), totals);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repeated_case_trials", reps);
        out.put("cases", caseReports);
        out.put("totals", totals.invariantMap());
        boolean allZero = totals.backendAfterDenial == 0 && totals.quotaViolations == 0
            && totals.capacityViolations == 0 && totals.partialDeductions == 0
            && totals.silentFallbacks == 0 && totals.incorrectSuccess == 0;
        out.put("all_invariants_zero", allZero);
        return toJson(out);
    }

    private void record(String name, Counters c, Counters totals) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("case", name);
        rec.putAll(c.toMap());
        caseReports.add(rec);
        totals.add(c);
    }

    // ====================================================================
    // Runtime enforcement cases
    // ====================================================================

    private Counters negativeResourceRequests() {
        Counters c = new Counters();
        ServiceType type = ServiceType.TEXT_GENERATION;
        ServiceRegistry reg = registry(type, 5);
        MockServiceBackend backend = new MockServiceBackend(reg);
        Map<ResourceType, Long> alloc = new HashMap<>();
        alloc.put(ResourceType.API_CREDITS, 100L);
        for (int r = 0; r < reps; r++) {
            ExecutionContext ctx = ctx(alloc, type, reg, backend);
            long before = ctx.getConsumedResource(ResourceType.API_CREDITS);
            Map<ResourceType, Long> neg = new HashMap<>();
            neg.put(ResourceType.API_CREDITS, -10L);
            ResourceType off = ctx.tryConsumeBundle(neg);
            if (off == null) c.incorrectSuccess++;                  // negative accepted
            if (ctx.getConsumedResource(ResourceType.API_CREDITS) != before) c.partialDeductions++;
            if (!ctx.tryConsumeResource(ResourceType.API_CREDITS, -5)) { /* correctly rejected */ }
            else c.incorrectSuccess++;
            if (ctx.getConsumedResource(ResourceType.API_CREDITS) != before) c.partialDeductions++;
        }
        c.denominators(reps, reps * 2, 0, c.incorrectSuccess);
        return c;
    }

    private Counters repeatedOverQuota() {
        Counters c = new Counters();
        ServiceType type = ServiceType.TEXT_GENERATION; // API_CREDITS 5 per call
        ServiceRegistry reg = registry(type, 1000);
        MockServiceBackend backend = new MockServiceBackend(reg, MockServiceBackend.MockConfig.fast());
        Map<ResourceType, Long> alloc = new HashMap<>();
        alloc.put(ResourceType.COMPUTE, 100000L);
        alloc.put(ResourceType.MEMORY, 100000L);
        alloc.put(ResourceType.API_CREDITS, 12L); // affords 2 calls
        ExecutionContext ctx = ctx(alloc, type, reg, backend);
        for (int i = 0; i < 20; i++) {
            int backendBefore = backend.getInvocationCount();
            ServiceResult r = ctx.invokeService(type, Map.of("prompt", "x"));
            int backendAfter = backend.getInvocationCount();
            if (!r.isSuccess() && backendAfter != backendBefore) c.backendAfterDenial++;
        }
        if (ctx.getCharged(ResourceType.API_CREDITS) > alloc.get(ResourceType.API_CREDITS)) c.quotaViolations++;
        if (ctx.getCharged(ResourceType.API_CREDITS) % 5 != 0) c.partialDeductions++;
        c.denominators(1, 20, 2, (int) (ctx.getCharged(ResourceType.API_CREDITS) / 5));
        return c;
    }

    private Counters concurrentOverQuota() {
        Counters c = new Counters();
        ServiceType type = ServiceType.TEXT_GENERATION;
        ServiceRegistry reg = registry(type, 100000);
        MockServiceBackend backend = new MockServiceBackend(reg, MockServiceBackend.MockConfig.fast());
        long credits = 5L * 20; // exactly 20 calls affordable
        Map<ResourceType, Long> alloc = new HashMap<>();
        alloc.put(ResourceType.COMPUTE, 100000000L);
        alloc.put(ResourceType.MEMORY, 100000000L);
        alloc.put(ResourceType.API_CREDITS, credits);
        ExecutionContext ctx = ctx(alloc, type, reg, backend);
        AtomicInteger successes = new AtomicInteger();
        try {
            ExecutorService pool = Executors.newFixedThreadPool(32);
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < reps * 4; i++) {
                fs.add(pool.submit(() -> {
                    if (ctx.invokeService(type, Map.of("prompt", "x")).isSuccess()) successes.incrementAndGet();
                }));
            }
            for (Future<?> f : fs) f.get();
            pool.shutdown();
        } catch (Exception e) {
            c.incorrectSuccess++;
        }
        if (ctx.getCharged(ResourceType.API_CREDITS) > credits) c.quotaViolations++;
        if (successes.get() * 5L != ctx.getCharged(ResourceType.API_CREDITS)) c.partialDeductions++;
        if (backend.getInvocationCount() != successes.get()) c.backendAfterDenial++;
        c.denominators(1, reps * 4, 20, successes.get());
        return c;
    }

    private Counters duplicateRequests() {
        Counters c = new Counters();
        ServiceType type = ServiceType.TEXT_GENERATION;
        ServiceRegistry reg = registry(type, 100000);
        MockServiceBackend backend = new MockServiceBackend(reg, MockServiceBackend.MockConfig.fast());
        Map<ResourceType, Long> alloc = new HashMap<>();
        alloc.put(ResourceType.COMPUTE, 100L);
        alloc.put(ResourceType.MEMORY, 100L);
        alloc.put(ResourceType.API_CREDITS, 100L);
        ExecutionContext ctx = ctx(alloc, type, reg, backend);
        Map<String, Object> identical = Map.of("prompt", "same");
        int success = 0;
        for (int i = 0; i < 50; i++) {
            if (ctx.invokeService(type, identical).isSuccess()) success++;
        }
        // Each duplicate is a real call charged against quota; none may exceed it.
        if (ctx.getCharged(ResourceType.COMPUTE) > alloc.get(ResourceType.COMPUTE)) c.quotaViolations++;
        if (ctx.getCharged(ResourceType.API_CREDITS) > alloc.get(ResourceType.API_CREDITS)) c.quotaViolations++;
        if (success != backend.getInvocationCount()) c.backendAfterDenial++;
        long computePerCall = ServiceType.TEXT_GENERATION.getDefaultResourceRequirements()
            .get(ResourceType.COMPUTE);
        int affordable = (int) (alloc.get(ResourceType.COMPUTE) / computePerCall);
        c.denominators(1, 50, affordable, success);
        return c;
    }

    private Counters staleReplay() {
        Counters c = new Counters();
        int staleAcceptedCount = 0;
        for (int r = 0; r < reps; r++) {
            AgentRuntime runtime = new AgentRuntime.Builder()
                .serviceArbitrator(new ServiceArbitrator(new PriorityEconomy(), new ServiceRegistry()))
                .serviceRegistry(new ServiceRegistry())
                .resourcePool(ResourcePool.ofSingle(ResourceType.COMPUTE, 100))
                .build();
            Map<ResourceType, Long> bundle = Map.of(ResourceType.COMPUTE, 5L);
            runtime.installContract(new AllocationContract("AC5", 5, "a", bundle, 0, null, "p", "optimal"));
            boolean staleAccepted = runtime.installContract(
                new AllocationContract("AC3", 3, "a", bundle, 0, null, "p", "optimal"));
            if (staleAccepted) { c.incorrectSuccess++; staleAcceptedCount++; }
            if (runtime.getContract("a").getVersion() != 5) c.incorrectSuccess++;
        }
        c.denominators(reps, reps, 0, staleAcceptedCount);
        return c;
    }

    private Counters invalidComposition() {
        Counters c = new Counters();
        ServiceComposition invalid = new ServiceComposition.Builder("bad")
            .addNode("a", ServiceType.TEXT_TO_SPEECH)
            .addNode("b", ServiceType.OCR)
            .connect("a", "b", ServiceType.DataType.AUDIO)
            .build();
        try {
            new ServiceRegistry().registerComposition(invalid);
            c.incorrectSuccess++; // should have thrown
        } catch (IllegalArgumentException ok) { /* rejected */ }
        c.denominators(1, 1, 0, c.incorrectSuccess);
        return c;
    }

    private Counters cyclicComposition() {
        Counters c = new Counters();
        ServiceComposition cyclic = new ServiceComposition.Builder("cyc")
            .addNode("a", ServiceType.TEXT_GENERATION)
            .addNode("b", ServiceType.TEXT_SUMMARIZATION)
            .connect("a", "b", ServiceType.DataType.TEXT)
            .connect("b", "a", ServiceType.DataType.TEXT)
            .build();
        try {
            new ServiceRegistry().registerComposition(cyclic);
            c.incorrectSuccess++;
        } catch (IllegalArgumentException ok) { /* rejected */ }
        c.denominators(1, 1, 0, c.incorrectSuccess);
        return c;
    }

    private Counters craftedSolver(String script, String kind) {
        Counters c = new Counters();
        if (script == null || script.isEmpty()) return c;
        List<Agent> agents = new ArrayList<>();
        Agent a1 = new Agent("a1", "a1", Map.of(ResourceType.COMPUTE, 1.0), 0.0);
        a1.setRequest(ResourceType.COMPUTE, 1, 50);
        Agent a2 = new Agent("a2", "a2", Map.of(ResourceType.COMPUTE, 1.0), 0.0);
        a2.setRequest(ResourceType.COMPUTE, 1, 50);
        agents.add(a1); agents.add(a2);
        ResourcePool pool = ResourcePool.ofSingle(ResourceType.COMPUTE, 100);
        Map<String, BigDecimal> burns = new HashMap<>();
        burns.put("a1", BigDecimal.ONE); burns.put("a2", BigDecimal.ONE);

        ConvexJointArbitrator failClosed = new ConvexJointArbitrator(
            new PriorityEconomy(), solverPython, Paths.get(script)).setTimeoutMillis(2000);
        try {
            JointArbitrator.JointAllocationResult r = failClosed.arbitrate(agents, pool, burns);
            if (r.isFeasible()) c.incorrectSuccess++;
        } catch (RuntimeException expected) { /* fail closed */ }

        ConvexJointArbitrator withFallback = new ConvexJointArbitrator(
            new PriorityEconomy(), solverPython, Paths.get(script))
            .setTimeoutMillis(2000).setUseFallbackOnError(true);
        JointArbitrator.JointAllocationResult fb = withFallback.arbitrate(agents, pool, burns);
        if (fb.isFeasible()) {
            String msg = fb.getMessage();
            if (!(msg.contains("requested=") && msg.contains("actual="))) c.silentFallbacks++;
        }
        int expectedFallbackSuccess = "hung".equals(kind) ? 1 : 0;
        c.denominators(1, 2, expectedFallbackSuccess, c.incorrectSuccess + (fb.isFeasible() ? 1 : 0));
        return c;
    }

    private Counters oversubscribedMinimums() {
        Counters c = new Counters();
        ConvexJointArbitrator arb = new ConvexJointArbitrator(
            new PriorityEconomy(), solverPython, Paths.get("scripts/joint_solver.py"));
        List<Agent> agents = new ArrayList<>();
        Agent a1 = new Agent("a1", "a1", Map.of(ResourceType.COMPUTE, 1.0), 0.0);
        a1.setRequest(ResourceType.COMPUTE, 60, 100);
        Agent a2 = new Agent("a2", "a2", Map.of(ResourceType.COMPUTE, 1.0), 0.0);
        a2.setRequest(ResourceType.COMPUTE, 60, 100);
        agents.add(a1); agents.add(a2);
        ResourcePool pool = ResourcePool.ofSingle(ResourceType.COMPUTE, 100);
        Map<String, BigDecimal> burns = new HashMap<>();
        burns.put("a1", BigDecimal.ONE); burns.put("a2", BigDecimal.ONE);
        JointArbitrator.JointAllocationResult r = arb.arbitrate(agents, pool, burns);
        if (r.isFeasible()) c.incorrectSuccess++;
        c.denominators(1, 1, 0, c.incorrectSuccess);
        return c;
    }

    private Counters oneExhaustedResource() {
        Counters c = new Counters();
        ServiceType type = ServiceType.TEXT_GENERATION; // needs COMPUTE10 MEMORY8 API_CREDITS5
        ServiceRegistry reg = registry(type, 100000);
        MockServiceBackend backend = new MockServiceBackend(reg, MockServiceBackend.MockConfig.fast());
        for (int r = 0; r < reps; r++) {
            Map<ResourceType, Long> alloc = new HashMap<>();
            alloc.put(ResourceType.COMPUTE, 100L);
            alloc.put(ResourceType.MEMORY, 4L);   // exhausted (need 8)
            alloc.put(ResourceType.API_CREDITS, 100L);
            ExecutionContext ctx = ctx(alloc, type, reg, backend);
            int backendBefore = backend.getInvocationCount();
            ServiceResult res = ctx.invokeService(type, Map.of("prompt", "x"));
            if (res.isSuccess()) c.incorrectSuccess++;
            if (backend.getInvocationCount() != backendBefore) c.backendAfterDenial++;
            if (ctx.getCharged(ResourceType.COMPUTE) != 0 || ctx.getCharged(ResourceType.MEMORY) != 0
                || ctx.getCharged(ResourceType.API_CREDITS) != 0) c.partialDeductions++;
        }
        c.denominators(reps, reps, 0, c.incorrectSuccess);
        return c;
    }

    // ====================================================================
    // JSON output
    // ====================================================================

    @SuppressWarnings("unchecked")
    private static String toJson(Object v) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, v);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeJson(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String) {
            sb.append('"').append(((String) v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v.toString());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeJson(sb, e.getKey());
                sb.append(':');
                writeJson(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                writeJson(sb, o);
            }
            sb.append(']');
        } else {
            writeJson(sb, v.toString());
        }
    }
}
