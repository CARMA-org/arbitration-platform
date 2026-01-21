package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for AI service backends.
 *
 * This is the KEY ABSTRACTION that allows the platform to work with:
 * - MockServiceBackend: For testing and demos (deterministic responses)
 * - LLMServiceBackend: For real LLM API calls (OpenAI, Anthropic, etc.)
 * - CustomServiceBackend: For user-defined backends
 *
 * This abstraction enables integration with real AI agents by allowing
 * implementations to make actual LLM API calls.
 *
 * To integrate real LLMs:
 * 1. Implement this interface with your API client
 * 2. Pass your implementation to AgentRuntime
 * 3. Agents will automatically use real LLM calls
 *
 * Example:
 * <pre>
 * ServiceBackend realBackend = new LLMServiceBackend.Builder()
 *     .openAIKey(System.getenv("OPENAI_API_KEY"))
 *     .anthropicKey(System.getenv("ANTHROPIC_API_KEY"))
 *     .build();
 *
 * AgentRuntime runtime = new AgentRuntime.Builder()
 *     .serviceBackend(realBackend)  // Use real LLMs instead of mocks
 *     .build();
 * </pre>
 */
public interface ServiceBackend {

    /**
     * Result of a service invocation.
     */
    class InvocationResult {
        private final boolean success;
        private final Map<String, Object> output;
        private final String error;
        private final long durationMs;

        public InvocationResult(boolean success, Map<String, Object> output,
                               String error, long durationMs) {
            this.success = success;
            this.output = output;
            this.error = error;
            this.durationMs = durationMs;
        }

        public static InvocationResult success(Map<String, Object> output, long durationMs) {
            return new InvocationResult(true, output, null, durationMs);
        }

        public static InvocationResult failure(String error, long durationMs) {
            return new InvocationResult(false, null, error, durationMs);
        }

        public boolean isSuccess() { return success; }
        public Map<String, Object> getOutput() { return output; }
        public String getError() { return error; }
        public long getDurationMs() { return durationMs; }
    }

    /**
     * Invoke a service by its registered ID.
     *
     * @param serviceId The ID of the service in the registry
     * @param input Input parameters for the service
     * @return Result of the invocation
     */
    InvocationResult invoke(String serviceId, Map<String, Object> input);

    /**
     * Invoke a service by type (auto-selects best available instance).
     *
     * @param type The type of service to invoke
     * @param input Input parameters for the service
     * @return Result of the invocation
     */
    InvocationResult invokeByType(ServiceType type, Map<String, Object> input);

    /**
     * Invoke a service asynchronously.
     *
     * @param serviceId The ID of the service in the registry
     * @param input Input parameters for the service
     * @return Future containing the result
     */
    CompletableFuture<InvocationResult> invokeAsync(String serviceId, Map<String, Object> input);

    /**
     * Check if this backend supports a specific service type.
     *
     * @param type The service type to check
     * @return true if the backend can handle this service type
     */
    boolean supportsServiceType(ServiceType type);

    /**
     * Get the name of this backend (for logging/debugging).
     */
    String getName();

    /**
     * Shutdown the backend and release resources.
     */
    void shutdown();
}
