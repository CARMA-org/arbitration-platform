package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.*;

import java.util.*;

/**
 * Detects and groups contentions using graph-based connected component analysis.
 * 
 * When multiple agents compete for overlapping sets of resources, they must be
 * arbitrated together as an atomic transaction to enable cross-resource trades
 * and achieve global Pareto optimality.
 * 
 * Example: If Agent A wants {Compute, Storage} and Agent B wants {Compute},
 * and Agent C wants {Storage}, all three form a connected component because
 * A bridges B and C through shared resource demands.
 */
public class ContentionDetector {

    /**
     * A ContentionGroup represents a set of agents that must be arbitrated together.
     */
    public static class ContentionGroup {
        private final String groupId;
        private final Set<Agent> agents;
        private final Set<ResourceType> resources;
        private final Map<ResourceType, Long> availableQuantities;
        private final long detectedAtMs;

        public ContentionGroup(String groupId, Set<Agent> agents, Set<ResourceType> resources,
                              Map<ResourceType, Long> availableQuantities) {
            this.groupId = groupId;
            this.agents = new HashSet<>(agents);
            this.resources = new HashSet<>(resources);
            this.availableQuantities = new HashMap<>(availableQuantities);
            this.detectedAtMs = System.currentTimeMillis();
        }

        public String getGroupId() { return groupId; }
        public Set<Agent> getAgents() { return Collections.unmodifiableSet(agents); }
        public Set<ResourceType> getResources() { return Collections.unmodifiableSet(resources); }
        public Map<ResourceType, Long> getAvailableQuantities() { return Collections.unmodifiableMap(availableQuantities); }
        public long getDetectedAtMs() { return detectedAtMs; }
        public int getAgentCount() { return agents.size(); }
        public int getResourceCount() { return resources.size(); }

        /**
         * Check if this is a multi-resource contention requiring joint optimization.
         */
        public boolean requiresJointOptimization() {
            return resources.size() > 1 && agents.size() > 1;
        }

        /**
         * Calculate overall contention severity (demand / supply ratio).
         */
        public double getContentionSeverity() {
            double totalDemand = 0;
            double totalSupply = 0;
            for (ResourceType type : resources) {
                for (Agent agent : agents) {
                    totalDemand += agent.getIdeal(type);
                }
                totalSupply += availableQuantities.getOrDefault(type, 0L);
            }
            return totalSupply > 0 ? totalDemand / totalSupply : Double.MAX_VALUE;
        }

        @Override
        public String toString() {
            return String.format("ContentionGroup[%s: %d agents, %d resources, severity=%.2f]",
                groupId, agents.size(), resources.size(), getContentionSeverity());
        }
    }

    // ========================================================================
    // Detection Methods
    // ========================================================================

    /**
     * Detect all contention groups from a set of agents and resource pool.
     * Uses Union-Find algorithm for connected component detection.
     * 
     * @param agents All agents with resource requests
     * @param pool Current resource pool state
     * @return List of contention groups (each requiring atomic arbitration)
     */
    public List<ContentionGroup> detectContentions(List<Agent> agents, ResourcePool pool) {
        if (agents.isEmpty()) {
            return Collections.emptyList();
        }

        // Build adjacency: two agents are connected if they share any resource demand
        Map<String, Set<String>> adjacency = buildAdjacencyGraph(agents, pool);
        
        // Find connected components using Union-Find
        Map<String, String> parent = new HashMap<>();
        for (Agent agent : agents) {
            parent.put(agent.getId(), agent.getId());
        }

        // Union agents that share resources
        for (Agent agent : agents) {
            String agentId = agent.getId();
            Set<String> neighbors = adjacency.getOrDefault(agentId, Collections.emptySet());
            for (String neighborId : neighbors) {
                union(parent, agentId, neighborId);
            }
        }

        // Group agents by their root
        Map<String, Set<Agent>> components = new HashMap<>();
        for (Agent agent : agents) {
            String root = find(parent, agent.getId());
            components.computeIfAbsent(root, k -> new HashSet<>()).add(agent);
        }

        // Convert to ContentionGroups
        List<ContentionGroup> groups = new ArrayList<>();
        int groupNum = 0;
        for (Set<Agent> component : components.values()) {
            // Skip singleton components with no contention
            if (component.size() < 2) {
                continue;
            }

            // Collect resources this component cares about
            Set<ResourceType> resources = new HashSet<>();
            for (Agent agent : component) {
                for (ResourceType type : ResourceType.values()) {
                    if (agent.getIdeal(type) > 0) {
                        resources.add(type);
                    }
                }
            }

            // Check if there's actual contention (demand > supply)
            boolean hasContention = false;
            Map<ResourceType, Long> available = new HashMap<>();
            for (ResourceType type : resources) {
                long supply = pool.getAvailable(type);
                available.put(type, supply);
                
                long demand = component.stream()
                    .mapToLong(a -> a.getIdeal(type))
                    .sum();
                if (demand > supply) {
                    hasContention = true;
                }
            }

            if (hasContention) {
                groups.add(new ContentionGroup(
                    "CG-" + (++groupNum),
                    component,
                    resources,
                    available
                ));
            }
        }

        return groups;
    }

    /**
     * Build adjacency graph where agents are connected if they share resource demands.
     */
    private Map<String, Set<String>> buildAdjacencyGraph(List<Agent> agents, ResourcePool pool) {
        Map<String, Set<String>> adjacency = new HashMap<>();
        
        // For each resource type, find all agents that want it
        Map<ResourceType, List<Agent>> resourceToAgents = new HashMap<>();
        for (Agent agent : agents) {
            for (ResourceType type : ResourceType.values()) {
                if (agent.getIdeal(type) > 0) {
                    resourceToAgents.computeIfAbsent(type, k -> new ArrayList<>()).add(agent);
                }
            }
        }

        // Connect all agents that share a resource (with contention)
        for (Map.Entry<ResourceType, List<Agent>> entry : resourceToAgents.entrySet()) {
            ResourceType type = entry.getKey();
            List<Agent> competing = entry.getValue();
            
            // Check if there's contention for this resource
            long totalDemand = competing.stream().mapToLong(a -> a.getIdeal(type)).sum();
            long supply = pool.getAvailable(type);
            
            if (totalDemand > supply && competing.size() > 1) {
                // Connect all pairs of agents competing for this resource
                for (int i = 0; i < competing.size(); i++) {
                    for (int j = i + 1; j < competing.size(); j++) {
                        String id1 = competing.get(i).getId();
                        String id2 = competing.get(j).getId();
                        adjacency.computeIfAbsent(id1, k -> new HashSet<>()).add(id2);
                        adjacency.computeIfAbsent(id2, k -> new HashSet<>()).add(id1);
                    }
                }
            }
        }

        return adjacency;
    }

    // ========================================================================
    // Union-Find Helpers
    // ========================================================================

    private String find(Map<String, String> parent, String x) {
        if (!parent.get(x).equals(x)) {
            parent.put(x, find(parent, parent.get(x))); // Path compression
        }
        return parent.get(x);
    }

    private void union(Map<String, String> parent, String x, String y) {
        String rootX = find(parent, x);
        String rootY = find(parent, y);
        if (!rootX.equals(rootY)) {
            parent.put(rootX, rootY);
        }
    }

    // ========================================================================
    // Analysis Methods
    // ========================================================================

    /**
     * Analyze contentions and return summary statistics.
     */
    public ContentionAnalysis analyzeContentions(List<Agent> agents, ResourcePool pool) {
        List<ContentionGroup> groups = detectContentions(agents, pool);
        
        int totalAgentsInContention = groups.stream()
            .mapToInt(ContentionGroup::getAgentCount)
            .sum();
        
        int multiResourceContentions = (int) groups.stream()
            .filter(ContentionGroup::requiresJointOptimization)
            .count();
        
        double maxSeverity = groups.stream()
            .mapToDouble(ContentionGroup::getContentionSeverity)
            .max()
            .orElse(0.0);
        
        return new ContentionAnalysis(
            groups.size(),
            totalAgentsInContention,
            multiResourceContentions,
            maxSeverity,
            groups
        );
    }

    /**
     * Summary of contention analysis.
     */
    public static class ContentionAnalysis {
        public final int totalGroups;
        public final int totalAgentsInContention;
        public final int multiResourceContentions;
        public final double maxSeverity;
        public final List<ContentionGroup> groups;

        public ContentionAnalysis(int totalGroups, int totalAgentsInContention,
                                 int multiResourceContentions, double maxSeverity,
                                 List<ContentionGroup> groups) {
            this.totalGroups = totalGroups;
            this.totalAgentsInContention = totalAgentsInContention;
            this.multiResourceContentions = multiResourceContentions;
            this.maxSeverity = maxSeverity;
            this.groups = groups;
        }

        @Override
        public String toString() {
            return String.format(
                "ContentionAnalysis[groups=%d, agents=%d, multiResource=%d, maxSeverity=%.2f]",
                totalGroups, totalAgentsInContention, multiResourceContentions, maxSeverity);
        }
    }
}
