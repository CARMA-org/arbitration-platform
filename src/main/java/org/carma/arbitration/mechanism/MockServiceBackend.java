package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Mock backend implementations for AI services.
 * 
 * Provides deterministic test responses for all service types, enabling:
 * - Unit testing without external dependencies
 * - Integration testing of composition pipelines
 * - Performance benchmarking with controlled latency
 * - Failure injection for resilience testing
 */
public class MockServiceBackend {

    private final ServiceRegistry registry;
    private final Map<ServiceType, ServiceHandler> handlers;
    private final ExecutorService executor;
    private final MockConfig config;
    private final Map<String, List<ServiceInvocation>> invocationLog;
    private final Random random;

    /**
     * Configuration for mock behavior.
     */
    public static class MockConfig {
        private boolean simulateLatency = true;
        private double latencyMultiplier = 0.1;  // 10% of base latency
        private double failureRate = 0.0;
        private boolean logInvocations = true;
        private long randomSeed = 42;

        public MockConfig simulateLatency(boolean simulate) {
            this.simulateLatency = simulate;
            return this;
        }

        public MockConfig latencyMultiplier(double multiplier) {
            this.latencyMultiplier = multiplier;
            return this;
        }

        public MockConfig failureRate(double rate) {
            this.failureRate = rate;
            return this;
        }

        public MockConfig logInvocations(boolean log) {
            this.logInvocations = log;
            return this;
        }

        public MockConfig randomSeed(long seed) {
            this.randomSeed = seed;
            return this;
        }

        public static MockConfig defaults() {
            return new MockConfig();
        }

        public static MockConfig fast() {
            return new MockConfig().simulateLatency(false);
        }

        public static MockConfig realistic() {
            return new MockConfig().latencyMultiplier(0.5);
        }

        public static MockConfig unreliable() {
            return new MockConfig().failureRate(0.1);
        }
    }

    /**
     * Record of a service invocation.
     */
    public static class ServiceInvocation {
        private final String serviceId;
        private final ServiceType serviceType;
        private final Map<String, Object> input;
        private final Map<String, Object> output;
        private final long timestampMs;
        private final long durationMs;
        private final boolean success;
        private final String error;

        public ServiceInvocation(String serviceId, ServiceType serviceType,
                                Map<String, Object> input, Map<String, Object> output,
                                long durationMs, boolean success, String error) {
            this.serviceId = serviceId;
            this.serviceType = serviceType;
            this.input = input;
            this.output = output;
            this.timestampMs = System.currentTimeMillis();
            this.durationMs = durationMs;
            this.success = success;
            this.error = error;
        }

        public String getServiceId() { return serviceId; }
        public ServiceType getServiceType() { return serviceType; }
        public Map<String, Object> getInput() { return input; }
        public Map<String, Object> getOutput() { return output; }
        public long getTimestampMs() { return timestampMs; }
        public long getDurationMs() { return durationMs; }
        public boolean isSuccess() { return success; }
        public String getError() { return error; }

        @Override
        public String toString() {
            return String.format("Invocation[%s: %s, %dms, %s]",
                serviceId, serviceType, durationMs, success ? "OK" : "FAILED");
        }
    }

    /**
     * Handler interface for service execution.
     */
    @FunctionalInterface
    public interface ServiceHandler {
        Map<String, Object> handle(Map<String, Object> input) throws Exception;
    }

    /**
     * Result of service invocation.
     */
    public static class InvocationResult {
        private final boolean success;
        private final Map<String, Object> output;
        private final String error;
        private final long durationMs;

        private InvocationResult(boolean success, Map<String, Object> output, 
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

    public MockServiceBackend(ServiceRegistry registry) {
        this(registry, MockConfig.defaults());
    }

    public MockServiceBackend(ServiceRegistry registry, MockConfig config) {
        this.registry = registry;
        this.config = config;
        this.handlers = new ConcurrentHashMap<>();
        this.executor = Executors.newCachedThreadPool();
        this.invocationLog = new ConcurrentHashMap<>();
        this.random = new Random(config.randomSeed);

        // Register default handlers for all service types
        registerDefaultHandlers();
    }

    // ========================================================================
    // Handler Registration
    // ========================================================================

    /**
     * Register a custom handler for a service type.
     */
    public void registerHandler(ServiceType type, ServiceHandler handler) {
        handlers.put(type, handler);
    }

    /**
     * Register default mock handlers.
     */
    private void registerDefaultHandlers() {
        // Text services
        handlers.put(ServiceType.TEXT_GENERATION, this::handleTextGeneration);
        handlers.put(ServiceType.TEXT_EMBEDDING, this::handleTextEmbedding);
        handlers.put(ServiceType.TEXT_CLASSIFICATION, this::handleTextClassification);
        handlers.put(ServiceType.TEXT_SUMMARIZATION, this::handleTextSummarization);

        // Vision services
        handlers.put(ServiceType.IMAGE_ANALYSIS, this::handleImageAnalysis);
        handlers.put(ServiceType.IMAGE_GENERATION, this::handleImageGeneration);
        handlers.put(ServiceType.OCR, this::handleOCR);

        // Audio services
        handlers.put(ServiceType.SPEECH_TO_TEXT, this::handleSpeechToText);
        handlers.put(ServiceType.TEXT_TO_SPEECH, this::handleTextToSpeech);

        // Reasoning services
        handlers.put(ServiceType.CODE_GENERATION, this::handleCodeGeneration);
        handlers.put(ServiceType.CODE_ANALYSIS, this::handleCodeAnalysis);
        handlers.put(ServiceType.REASONING, this::handleReasoning);

        // Data services
        handlers.put(ServiceType.DATA_EXTRACTION, this::handleDataExtraction);
        handlers.put(ServiceType.VECTOR_SEARCH, this::handleVectorSearch);
        handlers.put(ServiceType.KNOWLEDGE_RETRIEVAL, this::handleKnowledgeRetrieval);
    }

    // ========================================================================
    // Service Invocation
    // ========================================================================

    /**
     * Invoke a service synchronously.
     */
    public InvocationResult invoke(String serviceId, Map<String, Object> input) {
        long startTime = System.currentTimeMillis();

        Optional<AIService> serviceOpt = registry.get(serviceId);
        if (serviceOpt.isEmpty()) {
            return InvocationResult.failure("Service not found: " + serviceId, 0);
        }

        AIService service = serviceOpt.get();
        ServiceType type = service.getType();
        ServiceHandler handler = handlers.get(type);

        if (handler == null) {
            return InvocationResult.failure("No handler for service type: " + type, 0);
        }

        // Check availability
        if (!service.isAvailable()) {
            return InvocationResult.failure("Service unavailable: " + serviceId, 0);
        }

        // Simulate latency
        if (config.simulateLatency) {
            int baseLatency = type.getBaseLatencyMs();
            int simulatedLatency = (int) (baseLatency * config.latencyMultiplier);
            try {
                Thread.sleep(simulatedLatency);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Simulate failures
        if (config.failureRate > 0 && random.nextDouble() < config.failureRate) {
            long duration = System.currentTimeMillis() - startTime;
            logInvocation(serviceId, type, input, null, duration, false, "Simulated failure");
            return InvocationResult.failure("Simulated failure", duration);
        }

        try {
            Map<String, Object> output = handler.handle(input);
            long duration = System.currentTimeMillis() - startTime;
            logInvocation(serviceId, type, input, output, duration, true, null);
            return InvocationResult.success(output, duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logInvocation(serviceId, type, input, null, duration, false, e.getMessage());
            return InvocationResult.failure(e.getMessage(), duration);
        }
    }

    /**
     * Invoke a service asynchronously.
     */
    public CompletableFuture<InvocationResult> invokeAsync(String serviceId, Map<String, Object> input) {
        return CompletableFuture.supplyAsync(() -> invoke(serviceId, input), executor);
    }

    /**
     * Invoke a service by type (auto-selects best available instance).
     */
    public InvocationResult invokeByType(ServiceType type, Map<String, Object> input) {
        Optional<AIService> service = registry.query()
            .ofType(type)
            .availableOnly()
            .findBestByLatency();

        if (service.isEmpty()) {
            return InvocationResult.failure("No available service of type: " + type, 0);
        }

        return invoke(service.get().getServiceId(), input);
    }

    // ========================================================================
    // Default Handlers
    // ========================================================================

    private Map<String, Object> handleTextGeneration(Map<String, Object> input) {
        String prompt = (String) input.getOrDefault("prompt", "");
        int maxTokens = (int) input.getOrDefault("max_tokens", 100);
        
        String generated = generateMockText(prompt, maxTokens);
        
        return Map.of(
            "text", generated,
            "tokens_used", generated.split("\\s+").length,
            "finish_reason", "complete"
        );
    }

    private Map<String, Object> handleTextEmbedding(Map<String, Object> input) {
        String text = (String) input.getOrDefault("text", "");
        int dimensions = (int) input.getOrDefault("dimensions", 768);
        
        // Generate deterministic mock embedding based on text hash
        double[] embedding = new double[dimensions];
        int hash = text.hashCode();
        Random r = new Random(hash);
        for (int i = 0; i < dimensions; i++) {
            embedding[i] = r.nextGaussian() * 0.1;
        }
        
        // Normalize
        double norm = 0;
        for (double v : embedding) norm += v * v;
        norm = Math.sqrt(norm);
        for (int i = 0; i < dimensions; i++) embedding[i] /= norm;
        
        return Map.of(
            "embedding", embedding,
            "dimensions", dimensions,
            "model", "mock-embedding-v1"
        );
    }

    private Map<String, Object> handleTextClassification(Map<String, Object> input) {
        String text = (String) input.getOrDefault("text", "");
        
        // Mock classification based on keywords
        String category = "neutral";
        double confidence = 0.85;
        
        String lower = text.toLowerCase();
        if (lower.contains("error") || lower.contains("fail") || lower.contains("bad")) {
            category = "negative";
            confidence = 0.92;
        } else if (lower.contains("success") || lower.contains("good") || lower.contains("great")) {
            category = "positive";
            confidence = 0.89;
        }
        
        return Map.of(
            "category", category,
            "confidence", confidence,
            "labels", List.of("positive", "negative", "neutral")
        );
    }

    private Map<String, Object> handleTextSummarization(Map<String, Object> input) {
        String text = (String) input.getOrDefault("text", "");
        int maxLength = (int) input.getOrDefault("max_length", 50);
        
        // Simple mock: take first N words
        String[] words = text.split("\\s+");
        int wordCount = Math.min(maxLength, words.length);
        String summary = String.join(" ", Arrays.copyOf(words, wordCount));
        if (wordCount < words.length) {
            summary += "...";
        }
        
        return Map.of(
            "summary", summary,
            "original_length", words.length,
            "summary_length", wordCount
        );
    }

    private Map<String, Object> handleImageAnalysis(Map<String, Object> input) {
        // Mock image analysis results
        return Map.of(
            "objects", List.of(
                Map.of("label", "person", "confidence", 0.95, "bbox", List.of(10, 20, 100, 200)),
                Map.of("label", "car", "confidence", 0.87, "bbox", List.of(150, 100, 300, 250))
            ),
            "scene", "outdoor",
            "scene_confidence", 0.82,
            "colors", List.of("blue", "green", "gray"),
            "description", "An outdoor scene with a person near a car"
        );
    }

    private Map<String, Object> handleImageGeneration(Map<String, Object> input) {
        String prompt = (String) input.getOrDefault("prompt", "");
        int width = (int) input.getOrDefault("width", 512);
        int height = (int) input.getOrDefault("height", 512);
        
        // Return mock image data (base64 placeholder)
        String mockBase64 = Base64.getEncoder().encodeToString(
            ("MOCK_IMAGE:" + prompt.hashCode()).getBytes());
        
        return Map.of(
            "image_base64", mockBase64,
            "width", width,
            "height", height,
            "format", "png",
            "seed", Math.abs(prompt.hashCode())
        );
    }

    private Map<String, Object> handleOCR(Map<String, Object> input) {
        return Map.of(
            "text", "Mock extracted text from image.\nLine 2 of extracted content.",
            "confidence", 0.94,
            "blocks", List.of(
                Map.of("text", "Mock extracted text from image.", "confidence", 0.96),
                Map.of("text", "Line 2 of extracted content.", "confidence", 0.92)
            )
        );
    }

    private Map<String, Object> handleSpeechToText(Map<String, Object> input) {
        return Map.of(
            "text", "This is mock transcribed text from audio input.",
            "confidence", 0.91,
            "duration_seconds", 5.2,
            "language", "en-US",
            "words", List.of(
                Map.of("word", "This", "start", 0.0, "end", 0.3),
                Map.of("word", "is", "start", 0.3, "end", 0.5),
                Map.of("word", "mock", "start", 0.5, "end", 0.9)
            )
        );
    }

    private Map<String, Object> handleTextToSpeech(Map<String, Object> input) {
        String text = (String) input.getOrDefault("text", "");
        
        // Mock audio data
        String mockAudio = Base64.getEncoder().encodeToString(
            ("MOCK_AUDIO:" + text.hashCode()).getBytes());
        
        return Map.of(
            "audio_base64", mockAudio,
            "format", "mp3",
            "duration_seconds", text.length() * 0.05,
            "sample_rate", 22050
        );
    }

    private Map<String, Object> handleCodeGeneration(Map<String, Object> input) {
        String prompt = (String) input.getOrDefault("prompt", "");
        String language = (String) input.getOrDefault("language", "python");
        
        String code = String.format("""
            # Generated code for: %s
            def generated_function():
                # Mock implementation
                result = "Hello from generated code"
                return result
            
            if __name__ == "__main__":
                print(generated_function())
            """, prompt);
        
        return Map.of(
            "code", code,
            "language", language,
            "tokens_used", code.split("\\s+").length
        );
    }

    private Map<String, Object> handleCodeAnalysis(Map<String, Object> input) {
        String code = (String) input.getOrDefault("code", "");
        
        return Map.of(
            "issues", List.of(
                Map.of("type", "style", "message", "Consider adding type hints", "line", 1),
                Map.of("type", "info", "message", "Function could be simplified", "line", 3)
            ),
            "complexity", Map.of("cyclomatic", 5, "cognitive", 3),
            "lines_of_code", code.split("\n").length,
            "summary", "Code analysis complete. 2 suggestions found."
        );
    }

    private Map<String, Object> handleReasoning(Map<String, Object> input) {
        String query = (String) input.getOrDefault("query", "");
        
        return Map.of(
            "answer", "Based on the analysis, the answer is: Mock reasoning result.",
            "reasoning_steps", List.of(
                "Step 1: Analyze the input query",
                "Step 2: Apply logical reasoning",
                "Step 3: Synthesize conclusion"
            ),
            "confidence", 0.88,
            "sources", List.of("internal_knowledge", "logical_inference")
        );
    }

    private Map<String, Object> handleDataExtraction(Map<String, Object> input) {
        return Map.of(
            "entities", List.of(
                Map.of("type", "person", "value", "John Doe", "confidence", 0.95),
                Map.of("type", "date", "value", "2024-01-15", "confidence", 0.92),
                Map.of("type", "location", "value", "New York", "confidence", 0.89)
            ),
            "structured_data", Map.of(
                "name", "John Doe",
                "date", "2024-01-15",
                "location", "New York"
            )
        );
    }

    private Map<String, Object> handleVectorSearch(Map<String, Object> input) {
        @SuppressWarnings("unchecked")
        double[] query = (double[]) input.getOrDefault("query_vector", new double[768]);
        int k = (int) input.getOrDefault("k", 5);
        
        // Mock search results
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            results.add(Map.of(
                "id", "doc_" + (i + 1),
                "score", 0.95 - (i * 0.05),
                "content", "Mock document " + (i + 1) + " content"
            ));
        }
        
        return Map.of(
            "results", results,
            "query_time_ms", 12,
            "total_matches", k
        );
    }

    private Map<String, Object> handleKnowledgeRetrieval(Map<String, Object> input) {
        String query = (String) input.getOrDefault("query", "");
        
        return Map.of(
            "passages", List.of(
                Map.of("text", "Relevant knowledge passage 1 for: " + query, "relevance", 0.92),
                Map.of("text", "Relevant knowledge passage 2 for: " + query, "relevance", 0.85)
            ),
            "sources", List.of("knowledge_base_1", "knowledge_base_2"),
            "answer", "Based on retrieved knowledge: Mock answer for " + query
        );
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    private String generateMockText(String prompt, int maxTokens) {
        String[] mockResponses = {
            "This is a mock response generated for testing purposes.",
            "The system has processed your request successfully.",
            "Based on the input provided, here is the generated output.",
            "Mock generation complete. This text simulates AI output.",
            "Testing response: The quick brown fox jumps over the lazy dog."
        };
        
        int index = Math.abs(prompt.hashCode()) % mockResponses.length;
        String base = mockResponses[index];
        
        // Extend or truncate to approximate token count
        StringBuilder result = new StringBuilder(base);
        while (result.toString().split("\\s+").length < maxTokens && result.length() < maxTokens * 10) {
            result.append(" ").append(base);
        }
        
        String[] words = result.toString().split("\\s+");
        return String.join(" ", Arrays.copyOf(words, Math.min(maxTokens, words.length)));
    }

    private void logInvocation(String serviceId, ServiceType type, Map<String, Object> input,
                              Map<String, Object> output, long duration, boolean success, String error) {
        if (!config.logInvocations) return;

        ServiceInvocation invocation = new ServiceInvocation(
            serviceId, type, input, output, duration, success, error);
        
        invocationLog.computeIfAbsent(serviceId, k -> 
            Collections.synchronizedList(new ArrayList<>())).add(invocation);
    }

    // ========================================================================
    // Invocation Log Access
    // ========================================================================

    /**
     * Get all invocations for a service.
     */
    public List<ServiceInvocation> getInvocations(String serviceId) {
        return invocationLog.getOrDefault(serviceId, Collections.emptyList());
    }

    /**
     * Get all invocations.
     */
    public List<ServiceInvocation> getAllInvocations() {
        List<ServiceInvocation> all = new ArrayList<>();
        for (List<ServiceInvocation> list : invocationLog.values()) {
            all.addAll(list);
        }
        all.sort(Comparator.comparingLong(ServiceInvocation::getTimestampMs));
        return all;
    }

    /**
     * Get invocation count.
     */
    public int getInvocationCount() {
        return invocationLog.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Clear invocation log.
     */
    public void clearLog() {
        invocationLog.clear();
    }

    /**
     * Get success rate across all invocations.
     */
    public double getSuccessRate() {
        List<ServiceInvocation> all = getAllInvocations();
        if (all.isEmpty()) return 1.0;
        long successful = all.stream().filter(ServiceInvocation::isSuccess).count();
        return (double) successful / all.size();
    }

    /**
     * Get average latency across all invocations.
     */
    public double getAverageLatency() {
        List<ServiceInvocation> all = getAllInvocations();
        if (all.isEmpty()) return 0.0;
        return all.stream().mapToLong(ServiceInvocation::getDurationMs).average().orElse(0.0);
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * Shutdown the executor.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String toString() {
        return String.format("MockServiceBackend[handlers=%d, invocations=%d, success=%.1f%%]",
            handlers.size(), getInvocationCount(), getSuccessRate() * 100);
    }
}
