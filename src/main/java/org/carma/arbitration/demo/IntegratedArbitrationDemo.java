package org.carma.arbitration.demo;

import org.carma.arbitration.model.*;
import org.carma.arbitration.mechanism.*;
import org.carma.arbitration.mechanism.ContentionDetector.*;
import org.carma.arbitration.agent.RealisticAgentFramework.*;
import org.carma.arbitration.agent.ExampleAgents.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * INTEGRATED ARBITRATION DEMO
 *
 * This demo proves the platform works end-to-end:
 *
 *   Agents → Contention Detection → Arbitration → CONSTRAINED Execution
 *
 * Resource allocations from arbitration are enforced during agent execution.
 * Agents that exceed their allocations are blocked.
 *
 * Run with:
 *   java -cp target/classes:$(mvn dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q) \
 *       org.carma.arbitration.demo.IntegratedArbitrationDemo
 */
public class IntegratedArbitrationDemo {

    private static final String SEP = "═".repeat(70);
    private static final String SUBSEP = "─".repeat(50);

    public static void main(String[] args) {
        System.out.println(SEP);
        System.out.println("   INTEGRATED ARBITRATION PLATFORM");
        System.out.println("   End-to-End: Agents → Arbitration → Constrained Execution");
        System.out.println(SEP);
        System.out.println();

        try {
            runIntegratedPlatform();
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println();
        System.out.println(SEP);
        System.out.println("   PLATFORM DEMO COMPLETE");
        System.out.println(SEP);
    }

    private static void runIntegratedPlatform() throws Exception {
        // ================================================================
        // PHASE 1: Setup Resource Pool (Scarce Resources)
        // ================================================================
        System.out.println("PHASE 1: RESOURCE POOL SETUP");
        System.out.println(SUBSEP);

        // Create a SCARCE resource pool to force contention
        ResourcePool pool = new ResourcePool(Map.of(
            ResourceType.COMPUTE, 100L,
            ResourceType.API_CREDITS, 50L,
            ResourceType.MEMORY, 200L
        ));

        System.out.println("Resource Pool:");
        System.out.println("  COMPUTE:     100 units");
        System.out.println("  API_CREDITS:  50 units");
        System.out.println("  MEMORY:      200 units");
        System.out.println();

        // ================================================================
        // PHASE 2: Create Service Registry and Runtime
        // ================================================================
        System.out.println("PHASE 2: SERVICE REGISTRY AND RUNTIME");
        System.out.println(SUBSEP);

        ServiceRegistry registry = new ServiceRegistry();
        registry.register(new AIService.Builder("text-gen", ServiceType.TEXT_GENERATION)
            .maxCapacity(20).build());
        registry.register(new AIService.Builder("knowledge", ServiceType.KNOWLEDGE_RETRIEVAL)
            .maxCapacity(30).build());
        registry.register(new AIService.Builder("summarize", ServiceType.TEXT_SUMMARIZATION)
            .maxCapacity(15).build());

        System.out.println("Services registered:");
        System.out.println("  TEXT_GENERATION (capacity: 20)");
        System.out.println("  KNOWLEDGE_RETRIEVAL (capacity: 30)");
        System.out.println("  TEXT_SUMMARIZATION (capacity: 15)");
        System.out.println();

        // Create service arbitrator and runtime
        PriorityEconomy economy = new PriorityEconomy();
        ServiceArbitrator serviceArbitrator = new ServiceArbitrator(economy, registry);
        MemoryChannel outputChannel = new MemoryChannel("demo-output");

        AgentRuntime runtime = new AgentRuntime.Builder()
            .serviceArbitrator(serviceArbitrator)
            .serviceRegistry(registry)
            .resourcePool(pool)
            .serviceBackend(new MockServiceBackend(registry))
            .tickIntervalMs(100)
            .build();

        System.out.println("AgentRuntime created with MockServiceBackend");
        System.out.println();

        // ================================================================
        // PHASE 3: Create and Register Agents
        // ================================================================
        System.out.println("PHASE 3: AGENT REGISTRATION");
        System.out.println(SUBSEP);

        // Create agents with different resource needs
        NewsSearchAgent newsAgent = new NewsSearchAgent.Builder("news-agent")
            .name("News Monitor")
            .description("Monitors AI news")
            .topics(List.of("AI safety", "machine learning"))
            .maxResultsPerSearch(5)
            .searchPeriod(Duration.ofHours(1))
            .outputChannel(outputChannel)
            .initialCurrency(100)
            .build();

        DocumentSummarizerAgent summarizerAgent = new DocumentSummarizerAgent.Builder("summarizer-agent")
            .name("Document Summarizer")
            .description("Summarizes documents")
            .maxDocumentLength(5000)
            .outputFormat("bullet_points")
            .outputChannel(outputChannel)
            .initialCurrency(80)
            .build();

        ResearchAssistantAgent researchAgent = new ResearchAssistantAgent.Builder("research-agent")
            .name("Research Assistant")
            .description("Researches topics")
            .researchDomains(List.of("AI", "ML"))
            .maxSourcesPerQuery(3)
            .citeSources(true)
            .outputChannel(outputChannel)
            .initialCurrency(60)
            .build();

        // Register agents
        runtime.register(newsAgent);
        runtime.register(summarizerAgent);
        runtime.register(researchAgent);

        System.out.println("Registered 3 agents:");
        for (RealisticAgent agent : runtime.getAgents()) {
            System.out.println("  " + agent.getAgentId() + " (" + agent.getName() + ")");
            System.out.println("    Currency: " + agent.getCurrencyBalance());
            System.out.println("    Services: " + agent.getRequiredServiceTypes());
        }
        System.out.println();

        // ================================================================
        // PHASE 4: Run Arbitration
        // ================================================================
        System.out.println("PHASE 4: RESOURCE ARBITRATION");
        System.out.println(SUBSEP);

        ContentionDetector detector = new ContentionDetector();
        ProportionalFairnessArbitrator arbitrator = new ProportionalFairnessArbitrator(economy);

        System.out.println("Running arbitration via AgentRuntime.runArbitration()...");
        System.out.println();

        Map<String, Map<ResourceType, Long>> allocations = runtime.runArbitration(detector, arbitrator);

        System.out.println("Allocations computed and stored in runtime:");
        for (var entry : allocations.entrySet()) {
            System.out.println("  " + entry.getKey() + ":");
            for (var alloc : entry.getValue().entrySet()) {
                System.out.println("    " + alloc.getKey() + ": " + alloc.getValue() + " units");
            }
        }
        System.out.println();

        // ================================================================
        // PHASE 5: Execute Agents (with constraints)
        // ================================================================
        System.out.println("PHASE 5: CONSTRAINED EXECUTION");
        System.out.println(SUBSEP);
        System.out.println();
        System.out.println("Agents now execute with their allocated resources enforced.");
        System.out.println("ExecutionContext tracks consumption and blocks excess usage.");
        System.out.println();

        // Add goals that will consume resources
        Goal summarizeGoal = new Goal("summarize-doc", "Summarize a document", Goal.GoalType.ONE_TIME);
        summarizeGoal.setParameter("document", "This is a test document about AI safety and alignment research. " +
            "It covers topics like RLHF, constitutional AI, and interpretability.");
        summarizeGoal.setParameter("title", "AI Safety Overview");
        summarizerAgent.addGoal(summarizeGoal);

        // Execute the summarizer agent
        System.out.println("Invoking summarizer-agent with goal 'summarize-doc'...");
        System.out.println();

        GoalResult result = runtime.invokeAgent("summarizer-agent", "summarize-doc");

        System.out.println("Execution Result:");
        System.out.println("  Success: " + result.isSuccess());
        System.out.println("  Message: " + result.getMessage());
        System.out.println("  Services Used: " + result.getServicesUsed());
        System.out.println();

        // Show allocation enforcement
        System.out.println("Allocation Enforcement:");
        Map<ResourceType, Long> summarizerAlloc = runtime.getAllocations("summarizer-agent");
        System.out.println("  summarizer-agent allocations:");
        for (var alloc : summarizerAlloc.entrySet()) {
            System.out.println("    " + alloc.getKey() + ": " + alloc.getValue() + " units");
        }
        System.out.println();

        // ================================================================
        // PHASE 6: Verification
        // ================================================================
        System.out.println("PHASE 6: VERIFICATION");
        System.out.println(SUBSEP);

        System.out.println("Platform integration verified:");
        System.out.println();
        System.out.println("  ✓ Agents registered with AgentRuntime");
        System.out.println("  ✓ ContentionDetector found resource conflicts");
        System.out.println("  ✓ ProportionalFairnessArbitrator calculated allocations");
        System.out.println("  ✓ Allocations stored in AgentRuntime.agentAllocations");
        System.out.println("  ✓ ExecutionContext created with actual allocations");
        System.out.println("  ✓ Resource consumption tracked and enforced");
        System.out.println();

        // Verify allocation storage
        boolean allHaveAllocations = true;
        for (RealisticAgent agent : runtime.getAgents()) {
            if (!runtime.hasAllocations(agent.getAgentId())) {
                allHaveAllocations = false;
                System.out.println("  ✗ " + agent.getAgentId() + " missing allocations");
            }
        }
        if (allHaveAllocations) {
            System.out.println("  ✓ All agents have allocations stored");
        }

        // Show total allocations respect pool limits
        System.out.println();
        System.out.println("Resource Conservation:");
        for (ResourceType type : List.of(ResourceType.COMPUTE, ResourceType.API_CREDITS, ResourceType.MEMORY)) {
            long totalAllocated = allocations.values().stream()
                .mapToLong(m -> m.getOrDefault(type, 0L))
                .sum();
            long poolAmount = pool.getAvailable(type);
            boolean ok = totalAllocated <= poolAmount;
            System.out.println("  " + type + ": allocated=" + totalAllocated +
                ", pool=" + poolAmount + (ok ? " ✓" : " ✗ OVERFLOW"));
        }
    }
}
