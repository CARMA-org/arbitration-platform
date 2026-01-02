package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.*;

import java.util.*;

/**
 * Configurable policies for controlling how agents get grouped for joint optimization.
 * 
 * Joint optimization achieves global Pareto optimality but has computational cost O(n³)
 * in the number of agents. These policies allow trading off optimality for performance
 * by limiting group formation.
 * 
 * <h2>Policy Dimensions</h2>
 * 
 * <h3>1. K-Hop Limits</h3>
 * Controls how far contention can spread through the resource-sharing graph.
 * - k=1: Only agents directly competing for the same resource are grouped
 * - k=2: Agents competing for resources with common competitors are grouped  
 * - k=∞ (default): Full transitive closure, maximum Pareto optimality
 * 
 * Example with k=1:
 *   A wants {Compute, Storage}, B wants {Compute}, C wants {Storage}
 *   - Without limit: A, B, C form one group (A bridges B and C)
 *   - With k=1: {A,B} and {A,C} are separate groups (no transitive grouping)
 * 
 * <h3>2. Size Bounds</h3>
 * Maximum number of agents in any single optimization group.
 * - Larger groups = better optimality but O(n³) cost
 * - Smaller groups = faster but may miss cross-agent trades
 * 
 * <h3>3. Compatibility Matrices</h3>
 * Explicit specification of which agents can be optimized together.
 * Use cases:
 * - Tenant isolation in multi-tenant systems
 * - Security boundaries between trust domains
 * - Organizational separation requirements
 * 
 * <h2>Trade-offs</h2>
 * 
 * | Policy | Optimality | Performance | Use Case |
 * |--------|------------|-------------|----------|
 * | No limits | 100% | O(n³) | Small systems, batch processing |
 * | k-hop=1 | ~95% | O(n²) | Real-time with some trade loss |
 * | Size=10 | ~90% | O(1) | Large-scale with bounded latency |
 * | Compatibility | Varies | Varies | Multi-tenant, security boundaries |
 * 
 * @see ContentionDetector
 * @see GroupingSplitter
 */
public class GroupingPolicy {

    // ========================================================================
    // Constants
    // ========================================================================

    /** Unlimited k-hop (full transitive closure) */
    public static final int UNLIMITED_HOPS = Integer.MAX_VALUE;
    
    /** Unlimited group size */
    public static final int UNLIMITED_SIZE = Integer.MAX_VALUE;

    /** Default policy: no restrictions */
    public static final GroupingPolicy DEFAULT = new GroupingPolicy.Builder().build();

    /** Performance-optimized: k=1, size=10 */
    public static final GroupingPolicy PERFORMANCE = new GroupingPolicy.Builder()
        .kHopLimit(1)
        .maxGroupSize(10)
        .build();

    /** Balanced: k=2, size=20 */
    public static final GroupingPolicy BALANCED = new GroupingPolicy.Builder()
        .kHopLimit(2)
        .maxGroupSize(20)
        .build();

    // ========================================================================
    // Fields
    // ========================================================================

    private final int kHopLimit;
    private final int maxGroupSize;
    private final int minGroupSize;
    private final CompatibilityMatrix compatibilityMatrix;
    private final SplitStrategy splitStrategy;
    private final boolean enableDynamicAdjustment;
    private final double targetLatencyMs;

    // ========================================================================
    // Constructor (via Builder)
    // ========================================================================

    private GroupingPolicy(Builder builder) {
        this.kHopLimit = builder.kHopLimit;
        this.maxGroupSize = builder.maxGroupSize;
        this.minGroupSize = builder.minGroupSize;
        this.compatibilityMatrix = builder.compatibilityMatrix;
        this.splitStrategy = builder.splitStrategy;
        this.enableDynamicAdjustment = builder.enableDynamicAdjustment;
        this.targetLatencyMs = builder.targetLatencyMs;
    }

    // ========================================================================
    // Getters
    // ========================================================================

    /**
     * Maximum number of hops in the contention graph for grouping.
     * k=1 means only direct competitors are grouped.
     * k=∞ (UNLIMITED_HOPS) means full transitive closure.
     */
    public int getKHopLimit() {
        return kHopLimit;
    }

    /**
     * Maximum number of agents in a single optimization group.
     */
    public int getMaxGroupSize() {
        return maxGroupSize;
    }

    /**
     * Minimum number of agents to trigger group optimization.
     * Groups smaller than this use sequential arbitration.
     */
    public int getMinGroupSize() {
        return minGroupSize;
    }

    /**
     * Compatibility matrix defining which agents can be grouped together.
     */
    public CompatibilityMatrix getCompatibilityMatrix() {
        return compatibilityMatrix;
    }

    /**
     * Strategy for splitting oversized groups.
     */
    public SplitStrategy getSplitStrategy() {
        return splitStrategy;
    }

    /**
     * Whether to dynamically adjust policy based on observed latency.
     */
    public boolean isDynamicAdjustmentEnabled() {
        return enableDynamicAdjustment;
    }

    /**
     * Target latency in milliseconds for dynamic adjustment.
     */
    public double getTargetLatencyMs() {
        return targetLatencyMs;
    }

    /**
     * Check if k-hop limiting is enabled.
     */
    public boolean hasKHopLimit() {
        return kHopLimit != UNLIMITED_HOPS;
    }

    /**
     * Check if size limiting is enabled.
     */
    public boolean hasSizeLimit() {
        return maxGroupSize != UNLIMITED_SIZE;
    }

    /**
     * Check if compatibility matrix is defined.
     */
    public boolean hasCompatibilityMatrix() {
        return compatibilityMatrix != null && !compatibilityMatrix.isEmpty();
    }

    /**
     * Check if two agents are compatible (can be grouped together).
     */
    public boolean areCompatible(Agent a, Agent b) {
        if (compatibilityMatrix == null || compatibilityMatrix.isEmpty()) {
            return true; // No restrictions
        }
        return compatibilityMatrix.areCompatible(a.getId(), b.getId());
    }

    /**
     * Check if two agents are compatible by ID.
     */
    public boolean areCompatible(String agentIdA, String agentIdB) {
        if (compatibilityMatrix == null || compatibilityMatrix.isEmpty()) {
            return true;
        }
        return compatibilityMatrix.areCompatible(agentIdA, agentIdB);
    }

    // ========================================================================
    // Compatibility Matrix
    // ========================================================================

    /**
     * Matrix defining which agents can be grouped together.
     * 
     * Can be defined in three modes:
     * 1. ALLOWLIST: Only explicitly allowed pairs can be grouped
     * 2. BLOCKLIST: All pairs allowed except explicitly blocked
     * 3. CATEGORY: Agents in same category can be grouped
     */
    public static class CompatibilityMatrix {
        
        public enum Mode {
            /** Only explicitly allowed pairs can be grouped */
            ALLOWLIST,
            /** All pairs allowed except explicitly blocked */
            BLOCKLIST,
            /** Agents in same category can be grouped */
            CATEGORY
        }

        private final Mode mode;
        private final Set<AgentPair> explicitPairs;
        private final Map<String, String> agentCategories;

        private CompatibilityMatrix(Mode mode, Set<AgentPair> explicitPairs, 
                                   Map<String, String> agentCategories) {
            this.mode = mode;
            this.explicitPairs = explicitPairs != null ? new HashSet<>(explicitPairs) : new HashSet<>();
            this.agentCategories = agentCategories != null ? new HashMap<>(agentCategories) : new HashMap<>();
        }

        /**
         * Create an allowlist-based matrix.
         */
        public static CompatibilityMatrix allowlist(Set<AgentPair> allowedPairs) {
            return new CompatibilityMatrix(Mode.ALLOWLIST, allowedPairs, null);
        }

        /**
         * Create a blocklist-based matrix.
         */
        public static CompatibilityMatrix blocklist(Set<AgentPair> blockedPairs) {
            return new CompatibilityMatrix(Mode.BLOCKLIST, blockedPairs, null);
        }

        /**
         * Create a category-based matrix where agents in the same category can be grouped.
         */
        public static CompatibilityMatrix byCategory(Map<String, String> agentToCategory) {
            return new CompatibilityMatrix(Mode.CATEGORY, null, agentToCategory);
        }

        /**
         * Check if two agents are compatible.
         */
        public boolean areCompatible(String agentIdA, String agentIdB) {
            if (agentIdA.equals(agentIdB)) {
                return true; // Agent is always compatible with itself
            }

            switch (mode) {
                case ALLOWLIST:
                    return explicitPairs.contains(new AgentPair(agentIdA, agentIdB));
                
                case BLOCKLIST:
                    return !explicitPairs.contains(new AgentPair(agentIdA, agentIdB));
                
                case CATEGORY:
                    String catA = agentCategories.get(agentIdA);
                    String catB = agentCategories.get(agentIdB);
                    if (catA == null || catB == null) {
                        return true; // Uncategorized agents can group with anyone
                    }
                    return catA.equals(catB);
                
                default:
                    return true;
            }
        }

        public boolean isEmpty() {
            switch (mode) {
                case ALLOWLIST:
                case BLOCKLIST:
                    return explicitPairs.isEmpty();
                case CATEGORY:
                    return agentCategories.isEmpty();
                default:
                    return true;
            }
        }

        public Mode getMode() {
            return mode;
        }

        public Set<AgentPair> getExplicitPairs() {
            return Collections.unmodifiableSet(explicitPairs);
        }

        public Map<String, String> getAgentCategories() {
            return Collections.unmodifiableMap(agentCategories);
        }

        @Override
        public String toString() {
            switch (mode) {
                case ALLOWLIST:
                    return String.format("CompatibilityMatrix[ALLOWLIST: %d pairs]", explicitPairs.size());
                case BLOCKLIST:
                    return String.format("CompatibilityMatrix[BLOCKLIST: %d blocked]", explicitPairs.size());
                case CATEGORY:
                    long uniqueCategories = agentCategories.values().stream().distinct().count();
                    return String.format("CompatibilityMatrix[CATEGORY: %d agents, %d categories]", 
                        agentCategories.size(), uniqueCategories);
                default:
                    return "CompatibilityMatrix[UNKNOWN]";
            }
        }
    }

    /**
     * Immutable pair of agent IDs for compatibility tracking.
     * Order-independent: AgentPair(A,B) equals AgentPair(B,A).
     */
    public static class AgentPair {
        private final String id1;
        private final String id2;

        public AgentPair(String idA, String idB) {
            // Canonical ordering for equality
            if (idA.compareTo(idB) <= 0) {
                this.id1 = idA;
                this.id2 = idB;
            } else {
                this.id1 = idB;
                this.id2 = idA;
            }
        }

        public String getId1() { return id1; }
        public String getId2() { return id2; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AgentPair agentPair = (AgentPair) o;
            return id1.equals(agentPair.id1) && id2.equals(agentPair.id2);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id1, id2);
        }

        @Override
        public String toString() {
            return String.format("(%s, %s)", id1, id2);
        }
    }

    // ========================================================================
    // Split Strategy
    // ========================================================================

    /**
     * Strategy for splitting oversized groups.
     */
    public enum SplitStrategy {
        /**
         * Split by minimizing edge cuts in the contention graph.
         * Preserves the most valuable cross-agent trade opportunities.
         */
        MIN_CUT,

        /**
         * Split by resource type affinity.
         * Groups agents with similar resource needs together.
         */
        RESOURCE_AFFINITY,

        /**
         * Split by priority/currency levels.
         * Groups high-priority agents together for faster processing.
         */
        PRIORITY_CLUSTERING,

        /**
         * Simple round-robin assignment to subgroups.
         * Fast but may break beneficial groupings.
         */
        ROUND_ROBIN,

        /**
         * Spectral clustering based on contention graph structure.
         * High quality but O(n³) preprocessing.
         */
        SPECTRAL
    }

    // ========================================================================
    // Builder
    // ========================================================================

    /**
     * Builder for GroupingPolicy.
     */
    public static class Builder {
        private int kHopLimit = UNLIMITED_HOPS;
        private int maxGroupSize = UNLIMITED_SIZE;
        private int minGroupSize = 2;
        private CompatibilityMatrix compatibilityMatrix = null;
        private SplitStrategy splitStrategy = SplitStrategy.MIN_CUT;
        private boolean enableDynamicAdjustment = false;
        private double targetLatencyMs = 100.0;

        public Builder() {}

        /**
         * Set k-hop limit for contention graph traversal.
         * @param k Maximum hops (1 = direct competitors only)
         */
        public Builder kHopLimit(int k) {
            if (k < 1) {
                throw new IllegalArgumentException("k-hop limit must be >= 1");
            }
            this.kHopLimit = k;
            return this;
        }

        /**
         * Set maximum group size.
         * @param size Maximum agents per group
         */
        public Builder maxGroupSize(int size) {
            if (size < 2) {
                throw new IllegalArgumentException("Max group size must be >= 2");
            }
            this.maxGroupSize = size;
            return this;
        }

        /**
         * Set minimum group size for joint optimization.
         * Groups smaller than this use sequential arbitration.
         */
        public Builder minGroupSize(int size) {
            if (size < 1) {
                throw new IllegalArgumentException("Min group size must be >= 1");
            }
            this.minGroupSize = size;
            return this;
        }

        /**
         * Set compatibility matrix.
         */
        public Builder compatibilityMatrix(CompatibilityMatrix matrix) {
            this.compatibilityMatrix = matrix;
            return this;
        }

        /**
         * Set split strategy for oversized groups.
         */
        public Builder splitStrategy(SplitStrategy strategy) {
            this.splitStrategy = strategy;
            return this;
        }

        /**
         * Enable dynamic policy adjustment based on observed latency.
         */
        public Builder enableDynamicAdjustment(boolean enable) {
            this.enableDynamicAdjustment = enable;
            return this;
        }

        /**
         * Set target latency for dynamic adjustment.
         */
        public Builder targetLatencyMs(double latencyMs) {
            this.targetLatencyMs = latencyMs;
            return this;
        }

        /**
         * Build the policy.
         */
        public GroupingPolicy build() {
            return new GroupingPolicy(this);
        }
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a policy with only k-hop limit.
     */
    public static GroupingPolicy withKHopLimit(int k) {
        return new Builder().kHopLimit(k).build();
    }

    /**
     * Create a policy with only size limit.
     */
    public static GroupingPolicy withMaxSize(int maxSize) {
        return new Builder().maxGroupSize(maxSize).build();
    }

    /**
     * Create a policy with both k-hop and size limits.
     */
    public static GroupingPolicy withLimits(int kHop, int maxSize) {
        return new Builder()
            .kHopLimit(kHop)
            .maxGroupSize(maxSize)
            .build();
    }

    /**
     * Create a tenant-isolated policy where each tenant forms separate groups.
     */
    public static GroupingPolicy tenantIsolated(Map<String, String> agentToTenant) {
        return new Builder()
            .compatibilityMatrix(CompatibilityMatrix.byCategory(agentToTenant))
            .build();
    }

    // ========================================================================
    // toString
    // ========================================================================

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("GroupingPolicy[");
        List<String> parts = new ArrayList<>();
        
        if (hasKHopLimit()) {
            parts.add("k-hop=" + kHopLimit);
        }
        if (hasSizeLimit()) {
            parts.add("maxSize=" + maxGroupSize);
        }
        if (minGroupSize > 2) {
            parts.add("minSize=" + minGroupSize);
        }
        if (hasCompatibilityMatrix()) {
            parts.add(compatibilityMatrix.toString());
        }
        if (splitStrategy != SplitStrategy.MIN_CUT) {
            parts.add("split=" + splitStrategy);
        }
        if (enableDynamicAdjustment) {
            parts.add("dynamic=true, target=" + targetLatencyMs + "ms");
        }
        
        if (parts.isEmpty()) {
            sb.append("UNLIMITED");
        } else {
            sb.append(String.join(", ", parts));
        }
        
        sb.append("]");
        return sb.toString();
    }
}
