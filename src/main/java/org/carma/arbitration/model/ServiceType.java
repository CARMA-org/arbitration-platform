package org.carma.arbitration.model;

import java.util.Map;
import java.util.Set;

/**
 * Types of narrow AI services available for composition.
 * 
 * Each service type defines:
 * - Display name and description
 * - Default resource requirements (compute, memory, etc.)
 * - Input/output data types for composition validation
 * - Typical latency characteristics
 */
public enum ServiceType {
    
    // Text Services
    TEXT_GENERATION(
        "Text Generation",
        "Generate text from prompts using language models",
        Map.of(ResourceType.COMPUTE, 10L, ResourceType.MEMORY, 8L, ResourceType.API_CREDITS, 5L),
        Set.of(DataType.TEXT, DataType.STRUCTURED),
        Set.of(DataType.TEXT),
        100, // base latency ms
        20   // default capacity (concurrent requests)
    ),
    
    TEXT_EMBEDDING(
        "Text Embedding",
        "Convert text to vector embeddings for similarity search",
        Map.of(ResourceType.COMPUTE, 5L, ResourceType.MEMORY, 4L, ResourceType.API_CREDITS, 2L),
        Set.of(DataType.TEXT),
        Set.of(DataType.VECTOR),
        50,
        50
    ),
    
    TEXT_CLASSIFICATION(
        "Text Classification",
        "Classify text into predefined categories",
        Map.of(ResourceType.COMPUTE, 4L, ResourceType.MEMORY, 3L, ResourceType.API_CREDITS, 1L),
        Set.of(DataType.TEXT),
        Set.of(DataType.STRUCTURED),
        30,
        100
    ),
    
    TEXT_SUMMARIZATION(
        "Text Summarization",
        "Generate concise summaries of longer text",
        Map.of(ResourceType.COMPUTE, 8L, ResourceType.MEMORY, 6L, ResourceType.API_CREDITS, 4L),
        Set.of(DataType.TEXT),
        Set.of(DataType.TEXT),
        150,
        30
    ),
    
    // Vision Services
    IMAGE_ANALYSIS(
        "Image Analysis",
        "Analyze images for objects, scenes, and attributes",
        Map.of(ResourceType.COMPUTE, 15L, ResourceType.MEMORY, 12L, ResourceType.API_CREDITS, 8L),
        Set.of(DataType.IMAGE),
        Set.of(DataType.STRUCTURED, DataType.TEXT),
        200,
        15
    ),
    
    IMAGE_GENERATION(
        "Image Generation",
        "Generate images from text descriptions",
        Map.of(ResourceType.COMPUTE, 25L, ResourceType.MEMORY, 20L, ResourceType.API_CREDITS, 15L),
        Set.of(DataType.TEXT),
        Set.of(DataType.IMAGE),
        500,
        5
    ),
    
    OCR(
        "Optical Character Recognition",
        "Extract text from images and documents",
        Map.of(ResourceType.COMPUTE, 8L, ResourceType.MEMORY, 6L, ResourceType.API_CREDITS, 3L),
        Set.of(DataType.IMAGE),
        Set.of(DataType.TEXT),
        100,
        40
    ),
    
    // Audio Services
    SPEECH_TO_TEXT(
        "Speech to Text",
        "Transcribe audio to text",
        Map.of(ResourceType.COMPUTE, 12L, ResourceType.MEMORY, 8L, ResourceType.API_CREDITS, 6L),
        Set.of(DataType.AUDIO),
        Set.of(DataType.TEXT),
        300,
        20
    ),
    
    TEXT_TO_SPEECH(
        "Text to Speech",
        "Synthesize speech from text",
        Map.of(ResourceType.COMPUTE, 10L, ResourceType.MEMORY, 6L, ResourceType.API_CREDITS, 5L),
        Set.of(DataType.TEXT),
        Set.of(DataType.AUDIO),
        200,
        25
    ),
    
    // Reasoning Services
    CODE_GENERATION(
        "Code Generation",
        "Generate code from natural language descriptions",
        Map.of(ResourceType.COMPUTE, 12L, ResourceType.MEMORY, 10L, ResourceType.API_CREDITS, 8L),
        Set.of(DataType.TEXT, DataType.STRUCTURED),
        Set.of(DataType.CODE),
        150,
        15
    ),
    
    CODE_ANALYSIS(
        "Code Analysis",
        "Analyze code for bugs, security issues, and improvements",
        Map.of(ResourceType.COMPUTE, 10L, ResourceType.MEMORY, 8L, ResourceType.API_CREDITS, 6L),
        Set.of(DataType.CODE),
        Set.of(DataType.STRUCTURED, DataType.TEXT),
        120,
        20
    ),
    
    REASONING(
        "Reasoning Engine",
        "Multi-step reasoning and chain-of-thought processing",
        Map.of(ResourceType.COMPUTE, 20L, ResourceType.MEMORY, 15L, ResourceType.API_CREDITS, 12L),
        Set.of(DataType.TEXT, DataType.STRUCTURED),
        Set.of(DataType.TEXT, DataType.STRUCTURED),
        400,
        10
    ),
    
    // Data Services
    DATA_EXTRACTION(
        "Data Extraction",
        "Extract structured data from unstructured sources",
        Map.of(ResourceType.COMPUTE, 6L, ResourceType.MEMORY, 4L, ResourceType.API_CREDITS, 3L),
        Set.of(DataType.TEXT, DataType.IMAGE),
        Set.of(DataType.STRUCTURED),
        80,
        50
    ),
    
    VECTOR_SEARCH(
        "Vector Search",
        "Semantic similarity search over vector embeddings",
        Map.of(ResourceType.COMPUTE, 3L, ResourceType.MEMORY, 10L, ResourceType.DATASET, 5L),
        Set.of(DataType.VECTOR),
        Set.of(DataType.STRUCTURED),
        20,
        200
    ),
    
    KNOWLEDGE_RETRIEVAL(
        "Knowledge Retrieval",
        "Retrieve relevant knowledge from structured sources",
        Map.of(ResourceType.COMPUTE, 4L, ResourceType.MEMORY, 6L, ResourceType.DATASET, 8L),
        Set.of(DataType.TEXT, DataType.STRUCTURED),
        Set.of(DataType.TEXT, DataType.STRUCTURED),
        50,
        80
    );

    /**
     * Data types for service input/output compatibility checking.
     */
    public enum DataType {
        TEXT,       // Natural language text
        STRUCTURED, // JSON, XML, or other structured data
        IMAGE,      // Image data (PNG, JPEG, etc.)
        AUDIO,      // Audio data (WAV, MP3, etc.)
        VIDEO,      // Video data
        VECTOR,     // Embedding vectors
        CODE        // Source code
    }

    private final String displayName;
    private final String description;
    private final Map<ResourceType, Long> defaultResourceRequirements;
    private final Set<DataType> inputTypes;
    private final Set<DataType> outputTypes;
    private final int baseLatencyMs;
    private final int defaultCapacity;

    ServiceType(String displayName, String description,
                Map<ResourceType, Long> defaultResourceRequirements,
                Set<DataType> inputTypes, Set<DataType> outputTypes,
                int baseLatencyMs, int defaultCapacity) {
        this.displayName = displayName;
        this.description = description;
        this.defaultResourceRequirements = defaultResourceRequirements;
        this.inputTypes = inputTypes;
        this.outputTypes = outputTypes;
        this.baseLatencyMs = baseLatencyMs;
        this.defaultCapacity = defaultCapacity;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public Map<ResourceType, Long> getDefaultResourceRequirements() {
        return defaultResourceRequirements;
    }

    public Set<DataType> getInputTypes() {
        return inputTypes;
    }

    public Set<DataType> getOutputTypes() {
        return outputTypes;
    }

    public int getBaseLatencyMs() {
        return baseLatencyMs;
    }

    public int getDefaultCapacity() {
        return defaultCapacity;
    }

    /**
     * Check if this service can accept input from another service.
     */
    public boolean canAcceptOutputFrom(ServiceType other) {
        for (DataType outputType : other.outputTypes) {
            if (this.inputTypes.contains(outputType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the compatible output types that can flow to this service.
     */
    public Set<DataType> getCompatibleInputsFrom(ServiceType other) {
        Set<DataType> compatible = new java.util.HashSet<>();
        for (DataType outputType : other.outputTypes) {
            if (this.inputTypes.contains(outputType)) {
                compatible.add(outputType);
            }
        }
        return compatible;
    }

    /**
     * Calculate total resource cost for a given number of requests.
     */
    public Map<ResourceType, Long> calculateResourceCost(int numRequests) {
        Map<ResourceType, Long> cost = new java.util.HashMap<>();
        for (var entry : defaultResourceRequirements.entrySet()) {
            cost.put(entry.getKey(), entry.getValue() * numRequests);
        }
        return cost;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
