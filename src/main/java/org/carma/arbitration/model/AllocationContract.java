package org.carma.arbitration.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An enforceable, versioned allocation contract issued by the platform to a
 * single agent for a single arbitration epoch.
 *
 * The contract is the minimal representation the runtime installs and enforces:
 * an identifier, a monotonically increasing version (epoch), the owning agent,
 * the complete resource bundle, an issue time, an optional expiry, the policy
 * that produced it, and the solver status reported by that policy.
 */
public final class AllocationContract {

    private final String allocationId;
    private final long version;
    private final String agentId;
    private final Map<ResourceType, Long> bundle;
    private final long issueTimeMs;
    private final Long expiryTimeMs;
    private final String policyName;
    private final String solverStatus;

    public AllocationContract(String allocationId, long version, String agentId,
                              Map<ResourceType, Long> bundle, long issueTimeMs,
                              Long expiryTimeMs, String policyName, String solverStatus) {
        this.allocationId = Objects.requireNonNull(allocationId, "allocationId");
        this.version = version;
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.bundle = Collections.unmodifiableMap(new HashMap<>(
            Objects.requireNonNull(bundle, "bundle")));
        this.issueTimeMs = issueTimeMs;
        this.expiryTimeMs = expiryTimeMs;
        this.policyName = Objects.requireNonNull(policyName, "policyName");
        this.solverStatus = solverStatus == null ? "" : solverStatus;
    }

    public String getAllocationId() { return allocationId; }
    public long getVersion() { return version; }
    public String getAgentId() { return agentId; }
    public Map<ResourceType, Long> getBundle() { return bundle; }
    public long getResource(ResourceType type) { return bundle.getOrDefault(type, 0L); }
    public long getIssueTimeMs() { return issueTimeMs; }
    public Long getExpiryTimeMs() { return expiryTimeMs; }
    public String getPolicyName() { return policyName; }
    public String getSolverStatus() { return solverStatus; }

    public boolean isExpired(long nowMs) {
        return expiryTimeMs != null && nowMs >= expiryTimeMs;
    }

    @Override
    public String toString() {
        return String.format(
            "AllocationContract[id=%s, v=%d, agent=%s, policy=%s, status=%s, bundle=%s%s]",
            allocationId, version, agentId, policyName, solverStatus, bundle,
            expiryTimeMs != null ? ", expiry=" + expiryTimeMs : "");
    }
}
