package org.carma.arbitration.agent;

import org.carma.arbitration.model.*;
import org.carma.arbitration.agent.RealisticAgentFramework.*;
import org.carma.arbitration.config.AgentConfigLoader.AgentConfig;

import javax.script.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * Executes agent behavior scripts using Kotlin scripting.
 *
 * Provides a sandboxed environment with access to:
 * - goal: Current Goal object
 * - context: ExecutionContext for service invocation
 * - publish(type, data): Output channel publishing
 * - GoalResult: Factory for success/failure results
 * - state: Persistent state map across executions
 * - config: Agent configuration (in initialization)
 *
 * This enables agents to be fully defined in YAML configuration files
 * with Kotlin scripts for procedural logic, without requiring Java classes.
 */
public class KotlinScriptExecutor {

    private static final long DEFAULT_TIMEOUT_MS = 5000;
    private static final Set<String> BLOCKED_PACKAGES = Set.of(
        "java.io.File",
        "java.nio.file",
        "java.net.Socket",
        "java.net.URL",
        "java.lang.Runtime",
        "java.lang.ProcessBuilder",
        "java.lang.reflect"
    );

    private final ScriptEngine engine;
    private final Map<String, Object> state;
    private final long timeoutMs;

    /**
     * Create a new Kotlin script executor.
     */
    public KotlinScriptExecutor() {
        this(DEFAULT_TIMEOUT_MS);
    }

    /**
     * Create a new Kotlin script executor with custom timeout.
     */
    public KotlinScriptExecutor(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        this.state = new ConcurrentHashMap<>();

        // Initialize Kotlin script engine
        ScriptEngineManager manager = new ScriptEngineManager();
        this.engine = manager.getEngineByExtension("kts");

        if (this.engine == null) {
            throw new IllegalStateException(
                "Kotlin script engine not available. Ensure kotlin-scripting-jvm-host is in classpath.");
        }
    }

    /**
     * Execute an initialization script (runs once at agent creation).
     *
     * @param script The Kotlin initialization script
     * @param config The agent configuration
     * @param logFn Logging function
     */
    public void executeInitialization(String script, AgentConfig config,
                                       java.util.function.Consumer<String> logFn) {
        if (script == null || script.trim().isEmpty()) {
            return;
        }

        try {
            Bindings bindings = engine.createBindings();

            // Initialization bindings
            bindings.put("config", new ScriptableConfig(config));
            bindings.put("state", state);
            bindings.put("log", (java.util.function.Consumer<String>) logFn::accept);

            // Wrap script with imports
            String wrappedScript = buildInitializationScript(script);

            // Execute with timeout
            executeWithTimeout(wrappedScript, bindings);

        } catch (Exception e) {
            throw new RuntimeException("Initialization script failed: " + e.getMessage(), e);
        }
    }

    /**
     * Execute a goal execution script.
     *
     * @param script The Kotlin execution script
     * @param goal The current goal
     * @param context The execution context
     * @param publishFn The publish function
     * @return GoalResult from script execution
     */
    public GoalResult executeGoal(String script, Goal goal, ExecutionContext context,
                                   BiConsumer<String, Map<String, Object>> publishFn) {
        if (script == null || script.trim().isEmpty()) {
            return GoalResult.success("No execution script defined", Map.of(), 0, List.of());
        }

        long startTime = System.currentTimeMillis();
        List<String> servicesUsed = new ArrayList<>();

        try {
            Bindings bindings = engine.createBindings();

            // Execution bindings
            bindings.put("goal", goal);
            bindings.put("context", new ScriptableContext(context, servicesUsed));
            bindings.put("state", state);
            bindings.put("publish", (BiConsumer<String, Map<String, Object>>) publishFn);
            bindings.put("GoalResult", GoalResult.class);
            bindings.put("startTime", startTime);
            bindings.put("servicesUsed", servicesUsed);

            // Wrap script with imports and helpers
            String wrappedScript = buildExecutionScript(script);

            // Execute with timeout
            Object result = executeWithTimeout(wrappedScript, bindings);

            // Convert result
            if (result instanceof GoalResult) {
                return (GoalResult) result;
            } else {
                return GoalResult.success(
                    result != null ? result.toString() : "Completed",
                    Map.of("result", result != null ? result : "null"),
                    System.currentTimeMillis() - startTime,
                    servicesUsed
                );
            }

        } catch (TimeoutException e) {
            return GoalResult.failure("Script execution timed out after " + timeoutMs + "ms");
        } catch (Exception e) {
            return GoalResult.failure("Script execution failed: " + e.getMessage());
        }
    }

    /**
     * Validate a script without executing it.
     *
     * @param script The script to validate
     * @param isInitialization True if this is an initialization script
     * @return ValidationResult with success/errors
     */
    public ValidationResult validateScript(String script, boolean isInitialization) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (script == null || script.trim().isEmpty()) {
            return new ValidationResult(true, errors, warnings);
        }

        // Check for blocked packages
        for (String blocked : BLOCKED_PACKAGES) {
            if (script.contains(blocked)) {
                errors.add("Script contains blocked package reference: " + blocked);
            }
        }

        // Try to compile (syntax check)
        try {
            if (engine instanceof Compilable) {
                Compilable compilable = (Compilable) engine;
                String wrappedScript = isInitialization ?
                    buildInitializationScript(script) : buildExecutionScript(script);
                compilable.compile(wrappedScript);
            }
        } catch (ScriptException e) {
            errors.add("Syntax error: " + e.getMessage());
        }

        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    /**
     * Get the current state map.
     */
    public Map<String, Object> getState() {
        return Collections.unmodifiableMap(state);
    }

    /**
     * Clear the state map.
     */
    public void clearState() {
        state.clear();
    }

    // ========================================================================
    // PRIVATE HELPERS
    // ========================================================================

    private String buildInitializationScript(String userScript) {
        return """
            import java.time.*
            import java.util.*

            // User initialization script
            """ + userScript;
    }

    private String buildExecutionScript(String userScript) {
        return """
            import java.time.*
            import java.util.*
            import org.carma.arbitration.agent.RealisticAgentFramework.GoalResult

            // Helper function for publishing
            fun publish(type: String, data: Map<String, Any>) {
                @Suppress("UNCHECKED_CAST")
                val publishFn = bindings["publish"] as java.util.function.BiConsumer<String, Map<String, Any>>
                publishFn.accept(type, data)
            }

            // User execution script
            """ + userScript;
    }

    private Object executeWithTimeout(String script, Bindings bindings)
            throws TimeoutException, ScriptException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Object> future = executor.submit(() -> {
                return engine.eval(script, bindings);
            });
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ScriptException) {
                throw (ScriptException) e.getCause();
            }
            throw new ScriptException(e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScriptException("Script execution interrupted");
        } finally {
            executor.shutdownNow();
        }
    }

    // ========================================================================
    // INNER CLASSES
    // ========================================================================

    /**
     * Wrapper around AgentConfig for safe script access.
     */
    public static class ScriptableConfig {
        private final AgentConfig config;

        public ScriptableConfig(AgentConfig config) {
            this.config = config;
        }

        public String getId() { return config.id; }
        public String getName() { return config.name; }
        public String getDescription() { return config.description; }
        public String getAutonomy() { return config.autonomy; }
        public double getCurrency() { return config.currency; }

        public Object getParameter(String key) {
            return config.parameters != null ? config.parameters.get(key) : null;
        }

        @SuppressWarnings("unchecked")
        public List<String> getList(String key) {
            Object value = getParameter(key);
            if (value instanceof List) {
                return (List<String>) value;
            }
            return List.of();
        }
    }

    /**
     * Wrapper around ExecutionContext for safe script access.
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
     * Result of script validation.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;
        private final List<String> warnings;

        public ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
            this.valid = valid;
            this.errors = List.copyOf(errors);
            this.warnings = List.copyOf(warnings);
        }

        public boolean isValid() { return valid; }
        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }

        @Override
        public String toString() {
            if (valid) {
                return "ValidationResult[VALID" +
                    (warnings.isEmpty() ? "]" : ", warnings=" + warnings + "]");
            } else {
                return "ValidationResult[INVALID, errors=" + errors + "]";
            }
        }
    }
}
