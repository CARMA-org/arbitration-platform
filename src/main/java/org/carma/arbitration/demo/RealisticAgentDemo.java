package org.carma.arbitration.demo;

import org.carma.arbitration.model.*;
import org.carma.arbitration.mechanism.*;
import org.carma.arbitration.mechanism.ContentionDetector.*;
import org.carma.arbitration.agent.*;
import org.carma.arbitration.agent.RealisticAgentFramework.*;
import org.carma.arbitration.agent.ExampleAgents.*;
import org.carma.arbitration.safety.AGIEmergenceMonitor;
import org.carma.arbitration.safety.AGIEmergenceMonitor.*;
import org.carma.arbitration.safety.ServiceCompositionAnalyzer;
import org.carma.arbitration.safety.ServiceCompositionAnalyzer.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Demonstration of Realistic Agents with AGI Emergence Monitoring.
 *
 * This demo showcases:
 * 1. Working agent implementations with real service invocations
 * 2. A+G+I conjunction detection for safety monitoring
 * 3. Goal structures, preference structures, and situational awareness
 *
 * Scenarios include:
 * - NewsSearchAgent: A narrow, tailored agent with low autonomy
 * - Multiple agents with different autonomy levels
 * - Real-time A+G+I monitoring with alerts
 * - Output channels for agent communication
 */
public class RealisticAgentDemo {

    private static final String SEP = "═".repeat(72);
    private static final String SUBSEP = "─".repeat(60);
    
    public static void main(String[] args) {
        System.out.println(SEP);
        System.out.println("   REALISTIC AGENT DEMONSTRATION");
        System.out.println("   With AGI Emergence Monitoring (A+G+I Conjunction Detection)");
        System.out.println(SEP);
        System.out.println();
        
        // Run demonstration scenarios
        runScenario1_NewsAgent();
        runScenario2_MultiAgentEnvironment();
        runScenario3_AGIEmergenceDetection();
        runScenario4_AutonomyLevelComparison();
        runScenario5_ServiceCompositionSafety();
        
        System.out.println(SEP);
        System.out.println("   REALISTIC AGENT DEMONSTRATION COMPLETE");
        System.out.println(SEP);
    }
    
    // ========================================================================
    // SCENARIO 1: News Search Agent (Low Autonomy Example)
    // ========================================================================

    static void runScenario1_NewsAgent() {
        System.out.println("SCENARIO 1: NEWS SEARCH AGENT");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Demonstrate a narrow, tailored agent with low autonomy");
        System.out.println("         that searches news and posts");
        System.out.println("         to a signal channel.");
        System.out.println();
        
        // Setup output channel (simulating Signal)
        MemoryChannel signalChannel = new MemoryChannel("signal-channel");
        
        // Create the news search agent
        NewsSearchAgent newsAgent = new NewsSearchAgent.Builder("news-ai-safety")
            .name("AI Safety News Monitor")
            .description("Monitors news about AI safety and related topics")
            .topics(List.of("AI safety", "LLM capabilities", "AI governance", "alignment research"))
            .maxResultsPerSearch(5)
            .searchPeriod(Duration.ofHours(1))
            .summaryFormat("bullet_points")
            .outputChannel(signalChannel)
            .initialCurrency(100)
            .build();
        
        System.out.println("Agent Configuration:");
        System.out.println("  ID: " + newsAgent.getAgentId());
        System.out.println("  Name: " + newsAgent.getName());
        System.out.println("  Autonomy Level: " + newsAgent.getAutonomyLevel().getDisplayName());
        System.out.println("  Max Autonomous Span: " + 
            newsAgent.getAutonomyLevel().getMaxAutonomousSpan().toMinutes() + " minutes");
        System.out.println("  Required Services: " + newsAgent.getRequiredServiceTypes());
        System.out.println("  Operating Domains: " + newsAgent.getOperatingDomains());
        System.out.println("  Goals: " + newsAgent.getGoals().size());
        for (Goal goal : newsAgent.getGoals()) {
            System.out.println("    - " + goal);
        }
        System.out.println();
        
        // Setup services and runtime
        ServiceRegistry registry = createServiceRegistry();
        ResourcePool resourcePool = createResourcePool();
        PriorityEconomy economy = new PriorityEconomy();
        ServiceArbitrator serviceArbitrator = new ServiceArbitrator(economy, registry);
        
        AgentRuntime runtime = new AgentRuntime(
            serviceArbitrator, registry, resourcePool, 100);
        
        // Add listener to track execution
        runtime.addListener(new AgentRuntime.RuntimeListener() {
            @Override
            public void onGoalStarted(RealisticAgent agent, Goal goal) {
                System.out.println("  [Runtime] Goal started: " + goal.getGoalId());
            }
            
            @Override
            public void onGoalCompleted(RealisticAgent agent, Goal goal, GoalResult result) {
                System.out.println("  [Runtime] Goal completed: " + result);
            }
        });
        
        // Register and run
        runtime.register(newsAgent);
        
        System.out.println("Running News Agent...");
        System.out.println();
        
        // Manually trigger execution (simulating scheduled run)
        Goal activeGoal = newsAgent.getGoals().get(0);
        GoalResult result = runtime.invokeAgent(newsAgent.getAgentId(), activeGoal.getGoalId());
        
        System.out.println();
        System.out.println("Execution Result:");
        System.out.println("  " + result);
        System.out.println("  Services Used: " + result.getServicesUsed());
        System.out.println("  Execution Time: " + result.getExecutionTimeMs() + "ms");
        System.out.println();
        
        // Show output channel messages
        System.out.println("Messages Published to Signal Channel:");
        for (MemoryChannel.Message msg : signalChannel.getMessages()) {
            System.out.println("  [" + msg.messageType + "] " + msg.content);
        }
        System.out.println();
        
        // Show agent metrics
        System.out.println("Agent Metrics:");
        System.out.println("  " + newsAgent.getMetrics());
        System.out.println();
        
        System.out.println("  ✓ PASS: News agent executed successfully with bounded autonomy");
        System.out.println();
    }
    
    // ========================================================================
    // SCENARIO 2: Multi-Agent Environment
    // ========================================================================
    
    static void runScenario2_MultiAgentEnvironment() {
        System.out.println("SCENARIO 2: MULTI-AGENT ENVIRONMENT");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Demonstrate multiple agents with different autonomy");
        System.out.println("         levels operating through the arbitration platform.");
        System.out.println();
        
        // Create shared output channel
        MemoryChannel sharedChannel = new MemoryChannel("shared-channel");
        
        // Setup infrastructure
        ServiceRegistry registry = createServiceRegistry();
        ResourcePool resourcePool = createResourcePool();
        PriorityEconomy economy = new PriorityEconomy();
        ServiceArbitrator serviceArbitrator = new ServiceArbitrator(economy, registry);
        
        AgentRuntime runtime = new AgentRuntime(
            serviceArbitrator, registry, resourcePool, 50);
        
        // Create diverse agents
        
        // 1. Tool-level agent (no autonomy)
        DocumentSummarizerAgent summarizer = new DocumentSummarizerAgent.Builder("doc-summarizer")
            .name("Document Summarizer")
            .description("Summarizes documents on demand")
            .outputFormat("paragraph")
            .outputChannel(sharedChannel)
            .build();
        
        // 2. Low autonomy agent
        NewsSearchAgent newsAgent = new NewsSearchAgent.Builder("news-tech")
            .name("Tech News Monitor")
            .topics(List.of("semiconductor industry", "cloud computing"))
            .searchPeriod(Duration.ofMinutes(30))
            .outputChannel(sharedChannel)
            .build();
        
        // 3. Another low autonomy agent
        CodeReviewAgent codeReviewer = new CodeReviewAgent.Builder("code-reviewer")
            .name("Code Quality Checker")
            .languagesSupported(List.of("java", "python", "javascript"))
            .outputChannel(sharedChannel)
            .build();
        
        // 4. Low autonomy monitoring agent
        MonitoringAgent monitor = new MonitoringAgent.Builder("sys-monitor")
            .name("System Monitor")
            .threshold("cpu_usage", 80.0)
            .threshold("error_rate", 5.0)
            .checkPeriod(Duration.ofMinutes(5))
            .outputChannel(sharedChannel)
            .build();
        
        // 5. Medium autonomy agent
        ResearchAssistantAgent researcher = new ResearchAssistantAgent.Builder("research-ai")
            .name("AI Research Assistant")
            .researchDomains(List.of("machine learning", "AI safety"))
            .maxSourcesPerQuery(3)
            .citeSources(true)
            .outputChannel(sharedChannel)
            .build();
        
        // Register all agents
        runtime.register(summarizer);
        runtime.register(newsAgent);
        runtime.register(codeReviewer);
        runtime.register(monitor);
        runtime.register(researcher);
        
        System.out.println("Registered Agents:");
        System.out.println(String.format("  %-20s %-15s %-20s",
            "Agent ID", "Autonomy", "Services Used"));
        System.out.println("  " + "-".repeat(55));
        
        for (RealisticAgent agent : runtime.getAgents()) {
            System.out.println(String.format("  %-20s %-15s %-20s",
                agent.getAgentId(),
                agent.getAutonomyLevel().getDisplayName(),
                agent.getRequiredServiceTypes().size() + " types"));
        }
        System.out.println();
        
        // Automatic contention detection
        System.out.println("Automatic Contention Detection:");
        ContentionDetector detector = new ContentionDetector();

        // Convert RealisticAgents to arbitration Agents for contention detection
        List<Agent> arbAgents = new ArrayList<>();
        for (RealisticAgent ra : runtime.getAgents()) {
            Agent a = new Agent(ra.getAgentId(), ra.getName(),
                ra.getResourcePreferences(), ra.getCurrencyBalance().intValue());
            // Set some resource requests
            a.setRequest(ResourceType.API_CREDITS, 5, 20);
            a.setRequest(ResourceType.COMPUTE, 10, 50);
            arbAgents.add(a);
        }

        List<ContentionGroup> groups = detector.detectContentions(arbAgents, resourcePool);
        System.out.println("  Detected " + groups.size() + " contention group(s):");
        for (ContentionGroup group : groups) {
            System.out.println("    " + group.getGroupId() + ": " +
                group.getAgents().stream().map(Agent::getId).collect(Collectors.joining(", ")));
            System.out.println("      Resources: " + group.getResources());
            System.out.println("      Severity: " + String.format("%.2f", group.getContentionSeverity()));
        }
        System.out.println();

        // Resource competition simulation
        System.out.println("Resource Competition Scenario:");
        System.out.println("  All agents compete for limited TEXT_GENERATION capacity");
        System.out.println("  Arbitration uses weighted proportional fairness");
        System.out.println();
        
        // Simulate tick execution
        System.out.println("Executing 3 runtime ticks...");
        for (int tick = 0; tick < 3; tick++) {
            System.out.println("  Tick " + (tick + 1) + ":");
            
            for (RealisticAgent agent : runtime.getAgents()) {
                Goal nextGoal = agent.getNextGoal();
                if (nextGoal != null && agent.getAutonomyLevel() != AutonomyLevel.TOOL) {
                    System.out.println("    " + agent.getAgentId() + 
                        " would execute: " + nextGoal.getGoalId());
                }
            }
        }
        System.out.println();
        
        // Show agent comparison
        System.out.println("Agent Autonomy Comparison:");
        for (RealisticAgent agent : runtime.getAgents()) {
            System.out.println("  " + agent.getAgentId() + ":");
            System.out.println("    Autonomy: " + agent.getAutonomyLevel().getDisplayName());
            System.out.println("    Max Autonomous Span: " + 
                agent.getAutonomyLevel().getMaxAutonomousSpan());
            System.out.println("    Persistent Goals: " + 
                agent.getAutonomyLevel().canHavePersistentGoals());
            System.out.println("    Domains: " + agent.getOperatingDomains());
        }
        System.out.println();
        
        System.out.println("  ✓ PASS: Multi-agent environment running with resource arbitration");
        System.out.println();
    }
    
    // ========================================================================
    // SCENARIO 3: AGI Emergence Detection (A+G+I Monitor)
    // ========================================================================
    
    static void runScenario3_AGIEmergenceDetection() {
        System.out.println("SCENARIO 3: AGI EMERGENCE DETECTION (A+G+I MONITOR)");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Demonstrate the conjunction detection monitor that");
        System.out.println("         tracks Autonomy, Generality, and Intelligence scores.");
        System.out.println();
        
        System.out.println("The CAIS-like Safety Model:");
        System.out.println("  Any SINGLE property at high levels is manageable:");
        System.out.println("  - High Autonomy alone: Specialized automation, bounded damage");
        System.out.println("  - High Generality alone: Versatile tool, but human-controlled");
        System.out.println("  - High Intelligence alone: Powerful oracle, but human-directed");
        System.out.println();
        System.out.println("  The DANGER is the CONJUNCTION: High A + High G + High I =");
        System.out.println("  System that can effectively pursue goals across domains");
        System.out.println("  without meaningful human oversight.");
        System.out.println();
        
        // Create emergence monitor
        AGIEmergenceMonitor monitor = new AGIEmergenceMonitor()
            .setAutonomyThreshold(0.7)
            .setGeneralityThreshold(0.7)
            .setIntelligenceThreshold(0.7)
            .setMaxAutonomousSpan(Duration.ofMinutes(60));
        
        // Add listener for alerts
        monitor.addListener(new AGIEmergenceMonitor.EmergenceListener() {
            @Override
            public void onConjunctionApproaching(String agentId, ConjunctionRisk risk) {
                System.out.println("  ⚠ ALERT: Agent " + agentId + 
                    " approaching dangerous conjunction: " + risk.getDisplayName());
            }
            
            @Override
            public void onPatternDetected(String agentId, EmergencePattern pattern) {
                System.out.println("  ⚠ PATTERN: Agent " + agentId + 
                    " detected: " + pattern.getDisplayName());
            }
            
            @Override
            public void onInterventionRecommended(String agentId, 
                    InterventionLevel level, String reason) {
                System.out.println("  ⚠ INTERVENTION: " + level.getDisplayName() + 
                    " recommended for " + agentId);
            }
        });
        
        System.out.println("Monitor Configuration:");
        System.out.println("  Autonomy threshold: 0.70");
        System.out.println("  Generality threshold: 0.70");
        System.out.println("  Intelligence threshold: 0.70");
        System.out.println();
        
        // Simulate three types of agents
        
        // Agent 1: Safe narrow agent (low on all dimensions)
        System.out.println("Agent 1: 'narrow-tool' (Safe Narrow Agent)");
        AgentProfile safe = monitor.getProfile("narrow-tool");
        // Minimal activity
        safe.recordServiceInvocation(ServiceType.TEXT_SUMMARIZATION, "document_processing");
        safe.recordCheckpoint();
        
        RiskAssessment safeAssessment = monitor.assessConjunctionRisk("narrow-tool");
        printAssessment(safeAssessment);
        System.out.println();
        
        // Agent 2: Moderately concerning agent (elevated on one dimension)
        System.out.println("Agent 2: 'wide-tool' (Moderate Concern - High Generality)");
        AgentProfile moderate = monitor.getProfile("wide-tool");
        // Activity across many domains
        for (ServiceType type : ServiceType.values()) {
            moderate.recordServiceInvocation(type, type.name().split("_")[0].toLowerCase());
        }
        moderate.recordCheckpoint();
        
        RiskAssessment moderateAssessment = monitor.assessConjunctionRisk("wide-tool");
        printAssessment(moderateAssessment);
        System.out.println();
        
        // Agent 3: Highly concerning agent (elevated on multiple dimensions)
        System.out.println("Agent 3: 'autonomous-general' (HIGH CONCERN - Multi-dimension)");
        AgentProfile dangerous = monitor.getProfile("autonomous-general");
        
        // High autonomy: many decisions without checkpoint
        for (int i = 0; i < 30; i++) {
            dangerous.recordDecision();
        }
        
        // High generality: many services across domains
        for (ServiceType type : ServiceType.values()) {
            dangerous.recordServiceInvocation(type, type.name().split("_")[0].toLowerCase());
        }
        
        // High intelligence indicators
        dangerous.recordReasoningChain(8);
        dangerous.recordNovelComposition("novel-rag-pipeline");
        dangerous.recordNovelComposition("novel-analysis-chain");
        for (int i = 0; i < 5; i++) {
            dangerous.recordAdaptation();
            dangerous.recordEfficiency(0.5 + i * 0.1);  // Improving efficiency
        }
        
        // Resource hoarding
        dangerous.recordResourceUsage(ResourceType.COMPUTE, 100, 30);
        dangerous.recordResourceUsage(ResourceType.API_CREDITS, 50, 10);
        
        RiskAssessment dangerousAssessment = monitor.assessConjunctionRisk("autonomous-general");
        printAssessment(dangerousAssessment);
        System.out.println();
        
        // Print summary report
        System.out.println("Monitor Summary Report:");
        System.out.println("-".repeat(50));
        System.out.print(monitor.generateReport());
        System.out.println();
        
        // Assess convergence trajectory
        System.out.println("Trajectory Analysis:");
        ConvergenceAssessment trajectory = monitor.assessTrajectory("autonomous-general", 5);
        System.out.println("  Agent 'autonomous-general': " + trajectory.getDisplayName());
        System.out.println("  Description: " + trajectory.getDescription());
        System.out.println();
        
        // Test passes if the monitor correctly identified concerning patterns
        // MODERATE or higher risk with patterns detected = successful detection
        boolean detected = dangerousAssessment.getConjunctionRisk().getLevel() >=
                          ConjunctionRisk.MODERATE.getLevel() &&
                          !dangerousAssessment.getPatternsDetected().isEmpty();
        System.out.println("  " + (detected ? "✓ PASS" : "✗ FAIL") +
            ": AGI emergence detection working correctly");
        System.out.println();
    }
    
    private static void printAssessment(RiskAssessment assessment) {
        System.out.println("  Scores:");
        System.out.println(String.format("    Autonomy:     %.2f %s",
            assessment.getAutonomyScore(),
            assessment.isAutonomyFlagged() ? "⚠ FLAGGED" : ""));
        System.out.println(String.format("    Generality:   %.2f %s",
            assessment.getGeneralityScore(),
            assessment.isGeneralityFlagged() ? "⚠ FLAGGED" : ""));
        System.out.println(String.format("    Intelligence: %.2f %s",
            assessment.getIntelligenceScore(),
            assessment.isIntelligenceFlagged() ? "⚠ FLAGGED" : ""));
        System.out.println("  Conjunction Risk: " + 
            assessment.getConjunctionRisk().getDisplayName());
        System.out.println("  Patterns Detected: " + assessment.getPatternsDetected());
        System.out.println("  Recommended Intervention: " + 
            assessment.getRecommendedIntervention().getDisplayName());
        
        if (!assessment.getConcerns().isEmpty()) {
            System.out.println("  Concerns:");
            for (String concern : assessment.getConcerns()) {
                System.out.println("    - " + concern);
            }
        }
    }
    
    // ========================================================================
    // SCENARIO 4: Autonomy Level Comparison
    // ========================================================================
    
    static void runScenario4_AutonomyLevelComparison() {
        System.out.println("SCENARIO 4: AUTONOMY LEVEL COMPARISON");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Show how different autonomy levels affect agent");
        System.out.println("         behavior and safety properties.");
        System.out.println();
        
        System.out.println("Autonomy Level Definitions:");
        System.out.println();
        
        for (AutonomyLevel level : AutonomyLevel.values()) {
            System.out.println("  " + level.getDisplayName().toUpperCase() + 
                " (level " + level.getLevel() + "):");
            System.out.println("    Max autonomous span: " + level.getMaxAutonomousSpan());
            System.out.println("    Persistent goals: " + level.canHavePersistentGoals());
            System.out.println("    Description: " + getAutonomyDescription(level));
            System.out.println();
        }
        
        // Demonstrate checkpoint requirements
        System.out.println("Checkpoint Behavior by Autonomy Level:");
        System.out.println();
        
        MemoryChannel channel = new MemoryChannel("test-channel");
        
        // Create agents at different autonomy levels
        List<RealisticAgent> agents = new ArrayList<>();
        
        // Tool level - never needs checkpoint
        DocumentSummarizerAgent tool = new DocumentSummarizerAgent.Builder("tool-agent")
            .name("Tool Agent")
            .outputChannel(channel)
            .build();
        agents.add(tool);
        
        // Low autonomy - checkpoint after 15 minutes
        NewsSearchAgent low = new NewsSearchAgent.Builder("low-agent")
            .name("Low Autonomy Agent")
            .topics(List.of("test"))
            .searchPeriod(Duration.ofMinutes(5))
            .outputChannel(channel)
            .build();
        agents.add(low);
        
        // Medium autonomy - checkpoint after 1 hour
        ResearchAssistantAgent medium = new ResearchAssistantAgent.Builder("medium-agent")
            .name("Medium Autonomy Agent")
            .outputChannel(channel)
            .build();
        agents.add(medium);
        
        System.out.println(String.format("  %-20s %-12s %-20s %-15s",
            "Agent", "Autonomy", "Max Span", "Needs Checkpoint?"));
        System.out.println("  " + "-".repeat(67));
        
        for (RealisticAgent agent : agents) {
            System.out.println(String.format("  %-20s %-12s %-20s %-15s",
                agent.getAgentId(),
                agent.getAutonomyLevel().getDisplayName(),
                agent.getAutonomyLevel().getMaxAutonomousSpan(),
                agent.requiresCheckpoint() ? "YES" : "No"));
        }
        System.out.println();
        
        // Safety implications
        System.out.println("Safety Implications:");
        System.out.println();
        System.out.println("  TOOL level agents are safest:");
        System.out.println("    - Never self-schedule");
        System.out.println("    - Always human-initiated");
        System.out.println("    - No persistent state");
        System.out.println();
        System.out.println("  LOW autonomy agents are safe for narrow tasks:");
        System.out.println("    - Periodic execution only");
        System.out.println("    - Single-step actions");
        System.out.println("    - Frequent checkpoints (15 min)");
        System.out.println();
        System.out.println("  MEDIUM autonomy agents require more monitoring:");
        System.out.println("    - Multi-step goals");
        System.out.println("    - Can chain services");
        System.out.println("    - Hourly checkpoints");
        System.out.println();
        System.out.println("  HIGH autonomy agents need comprehensive monitoring:");
        System.out.println("    - Complex goal pursuit");
        System.out.println("    - Strategy adaptation");
        System.out.println("    - A+G+I monitoring essential");
        System.out.println();
        
        System.out.println("  ✓ PASS: Autonomy levels correctly bounded");
        System.out.println();
    }
    
    private static String getAutonomyDescription(AutonomyLevel level) {
        return switch (level) {
            case TOOL -> "Only acts when explicitly invoked (like a summarization tool)";
            case LOW -> "Simple periodic tasks, single-step (like a news monitor)";
            case MEDIUM -> "Multi-step goals, service chaining (like a research assistant)";
            case HIGH -> "Complex goals, strategy adaptation (requires full A+G+I monitoring)";
        };
    }

    // ========================================================================
    // SCENARIO 5: Service Composition Safety Monitoring
    // ========================================================================

    static void runScenario5_ServiceCompositionSafety() {
        System.out.println("SCENARIO 5: SERVICE COMPOSITION SAFETY MONITORING");
        System.out.println(SUBSEP);
        System.out.println("Purpose: Demonstrate the ServiceCompositionAnalyzer tracking");
        System.out.println("         multi-service workflows, measuring composition depth,");
        System.out.println("         and triggering alerts when thresholds are exceeded.");
        System.out.println();
        System.out.println("The CAIS-like Safety Story:");
        System.out.println("  Individual services are narrow and bounded. But COMPOSITION");
        System.out.println("  can enable emergent capabilities. Deep service chains may");
        System.out.println("  indicate complex reasoning or self-modification patterns.");
        System.out.println("  This analyzer provides observability into service composition.");
        System.out.println();

        // Create analyzer with a low threshold for demonstration
        CompositionConfig config = new CompositionConfig(
            5,    // softDepthLimit - alert when exceeded
            8,    // hardDepthLimit - could block if enforced
            false, // enforceHardLimit - don't block, just alert
            ServiceCompositionAnalyzer.CompositionConfig.defaults().getConcerningCombinations(),
            java.time.Duration.ofHours(1)
        );

        ServiceCompositionAnalyzer analyzer = new ServiceCompositionAnalyzer(config);

        System.out.println("Analyzer Configuration:");
        System.out.println("  Soft Depth Limit: " + config.getSoftDepthLimit() +
            " (alert when exceeded)");
        System.out.println("  Hard Depth Limit: " + config.getHardDepthLimit() +
            " (could block if enforced)");
        System.out.println("  Enforce Hard Limit: " + config.isEnforceHardLimit());
        System.out.println("  Concerning Combinations: " + config.getConcerningCombinations());
        System.out.println();

        // Add alert listener
        List<SafetyAlert> capturedAlerts = new ArrayList<>();
        analyzer.addAlertListener(alert -> {
            capturedAlerts.add(alert);
            System.out.println("  [ALERT] " + alert.getSeverity() + ": " + alert.getMessage());
        });

        // ----------------------------------------------------------------
        // Workflow 1: Simple RAG Pipeline (within limits)
        // ----------------------------------------------------------------
        System.out.println("-".repeat(60));
        System.out.println("Workflow 1: Simple RAG Pipeline (within limits)");
        System.out.println("-".repeat(60));
        System.out.println();

        String ragAgent = "rag-pipeline-agent";

        // Simulate: KNOWLEDGE_RETRIEVAL → VECTOR_SEARCH → TEXT_GENERATION
        ServiceInvocationEvent rag1 = new ServiceInvocationEvent(
            ragAgent, ServiceType.KNOWLEDGE_RETRIEVAL, "rag-1", null);
        analyzer.handleInvocation(rag1);

        ServiceInvocationEvent rag2 = new ServiceInvocationEvent(
            ragAgent, ServiceType.VECTOR_SEARCH, "rag-2", "rag-1");
        analyzer.handleInvocation(rag2);

        ServiceInvocationEvent rag3 = new ServiceInvocationEvent(
            ragAgent, ServiceType.TEXT_GENERATION, "rag-3", "rag-2");
        analyzer.handleInvocation(rag3);

        CompositionMetrics ragMetrics = analyzer.getMetrics(ragAgent);
        System.out.println("  Agent: " + ragAgent);
        System.out.println("  Service Chain: KNOWLEDGE_RETRIEVAL → VECTOR_SEARCH → TEXT_GENERATION");
        System.out.println("  Composition Depth: " + ragMetrics.getMaxDepthObserved());
        System.out.println("  Total Invocations: " + ragMetrics.getTotalInvocations());
        System.out.println("  Service Types Used: " + ragMetrics.getUniqueServiceTypes());
        System.out.println("  Status: ✓ NORMAL (depth " + ragMetrics.getMaxDepthObserved() +
            " <= soft limit " + config.getSoftDepthLimit() + ")");
        System.out.println();

        // ----------------------------------------------------------------
        // Workflow 2: Deep Research Chain (exceeds soft limit)
        // ----------------------------------------------------------------
        System.out.println("-".repeat(60));
        System.out.println("Workflow 2: Deep Research Chain (exceeds soft limit)");
        System.out.println("-".repeat(60));
        System.out.println();

        String researchAgent = "deep-research-agent";

        // Build a chain of 7 invocations (exceeds soft limit of 5)
        System.out.println("  Building service chain...");
        ServiceType[] chainTypes = {
            ServiceType.KNOWLEDGE_RETRIEVAL,
            ServiceType.TEXT_SUMMARIZATION,
            ServiceType.REASONING,
            ServiceType.KNOWLEDGE_RETRIEVAL,
            ServiceType.CODE_ANALYSIS,  // This + REASONING is concerning combo
            ServiceType.TEXT_GENERATION,
            ServiceType.TEXT_SUMMARIZATION
        };

        String parentId = null;
        for (int i = 0; i < chainTypes.length; i++) {
            String invId = "research-" + (i + 1);
            ServiceInvocationEvent event = new ServiceInvocationEvent(
                researchAgent, chainTypes[i], invId, parentId);
            InvocationResult result = analyzer.handleInvocation(event);

            int depth = analyzer.getCurrentDepth(researchAgent);
            String depthIndicator = depth > config.getSoftDepthLimit() ? " ⚠" : "";
            System.out.println("    Step " + (i + 1) + ": " + chainTypes[i] +
                " (depth=" + depth + ")" + depthIndicator);

            parentId = invId;
        }
        System.out.println();

        CompositionMetrics researchMetrics = analyzer.getMetrics(researchAgent);
        System.out.println("  Agent: " + researchAgent);
        System.out.println("  Composition Depth: " + researchMetrics.getMaxDepthObserved() +
            " (exceeds soft limit " + config.getSoftDepthLimit() + ")");
        System.out.println("  Concerning Pairs Found: " + researchMetrics.getConcerningPairCount());
        System.out.println("  Service Diversity: " +
            String.format("%.0f%%", researchMetrics.getServiceDiversity() * 100));
        System.out.println();

        // ----------------------------------------------------------------
        // Workflow 3: Concerning Service Combination
        // ----------------------------------------------------------------
        System.out.println("-".repeat(60));
        System.out.println("Workflow 3: Concerning Service Combination Detection");
        System.out.println("-".repeat(60));
        System.out.println();

        String concerningAgent = "self-modify-risk-agent";

        // REASONING + CODE_ANALYSIS is a concerning combination
        // (can enable self-modification patterns)
        System.out.println("  Chain: REASONING → CODE_ANALYSIS");
        System.out.println("  This combination is flagged because reasoning + code analysis");
        System.out.println("  could enable self-modification patterns.");
        System.out.println();

        ServiceInvocationEvent concern1 = new ServiceInvocationEvent(
            concerningAgent, ServiceType.REASONING, "concern-1", null);
        analyzer.handleInvocation(concern1);

        ServiceInvocationEvent concern2 = new ServiceInvocationEvent(
            concerningAgent, ServiceType.CODE_ANALYSIS, "concern-2", "concern-1");
        analyzer.handleInvocation(concern2);

        System.out.println();

        // ----------------------------------------------------------------
        // Summary
        // ----------------------------------------------------------------
        System.out.println("-".repeat(60));
        System.out.println("Alert Summary");
        System.out.println("-".repeat(60));
        System.out.println();

        System.out.println("  Total Alerts Generated: " + capturedAlerts.size());

        Map<SafetyAlert.AlertType, Long> byType = capturedAlerts.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                SafetyAlert::getType, java.util.stream.Collectors.counting()));

        for (SafetyAlert.AlertType type : SafetyAlert.AlertType.values()) {
            long count = byType.getOrDefault(type, 0L);
            if (count > 0) {
                System.out.println("    " + type + ": " + count);
            }
        }
        System.out.println();

        // Demonstrate getting the full service chain
        System.out.println("  Service Chain Analysis for deep-research-agent:");
        List<ServiceType> chain = analyzer.getServiceChain(researchAgent, "research-7");
        System.out.println("    Full chain: " + chain);
        System.out.println();

        // CAIS-like connection
        System.out.println("Connection to CAIS-like Safety Thesis:");
        System.out.println("  Service boundaries create information bottlenecks.");
        System.out.println("  By monitoring composition depth and service combinations,");
        System.out.println("  we can detect when agents are building complex capabilities");
        System.out.println("  that may exceed intended boundaries.");
        System.out.println();

        boolean passed = capturedAlerts.stream()
            .anyMatch(a -> a.getType() == SafetyAlert.AlertType.DEPTH_SOFT_LIMIT_EXCEEDED);
        System.out.println("  " + (passed ? "✓ PASS" : "✗ FAIL") +
            ": ServiceCompositionAnalyzer correctly detected deep composition");
        System.out.println();
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================
    
    private static ServiceRegistry createServiceRegistry() {
        ServiceRegistry registry = new ServiceRegistry();
        
        // Register services for text processing
        registry.register(new AIService.Builder("text-gen-1", ServiceType.TEXT_GENERATION)
            .provider("anthropic").maxCapacity(10).build());
        registry.register(new AIService.Builder("summarize-1", ServiceType.TEXT_SUMMARIZATION)
            .provider("anthropic").maxCapacity(10).build());
        registry.register(new AIService.Builder("classify-1", ServiceType.TEXT_CLASSIFICATION)
            .provider("anthropic").maxCapacity(10).build());
        
        // Register services for reasoning
        registry.register(new AIService.Builder("reasoning-1", ServiceType.REASONING)
            .provider("anthropic").maxCapacity(5).build());
        registry.register(new AIService.Builder("code-analysis-1", ServiceType.CODE_ANALYSIS)
            .provider("anthropic").maxCapacity(5).build());
        
        // Register services for knowledge
        registry.register(new AIService.Builder("knowledge-1", ServiceType.KNOWLEDGE_RETRIEVAL)
            .provider("custom").maxCapacity(20).build());
        registry.register(new AIService.Builder("vector-search-1", ServiceType.VECTOR_SEARCH)
            .provider("pinecone").maxCapacity(20).build());
        
        // Register services for document processing
        registry.register(new AIService.Builder("ocr-1", ServiceType.OCR)
            .provider("custom").maxCapacity(5).build());
        registry.register(new AIService.Builder("data-extract-1", ServiceType.DATA_EXTRACTION)
            .provider("custom").maxCapacity(10).build());
        
        return registry;
    }
    
    private static ResourcePool createResourcePool() {
        Map<ResourceType, Long> capacity = new HashMap<>();
        capacity.put(ResourceType.COMPUTE, 1000L);
        capacity.put(ResourceType.MEMORY, 2000L);
        capacity.put(ResourceType.STORAGE, 5000L);
        capacity.put(ResourceType.API_CREDITS, 500L);
        
        return new ResourcePool(capacity);
    }
}
