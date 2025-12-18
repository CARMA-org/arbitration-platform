package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.*;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Joint arbitrator using convex optimization via Python + Clarabel.
 * 
 * This implementation achieves TRUE GLOBAL Pareto optimality by solving
 * the full N×M allocation problem jointly, enabling cross-resource trades.
 * 
 * Mathematical formulation:
 *   maximize: Σᵢ cᵢ · log(Σⱼ wᵢⱼ · aᵢⱼ)
 *   
 *   subject to:
 *     Σᵢ aᵢⱼ ≤ Qⱼ           ∀j  (resource capacity)
 *     aᵢⱼ ≥ minᵢⱼ           ∀i,j (minimum requirements)
 *     aᵢⱼ ≤ idealᵢⱼ         ∀i,j (maximum requests)
 * 
 * REQUIREMENTS:
 *   - Python 3.8+
 *   - cvxpy: pip install cvxpy
 *   - clarabel: pip install clarabel
 *   - numpy: pip install numpy
 * 
 * INSTALLATION:
 *   pip install cvxpy clarabel numpy
 * 
 * The solver script is located at: scripts/joint_solver.py
 */
public class ConvexJointArbitrator implements JointArbitrator {

    private final PriorityEconomy economy;
    private final String pythonCommand;
    private final Path solverScriptPath;
    private final SequentialJointArbitrator fallback;
    private boolean useFallbackOnError;
    private boolean debug = false;

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
        this.useFallbackOnError = true;
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
        
        // CRITICAL FIX: Use SORTED resource list for deterministic ordering
        // Sort by enum ordinal to guarantee same order every time
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
            
            // Parse result (pass resources list for consistent ordering)
            JointAllocationResult result = parseResult(outputJson, agents, resources, currencyCommitments, startTime);
            
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
                System.err.println("ConvexJointArbitrator failed, using fallback: " + e.getMessage());
                return fallback.arbitrate(agents, pool, currencyCommitments);
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
        sb.append("]");
        
        sb.append("}");
        return sb.toString();
    }

    /**
     * Call the Python solver via subprocess.
     */
    private String callPythonSolver(String inputJson) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(pythonCommand, solverScriptPath.toString());
        pb.redirectErrorStream(false);
        
        Process process = pb.start();
        
        // Write input
        try (OutputStream os = process.getOutputStream()) {
            os.write(inputJson.getBytes());
            os.flush();
        }
        
        // Read output
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            output = sb.toString();
        }
        
        // Read errors
        String errors;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            errors = sb.toString();
        }
        
        int exitCode = process.waitFor();
        
        if (exitCode != 0) {
            throw new IOException("Python solver failed with exit code " + exitCode + ": " + errors);
        }
        
        if (output.isEmpty()) {
            throw new IOException("Python solver returned empty output. Errors: " + errors);
        }
        
        return output;
    }

    /**
     * Parse the JSON result from Python solver.
     * Uses the provided resources list to ensure consistent ordering with buildInputJson.
     */
    private JointAllocationResult parseResult(
            String json,
            List<Agent> agents,
            List<ResourceType> resources,
            Map<String, BigDecimal> currencyCommitments,
            long startTime) {
        
        // Result storage
        Map<String, Map<ResourceType, Long>> allocations = new HashMap<>();
        double objectiveValue = 0;
        boolean feasible = true;
        String message = "";
        
        try {
            // Extract status - FIXED: handle whitespace in JSON
            String status = extractJsonString(json, "status");
            feasible = "optimal".equals(status);
            message = status;
            
            if (debug) {
                System.err.println("[DEBUG] Parsed status: '" + status + "', feasible: " + feasible);
            }
            
            // Extract objective
            objectiveValue = extractJsonDouble(json, "objective");
            
            // Extract allocations matrix
            String allocsJson = extractJsonArray(json, "allocations");
            List<List<Double>> allocMatrix = parseNestedDoubleArray(allocsJson);
            
            if (debug) {
                System.err.println("[DEBUG] Allocation matrix rows: " + allocMatrix.size());
                System.err.println("[DEBUG] Resources count: " + resources.size());
            }
            
            for (int i = 0; i < agents.size() && i < allocMatrix.size(); i++) {
                Agent agent = agents.get(i);
                Map<ResourceType, Long> agentAllocs = new HashMap<>();
                List<Double> row = allocMatrix.get(i);
                
                for (int j = 0; j < resources.size() && j < row.size(); j++) {
                    // Round to nearest integer
                    long alloc = Math.round(row.get(j));
                    agentAllocs.put(resources.get(j), alloc);
                }
                
                allocations.put(agent.getId(), agentAllocs);
            }
            
            // Check for solver warnings
            if (json.contains("warning")) {
                message += " (with warnings)";
            }
            
        } catch (Exception e) {
            if (debug) {
                System.err.println("[DEBUG] Parse exception: " + e.getMessage());
                e.printStackTrace(System.err);
            }
            feasible = false;
            message = "Parse error: " + e.getMessage();
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
