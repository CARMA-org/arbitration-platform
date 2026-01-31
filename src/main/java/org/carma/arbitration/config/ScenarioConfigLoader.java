package org.carma.arbitration.config;

import org.carma.arbitration.model.*;
import org.carma.arbitration.agent.RealisticAgentFramework.*;
import org.carma.arbitration.mechanism.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Loads complete scenario configurations from YAML files.
 *
 * A scenario consists of:
 * - Resource pool definition
 * - Service registry (optional)
 * - Agent configurations (one file per agent)
 * - Arbitration settings
 *
 * Directory structure:
 * <pre>
 * scenarios/
 *   basic-arbitration/
 *     scenario.yaml         # Main scenario config
 *     agent-a1.yaml         # Agent configs
 *     agent-a2.yaml
 *     agent-a3.yaml
 * </pre>
 */
public class ScenarioConfigLoader {

    // ========================================================================
    // CONFIGURATION DATA CLASSES
    // ========================================================================

    /**
     * Root configuration for a scenario.
     */
    public static class ScenarioConfig {
        public String name;
        public String description;
        public Map<String, Long> pool;                   // Resource pool
        public List<ServiceConfig> services;             // Service registry
        public List<String> agents;                      // Agent file references
        public ArbitrationConfig arbitration;

        @Override
        public String toString() {
            return String.format("ScenarioConfig[name=%s, agents=%d]",
                name, agents != null ? agents.size() : 0);
        }
    }

    /**
     * Service definition in scenario.
     */
    public static class ServiceConfig {
        public String id;
        public String type;          // ServiceType name
        public String provider;
        public int capacity = 10;
        public String description;
        public Map<String, Long> resources;  // Underlying resource requirements

        @Override
        public String toString() {
            return String.format("ServiceConfig[id=%s, type=%s]", id, type);
        }
    }

    /**
     * Arbitration settings for the scenario.
     */
    public static class ArbitrationConfig {
        public String mechanism = "proportional_fairness";
        public GroupingPolicyConfig groupingPolicy;
        public boolean autoDetectContentions = true;

        public static class GroupingPolicyConfig {
            public int kHopLimit = Integer.MAX_VALUE;
            public int maxGroupSize = Integer.MAX_VALUE;
            public String splitStrategy = "RESOURCE_AFFINITY";
        }
    }

    // ========================================================================
    // LOADING
    // ========================================================================

    private final Yaml yaml;
    private final AgentConfigLoader agentLoader;

    public ScenarioConfigLoader() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        this.yaml = new Yaml(options);
        this.agentLoader = new AgentConfigLoader();
    }

    /**
     * Load a complete scenario from a directory.
     *
     * @param scenarioDir Directory containing scenario.yaml and agent files
     * @return Parsed scenario configuration
     */
    public ScenarioConfig loadScenario(Path scenarioDir) throws IOException {
        Path scenarioFile = scenarioDir.resolve("scenario.yaml");
        if (!Files.exists(scenarioFile)) {
            throw new IOException("scenario.yaml not found in: " + scenarioDir);
        }

        try (InputStream is = Files.newInputStream(scenarioFile)) {
            Map<String, Object> raw = yaml.load(is);
            return parseScenarioConfig(raw);
        }
    }

    /**
     * Parse raw YAML into ScenarioConfig.
     */
    @SuppressWarnings("unchecked")
    private ScenarioConfig parseScenarioConfig(Map<String, Object> raw) {
        ScenarioConfig config = new ScenarioConfig();

        config.name = getString(raw, "name", "unnamed");
        config.description = getString(raw, "description", "");

        // Parse pool
        config.pool = new HashMap<>();
        Map<String, Object> poolMap = (Map<String, Object>) raw.get("pool");
        if (poolMap != null) {
            for (Map.Entry<String, Object> entry : poolMap.entrySet()) {
                config.pool.put(entry.getKey(), ((Number) entry.getValue()).longValue());
            }
        }

        // Parse services
        config.services = new ArrayList<>();
        List<Map<String, Object>> servicesList = (List<Map<String, Object>>) raw.get("services");
        if (servicesList != null) {
            for (Map<String, Object> svcMap : servicesList) {
                ServiceConfig svc = new ServiceConfig();
                svc.id = getString(svcMap, "id");
                svc.type = getString(svcMap, "type");
                svc.provider = getString(svcMap, "provider", "default");
                svc.capacity = getInt(svcMap, "capacity", 10);
                svc.description = getString(svcMap, "description", "");
                svc.resources = new HashMap<>();
                Map<String, Object> resMap = (Map<String, Object>) svcMap.get("resources");
                if (resMap != null) {
                    for (Map.Entry<String, Object> entry : resMap.entrySet()) {
                        svc.resources.put(entry.getKey(), ((Number) entry.getValue()).longValue());
                    }
                }
                config.services.add(svc);
            }
        }

        // Parse agent references
        config.agents = new ArrayList<>();
        List<String> agentList = (List<String>) raw.get("agents");
        if (agentList != null) {
            config.agents.addAll(agentList);
        }

        // Parse arbitration config
        config.arbitration = new ArbitrationConfig();
        Map<String, Object> arbMap = (Map<String, Object>) raw.get("arbitration");
        if (arbMap != null) {
            config.arbitration.mechanism = getString(arbMap, "mechanism", "proportional_fairness");
            config.arbitration.autoDetectContentions = getBoolean(arbMap, "autoDetectContentions", true);

            Map<String, Object> gpMap = (Map<String, Object>) arbMap.get("groupingPolicy");
            if (gpMap != null) {
                config.arbitration.groupingPolicy = new ArbitrationConfig.GroupingPolicyConfig();
                config.arbitration.groupingPolicy.kHopLimit = getInt(gpMap, "kHopLimit", Integer.MAX_VALUE);
                config.arbitration.groupingPolicy.maxGroupSize = getInt(gpMap, "maxGroupSize", Integer.MAX_VALUE);
                config.arbitration.groupingPolicy.splitStrategy = getString(gpMap, "splitStrategy", "RESOURCE_AFFINITY");
            }
        }

        return config;
    }

    // ========================================================================
    // BUILDING
    // ========================================================================

    /**
     * Build a ResourcePool from scenario configuration.
     */
    public ResourcePool buildPool(ScenarioConfig config) {
        Map<ResourceType, Long> poolMap = new HashMap<>();
        for (Map.Entry<String, Long> entry : config.pool.entrySet()) {
            ResourceType type = ResourceType.valueOf(entry.getKey());
            poolMap.put(type, entry.getValue());
        }
        return new ResourcePool(poolMap);
    }

    /**
     * Build a ServiceRegistry from scenario configuration.
     */
    public ServiceRegistry buildRegistry(ScenarioConfig config) {
        ServiceRegistry registry = new ServiceRegistry();
        for (ServiceConfig svc : config.services) {
            ServiceType type = ServiceType.valueOf(svc.type);
            AIService.Builder builder = new AIService.Builder(svc.id, type)
                .provider(svc.provider)
                .maxCapacity(svc.capacity);

            if (svc.description != null && !svc.description.isEmpty()) {
                builder.metadata("description", svc.description);
            }

            registry.register(builder.build());
        }
        return registry;
    }

    /**
     * Load all agents defined in the scenario.
     *
     * @param scenarioDir Directory containing agent YAML files
     * @param config Scenario configuration with agent file references
     * @return List of arbitration-model agents
     */
    public List<Agent> loadArbitrationAgents(Path scenarioDir, ScenarioConfig config) throws IOException {
        List<Agent> agents = new ArrayList<>();

        // If agent list is empty, find all YAML files in directory
        List<String> agentFiles = config.agents;
        if (agentFiles == null || agentFiles.isEmpty()) {
            agentFiles = new ArrayList<>();
            for (Path p : agentLoader.findAgentFiles(scenarioDir)) {
                agentFiles.add(p.getFileName().toString());
            }
        }

        for (String agentFile : agentFiles) {
            Path agentPath = scenarioDir.resolve(agentFile);
            if (!Files.exists(agentPath)) {
                throw new IOException("Agent file not found: " + agentPath);
            }

            AgentConfigLoader.AgentConfig agentConfig = agentLoader.loadFromFile(agentPath);
            Agent agent = agentLoader.buildArbitrationAgent(agentConfig);
            agents.add(agent);
        }

        return agents;
    }

    /**
     * Load all RealisticAgents defined in the scenario.
     *
     * @param scenarioDir Directory containing agent YAML files
     * @param config Scenario configuration
     * @param channels Shared output channels
     * @return List of realistic agents
     */
    public List<RealisticAgent> loadRealisticAgents(Path scenarioDir, ScenarioConfig config,
                                                     Map<String, OutputChannel> channels) throws IOException {
        List<RealisticAgent> agents = new ArrayList<>();

        List<String> agentFiles = config.agents;
        if (agentFiles == null || agentFiles.isEmpty()) {
            agentFiles = new ArrayList<>();
            for (Path p : agentLoader.findAgentFiles(scenarioDir)) {
                agentFiles.add(p.getFileName().toString());
            }
        }

        for (String agentFile : agentFiles) {
            Path agentPath = scenarioDir.resolve(agentFile);
            if (!Files.exists(agentPath)) {
                throw new IOException("Agent file not found: " + agentPath);
            }

            AgentConfigLoader.AgentConfig agentConfig = agentLoader.loadFromFile(agentPath);
            RealisticAgent agent = agentLoader.buildRealisticAgent(agentConfig, channels);
            agents.add(agent);
        }

        return agents;
    }

    /**
     * Build GroupingPolicy from scenario configuration.
     */
    public GroupingPolicy buildGroupingPolicy(ScenarioConfig config) {
        if (config.arbitration == null || config.arbitration.groupingPolicy == null) {
            return GroupingPolicy.DEFAULT;
        }

        ArbitrationConfig.GroupingPolicyConfig gpc = config.arbitration.groupingPolicy;

        GroupingPolicy.Builder builder = new GroupingPolicy.Builder();

        if (gpc.kHopLimit < Integer.MAX_VALUE) {
            builder.kHopLimit(gpc.kHopLimit);
        }

        if (gpc.maxGroupSize < Integer.MAX_VALUE) {
            builder.maxGroupSize(gpc.maxGroupSize);
        }

        if (gpc.splitStrategy != null) {
            try {
                GroupingPolicy.SplitStrategy strategy =
                    GroupingPolicy.SplitStrategy.valueOf(gpc.splitStrategy);
                builder.splitStrategy(strategy);
            } catch (IllegalArgumentException e) {
                // Use default
            }
        }

        return builder.build();
    }

    /**
     * Create an arbitrator based on mechanism name.
     */
    public Object buildArbitrator(String mechanism, PriorityEconomy economy) {
        switch (mechanism.toLowerCase()) {
            case "proportional_fairness":
                return new ProportionalFairnessArbitrator(economy);
            case "sequential_joint":
                return new SequentialJointArbitrator(economy);
            case "gradient_joint":
                return new GradientJointArbitrator(economy);
            case "convex_joint":
                return new ConvexJointArbitrator(economy);
            default:
                return new ProportionalFairnessArbitrator(economy);
        }
    }

    // ========================================================================
    // UTILITY METHODS
    // ========================================================================

    /**
     * List all available scenarios in the config/scenarios directory.
     */
    public List<String> listScenarios(Path configRoot) throws IOException {
        Path scenariosDir = configRoot.resolve("scenarios");
        if (!Files.exists(scenariosDir)) {
            return Collections.emptyList();
        }

        List<String> scenarios = new ArrayList<>();
        try (var stream = Files.list(scenariosDir)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> Files.exists(p.resolve("scenario.yaml")))
                  .map(p -> p.getFileName().toString())
                  .forEach(scenarios::add);
        }
        return scenarios;
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private String getString(Map<String, Object> map, String key) {
        return getString(map, key, null);
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    private boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }
}
