package org.carma.arbitration.agent;

import org.carma.arbitration.model.*;
import org.carma.arbitration.mechanism.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Framework for implementing realistic agents that operate within the arbitration platform.
 * 
 * This addresses Milestone 3's requirement for "broader support for goal structures,
 * preference structures, and situational awareness" by providing:
 * 
 * 1. Goal Structure: Agents have explicit goals with priorities and deadlines
 * 2. Autonomy Levels: Configurable autonomy from tool-like to fully autonomous
 * 3. Execution Model: Tick-based execution with human checkpoints
 * 4. Resource Awareness: Agents request resources through arbitration
 * 5. Safety Integration: All agent actions are monitored and bounded
 * 
 * Example usage:
 * <pre>
 * RealisticAgent newsAgent = new NewsSearchAgent.Builder("news-agent-1")
 *     .setAutonomyLevel(AutonomyLevel.LOW)
 *     .addGoal(new Goal("find-news", GoalType.PERIODIC, Duration.ofHours(1)))
 *     .setOutputChannel(signalChannel)
 *     .build();
 * 
 * AgentRuntime runtime = new AgentRuntime(arbitrator, pool);
 * runtime.register(newsAgent);
 * runtime.start();
 * </pre>
 */
public class RealisticAgentFramework {

    // ========================================================================
    // AUTONOMY LEVELS
    // ========================================================================
    
    /**
     * Defines the level of autonomous operation permitted for an agent.
     * Aligns with CAIS-like safety principles: narrow + tool-like = safer.
     */
    public enum AutonomyLevel {
        /**
         * Tool-like: Agent only acts when explicitly invoked.
         * No persistent goals, no self-initiated actions.
         * Example: A summarization agent that processes documents on demand.
         */
        TOOL(0, "Tool-like", Duration.ZERO, false),
        
        /**
         * Low autonomy: Agent can execute simple periodic tasks.
         * Limited to single-step actions, requires frequent checkpoints.
         * Example: A news monitoring agent that checks sources hourly.
         */
        LOW(1, "Low", Duration.ofMinutes(15), true),
        
        /**
         * Medium autonomy: Agent can pursue multi-step goals.
         * Can chain services, but requires checkpoint for novel situations.
         * Example: A research agent that gathers and synthesizes information.
         */
        MEDIUM(2, "Medium", Duration.ofHours(1), true),
        
        /**
         * High autonomy: Agent can adapt strategies and pursue complex goals.
         * Still bounded by resource limits and safety monitors.
         * Example: A project management agent coordinating multiple tasks.
         */
        HIGH(3, "High", Duration.ofHours(4), true);
        
        private final int level;
        private final String displayName;
        private final Duration maxAutonomousSpan;
        private final boolean canHavePersistentGoals;
        
        AutonomyLevel(int level, String displayName, Duration maxSpan, boolean persistentGoals) {
            this.level = level;
            this.displayName = displayName;
            this.maxAutonomousSpan = maxSpan;
            this.canHavePersistentGoals = persistentGoals;
        }
        
        public int getLevel() { return level; }
        public String getDisplayName() { return displayName; }
        public Duration getMaxAutonomousSpan() { return maxAutonomousSpan; }
        public boolean canHavePersistentGoals() { return canHavePersistentGoals; }
    }
    
    // ========================================================================
    // GOAL STRUCTURE
    // ========================================================================
    
    /**
     * Represents a goal that an agent is pursuing.
     * Goals can be one-time, periodic, or reactive (triggered by events).
     */
    public static class Goal {
        private final String goalId;
        private final String description;
        private final GoalType type;
        private final Duration period;          // For periodic goals
        private final Instant deadline;         // For one-time goals
        private final int priority;             // 1 (low) to 10 (high)
        private final Map<String, Object> parameters;
        private GoalStatus status;
        private Instant lastExecuted;
        private int executionCount;
        private String lastResult;
        
        public enum GoalType {
            ONE_TIME,       // Execute once and complete
            PERIODIC,       // Execute repeatedly on schedule
            REACTIVE,       // Execute in response to events
            CONTINUOUS      // Always active background goal
        }
        
        public enum GoalStatus {
            PENDING,        // Not yet started
            ACTIVE,         // Currently being pursued
            PAUSED,         // Temporarily suspended
            COMPLETED,      // Successfully achieved
            FAILED,         // Could not be achieved
            CANCELLED       // Cancelled by user/system
        }
        
        public Goal(String goalId, String description, GoalType type) {
            this(goalId, description, type, null, null, 5);
        }
        
        public Goal(String goalId, String description, GoalType type, Duration period) {
            this(goalId, description, type, period, null, 5);
        }
        
        public Goal(String goalId, String description, GoalType type, 
                    Duration period, Instant deadline, int priority) {
            this.goalId = goalId;
            this.description = description;
            this.type = type;
            this.period = period;
            this.deadline = deadline;
            this.priority = Math.max(1, Math.min(10, priority));
            this.parameters = new HashMap<>();
            this.status = GoalStatus.PENDING;
            this.executionCount = 0;
        }
        
        public String getGoalId() { return goalId; }
        public String getDescription() { return description; }
        public GoalType getType() { return type; }
        public Duration getPeriod() { return period; }
        public Instant getDeadline() { return deadline; }
        public int getPriority() { return priority; }
        public GoalStatus getStatus() { return status; }
        public Instant getLastExecuted() { return lastExecuted; }
        public int getExecutionCount() { return executionCount; }
        public String getLastResult() { return lastResult; }
        
        public void setParameter(String key, Object value) {
            parameters.put(key, value);
        }
        
        public Object getParameter(String key) {
            return parameters.get(key);
        }
        
        public void markExecuted(String result) {
            this.lastExecuted = Instant.now();
            this.executionCount++;
            this.lastResult = result;
        }
        
        public void setStatus(GoalStatus status) {
            this.status = status;
        }
        
        public boolean isDue() {
            if (type == GoalType.ONE_TIME && status == GoalStatus.COMPLETED) {
                return false;
            }
            if (type == GoalType.PERIODIC && period != null && lastExecuted != null) {
                return Instant.now().isAfter(lastExecuted.plus(period));
            }
            return status == GoalStatus.ACTIVE || status == GoalStatus.PENDING;
        }
        
        public boolean isExpired() {
            return deadline != null && Instant.now().isAfter(deadline);
        }
        
        @Override
        public String toString() {
            return String.format("Goal[%s: %s, type=%s, priority=%d, status=%s]",
                goalId, description, type, priority, status);
        }
    }
    
    // ========================================================================
    // OUTPUT CHANNELS
    // ========================================================================
    
    /**
     * Represents a channel where agents can publish their outputs.
     * This enables integration with external systems (Signal, Slack, files, etc.)
     */
    public interface OutputChannel {
        String getChannelId();
        String getChannelType();
        void publish(String agentId, String messageType, Object content);
        void close();
    }
    
    /**
     * Console output channel for testing/debugging.
     */
    public static class ConsoleChannel implements OutputChannel {
        private final String channelId;
        
        public ConsoleChannel(String channelId) {
            this.channelId = channelId;
        }
        
        @Override
        public String getChannelId() { return channelId; }
        
        @Override
        public String getChannelType() { return "console"; }
        
        @Override
        public void publish(String agentId, String messageType, Object content) {
            System.out.printf("[%s] Agent %s → %s: %s%n",
                Instant.now(), agentId, messageType, content);
        }
        
        @Override
        public void close() {}
    }
    
    /**
     * File-based output channel that appends to a log file.
     */
    public static class FileChannel implements OutputChannel {
        private final String channelId;
        private final String filePath;
        private final List<String> buffer;
        
        public FileChannel(String channelId, String filePath) {
            this.channelId = channelId;
            this.filePath = filePath;
            this.buffer = Collections.synchronizedList(new ArrayList<>());
        }
        
        @Override
        public String getChannelId() { return channelId; }
        
        @Override
        public String getChannelType() { return "file"; }
        
        @Override
        public void publish(String agentId, String messageType, Object content) {
            String entry = String.format("%s|%s|%s|%s",
                Instant.now(), agentId, messageType, content);
            buffer.add(entry);
        }
        
        @Override
        public void close() {
            // In production, would write buffer to file
            System.out.println("FileChannel buffer has " + buffer.size() + " entries");
        }
        
        public List<String> getBuffer() {
            return new ArrayList<>(buffer);
        }
    }
    
    /**
     * In-memory channel for collecting outputs during testing.
     */
    public static class MemoryChannel implements OutputChannel {
        private final String channelId;
        private final List<Message> messages;
        
        public static class Message {
            public final Instant timestamp;
            public final String agentId;
            public final String messageType;
            public final Object content;
            
            public Message(String agentId, String messageType, Object content) {
                this.timestamp = Instant.now();
                this.agentId = agentId;
                this.messageType = messageType;
                this.content = content;
            }
            
            @Override
            public String toString() {
                return String.format("Message[%s from %s: %s]", messageType, agentId, content);
            }
        }
        
        public MemoryChannel(String channelId) {
            this.channelId = channelId;
            this.messages = Collections.synchronizedList(new ArrayList<>());
        }
        
        @Override
        public String getChannelId() { return channelId; }
        
        @Override
        public String getChannelType() { return "memory"; }
        
        @Override
        public void publish(String agentId, String messageType, Object content) {
            messages.add(new Message(agentId, messageType, content));
        }
        
        @Override
        public void close() {}
        
        public List<Message> getMessages() {
            return new ArrayList<>(messages);
        }
        
        public List<Message> getMessagesFrom(String agentId) {
            return messages.stream()
                .filter(m -> m.agentId.equals(agentId))
                .toList();
        }
        
        public int getMessageCount() {
            return messages.size();
        }
        
        public void clear() {
            messages.clear();
        }
    }
    
    // ========================================================================
    // REALISTIC AGENT BASE CLASS
    // ========================================================================
    
    /**
     * Abstract base class for realistic agents that operate within the platform.
     * 
     * Subclasses implement the specific behavior in executeGoal() while this
     * base class handles:
     * - Resource acquisition through arbitration
     * - Goal scheduling and prioritization
     * - Output channel publishing
     * - Safety checkpoint enforcement
     * - Execution metrics tracking
     */
    public static abstract class RealisticAgent {
        protected final String agentId;
        protected final String name;
        protected final String description;
        protected final AutonomyLevel autonomyLevel;
        protected final List<Goal> goals;
        protected final Map<ResourceType, Double> resourcePreferences;
        protected final List<OutputChannel> outputChannels;
        protected final AgentMetrics metrics;
        
        // State
        protected AgentState state;
        protected Instant lastCheckpoint;
        protected Instant startedAt;
        protected BigDecimal currencyBalance;
        
        public enum AgentState {
            IDLE,           // Not running
            RUNNING,        // Actively executing
            WAITING,        // Waiting for resources
            CHECKPOINT,     // Awaiting human approval
            PAUSED,         // Temporarily paused
            TERMINATED      // Permanently stopped
        }
        
        /**
         * Metrics tracked for each agent.
         */
        public static class AgentMetrics {
            private int goalsAttempted = 0;
            private int goalsCompleted = 0;
            private int goalsFailed = 0;
            private long totalExecutionTimeMs = 0;
            private int serviceInvocations = 0;
            private int checkpointsReached = 0;
            private final Map<String, Integer> serviceUsage = new HashMap<>();
            
            public void recordGoalAttempt() { goalsAttempted++; }
            public void recordGoalCompletion() { goalsCompleted++; }
            public void recordGoalFailure() { goalsFailed++; }
            public void recordExecutionTime(long ms) { totalExecutionTimeMs += ms; }
            public void recordServiceInvocation(String serviceType) {
                serviceInvocations++;
                serviceUsage.merge(serviceType, 1, Integer::sum);
            }
            public void recordCheckpoint() { checkpointsReached++; }
            
            public int getGoalsAttempted() { return goalsAttempted; }
            public int getGoalsCompleted() { return goalsCompleted; }
            public int getGoalsFailed() { return goalsFailed; }
            public long getTotalExecutionTimeMs() { return totalExecutionTimeMs; }
            public int getServiceInvocations() { return serviceInvocations; }
            public Map<String, Integer> getServiceUsage() { return new HashMap<>(serviceUsage); }
            
            public double getSuccessRate() {
                if (goalsAttempted == 0) return 0.0;
                return (double) goalsCompleted / goalsAttempted;
            }
            
            @Override
            public String toString() {
                return String.format("Metrics[goals=%d/%d (%.1f%%), services=%d, time=%dms]",
                    goalsCompleted, goalsAttempted, getSuccessRate() * 100,
                    serviceInvocations, totalExecutionTimeMs);
            }
        }
        
        protected RealisticAgent(Builder<?> builder) {
            this.agentId = builder.agentId;
            this.name = builder.name;
            this.description = builder.description;
            this.autonomyLevel = builder.autonomyLevel;
            this.goals = new ArrayList<>(builder.goals);
            this.resourcePreferences = new HashMap<>(builder.resourcePreferences);
            this.outputChannels = new ArrayList<>(builder.outputChannels);
            this.metrics = new AgentMetrics();
            this.state = AgentState.IDLE;
            this.currencyBalance = builder.initialCurrency;
        }
        
        // ========================================================================
        // Abstract Methods - Implement in subclasses
        // ========================================================================
        
        /**
         * Execute a single goal. Subclasses implement the actual behavior.
         * 
         * @param goal The goal to execute
         * @param context Execution context with available services and resources
         * @return Result of goal execution
         */
        protected abstract GoalResult executeGoal(Goal goal, ExecutionContext context);
        
        /**
         * Get the service types this agent uses.
         * Used for resource planning and safety monitoring.
         */
        public abstract Set<ServiceType> getRequiredServiceTypes();
        
        /**
         * Get the domain(s) this agent operates in.
         * Used for generality monitoring.
         */
        public abstract Set<String> getOperatingDomains();
        
        // ========================================================================
        // Lifecycle Methods
        // ========================================================================
        
        /**
         * Called when agent is registered with a runtime.
         */
        public void onRegistered(AgentRuntime runtime) {
            this.startedAt = Instant.now();
            this.state = AgentState.IDLE;
        }
        
        /**
         * Called on each tick of the runtime.
         * Returns the next goal to execute, or null if none are due.
         */
        public Goal getNextGoal() {
            // Check autonomy constraints
            if (autonomyLevel == AutonomyLevel.TOOL) {
                return null; // Tools don't self-schedule
            }
            
            // Find highest priority due goal
            return goals.stream()
                .filter(Goal::isDue)
                .filter(g -> g.getStatus() == Goal.GoalStatus.ACTIVE || 
                            g.getStatus() == Goal.GoalStatus.PENDING)
                .max(Comparator.comparingInt(Goal::getPriority))
                .orElse(null);
        }
        
        /**
         * Check if a human checkpoint is required before continuing.
         */
        public boolean requiresCheckpoint() {
            if (autonomyLevel == AutonomyLevel.TOOL) {
                return false; // Tools are always human-initiated
            }
            
            Duration maxSpan = autonomyLevel.getMaxAutonomousSpan();
            if (lastCheckpoint == null) {
                return startedAt != null && 
                       Duration.between(startedAt, Instant.now()).compareTo(maxSpan) > 0;
            }
            
            return Duration.between(lastCheckpoint, Instant.now()).compareTo(maxSpan) > 0;
        }
        
        /**
         * Record that a checkpoint was passed.
         */
        public void passCheckpoint() {
            this.lastCheckpoint = Instant.now();
            this.metrics.recordCheckpoint();
        }
        
        // ========================================================================
        // Output Publishing
        // ========================================================================
        
        /**
         * Publish output to all registered channels.
         */
        protected void publish(String messageType, Object content) {
            for (OutputChannel channel : outputChannels) {
                try {
                    channel.publish(agentId, messageType, content);
                } catch (Exception e) {
                    System.err.println("Failed to publish to channel " + 
                        channel.getChannelId() + ": " + e.getMessage());
                }
            }
        }
        
        // ========================================================================
        // Accessors
        // ========================================================================
        
        public String getAgentId() { return agentId; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public AutonomyLevel getAutonomyLevel() { return autonomyLevel; }
        public List<Goal> getGoals() { return Collections.unmodifiableList(goals); }
        public Map<ResourceType, Double> getResourcePreferences() { 
            return Collections.unmodifiableMap(resourcePreferences); 
        }
        public AgentState getState() { return state; }
        public AgentMetrics getMetrics() { return metrics; }
        public BigDecimal getCurrencyBalance() { return currencyBalance; }
        
        public void setState(AgentState state) {
            this.state = state;
        }
        
        public void addGoal(Goal goal) {
            goals.add(goal);
        }
        
        public void addOutputChannel(OutputChannel channel) {
            outputChannels.add(channel);
        }
        
        @Override
        public String toString() {
            return String.format("Agent[%s: %s, autonomy=%s, state=%s, goals=%d]",
                agentId, name, autonomyLevel.getDisplayName(), state, goals.size());
        }
        
        // ========================================================================
        // Builder
        // ========================================================================
        
        public abstract static class Builder<T extends Builder<T>> {
            protected final String agentId;
            protected String name;
            protected String description = "";
            protected AutonomyLevel autonomyLevel = AutonomyLevel.LOW;
            protected final List<Goal> goals = new ArrayList<>();
            protected final Map<ResourceType, Double> resourcePreferences = new HashMap<>();
            protected final List<OutputChannel> outputChannels = new ArrayList<>();
            protected BigDecimal initialCurrency = BigDecimal.valueOf(100);
            
            public Builder(String agentId) {
                this.agentId = agentId;
                this.name = agentId;
            }
            
            @SuppressWarnings("unchecked")
            protected T self() {
                return (T) this;
            }
            
            public T name(String name) {
                this.name = name;
                return self();
            }
            
            public T description(String description) {
                this.description = description;
                return self();
            }
            
            public T autonomyLevel(AutonomyLevel level) {
                this.autonomyLevel = level;
                return self();
            }
            
            public T addGoal(Goal goal) {
                this.goals.add(goal);
                return self();
            }
            
            public T resourcePreference(ResourceType type, double weight) {
                this.resourcePreferences.put(type, weight);
                return self();
            }
            
            public T outputChannel(OutputChannel channel) {
                this.outputChannels.add(channel);
                return self();
            }
            
            public T initialCurrency(double amount) {
                this.initialCurrency = BigDecimal.valueOf(amount);
                return self();
            }
            
            public abstract RealisticAgent build();
        }
    }
    
    // ========================================================================
    // EXECUTION CONTEXT
    // ========================================================================
    
    /**
     * Context provided to agents during goal execution.
     * Contains available resources, services, and execution helpers.
     *
     * IMPORTANT: This context enforces resource allocations from arbitration.
     * Agents can only consume up to their allocated amounts.
     */
    public static class ExecutionContext {
        private final Map<ResourceType, Long> allocatedResources;
        private final Map<ResourceType, Long> consumedResources;  // Track consumption
        private final Map<ResourceType, Long> chargedResources;   // Accounting record
        private final Map<ServiceType, Integer> allocatedServiceSlots;
        private final ServiceRegistry serviceRegistry;
        private final ServiceBackend serviceBackend;
        private final Consumer<String> logger;
        private final long timeoutMs;
        private int backendInvocations;
        private int blockedCalls;

        public ExecutionContext(
                Map<ResourceType, Long> allocatedResources,
                Map<ServiceType, Integer> allocatedServiceSlots,
                ServiceRegistry serviceRegistry,
                ServiceBackend serviceBackend,
                Consumer<String> logger,
                long timeoutMs) {
            this.allocatedResources = new HashMap<>(allocatedResources);
            this.consumedResources = new HashMap<>();
            this.chargedResources = new HashMap<>();
            this.allocatedServiceSlots = allocatedServiceSlots;
            this.serviceRegistry = serviceRegistry;
            this.serviceBackend = serviceBackend;
            this.logger = logger;
            this.timeoutMs = timeoutMs;

            // Initialize consumption tracking
            for (ResourceType type : ResourceType.values()) {
                consumedResources.put(type, 0L);
                chargedResources.put(type, 0L);
            }
        }

        public long getAllocatedResource(ResourceType type) {
            return allocatedResources.getOrDefault(type, 0L);
        }

        public long getConsumedResource(ResourceType type) {
            return consumedResources.getOrDefault(type, 0L);
        }

        public long getRemainingResource(ResourceType type) {
            return getAllocatedResource(type) - getConsumedResource(type);
        }

        /**
         * Try to consume a single resource. Returns true and deducts the amount if
         * it fits within the remaining allocation, otherwise returns false and
         * leaves all counters unchanged. Over-quota and negative requests never
         * partially consume the remaining quota.
         */
        public synchronized boolean tryConsumeResource(ResourceType type, long amount) {
            if (amount < 0) {
                log("Rejected negative resource request: " + type + " amount " + amount);
                return false;
            }
            long remaining = getAllocatedResource(type) - getConsumedResource(type);
            if (amount <= remaining) {
                consumedResources.put(type, getConsumedResource(type) + amount);
                chargedResources.merge(type, amount, Long::sum);
                return true;
            }
            log("Resource limit hit: " + type + " requested " + amount +
                " but only " + remaining + " remaining (nothing consumed)");
            return false;
        }

        /**
         * Atomically check and consume a complete resource bundle. Either every
         * resource in the bundle fits and all are deducted, or nothing is consumed
         * and no counter changes. Any negative quantity fails the whole bundle.
         *
         * @return null on success; otherwise the first resource that could not be
         *         satisfied (the exhausted resource)
         */
        public synchronized ResourceType tryConsumeBundle(Map<ResourceType, Long> bundle) {
            for (Map.Entry<ResourceType, Long> e : bundle.entrySet()) {
                if (e.getValue() < 0) {
                    log("Rejected negative bundle component: " + e.getKey() + "=" + e.getValue());
                    return e.getKey();
                }
            }
            for (Map.Entry<ResourceType, Long> e : bundle.entrySet()) {
                long remaining = getAllocatedResource(e.getKey()) - getConsumedResource(e.getKey());
                if (e.getValue() > remaining) {
                    return e.getKey();
                }
            }
            for (Map.Entry<ResourceType, Long> e : bundle.entrySet()) {
                consumedResources.put(e.getKey(), getConsumedResource(e.getKey()) + e.getValue());
                chargedResources.merge(e.getKey(), e.getValue(), Long::sum);
            }
            return null;
        }

        /**
         * Check if a resource consumption would succeed without actually consuming.
         */
        public synchronized boolean canConsumeResource(ResourceType type, long amount) {
            return amount >= 0 && amount <= getRemainingResource(type);
        }

        /** Total number of backend invocations made through this context. */
        public synchronized int getBackendInvocations() { return backendInvocations; }

        /** Number of service calls denied before reaching the backend. */
        public synchronized int getBlockedCalls() { return blockedCalls; }

        /** Total amount of a resource charged through this context. */
        public synchronized long getCharged(ResourceType type) {
            return chargedResources.getOrDefault(type, 0L);
        }

        /** Snapshot of all resources charged through this context. */
        public synchronized Map<ResourceType, Long> getChargedBundle() {
            return new HashMap<>(chargedResources);
        }

        public int getAllocatedServiceSlots(ServiceType type) {
            return allocatedServiceSlots.getOrDefault(type, 0);
        }

        public boolean hasService(ServiceType type) {
            return allocatedServiceSlots.getOrDefault(type, 0) > 0;
        }

        public void log(String message) {
            if (logger != null) {
                logger.accept(message);
            }
        }

        /**
         * Invoke a service and return the result.
         *
         * Enforcement is atomic and fails closed: the call charges the complete
         * resource vector returned by {@link ServiceType#getDefaultResourceRequirements()}
         * and acquires a real service-capacity slot before the backend is touched.
         * If any required resource or the service slot is unavailable, nothing is
         * consumed, the backend is not invoked, and an explicit denial naming the
         * exhausted resource is returned with all counters unchanged.
         */
        public ServiceResult invokeService(ServiceType type, Map<String, Object> input) {
            if (!hasService(type)) {
                synchronized (this) { blockedCalls++; }
                return ServiceResult.denial("Service not allocated: " + type, null);
            }

            Map<ResourceType, Long> required = type.getDefaultResourceRequirements();
            String slotId;

            // Atomic gate: check the full vector, acquire a slot, then consume.
            synchronized (this) {
                for (Map.Entry<ResourceType, Long> e : required.entrySet()) {
                    if (e.getValue() < 0) {
                        blockedCalls++;
                        return ServiceResult.denial(
                            "Negative resource requirement: " + e.getKey(), e.getKey());
                    }
                }
                for (Map.Entry<ResourceType, Long> e : required.entrySet()) {
                    long remaining = getAllocatedResource(e.getKey()) - getConsumedResource(e.getKey());
                    if (e.getValue() > remaining) {
                        blockedCalls++;
                        return ServiceResult.denial(
                            "Insufficient " + e.getKey() + ": need " + e.getValue()
                            + ", remaining " + remaining, e.getKey());
                    }
                }
                // Acquire an actual service-capacity slot before invoking the backend.
                java.util.Optional<String> slot = serviceRegistry != null
                    ? serviceRegistry.acquireSlot(type) : java.util.Optional.of("no-registry");
                if (slot.isEmpty()) {
                    blockedCalls++;
                    return ServiceResult.denial(
                        "Service capacity exhausted: " + type, null);
                }
                slotId = slot.get();
                // All gates passed: consume the complete vector atomically.
                for (Map.Entry<ResourceType, Long> e : required.entrySet()) {
                    consumedResources.put(e.getKey(), getConsumedResource(e.getKey()) + e.getValue());
                    chargedResources.merge(e.getKey(), e.getValue(), Long::sum);
                }
                backendInvocations++;
            }

            long startTime = System.currentTimeMillis();
            try {
                // Use the pluggable backend (MockServiceBackend or LLMServiceBackend)
                ServiceBackend.InvocationResult result = serviceBackend.invokeByType(type, input);
                long latency = System.currentTimeMillis() - startTime;
                return new ServiceResult(
                    result.isSuccess(),
                    result.getError(),
                    result.getOutput(),
                    latency
                );
            } finally {
                if (serviceRegistry != null && !"no-registry".equals(slotId)) {
                    serviceRegistry.releaseSlot(slotId);
                }
            }
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public ServiceRegistry getServiceRegistry() {
            return serviceRegistry;
        }

        /**
         * Get a summary of resource consumption.
         */
        public String getConsumptionSummary() {
            StringBuilder sb = new StringBuilder();
            for (ResourceType type : allocatedResources.keySet()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(type).append("=")
                  .append(getConsumedResource(type)).append("/")
                  .append(getAllocatedResource(type));
            }
            return sb.length() > 0 ? sb.toString() : "none";
        }
    }
    
    /**
     * Result of a service invocation.
     */
    public static class ServiceResult {
        private final boolean success;
        private final String error;
        private final Map<String, Object> output;
        private final long latencyMs;
        private final boolean denied;
        private final ResourceType exhaustedResource;

        public ServiceResult(boolean success, String error,
                            Map<String, Object> output, long latencyMs) {
            this(success, error, output, latencyMs, false, null);
        }

        public ServiceResult(boolean success, String error, Map<String, Object> output,
                            long latencyMs, boolean denied, ResourceType exhaustedResource) {
            this.success = success;
            this.error = error;
            this.output = output;
            this.latencyMs = latencyMs;
            this.denied = denied;
            this.exhaustedResource = exhaustedResource;
        }

        /**
         * Build an explicit denial that consumed nothing and never reached the
         * backend. {@code exhaustedResource} names the resource that was short, or
         * null when the denial was due to an unavailable service slot.
         */
        public static ServiceResult denial(String message, ResourceType exhaustedResource) {
            return new ServiceResult(false, message, null, 0, true, exhaustedResource);
        }

        public boolean isSuccess() { return success; }
        public boolean isDenied() { return denied; }
        public ResourceType getExhaustedResource() { return exhaustedResource; }
        public String getError() { return error; }
        public Map<String, Object> getOutput() { return output; }
        public long getLatencyMs() { return latencyMs; }
        
        @SuppressWarnings("unchecked")
        public <T> T getOutputValue(String key) {
            return output != null ? (T) output.get(key) : null;
        }
        
        @Override
        public String toString() {
            return success ? 
                String.format("ServiceResult[OK, %dms]", latencyMs) :
                String.format("ServiceResult[FAIL: %s]", error);
        }
    }
    
    /**
     * Result of goal execution.
     */
    public static class GoalResult {
        private final boolean success;
        private final String message;
        private final Map<String, Object> outputs;
        private final long executionTimeMs;
        private final List<String> servicesUsed;
        
        public GoalResult(boolean success, String message, 
                         Map<String, Object> outputs, long executionTimeMs,
                         List<String> servicesUsed) {
            this.success = success;
            this.message = message;
            this.outputs = outputs != null ? outputs : new HashMap<>();
            this.executionTimeMs = executionTimeMs;
            this.servicesUsed = servicesUsed != null ? servicesUsed : new ArrayList<>();
        }
        
        public static GoalResult success(String message, Map<String, Object> outputs, 
                                        long timeMs, List<String> services) {
            return new GoalResult(true, message, outputs, timeMs, services);
        }
        
        public static GoalResult success(String message) {
            return new GoalResult(true, message, null, 0, null);
        }
        
        public static GoalResult failure(String message) {
            return new GoalResult(false, message, null, 0, null);
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public Map<String, Object> getOutputs() { return outputs; }
        public long getExecutionTimeMs() { return executionTimeMs; }
        public List<String> getServicesUsed() { return servicesUsed; }
        
        @Override
        public String toString() {
            return String.format("GoalResult[%s: %s, %dms]",
                success ? "SUCCESS" : "FAILED", message, executionTimeMs);
        }
    }
    
    // ========================================================================
    // AGENT RUNTIME
    // ========================================================================
    
    /**
     * Runtime environment that executes agents.
     * Handles scheduling, resource arbitration, and safety monitoring.
     *
     * To use REAL LLMs instead of mocks:
     * <pre>
     * ServiceBackend realBackend = new LLMServiceBackend.Builder()
     *     .fromEnvironment()  // Uses OPENAI_API_KEY, ANTHROPIC_API_KEY env vars
     *     .build();
     *
     * AgentRuntime runtime = new AgentRuntime.Builder()
     *     .serviceArbitrator(arbitrator)
     *     .serviceRegistry(registry)
     *     .resourcePool(pool)
     *     .serviceBackend(realBackend)  // Use real LLMs!
     *     .build();
     * </pre>
     */
    public static class AgentRuntime {
        private final PriorityEconomy economy;
        private final ServiceArbitrator serviceArbitrator;
        private final ServiceRegistry serviceRegistry;
        private final ServiceBackend serviceBackend;  // Interface allows MockServiceBackend or LLMServiceBackend
        private final ResourcePool resourcePool;

        private final Map<String, RealisticAgent> agents;
        private final Map<String, Map<ResourceType, Long>> agentAllocations;  // Resource allocations from arbitration
        private final Map<String, AllocationContract> contracts;              // Installed enforceable contracts
        private final Map<String, ExecutionContext> lastContexts = new ConcurrentHashMap<>();
        private final Object installLock = new Object();
        private long epochCounter = 0;                                        // Monotonic allocation version source
        private final ScheduledExecutorService scheduler;
        private final List<RuntimeListener> listeners;
        private volatile boolean running;
        private final long tickIntervalMs;

        /**
         * Listener for runtime events.
         */
        public interface RuntimeListener {
            default void onAgentRegistered(RealisticAgent agent) {}
            default void onGoalStarted(RealisticAgent agent, Goal goal) {}
            default void onGoalCompleted(RealisticAgent agent, Goal goal, GoalResult result) {}
            default void onCheckpointRequired(RealisticAgent agent) {}
            default void onRuntimeStarted() {}
            default void onRuntimeStopped() {}
        }

        /**
         * Legacy constructor - uses MockServiceBackend.
         * Prefer using Builder for real LLM integration.
         */
        public AgentRuntime(ServiceArbitrator serviceArbitrator,
                           ServiceRegistry serviceRegistry,
                           ResourcePool resourcePool) {
            this(serviceArbitrator, serviceRegistry, resourcePool,
                new MockServiceBackend(serviceRegistry), 1000);
        }

        /**
         * Legacy constructor with tick interval - uses MockServiceBackend.
         * Prefer using Builder for real LLM integration.
         */
        public AgentRuntime(ServiceArbitrator serviceArbitrator,
                           ServiceRegistry serviceRegistry,
                           ResourcePool resourcePool,
                           long tickIntervalMs) {
            this(serviceArbitrator, serviceRegistry, resourcePool,
                new MockServiceBackend(serviceRegistry), tickIntervalMs);
        }

        /**
         * Full constructor with custom ServiceBackend.
         * Use this to integrate real LLMs via LLMServiceBackend.
         */
        public AgentRuntime(ServiceArbitrator serviceArbitrator,
                           ServiceRegistry serviceRegistry,
                           ResourcePool resourcePool,
                           ServiceBackend serviceBackend,
                           long tickIntervalMs) {
            this.economy = new PriorityEconomy();
            this.serviceArbitrator = serviceArbitrator;
            this.serviceRegistry = serviceRegistry;
            this.serviceBackend = serviceBackend;
            this.resourcePool = resourcePool;
            this.agents = new ConcurrentHashMap<>();
            this.agentAllocations = new ConcurrentHashMap<>();
            this.contracts = new ConcurrentHashMap<>();
            this.scheduler = Executors.newScheduledThreadPool(4);
            this.listeners = new CopyOnWriteArrayList<>();
            this.tickIntervalMs = tickIntervalMs;
        }

        /**
         * Builder for AgentRuntime - recommended way to construct.
         */
        public static class Builder {
            private ServiceArbitrator serviceArbitrator;
            private ServiceRegistry serviceRegistry;
            private ResourcePool resourcePool;
            private ServiceBackend serviceBackend;
            private long tickIntervalMs = 1000;

            public Builder serviceArbitrator(ServiceArbitrator arbitrator) {
                this.serviceArbitrator = arbitrator;
                return this;
            }

            public Builder serviceRegistry(ServiceRegistry registry) {
                this.serviceRegistry = registry;
                return this;
            }

            public Builder resourcePool(ResourcePool pool) {
                this.resourcePool = pool;
                return this;
            }

            /**
             * Set the service backend.
             * Use MockServiceBackend for testing, LLMServiceBackend for real LLM calls.
             */
            public Builder serviceBackend(ServiceBackend backend) {
                this.serviceBackend = backend;
                return this;
            }

            public Builder tickIntervalMs(long interval) {
                this.tickIntervalMs = interval;
                return this;
            }

            public AgentRuntime build() {
                if (serviceArbitrator == null || serviceRegistry == null || resourcePool == null) {
                    throw new IllegalStateException(
                        "serviceArbitrator, serviceRegistry, and resourcePool are required");
                }
                // Default to MockServiceBackend if not specified
                if (serviceBackend == null) {
                    serviceBackend = new MockServiceBackend(serviceRegistry);
                }
                return new AgentRuntime(serviceArbitrator, serviceRegistry, resourcePool,
                    serviceBackend, tickIntervalMs);
            }
        }
        
        /**
         * Register an agent with the runtime.
         */
        public void register(RealisticAgent agent) {
            agents.put(agent.getAgentId(), agent);
            agent.onRegistered(this);
            
            // Activate goals
            for (Goal goal : agent.getGoals()) {
                if (goal.getStatus() == Goal.GoalStatus.PENDING) {
                    goal.setStatus(Goal.GoalStatus.ACTIVE);
                }
            }
            
            notifyListeners(l -> l.onAgentRegistered(agent));
        }
        
        /**
         * Unregister an agent from the runtime.
         */
        public void unregister(String agentId) {
            RealisticAgent agent = agents.remove(agentId);
            if (agent != null) {
                agent.setState(RealisticAgent.AgentState.TERMINATED);
            }
        }
        
        /**
         * Start the runtime.
         */
        public void start() {
            running = true;
            scheduler.scheduleAtFixedRate(
                this::tick, 0, tickIntervalMs, TimeUnit.MILLISECONDS);
            notifyListeners(RuntimeListener::onRuntimeStarted);
        }
        
        /**
         * Stop the runtime.
         */
        public void stop() {
            running = false;
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            notifyListeners(RuntimeListener::onRuntimeStopped);
        }
        
        /**
         * Run a single tick of the runtime.
         */
        private void tick() {
            if (!running) return;
            
            for (RealisticAgent agent : agents.values()) {
                try {
                    processAgent(agent);
                } catch (Exception e) {
                    System.err.println("Error processing agent " + 
                        agent.getAgentId() + ": " + e.getMessage());
                }
            }
        }
        
        /**
         * Process a single agent's tick.
         */
        private void processAgent(RealisticAgent agent) {
            // Check if checkpoint required
            if (agent.requiresCheckpoint()) {
                agent.setState(RealisticAgent.AgentState.CHECKPOINT);
                notifyListeners(l -> l.onCheckpointRequired(agent));
                return;
            }
            
            // Get next goal to execute
            Goal goal = agent.getNextGoal();
            if (goal == null) {
                agent.setState(RealisticAgent.AgentState.IDLE);
                return;
            }
            
            // Execute the goal
            executeAgentGoal(agent, goal);
        }
        
        /**
         * Execute a goal for an agent.
         */
        private void executeAgentGoal(RealisticAgent agent, Goal goal) {
            agent.setState(RealisticAgent.AgentState.RUNNING);
            agent.getMetrics().recordGoalAttempt();
            notifyListeners(l -> l.onGoalStarted(agent, goal));

            long startTime = System.currentTimeMillis();

            try {
                // Request service slots through arbitration
                Map<ServiceType, Integer> serviceSlots =
                    requestServicesForAgent(agent);

                // Get resource allocations for this agent (from arbitration)
                Map<ResourceType, Long> allocations = agentAllocations.getOrDefault(
                    agent.getAgentId(), new HashMap<>());

                // Create execution context with ACTUAL allocations
                ExecutionContext context = new ExecutionContext(
                    allocations,  // Use real allocations from arbitration
                    serviceSlots,
                    serviceRegistry,
                    serviceBackend,
                    msg -> System.out.println("[" + agent.getAgentId() + "] " + msg),
                    30000  // 30 second timeout
                );
                lastContexts.put(agent.getAgentId(), context);

                // Execute the goal
                GoalResult result = agent.executeGoal(goal, context);
                
                // Record metrics
                long executionTime = System.currentTimeMillis() - startTime;
                agent.getMetrics().recordExecutionTime(executionTime);
                
                for (String service : result.getServicesUsed()) {
                    agent.getMetrics().recordServiceInvocation(service);
                }
                
                // Update goal status
                goal.markExecuted(result.getMessage());
                if (result.isSuccess()) {
                    agent.getMetrics().recordGoalCompletion();
                    if (goal.getType() == Goal.GoalType.ONE_TIME) {
                        goal.setStatus(Goal.GoalStatus.COMPLETED);
                    }
                } else {
                    agent.getMetrics().recordGoalFailure();
                    if (goal.getType() == Goal.GoalType.ONE_TIME) {
                        goal.setStatus(Goal.GoalStatus.FAILED);
                    }
                }
                
                notifyListeners(l -> l.onGoalCompleted(agent, goal, result));
                
            } catch (Exception e) {
                agent.getMetrics().recordGoalFailure();
                goal.setStatus(Goal.GoalStatus.FAILED);
                GoalResult failResult = GoalResult.failure(e.getMessage());
                notifyListeners(l -> l.onGoalCompleted(agent, goal, failResult));
            }
            
            agent.setState(RealisticAgent.AgentState.IDLE);
        }
        
        /**
         * Request service slots for an agent through arbitration.
         */
        private Map<ServiceType, Integer> requestServicesForAgent(RealisticAgent agent) {
            Map<ServiceType, Integer> slots = new HashMap<>();
            
            // Request 1 slot for each service type the agent uses
            for (ServiceType type : agent.getRequiredServiceTypes()) {
                int available = serviceRegistry.getAvailableCapacity(type);
                if (available > 0) {
                    slots.put(type, 1);
                }
            }
            
            return slots;
        }
        
        /**
         * Manually trigger goal execution for tool-level agents.
         */
        public GoalResult invokeAgent(String agentId, String goalId) {
            RealisticAgent agent = agents.get(agentId);
            if (agent == null) {
                return GoalResult.failure("Agent not found: " + agentId);
            }

            Goal goal = agent.getGoals().stream()
                .filter(g -> g.getGoalId().equals(goalId))
                .findFirst()
                .orElse(null);

            if (goal == null) {
                return GoalResult.failure("Goal not found: " + goalId);
            }

            // Get resource allocations for this agent
            Map<ResourceType, Long> allocations = agentAllocations.getOrDefault(
                agentId, new HashMap<>());

            // For tool-level agents, execute immediately with actual allocations
            Map<ServiceType, Integer> serviceSlots = requestServicesForAgent(agent);
            ExecutionContext context = new ExecutionContext(
                allocations,  // Use real allocations
                serviceSlots,
                serviceRegistry,
                serviceBackend,
                msg -> System.out.println("[" + agent.getAgentId() + "] " + msg),
                30000
            );
            lastContexts.put(agent.getAgentId(), context);

            return agent.executeGoal(goal, context);
        }

        // ====================================================================
        // RESOURCE ALLOCATION MANAGEMENT
        // ====================================================================

        /**
         * Set resource allocations for an agent.
         * These allocations are enforced during goal execution.
         *
         * @param agentId The agent ID
         * @param allocations Map of resource type to allocated amount
         */
        public void setAllocations(String agentId, Map<ResourceType, Long> allocations) {
            agentAllocations.put(agentId, new HashMap<>(allocations));
        }

        /**
         * Get current allocations for an agent.
         */
        public Map<ResourceType, Long> getAllocations(String agentId) {
            return new HashMap<>(agentAllocations.getOrDefault(agentId, new HashMap<>()));
        }

        /**
         * Clear all allocations (e.g., before re-running arbitration).
         */
        public void clearAllocations() {
            agentAllocations.clear();
        }

        /**
         * Run arbitration for all registered agents and store the allocations.
         * This is the key integration point between arbitration and execution.
         *
         * @param detector ContentionDetector to find contention groups
         * @param arbitrator Arbitrator to calculate fair allocations
         * @return Map of agent ID to their allocations
         */
        public Map<String, Map<ResourceType, Long>> runArbitration(
                org.carma.arbitration.mechanism.ContentionDetector detector,
                org.carma.arbitration.mechanism.ProportionalFairnessArbitrator arbitrator) {

            // Convert RealisticAgents to arbitration model Agents
            List<org.carma.arbitration.model.Agent> arbAgents = new ArrayList<>();
            for (RealisticAgent ra : agents.values()) {
                org.carma.arbitration.model.Agent a = new org.carma.arbitration.model.Agent(
                    ra.getAgentId(),
                    ra.getName(),
                    ra.getResourcePreferences(),
                    ra.getCurrencyBalance().intValue()
                );

                // Set resource requests based on agent's service requirements
                for (ServiceType svc : ra.getRequiredServiceTypes()) {
                    Map<ResourceType, Long> reqs = svc.getDefaultResourceRequirements();
                    for (Map.Entry<ResourceType, Long> req : reqs.entrySet()) {
                        long current = a.getIdeal(req.getKey());
                        a.setRequest(req.getKey(),
                            Math.max(1, req.getValue() / 2),  // min
                            current + req.getValue());         // ideal
                    }
                }
                arbAgents.add(a);
            }

            // Detect contentions
            List<org.carma.arbitration.mechanism.ContentionDetector.ContentionGroup> groups =
                detector.detectContentions(arbAgents, resourcePool);

            // Initialize allocations for all agents
            Map<String, Map<ResourceType, Long>> allAllocations = new HashMap<>();
            for (org.carma.arbitration.model.Agent a : arbAgents) {
                allAllocations.put(a.getId(), new HashMap<>());
            }

            // Run arbitration for each contention group
            Map<String, java.math.BigDecimal> burns = new HashMap<>();
            for (org.carma.arbitration.model.Agent a : arbAgents) {
                burns.put(a.getId(), java.math.BigDecimal.ZERO);
            }

            for (var group : groups) {
                for (ResourceType type : group.getResources()) {
                    List<org.carma.arbitration.model.Agent> competing = group.getAgents().stream()
                        .filter(a -> a.getIdeal(type) > 0)
                        .collect(java.util.stream.Collectors.toList());

                    if (competing.isEmpty()) continue;

                    org.carma.arbitration.model.Contention contention =
                        new org.carma.arbitration.model.Contention(
                            type, competing, group.getAvailableQuantities().get(type));

                    Map<String, java.math.BigDecimal> groupBurns = new HashMap<>();
                    for (var a : competing) {
                        groupBurns.put(a.getId(), burns.get(a.getId()));
                    }

                    var result = arbitrator.arbitrate(contention, groupBurns);

                    for (var a : competing) {
                        allAllocations.get(a.getId()).put(type, result.getAllocation(a.getId()));
                    }
                }
            }

            // For agents not in any contention group, give them their full requested resources
            Set<String> agentsInContention = new HashSet<>();
            for (var group : groups) {
                for (var agent : group.getAgents()) {
                    agentsInContention.add(agent.getId());
                }
            }

            for (org.carma.arbitration.model.Agent a : arbAgents) {
                if (!agentsInContention.contains(a.getId())) {
                    // No contention - agent gets their ideal amounts
                    Map<ResourceType, Long> fullAlloc = new HashMap<>();
                    for (ResourceType type : ResourceType.values()) {
                        long ideal = a.getIdeal(type);
                        if (ideal > 0) {
                            fullAlloc.put(type, ideal);
                        }
                    }
                    // Also give baseline API_CREDITS for service calls
                    if (!fullAlloc.containsKey(ResourceType.API_CREDITS)) {
                        fullAlloc.put(ResourceType.API_CREDITS, 10L);  // baseline
                    }
                    allAllocations.put(a.getId(), fullAlloc);
                }
            }

            // Store allocations in runtime
            for (var entry : allAllocations.entrySet()) {
                agentAllocations.put(entry.getKey(), entry.getValue());
            }

            return allAllocations;
        }

        /**
         * Canonical mediation path: run arbitration through a {@link JointArbitrator}.
         *
         * For every contention group the selected joint arbitrator is called exactly
         * once on the complete group (all resources at once) rather than looping over
         * resource types with a single-resource allocator. The result is checked for
         * conservation and installed as versioned {@link AllocationContract}s.
         *
         * Solver failure fails closed: an infeasible group aborts the whole run.
         */
        public Map<String, Map<ResourceType, Long>> runArbitration(
                org.carma.arbitration.mechanism.ContentionDetector detector,
                org.carma.arbitration.mechanism.JointArbitrator arbitrator) {
            return runArbitration(detector, arbitrator,
                arbitrator.getClass().getSimpleName(), null);
        }

        /**
         * Canonical mediation path with an explicit policy label and optional lease
         * duration (milliseconds) after which the installed contracts expire.
         */
        public Map<String, Map<ResourceType, Long>> runArbitration(
                org.carma.arbitration.mechanism.ContentionDetector detector,
                org.carma.arbitration.mechanism.JointArbitrator arbitrator,
                String policyName,
                Long leaseDurationMs) {

            List<org.carma.arbitration.model.Agent> arbAgents = buildArbitrationAgents();

            List<org.carma.arbitration.mechanism.ContentionDetector.ContentionGroup> groups =
                detector.detectContentions(arbAgents, resourcePool);

            Map<String, Map<ResourceType, Long>> allAllocations = new HashMap<>();
            for (org.carma.arbitration.model.Agent a : arbAgents) {
                allAllocations.put(a.getId(), new HashMap<>());
            }

            Map<String, java.math.BigDecimal> burns = new HashMap<>();
            for (org.carma.arbitration.model.Agent a : arbAgents) {
                burns.put(a.getId(), java.math.BigDecimal.ZERO);
            }

            String solverStatus = groups.isEmpty() ? "no_contention" : "optimal";
            for (var group : groups) {
                Map<String, java.math.BigDecimal> groupBurns = new HashMap<>();
                for (var a : group.getAgents()) {
                    groupBurns.put(a.getId(), burns.get(a.getId()));
                }

                // Call the joint arbitrator ONCE on the complete contention group.
                org.carma.arbitration.mechanism.JointArbitrator.JointAllocationResult r =
                    arbitrator.arbitrate(group, groupBurns);

                if (!r.isFeasible()) {
                    throw new IllegalStateException(
                        "Joint arbitration failed closed for group " + group.getGroupId()
                        + ": " + r.getMessage());
                }
                solverStatus = r.getMessage();
                for (var a : group.getAgents()) {
                    allAllocations.get(a.getId()).putAll(r.getAllocations(a.getId()));
                }
            }

            fillNonContentionAgents(arbAgents, groups, allAllocations);
            installContracts(allAllocations, policyName, solverStatus, leaseDurationMs);
            return allAllocations;
        }

        /**
         * Build arbitration-model agents from the registered runtime agents, deriving
         * per-resource min/ideal requests from their service requirements. Shared by
         * the joint canonical path.
         */
        private List<org.carma.arbitration.model.Agent> buildArbitrationAgents() {
            List<org.carma.arbitration.model.Agent> arbAgents = new ArrayList<>();
            for (RealisticAgent ra : agents.values()) {
                org.carma.arbitration.model.Agent a = new org.carma.arbitration.model.Agent(
                    ra.getAgentId(), ra.getName(), ra.getResourcePreferences(),
                    ra.getCurrencyBalance().intValue());
                for (ServiceType svc : ra.getRequiredServiceTypes()) {
                    Map<ResourceType, Long> reqs = svc.getDefaultResourceRequirements();
                    for (Map.Entry<ResourceType, Long> req : reqs.entrySet()) {
                        long current = a.getIdeal(req.getKey());
                        a.setRequest(req.getKey(),
                            Math.max(1, req.getValue() / 2),
                            current + req.getValue());
                    }
                }
                arbAgents.add(a);
            }
            return arbAgents;
        }

        /**
         * Give agents outside any contention group their ideal request, but never
         * more than the capacity remaining after the contended groups are served, so
         * conservation always holds. Agents are served in a deterministic id order.
         */
        private void fillNonContentionAgents(
                List<org.carma.arbitration.model.Agent> arbAgents,
                List<org.carma.arbitration.mechanism.ContentionDetector.ContentionGroup> groups,
                Map<String, Map<ResourceType, Long>> allAllocations) {

            Set<String> inContention = new HashSet<>();
            for (var group : groups) {
                for (var agent : group.getAgents()) inContention.add(agent.getId());
            }

            Map<ResourceType, Long> remaining = new HashMap<>();
            for (ResourceType type : ResourceType.values()) {
                long used = 0;
                for (var m : allAllocations.values()) used += m.getOrDefault(type, 0L);
                remaining.put(type, resourcePool.getCapacity(type) - used);
            }

            List<org.carma.arbitration.model.Agent> ordered = new ArrayList<>(arbAgents);
            ordered.sort(Comparator.comparing(org.carma.arbitration.model.Agent::getId));
            for (org.carma.arbitration.model.Agent a : ordered) {
                if (inContention.contains(a.getId())) continue;
                Map<ResourceType, Long> alloc = allAllocations.get(a.getId());
                for (ResourceType type : ResourceType.values()) {
                    long want = a.getIdeal(type);
                    if (want <= 0 && type == ResourceType.API_CREDITS) {
                        want = 10L; // baseline credits so tool agents can call services
                    }
                    if (want <= 0) continue;
                    long grant = Math.min(want, Math.max(0, remaining.get(type)));
                    if (grant > 0) {
                        alloc.put(type, grant);
                        remaining.put(type, remaining.get(type) - grant);
                    }
                }
            }
        }

        /**
         * Validate that no resource is over-allocated relative to pool capacity.
         * Called before any contract is installed.
         */
        private void checkConservation(Map<String, Map<ResourceType, Long>> allocations) {
            for (ResourceType type : ResourceType.values()) {
                long total = 0;
                for (var m : allocations.values()) total += m.getOrDefault(type, 0L);
                long cap = resourcePool.getCapacity(type);
                if (total > cap) {
                    throw new IllegalStateException("conservation violated for " + type
                        + ": allocated " + total + " > capacity " + cap);
                }
            }
        }

        /**
         * Install a complete allocation as versioned contracts atomically. Conservation
         * is checked first; a fresh monotonically increasing epoch is assigned; any
         * agent whose currently installed contract has a newer or equal version causes
         * the stale install to be rejected. On success both the contract store and the
         * enforcement allocations are swapped under a single lock.
         */
        public List<AllocationContract> installContracts(
                Map<String, Map<ResourceType, Long>> allocations,
                String policyName, String solverStatus, Long leaseDurationMs) {

            checkConservation(allocations);
            synchronized (installLock) {
                long version = ++epochCounter;
                long now = System.currentTimeMillis();
                Long expiry = leaseDurationMs != null ? now + leaseDurationMs : null;

                List<AllocationContract> prepared = new ArrayList<>();
                for (var e : allocations.entrySet()) {
                    AllocationContract existing = contracts.get(e.getKey());
                    if (existing != null && version <= existing.getVersion()) {
                        throw new IllegalStateException("stale allocation version " + version
                            + " <= installed " + existing.getVersion() + " for " + e.getKey());
                    }
                    prepared.add(new AllocationContract(
                        "AC-" + version + "-" + e.getKey(), version, e.getKey(),
                        e.getValue(), now, expiry, policyName, solverStatus));
                }
                for (AllocationContract c : prepared) {
                    contracts.put(c.getAgentId(), c);
                    agentAllocations.put(c.getAgentId(), new HashMap<>(c.getBundle()));
                }
                return prepared;
            }
        }

        /**
         * Install a single pre-built contract, rejecting it if a newer or equal
         * version is already installed for that agent.
         *
         * @return true if installed, false if rejected as stale
         */
        public boolean installContract(AllocationContract c) {
            synchronized (installLock) {
                AllocationContract existing = contracts.get(c.getAgentId());
                if (existing != null && c.getVersion() <= existing.getVersion()) {
                    return false;
                }
                contracts.put(c.getAgentId(), c);
                agentAllocations.put(c.getAgentId(), new HashMap<>(c.getBundle()));
                if (c.getVersion() > epochCounter) epochCounter = c.getVersion();
                return true;
            }
        }

        /** Reserve and return the next monotonically increasing allocation epoch. */
        public long nextEpoch() {
            synchronized (installLock) { return ++epochCounter; }
        }

        /** Get the installed contract for an agent, or null. */
        public AllocationContract getContract(String agentId) {
            return contracts.get(agentId);
        }

        /** Whether an enforceable contract is installed for an agent. */
        public boolean hasContract(String agentId) {
            return contracts.containsKey(agentId);
        }

        /** The most recent execution context created for an agent (accounting record). */
        public ExecutionContext getLastExecutionContext(String agentId) {
            return lastContexts.get(agentId);
        }

        /**
         * Check if an agent has any allocations set.
         */
        public boolean hasAllocations(String agentId) {
            Map<ResourceType, Long> allocs = agentAllocations.get(agentId);
            return allocs != null && !allocs.isEmpty();
        }

        /**
         * Get all registered agents.
         */
        public Collection<RealisticAgent> getAgents() {
            return agents.values();
        }

        /**
         * Get the resource pool.
         */
        public ResourcePool getResourcePool() {
            return resourcePool;
        }

        /**
         * Approve checkpoint for an agent.
         */
        public void approveCheckpoint(String agentId) {
            RealisticAgent agent = agents.get(agentId);
            if (agent != null) {
                agent.passCheckpoint();
                agent.setState(RealisticAgent.AgentState.IDLE);
            }
        }
        
        /**
         * Run for a specified duration (for testing).
         */
        public void runFor(Duration duration) {
            start();
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            stop();
        }
        
        /**
         * Run a specified number of ticks (for testing).
         */
        public void runTicks(int count) {
            for (int i = 0; i < count && running; i++) {
                tick();
                try {
                    Thread.sleep(tickIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        public void addListener(RuntimeListener listener) {
            listeners.add(listener);
        }
        
        public void removeListener(RuntimeListener listener) {
            listeners.remove(listener);
        }
        
        private void notifyListeners(Consumer<RuntimeListener> action) {
            for (RuntimeListener listener : listeners) {
                try {
                    action.accept(listener);
                } catch (Exception e) {
                    System.err.println("Error in runtime listener: " + e.getMessage());
                }
            }
        }

        public Optional<RealisticAgent> getAgent(String agentId) {
            return Optional.ofNullable(agents.get(agentId));
        }
        
        public boolean isRunning() {
            return running;
        }
    }
}
