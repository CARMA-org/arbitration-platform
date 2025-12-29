package org.carma.arbitration;

import org.carma.arbitration.mechanism.*;
import org.carma.arbitration.model.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Demonstration of AI Service Integration for the Arbitration Platform.
 * 
 * This demo showcases:
 * 1. Service type definitions and resource mapping
 * 2. Service composition (DAG) construction and validation
 * 3. Service arbitration among competing agents
 * 4. Mock backend execution of service pipelines
 * 5. Composition monitoring and metrics
 * 
 * Usage:
 *   java -cp out org.carma.arbitration.ServiceDemo
 *   java -cp out org.carma.arbitration.ServiceDemo --verbose
 */
public class ServiceDemo {

    private static final String SEP = "═".repeat(72);
    private static final String SUBSEP = "─".repeat(60);
    private static boolean verbose = false;

    public static void main(String[] args) {
        verbose = Arrays.asList(args).contains("--verbose") || 
                  Arrays.asList(args).contains("-v");

        System.out.println(SEP);
        System.out.println("   AI SERVICE INTEGRATION DEMO");
        System.out.println("   Platform-Mediated Multi-Agent Service Allocation");
        System.out.println(SEP);
        System.out.println();

        // Run all scenarios
        runScenario1_ServiceTypes();
        runScenario2_ServiceComposition();
        runScenario3_ServiceArbitration();
        runScenario4_MockExecution();
        runScenario5_MultiAgentCompetition();
        runScenario6_CompositionMonitoring();

        System.out.println(SEP);
        System.out.println("   SERVICE INTEGRATION DEMO COMPLETE");
        System.out.println(SEP);
    }

    // ========================================================================
    // SCENARIO 1: Service Types and Resource Mapping
    // ========================================================================

    static void runScenario1_ServiceTypes() {
        System.out.println("SCENARIO 1: SERVICE TYPES AND RESOURCE MAPPING");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Demonstrate available AI service types and their");
        System.out.println("         resource requirements (compute, memory, API credits).");
        System.out.println();

        System.out.println("Available Service Types:");
        System.out.println();

        // Group by category
        Map<String, List<ServiceType>> categories = new LinkedHashMap<>();
        categories.put("Text Processing", List.of(
            ServiceType.TEXT_GENERATION, ServiceType.TEXT_EMBEDDING,
            ServiceType.TEXT_CLASSIFICATION, ServiceType.TEXT_SUMMARIZATION));
        categories.put("Vision", List.of(
            ServiceType.IMAGE_ANALYSIS, ServiceType.IMAGE_GENERATION, ServiceType.OCR));
        categories.put("Audio", List.of(
            ServiceType.SPEECH_TO_TEXT, ServiceType.TEXT_TO_SPEECH));
        categories.put("Reasoning", List.of(
            ServiceType.CODE_GENERATION, ServiceType.CODE_ANALYSIS, ServiceType.REASONING));
        categories.put("Data", List.of(
            ServiceType.DATA_EXTRACTION, ServiceType.VECTOR_SEARCH, ServiceType.KNOWLEDGE_RETRIEVAL));

        for (var entry : categories.entrySet()) {
            System.out.println("  " + entry.getKey() + ":");
            for (ServiceType type : entry.getValue()) {
                Map<ResourceType, Long> resources = type.getDefaultResourceRequirements();
                System.out.printf("    %-22s Compute=%2d, Memory=%2d, API=%2d  [%dms latency]\n",
                    type.name(),
                    resources.getOrDefault(ResourceType.COMPUTE, 0L),
                    resources.getOrDefault(ResourceType.MEMORY, 0L),
                    resources.getOrDefault(ResourceType.API_CREDITS, 0L),
                    type.getBaseLatencyMs());
            }
            System.out.println();
        }

        // Show data type compatibility
        System.out.println("Data Type Compatibility Example:");
        ServiceType source = ServiceType.IMAGE_ANALYSIS;
        ServiceType target = ServiceType.TEXT_GENERATION;
        boolean compatible = target.canAcceptOutputFrom(source);
        Set<ServiceType.DataType> overlap = target.getCompatibleInputsFrom(source);
        System.out.printf("  %s → %s: %s\n", source, target, 
            compatible ? "Compatible via " + overlap : "Not compatible");

        source = ServiceType.TEXT_GENERATION;
        target = ServiceType.TEXT_TO_SPEECH;
        compatible = target.canAcceptOutputFrom(source);
        overlap = target.getCompatibleInputsFrom(source);
        System.out.printf("  %s → %s: %s\n", source, target,
            compatible ? "Compatible via " + overlap : "Not compatible");

        System.out.println();
        System.out.println("  ✓ PASS: Service types defined with resource mappings");
        System.out.println();
    }

    // ========================================================================
    // SCENARIO 2: Service Composition (DAG Construction)
    // ========================================================================

    static void runScenario2_ServiceComposition() {
        System.out.println("SCENARIO 2: SERVICE COMPOSITION (DAG CONSTRUCTION)");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Build and validate service composition pipelines");
        System.out.println("         as directed acyclic graphs (DAGs).");
        System.out.println();

        // Linear chain: Image → Analysis → Text Generation → Speech
        System.out.println("Composition 1: Image-to-Speech Pipeline (Linear Chain)");
        ServiceComposition imageToSpeech = new ServiceComposition.Builder("img-to-speech")
            .name("Image to Speech Pipeline")
            .description("Analyzes image, generates description, converts to speech")
            .addNode("analyze", ServiceType.IMAGE_ANALYSIS)
            .addNode("describe", ServiceType.TEXT_GENERATION)
            .addNode("speak", ServiceType.TEXT_TO_SPEECH)
            .connect("analyze", "describe", ServiceType.DataType.TEXT)
            .connect("describe", "speak", ServiceType.DataType.TEXT)
            .build();

        ServiceComposition.ValidationResult result1 = imageToSpeech.validate();
        System.out.println("  Structure: IMAGE_ANALYSIS → TEXT_GENERATION → TEXT_TO_SPEECH");
        System.out.println("  Validation: " + result1);
        System.out.println("  Entry nodes: " + imageToSpeech.getEntryNodes());
        System.out.println("  Exit nodes: " + imageToSpeech.getExitNodes());
        System.out.println("  Est. latency: " + imageToSpeech.estimateCriticalPathLatencyMs() + "ms");
        System.out.println("  Resources: " + imageToSpeech.calculateTotalResourceRequirements());
        System.out.println();

        // Fan-out: Document Analysis
        System.out.println("Composition 2: Document Analysis Pipeline (Fan-out)");
        ServiceComposition docAnalysis = new ServiceComposition.Builder("doc-analysis")
            .name("Document Analysis")
            .description("Extracts data, classifies, and summarizes document")
            .addNode("ocr", ServiceType.OCR)
            .addNode("extract", ServiceType.DATA_EXTRACTION)
            .addNode("classify", ServiceType.TEXT_CLASSIFICATION)
            .addNode("summarize", ServiceType.TEXT_SUMMARIZATION)
            .connect("ocr", "extract", ServiceType.DataType.TEXT)
            .connect("ocr", "classify", ServiceType.DataType.TEXT)
            .connect("ocr", "summarize", ServiceType.DataType.TEXT)
            .build();

        ServiceComposition.ValidationResult result2 = docAnalysis.validate();
        System.out.println("  Structure: OCR → [DATA_EXTRACTION, TEXT_CLASSIFICATION, TEXT_SUMMARIZATION]");
        System.out.println("  Validation: " + result2);
        System.out.println("  Parallelizable branches: 3");
        System.out.println("  Est. latency: " + docAnalysis.estimateCriticalPathLatencyMs() + "ms");
        System.out.println();

        // Fan-in: RAG Pipeline
        System.out.println("Composition 3: RAG Pipeline (Fan-in)");
        ServiceComposition ragPipeline = new ServiceComposition.Builder("rag-pipeline")
            .name("Retrieval-Augmented Generation")
            .description("Embeds query, retrieves knowledge, generates response")
            .addNode("embed", ServiceType.TEXT_EMBEDDING)
            .addNode("search", ServiceType.VECTOR_SEARCH)
            .addNode("retrieve", ServiceType.KNOWLEDGE_RETRIEVAL)
            .addNode("generate", ServiceType.TEXT_GENERATION)
            .connect("embed", "search", ServiceType.DataType.VECTOR)
            .connect("search", "retrieve", ServiceType.DataType.STRUCTURED)
            .connect("retrieve", "generate", ServiceType.DataType.TEXT)
            .build();

        ServiceComposition.ValidationResult result3 = ragPipeline.validate();
        System.out.println("  Structure: TEXT_EMBEDDING → VECTOR_SEARCH → KNOWLEDGE_RETRIEVAL → TEXT_GENERATION");
        System.out.println("  Validation: " + result3);
        System.out.println("  Est. latency: " + ragPipeline.estimateCriticalPathLatencyMs() + "ms");
        System.out.println();

        // Invalid composition (cycle detection)
        System.out.println("Composition 4: Invalid Pipeline (Cycle Detection)");
        ServiceComposition cyclic = new ServiceComposition.Builder("cyclic-invalid")
            .addNode("a", ServiceType.TEXT_GENERATION)
            .addNode("b", ServiceType.TEXT_SUMMARIZATION)
            .addNode("c", ServiceType.TEXT_CLASSIFICATION)
            .connect("a", "b", ServiceType.DataType.TEXT)
            .connect("b", "c", ServiceType.DataType.TEXT)
            .connect("c", "a", ServiceType.DataType.TEXT)  // Creates cycle
            .build();

        ServiceComposition.ValidationResult result4 = cyclic.validate();
        System.out.println("  Structure: A → B → C → A (cycle!)");
        System.out.println("  Validation: " + result4);
        System.out.println();

        boolean allValid = result1.isValid() && result2.isValid() && result3.isValid() && !result4.isValid();
        System.out.println("  " + (allValid ? "✓ PASS" : "✗ FAIL") + 
            ": Composition validation working correctly");
        System.out.println();
    }

    // ========================================================================
    // SCENARIO 3: Service Arbitration
    // ========================================================================

    static void runScenario3_ServiceArbitration() {
        System.out.println("SCENARIO 3: SERVICE ARBITRATION");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Allocate service slots among competing agents using");
        System.out.println("         weighted proportional fairness.");
        System.out.println();

        // Setup registry with services
        ServiceRegistry registry = new ServiceRegistry();
        
        // Add multiple instances of key service types
        registry.register(new AIService.Builder("text-gen-1", ServiceType.TEXT_GENERATION)
            .provider("anthropic").maxCapacity(10).build());
        registry.register(new AIService.Builder("text-gen-2", ServiceType.TEXT_GENERATION)
            .provider("openai").maxCapacity(8).build());
        registry.register(new AIService.Builder("img-analysis-1", ServiceType.IMAGE_ANALYSIS)
            .provider("anthropic").maxCapacity(5).build());
        registry.register(new AIService.Builder("embedding-1", ServiceType.TEXT_EMBEDDING)
            .provider("openai").maxCapacity(20).build());
        registry.register(new AIService.Builder("vector-search-1", ServiceType.VECTOR_SEARCH)
            .provider("pinecone").maxCapacity(15).build());

        System.out.println("Registry initialized:");
        System.out.println("  " + registry.getStats());
        System.out.println();

        // Create service requests from agents
        PriorityEconomy economy = new PriorityEconomy();
        ServiceArbitrator arbitrator = new ServiceArbitrator(economy, registry);

        List<ServiceArbitrator.ServiceRequest> requests = List.of(
            new ServiceArbitrator.ServiceRequest.Builder("agent-ml-team")
                .requestService(ServiceType.TEXT_GENERATION, 5)
                .requestService(ServiceType.TEXT_EMBEDDING, 8)
                .currencyCommitment(BigDecimal.valueOf(50))
                .preference(ServiceType.TEXT_GENERATION, 0.7)
                .preference(ServiceType.TEXT_EMBEDDING, 0.3)
                .build(),
            new ServiceArbitrator.ServiceRequest.Builder("agent-search-team")
                .requestService(ServiceType.TEXT_EMBEDDING, 10)
                .requestService(ServiceType.VECTOR_SEARCH, 8)
                .currencyCommitment(BigDecimal.valueOf(30))
                .preference(ServiceType.TEXT_EMBEDDING, 0.4)
                .preference(ServiceType.VECTOR_SEARCH, 0.6)
                .build(),
            new ServiceArbitrator.ServiceRequest.Builder("agent-content-team")
                .requestService(ServiceType.TEXT_GENERATION, 8)
                .requestService(ServiceType.IMAGE_ANALYSIS, 3)
                .currencyCommitment(BigDecimal.valueOf(20))
                .preference(ServiceType.TEXT_GENERATION, 0.8)
                .preference(ServiceType.IMAGE_ANALYSIS, 0.2)
                .build()
        );

        System.out.println("Service Requests:");
        System.out.println("  agent-ml-team:      TEXT_GEN=5, EMBEDDING=8   (burns 50, prefers TEXT_GEN)");
        System.out.println("  agent-search-team:  EMBEDDING=10, VECTOR=8    (burns 30, prefers VECTOR)");
        System.out.println("  agent-content-team: TEXT_GEN=8, IMG_ANALYSIS=3 (burns 20, prefers TEXT_GEN)");
        System.out.println();

        System.out.println("Capacity Available:");
        System.out.println("  TEXT_GENERATION: " + registry.getAvailableCapacity(ServiceType.TEXT_GENERATION));
        System.out.println("  TEXT_EMBEDDING: " + registry.getAvailableCapacity(ServiceType.TEXT_EMBEDDING));
        System.out.println("  VECTOR_SEARCH: " + registry.getAvailableCapacity(ServiceType.VECTOR_SEARCH));
        System.out.println("  IMAGE_ANALYSIS: " + registry.getAvailableCapacity(ServiceType.IMAGE_ANALYSIS));
        System.out.println();

        // Arbitrate
        ServiceArbitrator.ServiceAllocationResult result = arbitrator.arbitrate(requests);

        System.out.println("Arbitration Result:");
        System.out.println("  Feasible: " + result.isFeasible());
        System.out.println("  Objective: " + String.format("%.4f", result.getObjectiveValue()));
        System.out.println("  Time: " + result.getComputationTimeMs() + "ms");
        System.out.println();

        System.out.println("Allocations:");
        for (String agentId : List.of("agent-ml-team", "agent-search-team", "agent-content-team")) {
            Map<ServiceType, Integer> allocs = result.getAllocations(agentId);
            System.out.println("  " + agentId + ": " + allocs);
        }
        System.out.println();

        System.out.println("  " + (result.isFeasible() ? "✓ PASS" : "✗ FAIL") + 
            ": Service arbitration complete");
        System.out.println();
    }

    // ========================================================================
    // SCENARIO 4: Mock Service Execution
    // ========================================================================

    static void runScenario4_MockExecution() {
        System.out.println("SCENARIO 4: MOCK SERVICE EXECUTION");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Execute service calls using mock backends for testing.");
        System.out.println();

        // Setup
        ServiceRegistry registry = ServiceRegistry.forTesting(2);
        MockServiceBackend backend = new MockServiceBackend(registry, 
            MockServiceBackend.MockConfig.fast());

        System.out.println("Mock Backend Configuration:");
        System.out.println("  Latency simulation: disabled (fast mode)");
        System.out.println("  Failure rate: 0%");
        System.out.println();

        // Execute various service types
        System.out.println("Service Invocations:");
        System.out.println();

        // Text Generation
        MockServiceBackend.InvocationResult genResult = backend.invokeByType(
            ServiceType.TEXT_GENERATION,
            Map.of("prompt", "Explain quantum computing", "max_tokens", 50));
        System.out.println("  TEXT_GENERATION:");
        System.out.println("    Input: prompt='Explain quantum computing', max_tokens=50");
        System.out.println("    Success: " + genResult.isSuccess());
        if (genResult.isSuccess()) {
            String text = (String) genResult.getOutput().get("text");
            System.out.println("    Output: \"" + truncate(text, 60) + "...\"");
        }
        System.out.println();

        // Text Embedding
        MockServiceBackend.InvocationResult embedResult = backend.invokeByType(
            ServiceType.TEXT_EMBEDDING,
            Map.of("text", "Hello world", "dimensions", 768));
        System.out.println("  TEXT_EMBEDDING:");
        System.out.println("    Input: text='Hello world', dimensions=768");
        System.out.println("    Success: " + embedResult.isSuccess());
        if (embedResult.isSuccess()) {
            double[] embedding = (double[]) embedResult.getOutput().get("embedding");
            System.out.println("    Output: [" + String.format("%.4f, %.4f, %.4f", 
                embedding[0], embedding[1], embedding[2]) + ", ... ] (768 dims)");
        }
        System.out.println();

        // Image Analysis
        MockServiceBackend.InvocationResult imgResult = backend.invokeByType(
            ServiceType.IMAGE_ANALYSIS,
            Map.of("image", "base64_data_here"));
        System.out.println("  IMAGE_ANALYSIS:");
        System.out.println("    Input: image=<base64_data>");
        System.out.println("    Success: " + imgResult.isSuccess());
        if (imgResult.isSuccess()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> objects = (List<Map<String, Object>>) imgResult.getOutput().get("objects");
            System.out.println("    Output: Detected " + objects.size() + " objects");
            for (Map<String, Object> obj : objects) {
                System.out.println("      - " + obj.get("label") + " (confidence: " + obj.get("confidence") + ")");
            }
        }
        System.out.println();

        // Code Generation
        MockServiceBackend.InvocationResult codeResult = backend.invokeByType(
            ServiceType.CODE_GENERATION,
            Map.of("prompt", "fibonacci function", "language", "python"));
        System.out.println("  CODE_GENERATION:");
        System.out.println("    Input: prompt='fibonacci function', language='python'");
        System.out.println("    Success: " + codeResult.isSuccess());
        if (codeResult.isSuccess()) {
            String code = (String) codeResult.getOutput().get("code");
            String[] lines = code.split("\n");
            System.out.println("    Output: " + lines.length + " lines of code generated");
            if (verbose) {
                System.out.println("    ---");
                for (String line : lines) {
                    System.out.println("    " + line);
                }
                System.out.println("    ---");
            }
        }
        System.out.println();

        // Summary
        System.out.println("Execution Summary:");
        System.out.println("  Total invocations: " + backend.getInvocationCount());
        System.out.println("  Success rate: " + String.format("%.1f%%", backend.getSuccessRate() * 100));
        System.out.println("  Avg latency: " + String.format("%.2f", backend.getAverageLatency()) + "ms");
        System.out.println();

        boolean allSucceeded = genResult.isSuccess() && embedResult.isSuccess() && 
                              imgResult.isSuccess() && codeResult.isSuccess();
        System.out.println("  " + (allSucceeded ? "✓ PASS" : "✗ FAIL") + 
            ": Mock execution working correctly");
        System.out.println();
    }

    // ========================================================================
    // SCENARIO 5: Multi-Agent Competition for Services
    // ========================================================================

    static void runScenario5_MultiAgentCompetition() {
        System.out.println("SCENARIO 5: MULTI-AGENT COMPETITION FOR SERVICES");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Demonstrate fair allocation when service capacity is");
        System.out.println("         scarce and multiple agents compete.");
        System.out.println();

        // Setup with limited capacity
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(new AIService.Builder("limited-gen", ServiceType.TEXT_GENERATION)
            .provider("limited").maxCapacity(10).build());

        PriorityEconomy economy = new PriorityEconomy();
        ServiceArbitrator arbitrator = new ServiceArbitrator(economy, registry);

        System.out.println("Scarce Resource Setup:");
        System.out.println("  TEXT_GENERATION capacity: 10 slots");
        System.out.println("  Total demand: 25 slots (2.5x oversubscribed)");
        System.out.println();

        // High-priority agent (burns more currency)
        // Medium-priority agents
        // Low-priority agent
        List<ServiceArbitrator.ServiceRequest> requests = List.of(
            new ServiceArbitrator.ServiceRequest.Builder("high-priority")
                .requestService(ServiceType.TEXT_GENERATION, 8)
                .currencyCommitment(BigDecimal.valueOf(100))
                .build(),
            new ServiceArbitrator.ServiceRequest.Builder("medium-priority-1")
                .requestService(ServiceType.TEXT_GENERATION, 6)
                .currencyCommitment(BigDecimal.valueOf(30))
                .build(),
            new ServiceArbitrator.ServiceRequest.Builder("medium-priority-2")
                .requestService(ServiceType.TEXT_GENERATION, 6)
                .currencyCommitment(BigDecimal.valueOf(30))
                .build(),
            new ServiceArbitrator.ServiceRequest.Builder("low-priority")
                .requestService(ServiceType.TEXT_GENERATION, 5)
                .currencyCommitment(BigDecimal.ZERO)
                .build()
        );

        System.out.println("Competing Agents:");
        System.out.println("  high-priority:     wants 8, burns 100 currency (weight=110)");
        System.out.println("  medium-priority-1: wants 6, burns 30 currency  (weight=40)");
        System.out.println("  medium-priority-2: wants 6, burns 30 currency  (weight=40)");
        System.out.println("  low-priority:      wants 5, burns 0 currency   (weight=10)");
        System.out.println();

        ServiceArbitrator.ServiceAllocationResult result = arbitrator.arbitrate(requests);

        System.out.println("Allocation Results:");
        int total = 0;
        for (String agentId : List.of("high-priority", "medium-priority-1", "medium-priority-2", "low-priority")) {
            int slots = result.getAllocation(agentId, ServiceType.TEXT_GENERATION);
            total += slots;
            System.out.printf("  %-18s: %d slots\n", agentId, slots);
        }
        System.out.println("  " + "-".repeat(30));
        System.out.printf("  %-18s: %d slots\n", "TOTAL", total);
        System.out.println();

        System.out.println("Analysis:");
        int highAlloc = result.getAllocation("high-priority", ServiceType.TEXT_GENERATION);
        int lowAlloc = result.getAllocation("low-priority", ServiceType.TEXT_GENERATION);
        System.out.println("  High-priority got " + highAlloc + " (burned most, got most)");
        System.out.println("  Low-priority got " + lowAlloc + " (protected by log barrier, got minimum)");
        System.out.println();

        boolean fairAllocation = highAlloc > lowAlloc && lowAlloc > 0 && total <= 10;
        System.out.println("  " + (fairAllocation ? "✓ PASS" : "✗ FAIL") + 
            ": Fair allocation under scarcity");
        System.out.println();
    }

    // ========================================================================
    // SCENARIO 6: Composition Monitoring
    // ========================================================================

    static void runScenario6_CompositionMonitoring() {
        System.out.println("SCENARIO 6: COMPOSITION MONITORING");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Track composition execution, node status, and metrics.");
        System.out.println();

        // Setup
        ServiceRegistry registry = ServiceRegistry.forTesting(2);
        CompositionMonitor monitor = new CompositionMonitor(registry);

        // Create a composition
        ServiceComposition pipeline = new ServiceComposition.Builder("monitored-pipeline")
            .name("Monitored Pipeline")
            .addNode("step1", ServiceType.TEXT_EMBEDDING)
            .addNode("step2", ServiceType.VECTOR_SEARCH)
            .addNode("step3", ServiceType.TEXT_GENERATION)
            .connect("step1", "step2", ServiceType.DataType.VECTOR)
            .connect("step2", "step3", ServiceType.DataType.TEXT)
            .build();

        pipeline.validate();
        registry.registerComposition(pipeline);

        System.out.println("Pipeline: TEXT_EMBEDDING → VECTOR_SEARCH → TEXT_GENERATION");
        System.out.println();

        // Start multiple instances
        System.out.println("Starting composition instances...");
        CompositionMonitor.CompositionInstance inst1 = monitor.startComposition("agent-1", pipeline);
        CompositionMonitor.CompositionInstance inst2 = monitor.startComposition("agent-2", pipeline);
        CompositionMonitor.CompositionInstance inst3 = monitor.startComposition("agent-3", pipeline);

        System.out.println("  Started: " + inst1.getInstanceId() + " for agent-1");
        System.out.println("  Started: " + inst2.getInstanceId() + " for agent-2");
        System.out.println("  Started: " + inst3.getInstanceId() + " for agent-3");
        System.out.println();

        // Simulate execution of instance 1 (complete)
        System.out.println("Simulating execution of " + inst1.getInstanceId() + ":");
        
        System.out.println("  Starting step1 (TEXT_EMBEDDING)...");
        monitor.startNode(inst1.getInstanceId(), "step1", "embedding-service-1");
        monitor.completeNode(inst1.getInstanceId(), "step1", 
            Map.of("embedding", new double[768]));
        System.out.println("    ✓ step1 complete");

        System.out.println("  Starting step2 (VECTOR_SEARCH)...");
        monitor.startNode(inst1.getInstanceId(), "step2", "vector-service-1");
        monitor.completeNode(inst1.getInstanceId(), "step2",
            Map.of("results", List.of("doc1", "doc2")));
        System.out.println("    ✓ step2 complete");

        System.out.println("  Starting step3 (TEXT_GENERATION)...");
        monitor.startNode(inst1.getInstanceId(), "step3", "gen-service-1");
        monitor.completeNode(inst1.getInstanceId(), "step3",
            Map.of("text", "Generated response"));
        System.out.println("    ✓ step3 complete");
        System.out.println();

        // Simulate failure of instance 2
        System.out.println("Simulating failure of " + inst2.getInstanceId() + ":");
        monitor.startNode(inst2.getInstanceId(), "step1", "embedding-service-1");
        monitor.completeNode(inst2.getInstanceId(), "step1", Map.of("embedding", new double[768]));
        monitor.startNode(inst2.getInstanceId(), "step2", "vector-service-1");
        monitor.failNode(inst2.getInstanceId(), "step2", "Connection timeout");
        monitor.failNode(inst2.getInstanceId(), "step2", "Connection timeout");
        monitor.failNode(inst2.getInstanceId(), "step2", "Connection timeout");
        monitor.failNode(inst2.getInstanceId(), "step2", "Connection timeout"); // 4th failure triggers composition failure
        System.out.println("  ✗ step2 failed after 3 retries");
        System.out.println();

        // Instance 3 still running
        System.out.println("Instance " + inst3.getInstanceId() + " still in progress...");
        System.out.println("  Progress: " + String.format("%.0f%%", inst3.getProgress() * 100));
        System.out.println();

        // Print metrics
        CompositionMonitor.CompositionMetrics metrics = monitor.getMetrics();
        System.out.println("Monitoring Metrics:");
        System.out.println("  Total started: " + metrics.getTotalStarted());
        System.out.println("  Total completed: " + metrics.getTotalCompleted());
        System.out.println("  Total failed: " + metrics.getTotalFailed());
        System.out.println("  Success rate: " + String.format("%.1f%%", metrics.getSuccessRate() * 100));
        System.out.println("  Active instances: " + monitor.getActiveCount());
        System.out.println();

        // Print instance states
        System.out.println("Instance States:");
        System.out.printf("  %s: %s (%.0fms)\n", 
            inst1.getInstanceId(), inst1.getStatus(), (double) inst1.getDurationMs());
        System.out.printf("  %s: %s - %s\n",
            inst2.getInstanceId(), inst2.getStatus(), inst2.getFailureReason());
        System.out.printf("  %s: %s (in progress)\n",
            inst3.getInstanceId(), inst3.getStatus());
        System.out.println();

        boolean metricsCorrect = metrics.getTotalCompleted() == 1 && 
                                metrics.getTotalFailed() == 1 &&
                                monitor.getActiveCount() == 1;
        System.out.println("  " + (metricsCorrect ? "✓ PASS" : "✗ FAIL") + 
            ": Composition monitoring working correctly");
        System.out.println();
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen);
    }
}
