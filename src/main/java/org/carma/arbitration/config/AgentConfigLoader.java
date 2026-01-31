package org.carma.arbitration.config;

import org.carma.arbitration.model.*;
import org.carma.arbitration.agent.RealisticAgentFramework.*;
import org.carma.arbitration.agent.ExampleAgents.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Loads agent configurations from YAML files.
 *
 * This enables declarative agent definitions without Java code:
 * - One YAML file per agent
 * - Supports all built-in agent types (NewsSearchAgent, etc.)
 * - Supports custom agents with inline behavior scripts
 * - Validates configuration at load time
 */
public class AgentConfigLoader {

    // ========================================================================
    // CONFIGURATION DATA CLASSES
    // ========================================================================

    /**
     * Root configuration structure for an agent YAML file.
     */
    public static class AgentConfig {
        public String id;
        public String name;
        public String type;                              // Agent type or "custom"
        public String description;
        public String autonomy;                          // TOOL, LOW, MEDIUM, HIGH
        public double currency = 100.0;
        public Map<String, Double> preferences;          // Resource preferences
        public Map<String, RequestBounds> requests;      // Min/ideal requests
        public Map<String, Object> parameters;           // Agent-specific params
        public List<GoalConfig> goals;
        public List<OutputConfig> outputs;
        public String behavior;                          // For custom agents

        @Override
        public String toString() {
            return String.format("AgentConfig[id=%s, type=%s, autonomy=%s]", id, type, autonomy);
        }
    }

    /**
     * Resource request bounds (min and ideal).
     */
    public static class RequestBounds {
        public long min;
        public long ideal;

        public RequestBounds() {}

        public RequestBounds(long min, long ideal) {
            this.min = min;
            this.ideal = ideal;
        }

        @Override
        public String toString() {
            return String.format("{min=%d, ideal=%d}", min, ideal);
        }
    }

    /**
     * Goal configuration within an agent.
     */
    public static class GoalConfig {
        public String id;
        public String description;
        public String type;        // ONE_TIME, PERIODIC, REACTIVE, CONTINUOUS
        public String period;      // ISO-8601 duration (e.g., "PT1H")
        public String deadline;    // ISO-8601 instant
        public int priority = 5;
        public Map<String, Object> parameters;

        @Override
        public String toString() {
            return String.format("GoalConfig[id=%s, type=%s]", id, type);
        }
    }

    /**
     * Output channel configuration.
     */
    public static class OutputConfig {
        public String type;        // console, file, memory
        public String name;
        public String path;        // For file channels

        @Override
        public String toString() {
            return String.format("OutputConfig[type=%s, name=%s]", type, name);
        }
    }

    // ========================================================================
    // LOADING METHODS
    // ========================================================================

    private final Yaml yaml;

    public AgentConfigLoader() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        this.yaml = new Yaml(options);
    }

    /**
     * Load agent configuration from a YAML file.
     */
    public AgentConfig loadFromFile(Path yamlFile) throws IOException {
        try (InputStream is = Files.newInputStream(yamlFile)) {
            Map<String, Object> raw = yaml.load(is);
            return parseAgentConfig(raw);
        }
    }

    /**
     * Load agent configuration from a YAML string.
     */
    public AgentConfig loadFromString(String yamlContent) {
        Map<String, Object> raw = yaml.load(yamlContent);
        return parseAgentConfig(raw);
    }

    /**
     * Parse raw YAML map into AgentConfig.
     */
    @SuppressWarnings("unchecked")
    private AgentConfig parseAgentConfig(Map<String, Object> raw) {
        AgentConfig config = new AgentConfig();

        config.id = getString(raw, "id");
        config.name = getString(raw, "name");
        config.type = getString(raw, "type", "custom");
        config.description = getString(raw, "description", "");
        config.autonomy = getString(raw, "autonomy", "TOOL");
        config.currency = getDouble(raw, "currency", 100.0);
        config.behavior = getString(raw, "behavior", null);

        // Parse preferences
        config.preferences = new HashMap<>();
        Map<String, Object> prefs = (Map<String, Object>) raw.get("preferences");
        if (prefs != null) {
            for (Map.Entry<String, Object> entry : prefs.entrySet()) {
                config.preferences.put(entry.getKey(), ((Number) entry.getValue()).doubleValue());
            }
        }

        // Parse requests
        config.requests = new HashMap<>();
        Map<String, Object> reqs = (Map<String, Object>) raw.get("requests");
        if (reqs != null) {
            for (Map.Entry<String, Object> entry : reqs.entrySet()) {
                Map<String, Object> bounds = (Map<String, Object>) entry.getValue();
                long min = ((Number) bounds.getOrDefault("min", 0)).longValue();
                long ideal = ((Number) bounds.getOrDefault("ideal", 0)).longValue();
                config.requests.put(entry.getKey(), new RequestBounds(min, ideal));
            }
        }

        // Parse parameters (agent-specific)
        config.parameters = (Map<String, Object>) raw.get("parameters");
        if (config.parameters == null) {
            config.parameters = new HashMap<>();
        }

        // Parse goals
        config.goals = new ArrayList<>();
        List<Map<String, Object>> goalsList = (List<Map<String, Object>>) raw.get("goals");
        if (goalsList != null) {
            for (Map<String, Object> goalMap : goalsList) {
                GoalConfig goal = new GoalConfig();
                goal.id = getString(goalMap, "id");
                goal.description = getString(goalMap, "description", "");
                goal.type = getString(goalMap, "type", "ONE_TIME");
                goal.period = getString(goalMap, "period", null);
                goal.deadline = getString(goalMap, "deadline", null);
                goal.priority = getInt(goalMap, "priority", 5);
                goal.parameters = (Map<String, Object>) goalMap.get("parameters");
                config.goals.add(goal);
            }
        }

        // Parse outputs
        config.outputs = new ArrayList<>();
        List<Map<String, Object>> outputsList = (List<Map<String, Object>>) raw.get("outputs");
        if (outputsList != null) {
            for (Map<String, Object> outMap : outputsList) {
                OutputConfig output = new OutputConfig();
                output.type = getString(outMap, "type", "console");
                output.name = getString(outMap, "name", "default");
                output.path = getString(outMap, "path", null);
                config.outputs.add(output);
            }
        }

        return config;
    }

    // ========================================================================
    // AGENT BUILDING
    // ========================================================================

    /**
     * Build a RealisticAgent from configuration.
     *
     * @param config The loaded agent configuration
     * @param channels Pre-created output channels (by name)
     * @return Configured RealisticAgent instance
     */
    public RealisticAgent buildRealisticAgent(AgentConfig config, Map<String, OutputChannel> channels) {
        validateConfig(config);

        RealisticAgent agent;

        switch (config.type) {
            case "NewsSearchAgent":
                agent = buildNewsSearchAgent(config, channels);
                break;
            case "DocumentSummarizerAgent":
                agent = buildDocumentSummarizerAgent(config, channels);
                break;
            case "DataExtractionAgent":
                agent = buildDataExtractionAgent(config, channels);
                break;
            case "ResearchAssistantAgent":
                agent = buildResearchAssistantAgent(config, channels);
                break;
            case "CodeReviewAgent":
                agent = buildCodeReviewAgent(config, channels);
                break;
            case "MonitoringAgent":
                agent = buildMonitoringAgent(config, channels);
                break;
            case "custom":
                agent = buildConfigurableAgent(config, channels);
                break;
            default:
                throw new IllegalArgumentException("Unknown agent type: " + config.type);
        }

        return agent;
    }

    /**
     * Build an arbitration-model Agent from configuration.
     * Used for resource allocation scenarios without realistic agent behavior.
     */
    public Agent buildArbitrationAgent(AgentConfig config) {
        validateConfig(config);

        // Build preference map
        Map<ResourceType, Double> prefMap = new HashMap<>();
        if (config.preferences != null) {
            for (Map.Entry<String, Double> entry : config.preferences.entrySet()) {
                ResourceType type = ResourceType.valueOf(entry.getKey());
                prefMap.put(type, entry.getValue());
            }
        }

        // Create agent
        Agent agent = new Agent(config.id, config.name, prefMap, config.currency);

        // Set requests
        if (config.requests != null) {
            for (Map.Entry<String, RequestBounds> entry : config.requests.entrySet()) {
                ResourceType type = ResourceType.valueOf(entry.getKey());
                RequestBounds bounds = entry.getValue();
                agent.setRequest(type, bounds.min, bounds.ideal);
            }
        }

        return agent;
    }

    // ========================================================================
    // SPECIFIC AGENT BUILDERS
    // ========================================================================

    @SuppressWarnings("unchecked")
    private NewsSearchAgent buildNewsSearchAgent(AgentConfig config, Map<String, OutputChannel> channels) {
        NewsSearchAgent.Builder builder = new NewsSearchAgent.Builder(config.id)
            .name(config.name)
            .description(config.description)
            .initialCurrency(config.currency);

        // Set topics from parameters
        List<String> topics = (List<String>) config.parameters.get("topics");
        if (topics != null) {
            builder.topics(topics);
        }

        // Set other parameters
        Integer maxResults = getParamInt(config.parameters, "maxResultsPerSearch");
        if (maxResults != null) {
            builder.maxResultsPerSearch(maxResults);
        }

        String format = (String) config.parameters.get("summaryFormat");
        if (format != null) {
            builder.summaryFormat(format);
        }

        String periodStr = (String) config.parameters.get("searchPeriod");
        if (periodStr != null) {
            builder.searchPeriod(Duration.parse(periodStr));
        }

        // Add output channels
        addOutputChannels(builder, config, channels);

        return builder.build();
    }

    private DocumentSummarizerAgent buildDocumentSummarizerAgent(AgentConfig config, Map<String, OutputChannel> channels) {
        DocumentSummarizerAgent.Builder builder = new DocumentSummarizerAgent.Builder(config.id)
            .name(config.name)
            .description(config.description)
            .initialCurrency(config.currency);

        Integer maxLength = getParamInt(config.parameters, "maxDocumentLength");
        if (maxLength != null) {
            builder.maxDocumentLength(maxLength);
        }

        String format = (String) config.parameters.get("outputFormat");
        if (format != null) {
            builder.outputFormat(format);
        }

        addOutputChannels(builder, config, channels);

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private DataExtractionAgent buildDataExtractionAgent(AgentConfig config, Map<String, OutputChannel> channels) {
        DataExtractionAgent.Builder builder = new DataExtractionAgent.Builder(config.id)
            .name(config.name)
            .description(config.description)
            .initialCurrency(config.currency);

        String dataSource = (String) config.parameters.get("dataSource");
        if (dataSource != null) {
            builder.dataSource(dataSource);
        }

        String schema = (String) config.parameters.get("extractionSchema");
        if (schema != null) {
            builder.extractionSchema(schema);
        }

        List<String> fields = (List<String>) config.parameters.get("fieldsToExtract");
        if (fields != null) {
            builder.fieldsToExtract(fields);
        }

        String periodStr = (String) config.parameters.get("extractionPeriod");
        if (periodStr != null) {
            builder.extractionPeriod(Duration.parse(periodStr));
        }

        addOutputChannels(builder, config, channels);

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private ResearchAssistantAgent buildResearchAssistantAgent(AgentConfig config, Map<String, OutputChannel> channels) {
        ResearchAssistantAgent.Builder builder = new ResearchAssistantAgent.Builder(config.id)
            .name(config.name)
            .description(config.description)
            .initialCurrency(config.currency);

        List<String> domains = (List<String>) config.parameters.get("researchDomains");
        if (domains != null) {
            builder.researchDomains(domains);
        }

        Integer maxSources = getParamInt(config.parameters, "maxSourcesPerQuery");
        if (maxSources != null) {
            builder.maxSourcesPerQuery(maxSources);
        }

        Boolean cite = (Boolean) config.parameters.get("citeSources");
        if (cite != null) {
            builder.citeSources(cite);
        }

        addOutputChannels(builder, config, channels);

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private CodeReviewAgent buildCodeReviewAgent(AgentConfig config, Map<String, OutputChannel> channels) {
        CodeReviewAgent.Builder builder = new CodeReviewAgent.Builder(config.id)
            .name(config.name)
            .description(config.description)
            .initialCurrency(config.currency);

        List<String> languages = (List<String>) config.parameters.get("languagesSupported");
        if (languages != null) {
            builder.languagesSupported(languages);
        }

        List<String> categories = (List<String>) config.parameters.get("checkCategories");
        if (categories != null) {
            builder.checkCategories(categories);
        }

        addOutputChannels(builder, config, channels);

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private MonitoringAgent buildMonitoringAgent(AgentConfig config, Map<String, OutputChannel> channels) {
        MonitoringAgent.Builder builder = new MonitoringAgent.Builder(config.id)
            .name(config.name)
            .description(config.description)
            .initialCurrency(config.currency);

        Map<String, Object> thresholds = (Map<String, Object>) config.parameters.get("thresholds");
        if (thresholds != null) {
            for (Map.Entry<String, Object> entry : thresholds.entrySet()) {
                builder.threshold(entry.getKey(), ((Number) entry.getValue()).doubleValue());
            }
        }

        String alertChannel = (String) config.parameters.get("alertChannel");
        if (alertChannel != null) {
            builder.alertChannel(alertChannel);
        }

        String periodStr = (String) config.parameters.get("checkPeriod");
        if (periodStr != null) {
            builder.checkPeriod(Duration.parse(periodStr));
        }

        addOutputChannels(builder, config, channels);

        return builder.build();
    }

    /**
     * Build a ConfigurableAgent that executes inline behavior scripts.
     */
    private ConfigurableAgent buildConfigurableAgent(AgentConfig config, Map<String, OutputChannel> channels) {
        ConfigurableAgent.Builder builder = new ConfigurableAgent.Builder(config.id)
            .name(config.name)
            .description(config.description)
            .autonomyLevel(AutonomyLevel.valueOf(config.autonomy))
            .initialCurrency(config.currency);

        // Set behavior script
        if (config.behavior != null) {
            builder.behaviorScript(config.behavior);
        }

        // Add goals from config
        for (GoalConfig goalConfig : config.goals) {
            Goal.GoalType goalType = Goal.GoalType.valueOf(goalConfig.type);
            Duration period = goalConfig.period != null ? Duration.parse(goalConfig.period) : null;

            Goal goal = new Goal(
                goalConfig.id,
                goalConfig.description,
                goalType,
                period,
                null,  // deadline
                goalConfig.priority
            );

            if (goalConfig.parameters != null) {
                for (Map.Entry<String, Object> param : goalConfig.parameters.entrySet()) {
                    goal.setParameter(param.getKey(), param.getValue());
                }
            }

            builder.addGoal(goal);
        }

        // Set resource preferences
        if (config.preferences != null) {
            for (Map.Entry<String, Double> entry : config.preferences.entrySet()) {
                ResourceType type = ResourceType.valueOf(entry.getKey());
                builder.resourcePreference(type, entry.getValue());
            }
        }

        // Set required services
        @SuppressWarnings("unchecked")
        List<String> services = (List<String>) config.parameters.get("requiredServices");
        if (services != null) {
            for (String svc : services) {
                builder.requireService(ServiceType.valueOf(svc));
            }
        }

        // Set operating domains
        @SuppressWarnings("unchecked")
        List<String> domains = (List<String>) config.parameters.get("operatingDomains");
        if (domains != null) {
            builder.operatingDomains(new HashSet<>(domains));
        }

        addOutputChannels(builder, config, channels);

        return builder.build();
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private void addOutputChannels(RealisticAgent.Builder<?> builder, AgentConfig config,
                                   Map<String, OutputChannel> channels) {
        if (config.outputs != null) {
            for (OutputConfig out : config.outputs) {
                OutputChannel channel = channels.get(out.name);
                if (channel == null) {
                    // Create default channel based on type
                    switch (out.type) {
                        case "console":
                            channel = new ConsoleChannel(out.name);
                            break;
                        case "file":
                            String filePath = out.path != null ? out.path : out.name + ".log";
                            channel = new FileChannel(out.name, filePath);
                            break;
                        case "memory":
                            channel = new MemoryChannel(out.name);
                            break;
                        default:
                            channel = new ConsoleChannel(out.name);
                    }
                    channels.put(out.name, channel);
                }
                builder.outputChannel(channel);
            }
        }
    }

    private void validateConfig(AgentConfig config) {
        if (config.id == null || config.id.isEmpty()) {
            throw new IllegalArgumentException("Agent configuration must have an 'id'");
        }
        if (config.name == null || config.name.isEmpty()) {
            config.name = config.id; // Default name to id
        }
        if (config.type == null || config.type.isEmpty()) {
            config.type = "custom";
        }

        // Validate autonomy level
        try {
            AutonomyLevel.valueOf(config.autonomy);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid autonomy level: " + config.autonomy +
                ". Must be one of: TOOL, LOW, MEDIUM, HIGH");
        }
    }

    private String getString(Map<String, Object> map, String key) {
        return getString(map, key, null);
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private double getDouble(Map<String, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }

    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    private Integer getParamInt(Map<String, Object> params, String key) {
        if (params == null) return null;
        Object value = params.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    // ========================================================================
    // UTILITY METHODS
    // ========================================================================

    /**
     * List all agent YAML files in a directory.
     */
    public List<Path> findAgentFiles(Path directory) throws IOException {
        List<Path> agentFiles = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                  .filter(p -> !p.getFileName().toString().equals("scenario.yaml"))
                  .forEach(agentFiles::add);
        }
        return agentFiles;
    }

    /**
     * Load all agents from a directory.
     */
    public List<AgentConfig> loadAllAgents(Path directory) throws IOException {
        List<AgentConfig> agents = new ArrayList<>();
        for (Path file : findAgentFiles(directory)) {
            agents.add(loadFromFile(file));
        }
        return agents;
    }
}
