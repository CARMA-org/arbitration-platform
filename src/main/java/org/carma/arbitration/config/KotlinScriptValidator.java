package org.carma.arbitration.config;

import org.carma.arbitration.config.AgentConfigLoader.*;
import org.carma.arbitration.model.ServiceType;

import javax.script.*;
import java.util.*;
import java.util.regex.*;

/**
 * Comprehensive validator for Kotlin scripts in agent configurations.
 *
 * Validates scripts at load time to catch errors before runtime:
 * - Syntax validation via Kotlin compiler
 * - Security checks (blocked APIs, reflection, file/network access)
 * - Service declaration consistency (scripts can only use declared services)
 * - Binding availability checks
 * - Best practice warnings
 *
 * This ensures that third-party agents defined in YAML are safe and correct
 * before they are instantiated and executed.
 */
public class KotlinScriptValidator {

    // ========================================================================
    // SECURITY RULES
    // ========================================================================

    /**
     * Packages/classes that are completely blocked in scripts.
     * These provide dangerous capabilities that scripts should never have.
     */
    private static final Set<String> BLOCKED_APIS = Set.of(
        // File system access
        "java.io.File",
        "java.io.FileInputStream",
        "java.io.FileOutputStream",
        "java.io.FileReader",
        "java.io.FileWriter",
        "java.io.RandomAccessFile",
        "java.nio.file.Files",
        "java.nio.file.Paths",
        "java.nio.file.Path",
        "java.nio.channels.FileChannel",

        // Network access
        "java.net.Socket",
        "java.net.ServerSocket",
        "java.net.URL",
        "java.net.HttpURLConnection",
        "java.net.URLConnection",
        "java.net.DatagramSocket",

        // Process execution
        "java.lang.Runtime",
        "java.lang.ProcessBuilder",
        "java.lang.Process",

        // Reflection (can bypass security)
        "java.lang.reflect",
        "java.lang.Class.forName",
        "java.lang.Class.getDeclaredField",
        "java.lang.Class.getDeclaredMethod",
        ".javaClass",
        "::class.java",

        // Class loading (can load arbitrary code)
        "java.lang.ClassLoader",
        "java.net.URLClassLoader",

        // Security-sensitive
        "java.security.AccessController",
        "java.lang.SecurityManager",
        "System.setSecurityManager",
        "System.exit",
        "System.getenv",
        "System.getProperty",

        // Native code
        "System.loadLibrary",
        "System.load",
        "Runtime.loadLibrary",

        // Serialization (can be exploited)
        "java.io.ObjectInputStream",
        "java.io.ObjectOutputStream"
    );

    /**
     * Patterns that indicate potentially dangerous operations.
     */
    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
        // Shell execution patterns
        Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec"),
        Pattern.compile("ProcessBuilder\\s*\\("),

        // Reflection patterns
        Pattern.compile("\\.getDeclaredField\\s*\\("),
        Pattern.compile("\\.getDeclaredMethod\\s*\\("),
        Pattern.compile("\\.setAccessible\\s*\\(\\s*true\\s*\\)"),
        Pattern.compile("Class\\.forName\\s*\\("),

        // Dynamic code execution
        Pattern.compile("ScriptEngine"),
        Pattern.compile("eval\\s*\\(.*\\)"),  // JS-style eval

        // Bypassing access controls
        Pattern.compile("sun\\.misc\\.Unsafe"),
        Pattern.compile("jdk\\.internal")
    );

    /**
     * Bindings available in initialization scripts.
     */
    private static final Set<String> INIT_BINDINGS = Set.of(
        "config", "state", "log"
    );

    /**
     * Bindings available in execution scripts.
     */
    private static final Set<String> EXEC_BINDINGS = Set.of(
        "goal", "context", "state", "publish", "GoalResult", "startTime", "servicesUsed"
    );

    // ========================================================================
    // VALIDATION API
    // ========================================================================

    private final ScriptEngine kotlinEngine;

    public KotlinScriptValidator() {
        ScriptEngineManager manager = new ScriptEngineManager();
        this.kotlinEngine = manager.getEngineByExtension("kts");
        // Note: kotlinEngine may be null if Kotlin scripting isn't available
    }

    /**
     * Validate an entire agent configuration.
     *
     * @param config The agent configuration to validate
     * @return Validation result with all errors and warnings
     */
    public ConfigValidationResult validateConfig(AgentConfig config) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Basic config validation
        if (config.id == null || config.id.isEmpty()) {
            errors.add("Agent configuration must have an 'id'");
        }

        // Validate initialization script
        if (config.initialization != null && !config.initialization.isEmpty()) {
            ScriptValidationResult initResult = validateScript(
                config.initialization, ScriptType.INITIALIZATION, config);
            errors.addAll(prefixErrors("initialization", initResult.errors));
            warnings.addAll(prefixErrors("initialization", initResult.warnings));
        }

        // Validate execution script
        if (config.execution != null && !config.execution.isEmpty()) {
            ScriptValidationResult execResult = validateScript(
                config.execution, ScriptType.EXECUTION, config);
            errors.addAll(prefixErrors("execution", execResult.errors));
            warnings.addAll(prefixErrors("execution", execResult.warnings));
        }

        // Validate service declarations match script usage
        if (config.execution != null) {
            validateServiceConsistency(config, errors, warnings);
        }

        return new ConfigValidationResult(
            config.id != null ? config.id : "<unknown>",
            errors.isEmpty(),
            errors,
            warnings
        );
    }

    /**
     * Validate a single script.
     *
     * @param script The script source code
     * @param type Whether this is initialization or execution
     * @param config The containing agent config (for context)
     * @return Validation result
     */
    public ScriptValidationResult validateScript(String script, ScriptType type, AgentConfig config) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (script == null || script.trim().isEmpty()) {
            return new ScriptValidationResult(true, errors, warnings);
        }

        // 1. Security checks
        validateSecurity(script, errors, warnings);

        // 2. Syntax validation (compile check)
        validateSyntax(script, type, errors);

        // 3. Binding usage checks
        validateBindings(script, type, warnings);

        // 4. Best practices
        validateBestPractices(script, type, config, warnings);

        return new ScriptValidationResult(errors.isEmpty(), errors, warnings);
    }

    // ========================================================================
    // VALIDATION METHODS
    // ========================================================================

    private void validateSecurity(String script, List<String> errors, List<String> warnings) {
        // Check for blocked APIs
        for (String blocked : BLOCKED_APIS) {
            if (script.contains(blocked)) {
                errors.add("Script contains blocked API: " + blocked +
                    ". This API is not permitted for security reasons.");
            }
        }

        // Check for dangerous patterns
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            Matcher matcher = pattern.matcher(script);
            if (matcher.find()) {
                errors.add("Script contains dangerous pattern: " + matcher.group() +
                    ". This pattern is not permitted for security reasons.");
            }
        }

        // Check for import statements that might bypass blocks
        Pattern importPattern = Pattern.compile("import\\s+([\\w.]+)");
        Matcher importMatcher = importPattern.matcher(script);
        while (importMatcher.find()) {
            String importedClass = importMatcher.group(1);
            for (String blocked : BLOCKED_APIS) {
                if (importedClass.startsWith(blocked) || blocked.startsWith(importedClass)) {
                    errors.add("Script imports blocked package: " + importedClass);
                }
            }
        }

        // Warn about potentially suspicious patterns
        if (script.contains("Thread.sleep")) {
            warnings.add("Script uses Thread.sleep which may cause timeout issues");
        }
        if (script.contains("while (true)") || script.contains("while(true)")) {
            warnings.add("Script contains infinite loop pattern - ensure proper exit conditions");
        }
    }

    private void validateSyntax(String script, ScriptType type, List<String> errors) {
        if (kotlinEngine == null) {
            // Can't validate syntax without Kotlin engine, but don't fail
            return;
        }

        if (!(kotlinEngine instanceof Compilable)) {
            return;
        }

        try {
            Compilable compilable = (Compilable) kotlinEngine;
            String wrappedScript = wrapScript(script, type);
            compilable.compile(wrappedScript);
        } catch (ScriptException e) {
            // Extract meaningful error message
            String message = e.getMessage();
            if (message != null) {
                // Clean up the error message
                message = message.replaceAll("\\s+", " ").trim();
                if (message.length() > 200) {
                    message = message.substring(0, 200) + "...";
                }
            }
            errors.add("Syntax error: " + message);
        }
    }

    private void validateBindings(String script, ScriptType type, List<String> warnings) {
        Set<String> availableBindings = (type == ScriptType.INITIALIZATION)
            ? INIT_BINDINGS : EXEC_BINDINGS;

        // Check for common misspellings or wrong bindings
        if (type == ScriptType.INITIALIZATION) {
            if (script.contains("goal.") || script.contains("goal[")) {
                warnings.add("'goal' binding is not available in initialization scripts. " +
                    "Use 'config' to access configuration parameters.");
            }
            if (script.contains("context.")) {
                warnings.add("'context' binding is not available in initialization scripts.");
            }
            if (script.contains("publish(") && !script.contains("// publish")) {
                warnings.add("'publish' function is not available in initialization scripts.");
            }
        }

        if (type == ScriptType.EXECUTION) {
            if (script.contains("config.") && !script.contains("// config")) {
                warnings.add("'config' binding is not directly available in execution scripts. " +
                    "Access configuration via goal parameters or store in state during initialization.");
            }
        }
    }

    private void validateBestPractices(String script, ScriptType type,
                                       AgentConfig config, List<String> warnings) {
        // Check that execution scripts return GoalResult
        if (type == ScriptType.EXECUTION) {
            if (!script.contains("GoalResult.success") && !script.contains("GoalResult.failure")) {
                warnings.add("Execution script should explicitly return GoalResult.success() or " +
                    "GoalResult.failure() for clear result handling.");
            }
        }

        // Check for state access without null checks
        if (script.contains("state[") && !script.contains("state.getOrDefault") &&
            !script.contains("state.containsKey") && !script.contains("?: ") &&
            !script.contains("state[\"") && !script.contains("state['")) {
            // This is a heuristic check - may have false positives
        }

        // Check that service calls have error handling
        if (script.contains("invokeService(") &&
            !script.contains(".isSuccess()") && !script.contains(".isFailure()")) {
            warnings.add("Service invocations should check result.isSuccess() before accessing outputs");
        }
    }

    private void validateServiceConsistency(AgentConfig config,
                                            List<String> errors, List<String> warnings) {
        String script = config.execution;
        if (script == null) return;

        // Extract service names used in script
        Set<String> usedServices = new HashSet<>();
        Pattern servicePattern = Pattern.compile(
            "(?:hasService|invokeService)\\s*\\(\\s*[\"']([A-Z_]+)[\"']");
        Matcher matcher = servicePattern.matcher(script);
        while (matcher.find()) {
            usedServices.add(matcher.group(1));
        }

        // Get declared services
        Set<String> declaredRequired = new HashSet<>();
        Set<String> declaredOptional = new HashSet<>();
        if (config.services != null) {
            if (config.services.required != null) {
                declaredRequired.addAll(config.services.required);
            }
            if (config.services.optional != null) {
                declaredOptional.addAll(config.services.optional);
            }
        }
        Set<String> allDeclared = new HashSet<>();
        allDeclared.addAll(declaredRequired);
        allDeclared.addAll(declaredOptional);

        // Check for undeclared service usage
        for (String used : usedServices) {
            if (!allDeclared.contains(used)) {
                // Verify it's a valid service type
                try {
                    ServiceType.valueOf(used);
                    warnings.add("Script uses service '" + used + "' which is not declared in " +
                        "services.required or services.optional. Declare services for safety auditing.");
                } catch (IllegalArgumentException e) {
                    errors.add("Script references unknown service type: '" + used + "'");
                }
            }
        }

        // Check for declared but unused services
        for (String declared : allDeclared) {
            if (!usedServices.contains(declared)) {
                warnings.add("Declared service '" + declared + "' is not used in execution script");
            }
        }
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private String wrapScript(String userScript, ScriptType type) {
        if (type == ScriptType.INITIALIZATION) {
            return """
                import java.time.*
                import java.util.*

                // Mock bindings for compilation
                val config: Any = Unit
                val state: MutableMap<String, Any> = mutableMapOf()
                val log: (String) -> Unit = {}

                // User script
                """ + userScript;
        } else {
            return """
                import java.time.*
                import java.util.*
                import org.carma.arbitration.agent.RealisticAgentFramework.GoalResult

                // Mock bindings for compilation
                val goal: Any = Unit
                val context: Any = Unit
                val state: MutableMap<String, Any> = mutableMapOf()
                val startTime: Long = 0L
                val servicesUsed: MutableList<String> = mutableListOf()
                fun publish(type: String, data: Map<String, Any>) {}

                // User script
                """ + userScript;
        }
    }

    private List<String> prefixErrors(String prefix, List<String> messages) {
        List<String> result = new ArrayList<>();
        for (String msg : messages) {
            result.add("[" + prefix + "] " + msg);
        }
        return result;
    }

    // ========================================================================
    // RESULT CLASSES
    // ========================================================================

    public enum ScriptType {
        INITIALIZATION,
        EXECUTION
    }

    /**
     * Result of validating a single script.
     */
    public static class ScriptValidationResult {
        private final boolean valid;
        private final List<String> errors;
        private final List<String> warnings;

        public ScriptValidationResult(boolean valid, List<String> errors, List<String> warnings) {
            this.valid = valid;
            this.errors = List.copyOf(errors);
            this.warnings = List.copyOf(warnings);
        }

        public boolean isValid() { return valid; }
        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(valid ? "VALID" : "INVALID");
            if (!errors.isEmpty()) {
                sb.append("\n  Errors: ").append(errors);
            }
            if (!warnings.isEmpty()) {
                sb.append("\n  Warnings: ").append(warnings);
            }
            return sb.toString();
        }
    }

    /**
     * Result of validating an entire agent configuration.
     */
    public static class ConfigValidationResult {
        private final String agentId;
        private final boolean valid;
        private final List<String> errors;
        private final List<String> warnings;

        public ConfigValidationResult(String agentId, boolean valid,
                                      List<String> errors, List<String> warnings) {
            this.agentId = agentId;
            this.valid = valid;
            this.errors = List.copyOf(errors);
            this.warnings = List.copyOf(warnings);
        }

        public String getAgentId() { return agentId; }
        public boolean isValid() { return valid; }
        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }

        /**
         * Throw an exception if validation failed.
         */
        public void throwIfInvalid() {
            if (!valid) {
                throw new AgentConfigValidationException(agentId, errors);
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Agent '").append(agentId).append("': ");
            sb.append(valid ? "VALID" : "INVALID");
            if (!errors.isEmpty()) {
                sb.append("\n  Errors:");
                for (String error : errors) {
                    sb.append("\n    - ").append(error);
                }
            }
            if (!warnings.isEmpty()) {
                sb.append("\n  Warnings:");
                for (String warning : warnings) {
                    sb.append("\n    - ").append(warning);
                }
            }
            return sb.toString();
        }
    }

    /**
     * Exception thrown when agent configuration validation fails.
     */
    public static class AgentConfigValidationException extends RuntimeException {
        private final String agentId;
        private final List<String> errors;

        public AgentConfigValidationException(String agentId, List<String> errors) {
            super("Agent '" + agentId + "' failed validation: " + errors);
            this.agentId = agentId;
            this.errors = List.copyOf(errors);
        }

        public String getAgentId() { return agentId; }
        public List<String> getErrors() { return errors; }
    }

    // ========================================================================
    // STATIC UTILITY METHODS
    // ========================================================================

    /**
     * Validate a configuration and throw if invalid.
     * Convenience method for load-time validation.
     */
    public static void validateOrThrow(AgentConfig config) {
        KotlinScriptValidator validator = new KotlinScriptValidator();
        ConfigValidationResult result = validator.validateConfig(config);
        result.throwIfInvalid();
    }

    /**
     * Validate a configuration and return result.
     * Convenience method for programmatic validation.
     */
    public static ConfigValidationResult validate(AgentConfig config) {
        KotlinScriptValidator validator = new KotlinScriptValidator();
        return validator.validateConfig(config);
    }
}
