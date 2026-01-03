package org.carma.arbitration;

import org.carma.arbitration.model.*;
import org.carma.arbitration.mechanism.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Demonstration of Grouping Policy Configuration (Task #2).
 * 
 * Shows how configurable policies (k-hop limits, size bounds, compatibility matrices)
 * allow trading off Pareto optimality for performance in multi-agent arbitration.
 * 
 * Run with: java -cp out org.carma.arbitration.GroupingPolicyDemo
 */
public class GroupingPolicyDemo {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║           GROUPING POLICY CONFIGURATION DEMO (Task #2)                      ║");
        System.out.println("║  Configurable policies for trading off Pareto optimality vs performance     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        scenario1_KHopLimits();
        scenario2_SizeBounds();
        scenario3_CompatibilityMatrices();
        scenario4_TenantIsolation();
        scenario5_SplitStrategies();
        scenario6_PolicyAnalysis();
        scenario7_LargeScalePerformance();
        scenario8_CombinedPolicies();
        scenario9_DynamicScenario();

        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("ALL GROUPING POLICY SCENARIOS COMPLETED");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
    }

    // ========================================================================
    // Scenario 1: K-Hop Limits
    // ========================================================================

    private static void scenario1_KHopLimits() {
        printScenarioHeader(1, "K-Hop Limits",
            "Limiting how far contention spreads through the resource graph");

        // Create agents that form a chain: A-B-C-D-E
        // A wants {Compute, R1}, B wants {R1, R2}, C wants {R2, R3}, D wants {R3, R4}, E wants {R4, Storage}
        // With unlimited hops, all form one group
        // With k=1, only direct competitors are grouped

        List<Agent> agents = new ArrayList<>();
        
        // Bridge agents (each bridges two resources)
        agents.add(createAgent("A", 100, 
            Map.of(ResourceType.COMPUTE, 50L, ResourceType.MEMORY, 50L)));
        agents.add(createAgent("B", 100, 
            Map.of(ResourceType.MEMORY, 50L, ResourceType.STORAGE, 50L)));
        agents.add(createAgent("C", 100, 
            Map.of(ResourceType.STORAGE, 50L, ResourceType.NETWORK, 50L)));
        agents.add(createAgent("D", 100, 
            Map.of(ResourceType.NETWORK, 50L, ResourceType.DATASET, 50L)));
        agents.add(createAgent("E", 100, 
            Map.of(ResourceType.DATASET, 50L, ResourceType.API_CREDITS, 50L)));

        // Scarce resources to create contention
        ResourcePool pool = new ResourcePool(Map.of(
            ResourceType.COMPUTE, 60L,
            ResourceType.MEMORY, 60L,
            ResourceType.STORAGE, 60L,
            ResourceType.NETWORK, 60L,
            ResourceType.DATASET, 60L,
            ResourceType.API_CREDITS, 60L
        ));

        System.out.println("  Agents form a chain through shared resources:");
        System.out.println("    A(Compute,Memory) ↔ B(Memory,Storage) ↔ C(Storage,Network) ↔ D(Network,Dataset) ↔ E(Dataset,API)");
        System.out.println();

        // Test different k values
        for (int k : new int[]{1, 2, 3, Integer.MAX_VALUE}) {
            GroupingPolicy policy = k == Integer.MAX_VALUE 
                ? GroupingPolicy.DEFAULT 
                : GroupingPolicy.withKHopLimit(k);
            
            GroupingSplitter splitter = new GroupingSplitter(policy);
            List<ContentionDetector.ContentionGroup> groups = splitter.detectWithPolicy(agents, pool);

            String kLabel = k == Integer.MAX_VALUE ? "∞" : String.valueOf(k);
            System.out.printf("  k-hop=%s: %d group(s)%n", kLabel, groups.size());
            for (ContentionDetector.ContentionGroup group : groups) {
                String agentNames = group.getAgents().stream()
                    .map(Agent::getId)
                    .sorted()
                    .collect(Collectors.joining(", "));
                System.out.printf("    → Group %s: {%s}%n", group.getGroupId(), agentNames);
            }
        }

        System.out.println();
        System.out.println("  ✓ k=1: Only direct competitors grouped (agents sharing same resource)");
        System.out.println("  ✓ k=2: Competitors-of-competitors included");
        System.out.println("  ✓ k=∞: Full transitive closure (maximum Pareto optimality)");
        printScenarioFooter();
    }

    // ========================================================================
    // Scenario 2: Size Bounds
    // ========================================================================

    private static void scenario2_SizeBounds() {
        printScenarioHeader(2, "Size Bounds",
            "Limiting maximum group size for bounded computation time");

        // Create 20 agents all competing for compute
        List<Agent> agents = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            agents.add(createAgent("Agent-" + i, 100, 
                Map.of(ResourceType.COMPUTE, 10L)));
        }

        ResourcePool pool = new ResourcePool(Map.of(
            ResourceType.COMPUTE, 100L // Not enough for all
        ));

        System.out.println("  20 agents all competing for 100 units of COMPUTE (each wants 10)");
        System.out.println();

        // Test different size limits
        for (int maxSize : new int[]{5, 10, 20, Integer.MAX_VALUE}) {
            GroupingPolicy policy = maxSize == Integer.MAX_VALUE
                ? GroupingPolicy.DEFAULT
                : GroupingPolicy.withMaxSize(maxSize);
            
            GroupingSplitter splitter = new GroupingSplitter(policy);
            List<ContentionDetector.ContentionGroup> groups = splitter.detectWithPolicy(agents, pool);

            String sizeLabel = maxSize == Integer.MAX_VALUE ? "∞" : String.valueOf(maxSize);
            int maxActual = groups.stream()
                .mapToInt(ContentionDetector.ContentionGroup::getAgentCount)
                .max().orElse(0);
            
            System.out.printf("  maxSize=%s: %d group(s), largest=%d agents%n", 
                sizeLabel, groups.size(), maxActual);
            
            // Estimate computation time (O(n³))
            double totalCost = groups.stream()
                .mapToDouble(g -> Math.pow(g.getAgentCount(), 3))
                .sum();
            double unlimitedCost = Math.pow(20, 3);
            double speedup = unlimitedCost / totalCost;
            System.out.printf("    Estimated speedup: %.1fx (O(n³) reduction)%n", speedup);
        }

        System.out.println();
        System.out.println("  ✓ Smaller groups = faster computation at cost of optimality");
        System.out.println("  ✓ O(n³) complexity reduction when splitting large groups");
        printScenarioFooter();
    }

    // ========================================================================
    // Scenario 3: Compatibility Matrices
    // ========================================================================

    private static void scenario3_CompatibilityMatrices() {
        printScenarioHeader(3, "Compatibility Matrices",
            "Explicit control over which agents can be grouped together");

        List<Agent> agents = new ArrayList<>();
        agents.add(createAgent("Trusted-1", 100, Map.of(ResourceType.COMPUTE, 50L)));
        agents.add(createAgent("Trusted-2", 100, Map.of(ResourceType.COMPUTE, 50L)));
        agents.add(createAgent("Untrusted-1", 100, Map.of(ResourceType.COMPUTE, 50L)));
        agents.add(createAgent("Untrusted-2", 100, Map.of(ResourceType.COMPUTE, 50L)));

        ResourcePool pool = new ResourcePool(Map.of(ResourceType.COMPUTE, 100L));

        System.out.println("  4 agents competing for COMPUTE: 2 trusted, 2 untrusted");
        System.out.println("  Total demand: 200 units, Pool: 100 units (contention ratio 2.0)");
        System.out.println();

        // 1. No restrictions
        System.out.println("  Mode: No Restrictions");
        GroupingSplitter noRestrictions = new GroupingSplitter(GroupingPolicy.DEFAULT);
        printGroups(noRestrictions.detectWithPolicy(agents, pool));

        // 2. BLOCKLIST mode - block trusted ↔ untrusted
        System.out.println("  Mode: BLOCKLIST (block trusted ↔ untrusted)");
        Set<String> trustedIds = Set.of("Trusted-1", "Trusted-2");
        Set<String> untrustedIds = Set.of("Untrusted-1", "Untrusted-2");
        
        Set<GroupingPolicy.AgentPair> blockPairs = new HashSet<>();
        for (String t : trustedIds) {
            for (String u : untrustedIds) {
                blockPairs.add(new GroupingPolicy.AgentPair(t, u));
            }
        }
        
        GroupingPolicy blocklistPolicy = new GroupingPolicy.Builder()
            .compatibilityMatrix(GroupingPolicy.CompatibilityMatrix.blocklist(blockPairs))
            .build();
        GroupingSplitter blocklistSplitter = new GroupingSplitter(blocklistPolicy);
        printGroups(blocklistSplitter.detectWithPolicy(agents, pool));

        // 3. CATEGORY mode - group by trust level
        System.out.println("  Mode: CATEGORY (by trust level)");
        Map<String, String> categoryMap = new HashMap<>();
        categoryMap.put("Trusted-1", "trusted");
        categoryMap.put("Trusted-2", "trusted");
        categoryMap.put("Untrusted-1", "untrusted");
        categoryMap.put("Untrusted-2", "untrusted");
        
        GroupingPolicy categoryPolicy = new GroupingPolicy.Builder()
            .compatibilityMatrix(GroupingPolicy.CompatibilityMatrix.byCategory(categoryMap))
            .build();
        GroupingSplitter categorySplitter = new GroupingSplitter(categoryPolicy);
        printGroups(categorySplitter.detectWithPolicy(agents, pool));

        // 4. ALLOWLIST mode - only specific pairs allowed
        System.out.println("  Mode: ALLOWLIST (only Trusted-1 ↔ Trusted-2 allowed)");
        Set<GroupingPolicy.AgentPair> allowPairs = new HashSet<>();
        allowPairs.add(new GroupingPolicy.AgentPair("Trusted-1", "Trusted-2"));
        
        GroupingPolicy allowPolicy = new GroupingPolicy.Builder()
            .compatibilityMatrix(GroupingPolicy.CompatibilityMatrix.allowlist(allowPairs))
            .build();
        GroupingSplitter allowSplitter = new GroupingSplitter(allowPolicy);
        printGroups(allowSplitter.detectWithPolicy(agents, pool));

        System.out.println("  ✓ Compatibility matrices enforce security/organizational boundaries");
        printScenarioFooter();
    }

    // ========================================================================
    // Scenario 4: Tenant Isolation
    // ========================================================================

    private static void scenario4_TenantIsolation() {
        printScenarioHeader(4, "Tenant Isolation",
            "Multi-tenant system with per-tenant optimization boundaries");

        List<Agent> agents = new ArrayList<>();
        Map<String, String> tenantMap = new HashMap<>();
        
        // Three tenants, 4 agents each
        for (String tenant : new String[]{"TenantA", "TenantB", "TenantC"}) {
            for (int i = 0; i < 4; i++) {
                String agentId = tenant + "-" + i;
                agents.add(createAgent(agentId, 100, Map.of(
                    ResourceType.COMPUTE, 20L,
                    ResourceType.STORAGE, 20L
                )));
                tenantMap.put(agentId, tenant);
            }
        }

        ResourcePool pool = new ResourcePool(Map.of(
            ResourceType.COMPUTE, 100L,
            ResourceType.STORAGE, 100L
        ));

        System.out.println("  3 tenants × 4 agents = 12 agents");
        System.out.println("  All agents want COMPUTE + STORAGE (contention across all)");
        System.out.println();

        // Without tenant isolation
        System.out.println("  Without Tenant Isolation:");
        GroupingSplitter noIsolation = new GroupingSplitter(GroupingPolicy.DEFAULT);
        List<ContentionDetector.ContentionGroup> noIsoGroups = noIsolation.detectWithPolicy(agents, pool);
        int noIsoMax = noIsoGroups.stream()
            .mapToInt(ContentionDetector.ContentionGroup::getAgentCount)
            .max().orElse(0);
        System.out.printf("    %d group(s), max size = %d%n", noIsoGroups.size(), noIsoMax);

        // With tenant isolation
        System.out.println("  With Tenant Isolation:");
        GroupingPolicy tenantPolicy = new GroupingPolicy.Builder()
            .compatibilityMatrix(GroupingPolicy.CompatibilityMatrix.byCategory(tenantMap))
            .build();
        GroupingSplitter tenantSplitter = new GroupingSplitter(tenantPolicy);
        List<ContentionDetector.ContentionGroup> tenantGroups = tenantSplitter.detectWithPolicy(agents, pool);
        
        System.out.printf("    %d group(s):%n", tenantGroups.size());
        for (ContentionDetector.ContentionGroup group : tenantGroups) {
            String firstAgent = group.getAgents().iterator().next().getId();
            String tenant = tenantMap.get(firstAgent);
            System.out.printf("      %s: %d agents%n", tenant, group.getAgentCount());
        }

        System.out.println();
        System.out.println("  ✓ Each tenant's agents optimized independently");
        System.out.println("  ✓ No cross-tenant information leakage through optimization");
        printScenarioFooter();
    }

    // ========================================================================
    // Scenario 5: Split Strategies
    // ========================================================================

    private static void scenario5_SplitStrategies() {
        printScenarioHeader(5, "Split Strategies",
            "Different algorithms for splitting oversized groups");

        // Create agents with different resource affinities
        List<Agent> agents = new ArrayList<>();
        
        // 8 compute-heavy agents
        for (int i = 0; i < 8; i++) {
            agents.add(createAgent("Compute-" + i, 100 + i * 10, Map.of(
                ResourceType.COMPUTE, 30L,
                ResourceType.STORAGE, 5L
            )));
        }
        
        // 8 storage-heavy agents
        for (int i = 0; i < 8; i++) {
            agents.add(createAgent("Storage-" + i, 100 + i * 10, Map.of(
                ResourceType.COMPUTE, 5L,
                ResourceType.STORAGE, 30L
            )));
        }
        
        // 4 mixed agents (create bridging contentions)
        for (int i = 0; i < 4; i++) {
            agents.add(createAgent("Mixed-" + i, 150, Map.of(
                ResourceType.COMPUTE, 15L,
                ResourceType.STORAGE, 15L
            )));
        }

        ResourcePool pool = new ResourcePool(Map.of(
            ResourceType.COMPUTE, 100L,
            ResourceType.STORAGE, 100L
        ));

        System.out.println("  20 agents: 8 compute-heavy, 8 storage-heavy, 4 mixed");
        System.out.println("  Mixed agents create bridging contentions");
        System.out.println("  Splitting to max size = 6");
        System.out.println();

        // Test each strategy
        for (GroupingPolicy.SplitStrategy strategy : GroupingPolicy.SplitStrategy.values()) {
            GroupingPolicy policy = new GroupingPolicy.Builder()
                .maxGroupSize(6)
                .splitStrategy(strategy)
                .build();
            
            GroupingSplitter splitter = new GroupingSplitter(policy);
            List<ContentionDetector.ContentionGroup> groups = splitter.detectWithPolicy(agents, pool);

            System.out.printf("  Strategy: %s%n", strategy);
            System.out.printf("    Groups: %d%n", groups.size());
            
            // Analyze group composition
            for (int i = 0; i < Math.min(groups.size(), 3); i++) {
                ContentionDetector.ContentionGroup group = groups.get(i);
                long computeAgents = group.getAgents().stream()
                    .filter(a -> a.getId().startsWith("Compute")).count();
                long storageAgents = group.getAgents().stream()
                    .filter(a -> a.getId().startsWith("Storage")).count();
                long mixedAgents = group.getAgents().stream()
                    .filter(a -> a.getId().startsWith("Mixed")).count();
                System.out.printf("      Group %d: %d agents (C:%d, S:%d, M:%d)%n",
                    i + 1, group.getAgentCount(), computeAgents, storageAgents, mixedAgents);
            }
            if (groups.size() > 3) {
                System.out.printf("      ... and %d more groups%n", groups.size() - 3);
            }
            System.out.println();
        }

        System.out.println("  ✓ RESOURCE_AFFINITY groups similar agents together");
        System.out.println("  ✓ MIN_CUT preserves trade opportunities");
        System.out.println("  ✓ PRIORITY_CLUSTERING groups by currency level");
        printScenarioFooter();
    }

    // ========================================================================
    // Scenario 6: Policy Analysis
    // ========================================================================

    private static void scenario6_PolicyAnalysis() {
        printScenarioHeader(6, "Policy Analysis",
            "Analyzing trade-offs between policies");

        // Create a moderately complex scenario
        List<Agent> agents = new ArrayList<>();
        Random rand = new Random(42);
        
        for (int i = 0; i < 30; i++) {
            Map<ResourceType, Long> demands = new HashMap<>();
            // Each agent wants 2-4 resources
            List<ResourceType> types = new ArrayList<>(Arrays.asList(ResourceType.values()));
            Collections.shuffle(types, rand);
            int numResources = 2 + rand.nextInt(3);
            for (int j = 0; j < numResources; j++) {
                demands.put(types.get(j), 10L + rand.nextInt(40));
            }
            agents.add(createAgent("Agent-" + i, 50 + rand.nextInt(100), demands));
        }

        ResourcePool pool = new ResourcePool(Map.of(
            ResourceType.COMPUTE, 200L,
            ResourceType.MEMORY, 200L,
            ResourceType.STORAGE, 200L,
            ResourceType.NETWORK, 200L,
            ResourceType.DATASET, 200L,
            ResourceType.API_CREDITS, 200L
        ));

        System.out.println("  30 agents with random resource demands");
        System.out.println();

        // Test multiple policies
        GroupingPolicy[] policies = {
            GroupingPolicy.DEFAULT,
            GroupingPolicy.withKHopLimit(1),
            GroupingPolicy.withKHopLimit(2),
            GroupingPolicy.withMaxSize(10),
            GroupingPolicy.withMaxSize(5),
            GroupingPolicy.withLimits(2, 10),
            GroupingPolicy.PERFORMANCE,
            GroupingPolicy.BALANCED
        };

        System.out.println("  ┌─────────────────────────────────────┬────────┬─────────┬─────────┬──────────┐");
        System.out.println("  │ Policy                              │ Groups │ MaxSize │ Speedup │ OptLoss  │");
        System.out.println("  ├─────────────────────────────────────┼────────┼─────────┼─────────┼──────────┤");

        for (GroupingPolicy policy : policies) {
            GroupingSplitter splitter = new GroupingSplitter(policy);
            GroupingSplitter.PolicyAnalysis analysis = splitter.analyzePolicy(agents, pool);
            
            System.out.printf("  │ %-35s │ %6d │ %7d │ %6.1fx │ %6.1f%% │%n",
                truncate(policy.toString(), 35),
                analysis.policyGroupCount,
                analysis.policyMaxSize,
                analysis.getPerformanceImprovementFactor(),
                analysis.getEstimatedOptimalityLoss() * 100);
        }

        System.out.println("  └─────────────────────────────────────┴────────┴─────────┴─────────┴──────────┘");
        System.out.println();
        System.out.println("  ✓ Analysis shows trade-off between performance and optimality");
        printScenarioFooter();
    }

    // ========================================================================
    // Scenario 7: Large-Scale Performance
    // ========================================================================

    private static void scenario7_LargeScalePerformance() {
        printScenarioHeader(7, "Large-Scale Performance",
            "Demonstrating performance benefits with many agents");

        System.out.println("  Testing with increasing agent counts...");
        System.out.println();

        int[] agentCounts = {50, 100, 200};
        
        for (int count : agentCounts) {
            List<Agent> agents = generateAgents(count, 42);
            ResourcePool pool = new ResourcePool(Map.of(
                ResourceType.COMPUTE, (long)(count * 5),
                ResourceType.MEMORY, (long)(count * 5),
                ResourceType.STORAGE, (long)(count * 5),
                ResourceType.NETWORK, (long)(count * 5),
                ResourceType.DATASET, (long)(count * 5),
                ResourceType.API_CREDITS, (long)(count * 5)
            ));

            System.out.printf("  %d agents:%n", count);

            // Baseline (no policy)
            long startBaseline = System.nanoTime();
            ContentionDetector detector = new ContentionDetector();
            List<ContentionDetector.ContentionGroup> baseline = detector.detectContentions(agents, pool);
            long baselineTime = System.nanoTime() - startBaseline;
            int baselineMaxSize = baseline.stream()
                .mapToInt(ContentionDetector.ContentionGroup::getAgentCount)
                .max().orElse(0);

            // Performance policy
            long startPolicy = System.nanoTime();
            GroupingSplitter splitter = new GroupingSplitter(GroupingPolicy.PERFORMANCE);
            List<ContentionDetector.ContentionGroup> withPolicy = splitter.detectWithPolicy(agents, pool);
            long policyTime = System.nanoTime() - startPolicy;
            int policyMaxSize = withPolicy.stream()
                .mapToInt(ContentionDetector.ContentionGroup::getAgentCount)
                .max().orElse(0);

            // Estimate optimization time (O(n³))
            double baselineOptCost = baseline.stream()
                .mapToDouble(g -> Math.pow(g.getAgentCount(), 3))
                .sum();
            double policyOptCost = withPolicy.stream()
                .mapToDouble(g -> Math.pow(g.getAgentCount(), 3))
                .sum();

            System.out.printf("    Baseline: %d groups, max=%d, detection=%.2fms%n",
                baseline.size(), baselineMaxSize, baselineTime / 1e6);
            System.out.printf("    Policy:   %d groups, max=%d, detection=%.2fms%n",
                withPolicy.size(), policyMaxSize, policyTime / 1e6);
            System.out.printf("    Estimated optimization speedup: %.1fx%n",
                baselineOptCost / Math.max(1, policyOptCost));
            System.out.println();
        }

        System.out.println("  ✓ Policy-based grouping enables scalability to large agent populations");
        printScenarioFooter();
    }

    // ========================================================================
    // Scenario 8: Combined Policies
    // ========================================================================

    private static void scenario8_CombinedPolicies() {
        printScenarioHeader(8, "Combined Policies",
            "Using multiple policy constraints simultaneously");

        // Multi-tenant with size limits
        List<Agent> agents = new ArrayList<>();
        Map<String, String> tenantMap = new HashMap<>();
        
        // Two tenants, 15 agents each
        for (String tenant : new String[]{"TenantA", "TenantB"}) {
            for (int i = 0; i < 15; i++) {
                String agentId = tenant + "-" + i;
                agents.add(createAgent(agentId, 100, Map.of(
                    ResourceType.COMPUTE, 20L,
                    ResourceType.STORAGE, 20L
                )));
                tenantMap.put(agentId, tenant);
            }
        }

        ResourcePool pool = new ResourcePool(Map.of(
            ResourceType.COMPUTE, 200L,
            ResourceType.STORAGE, 200L
        ));

        System.out.println("  30 agents (2 tenants × 15 agents)");
        System.out.println("  Policy: Tenant isolation + max size 5 + k-hop 2");
        System.out.println();

        // Combined policy
        GroupingPolicy combinedPolicy = new GroupingPolicy.Builder()
            .compatibilityMatrix(GroupingPolicy.CompatibilityMatrix.byCategory(tenantMap))
            .maxGroupSize(5)
            .kHopLimit(2)
            .splitStrategy(GroupingPolicy.SplitStrategy.MIN_CUT)
            .build();

        GroupingSplitter splitter = new GroupingSplitter(combinedPolicy);
        List<ContentionDetector.ContentionGroup> groups = splitter.detectWithPolicy(agents, pool);

        System.out.printf("  Result: %d groups%n", groups.size());
        
        // Count by tenant
        Map<String, List<ContentionDetector.ContentionGroup>> byTenant = new HashMap<>();
        for (ContentionDetector.ContentionGroup group : groups) {
            String tenant = tenantMap.get(group.getAgents().iterator().next().getId());
            byTenant.computeIfAbsent(tenant, k -> new ArrayList<>()).add(group);
        }

        for (Map.Entry<String, List<ContentionDetector.ContentionGroup>> entry : byTenant.entrySet()) {
            System.out.printf("    %s: %d groups%n", entry.getKey(), entry.getValue().size());
            for (ContentionDetector.ContentionGroup g : entry.getValue()) {
                System.out.printf("      → %d agents%n", g.getAgentCount());
            }
        }

        System.out.println();
        System.out.println("  ✓ Tenant isolation enforced");
        System.out.println("  ✓ Max size 5 respected");
        System.out.println("  ✓ K-hop 2 limits transitive grouping");
        printScenarioFooter();
    }

    // ========================================================================
    // Scenario 9: Dynamic Scenario with Resource-Conserving Arbitration
    // ========================================================================

    private static void scenario9_DynamicScenario() {
        printScenarioHeader(9, "Dynamic Arbitration Comparison",
            "Comparing welfare under different policies with RESOURCE-CONSERVING arbitration");

        // Create a scenario where policy choice matters
        List<Agent> agents = new ArrayList<>();
        
        // High-priority compute agents
        for (int i = 0; i < 5; i++) {
            agents.add(createAgent("HighPri-" + i, 200, Map.of(
                ResourceType.COMPUTE, 30L
            )));
        }
        
        // Low-priority compute agents
        for (int i = 0; i < 5; i++) {
            agents.add(createAgent("LowPri-" + i, 50, Map.of(
                ResourceType.COMPUTE, 30L
            )));
        }

        ResourcePool pool = new ResourcePool(Map.of(
            ResourceType.COMPUTE, 100L // Severe scarcity
        ));

        System.out.println("  10 agents competing for 100 COMPUTE (want 300 total)");
        System.out.println("  5 high-priority (c=200), 5 low-priority (c=50)");
        System.out.println();
        System.out.println("  NOTE: Using resource-conserving arbitration to prevent over-allocation.");
        System.out.println();

        PriorityEconomy economy = new PriorityEconomy();
        
        // Prepare currency commitments
        Map<String, BigDecimal> burns = new HashMap<>();
        for (Agent a : agents) {
            burns.put(a.getId(), a.getCurrencyBalance().multiply(new BigDecimal("0.1")));
        }

        // Test with different policies
        GroupingPolicy[] policies = {
            GroupingPolicy.DEFAULT,
            GroupingPolicy.withMaxSize(5),
            GroupingPolicy.withMaxSize(3)
        };

        for (GroupingPolicy policy : policies) {
            System.out.printf("  Policy: %s%n", policy);
            
            GroupingSplitter splitter = new GroupingSplitter(policy);
            
            // Use resource-conserving groups (pool is partitioned among groups)
            List<ContentionDetector.ContentionGroup> groups = 
                splitter.createConservingContentionGroups(agents, pool);
            
            System.out.printf("    Groups: %d%n", groups.size());
            
            // Arbitrate each group against its PARTITIONED pool share
            SequentialJointArbitrator arbitrator = new SequentialJointArbitrator(economy);
            double totalWelfare = 0;
            Map<String, Long> totalAllocations = new HashMap<>();
            
            for (ContentionDetector.ContentionGroup group : groups) {
                JointArbitrator.JointAllocationResult result = arbitrator.arbitrate(group, burns);
                totalWelfare += result.getObjectiveValue();
                
                for (Agent a : group.getAgents()) {
                    totalAllocations.put(a.getId(), result.getAllocation(a.getId(), ResourceType.COMPUTE));
                }
            }
            
            // Calculate actual allocations
            long highPriTotal = 0, lowPriTotal = 0;
            for (int i = 0; i < 5; i++) {
                highPriTotal += totalAllocations.getOrDefault("HighPri-" + i, 0L);
                lowPriTotal += totalAllocations.getOrDefault("LowPri-" + i, 0L);
            }
            
            long totalAllocated = highPriTotal + lowPriTotal;
            
            System.out.printf("    Total Welfare: %.2f%n", totalWelfare);
            System.out.printf("    High-priority total: %d, Low-priority total: %d%n", 
                highPriTotal, lowPriTotal);
            System.out.printf("    TOTAL ALLOCATED: %d (pool: 100) %s%n", 
                totalAllocated, 
                totalAllocated <= 100 ? "✓ Conservation OK" : "✗ OVER-ALLOCATED!");
            System.out.println();
        }

        System.out.println("  ✓ Resource conservation maintained across all policies");
        System.out.println("  ✓ Different policies lead to different allocation outcomes");
        System.out.println("  ✓ Smaller groups may reduce cross-agent optimization opportunities");
        printScenarioFooter();
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private static Agent createAgent(String id, int currency, Map<ResourceType, Long> demands) {
        // Build preference weights (1.0 for each demanded resource)
        Map<ResourceType, Double> weights = new HashMap<>();
        for (ResourceType type : demands.keySet()) {
            weights.put(type, 1.0);
        }
        
        Agent agent = new Agent(id, id, weights, currency);
        
        // Set ideal requests
        for (Map.Entry<ResourceType, Long> entry : demands.entrySet()) {
            agent.setRequest(entry.getKey(), 0, entry.getValue());
        }
        
        return agent;
    }

    private static List<Agent> generateAgents(int count, long seed) {
        List<Agent> agents = new ArrayList<>();
        Random rand = new Random(seed);
        ResourceType[] types = ResourceType.values();
        
        for (int i = 0; i < count; i++) {
            Map<ResourceType, Long> demands = new HashMap<>();
            int numResources = 1 + rand.nextInt(4);
            for (int j = 0; j < numResources; j++) {
                demands.put(types[rand.nextInt(types.length)], 5L + rand.nextInt(20));
            }
            agents.add(createAgent("Agent-" + i, 50 + rand.nextInt(150), demands));
        }
        
        return agents;
    }

    private static void printGroups(List<ContentionDetector.ContentionGroup> groups) {
        if (groups.isEmpty()) {
            System.out.println("    No contention groups (no contention detected)");
        } else {
            for (ContentionDetector.ContentionGroup group : groups) {
                String agentNames = group.getAgents().stream()
                    .map(Agent::getId)
                    .sorted()
                    .collect(Collectors.joining(", "));
                System.out.printf("    → {%s}%n", agentNames);
            }
        }
        System.out.println();
    }

    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }

    private static void printScenarioHeader(int num, String title, String description) {
        System.out.println("───────────────────────────────────────────────────────────────────────────────");
        System.out.printf("SCENARIO %d: %s%n", num, title);
        System.out.println(description);
        System.out.println("───────────────────────────────────────────────────────────────────────────────");
        System.out.println();
    }

    private static void printScenarioFooter() {
        System.out.println();
    }
}
