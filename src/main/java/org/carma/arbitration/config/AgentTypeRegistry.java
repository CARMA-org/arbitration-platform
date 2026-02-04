package org.carma.arbitration.config;

import org.carma.arbitration.agent.RealisticAgentFramework.*;
import org.carma.arbitration.agent.KotlinScriptExecutor;
import org.carma.arbitration.config.AgentConfigLoader.*;
import org.carma.arbitration.config.factories.BuiltInAgentFactories.*;
import org.carma.arbitration.config.factories.ScriptedAgentFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for agent type factories.
 *
 * This enables third-party agents without modifying Java source code:
 * 1. Built-in agent types are registered at startup
 * 2. Custom agents can use Kotlin scripting via YAML configuration
 * 3. New agent types can be registered programmatically
 *
 * The registry eliminates the hardcoded switch statement that previously
 * prevented third-party agent definitions.
 */
public class AgentTypeRegistry {

    /**
     * Factory interface for creating RealisticAgent instances.
     */
    @FunctionalInterface
    public interface AgentFactory {
        /**
         * Create an agent from configuration.
         *
         * @param config Agent configuration from YAML
         * @param channels Available output channels by name
         * @param executor Optional Kotlin script executor for scripted agents
         * @return Configured RealisticAgent instance
         */
        RealisticAgent create(AgentConfig config, Map<String, OutputChannel> channels,
                              KotlinScriptExecutor executor);
    }

    // Singleton registry instance
    private static final AgentTypeRegistry INSTANCE = new AgentTypeRegistry();

    // Registered factories by type name
    private final Map<String, AgentFactory> factories = new ConcurrentHashMap<>();

    // Default factory for scripted agents (when execution: block is present)
    private AgentFactory scriptedAgentFactory;

    private AgentTypeRegistry() {
        // Register built-in agent types
        registerBuiltInTypes();
    }

    /**
     * Get the singleton registry instance.
     */
    public static AgentTypeRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Register a new agent type factory.
     *
     * @param typeName The agent type name (e.g., "NewsSearchAgent")
     * @param factory The factory for creating agents of this type
     */
    public void register(String typeName, AgentFactory factory) {
        factories.put(typeName, factory);
    }

    /**
     * Set the factory for scripted agents (agents with execution: blocks).
     */
    public void setScriptedAgentFactory(AgentFactory factory) {
        this.scriptedAgentFactory = factory;
    }

    /**
     * Check if a type is registered.
     */
    public boolean hasType(String typeName) {
        return factories.containsKey(typeName);
    }

    /**
     * Get all registered type names.
     */
    public Set<String> getRegisteredTypes() {
        return Collections.unmodifiableSet(factories.keySet());
    }

    /**
     * Create an agent from configuration.
     *
     * Resolution order:
     * 1. If config has execution: block, use scripted agent factory
     * 2. If config.type matches a registered factory, use that factory
     * 3. If config.type is "custom" or "scripted", use scripted agent factory
     * 4. Throw exception for unknown types
     *
     * @param config Agent configuration
     * @param channels Output channels
     * @param executor Kotlin script executor (nullable)
     * @return Created agent
     */
    public RealisticAgent create(AgentConfig config, Map<String, OutputChannel> channels,
                                  KotlinScriptExecutor executor) {
        // Check if this is a scripted agent (has execution: block)
        if (config.execution != null && !config.execution.isEmpty()) {
            if (scriptedAgentFactory == null) {
                throw new IllegalStateException(
                    "Scripted agent factory not configured. Cannot create agent with execution: block.");
            }
            return scriptedAgentFactory.create(config, channels, executor);
        }

        // Check for registered type
        AgentFactory factory = factories.get(config.type);
        if (factory != null) {
            return factory.create(config, channels, executor);
        }

        // Fallback for custom/scripted types
        if ("custom".equals(config.type) || "scripted".equals(config.type)) {
            if (scriptedAgentFactory != null) {
                return scriptedAgentFactory.create(config, channels, executor);
            }
            throw new IllegalStateException(
                "Scripted agent factory not configured for custom agent type.");
        }

        throw new IllegalArgumentException("Unknown agent type: " + config.type +
            ". Registered types: " + factories.keySet());
    }

    /**
     * Convenience method to create agent without executor (uses default).
     */
    public RealisticAgent create(AgentConfig config, Map<String, OutputChannel> channels) {
        return create(config, channels, null);
    }

    // ========================================================================
    // BUILT-IN TYPE REGISTRATION
    // ========================================================================

    private void registerBuiltInTypes() {
        // Register built-in agent type factories
        register("NewsSearchAgent", new NewsSearchAgentFactory());
        register("DocumentSummarizerAgent", new DocumentSummarizerAgentFactory());
        register("DataExtractionAgent", new DataExtractionAgentFactory());
        register("ResearchAssistantAgent", new ResearchAssistantAgentFactory());
        register("CodeReviewAgent", new CodeReviewAgentFactory());
        register("MonitoringAgent", new MonitoringAgentFactory());

        // Register the scripted agent factory for custom/scripted types
        // This enables arbitrary third-party agents defined entirely in YAML
        ScriptedAgentFactory scriptedFactory = new ScriptedAgentFactory();
        setScriptedAgentFactory(scriptedFactory);
        register("scripted", scriptedFactory);
        register("custom", scriptedFactory);  // Backward compatibility
    }

    // ========================================================================
    // INNER FACTORY CLASSES FOR BUILT-IN TYPES
    // ========================================================================

    /**
     * Base class for factories that build from AgentConfig.
     * Provides common helper methods.
     */
    public static abstract class BaseAgentFactory implements AgentFactory {

        protected void addOutputChannels(RealisticAgent.Builder<?> builder,
                                         AgentConfig config,
                                         Map<String, OutputChannel> channels) {
            if (config.outputs != null) {
                for (OutputConfig out : config.outputs) {
                    OutputChannel channel = channels.get(out.name);
                    if (channel == null) {
                        channel = createChannel(out);
                        channels.put(out.name, channel);
                    }
                    builder.outputChannel(channel);
                }
            }
        }

        protected OutputChannel createChannel(OutputConfig out) {
            switch (out.type) {
                case "console":
                    return new ConsoleChannel(out.name);
                case "file":
                    String filePath = out.path != null ? out.path : out.name + ".log";
                    return new FileChannel(out.name, filePath);
                case "memory":
                    return new MemoryChannel(out.name);
                default:
                    return new ConsoleChannel(out.name);
            }
        }

        protected Integer getParamInt(Map<String, Object> params, String key) {
            if (params == null) return null;
            Object value = params.get(key);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        protected List<String> getParamList(Map<String, Object> params, String key) {
            if (params == null) return null;
            Object value = params.get(key);
            if (value instanceof List) {
                return (List<String>) value;
            }
            return null;
        }

        protected String getParamString(Map<String, Object> params, String key) {
            if (params == null) return null;
            Object value = params.get(key);
            return value != null ? value.toString() : null;
        }
    }
}
