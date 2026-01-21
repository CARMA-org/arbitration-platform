package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Real LLM service backend for actual AI service integration.
 *
 * This backend makes REAL API calls to LLM providers (OpenAI, Anthropic, etc.)
 * instead of returning mock responses.
 *
 * This is the key component that enables real AI agent integration,
 * allowing agents to make actual LLM calls instead of using mock responses.
 *
 * Usage:
 * <pre>
 * // Create backend with API keys
 * LLMServiceBackend backend = new LLMServiceBackend.Builder()
 *     .openAIKey(System.getenv("OPENAI_API_KEY"))
 *     .anthropicKey(System.getenv("ANTHROPIC_API_KEY"))
 *     .defaultProvider(Provider.OPENAI)
 *     .timeout(Duration.ofSeconds(30))
 *     .build();
 *
 * // Use in agent runtime
 * AgentRuntime runtime = new AgentRuntime.Builder()
 *     .serviceBackend(backend)
 *     .build();
 *
 * // Now agents make REAL LLM calls
 * runtime.register(newsAgent);
 * runtime.start();
 * </pre>
 *
 * Supported Providers:
 * - OPENAI: GPT-4, GPT-3.5, DALL-E, Whisper, TTS
 * - ANTHROPIC: Claude models
 * - LOCAL: Ollama or other local LLM servers
 * - CUSTOM: User-defined HTTP endpoints
 *
 * @see ServiceBackend
 * @see MockServiceBackend
 */
public class LLMServiceBackend implements ServiceBackend {

    /**
     * Supported LLM providers.
     */
    public enum Provider {
        OPENAI("https://api.openai.com/v1"),
        ANTHROPIC("https://api.anthropic.com/v1"),
        GEMINI("https://generativelanguage.googleapis.com/v1beta/models"),
        LOCAL("http://localhost:11434"),  // Ollama default
        CUSTOM(null);

        private final String baseUrl;

        Provider(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getBaseUrl() {
            return baseUrl;
        }
    }

    /**
     * Configuration for service type to provider mapping.
     */
    public static class ServiceConfig {
        private final Provider provider;
        private final String model;
        private final String endpoint;
        private final Map<String, Object> defaultParams;

        public ServiceConfig(Provider provider, String model, String endpoint,
                            Map<String, Object> defaultParams) {
            this.provider = provider;
            this.model = model;
            this.endpoint = endpoint;
            this.defaultParams = defaultParams != null ? defaultParams : Map.of();
        }

        public Provider getProvider() { return provider; }
        public String getModel() { return model; }
        public String getEndpoint() { return endpoint; }
        public Map<String, Object> getDefaultParams() { return defaultParams; }
    }

    private final Map<Provider, String> apiKeys;
    private final Map<Provider, String> baseUrls;
    private final Map<ServiceType, ServiceConfig> serviceConfigs;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final Duration timeout;
    private final boolean logRequests;
    private final List<InvocationRecord> invocationLog;

    /**
     * Record of an invocation for debugging/monitoring.
     */
    public static class InvocationRecord {
        public final ServiceType serviceType;
        public final Provider provider;
        public final String model;
        public final long timestampMs;
        public final long durationMs;
        public final boolean success;
        public final String error;

        public InvocationRecord(ServiceType serviceType, Provider provider, String model,
                               long durationMs, boolean success, String error) {
            this.serviceType = serviceType;
            this.provider = provider;
            this.model = model;
            this.timestampMs = System.currentTimeMillis();
            this.durationMs = durationMs;
            this.success = success;
            this.error = error;
        }
    }

    private LLMServiceBackend(Builder builder) {
        this.apiKeys = new HashMap<>(builder.apiKeys);
        this.baseUrls = new HashMap<>(builder.baseUrls);
        this.serviceConfigs = new HashMap<>(builder.serviceConfigs);
        this.timeout = builder.timeout;
        this.logRequests = builder.logRequests;
        this.invocationLog = Collections.synchronizedList(new ArrayList<>());
        this.executor = Executors.newCachedThreadPool();

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .executor(executor)
            .build();

        // Apply default base URLs
        for (Provider p : Provider.values()) {
            if (p.getBaseUrl() != null && !baseUrls.containsKey(p)) {
                baseUrls.put(p, p.getBaseUrl());
            }
        }

        // Set up default service configurations if not provided
        setupDefaultConfigs(builder.defaultProvider);
    }

    private void setupDefaultConfigs(Provider defaultProvider) {
        // Text generation
        if (!serviceConfigs.containsKey(ServiceType.TEXT_GENERATION)) {
            if (defaultProvider == Provider.OPENAI) {
                serviceConfigs.put(ServiceType.TEXT_GENERATION,
                    new ServiceConfig(Provider.OPENAI, "gpt-4", "/chat/completions", null));
            } else if (defaultProvider == Provider.ANTHROPIC) {
                serviceConfigs.put(ServiceType.TEXT_GENERATION,
                    new ServiceConfig(Provider.ANTHROPIC, "claude-3-opus-20240229", "/messages", null));
            } else if (defaultProvider == Provider.GEMINI) {
                serviceConfigs.put(ServiceType.TEXT_GENERATION,
                    new ServiceConfig(Provider.GEMINI, "gemini-2.5-flash", ":generateContent", null));
            }
        }

        // Text embedding
        if (!serviceConfigs.containsKey(ServiceType.TEXT_EMBEDDING)) {
            serviceConfigs.put(ServiceType.TEXT_EMBEDDING,
                new ServiceConfig(Provider.OPENAI, "text-embedding-3-small", "/embeddings", null));
        }

        // Text summarization (uses text generation with specific prompt)
        if (!serviceConfigs.containsKey(ServiceType.TEXT_SUMMARIZATION)) {
            serviceConfigs.put(ServiceType.TEXT_SUMMARIZATION,
                serviceConfigs.get(ServiceType.TEXT_GENERATION));
        }

        // Image generation
        if (!serviceConfigs.containsKey(ServiceType.IMAGE_GENERATION)) {
            serviceConfigs.put(ServiceType.IMAGE_GENERATION,
                new ServiceConfig(Provider.OPENAI, "dall-e-3", "/images/generations", null));
        }

        // Speech to text
        if (!serviceConfigs.containsKey(ServiceType.SPEECH_TO_TEXT)) {
            serviceConfigs.put(ServiceType.SPEECH_TO_TEXT,
                new ServiceConfig(Provider.OPENAI, "whisper-1", "/audio/transcriptions", null));
        }

        // Text to speech
        if (!serviceConfigs.containsKey(ServiceType.TEXT_TO_SPEECH)) {
            serviceConfigs.put(ServiceType.TEXT_TO_SPEECH,
                new ServiceConfig(Provider.OPENAI, "tts-1", "/audio/speech", null));
        }

        // Code generation (uses text generation)
        if (!serviceConfigs.containsKey(ServiceType.CODE_GENERATION)) {
            serviceConfigs.put(ServiceType.CODE_GENERATION,
                serviceConfigs.get(ServiceType.TEXT_GENERATION));
        }

        // Reasoning (uses text generation with specific prompt)
        if (!serviceConfigs.containsKey(ServiceType.REASONING)) {
            serviceConfigs.put(ServiceType.REASONING,
                serviceConfigs.get(ServiceType.TEXT_GENERATION));
        }
    }

    // ========================================================================
    // ServiceBackend Interface Implementation
    // ========================================================================

    @Override
    public InvocationResult invoke(String serviceId, Map<String, Object> input) {
        // For LLM backend, serviceId maps to a service type
        // In production, you might have a registry lookup here
        ServiceType type = (ServiceType) input.get("_serviceType");
        if (type == null) {
            return InvocationResult.failure("No service type specified", 0);
        }
        return invokeByType(type, input);
    }

    @Override
    public InvocationResult invokeByType(ServiceType type, Map<String, Object> input) {
        long startTime = System.currentTimeMillis();

        ServiceConfig config = serviceConfigs.get(type);
        if (config == null) {
            return InvocationResult.failure("No configuration for service type: " + type, 0);
        }

        String apiKey = apiKeys.get(config.getProvider());
        if (apiKey == null || apiKey.isEmpty()) {
            return InvocationResult.failure(
                "No API key configured for provider: " + config.getProvider() +
                ". Set " + config.getProvider().name() + "_API_KEY environment variable.", 0);
        }

        try {
            Map<String, Object> result = executeRequest(type, config, input, apiKey);
            long duration = System.currentTimeMillis() - startTime;

            if (logRequests) {
                invocationLog.add(new InvocationRecord(type, config.getProvider(),
                    config.getModel(), duration, true, null));
            }

            return InvocationResult.success(result, duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;

            if (logRequests) {
                invocationLog.add(new InvocationRecord(type, config.getProvider(),
                    config.getModel(), duration, false, e.getMessage()));
            }

            return InvocationResult.failure(e.getMessage(), duration);
        }
    }

    @Override
    public CompletableFuture<InvocationResult> invokeAsync(String serviceId, Map<String, Object> input) {
        return CompletableFuture.supplyAsync(() -> invoke(serviceId, input), executor);
    }

    @Override
    public boolean supportsServiceType(ServiceType type) {
        return serviceConfigs.containsKey(type);
    }

    @Override
    public String getName() {
        return "LLMServiceBackend";
    }

    @Override
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

    // ========================================================================
    // HTTP Request Execution
    // ========================================================================

    private Map<String, Object> executeRequest(ServiceType type, ServiceConfig config,
                                               Map<String, Object> input, String apiKey)
            throws Exception {

        String baseUrl = baseUrls.get(config.getProvider());
        String url;

        // Build URL based on provider (Gemini has different format)
        if (config.getProvider() == Provider.GEMINI) {
            // Gemini URL format: baseUrl/model:generateContent?key=apiKey
            url = baseUrl + "/" + config.getModel() + config.getEndpoint() + "?key=" + apiKey;
        } else {
            url = baseUrl + config.getEndpoint();
        }

        // Build request body based on service type and provider
        String requestBody = buildRequestBody(type, config, input);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "application/json");

        // Add authorization header based on provider
        switch (config.getProvider()) {
            case OPENAI:
                requestBuilder.header("Authorization", "Bearer " + apiKey);
                break;
            case ANTHROPIC:
                requestBuilder.header("x-api-key", apiKey);
                requestBuilder.header("anthropic-version", "2023-06-01");
                break;
            case GEMINI:
                // Gemini uses API key in query string (already added to URL)
                break;
            case LOCAL:
                // Ollama typically doesn't need auth
                break;
            case CUSTOM:
                requestBuilder.header("Authorization", "Bearer " + apiKey);
                break;
        }

        HttpRequest request = requestBuilder
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            throw new RuntimeException("API error " + response.statusCode() + ": " + response.body());
        }

        return parseResponse(type, config, response.body());
    }

    private String buildRequestBody(ServiceType type, ServiceConfig config,
                                    Map<String, Object> input) {
        StringBuilder json = new StringBuilder("{");

        switch (type) {
            case TEXT_GENERATION:
            case TEXT_SUMMARIZATION:
            case CODE_GENERATION:
            case REASONING:
                if (config.getProvider() == Provider.OPENAI) {
                    json.append("\"model\":\"").append(config.getModel()).append("\",");
                    json.append("\"messages\":[{\"role\":\"user\",\"content\":\"");
                    json.append(escapeJson(getPrompt(type, input)));
                    json.append("\"}]");
                    if (input.containsKey("max_tokens")) {
                        json.append(",\"max_tokens\":").append(input.get("max_tokens"));
                    }
                } else if (config.getProvider() == Provider.ANTHROPIC) {
                    json.append("\"model\":\"").append(config.getModel()).append("\",");
                    json.append("\"max_tokens\":").append(input.getOrDefault("max_tokens", 1024)).append(",");
                    json.append("\"messages\":[{\"role\":\"user\",\"content\":\"");
                    json.append(escapeJson(getPrompt(type, input)));
                    json.append("\"}]");
                } else if (config.getProvider() == Provider.GEMINI) {
                    // Gemini API format
                    json.append("\"contents\":[{\"parts\":[{\"text\":\"");
                    json.append(escapeJson(getPrompt(type, input)));
                    json.append("\"}]}]");
                    // Optional generation config
                    json.append(",\"generationConfig\":{");
                    json.append("\"maxOutputTokens\":").append(input.getOrDefault("max_tokens", 4096));
                    json.append(",\"temperature\":").append(input.getOrDefault("temperature", 0.7));
                    json.append("}");
                }
                break;

            case TEXT_EMBEDDING:
                json.append("\"model\":\"").append(config.getModel()).append("\",");
                json.append("\"input\":\"").append(escapeJson((String) input.getOrDefault("text", ""))).append("\"");
                break;

            case IMAGE_GENERATION:
                json.append("\"model\":\"").append(config.getModel()).append("\",");
                json.append("\"prompt\":\"").append(escapeJson((String) input.getOrDefault("prompt", ""))).append("\",");
                json.append("\"n\":1,");
                json.append("\"size\":\"").append(input.getOrDefault("size", "1024x1024")).append("\"");
                break;

            default:
                json.append("\"model\":\"").append(config.getModel()).append("\",");
                json.append("\"input\":\"").append(escapeJson(input.toString())).append("\"");
        }

        json.append("}");
        return json.toString();
    }

    private String getPrompt(ServiceType type, Map<String, Object> input) {
        String basePrompt = (String) input.getOrDefault("prompt",
            input.getOrDefault("text", input.getOrDefault("query", "")));

        switch (type) {
            case TEXT_SUMMARIZATION:
                return "Please summarize the following text concisely:\n\n" + basePrompt;
            case CODE_GENERATION:
                return "Generate code for the following requirement:\n\n" + basePrompt;
            case REASONING:
                return "Please reason through the following step by step:\n\n" + basePrompt;
            default:
                return basePrompt;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResponse(ServiceType type, ServiceConfig config,
                                              String responseBody) {
        // Simple JSON parsing - in production, use a proper JSON library
        Map<String, Object> result = new HashMap<>();

        try {
            if (config.getProvider() == Provider.OPENAI) {
                if (type == ServiceType.TEXT_GENERATION || type == ServiceType.TEXT_SUMMARIZATION ||
                    type == ServiceType.CODE_GENERATION || type == ServiceType.REASONING) {
                    // Extract content from choices[0].message.content
                    int contentStart = responseBody.indexOf("\"content\":\"");
                    if (contentStart > 0) {
                        contentStart += 11;
                        int contentEnd = findEndOfJsonString(responseBody, contentStart);
                        String content = unescapeJson(responseBody.substring(contentStart, contentEnd));
                        result.put("text", content);
                        result.put("content", content);
                    }
                } else if (type == ServiceType.TEXT_EMBEDDING) {
                    // Extract embedding array
                    int embeddingStart = responseBody.indexOf("\"embedding\":[");
                    if (embeddingStart > 0) {
                        embeddingStart += 13;
                        int embeddingEnd = responseBody.indexOf("]", embeddingStart);
                        String embeddingStr = responseBody.substring(embeddingStart, embeddingEnd);
                        String[] parts = embeddingStr.split(",");
                        double[] embedding = new double[parts.length];
                        for (int i = 0; i < parts.length; i++) {
                            embedding[i] = Double.parseDouble(parts[i].trim());
                        }
                        result.put("embedding", embedding);
                    }
                } else if (type == ServiceType.IMAGE_GENERATION) {
                    // Extract URL or base64
                    int urlStart = responseBody.indexOf("\"url\":\"");
                    if (urlStart > 0) {
                        urlStart += 7;
                        int urlEnd = findEndOfJsonString(responseBody, urlStart);
                        result.put("url", responseBody.substring(urlStart, urlEnd));
                    }
                }
            } else if (config.getProvider() == Provider.ANTHROPIC) {
                // Extract content from content[0].text
                int textStart = responseBody.indexOf("\"text\":\"");
                if (textStart > 0) {
                    textStart += 8;
                    int textEnd = findEndOfJsonString(responseBody, textStart);
                    String text = unescapeJson(responseBody.substring(textStart, textEnd));
                    result.put("text", text);
                    result.put("content", text);
                }
            } else if (config.getProvider() == Provider.GEMINI) {
                // Gemini response format: candidates[0].content.parts[0].text
                // Look for "text": " or "text":" in the parts array (JSON may have space after colon)
                int textStart = responseBody.indexOf("\"text\": \"");
                int offset = 9;  // length of "text": "
                if (textStart < 0) {
                    textStart = responseBody.indexOf("\"text\":\"");
                    offset = 8;  // length of "text":"
                }
                if (textStart > 0) {
                    textStart += offset;
                    int textEnd = findEndOfJsonString(responseBody, textStart);
                    String text = unescapeJson(responseBody.substring(textStart, textEnd));
                    result.put("text", text);
                    result.put("content", text);
                }
            }
        } catch (Exception e) {
            result.put("raw_response", responseBody);
            result.put("parse_error", e.getMessage());
        }

        result.put("provider", config.getProvider().name());
        result.put("model", config.getModel());

        return result;
    }

    private int findEndOfJsonString(String json, int start) {
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return i;
            }
        }
        return json.length();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String unescapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    // ========================================================================
    // Monitoring
    // ========================================================================

    public List<InvocationRecord> getInvocationLog() {
        return new ArrayList<>(invocationLog);
    }

    public void clearLog() {
        invocationLog.clear();
    }

    // ========================================================================
    // Builder
    // ========================================================================

    public static class Builder {
        private final Map<Provider, String> apiKeys = new HashMap<>();
        private final Map<Provider, String> baseUrls = new HashMap<>();
        private final Map<ServiceType, ServiceConfig> serviceConfigs = new HashMap<>();
        private Provider defaultProvider = Provider.OPENAI;
        private Duration timeout = Duration.ofSeconds(60);
        private boolean logRequests = true;

        /**
         * Set OpenAI API key.
         */
        public Builder openAIKey(String key) {
            if (key != null && !key.isEmpty()) {
                apiKeys.put(Provider.OPENAI, key);
            }
            return this;
        }

        /**
         * Set Anthropic API key.
         */
        public Builder anthropicKey(String key) {
            if (key != null && !key.isEmpty()) {
                apiKeys.put(Provider.ANTHROPIC, key);
            }
            return this;
        }

        /**
         * Set Google Gemini API key.
         */
        public Builder geminiKey(String key) {
            if (key != null && !key.isEmpty()) {
                apiKeys.put(Provider.GEMINI, key);
            }
            return this;
        }

        /**
         * Set API key for a specific provider.
         */
        public Builder apiKey(Provider provider, String key) {
            if (key != null && !key.isEmpty()) {
                apiKeys.put(provider, key);
            }
            return this;
        }

        /**
         * Set custom base URL for a provider.
         */
        public Builder baseUrl(Provider provider, String url) {
            baseUrls.put(provider, url);
            return this;
        }

        /**
         * Set the default provider for services.
         */
        public Builder defaultProvider(Provider provider) {
            this.defaultProvider = provider;
            return this;
        }

        /**
         * Set request timeout.
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Enable/disable request logging.
         */
        public Builder logRequests(boolean log) {
            this.logRequests = log;
            return this;
        }

        /**
         * Configure a specific service type.
         */
        public Builder configureService(ServiceType type, Provider provider,
                                        String model, String endpoint) {
            serviceConfigs.put(type, new ServiceConfig(provider, model, endpoint, null));
            return this;
        }

        /**
         * Build using environment variables for API keys.
         * Looks for: OPENAI_API_KEY, ANTHROPIC_API_KEY, GEMINI_API_KEY
         * Auto-sets default provider to the first one found.
         */
        public Builder fromEnvironment() {
            String openai = System.getenv("OPENAI_API_KEY");
            String anthropic = System.getenv("ANTHROPIC_API_KEY");
            String gemini = System.getenv("GEMINI_API_KEY");

            // Track if we've set a provider yet
            boolean providerSet = false;

            if (openai != null && !openai.isEmpty()) {
                apiKeys.put(Provider.OPENAI, openai);
                if (!providerSet) {
                    defaultProvider = Provider.OPENAI;
                    providerSet = true;
                }
            }
            if (anthropic != null && !anthropic.isEmpty()) {
                apiKeys.put(Provider.ANTHROPIC, anthropic);
                if (!providerSet) {
                    defaultProvider = Provider.ANTHROPIC;
                    providerSet = true;
                }
            }
            if (gemini != null && !gemini.isEmpty()) {
                apiKeys.put(Provider.GEMINI, gemini);
                if (!providerSet) {
                    defaultProvider = Provider.GEMINI;
                    providerSet = true;
                }
            }

            return this;
        }

        public LLMServiceBackend build() {
            return new LLMServiceBackend(this);
        }
    }

    @Override
    public String toString() {
        return String.format("LLMServiceBackend[providers=%s, services=%d]",
            apiKeys.keySet(), serviceConfigs.size());
    }
}
