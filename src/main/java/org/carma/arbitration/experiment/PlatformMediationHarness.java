package org.carma.arbitration.experiment;

import org.carma.arbitration.agent.RealisticAgentFramework.*;
import org.carma.arbitration.mechanism.*;
import org.carma.arbitration.model.*;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Paths;
import java.util.*;

public class PlatformMediationHarness {

    private static final double EPS = 1e-6;
    private static final double BASE_WEIGHT = 10.0;

    public static void main(String[] args) throws Exception {
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
        String scenarioHash = str(job.get("scenarioHash"), "");

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
        double[][] U = new double[n][m];
        double[][] leontiefReq = new double[n][m];
        long[][] demand = new long[n][m];
        long[][] lower = new long[n][m];
        long[][] upper = new long[n][m];
        double[] priority = new double[n];
        double[] c = new double[n];
        for (int i = 0; i < n; i++) {
            Map<String, Object> a = agentSpecs.get(i);
            ids[i] = str(a.get("id"), "agent-" + i);
            Map<String, Object> uw = (Map<String, Object>) a.get("utilWeights");
            Map<String, Object> lr = (Map<String, Object>) a.get("leontiefReq");
            Map<String, Object> md = (Map<String, Object>) a.get("mandatoryDemand");
            Map<String, Object> mn = (Map<String, Object>) a.get("min");
            Map<String, Object> up = (Map<String, Object>) a.get("upper");
            for (int j = 0; j < m; j++) {
                String rn = resources.get(j).name();
                U[i][j] = dbl(uw.get(rn), 0.0);
                leontiefReq[i][j] = dbl(lr.get(rn), 0.0);
                demand[i][j] = lng(md.get(rn), 0);
                lower[i][j] = lng(mn.get(rn), 0);
                upper[i][j] = lng(up.get(rn), 0);
            }
            priority[i] = dbl(a.get("priority"), 1.0);
            c[i] = BASE_WEIGHT + priority[i];
        }

        String jointFamily = jointFamily(policy);
        String utilityFamily = jointFamily != null ? jointFamily : "LINEAR";

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

        List<TaskAgent> taskAgents = new ArrayList<>();
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
            for (int j = 0; j < m; j++) prefs.put(resources.get(j), U[i][j]);
            TaskAgent.Builder b = new TaskAgent.Builder(ids[i]).preferences(prefs).tasks(tasks)
                .utilityDeclaration(declarationFor(utilityFamily, U[i], leontiefReq[i], resources))
                .operatorPriority(priority[i]);
            for (int j = 0; j < m; j++) {
                b.declaredMinimum(resources.get(j), lower[i][j]);
                b.declaredUpperBound(resources.get(j), upper[i][j]);
            }
            TaskAgent agent = b.build();
            runtime.register(agent);
            runtime.setOperatorPriority(ids[i], java.math.BigDecimal.valueOf(priority[i]));
            taskAgents.add(agent);
        }

        long t0 = System.nanoTime();
        long[][] alloc = new long[n][m];
        String solverStatus = policy;
        boolean feasible = true;
        String message = "";
        try {
            if (jointFamily != null) {
                ConvexJointArbitrator arb = new ConvexJointArbitrator(
                    new PriorityEconomy(), solverPython, Paths.get("scripts/joint_solver.py"))
                    .setTimeoutMillis(60000);
                Map<String, Map<ResourceType, Long>> res =
                    runtime.runArbitration(new ContentionDetector(), arb, policy, null);
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < m; j++) {
                        alloc[i][j] = res.get(ids[i]).getOrDefault(resources.get(j), 0L);
                    }
                }
                solverStatus = runtime.getSnapshot().getSolverStatus();
            } else {
                if (policy.equals("equal")) {
                    alloc = separable(ids, U, lower, upper, c, cap, 0.0, true);
                } else if (policy.equals("drf")) {
                    alloc = drf(demand, lower, upper, cap);
                } else if (policy.equals("decomposed_cobb_douglas")) {
                    alloc = separable(ids, U, lower, upper, c, cap, 1.0, false);
                } else if (policy.equals("separable")) {
                    alloc = separable(ids, U, lower, upper, c, cap, gamma, false);
                    solverStatus = "separable_gamma=" + gamma;
                } else {
                    throw new IllegalArgumentException("unknown policy: " + policy);
                }
                Map<String, Map<ResourceType, Long>> allocMap = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    Map<ResourceType, Long> bundle = new LinkedHashMap<>();
                    for (int j = 0; j < m; j++) bundle.put(resources.get(j), alloc[i][j]);
                    allocMap.put(ids[i], bundle);
                }
                runtime.installContracts(allocMap, policy, solverStatus, null);
            }
        } catch (RuntimeException ex) {
            feasible = false;
            message = ex.getMessage();
            solverStatus = "infeasible";
        }
        long allocLatencyMs = (System.nanoTime() - t0) / 1_000_000;

        if (!feasible) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cell", cell); out.put("seed", seed); out.put("policy", policy);
            out.put("utility_family", utilityFamily); out.put("scenario_hash", scenarioHash);
            out.put("gamma", gamma); out.put("feasible", false);
            out.put("message", message);
            return toJson(out);
        }

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
            double phi = phiForFamily(utilityFamily, U[i], alloc[i], leontiefReq[i]);
            declaredWelfare += c[i] * Math.log(Math.max(phi, EPS));
        }

        boolean execute = bool(job.get("execute"), true);
        if (!execute) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cell", cell); out.put("seed", seed); out.put("policy", policy);
            out.put("utility_family", utilityFamily); out.put("scenario_hash", scenarioHash);
            out.put("gamma", gamma); out.put("feasible", true);
            out.put("solver_status", solverStatus);
            out.put("allocation_latency_ms", allocLatencyMs);
            out.put("declared_welfare", declaredWelfare);
            out.put("capacity_violation", capacityViolation);
            out.put("bound_violation", boundViolation);
            return toJson(out);
        }

        List<Map<String, Object>> agentRecords = new ArrayList<>();
        long[] chargedTotal = new long[m];
        long[] allocatedTotal = new long[m];
        int backendTotal = 0, blockedTotal = 0, mandatoryFailTotal = 0;
        for (int i = 0; i < n; i++) {
            runtime.invokeAgent(ids[i], "run-all");
            TaskAgent agent = taskAgents.get(i);
            ExecutionContext ctx = runtime.getLastExecutionContext(ids[i]);
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("id", ids[i]);
            rec.put("priority", priority[i]);
            rec.put("archetype", str(agentSpecs.get(i).get("archetype"), ""));
            rec.put("utility_family", utilityFamily);
            rec.put("tasks_total", agent.getTasksTotal());
            rec.put("tasks_done", agent.getTasksDone());
            rec.put("mandatory_failures", agent.getMandatoryFailures());
            rec.put("completion", agent.getCompletion());
            rec.put("quality", agent.getMeanQuality());
            rec.put("slo", agent.getSloAttainment());
            rec.put("optional_refinement_rate", agent.getOptionalRefinementRate());
            rec.put("optional_refinements_done", agent.getOptionalRefinementsDone());
            int backendCalls = ctx != null ? ctx.getBackendInvocations() : 0;
            int blocked = ctx != null ? ctx.getBlockedCalls() : 0;
            rec.put("backend_calls", backendCalls);
            rec.put("blocked_calls", blocked);
            Map<String, Object> allocated = new LinkedHashMap<>();
            Map<String, Object> charged = new LinkedHashMap<>();
            Map<String, Object> unused = new LinkedHashMap<>();
            for (int j = 0; j < m; j++) {
                long ch = ctx != null ? ctx.getCharged(resources.get(j)) : 0;
                allocated.put(resources.get(j).name(), alloc[i][j]);
                charged.put(resources.get(j).name(), ch);
                unused.put(resources.get(j).name(), alloc[i][j] - ch);
                chargedTotal[j] += ch;
                allocatedTotal[j] += alloc[i][j];
            }
            rec.put("allocated", allocated);
            rec.put("charged", charged);
            rec.put("unused", unused);
            Map<String, Object> exhausted = new LinkedHashMap<>();
            for (Map.Entry<ResourceType, Integer> e : agent.getExhaustedCounts().entrySet()) {
                exhausted.put(e.getKey().name(), e.getValue());
            }
            rec.put("exhausted", exhausted);
            agentRecords.add(rec);
            backendTotal += backendCalls;
            blockedTotal += blocked;
            mandatoryFailTotal += agent.getMandatoryFailures();
        }

        Map<String, Object> capUtil = new LinkedHashMap<>();
        Map<String, Object> allocConsume = new LinkedHashMap<>();
        long totalCharged = 0, totalCap = 0, totalAllocated = 0;
        for (int j = 0; j < m; j++) {
            capUtil.put(resources.get(j).name(), cap[j] > 0 ? (double) chargedTotal[j] / cap[j] : 0.0);
            allocConsume.put(resources.get(j).name(),
                allocatedTotal[j] > 0 ? (double) chargedTotal[j] / allocatedTotal[j] : 0.0);
            totalCharged += chargedTotal[j];
            totalCap += cap[j];
            totalAllocated += allocatedTotal[j];
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cell", cell);
        out.put("seed", seed);
        out.put("policy", policy);
        out.put("utility_family", utilityFamily);
        out.put("scenario_hash", scenarioHash);
        out.put("gamma", gamma);
        out.put("feasible", true);
        out.put("solver_status", solverStatus);
        out.put("allocation_latency_ms", allocLatencyMs);
        out.put("declared_welfare", declaredWelfare);
        out.put("capacity_violation", capacityViolation);
        out.put("bound_violation", boundViolation);
        out.put("backend_calls_total", backendTotal);
        out.put("blocked_calls_total", blockedTotal);
        out.put("mandatory_failures_total", mandatoryFailTotal);
        out.put("capacity_utilization", totalCap > 0 ? (double) totalCharged / totalCap : 0.0);
        out.put("allocation_consumption", totalAllocated > 0 ? (double) totalCharged / totalAllocated : 0.0);
        out.put("capacity_utilization_by_resource", capUtil);
        out.put("allocation_consumption_by_resource", allocConsume);
        out.put("agents", agentRecords);
        return toJson(out);
    }


        // ====================================================================
    // Allocation policies
    // ====================================================================

    private static String jointFamily(String policy) {
        switch (policy) {
            case "joint":
            case "joint_linear":
                return "LINEAR";
            case "joint_cobb_douglas":
                return "COBB_DOUGLAS";
            case "joint_ces":
                return "CES";
            case "joint_leontief":
                return "LEONTIEF";
            default:
                return null;
        }
    }

    private static double phiForFamily(String family, double[] w, long[] alloc, double[] req) {
        int m = w.length;
        if ("COBB_DOUGLAS".equals(family)) {
            double phi = 1.0;
            for (int j = 0; j < m; j++) {
                if (w[j] > 0) phi *= Math.pow(Math.max(alloc[j], EPS), w[j]);
            }
            return phi;
        }
        if ("CES".equals(family)) {
            double rho = 0.5;
            double s = 0.0;
            for (int j = 0; j < m; j++) s += w[j] * Math.pow(Math.max(alloc[j], 0.0), rho);
            return Math.pow(Math.max(s, EPS), 1.0 / rho);
        }
        if ("LEONTIEF".equals(family)) {
            double best = Double.POSITIVE_INFINITY;
            for (int j = 0; j < m; j++) {
                if (req[j] > 0) best = Math.min(best, alloc[j] / req[j]);
            }
            return best == Double.POSITIVE_INFINITY ? 0.0 : best;
        }
        double phi = 0.0;
        for (int j = 0; j < m; j++) phi += w[j] * alloc[j];
        return phi;
    }

    private static UtilityDeclaration declarationFor(
            String family, double[] w, double[] req, List<ResourceType> resources) {
        int m = resources.size();
        Map<ResourceType, Double> weights = new LinkedHashMap<>();
        for (int j = 0; j < m; j++) weights.put(resources.get(j), w[j]);
        switch (family) {
            case "COBB_DOUGLAS":
                return UtilityDeclaration.cobbDouglas(weights);
            case "CES":
                return UtilityDeclaration.ces(weights, 0.5);
            case "LEONTIEF":
                Map<ResourceType, Double> reqs = new LinkedHashMap<>();
                for (int j = 0; j < m; j++) reqs.put(resources.get(j), req[j]);
                return UtilityDeclaration.leontief(reqs);
            default:
                return UtilityDeclaration.linear(weights);
        }
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

    static long[][] drf(long[][] demand, long[][] lower, long[][] upper, long[] cap) {
        int n = demand.length, m = cap.length;
        double[] dominantDivisor = new double[n];
        for (int i = 0; i < n; i++) {
            double mx = 0;
            for (int j = 0; j < m; j++) {
                if (cap[j] > 0 && demand[i][j] > 0) {
                    mx = Math.max(mx, (double) demand[i][j] / cap[j]);
                }
            }
            dominantDivisor[i] = mx;
        }
        double[] scalar = new double[n];
        boolean[] active = new boolean[n];
        for (int i = 0; i < n; i++) active[i] = dominantDivisor[i] > 0;

        for (int round = 0; round < n + 2; round++) {
            double[] remaining = new double[m];
            double[] activeCoef = new double[m];
            for (int j = 0; j < m; j++) {
                remaining[j] = cap[j];
                for (int i = 0; i < n; i++) {
                    if (!active[i]) remaining[j] -= scalar[i] * demand[i][j];
                    else activeCoef[j] += (double) demand[i][j] / dominantDivisor[i];
                }
            }
            double sCap = Double.POSITIVE_INFINITY;
            for (int j = 0; j < m; j++) {
                if (activeCoef[j] > 1e-12) sCap = Math.min(sCap, remaining[j] / activeCoef[j]);
            }
            double sUpper = Double.POSITIVE_INFINITY;
            int hit = -1;
            for (int i = 0; i < n; i++) {
                if (!active[i]) continue;
                for (int j = 0; j < m; j++) {
                    if (demand[i][j] > 0) {
                        double sMax = (double) upper[i][j] * dominantDivisor[i] / demand[i][j];
                        if (sMax < sUpper) { sUpper = sMax; hit = i; }
                    }
                }
            }
            double s = Math.min(sCap, sUpper);
            if (!Double.isFinite(s)) break;
            for (int i = 0; i < n; i++) {
                if (active[i]) scalar[i] = s / dominantDivisor[i];
            }
            if (sUpper <= sCap && hit >= 0) {
                active[hit] = false;
            } else {
                break;
            }
        }

        double[][] cont = new double[n][m];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) cont[i][j] = scalar[i] * demand[i][j];
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
