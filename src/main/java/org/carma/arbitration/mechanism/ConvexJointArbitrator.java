package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.*;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Joint multi-resource arbitrator that solves one convex program over the full
 * agent-by-resource allocation matrix by delegating to scripts/joint_solver.py
 * (cvxpy/Clarabel). The continuous solution is converted to integers by
 * capacity-preserving rounding. The subprocess runs under a hard timeout and the
 * arbitrator fails closed unless fallback is explicitly enabled.
 */
public class ConvexJointArbitrator implements JointArbitrator {

    private final PriorityEconomy economy;
    private final String pythonCommand;
    private final Path solverScriptPath;
    private final SequentialJointArbitrator fallback;
    private boolean useFallbackOnError;
    private boolean debug = false;
    private volatile long timeoutMillis = 30000;

    public static class SolverTimeoutException extends IOException {
        public SolverTimeoutException(String message) { super(message); }
    }

    /**
     * Create with default Python command and script path.
     */
    public ConvexJointArbitrator(PriorityEconomy economy) {
        this(economy, "python3", findSolverScript());
    }

    /**
     * Create with custom Python command and script path.
     */
    public ConvexJointArbitrator(PriorityEconomy economy, String pythonCommand, Path solverScriptPath) {
        this.economy = economy;
        this.pythonCommand = pythonCommand;
        this.solverScriptPath = solverScriptPath;
        this.fallback = new SequentialJointArbitrator(economy);
        this.useFallbackOnError = false;
    }

    public ConvexJointArbitrator() {
        this(new PriorityEconomy());
    }

    /**
     * Set whether to fall back to sequential solver on errors.
     */
    public ConvexJointArbitrator setUseFallbackOnError(boolean useFallback) {
        this.useFallbackOnError = useFallback;
        return this;
    }

    /**
     * Enable debug output.
     */
    public ConvexJointArbitrator setDebug(boolean debug) {
        this.debug = debug;
        return this;
    }

    public ConvexJointArbitrator setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
        return this;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    /**
     * Find the solver script in expected locations.
     */
    private static Path findSolverScript() {
        List<String> searchPaths = Arrays.asList(
            "scripts/joint_solver.py",
            "../scripts/joint_solver.py",
            "joint_solver.py",
            System.getProperty("user.dir") + "/scripts/joint_solver.py"
        );
        
        for (String path : searchPaths) {
            Path p = Paths.get(path);
            if (Files.exists(p)) {
                return p;
            }
        }
        
        // Return default path even if not found (will error at runtime)
        return Paths.get("scripts/joint_solver.py");
    }

    @Override
    public JointAllocationResult arbitrate(
            ContentionDetector.ContentionGroup group,
            Map<String, BigDecimal> currencyCommitments) {
        
        List<Agent> agents = new ArrayList<>(group.getAgents());
        Map<ResourceType, Long> available = group.getAvailableQuantities();
        ResourcePool pool = new ResourcePool(available);
        
        return arbitrate(agents, pool, currencyCommitments);
    }

    @Override
    public JointAllocationResult arbitrate(
            List<Agent> agents,
            ResourcePool pool,
            Map<String, BigDecimal> currencyCommitments) {
        
        long startTime = System.currentTimeMillis();
        
        List<ResourceType> resources = pool.getTotalCapacity().keySet().stream()
            .sorted(Comparator.comparingInt(ResourceType::ordinal))
            .collect(Collectors.toList());
        
        if (debug) {
            System.err.println("[DEBUG] Resources (sorted): " + resources);
        }
        
        try {
            // Build input JSON (pass resources list for consistent ordering)
            String inputJson = buildInputJson(agents, pool, currencyCommitments, resources);
            
            if (debug) {
                System.err.println("[DEBUG] Input JSON: " + inputJson);
            }
            
            // Call Python solver
            String outputJson = callPythonSolver(inputJson);
            
            if (debug) {
                System.err.println("[DEBUG] Output JSON: " + outputJson);
            }
            
            // Parse result (pass resources list and capacities for consistent ordering)
            long[] capacities = new long[resources.size()];
            for (int j = 0; j < resources.size(); j++) {
                capacities[j] = pool.getCapacity(resources.get(j));
            }
            JointAllocationResult result = parseResult(outputJson, agents, resources, capacities, currencyCommitments, startTime);
            
            if (debug) {
                System.err.println("[DEBUG] Parsed result feasible: " + result.isFeasible());
            }
            
            return result;
            
        } catch (Exception e) {
            if (debug) {
                System.err.println("[DEBUG] Exception: " + e.getMessage());
                e.printStackTrace(System.err);
            }
            if (useFallbackOnError) {
                System.err.println("ConvexJointArbitrator (requested=JOINT_LINEAR) failed: "
                    + e.getMessage() + " -- explicit fallback enabled, using actual="
                    + fallback.getClass().getSimpleName() + " (per-resource sequential)");
                JointAllocationResult fb = fallback.arbitrate(agents, pool, currencyCommitments);
                return new JointAllocationResult(
                    fb.getAllAllocations(), currencyCommitments, fb.getObjectiveValue(),
                    fb.isFeasible(),
                    "fallback[requested=JOINT_LINEAR,actual=SEQUENTIAL]: " + fb.getMessage(),
                    System.currentTimeMillis() - startTime);
            } else {
                throw new RuntimeException("Joint optimization failed: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Build JSON input for the Python solver.
     * Uses the provided resources list to ensure consistent ordering.
     */
    private String buildInputJson(
            List<Agent> agents,
            ResourcePool pool,
            Map<String, BigDecimal> currencyCommitments,
            List<ResourceType> resources) {
        
        int n = agents.size();
        int m = resources.size();
        
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        
        // n_agents, n_resources
        sb.append("\"n_agents\":").append(n).append(",");
        sb.append("\"n_resources\":").append(m).append(",");
        
        // preferences: n x m matrix of preference weights
        sb.append("\"preferences\":[");
        for (int i = 0; i < n; i++) {
            Agent agent = agents.get(i);
            sb.append("[");
            for (int j = 0; j < m; j++) {
                double w = agent.getPreferences().getWeight(resources.get(j));
                sb.append(w);
                if (j < m - 1) sb.append(",");
            }
            sb.append("]");
            if (i < n - 1) sb.append(",");
        }
        sb.append("],");
        
        // priority_weights: n array of priority weights
        sb.append("\"priority_weights\":[");
        for (int i = 0; i < n; i++) {
            BigDecimal burn = currencyCommitments.getOrDefault(agents.get(i).getId(), BigDecimal.ZERO);
            double weight = economy.calculatePriorityWeight(burn);
            sb.append(weight);
            if (i < n - 1) sb.append(",");
        }
        sb.append("],");
        
        // capacities: m array
        sb.append("\"capacities\":[");
        for (int j = 0; j < m; j++) {
            sb.append(pool.getCapacity(resources.get(j)));
            if (j < m - 1) sb.append(",");
        }
        sb.append("],");
        
        // minimums: n x m matrix
        sb.append("\"minimums\":[");
        for (int i = 0; i < n; i++) {
            Agent agent = agents.get(i);
            sb.append("[");
            for (int j = 0; j < m; j++) {
                sb.append(agent.getMinimum(resources.get(j)));
                if (j < m - 1) sb.append(",");
            }
            sb.append("]");
            if (i < n - 1) sb.append(",");
        }
        sb.append("],");
        
        // ideals: n x m matrix
        sb.append("\"ideals\":[");
        for (int i = 0; i < n; i++) {
            Agent agent = agents.get(i);
            sb.append("[");
            for (int j = 0; j < m; j++) {
                sb.append(agent.getIdeal(resources.get(j)));
                if (j < m - 1) sb.append(",");
            }
            sb.append("]");
            if (i < n - 1) sb.append(",");
        }
        sb.append("],");

        sb.append("\"utility_configs\":[");
        for (int i = 0; i < n; i++) {
            sb.append(utilityConfigJson(agents.get(i), resources));
            if (i < n - 1) sb.append(",");
        }
        sb.append("]");

        sb.append("}");
        return sb.toString();
    }

    private String utilityConfigJson(Agent agent, List<ResourceType> resources) {
        UtilityDeclaration decl = agent.getUtilityDeclaration();
        if (decl == null) {
            return "{\"type\":\"LINEAR\"}";
        }
        switch (decl.getFamily()) {
            case COBB_DOUGLAS:
                return "{\"type\":\"COBB_DOUGLAS\"}";
            case CES:
                return "{\"type\":\"CES\",\"rho\":" + decl.getRho() + "}";
            case LEONTIEF:
                StringBuilder r = new StringBuilder("{\"type\":\"LEONTIEF\",\"requirements\":[");
                for (int j = 0; j < resources.size(); j++) {
                    double req = decl.getRequirement(resources.get(j));
                    r.append(Math.max(0.0, req));
                    if (j < resources.size() - 1) r.append(",");
                }
                r.append("]}");
                return r.toString();
            default:
                return "{\"type\":\"LINEAR\"}";
        }
    }

    /**
     * Call the Python solver via subprocess.
     */
    private String callPythonSolver(String inputJson) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(pythonCommand, solverScriptPath.toString());
        pb.redirectErrorStream(false);

        Process process = pb.start();

        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread outReader = new Thread(() -> drainStream(process.getInputStream(), out));
        Thread errReader = new Thread(() -> drainStream(process.getErrorStream(), err));
        outReader.setDaemon(true);
        errReader.setDaemon(true);
        outReader.start();
        errReader.start();

        try (OutputStream os = process.getOutputStream()) {
            os.write(inputJson.getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException writeFailure) {
            if (debug) {
                System.err.println("[DEBUG] solver stdin write failed: " + writeFailure.getMessage());
            }
        }

        boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            joinQuietly(outReader);
            joinQuietly(errReader);
            throw new SolverTimeoutException(
                "Python solver exceeded timeout of " + timeoutMillis + "ms; process terminated");
        }

        outReader.join(2000);
        errReader.join(2000);

        int exitCode = process.exitValue();
        String output = out.toString();
        String errors = err.toString();

        if (exitCode != 0) {
            throw new IOException("Python solver failed with exit code " + exitCode + ": " + errors);
        }
        if (output.isEmpty()) {
            throw new IOException("Python solver returned empty output. Errors: " + errors);
        }
        return output;
    }

    private static void drainStream(InputStream in, StringBuilder sink) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sink.append(line);
            }
        } catch (IOException ignored) {
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Parse the JSON result from Python solver.
     * Uses the provided resources list to ensure consistent ordering with buildInputJson.
     */
    private JointAllocationResult parseResult(
            String json,
            List<Agent> agents,
            List<ResourceType> resources,
            long[] capacities,
            Map<String, BigDecimal> currencyCommitments,
            long startTime) {

        Map<String, Map<ResourceType, Long>> allocations = new HashMap<>();
        double objectiveValue = 0;
        boolean feasible = false;
        String message = "";

        try {
            String status = extractJsonString(json, "status");
            feasible = "optimal".equals(status) || "optimal_inaccurate".equals(status);
            String requested = extractJsonString(json, "requested_utility");
            String solved = extractJsonString(json, "solved_utility");

            if (debug) {
                System.err.println("[DEBUG] status='" + status + "' feasible=" + feasible
                    + " requested=" + requested + " solved=" + solved);
            }

            if (!feasible) {
                String errType = extractJsonString(json, "error_type");
                String errMsg = extractJsonString(json, "error_message");
                StringBuilder m = new StringBuilder(status);
                if (errType != null && !errType.isEmpty() && !"null".equals(errType)) {
                    m.append(" [").append(errType).append("]");
                }
                if (errMsg != null && !errMsg.isEmpty() && !"null".equals(errMsg)) {
                    m.append(": ").append(errMsg);
                }
                return new JointAllocationResult(
                    allocations, currencyCommitments, 0.0, false, m.toString(),
                    System.currentTimeMillis() - startTime);
            }

            objectiveValue = extractJsonDouble(json, "objective_value");
            message = status;
            if (requested != null && solved != null && !requested.equals(solved)) {
                message += " (requested=" + requested + ", solved=" + solved + ")";
            }
            String warnings = extractJsonArray(json, "warnings");
            if (warnings != null && !warnings.replaceAll("\\s", "").equals("[]")) {
                message += " (with warnings)";
            }

            String allocsJson = extractJsonArray(json, "allocations");
            List<List<Double>> allocMatrix = parseNestedDoubleArray(allocsJson);

            int n = agents.size();
            int m = resources.size();
            double[][] cont = new double[n][m];
            long[][] lower = new long[n][m];
            long[][] upper = new long[n][m];
            for (int i = 0; i < n && i < allocMatrix.size(); i++) {
                Agent agent = agents.get(i);
                List<Double> row = allocMatrix.get(i);
                for (int j = 0; j < m && j < row.size(); j++) {
                    cont[i][j] = row.get(j);
                    lower[i][j] = agent.getMinimum(resources.get(j));
                    upper[i][j] = agent.getIdeal(resources.get(j));
                }
            }

            long[][] rounded = roundColumnsPreservingCapacity(cont, lower, upper, capacities);

            for (int i = 0; i < n; i++) {
                Agent agent = agents.get(i);
                Map<ResourceType, Long> agentAllocs = new HashMap<>();
                for (int j = 0; j < m; j++) {
                    agentAllocs.put(resources.get(j), rounded[i][j]);
                }
                allocations.put(agent.getId(), agentAllocs);
            }

        } catch (Exception e) {
            if (debug) {
                System.err.println("[DEBUG] Parse exception: " + e.getMessage());
                e.printStackTrace(System.err);
            }
            feasible = false;
            message = "Parse error: " + e.getMessage();
            allocations = new HashMap<>();
        }

        return new JointAllocationResult(
            allocations,
            currencyCommitments,
            objectiveValue,
            feasible,
            message,
            System.currentTimeMillis() - startTime
        );
    }

    /**
     * Deterministic, capacity-preserving integer conversion via bounded
     * largest-remainder rounding applied independently to each resource column.
     * Guarantees each column sum does not exceed its integer capacity and that
     * every cell stays within its integer lower and upper bounds.
     */
    static long[][] roundColumnsPreservingCapacity(
            double[][] cont, long[][] lower, long[][] upper, long[] cap) {
        int n = cont.length;
        if (n == 0) return new long[0][0];
        int m = cont[0].length;
        long[][] out = new long[n][m];

        for (int j = 0; j < m; j++) {
            long[] base = new long[n];
            double[] rem = new double[n];
            long sumBase = 0;
            double colSum = 0.0;
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
                int pick = -1;
                double bestRem = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < n; i++) {
                    if (base[i] < upper[i][j] && rem[i] > bestRem) {
                        bestRem = rem[i];
                        pick = i;
                    }
                }
                if (pick < 0) break;
                base[pick]++;
                rem[pick] = Double.NEGATIVE_INFINITY;
                units--;
            }

            long finalCol = 0;
            for (int i = 0; i < n; i++) {
                out[i][j] = base[i];
                finalCol += base[i];
            }
            if (finalCol > cap[j]) {
                throw new IllegalStateException(
                    "capacity-preserving rounding violated capacity on resource " + j
                    + ": " + finalCol + " > " + cap[j]);
            }
        }
        return out;
    }

    // ========================================================================
    // Simple JSON Parsing Helpers (avoiding external dependencies)
    // FIXED: Now handles whitespace after colons (standard JSON formatting)
    // ========================================================================

    /**
     * Extract a string value from JSON.
     * Handles both "key":"value" and "key": "value" (with whitespace).
     */
    private String extractJsonString(String json, String key) {
        // Find the key
        String keyPattern = "\"" + key + "\"";
        int keyStart = json.indexOf(keyPattern);
        if (keyStart < 0) return "";
        
        // Find the colon after the key
        int colonPos = json.indexOf(":", keyStart + keyPattern.length());
        if (colonPos < 0) return "";
        
        // Skip whitespace after colon
        int valueStart = colonPos + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        
        if (valueStart >= json.length()) return "";
        
        // Check if value is a quoted string
        if (json.charAt(valueStart) == '"') {
            valueStart++; // Skip opening quote
            int valueEnd = json.indexOf("\"", valueStart);
            if (valueEnd < 0) return "";
            return json.substring(valueStart, valueEnd);
        } else {
            // Non-string value (number, boolean, null)
            int valueEnd = valueStart;
            while (valueEnd < json.length()) {
                char c = json.charAt(valueEnd);
                if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) break;
                valueEnd++;
            }
            return json.substring(valueStart, valueEnd);
        }
    }

    /**
     * Extract a double value from JSON.
     * Handles whitespace after colons.
     */
    private double extractJsonDouble(String json, String key) {
        String keyPattern = "\"" + key + "\"";
        int keyStart = json.indexOf(keyPattern);
        if (keyStart < 0) return 0;
        
        int colonPos = json.indexOf(":", keyStart + keyPattern.length());
        if (colonPos < 0) return 0;
        
        // Skip whitespace after colon
        int valueStart = colonPos + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        
        // Find end of number
        int valueEnd = valueStart;
        while (valueEnd < json.length()) {
            char c = json.charAt(valueEnd);
            if (c == ',' || c == '}' || c == ']') break;
            valueEnd++;
        }
        
        String numStr = json.substring(valueStart, valueEnd).trim();
        try {
            return Double.parseDouble(numStr);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Extract a JSON array.
     * Handles whitespace after colons.
     */
    private String extractJsonArray(String json, String key) {
        String keyPattern = "\"" + key + "\"";
        int keyStart = json.indexOf(keyPattern);
        if (keyStart < 0) return "[]";
        
        int colonPos = json.indexOf(":", keyStart + keyPattern.length());
        if (colonPos < 0) return "[]";
        
        // Skip whitespace after colon
        int valueStart = colonPos + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        
        if (valueStart >= json.length() || json.charAt(valueStart) != '[') return "[]";
        
        // Find matching bracket
        int depth = 0;
        int valueEnd = valueStart;
        for (; valueEnd < json.length(); valueEnd++) {
            char c = json.charAt(valueEnd);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    valueEnd++;
                    break;
                }
            }
        }
        
        return json.substring(valueStart, valueEnd);
    }

    /**
     * Parse a nested array of doubles from JSON.
     */
    private List<List<Double>> parseNestedDoubleArray(String arrayJson) {
        List<List<Double>> result = new ArrayList<>();
        
        // Remove outer brackets
        arrayJson = arrayJson.trim();
        if (arrayJson.startsWith("[")) arrayJson = arrayJson.substring(1);
        if (arrayJson.endsWith("]")) arrayJson = arrayJson.substring(0, arrayJson.length() - 1);
        
        // Parse each inner array
        int depth = 0;
        int start = 0;
        
        for (int i = 0; i < arrayJson.length(); i++) {
            char c = arrayJson.charAt(i);
            if (c == '[') {
                if (depth == 0) start = i + 1;
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    String inner = arrayJson.substring(start, i);
                    List<Double> row = new ArrayList<>();
                    for (String num : inner.split(",")) {
                        num = num.trim();
                        if (!num.isEmpty()) {
                            try {
                                row.add(Double.parseDouble(num));
                            } catch (NumberFormatException e) {
                                row.add(0.0);
                            }
                        }
                    }
                    result.add(row);
                }
            }
        }
        
        return result;
    }

    /**
     * Check if Python and required packages are available.
     */
    public boolean checkDependencies() {
        try {
            // Check Python
            ProcessBuilder pb = new ProcessBuilder(pythonCommand, "--version");
            Process p = pb.start();
            int exitCode = p.waitFor();
            if (exitCode != 0) return false;
            
            // Check cvxpy
            pb = new ProcessBuilder(pythonCommand, "-c", "import cvxpy; import clarabel; import numpy");
            p = pb.start();
            exitCode = p.waitFor();
            return exitCode == 0;
            
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get installation instructions for dependencies.
     */
    public static String getInstallationInstructions() {
        return """
            To enable joint optimization with Clarabel, install the following:
            
            1. Python 3.8 or higher:
               - Mac: brew install python3
               - Ubuntu: sudo apt install python3
            
            2. Required Python packages:
               pip install cvxpy clarabel numpy
            
            3. Verify installation:
               python3 -c "import cvxpy; import clarabel; print('OK')"
            
            Without these dependencies, the system will fall back to sequential
            optimization, which achieves LOCAL Pareto optimality only.
            """;
    }

    public PriorityEconomy getEconomy() {
        return economy;
    }

    @Override
    public String toString() {
        return String.format("ConvexJointArbitrator[python=%s, script=%s, fallback=%s]",
            pythonCommand, solverScriptPath, useFallbackOnError);
    }
}
