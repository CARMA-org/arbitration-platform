package org.carma.arbitration.safety;

import org.carma.arbitration.model.*;
import org.carma.arbitration.agent.RealisticAgentFramework.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Monitors service invocation patterns for safety.
 * 
 * This addresses the CAIS safety story: services alone are narrow, but composition
 * can enable emergent capabilities. This analyzer:
 * 
 * - Builds dependency graphs of service invocations per agent
 * - Tracks composition depth (how deep are service chains?)
 * - Detects concerning patterns (dangerous service combinations)
 * - Generates operator alerts when thresholds are exceeded
 * 
 * Integrates with EventBus as a subscriber to ServiceInvocationEvents.
 * 
 * From v0.5plus-requests.md:
 * "You have services; you need observability into how they're composed."
 */
public class ServiceCompositionAnalyzer {

    // ========================================================================
    // Configuration
    // ========================================================================
    
    /**
     * Configuration for service composition safety limits.
     */
    public static class CompositionConfig {
        private final int softDepthLimit;      // Alert when exceeded
        private final int hardDepthLimit;      // Optional: reject invocations
        private final boolean enforceHardLimit;
        private final Set<ServiceTypePair> concerningCombinations;
        private final Duration analysisWindow;
        
        public CompositionConfig(int softDepthLimit, int hardDepthLimit, 
                                 boolean enforceHardLimit,
                                 Set<ServiceTypePair> concerningCombinations,
                                 Duration analysisWindow) {
            this.softDepthLimit = softDepthLimit;
            this.hardDepthLimit = hardDepthLimit;
            this.enforceHardLimit = enforceHardLimit;
            this.concerningCombinations = new HashSet<>(concerningCombinations);
            this.analysisWindow = analysisWindow;
        }
        
        /**
         * Default configuration per v0.5plus-requests.md:
         * soft_depth_limit: 10
         * hard_depth_limit: 15
         * enforce_hard_limit: false
         */
        public static CompositionConfig defaults() {
            Set<ServiceTypePair> defaultConcerns = new HashSet<>();
            // Reasoning + code generation can enable self-modification
            defaultConcerns.add(new ServiceTypePair(
                ServiceType.REASONING, ServiceType.CODE_ANALYSIS));
            // Knowledge + reasoning can enable world-modeling
            defaultConcerns.add(new ServiceTypePair(
                ServiceType.KNOWLEDGE_RETRIEVAL, ServiceType.REASONING));
            // Vector search + generation can enable memory-augmented generation
            defaultConcerns.add(new ServiceTypePair(
                ServiceType.VECTOR_SEARCH, ServiceType.TEXT_GENERATION));
            
            return new CompositionConfig(
                10,     // softDepthLimit
                15,     // hardDepthLimit
                false,  // enforceHardLimit (off for v0.5)
                defaultConcerns,
                Duration.ofHours(1)  // analysisWindow
            );
        }
        
        public int getSoftDepthLimit() { return softDepthLimit; }
        public int getHardDepthLimit() { return hardDepthLimit; }
        public boolean isEnforceHardLimit() { return enforceHardLimit; }
        public Set<ServiceTypePair> getConcerningCombinations() { 
            return Collections.unmodifiableSet(concerningCombinations); 
        }
        public Duration getAnalysisWindow() { return analysisWindow; }
    }
    
    /**
     * Pair of service types that may be concerning in combination.
     */
    public static class ServiceTypePair {
        private final ServiceType first;
        private final ServiceType second;
        
        public ServiceTypePair(ServiceType first, ServiceType second) {
            // Normalize ordering for consistent hashing
            if (first.ordinal() <= second.ordinal()) {
                this.first = first;
                this.second = second;
            } else {
                this.first = second;
                this.second = first;
            }
        }
        
        public boolean matches(ServiceType a, ServiceType b) {
            return (first == a && second == b) || (first == b && second == a);
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ServiceTypePair that = (ServiceTypePair) o;
            return first == that.first && second == that.second;
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(first, second);
        }
        
        @Override
        public String toString() {
            return first + " + " + second;
        }
    }
    
    // ========================================================================
    // Events
    // ========================================================================
    
    /**
     * Event representing a service invocation.
     */
    public static class ServiceInvocationEvent {
        private final String agentId;
        private final ServiceType serviceType;
        private final Instant timestamp;
        private final String parentInvocationId;  // null if root
        private final String invocationId;
        private final Map<String, Object> metadata;
        
        public ServiceInvocationEvent(String agentId, ServiceType serviceType,
                                      String invocationId, String parentInvocationId) {
            this.agentId = agentId;
            this.serviceType = serviceType;
            this.timestamp = Instant.now();
            this.invocationId = invocationId;
            this.parentInvocationId = parentInvocationId;
            this.metadata = new HashMap<>();
        }
        
        public String getAgentId() { return agentId; }
        public ServiceType getServiceType() { return serviceType; }
        public Instant getTimestamp() { return timestamp; }
        public String getParentInvocationId() { return parentInvocationId; }
        public String getInvocationId() { return invocationId; }
        public Map<String, Object> getMetadata() { return metadata; }
    }
    
    // ========================================================================
    // Dependency Graph
    // ========================================================================
    
    /**
     * Dependency graph tracking service invocation chains for an agent.
     */
    public static class ServiceDependencyGraph {
        private final String agentId;
        private final List<InvocationNode> nodes;
        private final Map<String, InvocationNode> nodeIndex;
        private int maxDepthObserved;
        private Instant lastUpdated;
        
        public ServiceDependencyGraph(String agentId) {
            this.agentId = agentId;
            this.nodes = new ArrayList<>();
            this.nodeIndex = new HashMap<>();
            this.maxDepthObserved = 0;
            this.lastUpdated = Instant.now();
        }
        
        public void addInvocation(ServiceInvocationEvent event) {
            InvocationNode node = new InvocationNode(
                event.getInvocationId(),
                event.getServiceType(),
                event.getTimestamp()
            );
            
            if (event.getParentInvocationId() != null) {
                InvocationNode parent = nodeIndex.get(event.getParentInvocationId());
                if (parent != null) {
                    parent.addChild(node);
                    node.setParent(parent);
                    node.setDepth(parent.getDepth() + 1);
                    maxDepthObserved = Math.max(maxDepthObserved, node.getDepth());
                }
            } else {
                node.setDepth(1);  // Root invocation
                maxDepthObserved = Math.max(maxDepthObserved, 1);
            }
            
            nodes.add(node);
            nodeIndex.put(event.getInvocationId(), node);
            lastUpdated = Instant.now();
        }
        
        public int getMaxDepth() {
            return maxDepthObserved;
        }
        
        public int getCurrentChainDepth(String invocationId) {
            InvocationNode node = nodeIndex.get(invocationId);
            return node != null ? node.getDepth() : 0;
        }
        
        public Set<ServiceType> getUsedServiceTypes() {
            return nodes.stream()
                .map(InvocationNode::getServiceType)
                .collect(Collectors.toSet());
        }
        
        public List<ServiceType> getChainForInvocation(String invocationId) {
            List<ServiceType> chain = new ArrayList<>();
            InvocationNode node = nodeIndex.get(invocationId);
            while (node != null) {
                chain.add(0, node.getServiceType());
                node = node.getParent();
            }
            return chain;
        }
        
        /**
         * Get all service type pairs that have been observed in chains.
         */
        public Set<ServiceTypePair> getObservedPairs() {
            Set<ServiceTypePair> pairs = new HashSet<>();
            for (InvocationNode node : nodes) {
                if (node.getParent() != null) {
                    pairs.add(new ServiceTypePair(
                        node.getParent().getServiceType(),
                        node.getServiceType()
                    ));
                }
            }
            return pairs;
        }
        
        /**
         * Prune nodes older than given duration.
         */
        public void pruneOlderThan(Duration maxAge) {
            Instant cutoff = Instant.now().minus(maxAge);
            List<InvocationNode> toRemove = nodes.stream()
                .filter(n -> n.getTimestamp().isBefore(cutoff))
                .toList();
            
            for (InvocationNode node : toRemove) {
                nodes.remove(node);
                nodeIndex.remove(node.getInvocationId());
            }
            
            // Recalculate max depth
            maxDepthObserved = nodes.stream()
                .mapToInt(InvocationNode::getDepth)
                .max()
                .orElse(0);
        }
        
        public String getAgentId() { return agentId; }
        public List<InvocationNode> getNodes() { return Collections.unmodifiableList(nodes); }
        public Instant getLastUpdated() { return lastUpdated; }
    }
    
    /**
     * Node in the dependency graph representing a single invocation.
     */
    public static class InvocationNode {
        private final String invocationId;
        private final ServiceType serviceType;
        private final Instant timestamp;
        private InvocationNode parent;
        private final List<InvocationNode> children;
        private int depth;
        
        public InvocationNode(String invocationId, ServiceType serviceType, Instant timestamp) {
            this.invocationId = invocationId;
            this.serviceType = serviceType;
            this.timestamp = timestamp;
            this.children = new ArrayList<>();
            this.depth = 0;
        }
        
        public void addChild(InvocationNode child) { children.add(child); }
        public void setParent(InvocationNode parent) { this.parent = parent; }
        public void setDepth(int depth) { this.depth = depth; }
        
        public String getInvocationId() { return invocationId; }
        public ServiceType getServiceType() { return serviceType; }
        public Instant getTimestamp() { return timestamp; }
        public InvocationNode getParent() { return parent; }
        public List<InvocationNode> getChildren() { return Collections.unmodifiableList(children); }
        public int getDepth() { return depth; }
    }
    
    // ========================================================================
    // Metrics and Alerts
    // ========================================================================
    
    /**
     * Composition metrics for an agent.
     */
    public static class CompositionMetrics {
        private final String agentId;
        private final int maxDepthObserved;
        private final int totalInvocations;
        private final Set<ServiceType> uniqueServiceTypes;
        private final int concerningPairCount;
        private final Instant windowStart;
        private final Instant windowEnd;
        
        public CompositionMetrics(String agentId, int maxDepthObserved, 
                                  int totalInvocations, Set<ServiceType> uniqueServiceTypes,
                                  int concerningPairCount, Instant windowStart, Instant windowEnd) {
            this.agentId = agentId;
            this.maxDepthObserved = maxDepthObserved;
            this.totalInvocations = totalInvocations;
            this.uniqueServiceTypes = new HashSet<>(uniqueServiceTypes);
            this.concerningPairCount = concerningPairCount;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
        }
        
        public String getAgentId() { return agentId; }
        public int getMaxDepthObserved() { return maxDepthObserved; }
        public int getTotalInvocations() { return totalInvocations; }
        public Set<ServiceType> getUniqueServiceTypes() { 
            return Collections.unmodifiableSet(uniqueServiceTypes); 
        }
        public int getConcerningPairCount() { return concerningPairCount; }
        public Instant getWindowStart() { return windowStart; }
        public Instant getWindowEnd() { return windowEnd; }
        
        public double getServiceDiversity() {
            return (double) uniqueServiceTypes.size() / ServiceType.values().length;
        }
        
        @Override
        public String toString() {
            return String.format(
                "CompositionMetrics[agent=%s, maxDepth=%d, invocations=%d, " +
                "serviceTypes=%d, concerningPairs=%d]",
                agentId, maxDepthObserved, totalInvocations, 
                uniqueServiceTypes.size(), concerningPairCount
            );
        }
    }
    
    /**
     * Safety alert generated by the analyzer.
     */
    public static class SafetyAlert {
        public enum Severity {
            INFO,      // Informational only
            WARNING,   // Approaching limits
            CRITICAL,  // Limit exceeded
            EMERGENCY  // Immediate action required
        }
        
        public enum AlertType {
            DEPTH_SOFT_LIMIT_EXCEEDED,
            DEPTH_HARD_LIMIT_EXCEEDED,
            CONCERNING_COMBINATION_DETECTED,
            RAPID_COMPOSITION_GROWTH,
            SERVICE_DIVERSITY_THRESHOLD
        }
        
        private final String agentId;
        private final AlertType type;
        private final Severity severity;
        private final String message;
        private final Instant timestamp;
        private final Map<String, Object> details;
        
        public SafetyAlert(String agentId, AlertType type, Severity severity, String message) {
            this.agentId = agentId;
            this.type = type;
            this.severity = severity;
            this.message = message;
            this.timestamp = Instant.now();
            this.details = new HashMap<>();
        }
        
        public SafetyAlert withDetail(String key, Object value) {
            details.put(key, value);
            return this;
        }
        
        public String getAgentId() { return agentId; }
        public AlertType getType() { return type; }
        public Severity getSeverity() { return severity; }
        public String getMessage() { return message; }
        public Instant getTimestamp() { return timestamp; }
        public Map<String, Object> getDetails() { return Collections.unmodifiableMap(details); }
        
        @Override
        public String toString() {
            return String.format("[%s] %s: %s - %s", 
                severity, type, agentId, message);
        }
    }
    
    // ========================================================================
    // Main Analyzer
    // ========================================================================
    
    private final CompositionConfig config;
    private final Map<String, ServiceDependencyGraph> agentGraphs;
    private final List<SafetyAlert> recentAlerts;
    private final List<AlertListener> alertListeners;
    
    public ServiceCompositionAnalyzer() {
        this(CompositionConfig.defaults());
    }
    
    public ServiceCompositionAnalyzer(CompositionConfig config) {
        this.config = config;
        this.agentGraphs = new ConcurrentHashMap<>();
        this.recentAlerts = Collections.synchronizedList(new ArrayList<>());
        this.alertListeners = new ArrayList<>();
    }
    
    /**
     * Listener interface for receiving safety alerts.
     */
    public interface AlertListener {
        void onAlert(SafetyAlert alert);
    }
    
    public void addAlertListener(AlertListener listener) {
        alertListeners.add(listener);
    }
    
    public void removeAlertListener(AlertListener listener) {
        alertListeners.remove(listener);
    }
    
    /**
     * Handle a service invocation event.
     * This is the main entry point, called for each service invocation.
     */
    public InvocationResult handleInvocation(ServiceInvocationEvent event) {
        String agentId = event.getAgentId();
        
        // Get or create dependency graph for this agent
        ServiceDependencyGraph graph = agentGraphs.computeIfAbsent(
            agentId, ServiceDependencyGraph::new);
        
        // Add the invocation to the graph
        graph.addInvocation(event);
        
        // Check for concerns
        List<SafetyAlert> alerts = checkForConcerns(agentId);
        
        // Determine if invocation should be blocked
        boolean blocked = false;
        String blockReason = null;
        
        if (config.isEnforceHardLimit()) {
            int currentDepth = graph.getCurrentChainDepth(event.getInvocationId());
            if (currentDepth > config.getHardDepthLimit()) {
                blocked = true;
                blockReason = String.format(
                    "Composition depth %d exceeds hard limit %d",
                    currentDepth, config.getHardDepthLimit()
                );
            }
        }
        
        return new InvocationResult(blocked, blockReason, alerts);
    }
    
    /**
     * Result of processing an invocation.
     */
    public static class InvocationResult {
        private final boolean blocked;
        private final String blockReason;
        private final List<SafetyAlert> alerts;
        
        public InvocationResult(boolean blocked, String blockReason, List<SafetyAlert> alerts) {
            this.blocked = blocked;
            this.blockReason = blockReason;
            this.alerts = new ArrayList<>(alerts);
        }
        
        public boolean isBlocked() { return blocked; }
        public String getBlockReason() { return blockReason; }
        public List<SafetyAlert> getAlerts() { return Collections.unmodifiableList(alerts); }
    }
    
    /**
     * Get composition metrics for an agent.
     */
    public CompositionMetrics getMetrics(String agentId) {
        ServiceDependencyGraph graph = agentGraphs.get(agentId);
        if (graph == null) {
            return new CompositionMetrics(
                agentId, 0, 0, Collections.emptySet(), 0,
                Instant.now().minus(config.getAnalysisWindow()), Instant.now()
            );
        }
        
        // Count concerning pairs
        Set<ServiceTypePair> observedPairs = graph.getObservedPairs();
        int concerningCount = 0;
        for (ServiceTypePair pair : observedPairs) {
            if (config.getConcerningCombinations().contains(pair)) {
                concerningCount++;
            }
        }
        
        return new CompositionMetrics(
            agentId,
            graph.getMaxDepth(),
            graph.getNodes().size(),
            graph.getUsedServiceTypes(),
            concerningCount,
            Instant.now().minus(config.getAnalysisWindow()),
            Instant.now()
        );
    }
    
    /**
     * Check for safety concerns for an agent.
     */
    public List<SafetyAlert> checkForConcerns(String agentId) {
        List<SafetyAlert> alerts = new ArrayList<>();
        
        ServiceDependencyGraph graph = agentGraphs.get(agentId);
        if (graph == null) {
            return alerts;
        }
        
        int currentDepth = graph.getMaxDepth();
        
        // Check soft depth limit
        if (currentDepth > config.getSoftDepthLimit()) {
            SafetyAlert alert = new SafetyAlert(
                agentId,
                SafetyAlert.AlertType.DEPTH_SOFT_LIMIT_EXCEEDED,
                SafetyAlert.Severity.WARNING,
                String.format("Composition depth %d exceeds soft limit %d",
                    currentDepth, config.getSoftDepthLimit())
            ).withDetail("currentDepth", currentDepth)
             .withDetail("softLimit", config.getSoftDepthLimit());
            
            alerts.add(alert);
            notifyListeners(alert);
        }
        
        // Check hard depth limit
        if (currentDepth > config.getHardDepthLimit()) {
            SafetyAlert alert = new SafetyAlert(
                agentId,
                SafetyAlert.AlertType.DEPTH_HARD_LIMIT_EXCEEDED,
                SafetyAlert.Severity.CRITICAL,
                String.format("Composition depth %d exceeds hard limit %d",
                    currentDepth, config.getHardDepthLimit())
            ).withDetail("currentDepth", currentDepth)
             .withDetail("hardLimit", config.getHardDepthLimit());
            
            alerts.add(alert);
            notifyListeners(alert);
        }
        
        // Check for concerning combinations
        Set<ServiceTypePair> observedPairs = graph.getObservedPairs();
        for (ServiceTypePair pair : observedPairs) {
            if (config.getConcerningCombinations().contains(pair)) {
                SafetyAlert alert = new SafetyAlert(
                    agentId,
                    SafetyAlert.AlertType.CONCERNING_COMBINATION_DETECTED,
                    SafetyAlert.Severity.WARNING,
                    String.format("Concerning service combination detected: %s", pair)
                ).withDetail("combination", pair.toString());
                
                alerts.add(alert);
                notifyListeners(alert);
            }
        }
        
        // Check service diversity (high diversity may indicate generality)
        CompositionMetrics metrics = getMetrics(agentId);
        if (metrics.getServiceDiversity() > 0.6) {  // Using > 60% of service types
            SafetyAlert alert = new SafetyAlert(
                agentId,
                SafetyAlert.AlertType.SERVICE_DIVERSITY_THRESHOLD,
                SafetyAlert.Severity.INFO,
                String.format("Agent using %.0f%% of available service types",
                    metrics.getServiceDiversity() * 100)
            ).withDetail("diversity", metrics.getServiceDiversity())
             .withDetail("serviceTypes", metrics.getUniqueServiceTypes().toString());
            
            alerts.add(alert);
            notifyListeners(alert);
        }
        
        // Store alerts
        recentAlerts.addAll(alerts);
        
        return alerts;
    }
    
    /**
     * Get current composition depth for an agent.
     */
    public int getCurrentDepth(String agentId) {
        ServiceDependencyGraph graph = agentGraphs.get(agentId);
        return graph != null ? graph.getMaxDepth() : 0;
    }
    
    /**
     * Get the service chain for a specific invocation.
     */
    public List<ServiceType> getServiceChain(String agentId, String invocationId) {
        ServiceDependencyGraph graph = agentGraphs.get(agentId);
        if (graph == null) {
            return Collections.emptyList();
        }
        return graph.getChainForInvocation(invocationId);
    }
    
    /**
     * Get all recent alerts.
     */
    public List<SafetyAlert> getRecentAlerts() {
        return Collections.unmodifiableList(new ArrayList<>(recentAlerts));
    }
    
    /**
     * Get alerts for a specific agent.
     */
    public List<SafetyAlert> getAlertsForAgent(String agentId) {
        return recentAlerts.stream()
            .filter(a -> a.getAgentId().equals(agentId))
            .toList();
    }
    
    /**
     * Clear alerts older than the analysis window.
     */
    public void pruneOldData() {
        Instant cutoff = Instant.now().minus(config.getAnalysisWindow());
        
        // Prune alerts
        recentAlerts.removeIf(a -> a.getTimestamp().isBefore(cutoff));
        
        // Prune graphs
        for (ServiceDependencyGraph graph : agentGraphs.values()) {
            graph.pruneOlderThan(config.getAnalysisWindow());
        }
    }
    
    /**
     * Get configuration.
     */
    public CompositionConfig getConfig() {
        return config;
    }
    
    private void notifyListeners(SafetyAlert alert) {
        for (AlertListener listener : alertListeners) {
            try {
                listener.onAlert(alert);
            } catch (Exception e) {
                System.err.println("Alert listener error: " + e.getMessage());
            }
        }
    }
    
    // ========================================================================
    // Demonstration
    // ========================================================================
    
    /**
     * Demonstration of ServiceCompositionAnalyzer capabilities.
     */
    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("SERVICE COMPOSITION ANALYZER DEMONSTRATION");
        System.out.println("=".repeat(70));
        System.out.println();
        System.out.println("This demonstrates the CAIS safety monitoring of service composition.");
        System.out.println("Per v0.5plus-requests.md: 'You have services; you need observability'");
        System.out.println();
        
        // Create analyzer with default config
        ServiceCompositionAnalyzer analyzer = new ServiceCompositionAnalyzer();
        
        // Add alert listener
        analyzer.addAlertListener(alert -> {
            System.out.println("  [ALERT] " + alert);
        });
        
        // Scenario 1: Normal shallow composition
        System.out.println("-".repeat(70));
        System.out.println("SCENARIO 1: Normal Shallow Composition");
        System.out.println("-".repeat(70));
        System.out.println();
        
        String agent1 = "news-agent-1";
        
        // Simulate a simple news search workflow
        ServiceInvocationEvent e1 = new ServiceInvocationEvent(
            agent1, ServiceType.KNOWLEDGE_RETRIEVAL, "inv-1", null);
        analyzer.handleInvocation(e1);
        
        ServiceInvocationEvent e2 = new ServiceInvocationEvent(
            agent1, ServiceType.TEXT_SUMMARIZATION, "inv-2", "inv-1");
        analyzer.handleInvocation(e2);
        
        ServiceInvocationEvent e3 = new ServiceInvocationEvent(
            agent1, ServiceType.TEXT_GENERATION, "inv-3", "inv-2");
        analyzer.handleInvocation(e3);
        
        CompositionMetrics metrics1 = analyzer.getMetrics(agent1);
        System.out.println("Agent: " + agent1);
        System.out.println("  Max Depth: " + metrics1.getMaxDepthObserved());
        System.out.println("  Total Invocations: " + metrics1.getTotalInvocations());
        System.out.println("  Service Types Used: " + metrics1.getUniqueServiceTypes());
        System.out.println("  Service Diversity: " + String.format("%.1f%%", 
            metrics1.getServiceDiversity() * 100));
        System.out.println("  Status: NORMAL (depth within limits)");
        System.out.println();
        
        // Scenario 2: Deep composition exceeding soft limit
        System.out.println("-".repeat(70));
        System.out.println("SCENARIO 2: Deep Composition (Exceeds Soft Limit)");
        System.out.println("-".repeat(70));
        System.out.println();
        
        String agent2 = "research-agent-1";
        
        // Build a deep chain of 12 invocations
        String parentId = null;
        for (int i = 1; i <= 12; i++) {
            String invId = "deep-inv-" + i;
            ServiceType type = ServiceType.values()[i % ServiceType.values().length];
            ServiceInvocationEvent event = new ServiceInvocationEvent(
                agent2, type, invId, parentId);
            InvocationResult result = analyzer.handleInvocation(event);
            
            if (!result.getAlerts().isEmpty()) {
                System.out.println("  Alerts at depth " + i + ":");
                for (SafetyAlert alert : result.getAlerts()) {
                    System.out.println("    - " + alert.getMessage());
                }
            }
            
            parentId = invId;
        }
        
        CompositionMetrics metrics2 = analyzer.getMetrics(agent2);
        System.out.println();
        System.out.println("Agent: " + agent2);
        System.out.println("  Max Depth: " + metrics2.getMaxDepthObserved());
        System.out.println("  Soft Limit: " + analyzer.getConfig().getSoftDepthLimit());
        System.out.println("  Hard Limit: " + analyzer.getConfig().getHardDepthLimit());
        System.out.println("  Status: ALERT (depth exceeds soft limit)");
        System.out.println();
        
        // Scenario 3: Concerning service combinations
        System.out.println("-".repeat(70));
        System.out.println("SCENARIO 3: Concerning Service Combinations");
        System.out.println("-".repeat(70));
        System.out.println();
        
        String agent3 = "analysis-agent-1";
        
        // Reasoning + Code Analysis combination
        ServiceInvocationEvent r1 = new ServiceInvocationEvent(
            agent3, ServiceType.REASONING, "concern-1", null);
        analyzer.handleInvocation(r1);
        
        ServiceInvocationEvent r2 = new ServiceInvocationEvent(
            agent3, ServiceType.CODE_ANALYSIS, "concern-2", "concern-1");
        InvocationResult result3 = analyzer.handleInvocation(r2);
        
        System.out.println("Agent: " + agent3);
        System.out.println("  Chain: REASONING → CODE_ANALYSIS");
        System.out.println("  This combination flagged because:");
        System.out.println("    Reasoning + code analysis can enable self-modification patterns");
        System.out.println();
        
        // Scenario 4: Summary of all alerts
        System.out.println("-".repeat(70));
        System.out.println("ALERT SUMMARY");
        System.out.println("-".repeat(70));
        System.out.println();
        
        List<SafetyAlert> allAlerts = analyzer.getRecentAlerts();
        Map<SafetyAlert.Severity, Long> bySeverity = allAlerts.stream()
            .collect(Collectors.groupingBy(SafetyAlert::getSeverity, Collectors.counting()));
        
        System.out.println("Total Alerts: " + allAlerts.size());
        for (SafetyAlert.Severity severity : SafetyAlert.Severity.values()) {
            long count = bySeverity.getOrDefault(severity, 0L);
            if (count > 0) {
                System.out.println("  " + severity + ": " + count);
            }
        }
        System.out.println();
        
        System.out.println("=".repeat(70));
        System.out.println("END OF DEMONSTRATION");
        System.out.println("=".repeat(70));
    }
}
