package org.carma.arbitration.runner;

import org.carma.arbitration.config.*;
import org.carma.arbitration.config.ScenarioConfigLoader.*;
import org.carma.arbitration.model.*;
import org.carma.arbitration.mechanism.*;
import org.carma.arbitration.mechanism.ContentionDetector.*;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Executes scenarios loaded from configuration files.
 *
 * Key features:
 * - Loads scenario, pool, and agents from YAML config
 * - Uses ContentionDetector for automatic contention detection
 * - Applies grouping policies as configured
 * - Runs appropriate arbitration mechanism
 * - Reports results clearly
 *
 * Usage:
 * <pre>
 * ScenarioRunner runner = new ScenarioRunner();
 * ScenarioResult result = runner.run(Paths.get("config/scenarios/basic-arbitration"));
 * System.out.println(result);
 * </pre>
 */
public class ScenarioRunner {

    private final ScenarioConfigLoader loader;
    private final ContentionDetector detector;
    private final PriorityEconomy economy;

    private boolean verbose = true;

    public ScenarioRunner() {
        this.loader = new ScenarioConfigLoader();
        this.detector = new ContentionDetector();
        this.economy = new PriorityEconomy();
    }

    public ScenarioRunner verbose(boolean verbose) {
        this.verbose = verbose;
        return this;
    }

    // ========================================================================
    // MAIN EXECUTION
    // ========================================================================

    /**
     * Run a scenario from a directory.
     *
     * @param scenarioDir Directory containing scenario.yaml and agent files
     * @return Execution result with allocations and metrics
     */
    public ScenarioResult run(Path scenarioDir) throws IOException {
        // 1. Load scenario configuration
        log("Loading scenario from: " + scenarioDir);
        ScenarioConfig scenario = loader.loadScenario(scenarioDir);
        log("Scenario: " + scenario.name);
        log("Description: " + scenario.description);
        log("");

        // 2. Build resource pool
        ResourcePool pool = loader.buildPool(scenario);
        log("Resource Pool:");
        for (ResourceType type : ResourceType.values()) {
            long available = pool.getAvailable(type);
            if (available > 0) {
                log("  " + type + ": " + available);
            }
        }
        log("");

        // 3. Load agents
        List<Agent> agents = loader.loadArbitrationAgents(scenarioDir, scenario);
        log("Loaded " + agents.size() + " agents:");
        for (Agent agent : agents) {
            log("  " + agent.getId() + " (" + agent.getName() + ")");
            for (ResourceType type : ResourceType.values()) {
                long ideal = agent.getIdeal(type);
                if (ideal > 0) {
                    log("    " + type + ": min=" + agent.getMinimum(type) + ", ideal=" + ideal);
                }
            }
        }
        log("");

        // 4. AUTO-DETECT CONTENTIONS (KEY FEATURE!)
        log("=== AUTOMATIC CONTENTION DETECTION ===");
        List<ContentionGroup> groups = detector.detectContentions(agents, pool);

        if (groups.isEmpty()) {
            log("No contentions detected. All requests can be satisfied.");
            return new ScenarioResult(scenario.name, agents, Collections.emptyList(), pool);
        }

        log("Detected " + groups.size() + " contention group(s):");
        for (ContentionGroup group : groups) {
            log("  " + group.getGroupId() + ":");
            log("    Agents: " + group.getAgents().stream()
                .map(Agent::getId).collect(Collectors.joining(", ")));
            log("    Resources: " + group.getResources());
            log("    Severity: " + String.format("%.2f", group.getContentionSeverity()));
            log("    Requires joint optimization: " + group.requiresJointOptimization());
        }
        log("");

        // 5. Apply grouping policy if configured
        GroupingPolicy policy = loader.buildGroupingPolicy(scenario);
        if (policy != GroupingPolicy.DEFAULT) {
            log("Applying grouping policy:");
            log("  K-hop limit: " + policy.getKHopLimit());
            log("  Max group size: " + policy.getMaxGroupSize());
            log("");

            GroupingSplitter splitter = new GroupingSplitter(policy);
            groups = splitter.detectWithPolicy(agents, pool);
            log("After policy application: " + groups.size() + " group(s)");
            log("");
        }

        // 6. Run arbitration for each group
        log("=== ARBITRATION ===");
        List<GroupResult> groupResults = new ArrayList<>();

        String mechanism = scenario.arbitration != null ? scenario.arbitration.mechanism : "proportional_fairness";
        log("Mechanism: " + mechanism);
        log("");

        for (ContentionGroup group : groups) {
            GroupResult result = arbitrateGroup(group, mechanism, pool);
            groupResults.add(result);
        }

        // 7. Report results
        log("=== RESULTS ===");
        ScenarioResult scenarioResult = new ScenarioResult(scenario.name, agents, groupResults, pool);

        for (GroupResult gr : groupResults) {
            log("Group " + gr.groupId + ":");
            for (Map.Entry<String, Map<ResourceType, Long>> entry : gr.allocations.entrySet()) {
                log("  " + entry.getKey() + ":");
                for (Map.Entry<ResourceType, Long> alloc : entry.getValue().entrySet()) {
                    Agent agent = agents.stream()
                        .filter(a -> a.getId().equals(entry.getKey()))
                        .findFirst().orElse(null);
                    if (agent != null) {
                        long ideal = agent.getIdeal(alloc.getKey());
                        double satisfaction = ideal > 0 ? (double) alloc.getValue() / ideal * 100 : 100;
                        log("    " + alloc.getKey() + ": " + alloc.getValue() +
                            " (requested: " + ideal + ", satisfaction: " + String.format("%.1f%%", satisfaction) + ")");
                    }
                }
            }
            log("  Welfare: " + String.format("%.4f", gr.welfare));
            log("");
        }

        log("=== SUMMARY ===");
        log("Total agents: " + agents.size());
        log("Contention groups: " + groups.size());
        log("Total welfare: " + String.format("%.4f", scenarioResult.getTotalWelfare()));

        return scenarioResult;
    }

    // ========================================================================
    // ARBITRATION
    // ========================================================================

    /**
     * Arbitrate a single contention group.
     */
    private GroupResult arbitrateGroup(ContentionGroup group, String mechanism, ResourcePool pool) {
        List<Agent> groupAgents = new ArrayList<>(group.getAgents());
        Map<ResourceType, Long> available = group.getAvailableQuantities();

        // Create burns map (all zero for now)
        Map<String, BigDecimal> burns = new HashMap<>();
        for (Agent agent : groupAgents) {
            burns.put(agent.getId(), BigDecimal.ZERO);
        }

        // Check if this needs joint optimization
        boolean needsJoint = group.requiresJointOptimization();
        double welfare;
        Map<String, Map<ResourceType, Long>> allocations = new HashMap<>();

        if (needsJoint && (mechanism.equals("gradient_joint") || mechanism.equals("convex_joint"))) {
            // Use joint optimizer
            if (mechanism.equals("gradient_joint")) {
                GradientJointArbitrator jointArb = new GradientJointArbitrator(economy);
                GradientJointArbitrator.JointAllocationResult result =
                    jointArb.arbitrate(groupAgents, pool, burns);

                welfare = result.getObjectiveValue();
                for (Agent agent : groupAgents) {
                    Map<ResourceType, Long> agentAlloc = new HashMap<>();
                    for (ResourceType type : group.getResources()) {
                        agentAlloc.put(type, result.getAllocation(agent.getId(), type));
                    }
                    allocations.put(agent.getId(), agentAlloc);
                }
            } else {
                ConvexJointArbitrator jointArb = new ConvexJointArbitrator(economy);
                ConvexJointArbitrator.JointAllocationResult result =
                    jointArb.arbitrate(groupAgents, pool, burns);

                welfare = result.getObjectiveValue();
                for (Agent agent : groupAgents) {
                    Map<ResourceType, Long> agentAlloc = new HashMap<>();
                    for (ResourceType type : group.getResources()) {
                        agentAlloc.put(type, result.getAllocation(agent.getId(), type));
                    }
                    allocations.put(agent.getId(), agentAlloc);
                }
            }
        } else {
            // Sequential per-resource arbitration
            ProportionalFairnessArbitrator pfArb = new ProportionalFairnessArbitrator(economy);
            welfare = 0;

            for (ResourceType type : group.getResources()) {
                // Filter to agents that want this resource
                List<Agent> typeAgents = groupAgents.stream()
                    .filter(a -> a.getIdeal(type) > 0)
                    .collect(Collectors.toList());

                if (typeAgents.isEmpty()) continue;

                // Create contention for this resource
                Contention contention = new Contention(type, typeAgents, available.get(type));

                Map<String, BigDecimal> typeBurns = new HashMap<>();
                for (Agent a : typeAgents) {
                    typeBurns.put(a.getId(), burns.get(a.getId()));
                }

                AllocationResult result = pfArb.arbitrate(contention, typeBurns);
                welfare += result.getObjectiveValue();

                // Store allocations
                for (Agent agent : typeAgents) {
                    allocations.computeIfAbsent(agent.getId(), k -> new HashMap<>())
                        .put(type, result.getAllocation(agent.getId()));
                }
            }
        }

        return new GroupResult(group.getGroupId(), allocations, welfare);
    }

    // ========================================================================
    // RESULT CLASSES
    // ========================================================================

    /**
     * Result for a single contention group.
     */
    public static class GroupResult {
        public final String groupId;
        public final Map<String, Map<ResourceType, Long>> allocations;
        public final double welfare;

        public GroupResult(String groupId, Map<String, Map<ResourceType, Long>> allocations, double welfare) {
            this.groupId = groupId;
            this.allocations = allocations;
            this.welfare = welfare;
        }
    }

    /**
     * Complete result for a scenario.
     */
    public static class ScenarioResult {
        public final String scenarioName;
        public final List<Agent> agents;
        public final List<GroupResult> groupResults;
        public final ResourcePool pool;

        public ScenarioResult(String scenarioName, List<Agent> agents,
                             List<GroupResult> groupResults, ResourcePool pool) {
            this.scenarioName = scenarioName;
            this.agents = agents;
            this.groupResults = groupResults;
            this.pool = pool;
        }

        public double getTotalWelfare() {
            return groupResults.stream().mapToDouble(g -> g.welfare).sum();
        }

        public long getAllocation(String agentId, ResourceType type) {
            for (GroupResult gr : groupResults) {
                Map<ResourceType, Long> agentAllocs = gr.allocations.get(agentId);
                if (agentAllocs != null && agentAllocs.containsKey(type)) {
                    return agentAllocs.get(type);
                }
            }
            return 0;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ScenarioResult[").append(scenarioName).append("]\n");
            sb.append("  Agents: ").append(agents.size()).append("\n");
            sb.append("  Groups: ").append(groupResults.size()).append("\n");
            sb.append("  Total Welfare: ").append(String.format("%.4f", getTotalWelfare())).append("\n");
            return sb.toString();
        }
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private void log(String message) {
        if (verbose) {
            System.out.println(message);
        }
    }

    /**
     * List available scenarios.
     */
    public List<String> listScenarios(Path configRoot) throws IOException {
        return loader.listScenarios(configRoot);
    }
}
