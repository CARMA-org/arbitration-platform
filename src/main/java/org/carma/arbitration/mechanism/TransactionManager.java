package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages atomic transactions for joint allocations.
 * 
 * Ensures that multi-resource allocations either fully commit or fully rollback,
 * preventing partial state corruption. Provides explicit transaction boundaries
 * for auditing and safety verification.
 * 
 * Transaction lifecycle:
 * 1. BEGIN: Create transaction, snapshot current state
 * 2. PREPARE: Validate proposed allocations with SafetyMonitor
 * 3. COMMIT: Apply allocations atomically
 * 4. ROLLBACK: Restore previous state on any failure
 * 
 * All transitions are logged for verification: [TXN-START], [TXN-COMMIT], [TXN-ROLLBACK]
 */
public class TransactionManager {

    private static final AtomicLong txnCounter = new AtomicLong(0);
    
    private final SafetyMonitor safetyMonitor;
    private final boolean loggingEnabled;
    private final List<TransactionRecord> transactionLog;
    
    /**
     * Represents a single transaction.
     */
    public static class Transaction {
        private final String txnId;
        private final long startTimeMs;
        private final Map<String, Map<ResourceType, Long>> previousAllocations;
        private final Map<String, BigDecimal> previousBalances;
        private TransactionState state;
        private String failureReason;
        
        public Transaction(String txnId, List<Agent> agents) {
            this.txnId = txnId;
            this.startTimeMs = System.currentTimeMillis();
            this.state = TransactionState.STARTED;
            this.previousAllocations = new HashMap<>();
            this.previousBalances = new HashMap<>();
            
            // Snapshot current state for potential rollback
            for (Agent agent : agents) {
                Map<ResourceType, Long> allocs = new HashMap<>();
                for (ResourceType type : ResourceType.values()) {
                    allocs.put(type, agent.getAllocation(type));
                }
                previousAllocations.put(agent.getId(), allocs);
                previousBalances.put(agent.getId(), agent.getCurrencyBalance());
            }
        }
        
        public String getTxnId() { return txnId; }
        public long getStartTimeMs() { return startTimeMs; }
        public TransactionState getState() { return state; }
        public String getFailureReason() { return failureReason; }
        
        void setState(TransactionState state) { this.state = state; }
        void setFailureReason(String reason) { this.failureReason = reason; }
        
        Map<String, Map<ResourceType, Long>> getPreviousAllocations() {
            return previousAllocations;
        }
        
        Map<String, BigDecimal> getPreviousBalances() {
            return previousBalances;
        }
    }
    
    public enum TransactionState {
        STARTED,
        PREPARED,
        COMMITTED,
        ROLLED_BACK,
        FAILED
    }
    
    /**
     * Record of a completed transaction for auditing.
     */
    public static class TransactionRecord {
        private final String txnId;
        private final TransactionState finalState;
        private final long startTimeMs;
        private final long endTimeMs;
        private final int agentCount;
        private final int resourceCount;
        private final String outcome;
        
        public TransactionRecord(Transaction txn, int resourceCount) {
            this.txnId = txn.getTxnId();
            this.finalState = txn.getState();
            this.startTimeMs = txn.getStartTimeMs();
            this.endTimeMs = System.currentTimeMillis();
            this.agentCount = txn.getPreviousAllocations().size();
            this.resourceCount = resourceCount;
            this.outcome = txn.getState() == TransactionState.COMMITTED 
                ? "SUCCESS" 
                : "FAILED: " + txn.getFailureReason();
        }
        
        public String getTxnId() { return txnId; }
        public TransactionState getFinalState() { return finalState; }
        public long getDurationMs() { return endTimeMs - startTimeMs; }
        public String getOutcome() { return outcome; }
        
        @Override
        public String toString() {
            return String.format("TXN[%s] %s (%d agents, %d resources, %dms)",
                txnId.substring(0, 8), outcome, agentCount, resourceCount, getDurationMs());
        }
    }
    
    public TransactionManager(SafetyMonitor safetyMonitor) {
        this(safetyMonitor, true);
    }
    
    public TransactionManager(SafetyMonitor safetyMonitor, boolean loggingEnabled) {
        this.safetyMonitor = safetyMonitor;
        this.loggingEnabled = loggingEnabled;
        this.transactionLog = new ArrayList<>();
    }
    
    /**
     * Begin a new transaction for a joint allocation.
     * Snapshots current state for potential rollback.
     */
    public Transaction begin(List<Agent> agents) {
        String txnId = String.format("TXN-%08d-%d", 
            txnCounter.incrementAndGet(), 
            System.currentTimeMillis() % 100000);
        
        Transaction txn = new Transaction(txnId, agents);
        
        if (loggingEnabled) {
            log("[TXN-START] %s with %d agents", txnId, agents.size());
        }
        
        return txn;
    }
    
    /**
     * Prepare a transaction by validating proposed allocations.
     * Returns true if safe to commit, false otherwise.
     */
    public boolean prepare(
            Transaction txn,
            JointArbitrator.JointAllocationResult proposedResult,
            ResourcePool pool,
            List<Agent> agents) {
        
        if (txn.getState() != TransactionState.STARTED) {
            txn.setFailureReason("Invalid transaction state for prepare: " + txn.getState());
            txn.setState(TransactionState.FAILED);
            return false;
        }
        
        // Validate with SafetyMonitor
        SafetyMonitor.SafetyCheckResult check = safetyMonitor.validateTransition(
            proposedResult.getAllAllocations(), pool, agents);
        
        if (!check.isSafe()) {
            txn.setFailureReason("Safety check failed: " + String.join("; ", check.getViolations()));
            txn.setState(TransactionState.FAILED);
            
            if (loggingEnabled) {
                log("[TXN-PREPARE-FAILED] %s - %s", txn.getTxnId(), txn.getFailureReason());
            }
            
            return false;
        }
        
        txn.setState(TransactionState.PREPARED);
        
        if (loggingEnabled) {
            log("[TXN-PREPARED] %s - safety checks passed", txn.getTxnId());
        }
        
        return true;
    }
    
    /**
     * Commit a prepared transaction, applying allocations to agents.
     */
    public boolean commit(
            Transaction txn,
            JointArbitrator.JointAllocationResult result,
            List<Agent> agents,
            ResourcePool pool) {
        
        if (txn.getState() != TransactionState.PREPARED) {
            txn.setFailureReason("Transaction not prepared for commit: " + txn.getState());
            txn.setState(TransactionState.FAILED);
            return false;
        }
        
        try {
            // Apply allocations atomically
            int resourceCount = 0;
            for (Agent agent : agents) {
                Map<ResourceType, Long> allocs = result.getAllocations(agent.getId());
                for (Map.Entry<ResourceType, Long> entry : allocs.entrySet()) {
                    agent.setAllocation(entry.getKey(), entry.getValue());
                    resourceCount++;
                }
            }
            
            txn.setState(TransactionState.COMMITTED);
            
            if (loggingEnabled) {
                log("[TXN-COMMIT] %s - %d allocations applied", txn.getTxnId(), resourceCount);
            }
            
            // Record for audit
            transactionLog.add(new TransactionRecord(txn, resourceCount / agents.size()));
            
            return true;
            
        } catch (Exception e) {
            txn.setFailureReason("Commit failed: " + e.getMessage());
            txn.setState(TransactionState.FAILED);
            
            // Attempt rollback
            rollback(txn, agents);
            
            return false;
        }
    }
    
    /**
     * Rollback a transaction, restoring previous state.
     */
    public void rollback(Transaction txn, List<Agent> agents) {
        if (loggingEnabled) {
            log("[TXN-ROLLBACK] %s - restoring previous state", txn.getTxnId());
        }
        
        // Restore previous allocations
        for (Agent agent : agents) {
            Map<ResourceType, Long> prevAllocs = txn.getPreviousAllocations().get(agent.getId());
            if (prevAllocs != null) {
                for (Map.Entry<ResourceType, Long> entry : prevAllocs.entrySet()) {
                    agent.setAllocation(entry.getKey(), entry.getValue());
                }
            }
        }
        
        txn.setState(TransactionState.ROLLED_BACK);
        
        // Record for audit
        transactionLog.add(new TransactionRecord(txn, 0));
    }
    
    /**
     * Execute a full transaction: begin, prepare, commit (or rollback on failure).
     * This is the recommended high-level API for most use cases.
     */
    public TransactionRecord executeTransaction(
            JointArbitrator.JointAllocationResult result,
            List<Agent> agents,
            ResourcePool pool) {
        
        Transaction txn = begin(agents);
        
        if (!prepare(txn, result, pool, agents)) {
            rollback(txn, agents);
            return transactionLog.get(transactionLog.size() - 1);
        }
        
        if (!commit(txn, result, agents, pool)) {
            // Rollback already called by commit on failure
            return transactionLog.get(transactionLog.size() - 1);
        }
        
        return transactionLog.get(transactionLog.size() - 1);
    }
    
    /**
     * Get all transaction records for auditing.
     */
    public List<TransactionRecord> getTransactionLog() {
        return Collections.unmodifiableList(transactionLog);
    }
    
    /**
     * Get statistics about transactions.
     */
    public TransactionStats getStats() {
        int committed = 0;
        int rolledBack = 0;
        long totalDuration = 0;
        
        for (TransactionRecord record : transactionLog) {
            if (record.getFinalState() == TransactionState.COMMITTED) {
                committed++;
            } else {
                rolledBack++;
            }
            totalDuration += record.getDurationMs();
        }
        
        return new TransactionStats(committed, rolledBack, 
            transactionLog.isEmpty() ? 0 : totalDuration / transactionLog.size());
    }
    
    public static class TransactionStats {
        public final int committed;
        public final int rolledBack;
        public final long avgDurationMs;
        
        public TransactionStats(int committed, int rolledBack, long avgDurationMs) {
            this.committed = committed;
            this.rolledBack = rolledBack;
            this.avgDurationMs = avgDurationMs;
        }
        
        @Override
        public String toString() {
            return String.format("TxnStats[committed=%d, rolledBack=%d, avgTime=%dms]",
                committed, rolledBack, avgDurationMs);
        }
    }
    
    private void log(String format, Object... args) {
        if (loggingEnabled) {
            System.out.println(String.format(format, args));
        }
    }
}
