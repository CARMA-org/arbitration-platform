package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility for splitting contention groups according to GroupingPolicy.
 * 
 * When a contention group exceeds policy limits (k-hop, size, compatibility),
 * this class splits it into smaller subgroups that comply with the policy.
 * 
 * <h2>Splitting Algorithms</h2>
 * 
 * <h3>K-Hop Limited Detection</h3>
 * Uses BFS from each agent to find all agents within k hops in the contention
 * graph. Unlike naive overlapping merge, this version respects k-hop limits
 * by ensuring all pairs in a group are within k hops of each other.
 * 
 * <h3>Size-Based Splitting</h3>
 * When a group exceeds maxGroupSize, splits using one of:
 * - MIN_CUT: Minimize edges cut between subgroups (preserves trade opportunities)
 * - RESOURCE_AFFINITY: Group agents with similar resource needs
 * - PRIORITY_CLUSTERING: Group by priority levels
 * - ROUND_ROBIN: Simple distribution
 * - SPECTRAL: Graph-based clustering
 * 
 * <h3>Compatibility Enforcement</h3>
 * Separates incompatible agents into different groups regardless of contention.
 * 
 * <h3>Resource Conservation</h3>
 * When arbitrating split groups, use {@link #partitionPoolForGroups} or 
 * {@link #createConservingContentionGroups} to ensure total allocations
 * don't exceed pool capacity.
 * 
 * @see GroupingPolicy
 * @see ContentionDetector
 */
public class GroupingSplitter {

    private final GroupingPolicy policy;
    private boolean debug = false;

    /**
     * Create a splitter with the given policy.
     */
    public GroupingSplitter(GroupingPolicy policy) {
        this.policy = policy != null ? policy : GroupingPolicy.DEFAULT;
    }

    /**
     * Enable debug output.
     */
    public GroupingSplitter setDebug(boolean debug) {
        this.debug = debug;
        return this;
    }

    /**
     * Apply policy to a list of contention groups, returning policy-compliant subgroups.
     * 
     * @param groups Original contention groups from ContentionDetector
     * @param agents Full list of agents (needed for compatibility checks)
     * @param pool Resource pool
     * @return List of policy-compliant groups
     */
    public List<ContentionDetector.ContentionGroup> applyPolicy(
            List<ContentionDetector.ContentionGroup> groups,
            List<Agent> agents,
            ResourcePool pool) {
        
        List<ContentionDetector.ContentionGroup> result = new ArrayList<>();
        
        for (ContentionDetector.ContentionGroup group : groups) {
            List<ContentionDetector.ContentionGroup> split = splitGroup(group, agents, pool);
            result.addAll(split);
        }
        
        return result;
    }

    /**
     * Detect contentions with k-hop limiting applied during detection.
     * More efficient than detecting then splitting.
     * 
     * @param agents All agents
     * @param pool Resource pool
     * @return Policy-compliant contention groups
     */
    public List<ContentionDetector.ContentionGroup> detectWithPolicy(
            List<Agent> agents, ResourcePool pool) {
        
        if (!policy.hasKHopLimit() && !policy.hasCompatibilityMatrix()) {
            // No detection-time limits, use standard detection then split by size
            ContentionDetector detector = new ContentionDetector();
            List<ContentionDetector.ContentionGroup> groups = detector.detectContentions(agents, pool);
            return applyPolicy(groups, agents, pool);
        }

        // Build contention graph
        Map<String, Agent> agentMap = agents.stream()
            .collect(Collectors.toMap(Agent::getId, a -> a));
        Map<String, Set<String>> adjacency = buildContentionGraph(agents, pool);
        
        // Apply k-hop grouping with proper k-hop constraint
        List<Set<String>> neighborhoods;
        if (policy.hasKHopLimit()) {
            neighborhoods = findKHopConstrainedGroups(adjacency, policy.getKHopLimit());
        } else {
            // No k-hop limit - find connected components
            neighborhoods = findConnectedComponents(adjacency);
        }
        
        // Apply compatibility filtering
        if (policy.hasCompatibilityMatrix()) {
            neighborhoods = applyCompatibilityFilter(neighborhoods, policy);
        }
        
        // Convert to ContentionGroups - check contention against TOTAL demand
        List<ContentionDetector.ContentionGroup> groups = new ArrayList<>();
        int groupNum = 0;
        
        // First, calculate total demand across ALL agents for contention check
        Map<ResourceType, Long> totalDemand = new HashMap<>();
        for (Agent agent : agents) {
            for (ResourceType type : ResourceType.values()) {
                long ideal = agent.getIdeal(type);
                if (ideal > 0) {
                    totalDemand.merge(type, ideal, Long::sum);
                }
            }
        }
        
        // Identify which resources are actually contended
        Set<ResourceType> contentedResources = new HashSet<>();
        for (ResourceType type : totalDemand.keySet()) {
            if (totalDemand.get(type) > pool.getAvailable(type)) {
                contentedResources.add(type);
            }
        }
        
        for (Set<String> neighborhood : neighborhoods) {
            if (neighborhood.size() < 2) {
                continue; // Skip singletons
            }
            
            Set<Agent> groupAgents = neighborhood.stream()
                .map(agentMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
            
            if (groupAgents.size() < 2) {
                continue;
            }
            
            // Collect resources this group wants
            Set<ResourceType> resources = new HashSet<>();
            for (Agent agent : groupAgents) {
                for (ResourceType type : ResourceType.values()) {
                    if (agent.getIdeal(type) > 0) {
                        resources.add(type);
                    }
                }
            }
            
            // Check if this group is contending for any contended resource
            // (i.e., the group has agents wanting resources where TOTAL demand > supply)
            boolean hasContention = false;
            for (ResourceType type : resources) {
                if (contentedResources.contains(type)) {
                    hasContention = true;
                    break;
                }
            }
            
            if (hasContention) {
                Map<ResourceType, Long> available = new HashMap<>();
                for (ResourceType type : resources) {
                    available.put(type, pool.getAvailable(type));
                }
                
                ContentionDetector.ContentionGroup group = new ContentionDetector.ContentionGroup(
                    "CG-" + (++groupNum),
                    groupAgents,
                    resources,
                    available
                );
                groups.add(group);
            }
        }
        
        // Apply size limits
        if (policy.hasSizeLimit()) {
            groups = applySizeLimit(groups, agents, pool);
        }
        
        return groups;
    }

    /**
     * Create contention groups with partitioned pool availability.
     * 
     * Use this method when you need to arbitrate split groups while conserving
     * total resource allocations. Each group's available resources are 
     * proportionally reduced based on the number of groups.
     * 
     * @param agents All agents
     * @param pool Original resource pool
     * @return Groups with partitioned pool availability
     */
    public List<ContentionDetector.ContentionGroup> createConservingContentionGroups(
            List<Agent> agents, ResourcePool pool) {
        
        List<ContentionDetector.ContentionGroup> groups = detectWithPolicy(agents, pool);
        
        if (groups.size() <= 1) {
            return groups; // No partitioning needed
        }
        
        // Partition pool among groups based on their demand
        Map<ResourceType, Map<String, Long>> partitions = partitionPoolForGroups(groups, pool);
        
        // Create new groups with partitioned availability
        List<ContentionDetector.ContentionGroup> conservingGroups = new ArrayList<>();
        for (ContentionDetector.ContentionGroup group : groups) {
            Map<ResourceType, Long> groupAvailable = new HashMap<>();
            for (ResourceType type : group.getResources()) {
                Map<String, Long> typePartitions = partitions.get(type);
                if (typePartitions != null && typePartitions.containsKey(group.getGroupId())) {
                    groupAvailable.put(type, typePartitions.get(group.getGroupId()));
                } else {
                    groupAvailable.put(type, 0L);
                }
            }
            
            conservingGroups.add(new ContentionDetector.ContentionGroup(
                group.getGroupId(),
                group.getAgents(),
                group.getResources(),
                groupAvailable
            ));
        }
        
        return conservingGroups;
    }

    /**
     * Partition pool resources among groups based on demand ratios.
     * 
     * Each group receives a share of resources proportional to its total demand.
     * This ensures that if each group is arbitrated against its partition,
     * total allocations won't exceed pool capacity.
     * 
     * @param groups Contention groups
     * @param pool Original resource pool
     * @return Map from ResourceType -> (GroupId -> allocated capacity)
     */
    public Map<ResourceType, Map<String, Long>> partitionPoolForGroups(
            List<ContentionDetector.ContentionGroup> groups,
            ResourcePool pool) {
        
        Map<ResourceType, Map<String, Long>> partitions = new HashMap<>();
        
        // Calculate demand per resource per group
        Map<ResourceType, Map<String, Long>> demandByGroup = new HashMap<>();
        for (ContentionDetector.ContentionGroup group : groups) {
            for (ResourceType type : group.getResources()) {
                long groupDemand = group.getAgents().stream()
                    .mapToLong(a -> a.getIdeal(type))
                    .sum();
                demandByGroup.computeIfAbsent(type, k -> new HashMap<>())
                    .put(group.getGroupId(), groupDemand);
            }
        }
        
        // Partition each resource based on demand ratio
        for (ResourceType type : demandByGroup.keySet()) {
            Map<String, Long> groupDemands = demandByGroup.get(type);
            long totalDemand = groupDemands.values().stream().mapToLong(Long::longValue).sum();
            long available = pool.getAvailable(type);
            
            Map<String, Long> typePartition = new HashMap<>();
            
            if (totalDemand <= available) {
                // No contention - each group gets what it needs
                typePartition.putAll(groupDemands);
            } else {
                // Proportional partitioning
                long allocated = 0;
                List<String> groupIds = new ArrayList<>(groupDemands.keySet());
                
                for (int i = 0; i < groupIds.size(); i++) {
                    String groupId = groupIds.get(i);
                    long demand = groupDemands.get(groupId);
                    long share;
                    
                    if (i == groupIds.size() - 1) {
                        // Last group gets remainder to avoid rounding issues
                        share = available - allocated;
                    } else {
                        share = (long) Math.floor((double) demand / totalDemand * available);
                    }
                    
                    typePartition.put(groupId, Math.max(0, share));
                    allocated += share;
                }
            }
            
            partitions.put(type, typePartition);
        }
        
        return partitions;
    }

    /**
     * Split a single group according to policy.
     */
    private List<ContentionDetector.ContentionGroup> splitGroup(
            ContentionDetector.ContentionGroup group,
            List<Agent> allAgents,
            ResourcePool pool) {
        
        List<ContentionDetector.ContentionGroup> result = new ArrayList<>();
        
        // First apply compatibility filter
        List<Set<Agent>> compatibleSubgroups = splitByCompatibility(group.getAgents(), policy);
        
        // Then apply size limit to each subgroup
        for (Set<Agent> subgroup : compatibleSubgroups) {
            if (subgroup.size() <= policy.getMaxGroupSize()) {
                // Fits within size limit
                result.add(createSubgroup(subgroup, pool, result.size() + 1));
            } else {
                // Need to split further by size
                List<Set<Agent>> sizeSplit = splitBySize(subgroup, policy, allAgents, pool);
                for (Set<Agent> split : sizeSplit) {
                    result.add(createSubgroup(split, pool, result.size() + 1));
                }
            }
        }
        
        return result;
    }

    /**
     * Split agents by compatibility matrix.
     */
    private List<Set<Agent>> splitByCompatibility(Set<Agent> agents, GroupingPolicy policy) {
        if (!policy.hasCompatibilityMatrix()) {
            return Collections.singletonList(new HashSet<>(agents));
        }
        
        // Build compatibility graph
        List<Agent> agentList = new ArrayList<>(agents);
        Map<String, Set<String>> compatGraph = new HashMap<>();
        
        for (int i = 0; i < agentList.size(); i++) {
            for (int j = i + 1; j < agentList.size(); j++) {
                Agent a = agentList.get(i);
                Agent b = agentList.get(j);
                if (policy.areCompatible(a, b)) {
                    compatGraph.computeIfAbsent(a.getId(), k -> new HashSet<>()).add(b.getId());
                    compatGraph.computeIfAbsent(b.getId(), k -> new HashSet<>()).add(a.getId());
                }
            }
        }
        
        // Find connected components in compatibility graph
        Map<String, Agent> agentMap = agentList.stream()
            .collect(Collectors.toMap(Agent::getId, a -> a));
        
        Set<String> visited = new HashSet<>();
        List<Set<Agent>> components = new ArrayList<>();
        
        for (Agent agent : agents) {
            if (visited.contains(agent.getId())) {
                continue;
            }
            
            Set<Agent> component = new HashSet<>();
            Queue<String> queue = new LinkedList<>();
            queue.add(agent.getId());
            
            while (!queue.isEmpty()) {
                String current = queue.poll();
                if (visited.contains(current)) {
                    continue;
                }
                visited.add(current);
                component.add(agentMap.get(current));
                
                Set<String> neighbors = compatGraph.getOrDefault(current, Collections.emptySet());
                for (String neighbor : neighbors) {
                    if (!visited.contains(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
            
            if (!component.isEmpty()) {
                components.add(component);
            }
        }
        
        return components;
    }

    /**
     * Split a set of agents to fit within size limit.
     */
    private List<Set<Agent>> splitBySize(Set<Agent> agents, GroupingPolicy policy,
                                         List<Agent> allAgents, ResourcePool pool) {
        int maxSize = policy.getMaxGroupSize();
        if (agents.size() <= maxSize) {
            return Collections.singletonList(agents);
        }
        
        switch (policy.getSplitStrategy()) {
            case MIN_CUT:
                return splitByMinCut(agents, maxSize, pool);
            case RESOURCE_AFFINITY:
                return splitByResourceAffinity(agents, maxSize);
            case PRIORITY_CLUSTERING:
                return splitByPriority(agents, maxSize);
            case SPECTRAL:
                return splitBySpectral(agents, maxSize, pool);
            case ROUND_ROBIN:
            default:
                return splitRoundRobin(agents, maxSize);
        }
    }

    /**
     * Split by minimizing edge cuts in contention graph.
     */
    private List<Set<Agent>> splitByMinCut(Set<Agent> agents, int maxSize, ResourcePool pool) {
        List<Agent> agentList = new ArrayList<>(agents);
        int numGroups = (int) Math.ceil((double) agents.size() / maxSize);
        
        // Build contention graph for this subset
        Map<String, Set<String>> contention = new HashMap<>();
        Map<ResourceType, List<Agent>> resourceDemand = new HashMap<>();
        
        for (Agent agent : agents) {
            for (ResourceType type : ResourceType.values()) {
                if (agent.getIdeal(type) > 0) {
                    resourceDemand.computeIfAbsent(type, k -> new ArrayList<>()).add(agent);
                }
            }
        }
        
        for (List<Agent> competing : resourceDemand.values()) {
            if (competing.size() > 1) {
                for (int i = 0; i < competing.size(); i++) {
                    for (int j = i + 1; j < competing.size(); j++) {
                        String id1 = competing.get(i).getId();
                        String id2 = competing.get(j).getId();
                        contention.computeIfAbsent(id1, k -> new HashSet<>()).add(id2);
                        contention.computeIfAbsent(id2, k -> new HashSet<>()).add(id1);
                    }
                }
            }
        }
        
        // Greedy min-cut partitioning
        List<Set<Agent>> partitions = new ArrayList<>();
        for (int i = 0; i < numGroups; i++) {
            partitions.add(new HashSet<>());
        }
        
        // Sort agents by degree (number of contentions) - high degree first
        agentList.sort((a, b) -> {
            int degA = contention.getOrDefault(a.getId(), Collections.emptySet()).size();
            int degB = contention.getOrDefault(b.getId(), Collections.emptySet()).size();
            return Integer.compare(degB, degA); // Descending
        });
        
        // Assign each agent to partition that minimizes cut edges
        for (Agent agent : agentList) {
            int bestPartition = 0;
            int minCut = Integer.MAX_VALUE;
            
            for (int p = 0; p < numGroups; p++) {
                if (partitions.get(p).size() >= maxSize) {
                    continue; // Partition full
                }
                
                // Count edges cut if we add agent to this partition
                Set<String> neighbors = contention.getOrDefault(agent.getId(), Collections.emptySet());
                int cut = 0;
                for (String neighborId : neighbors) {
                    boolean neighborInPartition = partitions.get(p).stream()
                        .anyMatch(a -> a.getId().equals(neighborId));
                    if (!neighborInPartition) {
                        cut++;
                    }
                }
                
                if (cut < minCut) {
                    minCut = cut;
                    bestPartition = p;
                }
            }
            
            partitions.get(bestPartition).add(agent);
        }
        
        // Remove empty partitions
        partitions.removeIf(Set::isEmpty);
        return partitions;
    }

    /**
     * Split by resource type affinity - group agents with similar resource needs.
     */
    private List<Set<Agent>> splitByResourceAffinity(Set<Agent> agents, int maxSize) {
        List<Agent> agentList = new ArrayList<>(agents);
        
        // Calculate resource affinity vector for each agent
        Map<String, double[]> affinityVectors = new HashMap<>();
        ResourceType[] types = ResourceType.values();
        
        for (Agent agent : agents) {
            double[] vector = new double[types.length];
            double total = 0;
            for (int i = 0; i < types.length; i++) {
                vector[i] = agent.getIdeal(types[i]);
                total += vector[i];
            }
            // Normalize
            if (total > 0) {
                for (int i = 0; i < types.length; i++) {
                    vector[i] /= total;
                }
            }
            affinityVectors.put(agent.getId(), vector);
        }
        
        // K-means-like clustering
        int numGroups = (int) Math.ceil((double) agents.size() / maxSize);
        List<Set<Agent>> partitions = new ArrayList<>();
        
        // Initialize centroids by picking diverse agents
        List<double[]> centroids = new ArrayList<>();
        List<Agent> remaining = new ArrayList<>(agentList);
        
        for (int i = 0; i < numGroups && !remaining.isEmpty(); i++) {
            if (i == 0) {
                // First centroid: random
                Agent first = remaining.remove(0);
                centroids.add(affinityVectors.get(first.getId()));
                partitions.add(new HashSet<>());
            } else {
                // Subsequent centroids: maximize distance from existing
                double maxDist = -1;
                int bestIdx = 0;
                for (int j = 0; j < remaining.size(); j++) {
                    double[] vec = affinityVectors.get(remaining.get(j).getId());
                    double minDistToCentroid = Double.MAX_VALUE;
                    for (double[] centroid : centroids) {
                        double dist = euclideanDistance(vec, centroid);
                        minDistToCentroid = Math.min(minDistToCentroid, dist);
                    }
                    if (minDistToCentroid > maxDist) {
                        maxDist = minDistToCentroid;
                        bestIdx = j;
                    }
                }
                centroids.add(affinityVectors.get(remaining.remove(bestIdx).getId()));
                partitions.add(new HashSet<>());
            }
        }
        
        // Assign all agents to nearest centroid (respecting size limit)
        agentList.sort((a, b) -> {
            double[] vecA = affinityVectors.get(a.getId());
            double[] vecB = affinityVectors.get(b.getId());
            double minDistA = Double.MAX_VALUE, minDistB = Double.MAX_VALUE;
            for (double[] c : centroids) {
                minDistA = Math.min(minDistA, euclideanDistance(vecA, c));
                minDistB = Math.min(minDistB, euclideanDistance(vecB, c));
            }
            return Double.compare(minDistA, minDistB);
        });
        
        for (Agent agent : agentList) {
            double[] vec = affinityVectors.get(agent.getId());
            int bestPartition = 0;
            double minDist = Double.MAX_VALUE;
            
            for (int p = 0; p < centroids.size(); p++) {
                if (partitions.get(p).size() >= maxSize) {
                    continue;
                }
                double dist = euclideanDistance(vec, centroids.get(p));
                if (dist < minDist) {
                    minDist = dist;
                    bestPartition = p;
                }
            }
            
            partitions.get(bestPartition).add(agent);
        }
        
        partitions.removeIf(Set::isEmpty);
        return partitions;
    }

    /**
     * Split by priority/currency levels.
     */
    private List<Set<Agent>> splitByPriority(Set<Agent> agents, int maxSize) {
        List<Agent> sorted = new ArrayList<>(agents);
        sorted.sort((a, b) -> {
            // Sort by currency balance (higher first)
            return b.getCurrencyBalance().compareTo(a.getCurrencyBalance());
        });
        
        return splitIntoChunks(sorted, maxSize);
    }

    /**
     * Split using spectral clustering on contention graph.
     */
    private List<Set<Agent>> splitBySpectral(Set<Agent> agents, int maxSize, ResourcePool pool) {
        // Spectral clustering is expensive - for small groups, use min-cut
        if (agents.size() <= 50) {
            return splitByMinCut(agents, maxSize, pool);
        }
        
        // For larger groups, use a simplified spectral approach
        List<Agent> agentList = new ArrayList<>(agents);
        int numGroups = (int) Math.ceil((double) agents.size() / maxSize);
        
        // Build adjacency matrix
        Map<String, Integer> idxMap = new HashMap<>();
        for (int i = 0; i < agentList.size(); i++) {
            idxMap.put(agentList.get(i).getId(), i);
        }
        
        double[][] adjacency = new double[agentList.size()][agentList.size()];
        Map<ResourceType, List<Integer>> resourceAgents = new HashMap<>();
        
        for (int i = 0; i < agentList.size(); i++) {
            Agent agent = agentList.get(i);
            for (ResourceType type : ResourceType.values()) {
                if (agent.getIdeal(type) > 0) {
                    resourceAgents.computeIfAbsent(type, k -> new ArrayList<>()).add(i);
                }
            }
        }
        
        for (List<Integer> competing : resourceAgents.values()) {
            for (int i = 0; i < competing.size(); i++) {
                for (int j = i + 1; j < competing.size(); j++) {
                    int idx1 = competing.get(i);
                    int idx2 = competing.get(j);
                    adjacency[idx1][idx2] = 1.0;
                    adjacency[idx2][idx1] = 1.0;
                }
            }
        }
        
        // Compute degree and Laplacian
        double[] degree = new double[agentList.size()];
        for (int i = 0; i < agentList.size(); i++) {
            for (int j = 0; j < agentList.size(); j++) {
                degree[i] += adjacency[i][j];
            }
        }
        
        // Use Fiedler vector approximation via power iteration
        double[] fiedler = approximateFiedlerVector(adjacency, degree, agentList.size());
        
        // Sort by Fiedler vector and partition
        Integer[] indices = new Integer[agentList.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i;
        }
        Arrays.sort(indices, (a, b) -> Double.compare(fiedler[a], fiedler[b]));
        
        List<Agent> sorted = new ArrayList<>();
        for (int idx : indices) {
            sorted.add(agentList.get(idx));
        }
        
        return splitIntoChunks(sorted, maxSize);
    }

    /**
     * Simple round-robin split.
     */
    private List<Set<Agent>> splitRoundRobin(Set<Agent> agents, int maxSize) {
        return splitIntoChunks(new ArrayList<>(agents), maxSize);
    }

    /**
     * Split a list into chunks of maxSize.
     */
    private List<Set<Agent>> splitIntoChunks(List<Agent> agents, int maxSize) {
        List<Set<Agent>> result = new ArrayList<>();
        Set<Agent> current = new HashSet<>();
        
        for (Agent agent : agents) {
            current.add(agent);
            if (current.size() >= maxSize) {
                result.add(current);
                current = new HashSet<>();
            }
        }
        
        if (!current.isEmpty()) {
            result.add(current);
        }
        
        return result;
    }

    /**
     * Create a ContentionGroup from a set of agents.
     */
    private ContentionDetector.ContentionGroup createSubgroup(
            Set<Agent> agents, ResourcePool pool, int groupNum) {
        
        Set<ResourceType> resources = new HashSet<>();
        for (Agent agent : agents) {
            for (ResourceType type : ResourceType.values()) {
                if (agent.getIdeal(type) > 0) {
                    resources.add(type);
                }
            }
        }
        
        Map<ResourceType, Long> available = new HashMap<>();
        for (ResourceType type : resources) {
            available.put(type, pool.getAvailable(type));
        }
        
        return new ContentionDetector.ContentionGroup(
            "CG-" + groupNum,
            agents,
            resources,
            available
        );
    }

    // ========================================================================
    // K-Hop Graph Operations
    // ========================================================================

    /**
     * Build contention graph from agents.
     */
    private Map<String, Set<String>> buildContentionGraph(List<Agent> agents, ResourcePool pool) {
        Map<String, Set<String>> graph = new HashMap<>();
        Map<ResourceType, List<Agent>> resourceDemand = new HashMap<>();
        
        for (Agent agent : agents) {
            graph.put(agent.getId(), new HashSet<>());
            for (ResourceType type : ResourceType.values()) {
                if (agent.getIdeal(type) > 0) {
                    resourceDemand.computeIfAbsent(type, k -> new ArrayList<>()).add(agent);
                }
            }
        }
        
        for (Map.Entry<ResourceType, List<Agent>> entry : resourceDemand.entrySet()) {
            ResourceType type = entry.getKey();
            List<Agent> competing = entry.getValue();
            
            long demand = competing.stream().mapToLong(a -> a.getIdeal(type)).sum();
            long supply = pool.getAvailable(type);
            
            if (demand > supply && competing.size() > 1) {
                for (int i = 0; i < competing.size(); i++) {
                    for (int j = i + 1; j < competing.size(); j++) {
                        String id1 = competing.get(i).getId();
                        String id2 = competing.get(j).getId();
                        graph.get(id1).add(id2);
                        graph.get(id2).add(id1);
                    }
                }
            }
        }
        
        return graph;
    }

    /**
     * Find groups where all pairs of agents are within k hops of each other.
     * 
     * This is more restrictive than merging overlapping k-hop neighborhoods.
     * For a chain A-B-C-D-E with k=1:
     * - Old behavior: All merged into one group (A overlaps B, B overlaps C, etc.)
     * - New behavior: Groups respect k-hop constraint between all pairs
     */
    private List<Set<String>> findKHopConstrainedGroups(Map<String, Set<String>> graph, int k) {
        // Compute pairwise distances
        Map<String, Map<String, Integer>> distances = computeAllPairDistances(graph);
        
        // Use greedy algorithm to form groups
        Set<String> unassigned = new HashSet<>(graph.keySet());
        List<Set<String>> groups = new ArrayList<>();
        
        while (!unassigned.isEmpty()) {
            // Start with an unassigned agent
            String seed = unassigned.iterator().next();
            Set<String> group = new HashSet<>();
            group.add(seed);
            unassigned.remove(seed);
            
            // Add other agents that are within k hops of ALL current group members
            boolean changed = true;
            while (changed) {
                changed = false;
                for (String candidate : new ArrayList<>(unassigned)) {
                    // Check if candidate is within k hops of all group members
                    boolean withinK = true;
                    for (String member : group) {
                        Map<String, Integer> memberDist = distances.get(member);
                        int dist = memberDist != null ? memberDist.getOrDefault(candidate, Integer.MAX_VALUE) : Integer.MAX_VALUE;
                        if (dist > k) {
                            withinK = false;
                            break;
                        }
                    }
                    
                    if (withinK) {
                        group.add(candidate);
                        unassigned.remove(candidate);
                        changed = true;
                    }
                }
            }
            
            groups.add(group);
        }
        
        return groups;
    }

    /**
     * Compute all-pairs shortest path distances using BFS.
     */
    private Map<String, Map<String, Integer>> computeAllPairDistances(Map<String, Set<String>> graph) {
        Map<String, Map<String, Integer>> distances = new HashMap<>();
        
        for (String start : graph.keySet()) {
            Map<String, Integer> distFromStart = new HashMap<>();
            Queue<String> queue = new LinkedList<>();
            queue.add(start);
            distFromStart.put(start, 0);
            
            while (!queue.isEmpty()) {
                String current = queue.poll();
                int currentDist = distFromStart.get(current);
                
                for (String neighbor : graph.getOrDefault(current, Collections.emptySet())) {
                    if (!distFromStart.containsKey(neighbor)) {
                        distFromStart.put(neighbor, currentDist + 1);
                        queue.add(neighbor);
                    }
                }
            }
            
            distances.put(start, distFromStart);
        }
        
        return distances;
    }

    /**
     * Find connected components in the graph.
     */
    private List<Set<String>> findConnectedComponents(Map<String, Set<String>> graph) {
        Set<String> visited = new HashSet<>();
        List<Set<String>> components = new ArrayList<>();
        
        for (String node : graph.keySet()) {
            if (visited.contains(node)) continue;
            
            Set<String> component = new HashSet<>();
            Queue<String> queue = new LinkedList<>();
            queue.add(node);
            
            while (!queue.isEmpty()) {
                String current = queue.poll();
                if (visited.contains(current)) continue;
                visited.add(current);
                component.add(current);
                
                for (String neighbor : graph.getOrDefault(current, Collections.emptySet())) {
                    if (!visited.contains(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
            
            components.add(component);
        }
        
        return components;
    }

    /**
     * Apply compatibility filter to neighborhoods.
     */
    private List<Set<String>> applyCompatibilityFilter(List<Set<String>> neighborhoods, 
                                                        GroupingPolicy policy) {
        List<Set<String>> filtered = new ArrayList<>();
        
        for (Set<String> neighborhood : neighborhoods) {
            // Split neighborhood by compatibility
            List<Set<String>> compatible = splitByCompatibilityIds(neighborhood, policy);
            filtered.addAll(compatible);
        }
        
        return filtered;
    }

    private List<Set<String>> splitByCompatibilityIds(Set<String> ids, GroupingPolicy policy) {
        if (!policy.hasCompatibilityMatrix()) {
            return Collections.singletonList(ids);
        }
        
        // Build compatibility graph
        Map<String, Set<String>> compatGraph = new HashMap<>();
        List<String> idList = new ArrayList<>(ids);
        
        for (int i = 0; i < idList.size(); i++) {
            for (int j = i + 1; j < idList.size(); j++) {
                String a = idList.get(i);
                String b = idList.get(j);
                if (policy.areCompatible(a, b)) {
                    compatGraph.computeIfAbsent(a, k -> new HashSet<>()).add(b);
                    compatGraph.computeIfAbsent(b, k -> new HashSet<>()).add(a);
                }
            }
        }
        
        // Find connected components
        Set<String> visited = new HashSet<>();
        List<Set<String>> components = new ArrayList<>();
        
        for (String id : ids) {
            if (visited.contains(id)) continue;
            
            Set<String> component = new HashSet<>();
            Queue<String> queue = new LinkedList<>();
            queue.add(id);
            
            while (!queue.isEmpty()) {
                String current = queue.poll();
                if (visited.contains(current)) continue;
                visited.add(current);
                component.add(current);
                
                for (String neighbor : compatGraph.getOrDefault(current, Collections.emptySet())) {
                    if (!visited.contains(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
            
            if (!component.isEmpty()) {
                components.add(component);
            }
        }
        
        return components;
    }

    /**
     * Apply size limit to all groups.
     */
    private List<ContentionDetector.ContentionGroup> applySizeLimit(
            List<ContentionDetector.ContentionGroup> groups,
            List<Agent> allAgents,
            ResourcePool pool) {
        
        List<ContentionDetector.ContentionGroup> result = new ArrayList<>();
        int groupNum = 0;
        
        for (ContentionDetector.ContentionGroup group : groups) {
            if (group.getAgentCount() <= policy.getMaxGroupSize()) {
                result.add(group);
            } else {
                List<Set<Agent>> splits = splitBySize(
                    group.getAgents(), policy, allAgents, pool);
                for (Set<Agent> split : splits) {
                    result.add(createSubgroup(split, pool, ++groupNum));
                }
            }
        }
        
        return result;
    }

    // ========================================================================
    // Math Utilities
    // ========================================================================

    private double euclideanDistance(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    /**
     * Approximate Fiedler vector using power iteration on normalized Laplacian.
     */
    private double[] approximateFiedlerVector(double[][] adjacency, double[] degree, int n) {
        double[] v = new double[n];
        double[] ones = new double[n];
        
        // Initialize with random values
        Random rand = new Random(42);
        for (int i = 0; i < n; i++) {
            v[i] = rand.nextDouble();
            ones[i] = 1.0;
        }
        normalize(v);
        
        // Power iteration to find second smallest eigenvector
        for (int iter = 0; iter < 50; iter++) {
            double[] newV = new double[n];
            
            // Apply Laplacian: L = D - A
            for (int i = 0; i < n; i++) {
                newV[i] = degree[i] * v[i];
                for (int j = 0; j < n; j++) {
                    newV[i] -= adjacency[i][j] * v[j];
                }
            }
            
            // Orthogonalize against constant vector (first eigenvector)
            double dot = 0;
            for (int i = 0; i < n; i++) {
                dot += newV[i] * ones[i];
            }
            dot /= n;
            for (int i = 0; i < n; i++) {
                newV[i] -= dot;
            }
            
            normalize(newV);
            v = newV;
        }
        
        return v;
    }

    private void normalize(double[] v) {
        double norm = 0;
        for (double x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) {
                v[i] /= norm;
            }
        }
    }

    // ========================================================================
    // Analysis Methods
    // ========================================================================

    /**
     * Analyze the impact of a policy on a set of agents.
     */
    public PolicyAnalysis analyzePolicy(List<Agent> agents, ResourcePool pool) {
        // Original detection (no policy)
        ContentionDetector detector = new ContentionDetector();
        List<ContentionDetector.ContentionGroup> original = detector.detectContentions(agents, pool);
        
        // Detection with policy
        List<ContentionDetector.ContentionGroup> withPolicy = detectWithPolicy(agents, pool);
        
        // Calculate metrics
        int originalGroups = original.size();
        int policyGroups = withPolicy.size();
        
        int originalMaxSize = original.stream()
            .mapToInt(ContentionDetector.ContentionGroup::getAgentCount)
            .max().orElse(0);
        int policyMaxSize = withPolicy.stream()
            .mapToInt(ContentionDetector.ContentionGroup::getAgentCount)
            .max().orElse(0);
        
        // Estimate edge cuts (loss of optimality potential)
        int edgesCut = estimateEdgesCut(original, withPolicy, agents, pool);
        
        return new PolicyAnalysis(
            policy,
            originalGroups,
            policyGroups,
            originalMaxSize,
            policyMaxSize,
            edgesCut,
            original,
            withPolicy
        );
    }

    private int estimateEdgesCut(
            List<ContentionDetector.ContentionGroup> original,
            List<ContentionDetector.ContentionGroup> withPolicy,
            List<Agent> agents,
            ResourcePool pool) {
        
        // Build original grouping map
        Map<String, String> originalGrouping = new HashMap<>();
        for (ContentionDetector.ContentionGroup group : original) {
            for (Agent agent : group.getAgents()) {
                originalGrouping.put(agent.getId(), group.getGroupId());
            }
        }
        
        // Build policy grouping map
        Map<String, String> policyGrouping = new HashMap<>();
        for (ContentionDetector.ContentionGroup group : withPolicy) {
            for (Agent agent : group.getAgents()) {
                policyGrouping.put(agent.getId(), group.getGroupId());
            }
        }
        
        // Count edges that were in same original group but different policy groups
        Map<String, Set<String>> contention = buildContentionGraph(agents, pool);
        int cuts = 0;
        
        for (Map.Entry<String, Set<String>> entry : contention.entrySet()) {
            String agentA = entry.getKey();
            for (String agentB : entry.getValue()) {
                if (agentA.compareTo(agentB) < 0) { // Count each edge once
                    String origGroupA = originalGrouping.get(agentA);
                    String origGroupB = originalGrouping.get(agentB);
                    String policyGroupA = policyGrouping.get(agentA);
                    String policyGroupB = policyGrouping.get(agentB);
                    
                    // If same original group but different policy groups, edge is cut
                    if (origGroupA != null && origGroupA.equals(origGroupB)) {
                        if (policyGroupA == null || !policyGroupA.equals(policyGroupB)) {
                            cuts++;
                        }
                    }
                }
            }
        }
        
        return cuts;
    }

    /**
     * Analysis results for a grouping policy.
     */
    public static class PolicyAnalysis {
        public final GroupingPolicy policy;
        public final int originalGroupCount;
        public final int policyGroupCount;
        public final int originalMaxSize;
        public final int policyMaxSize;
        public final int edgesCut;
        public final List<ContentionDetector.ContentionGroup> originalGroups;
        public final List<ContentionDetector.ContentionGroup> policyGroups;

        public PolicyAnalysis(GroupingPolicy policy, int originalGroupCount, int policyGroupCount,
                            int originalMaxSize, int policyMaxSize, int edgesCut,
                            List<ContentionDetector.ContentionGroup> originalGroups,
                            List<ContentionDetector.ContentionGroup> policyGroups) {
            this.policy = policy;
            this.originalGroupCount = originalGroupCount;
            this.policyGroupCount = policyGroupCount;
            this.originalMaxSize = originalMaxSize;
            this.policyMaxSize = policyMaxSize;
            this.edgesCut = edgesCut;
            this.originalGroups = originalGroups;
            this.policyGroups = policyGroups;
        }

        /**
         * Estimate performance improvement factor.
         * Based on O(n³) complexity reduction.
         */
        public double getPerformanceImprovementFactor() {
            if (originalMaxSize <= 0 || policyMaxSize <= 0) return 1.0;
            // O(n³) → improvement is ratio of cubes
            return Math.pow((double) originalMaxSize / policyMaxSize, 3);
        }

        /**
         * Estimate optimality loss (rough approximation).
         */
        public double getEstimatedOptimalityLoss() {
            if (originalGroupCount == 0) return 0.0;
            // Each cut edge represents a potential trade opportunity lost
            // This is a rough approximation
            int totalOriginalEdges = 0;
            for (ContentionDetector.ContentionGroup g : originalGroups) {
                int n = g.getAgentCount();
                totalOriginalEdges += n * (n - 1) / 2; // Upper bound
            }
            if (totalOriginalEdges == 0) return 0.0;
            return Math.min(1.0, (double) edgesCut / totalOriginalEdges);
        }

        @Override
        public String toString() {
            return String.format(
                "PolicyAnalysis[policy=%s, groups: %d→%d, maxSize: %d→%d, edgesCut=%d, " +
                "perfImprovement=%.1fx, optimalityLoss=%.1f%%]",
                policy, originalGroupCount, policyGroupCount,
                originalMaxSize, policyMaxSize, edgesCut,
                getPerformanceImprovementFactor(),
                getEstimatedOptimalityLoss() * 100);
        }
    }
}
