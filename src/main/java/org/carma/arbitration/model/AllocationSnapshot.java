package org.carma.arbitration.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class AllocationSnapshot {

    private final long version;
    private final Map<String, AllocationContract> contracts;
    private final Map<String, ConsumptionLedger> ledgers;
    private final String policyName;
    private final String solverStatus;
    private final long issueTimeMs;
    private final Long expiryTimeMs;

    public AllocationSnapshot(long version,
                              Map<String, AllocationContract> contracts,
                              Map<String, ConsumptionLedger> ledgers,
                              String policyName, String solverStatus,
                              long issueTimeMs, Long expiryTimeMs) {
        this.version = version;
        this.contracts = Collections.unmodifiableMap(new LinkedHashMap<>(contracts));
        this.ledgers = Collections.unmodifiableMap(new LinkedHashMap<>(ledgers));
        this.policyName = policyName == null ? "" : policyName;
        this.solverStatus = solverStatus == null ? "" : solverStatus;
        this.issueTimeMs = issueTimeMs;
        this.expiryTimeMs = expiryTimeMs;
    }

    public static AllocationSnapshot empty() {
        return new AllocationSnapshot(0L, Collections.emptyMap(), Collections.emptyMap(),
            "none", "none", 0L, null);
    }

    public long getVersion() { return version; }

    public AllocationContract getContract(String agentId) { return contracts.get(agentId); }

    public ConsumptionLedger getLedger(String agentId) { return ledgers.get(agentId); }

    public boolean hasContract(String agentId) { return contracts.containsKey(agentId); }

    public Set<String> agentIds() { return contracts.keySet(); }

    public Map<String, AllocationContract> getContracts() { return contracts; }

    public String getPolicyName() { return policyName; }

    public String getSolverStatus() { return solverStatus; }

    public long getIssueTimeMs() { return issueTimeMs; }

    public Long getExpiryTimeMs() { return expiryTimeMs; }
}
