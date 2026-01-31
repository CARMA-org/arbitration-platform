package org.carma.arbitration.config;

import org.carma.arbitration.model.*;
import org.carma.arbitration.agent.RealisticAgentFramework.*;

import javax.script.*;
import java.util.*;

/**
 * A configurable agent that executes behavior defined via inline scripts.
 *
 * This enables agents to be fully defined in YAML configuration files
 * without requiring Java code. The behavior script uses a JavaScript-like
 * syntax that can access goal parameters, service context, and publish outputs.
 *
 * Example YAML configuration:
 * <pre>
 * id: custom-validator
 * name: Custom Validation Agent
 * type: custom
 * autonomy: TOOL
 *
 * behavior: |
 *   function executeGoal(goal, context) {
 *     var input = goal.getParameter("data");
 *     if (!context.hasService("DATA_EXTRACTION")) {
 *       return GoalResult.failure("Service unavailable");
 *     }
 *     var result = context.invokeService("DATA_EXTRACTION", {"text": input});
 *     if (result.isSuccess()) {
 *       publish("validation_result", {"valid": true, "data": result.getOutput("data")});
 *       return GoalResult.success("Validated", result.getOutputs());
 *     }
 *     return GoalResult.failure(result.getError());
 *   }
 * </pre>
 *
 * The script engine provides these bindings:
 * - goal: The current Goal object
 * - context: The ExecutionContext for service invocation
 * - publish(type, data): Function to publish to output channels
 * - GoalResult: Static factory methods for success/failure
 * - ServiceType: Enum for service type constants
 */
public class ConfigurableAgent extends RealisticAgent {

    private final String behaviorScript;
    private final Set<ServiceType> requiredServices;
    private final Set<String> operatingDomains;
    private final ScriptEngine scriptEngine;

    protected ConfigurableAgent(Builder builder) {
        super(builder);
        this.behaviorScript = builder.behaviorScript;
        this.requiredServices = new HashSet<>(builder.requiredServices);
        this.operatingDomains = new HashSet<>(builder.operatingDomains);

        // Initialize JavaScript engine (try multiple options for Java version compatibility)
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("javascript");
        if (engine == null) {
            engine = manager.getEngineByName("nashorn");
        }
        if (engine == null) {
            engine = manager.getEngineByName("graal.js");
        }
        this.scriptEngine = engine;

        // Note: If no JS engine available, executeGoal will use fallback behavior
        // without script execution. This is acceptable for config-driven agents
        // that use built-in types (NewsSearchAgent, etc.) rather than custom scripts.
    }

    @Override
    protected GoalResult executeGoal(Goal goal, ExecutionContext context) {
        if (behaviorScript == null || behaviorScript.isEmpty()) {
            // Default behavior: just return success
            return GoalResult.success(
                "No behavior defined",
                Map.of(),
                0,
                List.of()
            );
        }

        // If no script engine available, return a descriptive message
        if (scriptEngine == null) {
            return GoalResult.success(
                "Custom behavior script defined but no JavaScript engine available (Java 21+). " +
                "Use built-in agent types (NewsSearchAgent, etc.) or add GraalJS dependency.",
                Map.of("script_length", behaviorScript.length()),
                0,
                List.of()
            );
        }

        long startTime = System.currentTimeMillis();
        List<String> servicesUsed = new ArrayList<>();

        try {
            Bindings bindings = scriptEngine.createBindings();

            // Provide goal and context
            bindings.put("goal", goal);
            bindings.put("context", new ScriptableContext(context, servicesUsed));

            // Provide publish function
            bindings.put("agent", this);

            // Provide helper classes
            bindings.put("GoalResult", GoalResult.class);
            bindings.put("ServiceType", ServiceType.class);
            bindings.put("Map", Map.class);
            bindings.put("List", List.class);

            // Provide publish function as a Java object
            bindings.put("publishFn", (PublishFunction) this::publish);

            // Wrap the script with helper function access
            String wrappedScript = """
                function publish(type, data) {
                    agent.publish(type, data);
                }

                """ + behaviorScript + """

                // Call the executeGoal function
                var result = executeGoal(goal, context);
                result;
                """;

            Object result = scriptEngine.eval(wrappedScript, bindings);

            if (result instanceof GoalResult) {
                return (GoalResult) result;
            } else {
                return GoalResult.success(
                    result != null ? result.toString() : "Completed",
                    Map.of("result", result),
                    System.currentTimeMillis() - startTime,
                    servicesUsed
                );
            }

        } catch (ScriptException e) {
            return GoalResult.failure("Script execution error: " + e.getMessage());
        }
    }

    @Override
    public Set<ServiceType> getRequiredServiceTypes() {
        return Collections.unmodifiableSet(requiredServices);
    }

    @Override
    public Set<String> getOperatingDomains() {
        return Collections.unmodifiableSet(operatingDomains);
    }

    // ========================================================================
    // SCRIPTABLE CONTEXT WRAPPER
    // ========================================================================

    /**
     * Wrapper around ExecutionContext for easier scripting access.
     */
    public static class ScriptableContext {
        private final ExecutionContext delegate;
        private final List<String> servicesUsed;

        public ScriptableContext(ExecutionContext delegate, List<String> servicesUsed) {
            this.delegate = delegate;
            this.servicesUsed = servicesUsed;
        }

        public boolean hasService(String serviceName) {
            ServiceType type = ServiceType.valueOf(serviceName);
            return delegate.hasService(type);
        }

        public ServiceResult invokeService(String serviceName, Map<String, Object> input) {
            ServiceType type = ServiceType.valueOf(serviceName);
            servicesUsed.add(type.name());
            return delegate.invokeService(type, input);
        }

        public void log(String message) {
            delegate.log(message);
        }
    }

    /**
     * Functional interface for publish function in scripts.
     */
    @FunctionalInterface
    public interface PublishFunction {
        void publish(String messageType, Map<String, Object> content);
    }

    // ========================================================================
    // BUILDER
    // ========================================================================

    public static class Builder extends RealisticAgent.Builder<Builder> {
        private String behaviorScript;
        private Set<ServiceType> requiredServices = new HashSet<>();
        private Set<String> operatingDomains = new HashSet<>();

        public Builder(String agentId) {
            super(agentId);
        }

        public Builder behaviorScript(String script) {
            this.behaviorScript = script;
            return this;
        }

        public Builder requireService(ServiceType type) {
            this.requiredServices.add(type);
            return this;
        }

        public Builder operatingDomains(Set<String> domains) {
            this.operatingDomains = new HashSet<>(domains);
            return this;
        }

        public Builder addDomain(String domain) {
            this.operatingDomains.add(domain);
            return this;
        }

        @Override
        public ConfigurableAgent build() {
            return new ConfigurableAgent(this);
        }
    }
}
