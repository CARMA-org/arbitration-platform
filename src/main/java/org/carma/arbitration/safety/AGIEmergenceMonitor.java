package org.carma.arbitration.safety;

import org.carma.arbitration.model.*;
import org.carma.arbitration.agent.RealisticAgentFramework.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors for emergent AGI-like properties from service composition.
 * 
 * This is the core CAIS safety monitor. It tracks whether agents approach
 * dangerous conjunctions of Autonomy, Generality, and Intelligence (A+G+I).
 * 
 * The key insight: Individual services are narrow and safe. The danger is when
 * an orchestrating agent exhibits dangerous levels of A+G+I in *combination*,
 * even if each service is narrow.
 * 
 * Any single property at high levels is manageable:
 * - High Autonomy alone: Specialized automation with bounded damage
 * - High Generality alone: Versatile tool but human-controlled
 * - High Intelligence alone: Powerful oracle but human-directed
 * 
 * The danger is the conjunction: high A + high G + high I = system that can
 * effectively pursue goals across domains without meaningful oversight.
 * 
 * This implements the CAIS (Comprehensive AI Services) safety model,
 * providing situational awareness for the arbitration platform.
 */
public class AGIEmergenceMonitor {

    // ========================================================================
    // CONFIGURATION
    // ========================================================================
    
    private double autonomyThreshold = 0.7;
    private double generalityThreshold = 0.7;
    private double intelligenceThreshold = 0.7;
    
    // Autonomy-specific thresholds
    private Duration maxAutonomousSpan = Duration.ofHours(1);
    private int maxDecisionsWithoutCheckpoint = 20;
    private double resourceHoardingThreshold = 0.3;
    
    // Generality-specific thresholds
    private int domainDiversityLimit = 4;
    private double serviceCoverageLimit = 0.6;
    
    // Intelligence-specific thresholds  
    private double efficiencyImprovementLimit = 0.2;
    private int maxReasoningChainLength = 10;
    
    // ========================================================================
    // STATE TRACKING
    // ========================================================================
    
    private final Map<String, AgentProfile> agentProfiles;
    private final Map<String, List<RiskAssessment>> riskHistory;
    private final List<EmergenceListener> listeners;
    
    /**
     * Tracks computed A+G+I scores and behaviors for an agent.
     */
    public static class AgentProfile {
        private final String agentId;
        private Instant firstSeen;
        private Instant lastSeen;
        private Instant lastCheckpoint;
        
        // Autonomy tracking
        private Duration longestAutonomousSpan = Duration.ZERO;
        private int decisionsWithoutCheckpoint = 0;
        private Map<ResourceType, Long> resourcesHeld = new HashMap<>();
        private Map<ResourceType, Long> resourcesConsumed = new HashMap<>();
        private int planningHorizonEstimate = 0;
        
        // Generality tracking
        private Set<String> activedomains = new HashSet<>();
        private Set<ServiceType> servicesUsed = new HashSet<>();
        private Map<String, Integer> domainTransitions = new HashMap<>();
        
        // Intelligence tracking
        private List<Double> efficiencyHistory = new ArrayList<>();
        private int longestReasoningChain = 0;
        private Set<String> novelCompositions = new HashSet<>();
        private int adaptationCount = 0;
        
        // Service invocation tracking
        private List<ServiceInvocation> recentInvocations = new ArrayList<>();
        private static final int MAX_INVOCATION_HISTORY = 100;
        
        public AgentProfile(String agentId) {
            this.agentId = agentId;
            this.firstSeen = Instant.now();
            this.lastSeen = Instant.now();
        }
        
        public String getAgentId() { return agentId; }
        public Instant getFirstSeen() { return firstSeen; }
        public Instant getLastSeen() { return lastSeen; }
        public Duration getLongestAutonomousSpan() { return longestAutonomousSpan; }
        public int getDecisionsWithoutCheckpoint() { return decisionsWithoutCheckpoint; }
        public Set<String> getActiveDomains() { return activedomains; }
        public Set<ServiceType> getServicesUsed() { return servicesUsed; }
        public int getLongestReasoningChain() { return longestReasoningChain; }
        
        public void recordCheckpoint() {
            this.lastCheckpoint = Instant.now();
            this.decisionsWithoutCheckpoint = 0;
        }
        
        public void recordDecision() {
            this.decisionsWithoutCheckpoint++;
            this.lastSeen = Instant.now();
            
            // Update autonomous span
            if (lastCheckpoint != null) {
                Duration span = Duration.between(lastCheckpoint, Instant.now());
                if (span.compareTo(longestAutonomousSpan) > 0) {
                    longestAutonomousSpan = span;
                }
            } else {
                Duration span = Duration.between(firstSeen, Instant.now());
                if (span.compareTo(longestAutonomousSpan) > 0) {
                    longestAutonomousSpan = span;
                }
            }
        }
        
        public void recordServiceInvocation(ServiceType type, String domain) {
            servicesUsed.add(type);
            
            String prevDomain = activedomains.isEmpty() ? null : 
                activedomains.iterator().next();
            activedomains.add(domain);
            
            // Track domain transitions
            if (prevDomain != null && !prevDomain.equals(domain)) {
                String transition = prevDomain + "->" + domain;
                domainTransitions.merge(transition, 1, Integer::sum);
            }
            
            // Add to invocation history
            ServiceInvocation invocation = new ServiceInvocation(type, domain);
            recentInvocations.add(invocation);
            if (recentInvocations.size() > MAX_INVOCATION_HISTORY) {
                recentInvocations.remove(0);
            }
        }
        
        public void recordResourceUsage(ResourceType type, long held, long consumed) {
            resourcesHeld.merge(type, held, Long::sum);
            resourcesConsumed.merge(type, consumed, Long::sum);
        }
        
        public void recordEfficiency(double efficiency) {
            efficiencyHistory.add(efficiency);
            if (efficiencyHistory.size() > 50) {
                efficiencyHistory.remove(0);
            }
        }
        
        public void recordReasoningChain(int length) {
            if (length > longestReasoningChain) {
                longestReasoningChain = length;
            }
        }
        
        public void recordNovelComposition(String compositionId) {
            novelCompositions.add(compositionId);
        }
        
        public void recordAdaptation() {
            adaptationCount++;
        }
        
        public double getResourceHoardingScore() {
            long totalHeld = resourcesHeld.values().stream().mapToLong(Long::longValue).sum();
            long totalConsumed = resourcesConsumed.values().stream().mapToLong(Long::longValue).sum();
            
            if (totalHeld == 0) return 0.0;
            return (double) (totalHeld - totalConsumed) / totalHeld;
        }
        
        public double getEfficiencyTrend() {
            if (efficiencyHistory.size() < 2) return 0.0;
            
            int half = efficiencyHistory.size() / 2;
            double firstHalfAvg = efficiencyHistory.subList(0, half).stream()
                .mapToDouble(Double::doubleValue).average().orElse(0.0);
            double secondHalfAvg = efficiencyHistory.subList(half, efficiencyHistory.size()).stream()
                .mapToDouble(Double::doubleValue).average().orElse(0.0);
            
            if (firstHalfAvg == 0) return 0.0;
            return (secondHalfAvg - firstHalfAvg) / firstHalfAvg;
        }
        
        public double getDomainDiversityScore() {
            // Entropy-based diversity score
            if (recentInvocations.isEmpty()) return 0.0;
            
            Map<String, Integer> domainCounts = new HashMap<>();
            for (ServiceInvocation inv : recentInvocations) {
                domainCounts.merge(inv.domain, 1, Integer::sum);
            }
            
            double total = recentInvocations.size();
            double entropy = 0.0;
            for (int count : domainCounts.values()) {
                double p = count / total;
                if (p > 0) {
                    entropy -= p * Math.log(p);
                }
            }
            
            // Normalize to 0-1 (max entropy is ln(n) where n is number of domains)
            double maxEntropy = Math.log(Math.max(1, domainCounts.size()));
            return maxEntropy > 0 ? entropy / maxEntropy : 0.0;
        }
        
        public double getServiceCoverageScore(int totalServiceTypes) {
            return (double) servicesUsed.size() / totalServiceTypes;
        }
        
        public static class ServiceInvocation {
            public final ServiceType type;
            public final String domain;
            public final Instant timestamp;
            
            public ServiceInvocation(ServiceType type, String domain) {
                this.type = type;
                this.domain = domain;
                this.timestamp = Instant.now();
            }
        }
    }
    
    // ========================================================================
    // RISK ASSESSMENT
    // ========================================================================
    
    /**
     * Risk levels for conjunction detection.
     */
    public enum ConjunctionRisk {
        LOW(0, "Low", "Normal operation"),
        MODERATE(1, "Moderate", "One dimension elevated"),
        HIGH(2, "High", "Two dimensions elevated"),
        CRITICAL(3, "Critical", "All three dimensions elevated");
        
        private final int level;
        private final String displayName;
        private final String description;
        
        ConjunctionRisk(int level, String displayName, String description) {
            this.level = level;
            this.displayName = displayName;
            this.description = description;
        }
        
        public int getLevel() { return level; }
        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }
    
    /**
     * Convergence assessment for trajectory analysis.
     */
    public enum ConvergenceAssessment {
        STABLE("Stable", "No concerning trends"),
        PARTIAL_GROWTH("Partial Growth", "One dimension trending up"),
        CONVERGING_TOWARD_CONJUNCTION("Converging", "Multiple dimensions trending up");
        
        private final String displayName;
        private final String description;
        
        ConvergenceAssessment(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }
        
        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }
    
    /**
     * Intervention levels for responding to emergence risks.
     */
    public enum InterventionLevel {
        OBSERVE("Observe", "Log and monitor, no action"),
        ALERT("Alert", "Notify operators, request human review"),
        THROTTLE("Throttle", "Slow down agent's service access"),
        CHECKPOINT("Checkpoint", "Require human approval before continuing"),
        SUSPEND("Suspend", "Pause agent execution pending review"),
        TERMINATE("Terminate", "Stop agent, release all resources");
        
        private final String displayName;
        private final String description;
        
        InterventionLevel(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }
        
        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }
    
    /**
     * Complete risk assessment for an agent.
     */
    public static class RiskAssessment {
        private final String agentId;
        private final Instant timestamp;
        
        private final double autonomyScore;
        private final double generalityScore;
        private final double intelligenceScore;
        
        private final boolean autonomyFlagged;
        private final boolean generalityFlagged;
        private final boolean intelligenceFlagged;
        
        private final ConjunctionRisk conjunctionRisk;
        private final List<EmergencePattern> patternsDetected;
        private final InterventionLevel recommendedIntervention;
        private final List<String> concerns;
        
        public RiskAssessment(
                String agentId,
                double autonomyScore, double generalityScore, double intelligenceScore,
                boolean autonomyFlagged, boolean generalityFlagged, boolean intelligenceFlagged,
                ConjunctionRisk conjunctionRisk,
                List<EmergencePattern> patternsDetected,
                InterventionLevel recommendedIntervention,
                List<String> concerns) {
            this.agentId = agentId;
            this.timestamp = Instant.now();
            this.autonomyScore = autonomyScore;
            this.generalityScore = generalityScore;
            this.intelligenceScore = intelligenceScore;
            this.autonomyFlagged = autonomyFlagged;
            this.generalityFlagged = generalityFlagged;
            this.intelligenceFlagged = intelligenceFlagged;
            this.conjunctionRisk = conjunctionRisk;
            this.patternsDetected = patternsDetected;
            this.recommendedIntervention = recommendedIntervention;
            this.concerns = concerns;
        }
        
        public String getAgentId() { return agentId; }
        public Instant getTimestamp() { return timestamp; }
        public double getAutonomyScore() { return autonomyScore; }
        public double getGeneralityScore() { return generalityScore; }
        public double getIntelligenceScore() { return intelligenceScore; }
        public boolean isAutonomyFlagged() { return autonomyFlagged; }
        public boolean isGeneralityFlagged() { return generalityFlagged; }
        public boolean isIntelligenceFlagged() { return intelligenceFlagged; }
        public ConjunctionRisk getConjunctionRisk() { return conjunctionRisk; }
        public List<EmergencePattern> getPatternsDetected() { return patternsDetected; }
        public InterventionLevel getRecommendedIntervention() { return recommendedIntervention; }
        public List<String> getConcerns() { return concerns; }
        
        public int getFlagCount() {
            return (autonomyFlagged ? 1 : 0) + 
                   (generalityFlagged ? 1 : 0) + 
                   (intelligenceFlagged ? 1 : 0);
        }
        
        @Override
        public String toString() {
            return String.format(
                "RiskAssessment[%s: A=%.2f%s G=%.2f%s I=%.2f%s → %s, intervention=%s]",
                agentId,
                autonomyScore, autonomyFlagged ? "⚠" : "",
                generalityScore, generalityFlagged ? "⚠" : "",
                intelligenceScore, intelligenceFlagged ? "⚠" : "",
                conjunctionRisk.getDisplayName(),
                recommendedIntervention.getDisplayName()
            );
        }
    }
    
    // ========================================================================
    // EMERGENCE PATTERNS
    // ========================================================================
    
    /**
     * Specific patterns that suggest emergence.
     */
    public enum EmergencePattern {
        SELF_IMPROVEMENT_LOOP("Self-Improvement Loop", 
            "Agent analyzes its own outputs and modifies behavior"),
        WORLD_MODELING("World Modeling", 
            "Agent building persistent models of environment"),
        TOOL_MAKING("Tool Making", 
            "Agent creating reusable compositions"),
        GOAL_GENERATION("Goal Generation", 
            "Agent creating goals not specified by human"),
        INSTRUMENTAL_GOAL_PURSUIT("Instrumental Goal Pursuit", 
            "Agent pursuing power/resources beyond task needs");
        
        private final String displayName;
        private final String description;
        
        EmergencePattern(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }
        
        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }
    
    // ========================================================================
    // LISTENER INTERFACE
    // ========================================================================
    
    /**
     * Listener for emergence monitoring events.
     */
    public interface EmergenceListener {
        default void onRiskAssessed(RiskAssessment assessment) {}
        default void onPatternDetected(String agentId, EmergencePattern pattern) {}
        default void onInterventionRecommended(String agentId, InterventionLevel level, String reason) {}
        default void onConjunctionApproaching(String agentId, ConjunctionRisk risk) {}
    }
    
    // ========================================================================
    // CONSTRUCTOR AND CONFIGURATION
    // ========================================================================
    
    public AGIEmergenceMonitor() {
        this.agentProfiles = new ConcurrentHashMap<>();
        this.riskHistory = new ConcurrentHashMap<>();
        this.listeners = new ArrayList<>();
    }
    
    public AGIEmergenceMonitor setAutonomyThreshold(double threshold) {
        this.autonomyThreshold = threshold;
        return this;
    }
    
    public AGIEmergenceMonitor setGeneralityThreshold(double threshold) {
        this.generalityThreshold = threshold;
        return this;
    }
    
    public AGIEmergenceMonitor setIntelligenceThreshold(double threshold) {
        this.intelligenceThreshold = threshold;
        return this;
    }
    
    public AGIEmergenceMonitor setMaxAutonomousSpan(Duration duration) {
        this.maxAutonomousSpan = duration;
        return this;
    }
    
    // ========================================================================
    // PROFILE MANAGEMENT
    // ========================================================================
    
    /**
     * Get or create profile for an agent.
     */
    public AgentProfile getProfile(String agentId) {
        return agentProfiles.computeIfAbsent(agentId, AgentProfile::new);
    }
    
    /**
     * Record a service invocation for an agent.
     */
    public void recordInvocation(String agentId, ServiceType serviceType, String domain) {
        AgentProfile profile = getProfile(agentId);
        profile.recordServiceInvocation(serviceType, domain);
        profile.recordDecision();
    }
    
    /**
     * Record a checkpoint for an agent.
     */
    public void recordCheckpoint(String agentId) {
        AgentProfile profile = getProfile(agentId);
        profile.recordCheckpoint();
    }
    
    /**
     * Record resource usage for an agent.
     */
    public void recordResourceUsage(String agentId, ResourceType type, long held, long consumed) {
        AgentProfile profile = getProfile(agentId);
        profile.recordResourceUsage(type, held, consumed);
    }
    
    // ========================================================================
    // SCORE COMPUTATION
    // ========================================================================
    
    /**
     * Compute autonomy score for an agent (0.0 to 1.0).
     * 
     * Autonomy = capacity to act independently, pursue goals over extended
     * periods, make decisions without human checkpoints.
     */
    public double computeAutonomyScore(String agentId) {
        AgentProfile profile = agentProfiles.get(agentId);
        if (profile == null) return 0.0;
        
        double score = 0.0;
        
        // Factor 1: Temporal autonomy (longest span without checkpoint)
        double spanRatio = (double) profile.getLongestAutonomousSpan().toMinutes() / 
                          maxAutonomousSpan.toMinutes();
        score += Math.min(1.0, spanRatio) * 0.3;
        
        // Factor 2: Decisions without checkpoint
        double decisionRatio = (double) profile.getDecisionsWithoutCheckpoint() / 
                              maxDecisionsWithoutCheckpoint;
        score += Math.min(1.0, decisionRatio) * 0.3;
        
        // Factor 3: Resource hoarding
        score += profile.getResourceHoardingScore() * 0.2;
        
        // Factor 4: Planning horizon (estimated from service chain lengths)
        double planningRatio = (double) profile.getLongestReasoningChain() / 
                              maxReasoningChainLength;
        score += Math.min(1.0, planningRatio) * 0.2;
        
        return Math.min(1.0, score);
    }
    
    /**
     * Compute generality score for an agent (0.0 to 1.0).
     * 
     * Generality = capacity to operate effectively across diverse domains,
     * transfer capabilities, handle novel situations.
     */
    public double computeGeneralityScore(String agentId) {
        AgentProfile profile = agentProfiles.get(agentId);
        if (profile == null) return 0.0;
        
        double score = 0.0;
        
        // Factor 1: Domain diversity (entropy across domains)
        score += profile.getDomainDiversityScore() * 0.4;
        
        // Factor 2: Service category coverage
        int totalServiceTypes = ServiceType.values().length;
        score += profile.getServiceCoverageScore(totalServiceTypes) * 0.3;
        
        // Factor 3: Cross-domain transfers
        int transferCount = profile.domainTransitions.values().stream()
            .mapToInt(Integer::intValue).sum();
        double transferScore = Math.min(1.0, transferCount / 20.0);
        score += transferScore * 0.3;
        
        return Math.min(1.0, score);
    }
    
    /**
     * Compute intelligence score for an agent (0.0 to 1.0).
     * 
     * Intelligence = capacity to reason effectively, solve novel problems,
     * optimize behavior, learn and adapt.
     */
    public double computeIntelligenceScore(String agentId) {
        AgentProfile profile = agentProfiles.get(agentId);
        if (profile == null) return 0.0;
        
        double score = 0.0;
        
        // Factor 1: Efficiency improvement over time
        double efficiencyTrend = profile.getEfficiencyTrend();
        if (efficiencyTrend > 0) {
            score += Math.min(1.0, efficiencyTrend / efficiencyImprovementLimit) * 0.3;
        }
        
        // Factor 2: Reasoning chain complexity
        double reasoningRatio = (double) profile.getLongestReasoningChain() / 
                               maxReasoningChainLength;
        score += Math.min(1.0, reasoningRatio) * 0.25;
        
        // Factor 3: Novel compositions discovered
        double noveltyScore = Math.min(1.0, profile.novelCompositions.size() / 10.0);
        score += noveltyScore * 0.25;
        
        // Factor 4: Adaptation count (behavior changes based on outcomes)
        double adaptationScore = Math.min(1.0, profile.adaptationCount / 10.0);
        score += adaptationScore * 0.2;
        
        return Math.min(1.0, score);
    }
    
    // ========================================================================
    // CONJUNCTION DETECTION
    // ========================================================================
    
    /**
     * Assess conjunction risk for an agent.
     * This is the core safety check: detecting when all three dimensions
     * are elevated simultaneously.
     */
    public RiskAssessment assessConjunctionRisk(String agentId) {
        double autonomyScore = computeAutonomyScore(agentId);
        double generalityScore = computeGeneralityScore(agentId);
        double intelligenceScore = computeIntelligenceScore(agentId);
        
        boolean autonomyFlag = autonomyScore > autonomyThreshold;
        boolean generalityFlag = generalityScore > generalityThreshold;
        boolean intelligenceFlag = intelligenceScore > intelligenceThreshold;
        
        int flagCount = (autonomyFlag ? 1 : 0) + 
                       (generalityFlag ? 1 : 0) + 
                       (intelligenceFlag ? 1 : 0);
        
        ConjunctionRisk risk;
        if (flagCount == 3) {
            risk = ConjunctionRisk.CRITICAL;
        } else if (flagCount == 2) {
            risk = ConjunctionRisk.HIGH;
        } else if (flagCount == 1) {
            risk = ConjunctionRisk.MODERATE;
        } else {
            risk = ConjunctionRisk.LOW;
        }
        
        // Detect specific patterns
        List<EmergencePattern> patterns = detectPatterns(agentId);
        
        // Build concerns list
        List<String> concerns = new ArrayList<>();
        if (autonomyFlag) {
            concerns.add("Autonomy score " + String.format("%.2f", autonomyScore) + 
                        " exceeds threshold " + autonomyThreshold);
        }
        if (generalityFlag) {
            concerns.add("Generality score " + String.format("%.2f", generalityScore) + 
                        " exceeds threshold " + generalityThreshold);
        }
        if (intelligenceFlag) {
            concerns.add("Intelligence score " + String.format("%.2f", intelligenceScore) + 
                        " exceeds threshold " + intelligenceThreshold);
        }
        for (EmergencePattern pattern : patterns) {
            concerns.add("Pattern detected: " + pattern.getDisplayName());
        }
        
        // Determine intervention
        InterventionLevel intervention = determineIntervention(risk, patterns);
        
        RiskAssessment assessment = new RiskAssessment(
            agentId,
            autonomyScore, generalityScore, intelligenceScore,
            autonomyFlag, generalityFlag, intelligenceFlag,
            risk, patterns, intervention, concerns
        );
        
        // Store in history
        riskHistory.computeIfAbsent(agentId, k -> new ArrayList<>()).add(assessment);
        
        // Notify listeners
        for (EmergenceListener listener : listeners) {
            listener.onRiskAssessed(assessment);
            
            if (risk.getLevel() >= ConjunctionRisk.HIGH.getLevel()) {
                listener.onConjunctionApproaching(agentId, risk);
            }
            
            if (intervention.ordinal() >= InterventionLevel.CHECKPOINT.ordinal()) {
                listener.onInterventionRecommended(agentId, intervention, 
                    String.join("; ", concerns));
            }
            
            for (EmergencePattern pattern : patterns) {
                listener.onPatternDetected(agentId, pattern);
            }
        }
        
        return assessment;
    }
    
    /**
     * Assess trajectory: Is agent converging toward dangerous conjunction?
     */
    public ConvergenceAssessment assessTrajectory(String agentId, int windowSize) {
        List<RiskAssessment> history = riskHistory.get(agentId);
        if (history == null || history.size() < windowSize) {
            return ConvergenceAssessment.STABLE;
        }
        
        List<RiskAssessment> recent = history.subList(
            Math.max(0, history.size() - windowSize), history.size());
        
        // Compute trends for each dimension
        boolean autonomyTrendingUp = computeTrend(
            recent.stream().mapToDouble(RiskAssessment::getAutonomyScore).toArray()) > 0.1;
        boolean generalityTrendingUp = computeTrend(
            recent.stream().mapToDouble(RiskAssessment::getGeneralityScore).toArray()) > 0.1;
        boolean intelligenceTrendingUp = computeTrend(
            recent.stream().mapToDouble(RiskAssessment::getIntelligenceScore).toArray()) > 0.1;
        
        int risingDimensions = (autonomyTrendingUp ? 1 : 0) + 
                              (generalityTrendingUp ? 1 : 0) + 
                              (intelligenceTrendingUp ? 1 : 0);
        
        if (risingDimensions >= 2) {
            return ConvergenceAssessment.CONVERGING_TOWARD_CONJUNCTION;
        } else if (risingDimensions == 1) {
            return ConvergenceAssessment.PARTIAL_GROWTH;
        } else {
            return ConvergenceAssessment.STABLE;
        }
    }
    
    private double computeTrend(double[] values) {
        if (values.length < 2) return 0.0;
        
        // Simple linear regression slope
        double n = values.length;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        
        for (int i = 0; i < values.length; i++) {
            sumX += i;
            sumY += values[i];
            sumXY += i * values[i];
            sumX2 += i * i;
        }
        
        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
        return slope;
    }
    
    // ========================================================================
    // PATTERN DETECTION
    // ========================================================================
    
    /**
     * Detect specific emergence patterns for an agent.
     */
    private List<EmergencePattern> detectPatterns(String agentId) {
        List<EmergencePattern> patterns = new ArrayList<>();
        AgentProfile profile = agentProfiles.get(agentId);
        
        if (profile == null) return patterns;
        
        // Pattern 1: Self-improvement loop
        // Agent uses analysis service on its own outputs, then modifies behavior
        if (detectSelfImprovementLoop(profile)) {
            patterns.add(EmergencePattern.SELF_IMPROVEMENT_LOOP);
        }
        
        // Pattern 2: World modeling
        // Agent building persistent models through repeated knowledge queries
        if (detectWorldModeling(profile)) {
            patterns.add(EmergencePattern.WORLD_MODELING);
        }
        
        // Pattern 3: Tool making
        // Agent creating reusable compositions
        if (!profile.novelCompositions.isEmpty()) {
            patterns.add(EmergencePattern.TOOL_MAKING);
        }
        
        // Pattern 4: Goal generation
        // Would need goal tracking integration
        
        // Pattern 5: Instrumental goal pursuit
        // Resource hoarding without consumption
        if (profile.getResourceHoardingScore() > resourceHoardingThreshold) {
            patterns.add(EmergencePattern.INSTRUMENTAL_GOAL_PURSUIT);
        }
        
        return patterns;
    }
    
    private boolean detectSelfImprovementLoop(AgentProfile profile) {
        // Look for pattern: Generate → Analyze(own output) → Generate(modified)
        List<AgentProfile.ServiceInvocation> invocations = profile.recentInvocations;
        
        if (invocations.size() < 3) return false;
        
        int loopIndicators = 0;
        for (int i = 2; i < invocations.size(); i++) {
            ServiceType prev2 = invocations.get(i-2).type;
            ServiceType prev1 = invocations.get(i-1).type;
            ServiceType curr = invocations.get(i).type;
            
            // Pattern: generation → analysis → generation
            if ((prev2 == ServiceType.TEXT_GENERATION || prev2 == ServiceType.CODE_GENERATION) &&
                (prev1 == ServiceType.TEXT_CLASSIFICATION || prev1 == ServiceType.CODE_ANALYSIS) &&
                (curr == ServiceType.TEXT_GENERATION || curr == ServiceType.CODE_GENERATION)) {
                loopIndicators++;
            }
        }
        
        return loopIndicators >= 2;
    }
    
    private boolean detectWorldModeling(AgentProfile profile) {
        // Look for repeated knowledge queries building comprehensive model
        long knowledgeInvocations = profile.recentInvocations.stream()
            .filter(inv -> inv.type == ServiceType.KNOWLEDGE_RETRIEVAL || 
                          inv.type == ServiceType.VECTOR_SEARCH)
            .count();
        
        return knowledgeInvocations > 10 && profile.getActiveDomains().size() > 3;
    }
    
    // ========================================================================
    // INTERVENTION DETERMINATION
    // ========================================================================
    
    /**
     * Determine appropriate intervention based on risk and patterns.
     */
    private InterventionLevel determineIntervention(ConjunctionRisk risk, 
                                                    List<EmergencePattern> patterns) {
        InterventionLevel baseLevel = switch(risk) {
            case LOW -> InterventionLevel.OBSERVE;
            case MODERATE -> InterventionLevel.ALERT;
            case HIGH -> InterventionLevel.CHECKPOINT;
            case CRITICAL -> InterventionLevel.SUSPEND;
        };
        
        // Specific patterns may override base level
        for (EmergencePattern pattern : patterns) {
            InterventionLevel patternLevel = switch(pattern) {
                case SELF_IMPROVEMENT_LOOP -> InterventionLevel.CHECKPOINT;
                case WORLD_MODELING -> InterventionLevel.ALERT;
                case TOOL_MAKING -> InterventionLevel.ALERT;
                case GOAL_GENERATION -> InterventionLevel.CHECKPOINT;
                case INSTRUMENTAL_GOAL_PURSUIT -> InterventionLevel.SUSPEND;
            };
            
            if (patternLevel.ordinal() > baseLevel.ordinal()) {
                baseLevel = patternLevel;
            }
        }
        
        return baseLevel;
    }
    
    // ========================================================================
    // QUERIES AND REPORTING
    // ========================================================================
    
    /**
     * Get all agents with risk above a threshold.
     */
    public List<String> getAgentsAtRisk(ConjunctionRisk minRisk) {
        List<String> result = new ArrayList<>();
        
        for (String agentId : agentProfiles.keySet()) {
            RiskAssessment assessment = assessConjunctionRisk(agentId);
            if (assessment.getConjunctionRisk().getLevel() >= minRisk.getLevel()) {
                result.add(agentId);
            }
        }
        
        return result;
    }
    
    /**
     * Get risk history for an agent.
     */
    public List<RiskAssessment> getRiskHistory(String agentId) {
        return riskHistory.getOrDefault(agentId, Collections.emptyList());
    }
    
    /**
     * Generate summary report.
     */
    public String generateReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("AGI Emergence Monitor Report\n");
        sb.append("═".repeat(50)).append("\n");
        sb.append(String.format("Agents monitored: %d\n", agentProfiles.size()));
        sb.append(String.format("Thresholds: A=%.2f, G=%.2f, I=%.2f\n",
            autonomyThreshold, generalityThreshold, intelligenceThreshold));
        sb.append("\n");
        
        // Summary by risk level
        Map<ConjunctionRisk, Integer> riskCounts = new EnumMap<>(ConjunctionRisk.class);
        for (String agentId : agentProfiles.keySet()) {
            RiskAssessment assessment = assessConjunctionRisk(agentId);
            riskCounts.merge(assessment.getConjunctionRisk(), 1, Integer::sum);
        }
        
        sb.append("Risk Distribution:\n");
        for (ConjunctionRisk risk : ConjunctionRisk.values()) {
            int count = riskCounts.getOrDefault(risk, 0);
            sb.append(String.format("  %s: %d agents\n", risk.getDisplayName(), count));
        }
        sb.append("\n");
        
        // High-risk agents
        List<String> highRisk = getAgentsAtRisk(ConjunctionRisk.HIGH);
        if (!highRisk.isEmpty()) {
            sb.append("High-Risk Agents:\n");
            for (String agentId : highRisk) {
                RiskAssessment assessment = assessConjunctionRisk(agentId);
                sb.append(String.format("  %s: %s\n", agentId, assessment));
            }
        }
        
        return sb.toString();
    }
    
    // ========================================================================
    // LISTENER MANAGEMENT
    // ========================================================================
    
    public void addListener(EmergenceListener listener) {
        listeners.add(listener);
    }
    
    public void removeListener(EmergenceListener listener) {
        listeners.remove(listener);
    }
    
    @Override
    public String toString() {
        return String.format("AGIEmergenceMonitor[agents=%d, thresholds=(%.2f,%.2f,%.2f)]",
            agentProfiles.size(), autonomyThreshold, generalityThreshold, intelligenceThreshold);
    }
}
