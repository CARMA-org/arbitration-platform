package org.carma.arbitration.agent;

import org.carma.arbitration.model.*;
import org.carma.arbitration.agent.RealisticAgentFramework.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Example realistic agents demonstrating various autonomy levels and use cases.
 * 
 * These agents are designed to be actually implementable and runnable, not just
 * demonstration scenarios. Each agent:
 * - Has explicit goal structures
 * - Uses specific services through arbitration
 * - Publishes outputs to channels
 * - Operates within defined autonomy bounds
 * 
 * This addresses Richard's requirement: "What we need is to take some agents and
 * actually allow people to implement this with real agents."
 */
public class ExampleAgents {

    // ========================================================================
    // NEWS SEARCH AGENT (Low Autonomy)
    // ========================================================================
    
    /**
     * A narrow, tailored agent that searches for relevant news and publishes
     * to a signal channel. This is Richard's example of a "narrow tailored
     * agent with low autonomy."
     * 
     * Characteristics:
     * - Low autonomy: Periodic execution, single-step actions
     * - Narrow scope: Only searches news and publishes summaries
     * - Clear goal: Find and report relevant news on specified topics
     * - Bounded resources: Uses only text generation and knowledge retrieval
     * 
     * Example usage:
     * <pre>
     * OutputChannel signalChannel = new MemoryChannel("signal");
     * 
     * NewsSearchAgent newsAgent = new NewsSearchAgent.Builder("news-ai")
     *     .name("AI News Monitor")
     *     .topics(List.of("AI safety", "LLM capabilities", "AI governance"))
     *     .searchPeriod(Duration.ofHours(1))
     *     .maxResultsPerSearch(5)
     *     .outputChannel(signalChannel)
     *     .build();
     * 
     * runtime.register(newsAgent);
     * </pre>
     */
    public static class NewsSearchAgent extends RealisticAgent {
        
        private final List<String> topics;
        private final int maxResultsPerSearch;
        private final String summaryFormat;
        
        protected NewsSearchAgent(Builder builder) {
            super(builder);
            this.topics = new ArrayList<>(builder.topics);
            this.maxResultsPerSearch = builder.maxResultsPerSearch;
            this.summaryFormat = builder.summaryFormat;
        }
        
        @Override
        protected GoalResult executeGoal(Goal goal, ExecutionContext context) {
            long startTime = System.currentTimeMillis();
            List<String> servicesUsed = new ArrayList<>();
            Map<String, Object> outputs = new HashMap<>();
            
            context.log("Starting news search for topics: " + topics);
            
            // Step 1: Search for news on each topic using knowledge retrieval
            List<Map<String, Object>> allResults = new ArrayList<>();
            
            for (String topic : topics) {
                if (!context.hasService(ServiceType.KNOWLEDGE_RETRIEVAL)) {
                    return GoalResult.failure("Knowledge retrieval service not available");
                }
                
                Map<String, Object> searchInput = Map.of(
                    "query", topic + " recent news",
                    "max_results", maxResultsPerSearch
                );
                
                ServiceResult searchResult = context.invokeService(
                    ServiceType.KNOWLEDGE_RETRIEVAL, searchInput);
                servicesUsed.add(ServiceType.KNOWLEDGE_RETRIEVAL.name());
                
                if (searchResult.isSuccess()) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> results = 
                        searchResult.getOutputValue("results");
                    if (results != null) {
                        for (Map<String, Object> result : results) {
                            result.put("topic", topic);
                            allResults.add(result);
                        }
                    }
                }
            }
            
            context.log("Found " + allResults.size() + " total results");
            
            if (allResults.isEmpty()) {
                publish("news_update", Map.of(
                    "status", "no_results",
                    "message", "No news found for topics: " + topics,
                    "timestamp", Instant.now().toString()
                ));
                return GoalResult.success("No news found", outputs, 
                    System.currentTimeMillis() - startTime, servicesUsed);
            }
            
            // Step 2: Generate summaries using text generation
            if (context.hasService(ServiceType.TEXT_GENERATION)) {
                StringBuilder summaryPrompt = new StringBuilder();
                summaryPrompt.append("Summarize these news items briefly:\n\n");
                
                for (Map<String, Object> result : allResults) {
                    summaryPrompt.append("Topic: ").append(result.get("topic")).append("\n");
                    summaryPrompt.append("Title: ").append(result.get("title")).append("\n");
                    summaryPrompt.append("Content: ").append(result.get("content")).append("\n\n");
                }
                
                Map<String, Object> genInput = Map.of(
                    "prompt", summaryPrompt.toString(),
                    "format", summaryFormat
                );
                
                ServiceResult genResult = context.invokeService(
                    ServiceType.TEXT_GENERATION, genInput);
                servicesUsed.add(ServiceType.TEXT_GENERATION.name());
                
                if (genResult.isSuccess()) {
                    String summary = genResult.getOutputValue("text");
                    outputs.put("summary", summary);
                    
                    // Publish to output channels
                    Map<String, Object> newsUpdate = new HashMap<>();
                    newsUpdate.put("type", "news_summary");
                    newsUpdate.put("topics", topics);
                    newsUpdate.put("result_count", allResults.size());
                    newsUpdate.put("summary", summary);
                    newsUpdate.put("timestamp", Instant.now().toString());
                    
                    publish("news_update", newsUpdate);
                }
            }
            
            outputs.put("results", allResults);
            outputs.put("result_count", allResults.size());
            
            return GoalResult.success(
                "Found " + allResults.size() + " news items",
                outputs,
                System.currentTimeMillis() - startTime,
                servicesUsed
            );
        }
        
        @Override
        public Set<ServiceType> getRequiredServiceTypes() {
            return Set.of(ServiceType.KNOWLEDGE_RETRIEVAL, ServiceType.TEXT_GENERATION);
        }
        
        @Override
        public Set<String> getOperatingDomains() {
            return Set.of("news", "information_retrieval");
        }
        
        // Builder
        public static class Builder extends RealisticAgent.Builder<Builder> {
            private List<String> topics = new ArrayList<>();
            private int maxResultsPerSearch = 5;
            private String summaryFormat = "bullet_points";
            private Duration searchPeriod = Duration.ofHours(1);
            
            public Builder(String agentId) {
                super(agentId);
                this.autonomyLevel = AutonomyLevel.LOW;
            }
            
            public Builder topics(List<String> topics) {
                this.topics = new ArrayList<>(topics);
                return this;
            }
            
            public Builder addTopic(String topic) {
                this.topics.add(topic);
                return this;
            }
            
            public Builder maxResultsPerSearch(int max) {
                this.maxResultsPerSearch = max;
                return this;
            }
            
            public Builder summaryFormat(String format) {
                this.summaryFormat = format;
                return this;
            }
            
            public Builder searchPeriod(Duration period) {
                this.searchPeriod = period;
                return this;
            }
            
            @Override
            public NewsSearchAgent build() {
                // Add the periodic search goal
                Goal searchGoal = new Goal(
                    "periodic-news-search",
                    "Search for news on configured topics",
                    Goal.GoalType.PERIODIC,
                    searchPeriod
                );
                this.goals.add(searchGoal);
                
                // Set resource preferences for this agent type
                this.resourcePreferences.put(ResourceType.API_CREDITS, 0.6);
                this.resourcePreferences.put(ResourceType.COMPUTE, 0.3);
                this.resourcePreferences.put(ResourceType.MEMORY, 0.1);
                
                return new NewsSearchAgent(this);
            }
        }
    }
    
    // ========================================================================
    // DOCUMENT SUMMARIZER AGENT (Tool Level)
    // ========================================================================
    
    /**
     * A tool-level agent that summarizes documents on demand.
     * Has no autonomous operation - only executes when explicitly invoked.
     * 
     * This demonstrates the lowest autonomy level: pure tool behavior.
     */
    public static class DocumentSummarizerAgent extends RealisticAgent {
        
        private final int maxDocumentLength;
        private final String outputFormat;
        
        protected DocumentSummarizerAgent(Builder builder) {
            super(builder);
            this.maxDocumentLength = builder.maxDocumentLength;
            this.outputFormat = builder.outputFormat;
        }
        
        @Override
        protected GoalResult executeGoal(Goal goal, ExecutionContext context) {
            long startTime = System.currentTimeMillis();
            List<String> servicesUsed = new ArrayList<>();
            
            // Get document from goal parameters
            String document = (String) goal.getParameter("document");
            String title = (String) goal.getParameter("title");
            
            if (document == null || document.isEmpty()) {
                return GoalResult.failure("No document provided");
            }
            
            context.log("Summarizing document: " + (title != null ? title : "untitled"));
            
            // Truncate if needed
            String textToProcess = document;
            if (document.length() > maxDocumentLength) {
                textToProcess = document.substring(0, maxDocumentLength) + "...";
                context.log("Document truncated to " + maxDocumentLength + " characters");
            }
            
            // Step 1: Use text summarization service
            if (!context.hasService(ServiceType.TEXT_SUMMARIZATION)) {
                return GoalResult.failure("Text summarization service not available");
            }
            
            Map<String, Object> summaryInput = Map.of(
                "text", textToProcess,
                "format", outputFormat,
                "max_length", 500
            );
            
            ServiceResult summaryResult = context.invokeService(
                ServiceType.TEXT_SUMMARIZATION, summaryInput);
            servicesUsed.add(ServiceType.TEXT_SUMMARIZATION.name());
            
            if (!summaryResult.isSuccess()) {
                return GoalResult.failure("Summarization failed: " + summaryResult.getError());
            }
            
            String summary = summaryResult.getOutputValue("summary");
            
            // Publish result
            Map<String, Object> output = new HashMap<>();
            output.put("title", title);
            output.put("summary", summary);
            output.put("original_length", document.length());
            output.put("processed_length", textToProcess.length());
            output.put("timestamp", Instant.now().toString());
            
            publish("document_summary", output);
            
            return GoalResult.success(
                "Document summarized successfully",
                output,
                System.currentTimeMillis() - startTime,
                servicesUsed
            );
        }
        
        @Override
        public Set<ServiceType> getRequiredServiceTypes() {
            return Set.of(ServiceType.TEXT_SUMMARIZATION);
        }
        
        @Override
        public Set<String> getOperatingDomains() {
            return Set.of("document_processing");
        }
        
        /**
         * Convenience method to summarize a document.
         */
        public void summarize(AgentRuntime runtime, String document, String title) {
            Goal goal = new Goal(
                "summarize-" + System.currentTimeMillis(),
                "Summarize document: " + title,
                Goal.GoalType.ONE_TIME
            );
            goal.setParameter("document", document);
            goal.setParameter("title", title);
            
            addGoal(goal);
            runtime.invokeAgent(getAgentId(), goal.getGoalId());
        }
        
        // Builder
        public static class Builder extends RealisticAgent.Builder<Builder> {
            private int maxDocumentLength = 50000;
            private String outputFormat = "paragraph";
            
            public Builder(String agentId) {
                super(agentId);
                this.autonomyLevel = AutonomyLevel.TOOL;
            }
            
            public Builder maxDocumentLength(int max) {
                this.maxDocumentLength = max;
                return this;
            }
            
            public Builder outputFormat(String format) {
                this.outputFormat = format;
                return this;
            }
            
            @Override
            public DocumentSummarizerAgent build() {
                this.resourcePreferences.put(ResourceType.COMPUTE, 0.4);
                this.resourcePreferences.put(ResourceType.API_CREDITS, 0.5);
                this.resourcePreferences.put(ResourceType.MEMORY, 0.1);
                
                return new DocumentSummarizerAgent(this);
            }
        }
    }
    
    // ========================================================================
    // DATA EXTRACTION AGENT (Low Autonomy)
    // ========================================================================
    
    /**
     * An agent that monitors a data source and extracts structured information.
     * Low autonomy: Periodic execution with single-step extraction.
     */
    public static class DataExtractionAgent extends RealisticAgent {
        
        private final String dataSource;
        private final String extractionSchema;
        private final List<String> fieldsToExtract;
        
        protected DataExtractionAgent(Builder builder) {
            super(builder);
            this.dataSource = builder.dataSource;
            this.extractionSchema = builder.extractionSchema;
            this.fieldsToExtract = new ArrayList<>(builder.fieldsToExtract);
        }
        
        @Override
        protected GoalResult executeGoal(Goal goal, ExecutionContext context) {
            long startTime = System.currentTimeMillis();
            List<String> servicesUsed = new ArrayList<>();
            
            context.log("Starting data extraction from: " + dataSource);
            
            // Step 1: Use OCR if dealing with images/documents
            String textContent = (String) goal.getParameter("content");
            
            if (textContent == null && context.hasService(ServiceType.OCR)) {
                Map<String, Object> ocrInput = Map.of(
                    "source", dataSource,
                    "format", "text"
                );
                
                ServiceResult ocrResult = context.invokeService(ServiceType.OCR, ocrInput);
                servicesUsed.add(ServiceType.OCR.name());
                
                if (ocrResult.isSuccess()) {
                    textContent = ocrResult.getOutputValue("text");
                }
            }
            
            if (textContent == null || textContent.isEmpty()) {
                return GoalResult.failure("No content to process");
            }
            
            // Step 2: Extract structured data
            if (!context.hasService(ServiceType.DATA_EXTRACTION)) {
                return GoalResult.failure("Data extraction service not available");
            }
            
            Map<String, Object> extractInput = Map.of(
                "text", textContent,
                "schema", extractionSchema,
                "fields", fieldsToExtract
            );
            
            ServiceResult extractResult = context.invokeService(
                ServiceType.DATA_EXTRACTION, extractInput);
            servicesUsed.add(ServiceType.DATA_EXTRACTION.name());
            
            if (!extractResult.isSuccess()) {
                return GoalResult.failure("Extraction failed: " + extractResult.getError());
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> extractedData = extractResult.getOutputValue("data");
            
            // Publish extracted data
            Map<String, Object> output = new HashMap<>();
            output.put("source", dataSource);
            output.put("extracted_data", extractedData);
            output.put("fields_extracted", fieldsToExtract);
            output.put("timestamp", Instant.now().toString());
            
            publish("data_extracted", output);
            
            return GoalResult.success(
                "Extracted " + (extractedData != null ? extractedData.size() : 0) + " fields",
                output,
                System.currentTimeMillis() - startTime,
                servicesUsed
            );
        }
        
        @Override
        public Set<ServiceType> getRequiredServiceTypes() {
            return Set.of(ServiceType.DATA_EXTRACTION, ServiceType.OCR);
        }
        
        @Override
        public Set<String> getOperatingDomains() {
            return Set.of("data_processing", "document_processing");
        }
        
        // Builder
        public static class Builder extends RealisticAgent.Builder<Builder> {
            private String dataSource;
            private String extractionSchema = "auto";
            private List<String> fieldsToExtract = new ArrayList<>();
            private Duration extractionPeriod = Duration.ofMinutes(30);
            
            public Builder(String agentId) {
                super(agentId);
                this.autonomyLevel = AutonomyLevel.LOW;
            }
            
            public Builder dataSource(String source) {
                this.dataSource = source;
                return this;
            }
            
            public Builder extractionSchema(String schema) {
                this.extractionSchema = schema;
                return this;
            }
            
            public Builder fieldsToExtract(List<String> fields) {
                this.fieldsToExtract = new ArrayList<>(fields);
                return this;
            }
            
            public Builder addField(String field) {
                this.fieldsToExtract.add(field);
                return this;
            }
            
            public Builder extractionPeriod(Duration period) {
                this.extractionPeriod = period;
                return this;
            }
            
            @Override
            public DataExtractionAgent build() {
                Goal extractGoal = new Goal(
                    "periodic-extraction",
                    "Extract data from " + dataSource,
                    Goal.GoalType.PERIODIC,
                    extractionPeriod
                );
                this.goals.add(extractGoal);
                
                this.resourcePreferences.put(ResourceType.COMPUTE, 0.4);
                this.resourcePreferences.put(ResourceType.API_CREDITS, 0.4);
                this.resourcePreferences.put(ResourceType.MEMORY, 0.2);
                
                return new DataExtractionAgent(this);
            }
        }
    }
    
    // ========================================================================
    // RESEARCH ASSISTANT AGENT (Medium Autonomy)
    // ========================================================================
    
    /**
     * A research assistant that can pursue multi-step research goals.
     * Medium autonomy: Can chain services, but requires checkpoints for novel situations.
     */
    public static class ResearchAssistantAgent extends RealisticAgent {
        
        private final List<String> researchDomains;
        private final int maxSourcesPerQuery;
        private final boolean citeSources;
        
        protected ResearchAssistantAgent(Builder builder) {
            super(builder);
            this.researchDomains = new ArrayList<>(builder.researchDomains);
            this.maxSourcesPerQuery = builder.maxSourcesPerQuery;
            this.citeSources = builder.citeSources;
        }
        
        @Override
        protected GoalResult executeGoal(Goal goal, ExecutionContext context) {
            long startTime = System.currentTimeMillis();
            List<String> servicesUsed = new ArrayList<>();
            Map<String, Object> outputs = new HashMap<>();
            
            String researchQuery = (String) goal.getParameter("query");
            if (researchQuery == null) {
                researchQuery = goal.getDescription();
            }
            
            context.log("Starting research: " + researchQuery);
            
            // Step 1: Generate search queries from the research question
            List<String> searchQueries = new ArrayList<>();
            
            if (context.hasService(ServiceType.REASONING)) {
                Map<String, Object> reasonInput = Map.of(
                    "task", "query_expansion",
                    "input", researchQuery,
                    "max_queries", 3
                );
                
                ServiceResult reasonResult = context.invokeService(
                    ServiceType.REASONING, reasonInput);
                servicesUsed.add(ServiceType.REASONING.name());
                
                if (reasonResult.isSuccess()) {
                    @SuppressWarnings("unchecked")
                    List<String> expanded = reasonResult.getOutputValue("queries");
                    if (expanded != null) {
                        searchQueries.addAll(expanded);
                    }
                }
            }
            
            if (searchQueries.isEmpty()) {
                searchQueries.add(researchQuery);
            }
            
            // Step 2: Search for information using vector search and knowledge retrieval
            List<Map<String, Object>> allSources = new ArrayList<>();
            
            for (String query : searchQueries) {
                // Vector search
                if (context.hasService(ServiceType.VECTOR_SEARCH)) {
                    Map<String, Object> searchInput = Map.of(
                        "query", query,
                        "max_results", maxSourcesPerQuery
                    );
                    
                    ServiceResult searchResult = context.invokeService(
                        ServiceType.VECTOR_SEARCH, searchInput);
                    servicesUsed.add(ServiceType.VECTOR_SEARCH.name());
                    
                    if (searchResult.isSuccess()) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> results = 
                            searchResult.getOutputValue("results");
                        if (results != null) {
                            allSources.addAll(results);
                        }
                    }
                }
                
                // Knowledge retrieval
                if (context.hasService(ServiceType.KNOWLEDGE_RETRIEVAL)) {
                    Map<String, Object> retrieveInput = Map.of(
                        "query", query,
                        "max_results", maxSourcesPerQuery
                    );
                    
                    ServiceResult retrieveResult = context.invokeService(
                        ServiceType.KNOWLEDGE_RETRIEVAL, retrieveInput);
                    servicesUsed.add(ServiceType.KNOWLEDGE_RETRIEVAL.name());
                    
                    if (retrieveResult.isSuccess()) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> results = 
                            retrieveResult.getOutputValue("results");
                        if (results != null) {
                            allSources.addAll(results);
                        }
                    }
                }
            }
            
            context.log("Gathered " + allSources.size() + " sources");
            
            // Step 3: Synthesize findings using text generation
            if (!context.hasService(ServiceType.TEXT_GENERATION)) {
                outputs.put("sources", allSources);
                return GoalResult.success(
                    "Gathered sources but could not synthesize",
                    outputs, System.currentTimeMillis() - startTime, servicesUsed);
            }
            
            StringBuilder synthesisPrompt = new StringBuilder();
            synthesisPrompt.append("Research question: ").append(researchQuery).append("\n\n");
            synthesisPrompt.append("Based on the following sources, provide a comprehensive answer");
            if (citeSources) {
                synthesisPrompt.append(" with citations");
            }
            synthesisPrompt.append(":\n\n");
            
            for (int i = 0; i < Math.min(allSources.size(), 10); i++) {
                Map<String, Object> source = allSources.get(i);
                synthesisPrompt.append("[").append(i + 1).append("] ");
                synthesisPrompt.append(source.get("title")).append("\n");
                synthesisPrompt.append(source.get("content")).append("\n\n");
            }
            
            Map<String, Object> genInput = Map.of(
                "prompt", synthesisPrompt.toString(),
                "max_tokens", 2000
            );
            
            ServiceResult genResult = context.invokeService(
                ServiceType.TEXT_GENERATION, genInput);
            servicesUsed.add(ServiceType.TEXT_GENERATION.name());
            
            String synthesis = "";
            if (genResult.isSuccess()) {
                synthesis = genResult.getOutputValue("text");
                outputs.put("synthesis", synthesis);
            }
            
            outputs.put("sources", allSources);
            outputs.put("queries_used", searchQueries);
            
            // Publish research result
            Map<String, Object> researchResult = new HashMap<>();
            researchResult.put("query", researchQuery);
            researchResult.put("synthesis", synthesis);
            researchResult.put("source_count", allSources.size());
            researchResult.put("timestamp", Instant.now().toString());
            
            publish("research_result", researchResult);
            
            return GoalResult.success(
                "Research completed with " + allSources.size() + " sources",
                outputs,
                System.currentTimeMillis() - startTime,
                servicesUsed
            );
        }
        
        @Override
        public Set<ServiceType> getRequiredServiceTypes() {
            return Set.of(
                ServiceType.REASONING,
                ServiceType.VECTOR_SEARCH,
                ServiceType.KNOWLEDGE_RETRIEVAL,
                ServiceType.TEXT_GENERATION
            );
        }
        
        @Override
        public Set<String> getOperatingDomains() {
            Set<String> domains = new HashSet<>(researchDomains);
            domains.add("research");
            domains.add("information_retrieval");
            return domains;
        }
        
        /**
         * Start a new research task.
         */
        public void research(String query, int priority) {
            Goal researchGoal = new Goal(
                "research-" + System.currentTimeMillis(),
                query,
                Goal.GoalType.ONE_TIME,
                null, null, priority
            );
            researchGoal.setParameter("query", query);
            addGoal(researchGoal);
        }
        
        // Builder
        public static class Builder extends RealisticAgent.Builder<Builder> {
            private List<String> researchDomains = new ArrayList<>();
            private int maxSourcesPerQuery = 5;
            private boolean citeSources = true;
            
            public Builder(String agentId) {
                super(agentId);
                this.autonomyLevel = AutonomyLevel.MEDIUM;
            }
            
            public Builder researchDomains(List<String> domains) {
                this.researchDomains = new ArrayList<>(domains);
                return this;
            }
            
            public Builder addDomain(String domain) {
                this.researchDomains.add(domain);
                return this;
            }
            
            public Builder maxSourcesPerQuery(int max) {
                this.maxSourcesPerQuery = max;
                return this;
            }
            
            public Builder citeSources(boolean cite) {
                this.citeSources = cite;
                return this;
            }
            
            @Override
            public ResearchAssistantAgent build() {
                this.resourcePreferences.put(ResourceType.API_CREDITS, 0.5);
                this.resourcePreferences.put(ResourceType.COMPUTE, 0.3);
                this.resourcePreferences.put(ResourceType.MEMORY, 0.2);
                
                return new ResearchAssistantAgent(this);
            }
        }
    }
    
    // ========================================================================
    // CODE REVIEW AGENT (Low Autonomy)
    // ========================================================================
    
    /**
     * An agent that reviews code for issues and provides feedback.
     * Low autonomy: Single-step analysis with clear boundaries.
     */
    public static class CodeReviewAgent extends RealisticAgent {
        
        private final List<String> languagesSupported;
        private final List<String> checkCategories;
        
        protected CodeReviewAgent(Builder builder) {
            super(builder);
            this.languagesSupported = new ArrayList<>(builder.languagesSupported);
            this.checkCategories = new ArrayList<>(builder.checkCategories);
        }
        
        @Override
        protected GoalResult executeGoal(Goal goal, ExecutionContext context) {
            long startTime = System.currentTimeMillis();
            List<String> servicesUsed = new ArrayList<>();
            
            String code = (String) goal.getParameter("code");
            String language = (String) goal.getParameter("language");
            String filename = (String) goal.getParameter("filename");
            
            if (code == null || code.isEmpty()) {
                return GoalResult.failure("No code provided for review");
            }
            
            context.log("Reviewing " + (language != null ? language : "code") + 
                       " in " + (filename != null ? filename : "unknown file"));
            
            // Use code analysis service
            if (!context.hasService(ServiceType.CODE_ANALYSIS)) {
                return GoalResult.failure("Code analysis service not available");
            }
            
            Map<String, Object> analysisInput = Map.of(
                "code", code,
                "language", language != null ? language : "auto",
                "checks", checkCategories
            );
            
            ServiceResult analysisResult = context.invokeService(
                ServiceType.CODE_ANALYSIS, analysisInput);
            servicesUsed.add(ServiceType.CODE_ANALYSIS.name());
            
            if (!analysisResult.isSuccess()) {
                return GoalResult.failure("Analysis failed: " + analysisResult.getError());
            }
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> issues = analysisResult.getOutputValue("issues");
            @SuppressWarnings("unchecked")
            Map<String, Object> metrics = analysisResult.getOutputValue("metrics");
            
            // Generate review summary using text generation
            String reviewSummary = "";
            if (context.hasService(ServiceType.TEXT_GENERATION) && issues != null && !issues.isEmpty()) {
                StringBuilder prompt = new StringBuilder();
                prompt.append("Summarize these code review findings:\n\n");
                for (Map<String, Object> issue : issues) {
                    prompt.append("- ").append(issue.get("type")).append(": ");
                    prompt.append(issue.get("message")).append("\n");
                }
                
                Map<String, Object> genInput = Map.of(
                    "prompt", prompt.toString(),
                    "max_tokens", 500
                );
                
                ServiceResult genResult = context.invokeService(
                    ServiceType.TEXT_GENERATION, genInput);
                servicesUsed.add(ServiceType.TEXT_GENERATION.name());
                
                if (genResult.isSuccess()) {
                    reviewSummary = genResult.getOutputValue("text");
                }
            }
            
            // Build output
            Map<String, Object> output = new HashMap<>();
            output.put("filename", filename);
            output.put("language", language);
            output.put("issues", issues);
            output.put("issue_count", issues != null ? issues.size() : 0);
            output.put("metrics", metrics);
            output.put("summary", reviewSummary);
            output.put("timestamp", Instant.now().toString());
            
            // Publish review result
            publish("code_review", output);
            
            int issueCount = issues != null ? issues.size() : 0;
            return GoalResult.success(
                "Found " + issueCount + " issues",
                output,
                System.currentTimeMillis() - startTime,
                servicesUsed
            );
        }
        
        @Override
        public Set<ServiceType> getRequiredServiceTypes() {
            return Set.of(ServiceType.CODE_ANALYSIS, ServiceType.TEXT_GENERATION);
        }
        
        @Override
        public Set<String> getOperatingDomains() {
            return Set.of("software_development", "code_quality");
        }
        
        /**
         * Convenience method to review code.
         */
        public void reviewCode(AgentRuntime runtime, String code, String language, String filename) {
            Goal goal = new Goal(
                "review-" + System.currentTimeMillis(),
                "Review code: " + filename,
                Goal.GoalType.ONE_TIME
            );
            goal.setParameter("code", code);
            goal.setParameter("language", language);
            goal.setParameter("filename", filename);
            
            addGoal(goal);
            runtime.invokeAgent(getAgentId(), goal.getGoalId());
        }
        
        // Builder
        public static class Builder extends RealisticAgent.Builder<Builder> {
            private List<String> languagesSupported = List.of(
                "java", "python", "javascript", "typescript", "go", "rust");
            private List<String> checkCategories = List.of(
                "security", "performance", "style", "bugs", "complexity");
            
            public Builder(String agentId) {
                super(agentId);
                this.autonomyLevel = AutonomyLevel.LOW;
            }
            
            public Builder languagesSupported(List<String> languages) {
                this.languagesSupported = new ArrayList<>(languages);
                return this;
            }
            
            public Builder checkCategories(List<String> categories) {
                this.checkCategories = new ArrayList<>(categories);
                return this;
            }
            
            @Override
            public CodeReviewAgent build() {
                this.resourcePreferences.put(ResourceType.COMPUTE, 0.5);
                this.resourcePreferences.put(ResourceType.API_CREDITS, 0.4);
                this.resourcePreferences.put(ResourceType.MEMORY, 0.1);
                
                return new CodeReviewAgent(this);
            }
        }
    }
    
    // ========================================================================
    // MONITORING AGENT (Low Autonomy)
    // ========================================================================
    
    /**
     * An agent that monitors system metrics and generates alerts.
     * Low autonomy: Periodic checks with clear alerting rules.
     */
    public static class MonitoringAgent extends RealisticAgent {
        
        private final Map<String, Double> thresholds;
        private final String alertChannel;
        
        protected MonitoringAgent(Builder builder) {
            super(builder);
            this.thresholds = new HashMap<>(builder.thresholds);
            this.alertChannel = builder.alertChannel;
        }
        
        @Override
        protected GoalResult executeGoal(Goal goal, ExecutionContext context) {
            long startTime = System.currentTimeMillis();
            List<String> servicesUsed = new ArrayList<>();
            
            context.log("Running monitoring check");
            
            // Get current metrics (simulated)
            Map<String, Double> currentMetrics = getCurrentMetrics();
            
            // Check thresholds and generate alerts
            List<Map<String, Object>> alerts = new ArrayList<>();
            
            for (Map.Entry<String, Double> threshold : thresholds.entrySet()) {
                String metric = threshold.getKey();
                Double limit = threshold.getValue();
                Double current = currentMetrics.get(metric);
                
                if (current != null && current > limit) {
                    Map<String, Object> alert = new HashMap<>();
                    alert.put("metric", metric);
                    alert.put("current_value", current);
                    alert.put("threshold", limit);
                    alert.put("severity", current > limit * 1.5 ? "critical" : "warning");
                    alert.put("timestamp", Instant.now().toString());
                    alerts.add(alert);
                }
            }
            
            // Publish alerts
            if (!alerts.isEmpty()) {
                for (Map<String, Object> alert : alerts) {
                    publish("alert", alert);
                }
            }
            
            // Publish status update
            Map<String, Object> status = new HashMap<>();
            status.put("metrics", currentMetrics);
            status.put("alert_count", alerts.size());
            status.put("check_time", Instant.now().toString());
            
            publish("monitoring_status", status);
            
            Map<String, Object> output = new HashMap<>();
            output.put("metrics", currentMetrics);
            output.put("alerts", alerts);
            
            return GoalResult.success(
                "Checked " + currentMetrics.size() + " metrics, " + alerts.size() + " alerts",
                output,
                System.currentTimeMillis() - startTime,
                servicesUsed
            );
        }
        
        /**
         * Simulated metric collection.
         * In production, this would connect to actual monitoring systems.
         */
        private Map<String, Double> getCurrentMetrics() {
            Map<String, Double> metrics = new HashMap<>();
            Random random = new Random();
            
            // Simulate some metrics
            metrics.put("cpu_usage", 30.0 + random.nextDouble() * 40);
            metrics.put("memory_usage", 40.0 + random.nextDouble() * 30);
            metrics.put("disk_usage", 50.0 + random.nextDouble() * 30);
            metrics.put("request_latency_ms", 50.0 + random.nextDouble() * 150);
            metrics.put("error_rate", random.nextDouble() * 5);
            
            return metrics;
        }
        
        @Override
        public Set<ServiceType> getRequiredServiceTypes() {
            return Set.of(); // Monitoring agent doesn't use AI services
        }
        
        @Override
        public Set<String> getOperatingDomains() {
            return Set.of("operations", "monitoring");
        }
        
        // Builder
        public static class Builder extends RealisticAgent.Builder<Builder> {
            private Map<String, Double> thresholds = new HashMap<>();
            private String alertChannel = "alerts";
            private Duration checkPeriod = Duration.ofMinutes(5);
            
            public Builder(String agentId) {
                super(agentId);
                this.autonomyLevel = AutonomyLevel.LOW;
                
                // Default thresholds
                thresholds.put("cpu_usage", 80.0);
                thresholds.put("memory_usage", 85.0);
                thresholds.put("disk_usage", 90.0);
                thresholds.put("request_latency_ms", 200.0);
                thresholds.put("error_rate", 5.0);
            }
            
            public Builder threshold(String metric, double value) {
                this.thresholds.put(metric, value);
                return this;
            }
            
            public Builder alertChannel(String channel) {
                this.alertChannel = channel;
                return this;
            }
            
            public Builder checkPeriod(Duration period) {
                this.checkPeriod = period;
                return this;
            }
            
            @Override
            public MonitoringAgent build() {
                Goal monitorGoal = new Goal(
                    "periodic-monitoring",
                    "Check system metrics",
                    Goal.GoalType.PERIODIC,
                    checkPeriod
                );
                this.goals.add(monitorGoal);
                
                // Monitoring agent uses minimal resources
                this.resourcePreferences.put(ResourceType.COMPUTE, 0.7);
                this.resourcePreferences.put(ResourceType.MEMORY, 0.2);
                this.resourcePreferences.put(ResourceType.API_CREDITS, 0.1);
                
                return new MonitoringAgent(this);
            }
        }
    }
}
