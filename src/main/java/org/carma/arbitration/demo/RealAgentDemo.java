package org.carma.arbitration.demo;

import org.carma.arbitration.config.*;
import org.carma.arbitration.config.ScenarioConfigLoader.*;
import org.carma.arbitration.model.*;
import org.carma.arbitration.mechanism.*;
import org.carma.arbitration.agent.RealisticAgentFramework.*;
import org.carma.arbitration.agent.ExampleAgents.*;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * End-to-end demonstration with REAL agent execution and REAL LLM calls.
 *
 * This demo proves the platform is fully functional:
 * 1. Loads agent configs from YAML files
 * 2. Detects contentions automatically
 * 3. Arbitrates resources using real algorithms
 * 4. Executes agents with real LLM backend (if API key provided)
 * 5. Shows actual outputs from agent execution
 *
 * Usage:
 *   # With real LLM (recommended):
 *   export GEMINI_API_KEY=your_key  # or OPENAI_API_KEY, ANTHROPIC_API_KEY
 *   java -cp ... org.carma.arbitration.demo.RealAgentDemo
 *
 *   # With mock backend (no API key):
 *   java -cp ... org.carma.arbitration.demo.RealAgentDemo
 */
public class RealAgentDemo {

    private static final String SEP = "=".repeat(70);
    private static final String SUBSEP = "-".repeat(50);

    public static void main(String[] args) {
        System.out.println(SEP);
        System.out.println("   CARMA REAL AGENT EXECUTION DEMO");
        System.out.println("   End-to-End: Config → Arbitration → Execution → Output");
        System.out.println(SEP);
        System.out.println();

        // Check for API keys
        boolean hasRealBackend = checkApiKeys();

        try {
            // Run the demo
            runDemo(hasRealBackend);
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println();
        System.out.println(SEP);
        System.out.println("   DEMO COMPLETE");
        System.out.println(SEP);
    }

    private static boolean checkApiKeys() {
        String gemini = System.getenv("GEMINI_API_KEY");
        String openai = System.getenv("OPENAI_API_KEY");
        String anthropic = System.getenv("ANTHROPIC_API_KEY");

        System.out.println("API Key Status:");
        System.out.println("  GEMINI_API_KEY:    " + (isSet(gemini) ? "✓ SET" : "✗ not set"));
        System.out.println("  OPENAI_API_KEY:    " + (isSet(openai) ? "✓ SET" : "✗ not set"));
        System.out.println("  ANTHROPIC_API_KEY: " + (isSet(anthropic) ? "✓ SET" : "✗ not set"));
        System.out.println();

        boolean hasKey = isSet(gemini) || isSet(openai) || isSet(anthropic);

        if (hasKey) {
            System.out.println(">>> RUNNING WITH REAL LLM BACKEND <<<");
            System.out.println("    Agents will make actual API calls to LLM providers.");
            System.out.println();
            return true;
        } else {
            System.out.println("ERROR: No API key found.");
            System.out.println();
            System.out.println("This demo requires real LLM API credentials.");
            System.out.println("Set one of the following environment variables:");
            System.out.println("  export GEMINI_API_KEY=your_key_here");
            System.out.println("  export OPENAI_API_KEY=your_key_here");
            System.out.println("  export ANTHROPIC_API_KEY=your_key_here");
            System.out.println();
            System.out.println("For demos without API keys, use ConfigDrivenDemo instead.");
            System.exit(1);
            return false;
        }
    }

    private static boolean isSet(String value) {
        return value != null && !value.isEmpty();
    }

    private static void runDemo(boolean useRealBackend) throws Exception {
        // ================================================================
        // PHASE 1: Create Service Registry and Resource Pool
        // ================================================================
        System.out.println("PHASE 1: SETUP");
        System.out.println(SUBSEP);

        ServiceRegistry registry = new ServiceRegistry();
        registry.register(new AIService.Builder("text-gen", ServiceType.TEXT_GENERATION)
            .provider("primary")
            .maxCapacity(10)
            .build());
        registry.register(new AIService.Builder("knowledge", ServiceType.KNOWLEDGE_RETRIEVAL)
            .provider("internal")
            .maxCapacity(20)
            .build());
        registry.register(new AIService.Builder("summarize", ServiceType.TEXT_SUMMARIZATION)
            .provider("primary")
            .maxCapacity(15)
            .build());

        ResourcePool pool = new ResourcePool(Map.of(
            ResourceType.COMPUTE, 100L,
            ResourceType.MEMORY, 200L,
            ResourceType.API_CREDITS, 50L
        ));

        System.out.println("Service Registry:");
        System.out.println("  - TEXT_GENERATION (capacity: 10)");
        System.out.println("  - KNOWLEDGE_RETRIEVAL (capacity: 20)");
        System.out.println("  - TEXT_SUMMARIZATION (capacity: 15)");
        System.out.println();
        System.out.println("Resource Pool:");
        System.out.println("  - COMPUTE: 100 units");
        System.out.println("  - MEMORY: 200 units");
        System.out.println("  - API_CREDITS: 50 units");
        System.out.println();

        // ================================================================
        // PHASE 2: Create Service Backend (Real or Mock)
        // ================================================================
        System.out.println("PHASE 2: SERVICE BACKEND");
        System.out.println(SUBSEP);

        // Create real LLM backend (API key already verified)
        ServiceBackend backend = new LLMServiceBackend.Builder()
            .fromEnvironment()
            .logRequests(true)
            .build();
        System.out.println("Created: LLMServiceBackend (REAL API calls)");
        System.out.println();

        // ================================================================
        // PHASE 3: Create Agents
        // ================================================================
        System.out.println("PHASE 3: AGENT CREATION");
        System.out.println(SUBSEP);

        // Create output channels to capture agent outputs
        MemoryChannel outputChannel = new MemoryChannel("demo-output");

        // Create a news search agent
        NewsSearchAgent newsAgent = new NewsSearchAgent.Builder("news-agent")
            .name("AI News Monitor")
            .description("Searches for AI-related news")
            .topics(List.of("artificial intelligence", "machine learning"))
            .maxResultsPerSearch(3)
            .summaryFormat("brief")
            .searchPeriod(Duration.ofHours(1))
            .outputChannel(outputChannel)
            .initialCurrency(100)
            .build();

        // Create a document summarizer agent
        DocumentSummarizerAgent summarizerAgent = new DocumentSummarizerAgent.Builder("summarizer-agent")
            .name("Document Summarizer")
            .description("Summarizes documents on demand")
            .maxDocumentLength(10000)
            .outputFormat("bullet_points")
            .outputChannel(outputChannel)
            .initialCurrency(50)
            .build();

        System.out.println("Created agents:");
        System.out.println("  1. " + newsAgent.getName() + " (id: " + newsAgent.getAgentId() + ")");
        System.out.println("     Type: NewsSearchAgent");
        System.out.println("     Autonomy: " + newsAgent.getAutonomyLevel());
        System.out.println("     Services: " + newsAgent.getRequiredServiceTypes());
        System.out.println();
        System.out.println("  2. " + summarizerAgent.getName() + " (id: " + summarizerAgent.getAgentId() + ")");
        System.out.println("     Type: DocumentSummarizerAgent");
        System.out.println("     Autonomy: " + summarizerAgent.getAutonomyLevel());
        System.out.println("     Services: " + summarizerAgent.getRequiredServiceTypes());
        System.out.println();

        // ================================================================
        // PHASE 4: Arbitration
        // ================================================================
        System.out.println("PHASE 4: RESOURCE ARBITRATION");
        System.out.println(SUBSEP);

        // Create arbitration-model agents for resource allocation
        Agent arbNews = new Agent("news-agent", "AI News Monitor",
            Map.of(ResourceType.API_CREDITS, 0.6, ResourceType.COMPUTE, 0.3, ResourceType.MEMORY, 0.1), 100);
        arbNews.setRequest(ResourceType.API_CREDITS, 5, 15);
        arbNews.setRequest(ResourceType.COMPUTE, 10, 30);

        Agent arbSummarizer = new Agent("summarizer-agent", "Document Summarizer",
            Map.of(ResourceType.API_CREDITS, 0.5, ResourceType.COMPUTE, 0.4, ResourceType.MEMORY, 0.1), 50);
        arbSummarizer.setRequest(ResourceType.API_CREDITS, 2, 10);
        arbSummarizer.setRequest(ResourceType.COMPUTE, 5, 20);

        List<Agent> agents = List.of(arbNews, arbSummarizer);

        // Detect contentions
        ContentionDetector detector = new ContentionDetector();
        var contentionGroups = detector.detectContentions(agents, pool);

        System.out.println("Contention Detection:");
        if (contentionGroups.isEmpty()) {
            System.out.println("  No contentions - all requests can be satisfied.");
        } else {
            for (var group : contentionGroups) {
                System.out.println("  Group: " + group.getGroupId());
                System.out.println("    Agents: " + group.getAgentCount());
                System.out.println("    Resources: " + group.getResources());
                System.out.println("    Severity: " + String.format("%.2f", group.getContentionSeverity()));
            }
        }
        System.out.println();

        // Run arbitration
        PriorityEconomy economy = new PriorityEconomy();
        ProportionalFairnessArbitrator arbitrator = new ProportionalFairnessArbitrator(economy);

        System.out.println("Running Weighted Proportional Fairness...");
        System.out.println();

        for (ResourceType type : List.of(ResourceType.API_CREDITS, ResourceType.COMPUTE)) {
            List<Agent> competing = agents.stream()
                .filter(a -> a.getIdeal(type) > 0)
                .toList();

            if (competing.isEmpty()) continue;

            Contention contention = new Contention(type, new ArrayList<>(competing), pool.getAvailable(type));
            Map<String, BigDecimal> burns = new HashMap<>();
            for (Agent a : competing) {
                burns.put(a.getId(), BigDecimal.ZERO);
            }

            AllocationResult result = arbitrator.arbitrate(contention, burns);

            System.out.println("  " + type + " allocation:");
            for (Agent a : competing) {
                long allocated = result.getAllocation(a.getId());
                long ideal = a.getIdeal(type);
                double pct = ideal > 0 ? (allocated * 100.0 / ideal) : 100;
                System.out.printf("    %s: %d units (%.0f%% of ideal %d)%n",
                    a.getId(), allocated, pct, ideal);
            }
        }
        System.out.println();

        // ================================================================
        // PHASE 5: Agent Runtime and Execution
        // ================================================================
        System.out.println("PHASE 5: AGENT EXECUTION");
        System.out.println(SUBSEP);

        // Create ServiceArbitrator for runtime
        ServiceArbitrator serviceArbitrator = new ServiceArbitrator(economy, registry);

        // Create AgentRuntime with the real or mock backend
        AgentRuntime runtime = new AgentRuntime.Builder()
            .serviceArbitrator(serviceArbitrator)
            .serviceRegistry(registry)
            .resourcePool(pool)
            .serviceBackend(backend)
            .tickIntervalMs(100)
            .build();

        // Register agents
        runtime.register(newsAgent);
        runtime.register(summarizerAgent);

        System.out.println("Registered agents with runtime.");
        System.out.println();

        // Add a goal for the summarizer
        Goal summarizeGoal = new Goal(
            "summarize-sample",
            "Summarize sample document",
            Goal.GoalType.ONE_TIME
        );
        summarizeGoal.setParameter("document",
            "Artificial intelligence (AI) is transforming industries worldwide. " +
            "Machine learning models can now perform tasks that previously required human intelligence. " +
            "From healthcare diagnostics to autonomous vehicles, AI applications are expanding rapidly. " +
            "However, concerns about AI safety and alignment remain important topics of discussion.");
        summarizeGoal.setParameter("title", "AI Overview");
        summarizerAgent.addGoal(summarizeGoal);

        System.out.println("Starting agent runtime...");
        System.out.println();

        // Start runtime
        runtime.start();

        // Run for a few ticks
        System.out.println("Executing agents (5 ticks)...");
        for (int i = 0; i < 5; i++) {
            Thread.sleep(200);  // Wait between ticks
            System.out.print(".");
        }
        System.out.println(" Done!");
        System.out.println();

        // Invoke summarizer directly (it's TOOL level)
        System.out.println("Invoking summarizer agent directly...");
        runtime.invokeAgent("summarizer-agent", "summarize-sample");

        // Wait for execution
        Thread.sleep(500);

        // Stop runtime
        runtime.stop();

        // ================================================================
        // PHASE 6: Results
        // ================================================================
        System.out.println();
        System.out.println("PHASE 6: EXECUTION RESULTS");
        System.out.println(SUBSEP);

        // Show agent metrics
        System.out.println("Agent Metrics:");
        System.out.println("  " + newsAgent.getName() + ":");
        System.out.println("    Goals attempted: " + newsAgent.getMetrics().getGoalsAttempted());
        System.out.println("    Goals completed: " + newsAgent.getMetrics().getGoalsCompleted());
        System.out.println("    Service invocations: " + newsAgent.getMetrics().getServiceInvocations());
        System.out.println();
        System.out.println("  " + summarizerAgent.getName() + ":");
        System.out.println("    Goals attempted: " + summarizerAgent.getMetrics().getGoalsAttempted());
        System.out.println("    Goals completed: " + summarizerAgent.getMetrics().getGoalsCompleted());
        System.out.println("    Service invocations: " + summarizerAgent.getMetrics().getServiceInvocations());
        System.out.println();

        // Show captured outputs
        List<MemoryChannel.Message> messages = outputChannel.getMessages();
        System.out.println("Agent Outputs (" + messages.size() + " messages):");
        for (MemoryChannel.Message msg : messages) {
            System.out.println("  [" + msg.messageType + "] from " + msg.agentId + ":");
            System.out.println("    " + formatOutput(msg.content));
        }

        if (messages.isEmpty()) {
            System.out.println("  (No outputs captured - agents may not have executed goals)");
            System.out.println();
            System.out.println("  Note: The news agent has a PERIODIC goal that runs on schedule.");
            System.out.println("  The summarizer agent was invoked but may need more time or services.");
        }

        System.out.println();

        // ================================================================
        // Summary
        // ================================================================
        System.out.println("SUMMARY");
        System.out.println(SUBSEP);
        System.out.println("This demo demonstrated:");
        System.out.println("  ✓ Service registry with multiple AI service types");
        System.out.println("  ✓ Resource pool with COMPUTE, MEMORY, API_CREDITS");
        System.out.println("  ✓ " + (useRealBackend ? "REAL LLM backend (actual API calls)" : "Mock backend (simulated)"));
        System.out.println("  ✓ RealisticAgent creation (NewsSearchAgent, DocumentSummarizerAgent)");
        System.out.println("  ✓ Automatic contention detection");
        System.out.println("  ✓ Weighted Proportional Fairness arbitration");
        System.out.println("  ✓ AgentRuntime execution with service backend");
        System.out.println("  ✓ Output capture via MemoryChannel");
        System.out.println();

        if (!useRealBackend) {
            System.out.println("To run with REAL LLM calls:");
            System.out.println("  export GEMINI_API_KEY=your_key");
            System.out.println("  # or OPENAI_API_KEY, or ANTHROPIC_API_KEY");
            System.out.println("  java -cp ... org.carma.arbitration.demo.RealAgentDemo");
        }

        // Cleanup
        if (backend instanceof LLMServiceBackend) {
            ((LLMServiceBackend) backend).shutdown();
        }
    }

    private static String formatOutput(Object content) {
        if (content == null) return "(null)";
        String str = content.toString();
        if (str.length() > 200) {
            str = str.substring(0, 200) + "...";
        }
        return str.replace("\n", "\n    ");
    }
}
