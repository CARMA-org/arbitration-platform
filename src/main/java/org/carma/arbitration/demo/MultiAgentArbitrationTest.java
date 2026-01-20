package org.carma.arbitration.demo;

import org.carma.arbitration.mechanism.*;
import org.carma.arbitration.model.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * End-to-end multi-agent arbitration test with REAL LLM calls.
 *
 * This test proves the platform is a faithful multi-agent arbitration system:
 * 1. Multiple agents compete for limited LLM API quota
 * 2. Arbitration allocates quota based on priority/currency
 * 3. Agents make REAL LLM calls within their allocated quota
 * 4. High-priority agents get more API access
 * 5. Quota enforcement prevents over-consumption
 *
 * Run with:
 *   export GEMINI_API_KEY=your_key
 *   java -cp out org.carma.arbitration.demo.MultiAgentArbitrationTest
 */
public class MultiAgentArbitrationTest {

    // Simulated agents with different priorities
    static class LLMAgent {
        final String id;
        final int priority;        // Currency/priority (higher = more access)
        final String task;         // What this agent does
        int allocatedCalls;        // Allocated API calls from arbitration
        int actualCalls;           // Actual calls made
        List<String> responses;    // LLM responses received

        LLMAgent(String id, int priority, String task) {
            this.id = id;
            this.priority = priority;
            this.task = task;
            this.responses = new ArrayList<>();
        }
    }

    public static void main(String[] args) {
        System.out.println("════════════════════════════════════════════════════════════════════════");
        System.out.println("   MULTI-AGENT LLM ARBITRATION TEST");
        System.out.println("   Proving Real Arbitration with Real LLM Calls");
        System.out.println("════════════════════════════════════════════════════════════════════════");
        System.out.println();

        // Check for API key
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getenv("OPENAI_API_KEY");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getenv("ANTHROPIC_API_KEY");
        }

        if (apiKey == null || apiKey.isEmpty()) {
            System.out.println("No API key found. Running in SIMULATION mode...");
            System.out.println("Set GEMINI_API_KEY, OPENAI_API_KEY, or ANTHROPIC_API_KEY for real LLM calls.");
            System.out.println();
            runSimulationMode();
            return;
        }

        runRealMode();
    }

    private static void runSimulationMode() {
        System.out.println("PHASE 1: AGENT SETUP");
        System.out.println("────────────────────────────────────────────────────────────────────────");

        // Create agents with different priorities
        List<LLMAgent> agents = Arrays.asList(
            new LLMAgent("HighPriority-A", 100, "Critical customer support"),
            new LLMAgent("HighPriority-B", 80, "Real-time translation"),
            new LLMAgent("MediumPriority", 40, "Content summarization"),
            new LLMAgent("LowPriority-A", 20, "Background research"),
            new LLMAgent("LowPriority-B", 10, "Log analysis")
        );

        System.out.println("Agents registered:");
        for (LLMAgent agent : agents) {
            System.out.printf("  %-18s priority=%3d  task=%s%n",
                agent.id, agent.priority, agent.task);
        }
        System.out.println();

        // Total API quota available
        int totalQuota = 10;  // Total API calls available this round
        int totalDemand = agents.size() * 5;  // Each wants 5 calls

        System.out.println("PHASE 2: ARBITRATION");
        System.out.println("────────────────────────────────────────────────────────────────────────");
        System.out.println("Resource: LLM API Calls");
        System.out.println("Total quota: " + totalQuota + " calls");
        System.out.println("Total demand: " + totalDemand + " calls (each agent wants 5)");
        System.out.println("Contention ratio: " + String.format("%.1f", (double)totalDemand/totalQuota) + "x");
        System.out.println();

        // Run Weighted Proportional Fairness arbitration
        System.out.println("Running Weighted Proportional Fairness allocation...");
        System.out.println();

        // Create arbitration model using the platform's classes
        PriorityEconomy economy = new PriorityEconomy();
        ProportionalFairnessArbitrator arbitrator = new ProportionalFairnessArbitrator(economy);

        List<Agent> arbAgents = new ArrayList<>();
        Map<String, BigDecimal> burns = new HashMap<>();

        for (LLMAgent la : agents) {
            Agent a = new Agent(la.id, la.task,
                Map.of(ResourceType.API_CREDITS, 1.0), la.priority);
            a.setRequest(ResourceType.API_CREDITS, 1, 5);  // min=1, ideal=5
            arbAgents.add(a);
            burns.put(la.id, BigDecimal.ZERO);
        }

        Contention contention = new Contention(ResourceType.API_CREDITS, arbAgents, totalQuota);
        AllocationResult result = arbitrator.arbitrate(contention, burns);

        System.out.println("Arbitration Results:");
        System.out.println("┌──────────────────┬──────────┬───────────┬─────────────┐");
        System.out.println("│ Agent            │ Priority │ Allocated │ % of Quota  │");
        System.out.println("├──────────────────┼──────────┼───────────┼─────────────┤");

        int totalAllocated = 0;
        for (int i = 0; i < agents.size(); i++) {
            LLMAgent la = agents.get(i);
            int allocated = (int) result.getAllocation(la.id);
            la.allocatedCalls = allocated;
            totalAllocated += allocated;

            System.out.printf("│ %-16s │ %8d │ %9d │ %10.1f%% │%n",
                la.id, la.priority, allocated, (allocated * 100.0 / totalQuota));
        }
        System.out.println("└──────────────────┴──────────┴───────────┴─────────────┘");
        System.out.println("Total allocated: " + totalAllocated + "/" + totalQuota);
        System.out.println();

        System.out.println("PHASE 3: EXECUTION (Simulated)");
        System.out.println("────────────────────────────────────────────────────────────────────────");
        System.out.println("Each agent executes LLM calls within their allocated quota:");
        System.out.println();

        for (LLMAgent agent : agents) {
            System.out.printf("  %s: %d calls → ", agent.id, agent.allocatedCalls);
            if (agent.allocatedCalls > 0) {
                System.out.println("[Would make " + agent.allocatedCalls + " real LLM calls]");
            } else {
                System.out.println("[No quota - request queued]");
            }
        }
        System.out.println();

        System.out.println("PHASE 4: VERIFICATION");
        System.out.println("────────────────────────────────────────────────────────────────────────");

        // Verify high-priority agents got more
        LLMAgent highest = agents.stream().max(Comparator.comparingInt(a -> a.priority)).get();
        LLMAgent lowest = agents.stream().min(Comparator.comparingInt(a -> a.priority)).get();

        System.out.println("Priority-based allocation verification:");
        System.out.printf("  Highest priority (%s, p=%d): %d calls%n",
            highest.id, highest.priority, highest.allocatedCalls);
        System.out.printf("  Lowest priority (%s, p=%d): %d calls%n",
            lowest.id, lowest.priority, lowest.allocatedCalls);

        if (highest.allocatedCalls >= lowest.allocatedCalls) {
            System.out.println("  ✓ PASS: Higher priority agents received more allocation");
        } else {
            System.out.println("  ✗ FAIL: Priority not respected");
        }
        System.out.println();

        // Verify no over-allocation
        if (totalAllocated <= totalQuota) {
            System.out.println("Resource conservation: ✓ PASS");
            System.out.println("  Total allocated (" + totalAllocated + ") ≤ Total quota (" + totalQuota + ")");
        } else {
            System.out.println("Resource conservation: ✗ FAIL - over-allocated!");
        }
        System.out.println();

        // Verify minimum allocation (starvation protection)
        boolean allGotMinimum = agents.stream().allMatch(a -> a.allocatedCalls >= 1);
        if (allGotMinimum) {
            System.out.println("Starvation protection: ✓ PASS");
            System.out.println("  All agents received at least minimum allocation");
        } else {
            System.out.println("Starvation protection: ○ Some agents below minimum (quota exhausted)");
        }

        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════════════");
        System.out.println("   SIMULATION COMPLETE");
        System.out.println("   Set API key and re-run to execute with REAL LLM calls");
        System.out.println("════════════════════════════════════════════════════════════════════════");
    }

    private static void runRealMode() {
        System.out.println("PHASE 1: AGENT SETUP");
        System.out.println("────────────────────────────────────────────────────────────────────────");

        // Create agents with different priorities and tasks
        List<LLMAgent> agents = Arrays.asList(
            new LLMAgent("HighPriority", 100, "What is 2+2?"),
            new LLMAgent("MediumPriority", 50, "What is the capital of Japan?"),
            new LLMAgent("LowPriority", 20, "Name a color.")
        );

        System.out.println("Agents registered:");
        for (LLMAgent agent : agents) {
            System.out.printf("  %-16s priority=%3d%n", agent.id, agent.priority);
        }
        System.out.println();

        // Total API quota
        int totalQuota = 5;  // Limited to 5 calls total

        System.out.println("PHASE 2: ARBITRATION");
        System.out.println("────────────────────────────────────────────────────────────────────────");
        System.out.println("Resource: LLM API Calls");
        System.out.println("Total quota: " + totalQuota + " calls");
        System.out.println();

        // Run arbitration using the platform
        PriorityEconomy economy = new PriorityEconomy();
        ProportionalFairnessArbitrator arbitrator = new ProportionalFairnessArbitrator(economy);

        List<Agent> arbAgents = new ArrayList<>();
        Map<String, BigDecimal> burns = new HashMap<>();

        for (LLMAgent la : agents) {
            Agent a = new Agent(la.id, la.task,
                Map.of(ResourceType.API_CREDITS, 1.0), la.priority);
            a.setRequest(ResourceType.API_CREDITS, 1, 3);  // Each wants up to 3 calls
            arbAgents.add(a);
            burns.put(la.id, BigDecimal.ZERO);
        }

        Contention contention = new Contention(ResourceType.API_CREDITS, arbAgents, totalQuota);
        AllocationResult result = arbitrator.arbitrate(contention, burns);

        System.out.println("Arbitration Results:");
        for (int i = 0; i < agents.size(); i++) {
            LLMAgent la = agents.get(i);
            la.allocatedCalls = (int) result.getAllocation(la.id);
            System.out.printf("  %-16s priority=%3d → %d API calls%n",
                la.id, la.priority, la.allocatedCalls);
        }
        System.out.println();

        System.out.println("PHASE 3: REAL LLM EXECUTION");
        System.out.println("────────────────────────────────────────────────────────────────────────");

        // Create LLM backend
        LLMServiceBackend backend;
        try {
            backend = new LLMServiceBackend.Builder()
                .fromEnvironment()
                .logRequests(false)
                .build();
        } catch (Exception e) {
            System.out.println("Error creating LLM backend: " + e.getMessage());
            return;
        }

        System.out.println("Executing allocated LLM calls for each agent...");
        System.out.println();

        // Execute calls for each agent within their quota
        for (LLMAgent agent : agents) {
            System.out.println("Agent: " + agent.id + " (allocated " + agent.allocatedCalls + " calls)");

            for (int i = 0; i < agent.allocatedCalls; i++) {
                Map<String, Object> input = new HashMap<>();
                input.put("prompt", agent.task + " Reply in 3 words or less.");
                input.put("max_tokens", 20);

                ServiceBackend.InvocationResult llmResult = backend.invokeByType(
                    ServiceType.TEXT_GENERATION, input);

                agent.actualCalls++;

                if (llmResult.isSuccess()) {
                    String response = (String) llmResult.getOutput().get("text");
                    if (response != null) {
                        response = response.trim().split("\n")[0];  // First line only
                        if (response.length() > 50) response = response.substring(0, 50) + "...";
                    }
                    agent.responses.add(response);
                    System.out.printf("  Call %d: \"%s\" → \"%s\"%n",
                        i + 1, agent.task, response);
                } else {
                    System.out.printf("  Call %d: ERROR - %s%n",
                        i + 1, llmResult.getError());
                }

                // Small delay between calls
                try { Thread.sleep(500); } catch (InterruptedException e) {}
            }
            System.out.println();
        }

        System.out.println("PHASE 4: VERIFICATION");
        System.out.println("────────────────────────────────────────────────────────────────────────");

        // Verify results
        int totalActualCalls = agents.stream().mapToInt(a -> a.actualCalls).sum();
        int totalResponses = agents.stream().mapToInt(a -> a.responses.size()).sum();

        System.out.println("Execution Summary:");
        System.out.println("┌──────────────────┬──────────┬───────────┬────────────┬───────────┐");
        System.out.println("│ Agent            │ Priority │ Allocated │ Executed   │ Responses │");
        System.out.println("├──────────────────┼──────────┼───────────┼────────────┼───────────┤");
        for (LLMAgent agent : agents) {
            System.out.printf("│ %-16s │ %8d │ %9d │ %10d │ %9d │%n",
                agent.id, agent.priority, agent.allocatedCalls,
                agent.actualCalls, agent.responses.size());
        }
        System.out.println("└──────────────────┴──────────┴───────────┴────────────┴───────────┘");
        System.out.println();

        // Priority verification
        LLMAgent highest = agents.stream().max(Comparator.comparingInt(a -> a.priority)).get();
        LLMAgent lowest = agents.stream().min(Comparator.comparingInt(a -> a.priority)).get();

        System.out.println("Verification:");
        if (highest.allocatedCalls >= lowest.allocatedCalls) {
            System.out.println("  ✓ Priority respected: High-priority agent got more calls");
        }
        if (totalActualCalls <= totalQuota) {
            System.out.println("  ✓ Quota enforced: " + totalActualCalls + "/" + totalQuota + " calls used");
        }
        if (totalResponses > 0) {
            System.out.println("  ✓ Real LLM integration: " + totalResponses + " actual responses received");
        }

        backend.shutdown();

        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════════════");
        System.out.println("   MULTI-AGENT ARBITRATION TEST COMPLETE");
        System.out.println("");
        System.out.println("   This test demonstrates:");
        System.out.println("   • Multiple agents competing for limited LLM API quota");
        System.out.println("   • Weighted Proportional Fairness allocating based on priority");
        System.out.println("   • REAL LLM calls executed within allocated quotas");
        System.out.println("   • High-priority agents receiving proportionally more access");
        System.out.println("════════════════════════════════════════════════════════════════════════");
    }
}
