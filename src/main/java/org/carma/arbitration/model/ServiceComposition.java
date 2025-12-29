package org.carma.arbitration.model;

import java.util.*;

/**
 * Represents a composition of AI services forming a directed acyclic graph (DAG).
 * 
 * Agents define compositions to accomplish compound tasks by chaining narrow AI services.
 * For example: IMAGE_ANALYSIS → TEXT_GENERATION → TEXT_TO_SPEECH
 * 
 * The composition validates:
 * - Type compatibility between connected services
 * - Acyclicity of the graph
 * - Resource requirements aggregation
 */
public class ServiceComposition {

    private final String compositionId;
    private final String name;
    private final String description;
    private final Map<String, CompositionNode> nodes;
    private final List<CompositionEdge> edges;
    private final Set<String> entryNodes;
    private final Set<String> exitNodes;
    private final long createdAtMs;
    private CompositionStatus status;

    /**
     * Status of the composition.
     */
    public enum CompositionStatus {
        DRAFT,      // Being constructed
        VALIDATED,  // Passed validation checks
        ACTIVE,     // Currently in use
        SUSPENDED,  // Temporarily disabled
        RETIRED     // No longer available
    }

    /**
     * A node in the composition graph representing a service invocation.
     */
    public static class CompositionNode {
        private final String nodeId;
        private final ServiceType serviceType;
        private final Map<String, Object> parameters;
        private final int parallelism;
        private String assignedServiceId;

        public CompositionNode(String nodeId, ServiceType serviceType, 
                              Map<String, Object> parameters, int parallelism) {
            this.nodeId = nodeId;
            this.serviceType = serviceType;
            this.parameters = parameters != null ? new HashMap<>(parameters) : new HashMap<>();
            this.parallelism = parallelism;
        }

        public CompositionNode(String nodeId, ServiceType serviceType) {
            this(nodeId, serviceType, null, 1);
        }

        public String getNodeId() { return nodeId; }
        public ServiceType getServiceType() { return serviceType; }
        public Map<String, Object> getParameters() { return Collections.unmodifiableMap(parameters); }
        public int getParallelism() { return parallelism; }
        public String getAssignedServiceId() { return assignedServiceId; }
        
        public void setAssignedServiceId(String serviceId) {
            this.assignedServiceId = serviceId;
        }

        @Override
        public String toString() {
            return String.format("Node[%s: %s x%d]", nodeId, serviceType.name(), parallelism);
        }
    }

    /**
     * An edge connecting two nodes, representing data flow.
     */
    public static class CompositionEdge {
        private final String fromNodeId;
        private final String toNodeId;
        private final ServiceType.DataType dataType;
        private final String label;

        public CompositionEdge(String fromNodeId, String toNodeId, 
                              ServiceType.DataType dataType, String label) {
            this.fromNodeId = fromNodeId;
            this.toNodeId = toNodeId;
            this.dataType = dataType;
            this.label = label;
        }

        public CompositionEdge(String fromNodeId, String toNodeId, ServiceType.DataType dataType) {
            this(fromNodeId, toNodeId, dataType, null);
        }

        public String getFromNodeId() { return fromNodeId; }
        public String getToNodeId() { return toNodeId; }
        public ServiceType.DataType getDataType() { return dataType; }
        public String getLabel() { return label; }

        @Override
        public String toString() {
            return String.format("%s -[%s]-> %s", fromNodeId, dataType, toNodeId);
        }
    }

    /**
     * Result of composition validation.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;
        private final List<String> warnings;

        public ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
            this.valid = valid;
            this.errors = new ArrayList<>(errors);
            this.warnings = new ArrayList<>(warnings);
        }

        public static ValidationResult success() {
            return new ValidationResult(true, Collections.emptyList(), Collections.emptyList());
        }

        public static ValidationResult success(List<String> warnings) {
            return new ValidationResult(true, Collections.emptyList(), warnings);
        }

        public static ValidationResult failure(List<String> errors) {
            return new ValidationResult(false, errors, Collections.emptyList());
        }

        public boolean isValid() { return valid; }
        public List<String> getErrors() { return Collections.unmodifiableList(errors); }
        public List<String> getWarnings() { return Collections.unmodifiableList(warnings); }

        @Override
        public String toString() {
            if (valid) {
                return warnings.isEmpty() ? "Valid" : "Valid with warnings: " + warnings;
            }
            return "Invalid: " + errors;
        }
    }

    /**
     * Builder for ServiceComposition.
     */
    public static class Builder {
        private final String compositionId;
        private String name;
        private String description = "";
        private final Map<String, CompositionNode> nodes = new LinkedHashMap<>();
        private final List<CompositionEdge> edges = new ArrayList<>();

        public Builder(String compositionId) {
            this.compositionId = compositionId;
            this.name = compositionId;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder addNode(String nodeId, ServiceType type) {
            nodes.put(nodeId, new CompositionNode(nodeId, type));
            return this;
        }

        public Builder addNode(String nodeId, ServiceType type, int parallelism) {
            nodes.put(nodeId, new CompositionNode(nodeId, type, null, parallelism));
            return this;
        }

        public Builder addNode(String nodeId, ServiceType type, Map<String, Object> params) {
            nodes.put(nodeId, new CompositionNode(nodeId, type, params, 1));
            return this;
        }

        public Builder addNode(CompositionNode node) {
            nodes.put(node.getNodeId(), node);
            return this;
        }

        public Builder connect(String fromNodeId, String toNodeId, ServiceType.DataType dataType) {
            edges.add(new CompositionEdge(fromNodeId, toNodeId, dataType));
            return this;
        }

        public Builder connect(String fromNodeId, String toNodeId) {
            // Auto-detect compatible data type
            CompositionNode from = nodes.get(fromNodeId);
            CompositionNode to = nodes.get(toNodeId);
            if (from != null && to != null) {
                Set<ServiceType.DataType> compatible = to.getServiceType()
                    .getCompatibleInputsFrom(from.getServiceType());
                if (!compatible.isEmpty()) {
                    ServiceType.DataType dataType = compatible.iterator().next();
                    edges.add(new CompositionEdge(fromNodeId, toNodeId, dataType));
                }
            }
            return this;
        }

        public ServiceComposition build() {
            return new ServiceComposition(this);
        }
    }

    private ServiceComposition(Builder builder) {
        this.compositionId = builder.compositionId;
        this.name = builder.name;
        this.description = builder.description;
        this.nodes = new LinkedHashMap<>(builder.nodes);
        this.edges = new ArrayList<>(builder.edges);
        this.createdAtMs = System.currentTimeMillis();
        this.status = CompositionStatus.DRAFT;

        // Compute entry and exit nodes
        Set<String> hasIncoming = new HashSet<>();
        Set<String> hasOutgoing = new HashSet<>();
        for (CompositionEdge edge : edges) {
            hasIncoming.add(edge.getToNodeId());
            hasOutgoing.add(edge.getFromNodeId());
        }

        this.entryNodes = new HashSet<>();
        this.exitNodes = new HashSet<>();
        for (String nodeId : nodes.keySet()) {
            if (!hasIncoming.contains(nodeId)) {
                entryNodes.add(nodeId);
            }
            if (!hasOutgoing.contains(nodeId)) {
                exitNodes.add(nodeId);
            }
        }
    }

    // ========================================================================
    // Factory Methods for Common Patterns
    // ========================================================================

    /**
     * Create a simple linear chain composition.
     */
    public static ServiceComposition linearChain(String id, ServiceType... services) {
        Builder builder = new Builder(id).name("Linear Chain: " + id);
        
        String prevNodeId = null;
        for (int i = 0; i < services.length; i++) {
            String nodeId = "node_" + i;
            builder.addNode(nodeId, services[i]);
            if (prevNodeId != null) {
                builder.connect(prevNodeId, nodeId);
            }
            prevNodeId = nodeId;
        }
        
        return builder.build();
    }

    /**
     * Create a fan-out composition (one input, multiple parallel outputs).
     */
    public static ServiceComposition fanOut(String id, ServiceType source, ServiceType... targets) {
        Builder builder = new Builder(id).name("Fan-out: " + id);
        builder.addNode("source", source);
        
        for (int i = 0; i < targets.length; i++) {
            String targetId = "target_" + i;
            builder.addNode(targetId, targets[i]);
            builder.connect("source", targetId);
        }
        
        return builder.build();
    }

    /**
     * Create a fan-in composition (multiple inputs, one aggregating output).
     */
    public static ServiceComposition fanIn(String id, ServiceType aggregator, ServiceType... sources) {
        Builder builder = new Builder(id).name("Fan-in: " + id);
        builder.addNode("aggregator", aggregator);
        
        for (int i = 0; i < sources.length; i++) {
            String sourceId = "source_" + i;
            builder.addNode(sourceId, sources[i]);
            builder.connect(sourceId, "aggregator");
        }
        
        return builder.build();
    }

    // ========================================================================
    // Accessors
    // ========================================================================

    public String getCompositionId() { return compositionId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Map<String, CompositionNode> getNodes() { return Collections.unmodifiableMap(nodes); }
    public List<CompositionEdge> getEdges() { return Collections.unmodifiableList(edges); }
    public Set<String> getEntryNodes() { return Collections.unmodifiableSet(entryNodes); }
    public Set<String> getExitNodes() { return Collections.unmodifiableSet(exitNodes); }
    public long getCreatedAtMs() { return createdAtMs; }
    public CompositionStatus getStatus() { return status; }

    public CompositionNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public int getNodeCount() {
        return nodes.size();
    }

    public int getEdgeCount() {
        return edges.size();
    }

    // ========================================================================
    // Validation
    // ========================================================================

    /**
     * Validate the composition for correctness.
     */
    public ValidationResult validate() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Check for empty composition
        if (nodes.isEmpty()) {
            errors.add("Composition has no nodes");
            return ValidationResult.failure(errors);
        }

        // Check for orphan nodes (nodes referenced in edges but not defined)
        for (CompositionEdge edge : edges) {
            if (!nodes.containsKey(edge.getFromNodeId())) {
                errors.add("Edge references undefined source node: " + edge.getFromNodeId());
            }
            if (!nodes.containsKey(edge.getToNodeId())) {
                errors.add("Edge references undefined target node: " + edge.getToNodeId());
            }
        }

        // Check type compatibility
        for (CompositionEdge edge : edges) {
            CompositionNode from = nodes.get(edge.getFromNodeId());
            CompositionNode to = nodes.get(edge.getToNodeId());
            if (from != null && to != null) {
                if (!to.getServiceType().canAcceptOutputFrom(from.getServiceType())) {
                    errors.add(String.format("Type incompatibility: %s cannot accept output from %s",
                        to.getServiceType(), from.getServiceType()));
                }
                // Check if specified data type is valid
                if (edge.getDataType() != null) {
                    if (!from.getServiceType().getOutputTypes().contains(edge.getDataType())) {
                        errors.add(String.format("Service %s does not output %s",
                            from.getServiceType(), edge.getDataType()));
                    }
                    if (!to.getServiceType().getInputTypes().contains(edge.getDataType())) {
                        errors.add(String.format("Service %s cannot accept %s",
                            to.getServiceType(), edge.getDataType()));
                    }
                }
            }
        }

        // Check for cycles using DFS
        if (hasCycle()) {
            errors.add("Composition contains a cycle");
        }

        // Check for disconnected nodes
        Set<String> reachable = new HashSet<>();
        for (String entry : entryNodes) {
            collectReachable(entry, reachable);
        }
        for (String nodeId : nodes.keySet()) {
            if (!reachable.contains(nodeId) && !entryNodes.contains(nodeId)) {
                warnings.add("Node " + nodeId + " is not reachable from any entry node");
            }
        }

        // Check for missing entry/exit nodes
        if (entryNodes.isEmpty()) {
            errors.add("Composition has no entry nodes");
        }
        if (exitNodes.isEmpty()) {
            errors.add("Composition has no exit nodes");
        }

        if (!errors.isEmpty()) {
            return ValidationResult.failure(errors);
        }

        this.status = CompositionStatus.VALIDATED;
        return ValidationResult.success(warnings);
    }

    private void collectReachable(String nodeId, Set<String> reachable) {
        if (reachable.contains(nodeId)) return;
        reachable.add(nodeId);
        for (CompositionEdge edge : edges) {
            if (edge.getFromNodeId().equals(nodeId)) {
                collectReachable(edge.getToNodeId(), reachable);
            }
        }
    }

    private boolean hasCycle() {
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();

        for (String nodeId : nodes.keySet()) {
            if (hasCycleDFS(nodeId, visited, recursionStack)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCycleDFS(String nodeId, Set<String> visited, Set<String> recursionStack) {
        if (recursionStack.contains(nodeId)) {
            return true;
        }
        if (visited.contains(nodeId)) {
            return false;
        }

        visited.add(nodeId);
        recursionStack.add(nodeId);

        for (CompositionEdge edge : edges) {
            if (edge.getFromNodeId().equals(nodeId)) {
                if (hasCycleDFS(edge.getToNodeId(), visited, recursionStack)) {
                    return true;
                }
            }
        }

        recursionStack.remove(nodeId);
        return false;
    }

    // ========================================================================
    // Resource Calculation
    // ========================================================================

    /**
     * Calculate total resource requirements for this composition.
     */
    public Map<ResourceType, Long> calculateTotalResourceRequirements() {
        Map<ResourceType, Long> total = new HashMap<>();
        
        for (CompositionNode node : nodes.values()) {
            Map<ResourceType, Long> nodeReqs = node.getServiceType().getDefaultResourceRequirements();
            int parallelism = node.getParallelism();
            
            for (var entry : nodeReqs.entrySet()) {
                total.merge(entry.getKey(), entry.getValue() * parallelism, Long::sum);
            }
        }
        
        return total;
    }

    /**
     * Estimate total latency for sequential execution path.
     */
    public int estimateCriticalPathLatencyMs() {
        Map<String, Integer> latencyTo = new HashMap<>();
        
        // Initialize entry nodes
        for (String entry : entryNodes) {
            latencyTo.put(entry, nodes.get(entry).getServiceType().getBaseLatencyMs());
        }
        
        // Topological order processing
        List<String> order = topologicalSort();
        for (String nodeId : order) {
            if (entryNodes.contains(nodeId)) continue;
            
            int maxPredLatency = 0;
            for (CompositionEdge edge : edges) {
                if (edge.getToNodeId().equals(nodeId)) {
                    int predLatency = latencyTo.getOrDefault(edge.getFromNodeId(), 0);
                    maxPredLatency = Math.max(maxPredLatency, predLatency);
                }
            }
            
            int nodeLatency = nodes.get(nodeId).getServiceType().getBaseLatencyMs();
            latencyTo.put(nodeId, maxPredLatency + nodeLatency);
        }
        
        // Find max latency to any exit node
        int maxLatency = 0;
        for (String exit : exitNodes) {
            maxLatency = Math.max(maxLatency, latencyTo.getOrDefault(exit, 0));
        }
        
        return maxLatency;
    }

    /**
     * Get topological ordering of nodes.
     */
    public List<String> topologicalSort() {
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        
        for (String nodeId : nodes.keySet()) {
            topologicalSortDFS(nodeId, visited, result);
        }
        
        Collections.reverse(result);
        return result;
    }

    private void topologicalSortDFS(String nodeId, Set<String> visited, List<String> result) {
        if (visited.contains(nodeId)) return;
        visited.add(nodeId);
        
        for (CompositionEdge edge : edges) {
            if (edge.getFromNodeId().equals(nodeId)) {
                topologicalSortDFS(edge.getToNodeId(), visited, result);
            }
        }
        
        result.add(nodeId);
    }

    // ========================================================================
    // State Management
    // ========================================================================

    public void setStatus(CompositionStatus status) {
        this.status = status;
    }

    public void activate() {
        if (this.status == CompositionStatus.VALIDATED || this.status == CompositionStatus.SUSPENDED) {
            this.status = CompositionStatus.ACTIVE;
        }
    }

    public void suspend() {
        if (this.status == CompositionStatus.ACTIVE) {
            this.status = CompositionStatus.SUSPENDED;
        }
    }

    public void retire() {
        this.status = CompositionStatus.RETIRED;
    }

    // ========================================================================
    // Object Methods
    // ========================================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServiceComposition that = (ServiceComposition) o;
        return Objects.equals(compositionId, that.compositionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(compositionId);
    }

    @Override
    public String toString() {
        return String.format("ServiceComposition[%s: %d nodes, %d edges, %s]",
            compositionId, nodes.size(), edges.size(), status);
    }

    /**
     * Generate a visual representation of the composition.
     */
    public String toGraphString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Composition: ").append(name).append(" [").append(compositionId).append("]\n");
        sb.append("Status: ").append(status).append("\n");
        sb.append("Nodes:\n");
        for (CompositionNode node : nodes.values()) {
            String marker = entryNodes.contains(node.getNodeId()) ? "→ " :
                           exitNodes.contains(node.getNodeId()) ? "← " : "  ";
            sb.append("  ").append(marker).append(node).append("\n");
        }
        sb.append("Edges:\n");
        for (CompositionEdge edge : edges) {
            sb.append("  ").append(edge).append("\n");
        }
        sb.append("Resources: ").append(calculateTotalResourceRequirements()).append("\n");
        sb.append("Est. Latency: ").append(estimateCriticalPathLatencyMs()).append("ms");
        return sb.toString();
    }
}
