package org.carma.arbitration.experiment;

import org.carma.arbitration.agent.RealisticAgentFramework.*;
import org.carma.arbitration.mechanism.*;
import org.carma.arbitration.model.*;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Paths;
import java.util.*;

/**
 * Canonical-runtime experiment harness for platform mediation.
 *
 * Reads a single job (JSON on stdin), computes an allocation under the requested
 * policy, installs it as versioned contracts, executes each agent's externally
 * defined task queue through the constrained-execution path, and writes a
 * per-run metrics record (JSON on stdout).
 *
 * The joint policy is produced by {@link ConvexJointArbitrator}; the comparison
 * policies (equal, DRF, separable water-filling) are pure allocation rules. Every
 * policy is installed and executed through the same runtime path, so only the
 * joint policy ever reaches the Python solver, and only through the arbitrator.
 */
public class PlatformMediationHarness {

    private static final double EPS = 1e-6;
    private static final double BASE_WEIGHT = 10.0;

    public static void main(String[] args) throws Exception {
        // Reads one JSON job per line (JSONL) and emits one JSON result per line,
        // so a whole sweep can share a single JVM.
        Yaml yaml = new Yaml();
        try (Scanner sc = new Scanner(System.in)) {
            while (sc.hasNextLine()) {
                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> job = (Map<String, Object>) yaml.load(line);
                    System.out.println(run(job));
                } catch (Exception e) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                    System.out.println(toJson(err));
                }
                System.out.flush();
            }
        }
    }

    @SuppressWarnings("unchecked")
    static String run(Map<String, Object> job) {
        String cell = str(job.get("cell"), "");
        long seed = lng(job.get("seed"), 0);
        String policy = str(job.get("policy"), "equal");
        double gamma = dbl(job.get("gamma"), 1.0);
        String solverPython = str(job.get("solverPython"), "python3");

        Map<String, Object> capsRaw = (Map<String, Object>) job.get("capacities");
        List<ResourceType> resources = new ArrayList<>();
        for (String k : capsRaw.keySet()) resources.add(ResourceType.valueOf(k));
        resources.sort(Comparator.comparingInt(ResourceType::ordinal));
        int m = resources.size();
        long[] cap = new long[m];
        Map<ResourceType, Long> capMap = new LinkedHashMap<>();
        for (int j = 0; j < m; j++) {
            cap[j] = lng(capsRaw.get(resources.get(j).name()), 0);
            capMap.put(resources.get(j), cap[j]);
        }

        List<Map<String, Object>> agentSpecs = (List<Map<String, Object>>) job.get("agents");
        int n = agentSpecs.size();
        String[] ids = new String[n];
        double[][] W = new double[n][m];
        long[][] lower = new long[n][m];
        long[][] upper = new long[n][m];
        double[] priority = new double[n];
        double[] c = new double[n];
        for (int i = 0; i < n; i++) {
            Map<String, Object> a = agentSpecs.get(i);
            ids[i] = str(a.get("id"), "agent-" + i);
            Map<String, Object> prefs = (Map<String, Object>) a.get("prefs");
            Map<String, Object> mn = (Map<String, Object>) a.get("min");
            Map<String, Object> up = (Map<String, Object>) a.get("upper");
            for (int j = 0; j < m; j++) {
                String rn = resources.get(j).name();
                W[i][j] = dbl(prefs.get(rn), 0.0);
                lower[i][j] = lng(mn.get(rn), 0);
                upper[i][j] = lng(up.get(rn), 0);
            }
            priority[i] = dbl(a.get("priority"), 0.0);
            c[i] = BASE_WEIGHT + priority[i];
        }

        // Compute allocation under the requested policy (allocation latency measured).
        long t0 = System.nanoTime();
        long[][] alloc;
        String solverStatus;
        boolean feasible = true;
        String message = "";
        if (policy.equals("joint")) {
            Object[] r = jointAllocation(ids, W, lower, upper, priority, capMap, resources, solverPython);
            alloc = (long[][]) r[0];
            feasible = (boolean) r[1];
            solverStatus = (String) r[2];
            message = (String) r[3];
        } else if (policy.equals("equal")) {
            alloc = separable(ids, W, lower, upper, c, cap, 0.0, true);
            solverStatus = "equal";
        } else if (policy.equals("drf")) {
            alloc = drf(W, lower, upper, cap, upper);
            solverStatus = "drf";
        } else if (policy.equals("separable")) {
            alloc = separable(ids, W, lower, upper, c, cap, gamma, false);
            solverStatus = "separable_gamma=" + gamma;
        } else if (policy.equals("given")) {
            // Execute a precomputed allocation through the canonical runtime.
            @SuppressWarnings("unchecked")
            Map<String, Object> given = (Map<String, Object>) job.get("allocation");
            alloc = new long[n][m];
            for (int i = 0; i < n; i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> b = (Map<String, Object>) given.get(ids[i]);
                for (int j = 0; j < m; j++) {
                    alloc[i][j] = b == null ? 0 : lng(b.get(resources.get(j).name()), 0);
                }
            }
            solverStatus = "given";
        } else {
            throw new IllegalArgumentException("unknown policy: " + policy);
        }
        long allocLatencyMs = (System.nanoTime() - t0) / 1_000_000;

        if (!feasible) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cell", cell); out.put("seed", seed); out.put("policy", policy);
            out.put("gamma", gamma); out.put("feasible", false);
            out.put("message", message);
            return toJson(out);
        }

        // Invariant checks on the allocation itself (should be zero).
        int capacityViolation = 0, boundViolation = 0;
        for (int j = 0; j < m; j++) {
            long col = 0;
            for (int i = 0; i < n; i++) {
                col += alloc[i][j];
                if (alloc[i][j] < lower[i][j] || alloc[i][j] > upper[i][j]) boundViolation++;
            }
            if (col > cap[j]) capacityViolation++;
        }

        double declaredWelfare = 0;
        for (int i = 0; i < n; i++) {
            double phi = 0;
            for (int j = 0; j < m; j++) phi += W[i][j] * alloc[i][j];
            declaredWelfare += c[i] * Math.log(Math.max(phi, EPS));
        }

        // Calibration path: allocation only, no task execution.
        boolean execute = bool(job.get("execute"), true);
        if (!execute) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cell", cell); out.put("seed", seed); out.put("policy", policy);
            out.put("gamma", gamma); out.put("feasible", true);
            out.put("solver_status", solverStatus);
            out.put("allocation_latency_ms", allocLatencyMs);
            out.put("declared_welfare", declaredWelfare);
            out.put("capacity_violation", capacityViolation);
            out.put("bound_violation", boundViolation);
            return toJson(out);
        }

        // Build runtime and execute every agent's task queue through it.
        Map<String, Object> svcCaps = (Map<String, Object>) job.getOrDefault("services", new HashMap<>());
        ServiceRegistry registry = new ServiceRegistry();
        for (Map.Entry<String, Object> e : svcCaps.entrySet()) {
            registry.register(new AIService.Builder("svc-" + e.getKey(), ServiceType.valueOf(e.getKey()))
                .maxCapacity((int) lng(e.getValue(), 100000)).build());
        }
        ResourcePool pool = new ResourcePool(capMap);
        MockServiceBackend backend = new MockServiceBackend(registry, MockServiceBackend.MockConfig.fast());
        AgentRuntime runtime = new AgentRuntime.Builder()
            .serviceArbitrator(new ServiceArbitrator(new PriorityEconomy(), registry))
            .serviceRegistry(registry)
            .resourcePool(pool)
            .serviceBackend(backend)
            .build();

        Map<ResourceType, Double> prefMapDefault = new HashMap<>();
        List<TaskAgent> taskAgents = new ArrayList<>();
        Map<String, Map<ResourceType, Long>> allocMap = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> a = agentSpecs.get(i);
            List<Map<String, Object>> taskSpecs = (List<Map<String, Object>>) a.get("tasks");
            List<TaskAgent.Task> tasks = new ArrayList<>();
            for (Map<String, Object> ts : taskSpecs) {
                tasks.add(new TaskAgent.Task(
                    str(ts.get("id"), "t"),
                    serviceList((List<Object>) ts.get("mandatory")),
                    serviceList((List<Object>) ts.get("optional")),
                    dbl(ts.get("quality"), 0.5),
                    dbl(ts.get("refinement"), 0.2),
                    lng(ts.get("sloMs"), Long.MAX_VALUE)));
            }
            Map<ResourceType, Double> prefs = new HashMap<>();
            for (int j = 0; j < m; j++) prefs.put(resources.get(j), W[i][j]);
            TaskAgent agent = new TaskAgent.Builder(ids[i]).preferences(prefs).tasks(tasks).build();
            runtime.register(agent);
            taskAgents.add(agent);

            Map<ResourceType, Long> bundle = new LinkedHashMap<>();
            for (int j = 0; j < m; j++) bundle.put(resources.get(j), alloc[i][j]);
            allocMap.put(ids[i], bundle);
        }

        runtime.installContracts(allocMap, policy, solverStatus, null);

        // Execute each agent through the runtime (canonical constrained execution).
        List<Map<String, Object>> agentRecords = new ArrayList<>();
        long[] chargedTotal = new long[m];
        int backendTotal = 0, blockedTotal = 0;
        double prioSloWeighted = 0, prioSum = 0;
        for (int i = 0; i < n; i++) {
            runtime.invokeAgent(ids[i], "run-all");
            TaskAgent agent = taskAgents.get(i);
            ExecutionContext ctx = runtime.getLastExecutionContext(ids[i]);
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("id", ids[i]);
            rec.put("priority", priority[i]);
            rec.put("tasks_total", agent.getTasksTotal());
            rec.put("tasks_done", agent.getTasksDone());
            rec.put("completion", agent.getCompletion());
            rec.put("quality", agent.getMeanQuality());
            rec.put("slo", agent.getSloAttainment());
            int backendCalls = ctx != null ? ctx.getBackendInvocations() : 0;
            int blocked = ctx != null ? ctx.getBlockedCalls() : 0;
            rec.put("backend_calls", backendCalls);
            rec.put("blocked_calls", blocked);
            Map<String, Object> charged = new LinkedHashMap<>();
            for (int j = 0; j < m; j++) {
                long ch = ctx != null ? ctx.getCharged(resources.get(j)) : 0;
                charged.put(resources.get(j).name(), ch);
                chargedTotal[j] += ch;
            }
            rec.put("charged", charged);
            agentRecords.add(rec);
            backendTotal += backendCalls;
            blockedTotal += blocked;
            prioSloWeighted += c[i] * agent.getSloAttainment();
            prioSum += c[i];
        }

        Map<String, Object> util = new LinkedHashMap<>();
        for (int j = 0; j < m; j++) {
            util.put(resources.get(j).name(), cap[j] > 0 ? (double) chargedTotal[j] / cap[j] : 0.0);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cell", cell);
        out.put("seed", seed);
        out.put("policy", policy);
        out.put("gamma", gamma);
        out.put("feasible", true);
        out.put("solver_status", solverStatus);
        out.put("allocation_latency_ms", allocLatencyMs);
        out.put("declared_welfare", declaredWelfare);
        out.put("capacity_violation", capacityViolation);
        out.put("bound_violation", boundViolation);
        out.put("backend_calls_total", backendTotal);
        out.put("blocked_calls_total", blockedTotal);
        out.put("priority_weighted_slo", prioSum > 0 ? prioSloWeighted / prioSum : 0.0);
        out.put("utilization", util);
        out.put("agents", agentRecords);
        return toJson(out);
    }

    // ====================================================================
    // Allocation policies
    // ====================================================================

    /** Joint WPF via the canonical ConvexJointArbitrator (the only solver path). */
    private static Object[] jointAllocation(
            String[] ids, double[][] W, long[][] lower, long[][] upper, double[] priority,
            Map<ResourceType, Long> capMap, List<ResourceType> resources, String solverPython) {
        int n = ids.length, m = resources.size();
        List<Agent> agents = new ArrayList<>();
        Map<String, java.math.BigDecimal> burns = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Map<ResourceType, Double> prefs = new HashMap<>();
            for (int j = 0; j < m; j++) prefs.put(resources.get(j), W[i][j]);
            Agent a = new Agent(ids[i], ids[i], prefs, 0.0);
            for (int j = 0; j < m; j++) a.setRequest(resources.get(j), lower[i][j], upper[i][j]);
            agents.add(a);
            burns.put(ids[i], java.math.BigDecimal.valueOf(priority[i]));
        }
        ResourcePool pool = new ResourcePool(capMap);
        ConvexJointArbitrator arb = new ConvexJointArbitrator(
            new PriorityEconomy(), solverPython, Paths.get("scripts/joint_solver.py"));
        JointArbitrator.JointAllocationResult r = arb.arbitrate(agents, pool, burns);
        long[][] alloc = new long[n][m];
        if (r.isFeasible()) {
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < m; j++) {
                    alloc[i][j] = r.getAllocation(ids[i], resources.get(j));
                }
            }
        }
        return new Object[]{alloc, r.isFeasible(), r.isFeasible() ? "optimal" : "infeasible", r.getMessage()};
    }

    /** Separable water-filling: per resource, bounded-proportional on score c*W^gamma. */
    private static long[][] separable(String[] ids, double[][] W, long[][] lower, long[][] upper,
                                      double[] c, long[] cap, double gamma, boolean equal) {
        int n = ids.length, m = cap.length;
        double[][] cont = new double[n][m];
        for (int j = 0; j < m; j++) {
            double[] scores = new double[n];
            long[] lo = new long[n], up = new long[n];
            for (int i = 0; i < n; i++) {
                scores[i] = equal ? 1.0 : c[i] * Math.pow(Math.max(W[i][j], EPS), gamma);
                lo[i] = lower[i][j];
                up[i] = upper[i][j];
            }
            double[] col = boundedProportional(scores, lo, up, cap[j]);
            for (int i = 0; i < n; i++) cont[i][j] = col[i];
        }
        return roundSafe(cont, lower, upper, cap);
    }

    /** Dominant Resource Fairness for fixed demand bundles (the agents' upper bundles). */
    private static long[][] drf(double[][] W, long[][] lower, long[][] upper, long[] cap, long[][] demand) {
        int n = demand.length, m = cap.length;
        double[] mI = new double[n];
        for (int i = 0; i < n; i++) {
            double mx = 0;
            for (int j = 0; j < m; j++) {
                if (cap[j] > 0) mx = Math.max(mx, (double) demand[i][j] / cap[j]);
            }
            mI[i] = mx <= 0 ? 1.0 : mx;
        }
        double s = Double.MAX_VALUE;
        for (int j = 0; j < m; j++) {
            double denom = 0;
            for (int i = 0; i < n; i++) denom += demand[i][j] / mI[i];
            if (denom > 0) s = Math.min(s, cap[j] / denom);
        }
        if (s == Double.MAX_VALUE) s = 0;
        double[][] cont = new double[n][m];
        for (int i = 0; i < n; i++) {
            double t = s / mI[i];
            for (int j = 0; j < m; j++) cont[i][j] = t * demand[i][j];
        }
        return roundSafe(cont, lower, upper, cap);
    }

    /** Bounded-proportional fill mirroring the v0.9 separable family. */
    static double[] boundedProportional(double[] scores, long[] lower, long[] upper, long total) {
        int n = scores.length;
        double[] a = new double[n];
        double remaining = total;
        for (int i = 0; i < n; i++) { a[i] = lower[i]; remaining -= lower[i]; }
        if (remaining <= 1e-12) {
            for (int i = 0; i < n; i++) a[i] = Math.min(a[i], upper[i]);
            return a;
        }
        double[] sc = new double[n];
        for (int i = 0; i < n; i++) sc[i] = Math.max(scores[i], 0.0);
        boolean[] sat = new boolean[n];
        for (int iter = 0; iter < 4 * n + 5; iter++) {
            List<Integer> active = new ArrayList<>();
            for (int i = 0; i < n; i++) if (!sat[i] && a[i] < upper[i] - 1e-12) active.add(i);
            if (remaining <= 1e-12 || active.isEmpty()) break;
            double sa = 0;
            for (int i : active) sa += sc[i];
            if (sa <= 0) {
                double share = remaining / active.size();
                boolean progressed = false;
                for (int i : active) {
                    double room = upper[i] - a[i];
                    double add = Math.min(share, room);
                    a[i] += add; remaining -= add;
                    if (add < share - 1e-15) { sat[i] = true; progressed = true; }
                }
                if (!progressed) break;
                continue;
            }
            boolean overflow = false;
            for (int i : active) {
                double room = upper[i] - a[i];
                double want = remaining * sc[i] / sa;
                if (want > room + 1e-12) {
                    a[i] = upper[i]; sat[i] = true; remaining -= room; overflow = true;
                }
            }
            if (!overflow) {
                for (int i : active) a[i] += remaining * sc[i] / sa;
                remaining = 0;
                break;
            }
        }
        return a;
    }

    /** Deterministic capacity-safe integer rounding for the comparison policies. */
    static long[][] roundSafe(double[][] cont, long[][] lower, long[][] upper, long[] cap) {
        int n = cont.length;
        if (n == 0) return new long[0][0];
        int m = cont[0].length;
        long[][] out = new long[n][m];
        for (int j = 0; j < m; j++) {
            long[] base = new long[n];
            double[] rem = new double[n];
            long sumBase = 0;
            double colSum = 0;
            for (int i = 0; i < n; i++) {
                long f = (long) Math.floor(cont[i][j]);
                if (f < lower[i][j]) f = lower[i][j];
                if (f > upper[i][j]) f = upper[i][j];
                base[i] = f;
                rem[i] = cont[i][j] - f;
                sumBase += f;
                colSum += cont[i][j];
            }
            long target = Math.min(cap[j], Math.round(colSum));
            if (target < sumBase) target = sumBase;
            long units = target - sumBase;
            while (units > 0) {
                int pick = -1; double best = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < n; i++) {
                    if (base[i] < upper[i][j] && rem[i] > best) { best = rem[i]; pick = i; }
                }
                if (pick < 0) break;
                base[pick]++; rem[pick] = Double.NEGATIVE_INFINITY; units--;
            }
            // Capacity safety: if lower bounds pushed the column over capacity, trim
            // from agents with the most slack above their lower bound.
            long col = 0;
            for (int i = 0; i < n; i++) col += base[i];
            while (col > cap[j]) {
                int pick = -1; long bestSlack = 0;
                for (int i = 0; i < n; i++) {
                    long slack = base[i] - lower[i][j];
                    if (slack > bestSlack) { bestSlack = slack; pick = i; }
                }
                if (pick < 0) break;
                base[pick]--; col--;
            }
            for (int i = 0; i < n; i++) out[i][j] = base[i];
        }
        return out;
    }

    // ====================================================================
    // Small JSON helpers (input via SnakeYAML, output built by hand)
    // ====================================================================

    private static List<ServiceType> serviceList(List<Object> raw) {
        List<ServiceType> out = new ArrayList<>();
        if (raw != null) for (Object o : raw) out.add(ServiceType.valueOf(String.valueOf(o)));
        return out;
    }

    private static boolean bool(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean) return (Boolean) o;
        return Boolean.parseBoolean(String.valueOf(o));
    }
    private static String str(Object o, String def) { return o == null ? def : String.valueOf(o); }
    private static long lng(Object o, long def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).longValue();
        return Long.parseLong(String.valueOf(o));
    }
    private static double dbl(Object o, double def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).doubleValue();
        return Double.parseDouble(String.valueOf(o));
    }

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
            sb.append('"');
            for (char ch : ((String) v).toCharArray()) {
                switch (ch) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default: sb.append(ch);
                }
            }
            sb.append('"');
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else sb.append(d);
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
