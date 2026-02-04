package org.carma.arbitration.config;

import org.carma.arbitration.model.*;
import org.carma.arbitration.agent.RealisticAgentFramework.*;
import org.carma.arbitration.agent.KotlinScriptExecutor;
import org.carma.arbitration.config.AgentConfigLoader.AgentConfig;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * A configurable agent that executes behavior defined via Kotlin scripts.
 *
 * This enables agents to be fully defined in YAML configuration files
 * without requiring Java code. The Kotlin script can access goal parameters,
 * service context, and publish outputs.
 *
 * Example YAML configuration:
 * <pre>
 * id: custom-validator
 * name: Custom Validation Agent
 * type: custom
 * autonomy: TOOL
 *
 * services:
 *   required:
 *     - DATA_EXTRACTION
 *
 * initialization: |
 *   state["validationCount"] = 0
 *   log("Validator initialized")
 *
 * execution: |
 *   val input = goal.getParameter("data") as String
 *   if (!context.hasService("DATA_EXTRACTION")) {
 *       return@execution GoalResult.failure("Service unavailable")
 *   }
 *   val result = context.invokeService("DATA_EXTRACTION", mapOf("text" to input))
 *   if (result.isSuccess()) {
 *       state["validationCount"] = (state["validationCount"] as Int) + 1
 *       publish("validation_result", mapOf("valid" to true, "count" to state["validationCount"]))
 *       GoalResult.success("Validated", result.outputs, System.currentTimeMillis() - startTime, servicesUsed)
 *   } else {
 *       GoalResult.failure(result.error)
 *   }
 * </pre>
 *
 * Available script bindings:
 * - goal: The current Goal object
 * - context: ScriptableContext for service invocation
 * - state: Persistent Map across executions
 * - publish(type, data): Function to publish to output channels
 * - GoalResult: Factory class for success/failure results
 * - startTime: Execution start timestamp
 * - servicesUsed: List tracking invoked services
 */
public class ConfigurableAgent extends RealisticAgent {

    private final String initializationScript;
    private final String executionScript;
    private final Set<ServiceType> requiredServices;
    private final Set<String> operatingDomains;
    private final KotlinScriptExecutor scriptExecutor;
    private boolean initialized = false;
    private final AgentConfig config;

    protected ConfigurableAgent(Builder builder) {
        super(builder);
        this.initializationScript = builder.initializationScript;
        this.executionScript = builder.executionScript;
        this.requiredServices = new HashSet<>(builder.requiredServices);
        this.operatingDomains = new HashSet<>(builder.operatingDomains);
        this.config = builder.config;

        // Initialize Kotlin script executor
        KotlinScriptExecutor executor = null;
        try {
            executor = new KotlinScriptExecutor(builder.scriptTimeoutMs);
        } catch (Exception e) {
            // Kotlin scripting not available - will use fallback behavior
        }
        this.scriptExecutor = executor;
    }

    /**
     * Run initialization script if not already done.
     */
    private void ensureInitialized() {
        if (initialized || initializationScript == null || initializationScript.isEmpty()) {
            initialized = true;
            return;
        }

        if (scriptExecutor != null && config != null) {
            try {
                scriptExecutor.executeInitialization(initializationScript, config, this::log);
                initialized = true;
            } catch (Exception e) {
                log("Initialization script failed: " + e.getMessage());
                initialized = true; // Mark as initialized to prevent retry loops
            }
        } else {
            initialized = true;
        }
    }

    @Override
    protected GoalResult executeGoal(Goal goal, ExecutionContext context) {
        // Ensure initialization has run
        ensureInitialized();

        if (executionScript == null || executionScript.isEmpty()) {
            // Default behavior: just return success
            return GoalResult.success(
                "No execution script defined",
                Map.of(),
                0,
                List.of()
            );
        }

        // If no script executor available, return a descriptive message
        if (scriptExecutor == null) {
            return GoalResult.success(
                "Kotlin scripting not available. Add kotlin-scripting-jvm-host dependency.",
                Map.of("script_length", executionScript.length()),
                0,
                List.of()
            );
        }

        // Create publish function that forwards to agent's publish method
        BiConsumer<String, Map<String, Object>> publishFn = (type, data) -> {
            this.publish(type, data);
        };

        // Execute the Kotlin script
        return scriptExecutor.executeGoal(executionScript, goal, context, publishFn);
    }

    @Override
    public Set<ServiceType> getRequiredServiceTypes() {
        return Collections.unmodifiableSet(requiredServices);
    }

    @Override
    public Set<String> getOperatingDomains() {
        return Collections.unmodifiableSet(operatingDomains);
    }

    /**
     * Log a message for this agent.
     */
    protected void log(String message) {
        System.out.printf("[%s] %s: %s%n",
            java.time.Instant.now(), getAgentId(), message);
    }

    /**
     * Get the script executor's current state.
     */
    public Map<String, Object> getScriptState() {
        return scriptExecutor != null ? scriptExecutor.getState() : Map.of();
    }

    /**
     * Clear the script state.
     */
    public void clearScriptState() {
        if (scriptExecutor != null) {
            scriptExecutor.clearState();
        }
    }

    // ========================================================================
    // BUILDER
    // ========================================================================

    public static class Builder extends RealisticAgent.Builder<Builder> {
        private String initializationScript;
        private String executionScript;
        private Set<ServiceType> requiredServices = new HashSet<>();
        private Set<String> operatingDomains = new HashSet<>();
        private long scriptTimeoutMs = 5000;
        private AgentConfig config;

        // Legacy support
        private String behaviorScript;

        public Builder(String agentId) {
            super(agentId);
        }

        /**
         * Set the initialization script (runs once at agent creation).
         */
        public Builder initializationScript(String script) {
            this.initializationScript = script;
            return this;
        }

        /**
         * Set the execution script (runs for each goal).
         */
        public Builder executionScript(String script) {
            this.executionScript = script;
            return this;
        }

        /**
         * Legacy method: set behavior script (maps to executionScript).
         */
        public Builder behaviorScript(String script) {
            this.executionScript = script;
            return this;
        }

        public Builder requireService(ServiceType type) {
            this.requiredServices.add(type);
            return this;
        }

        public Builder requiredServices(Set<ServiceType> services) {
            this.requiredServices = new HashSet<>(services);
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

        public Builder scriptTimeout(long timeoutMs) {
            this.scriptTimeoutMs = timeoutMs;
            return this;
        }

        public Builder config(AgentConfig config) {
            this.config = config;
            return this;
        }

        @Override
        public ConfigurableAgent build() {
            return new ConfigurableAgent(this);
        }
    }
}
