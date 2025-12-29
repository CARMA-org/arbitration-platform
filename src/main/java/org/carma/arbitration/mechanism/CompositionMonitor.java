package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors active service compositions and tracks their execution.
 * 
 * The CompositionMonitor provides:
 * - Tracking of active composition instances
 * - Validation of composition chains during execution
 * - Dependency monitoring between services
 * - Metrics collection for performance analysis
 * - Failure detection and recovery coordination
 */
public class CompositionMonitor {

    private final ServiceRegistry registry;
    private final Map<String, CompositionInstance> activeInstances;
    private final Map<String, List<CompositionInstance>> instancesByAgent;
    private final Map<String, List<CompositionInstance>> instancesByComposition;
    private final AtomicLong instanceCounter;
    private final List<MonitorListener> listeners;
    private final CompositionMetrics metrics;

    /**
     * Represents an active instance of a composition.
     */
    public static class CompositionInstance {
        private final String instanceId;
        private final String agentId;
        private final ServiceComposition composition;
        private final Map<String, NodeExecution> nodeExecutions;
        private final long startedAtMs;
        private InstanceStatus status;
        private String failureReason;
        private long completedAtMs;

        public enum InstanceStatus {
            PENDING,    // Waiting to start
            RUNNING,    // Actively executing
            COMPLETED,  // Successfully finished
            FAILED,     // Execution failed
            CANCELLED   // Cancelled by user/system
        }

        public CompositionInstance(String instanceId, String agentId, ServiceComposition composition) {
            this.instanceId = instanceId;
            this.agentId = agentId;
            this.composition = composition;
            this.nodeExecutions = new ConcurrentHashMap<>();
            this.startedAtMs = System.currentTimeMillis();
            this.status = InstanceStatus.PENDING;

            // Initialize node executions
            for (String nodeId : composition.getNodes().keySet()) {
                nodeExecutions.put(nodeId, new NodeExecution(nodeId));
            }
        }

        public String getInstanceId() { return instanceId; }
        public String getAgentId() { return agentId; }
        public ServiceComposition getComposition() { return composition; }
        public Map<String, NodeExecution> getNodeExecutions() { return nodeExecutions; }
        public long getStartedAtMs() { return startedAtMs; }
        public InstanceStatus getStatus() { return status; }
        public String getFailureReason() { return failureReason; }
        public long getCompletedAtMs() { return completedAtMs; }

        public long getDurationMs() {
            long end = completedAtMs > 0 ? completedAtMs : System.currentTimeMillis();
            return end - startedAtMs;
        }

        public void setStatus(InstanceStatus status) {
            this.status = status;
            if (status == InstanceStatus.COMPLETED || status == InstanceStatus.FAILED ||
                status == InstanceStatus.CANCELLED) {
                this.completedAtMs = System.currentTimeMillis();
            }
        }

        public void setFailureReason(String reason) {
            this.failureReason = reason;
        }

        public NodeExecution getNodeExecution(String nodeId) {
            return nodeExecutions.get(nodeId);
        }

        public boolean isComplete() {
            return status == InstanceStatus.COMPLETED || 
                   status == InstanceStatus.FAILED || 
                   status == InstanceStatus.CANCELLED;
        }

        public double getProgress() {
            if (nodeExecutions.isEmpty()) return 0.0;
            long completed = nodeExecutions.values().stream()
                .filter(n -> n.getStatus() == NodeExecution.NodeStatus.COMPLETED)
                .count();
            return (double) completed / nodeExecutions.size();
        }

        @Override
        public String toString() {
            return String.format("Instance[%s: %s, agent=%s, status=%s, progress=%.0f%%]",
                instanceId, composition.getCompositionId(), agentId, status, getProgress() * 100);
        }
    }

    /**
     * Tracks execution of a single node within a composition.
     */
    public static class NodeExecution {
        private final String nodeId;
        private NodeStatus status;
        private String assignedServiceId;
        private long startedAtMs;
        private long completedAtMs;
        private int retryCount;
        private String lastError;
        private Map<String, Object> inputData;
        private Map<String, Object> outputData;

        public enum NodeStatus {
            PENDING,
            WAITING_INPUT,
            RUNNING,
            COMPLETED,
            FAILED,
            SKIPPED
        }

        public NodeExecution(String nodeId) {
            this.nodeId = nodeId;
            this.status = NodeStatus.PENDING;
            this.retryCount = 0;
        }

        public String getNodeId() { return nodeId; }
        public NodeStatus getStatus() { return status; }
        public String getAssignedServiceId() { return assignedServiceId; }
        public long getStartedAtMs() { return startedAtMs; }
        public long getCompletedAtMs() { return completedAtMs; }
        public int getRetryCount() { return retryCount; }
        public String getLastError() { return lastError; }
        public Map<String, Object> getInputData() { return inputData; }
        public Map<String, Object> getOutputData() { return outputData; }

        public void start(String serviceId) {
            this.status = NodeStatus.RUNNING;
            this.assignedServiceId = serviceId;
            this.startedAtMs = System.currentTimeMillis();
        }

        public void complete(Map<String, Object> output) {
            this.status = NodeStatus.COMPLETED;
            this.completedAtMs = System.currentTimeMillis();
            this.outputData = output;
        }

        public void fail(String error) {
            this.status = NodeStatus.FAILED;
            this.completedAtMs = System.currentTimeMillis();
            this.lastError = error;
        }

        public void retry() {
            this.retryCount++;
            this.status = NodeStatus.PENDING;
        }

        public long getDurationMs() {
            if (startedAtMs == 0) return 0;
            long end = completedAtMs > 0 ? completedAtMs : System.currentTimeMillis();
            return end - startedAtMs;
        }

        @Override
        public String toString() {
            return String.format("NodeExec[%s: %s, service=%s, %dms]",
                nodeId, status, assignedServiceId, getDurationMs());
        }
    }

    /**
     * Listener for composition events.
     */
    public interface MonitorListener {
        default void onCompositionStarted(CompositionInstance instance) {}
        default void onCompositionCompleted(CompositionInstance instance) {}
        default void onCompositionFailed(CompositionInstance instance, String reason) {}
        default void onNodeStarted(CompositionInstance instance, NodeExecution node) {}
        default void onNodeCompleted(CompositionInstance instance, NodeExecution node) {}
        default void onNodeFailed(CompositionInstance instance, NodeExecution node, String reason) {}
    }

    /**
     * Aggregated metrics for compositions.
     */
    public static class CompositionMetrics {
        private final AtomicLong totalStarted = new AtomicLong(0);
        private final AtomicLong totalCompleted = new AtomicLong(0);
        private final AtomicLong totalFailed = new AtomicLong(0);
        private final AtomicLong totalCancelled = new AtomicLong(0);
        private final Map<String, Long> completionsByComposition = new ConcurrentHashMap<>();
        private final Map<String, Long> failuresByComposition = new ConcurrentHashMap<>();
        private final Map<String, List<Long>> latenciesByComposition = new ConcurrentHashMap<>();

        public void recordStart() {
            totalStarted.incrementAndGet();
        }

        public void recordCompletion(String compositionId, long latencyMs) {
            totalCompleted.incrementAndGet();
            completionsByComposition.merge(compositionId, 1L, Long::sum);
            latenciesByComposition.computeIfAbsent(compositionId, k -> 
                Collections.synchronizedList(new ArrayList<>())).add(latencyMs);
        }

        public void recordFailure(String compositionId) {
            totalFailed.incrementAndGet();
            failuresByComposition.merge(compositionId, 1L, Long::sum);
        }

        public void recordCancellation() {
            totalCancelled.incrementAndGet();
        }

        public long getTotalStarted() { return totalStarted.get(); }
        public long getTotalCompleted() { return totalCompleted.get(); }
        public long getTotalFailed() { return totalFailed.get(); }
        public long getTotalCancelled() { return totalCancelled.get(); }

        public double getSuccessRate() {
            long completed = totalCompleted.get() + totalFailed.get();
            return completed > 0 ? (double) totalCompleted.get() / completed : 0.0;
        }

        public double getAverageLatency(String compositionId) {
            List<Long> latencies = latenciesByComposition.get(compositionId);
            if (latencies == null || latencies.isEmpty()) return 0.0;
            return latencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
        }

        public Map<String, Double> getFailureRates() {
            Map<String, Double> rates = new HashMap<>();
            for (String compId : completionsByComposition.keySet()) {
                long completed = completionsByComposition.getOrDefault(compId, 0L);
                long failed = failuresByComposition.getOrDefault(compId, 0L);
                long total = completed + failed;
                rates.put(compId, total > 0 ? (double) failed / total : 0.0);
            }
            return rates;
        }

        @Override
        public String toString() {
            return String.format("Metrics[started=%d, completed=%d, failed=%d, success=%.1f%%]",
                totalStarted.get(), totalCompleted.get(), totalFailed.get(), getSuccessRate() * 100);
        }
    }

    public CompositionMonitor(ServiceRegistry registry) {
        this.registry = registry;
        this.activeInstances = new ConcurrentHashMap<>();
        this.instancesByAgent = new ConcurrentHashMap<>();
        this.instancesByComposition = new ConcurrentHashMap<>();
        this.instanceCounter = new AtomicLong(0);
        this.listeners = new ArrayList<>();
        this.metrics = new CompositionMetrics();
    }

    // ========================================================================
    // Composition Lifecycle
    // ========================================================================

    /**
     * Start a new composition instance.
     */
    public CompositionInstance startComposition(String agentId, ServiceComposition composition) {
        String instanceId = "INST-" + instanceCounter.incrementAndGet();
        CompositionInstance instance = new CompositionInstance(instanceId, agentId, composition);
        
        activeInstances.put(instanceId, instance);
        instancesByAgent.computeIfAbsent(agentId, k -> 
            Collections.synchronizedList(new ArrayList<>())).add(instance);
        instancesByComposition.computeIfAbsent(composition.getCompositionId(), k -> 
            Collections.synchronizedList(new ArrayList<>())).add(instance);

        instance.setStatus(CompositionInstance.InstanceStatus.RUNNING);
        metrics.recordStart();
        notifyListeners(l -> l.onCompositionStarted(instance));

        return instance;
    }

    /**
     * Start execution of a specific node.
     */
    public void startNode(String instanceId, String nodeId, String serviceId) {
        CompositionInstance instance = activeInstances.get(instanceId);
        if (instance == null) return;

        NodeExecution node = instance.getNodeExecution(nodeId);
        if (node == null) return;

        // Validate dependencies are complete
        if (!areDependenciesComplete(instance, nodeId)) {
            node.fail("Dependencies not complete");
            return;
        }

        node.start(serviceId);
        notifyListeners(l -> l.onNodeStarted(instance, node));
    }

    /**
     * Complete a node with output data.
     */
    public void completeNode(String instanceId, String nodeId, Map<String, Object> output) {
        CompositionInstance instance = activeInstances.get(instanceId);
        if (instance == null) return;

        NodeExecution node = instance.getNodeExecution(nodeId);
        if (node == null) return;

        node.complete(output);
        notifyListeners(l -> l.onNodeCompleted(instance, node));

        // Check if all nodes are complete
        if (areAllNodesComplete(instance)) {
            completeComposition(instanceId);
        }
    }

    /**
     * Fail a node with error.
     */
    public void failNode(String instanceId, String nodeId, String error) {
        CompositionInstance instance = activeInstances.get(instanceId);
        if (instance == null) return;

        NodeExecution node = instance.getNodeExecution(nodeId);
        if (node == null) return;

        node.fail(error);
        notifyListeners(l -> l.onNodeFailed(instance, node, error));

        // Check retry policy
        ServiceComposition.CompositionNode compNode = 
            instance.getComposition().getNode(nodeId);
        
        // Simple retry policy: retry up to 3 times
        if (node.getRetryCount() < 3) {
            node.retry();
            // Re-queue for execution (in real implementation)
        } else {
            failComposition(instanceId, "Node " + nodeId + " failed after retries: " + error);
        }
    }

    /**
     * Complete a composition successfully.
     */
    public void completeComposition(String instanceId) {
        CompositionInstance instance = activeInstances.get(instanceId);
        if (instance == null) return;

        instance.setStatus(CompositionInstance.InstanceStatus.COMPLETED);
        metrics.recordCompletion(instance.getComposition().getCompositionId(), 
            instance.getDurationMs());
        
        // Release reserved capacity
        registry.releaseCapacityFor(instance.getComposition());
        
        notifyListeners(l -> l.onCompositionCompleted(instance));
    }

    /**
     * Fail a composition.
     */
    public void failComposition(String instanceId, String reason) {
        CompositionInstance instance = activeInstances.get(instanceId);
        if (instance == null) return;

        instance.setStatus(CompositionInstance.InstanceStatus.FAILED);
        instance.setFailureReason(reason);
        metrics.recordFailure(instance.getComposition().getCompositionId());
        
        // Release reserved capacity
        registry.releaseCapacityFor(instance.getComposition());
        
        notifyListeners(l -> l.onCompositionFailed(instance, reason));
    }

    /**
     * Cancel a composition.
     */
    public void cancelComposition(String instanceId) {
        CompositionInstance instance = activeInstances.get(instanceId);
        if (instance == null) return;

        instance.setStatus(CompositionInstance.InstanceStatus.CANCELLED);
        metrics.recordCancellation();
        
        // Release reserved capacity
        registry.releaseCapacityFor(instance.getComposition());
    }

    // ========================================================================
    // Validation
    // ========================================================================

    /**
     * Check if dependencies for a node are complete.
     */
    public boolean areDependenciesComplete(CompositionInstance instance, String nodeId) {
        ServiceComposition comp = instance.getComposition();
        
        for (ServiceComposition.CompositionEdge edge : comp.getEdges()) {
            if (edge.getToNodeId().equals(nodeId)) {
                NodeExecution dependency = instance.getNodeExecution(edge.getFromNodeId());
                if (dependency == null || 
                    dependency.getStatus() != NodeExecution.NodeStatus.COMPLETED) {
                    return false;
                }
            }
        }
        
        return true;
    }

    /**
     * Check if all nodes in composition are complete.
     */
    public boolean areAllNodesComplete(CompositionInstance instance) {
        for (NodeExecution node : instance.getNodeExecutions().values()) {
            if (node.getStatus() != NodeExecution.NodeStatus.COMPLETED &&
                node.getStatus() != NodeExecution.NodeStatus.SKIPPED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get nodes that are ready to execute.
     */
    public List<String> getReadyNodes(CompositionInstance instance) {
        List<String> ready = new ArrayList<>();
        
        for (var entry : instance.getNodeExecutions().entrySet()) {
            String nodeId = entry.getKey();
            NodeExecution node = entry.getValue();
            
            if (node.getStatus() == NodeExecution.NodeStatus.PENDING &&
                areDependenciesComplete(instance, nodeId)) {
                ready.add(nodeId);
            }
        }
        
        return ready;
    }

    /**
     * Validate data flow compatibility.
     */
    public List<String> validateDataFlow(CompositionInstance instance) {
        List<String> errors = new ArrayList<>();
        ServiceComposition comp = instance.getComposition();

        for (ServiceComposition.CompositionEdge edge : comp.getEdges()) {
            NodeExecution from = instance.getNodeExecution(edge.getFromNodeId());
            
            if (from != null && from.getStatus() == NodeExecution.NodeStatus.COMPLETED) {
                Map<String, Object> output = from.getOutputData();
                if (output == null || output.isEmpty()) {
                    errors.add("Node " + edge.getFromNodeId() + " produced no output");
                }
            }
        }

        return errors;
    }

    // ========================================================================
    // Queries
    // ========================================================================

    /**
     * Get active instance by ID.
     */
    public Optional<CompositionInstance> getInstance(String instanceId) {
        return Optional.ofNullable(activeInstances.get(instanceId));
    }

    /**
     * Get all active instances.
     */
    public Collection<CompositionInstance> getActiveInstances() {
        return activeInstances.values().stream()
            .filter(i -> !i.isComplete())
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Get instances for an agent.
     */
    public List<CompositionInstance> getInstancesForAgent(String agentId) {
        return instancesByAgent.getOrDefault(agentId, Collections.emptyList());
    }

    /**
     * Get active instances for an agent.
     */
    public List<CompositionInstance> getActiveInstancesForAgent(String agentId) {
        return getInstancesForAgent(agentId).stream()
            .filter(i -> !i.isComplete())
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Get instances for a composition.
     */
    public List<CompositionInstance> getInstancesForComposition(String compositionId) {
        return instancesByComposition.getOrDefault(compositionId, Collections.emptyList());
    }

    /**
     * Get count of active instances.
     */
    public int getActiveCount() {
        return (int) activeInstances.values().stream()
            .filter(i -> !i.isComplete())
            .count();
    }

    /**
     * Get metrics.
     */
    public CompositionMetrics getMetrics() {
        return metrics;
    }

    // ========================================================================
    // Cleanup
    // ========================================================================

    /**
     * Clean up completed instances older than specified age.
     */
    public int cleanupCompleted(long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        int removed = 0;

        Iterator<Map.Entry<String, CompositionInstance>> it = activeInstances.entrySet().iterator();
        while (it.hasNext()) {
            CompositionInstance instance = it.next().getValue();
            if (instance.isComplete() && instance.getCompletedAtMs() < cutoff) {
                it.remove();
                removed++;
            }
        }

        return removed;
    }

    /**
     * Force timeout on instances running too long.
     */
    public int timeoutStale(long maxDurationMs) {
        long now = System.currentTimeMillis();
        int timedOut = 0;

        for (CompositionInstance instance : activeInstances.values()) {
            if (!instance.isComplete() && (now - instance.getStartedAtMs()) > maxDurationMs) {
                failComposition(instance.getInstanceId(), "Execution timeout");
                timedOut++;
            }
        }

        return timedOut;
    }

    // ========================================================================
    // Listeners
    // ========================================================================

    public void addListener(MonitorListener listener) {
        listeners.add(listener);
    }

    public void removeListener(MonitorListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(java.util.function.Consumer<MonitorListener> action) {
        for (MonitorListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                System.err.println("Error in monitor listener: " + e.getMessage());
            }
        }
    }

    // ========================================================================
    // Object Methods
    // ========================================================================

    @Override
    public String toString() {
        return String.format("CompositionMonitor[active=%d, %s]", 
            getActiveCount(), metrics);
    }

    /**
     * Generate detailed status report.
     */
    public String toDetailedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("CompositionMonitor Status\n");
        sb.append("=".repeat(60)).append("\n");
        sb.append("Metrics: ").append(metrics).append("\n\n");
        
        sb.append("Active Instances:\n");
        for (CompositionInstance instance : getActiveInstances()) {
            sb.append("  ").append(instance).append("\n");
            for (NodeExecution node : instance.getNodeExecutions().values()) {
                sb.append("    ").append(node).append("\n");
            }
        }
        
        return sb.toString();
    }
}
