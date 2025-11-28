package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

/**
 * Implements an embargo queue for fair request batching.
 * 
 * The embargo queue collects incoming resource requests during a configurable
 * time window (default 100ms), then processes them as a batch. This ensures:
 * 
 * 1. Network Latency Fairness: Agents with faster network connections cannot
 *    gain systematic advantage by submitting requests earlier.
 * 
 * 2. Atomic Arbitration: All requests in a batch are arbitrated together,
 *    enabling joint optimization across agents and resources.
 * 
 * 3. Deterministic Ordering: Within a batch, requests are ordered by a
 *    deterministic hash to prevent timing attacks.
 */
public class EmbargoQueue {

    /**
     * Represents a queued resource request.
     */
    public static class QueuedRequest {
        private final String requestId;
        private final Agent agent;
        private final Map<ResourceType, Long> minimums;
        private final Map<ResourceType, Long> ideals;
        private final BigDecimal currencyCommitment;
        private final long submittedAtMs;
        private final long embargoEndsAtMs;

        public QueuedRequest(Agent agent, Map<ResourceType, Long> minimums,
                            Map<ResourceType, Long> ideals, BigDecimal currencyCommitment,
                            long embargoWindowMs) {
            this.requestId = UUID.randomUUID().toString().substring(0, 8);
            this.agent = agent;
            this.minimums = new HashMap<>(minimums);
            this.ideals = new HashMap<>(ideals);
            this.currencyCommitment = currencyCommitment;
            this.submittedAtMs = System.currentTimeMillis();
            this.embargoEndsAtMs = submittedAtMs + embargoWindowMs;
        }

        public String getRequestId() { return requestId; }
        public Agent getAgent() { return agent; }
        public Map<ResourceType, Long> getMinimums() { return Collections.unmodifiableMap(minimums); }
        public Map<ResourceType, Long> getIdeals() { return Collections.unmodifiableMap(ideals); }
        public BigDecimal getCurrencyCommitment() { return currencyCommitment; }
        public long getSubmittedAtMs() { return submittedAtMs; }
        public long getEmbargoEndsAtMs() { return embargoEndsAtMs; }

        public boolean isEmbargoComplete() {
            return System.currentTimeMillis() >= embargoEndsAtMs;
        }

        /**
         * Deterministic ordering key (prevents timing attacks within batch).
         */
        public String getOrderingKey() {
            // Hash of agent ID + request ID for deterministic but unpredictable ordering
            return Integer.toHexString((agent.getId() + requestId).hashCode());
        }

        @Override
        public String toString() {
            return String.format("QueuedRequest[%s from %s, embargo ends in %dms]",
                requestId, agent.getId(), Math.max(0, embargoEndsAtMs - System.currentTimeMillis()));
        }
    }

    /**
     * Represents a batch of requests ready for arbitration.
     */
    public static class RequestBatch {
        private final String batchId;
        private final List<QueuedRequest> requests;
        private final long createdAtMs;
        private final long embargoWindowMs;

        public RequestBatch(List<QueuedRequest> requests, long embargoWindowMs) {
            this.batchId = "BATCH-" + System.currentTimeMillis();
            // Sort by deterministic ordering key to prevent timing advantages
            this.requests = new ArrayList<>(requests);
            this.requests.sort(Comparator.comparing(QueuedRequest::getOrderingKey));
            this.createdAtMs = System.currentTimeMillis();
            this.embargoWindowMs = embargoWindowMs;
        }

        public String getBatchId() { return batchId; }
        public List<QueuedRequest> getRequests() { return Collections.unmodifiableList(requests); }
        public long getCreatedAtMs() { return createdAtMs; }
        public int size() { return requests.size(); }

        /**
         * Get all agents in this batch.
         */
        public List<Agent> getAgents() {
            return requests.stream()
                .map(QueuedRequest::getAgent)
                .toList();
        }

        /**
         * Get currency commitments map for arbitration.
         */
        public Map<String, BigDecimal> getCurrencyCommitments() {
            Map<String, BigDecimal> commitments = new HashMap<>();
            for (QueuedRequest req : requests) {
                commitments.put(req.getAgent().getId(), req.getCurrencyCommitment());
            }
            return commitments;
        }

        /**
         * Apply request bounds to agents before arbitration.
         */
        public void applyRequestsToAgents() {
            for (QueuedRequest req : requests) {
                Agent agent = req.getAgent();
                for (var entry : req.getMinimums().entrySet()) {
                    long ideal = req.getIdeals().getOrDefault(entry.getKey(), entry.getValue());
                    agent.setRequest(entry.getKey(), entry.getValue(), ideal);
                }
            }
        }

        @Override
        public String toString() {
            return String.format("RequestBatch[%s: %d requests, window=%dms]",
                batchId, requests.size(), embargoWindowMs);
        }
    }

    // ========================================================================
    // Queue State
    // ========================================================================

    private final long embargoWindowMs;
    private final Queue<QueuedRequest> pendingRequests;
    private final List<RequestBatch> processedBatches;
    private long lastBatchTimeMs;

    /**
     * Create embargo queue with specified window.
     * @param embargoWindowMs Time window for batching (default 100ms)
     */
    public EmbargoQueue(long embargoWindowMs) {
        this.embargoWindowMs = embargoWindowMs;
        this.pendingRequests = new ConcurrentLinkedQueue<>();
        this.processedBatches = new ArrayList<>();
        this.lastBatchTimeMs = System.currentTimeMillis();
    }

    /**
     * Create embargo queue with default 100ms window.
     */
    public EmbargoQueue() {
        this(100);
    }

    // ========================================================================
    // Request Submission
    // ========================================================================

    /**
     * Submit a resource request to the embargo queue.
     * The request will be held until the embargo window expires.
     */
    public QueuedRequest submit(Agent agent, Map<ResourceType, Long> minimums,
                               Map<ResourceType, Long> ideals, BigDecimal currencyCommitment) {
        QueuedRequest request = new QueuedRequest(agent, minimums, ideals, 
            currencyCommitment, embargoWindowMs);
        pendingRequests.add(request);
        return request;
    }

    /**
     * Submit a simple request with same min/ideal.
     */
    public QueuedRequest submit(Agent agent, ResourceType type, long minimum, long ideal,
                               BigDecimal currencyCommitment) {
        return submit(agent, 
            Map.of(type, minimum), 
            Map.of(type, ideal), 
            currencyCommitment);
    }

    // ========================================================================
    // Batch Processing
    // ========================================================================

    /**
     * Check if any requests are ready for batching.
     */
    public boolean hasReadyRequests() {
        return pendingRequests.stream().anyMatch(QueuedRequest::isEmbargoComplete);
    }

    /**
     * Get the next batch of requests that have completed their embargo.
     * Returns empty optional if no requests are ready.
     */
    public Optional<RequestBatch> getNextBatch() {
        List<QueuedRequest> ready = new ArrayList<>();
        
        // Collect all requests whose embargo has ended
        QueuedRequest req;
        while ((req = pendingRequests.peek()) != null && req.isEmbargoComplete()) {
            ready.add(pendingRequests.poll());
        }
        
        if (ready.isEmpty()) {
            return Optional.empty();
        }
        
        RequestBatch batch = new RequestBatch(ready, embargoWindowMs);
        processedBatches.add(batch);
        lastBatchTimeMs = System.currentTimeMillis();
        
        return Optional.of(batch);
    }

    /**
     * Force collection of all pending requests into a batch (for testing/shutdown).
     */
    public RequestBatch flushAll() {
        List<QueuedRequest> all = new ArrayList<>();
        while (!pendingRequests.isEmpty()) {
            all.add(pendingRequests.poll());
        }
        
        RequestBatch batch = new RequestBatch(all, embargoWindowMs);
        if (!all.isEmpty()) {
            processedBatches.add(batch);
        }
        lastBatchTimeMs = System.currentTimeMillis();
        
        return batch;
    }

    /**
     * Wait for embargo window and return batch (blocking).
     */
    public RequestBatch waitAndCollect() throws InterruptedException {
        // Wait for embargo window
        Thread.sleep(embargoWindowMs);
        return flushAll();
    }

    // ========================================================================
    // Queue State
    // ========================================================================

    public int getPendingCount() {
        return pendingRequests.size();
    }

    public long getEmbargoWindowMs() {
        return embargoWindowMs;
    }

    public long getLastBatchTimeMs() {
        return lastBatchTimeMs;
    }

    public int getTotalBatchesProcessed() {
        return processedBatches.size();
    }

    /**
     * Get time until next batch can be collected.
     */
    public long getTimeUntilNextBatch() {
        if (pendingRequests.isEmpty()) {
            return embargoWindowMs;
        }
        
        long earliestEmbargo = pendingRequests.stream()
            .mapToLong(QueuedRequest::getEmbargoEndsAtMs)
            .min()
            .orElse(System.currentTimeMillis() + embargoWindowMs);
        
        return Math.max(0, earliestEmbargo - System.currentTimeMillis());
    }

    // ========================================================================
    // Metrics
    // ========================================================================

    /**
     * Get queue statistics.
     */
    public QueueStats getStats() {
        int totalRequests = processedBatches.stream()
            .mapToInt(RequestBatch::size)
            .sum() + pendingRequests.size();
        
        double avgBatchSize = processedBatches.isEmpty() ? 0 :
            processedBatches.stream().mapToInt(RequestBatch::size).average().orElse(0);
        
        return new QueueStats(
            pendingRequests.size(),
            processedBatches.size(),
            totalRequests,
            avgBatchSize,
            embargoWindowMs
        );
    }

    public static class QueueStats {
        public final int pendingRequests;
        public final int batchesProcessed;
        public final int totalRequests;
        public final double avgBatchSize;
        public final long embargoWindowMs;

        public QueueStats(int pendingRequests, int batchesProcessed, int totalRequests,
                         double avgBatchSize, long embargoWindowMs) {
            this.pendingRequests = pendingRequests;
            this.batchesProcessed = batchesProcessed;
            this.totalRequests = totalRequests;
            this.avgBatchSize = avgBatchSize;
            this.embargoWindowMs = embargoWindowMs;
        }

        @Override
        public String toString() {
            return String.format(
                "QueueStats[pending=%d, batches=%d, total=%d, avgSize=%.1f, window=%dms]",
                pendingRequests, batchesProcessed, totalRequests, avgBatchSize, embargoWindowMs);
        }
    }

    @Override
    public String toString() {
        return String.format("EmbargoQueue[window=%dms, pending=%d, batches=%d]",
            embargoWindowMs, pendingRequests.size(), processedBatches.size());
    }
}
