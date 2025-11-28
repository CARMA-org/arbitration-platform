package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Centralized safety monitor that enforces system invariants before any state commit.
 * 
 * The SafetyMonitor acts as a gatekeeper that validates all state transitions
 * to ensure the system maintains its safety properties:
 * 
 * 1. Resource Conservation: Total allocations ≤ Total capacity
 * 2. Non-negativity: All allocations and balances ≥ 0
 * 3. Bound Compliance: min ≤ allocation ≤ ideal for all agents
 * 4. Currency Conservation: No currency created from nothing
 * 5. Individual Rationality: Agents receive ≥ outside option
 * 
 * This centralizes checks that were previously scattered throughout the codebase.
 */
public class SafetyMonitor {

    /**
     * Result of a safety check.
     */
    public static class SafetyCheckResult {
        private final boolean safe;
        private final List<String> violations;
        private final long checkedAtMs;

        public SafetyCheckResult(boolean safe, List<String> violations) {
            this.safe = safe;
            this.violations = new ArrayList<>(violations);
            this.checkedAtMs = System.currentTimeMillis();
        }

        public static SafetyCheckResult pass() {
            return new SafetyCheckResult(true, Collections.emptyList());
        }

        public static SafetyCheckResult fail(String... violations) {
            return new SafetyCheckResult(false, Arrays.asList(violations));
        }

        public static SafetyCheckResult fail(List<String> violations) {
            return new SafetyCheckResult(false, violations);
        }

        public boolean isSafe() { return safe; }
        public List<String> getViolations() { return Collections.unmodifiableList(violations); }
        public long getCheckedAtMs() { return checkedAtMs; }

        @Override
        public String toString() {
            if (safe) {
                return "SafetyCheckResult[PASS]";
            } else {
                return "SafetyCheckResult[FAIL: " + String.join("; ", violations) + "]";
            }
        }
    }

    /**
     * Tracks all safety checks performed.
     */
    public static class SafetyLog {
        private final List<SafetyCheckResult> checks = new ArrayList<>();
        private int passCount = 0;
        private int failCount = 0;

        public void record(SafetyCheckResult result) {
            checks.add(result);
            if (result.isSafe()) passCount++;
            else failCount++;
        }

        public int getPassCount() { return passCount; }
        public int getFailCount() { return failCount; }
        public int getTotalChecks() { return checks.size(); }
        public List<SafetyCheckResult> getFailures() {
            return checks.stream().filter(c -> !c.isSafe()).toList();
        }

        @Override
        public String toString() {
            return String.format("SafetyLog[%d checks: %d pass, %d fail]",
                getTotalChecks(), passCount, failCount);
        }
    }

    private final SafetyLog log;
    private boolean strictMode;

    public SafetyMonitor() {
        this.log = new SafetyLog();
        this.strictMode = true;
    }

    /**
     * In strict mode, failed checks throw exceptions.
     * In lenient mode, failed checks are logged but execution continues.
     */
    public SafetyMonitor setStrictMode(boolean strict) {
        this.strictMode = strict;
        return this;
    }

    // ========================================================================
    // Invariant 1: Resource Conservation
    // ========================================================================

    /**
     * Verify that total allocations do not exceed pool capacity.
     */
    public SafetyCheckResult checkResourceConservation(
            Map<String, Map<ResourceType, Long>> allocations,
            ResourcePool pool) {
        
        List<String> violations = new ArrayList<>();
        
        for (ResourceType type : pool.getTotalCapacity().keySet()) {
            long capacity = pool.getCapacity(type);
            long totalAllocated = 0;
            
            for (var agentAllocs : allocations.values()) {
                totalAllocated += agentAllocs.getOrDefault(type, 0L);
            }
            
            if (totalAllocated > capacity) {
                violations.add(String.format(
                    "Resource conservation violated for %s: allocated %d > capacity %d",
                    type.name(), totalAllocated, capacity));
            }
        }
        
        SafetyCheckResult result = violations.isEmpty() 
            ? SafetyCheckResult.pass() 
            : SafetyCheckResult.fail(violations);
        
        log.record(result);
        enforceIfStrict(result, "Resource conservation");
        return result;
    }

    /**
     * Verify single-resource allocation result.
     */
    public SafetyCheckResult checkResourceConservation(AllocationResult result, long capacity) {
        long total = result.getAllocations().values().stream().mapToLong(Long::longValue).sum();
        
        SafetyCheckResult check;
        if (total > capacity) {
            check = SafetyCheckResult.fail(String.format(
                "Resource conservation violated: allocated %d > capacity %d", total, capacity));
        } else {
            check = SafetyCheckResult.pass();
        }
        
        log.record(check);
        enforceIfStrict(check, "Resource conservation");
        return check;
    }

    // ========================================================================
    // Invariant 2: Non-negativity
    // ========================================================================

    /**
     * Verify all allocations are non-negative.
     */
    public SafetyCheckResult checkNonNegativity(Map<String, Map<ResourceType, Long>> allocations) {
        List<String> violations = new ArrayList<>();
        
        for (var entry : allocations.entrySet()) {
            String agentId = entry.getKey();
            for (var alloc : entry.getValue().entrySet()) {
                if (alloc.getValue() < 0) {
                    violations.add(String.format(
                        "Non-negativity violated: agent %s has %d %s",
                        agentId, alloc.getValue(), alloc.getKey().name()));
                }
            }
        }
        
        SafetyCheckResult result = violations.isEmpty() 
            ? SafetyCheckResult.pass() 
            : SafetyCheckResult.fail(violations);
        
        log.record(result);
        enforceIfStrict(result, "Non-negativity");
        return result;
    }

    /**
     * Verify agent currency balance is non-negative (within allowed debt limit).
     */
    public SafetyCheckResult checkCurrencyNonNegativity(Agent agent, BigDecimal minAllowedBalance) {
        SafetyCheckResult result;
        if (agent.getCurrencyBalance().compareTo(minAllowedBalance) < 0) {
            result = SafetyCheckResult.fail(String.format(
                "Currency non-negativity violated: agent %s has balance %s < min %s",
                agent.getId(), agent.getCurrencyBalance(), minAllowedBalance));
        } else {
            result = SafetyCheckResult.pass();
        }
        
        log.record(result);
        enforceIfStrict(result, "Currency non-negativity");
        return result;
    }

    // ========================================================================
    // Invariant 3: Bound Compliance
    // ========================================================================

    /**
     * Verify all allocations respect agent min/ideal bounds.
     */
    public SafetyCheckResult checkBoundCompliance(
            Map<String, Long> allocations,
            List<Agent> agents,
            ResourceType type) {
        
        List<String> violations = new ArrayList<>();
        
        for (Agent agent : agents) {
            Long alloc = allocations.get(agent.getId());
            if (alloc == null) continue;
            
            long min = agent.getMinimum(type);
            long ideal = agent.getIdeal(type);
            
            if (alloc < min) {
                violations.add(String.format(
                    "Bound compliance violated: agent %s got %d < min %d for %s",
                    agent.getId(), alloc, min, type.name()));
            }
            if (alloc > ideal) {
                violations.add(String.format(
                    "Bound compliance violated: agent %s got %d > ideal %d for %s",
                    agent.getId(), alloc, ideal, type.name()));
            }
        }
        
        SafetyCheckResult result = violations.isEmpty() 
            ? SafetyCheckResult.pass() 
            : SafetyCheckResult.fail(violations);
        
        log.record(result);
        enforceIfStrict(result, "Bound compliance");
        return result;
    }

    // ========================================================================
    // Invariant 4: Currency Conservation
    // ========================================================================

    /**
     * Verify that currency is conserved (total minted = total earned + initial).
     * Note: Burned currency is destroyed, not transferred.
     */
    public SafetyCheckResult checkCurrencyConservation(
            List<Agent> agents,
            BigDecimal totalInitialCurrency,
            BigDecimal totalMinted,
            BigDecimal totalBurned) {
        
        BigDecimal totalCurrent = agents.stream()
            .map(Agent::getCurrencyBalance)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        // Expected: initial + minted - burned = current
        BigDecimal expected = totalInitialCurrency.add(totalMinted).subtract(totalBurned);
        BigDecimal diff = expected.subtract(totalCurrent).abs();
        
        SafetyCheckResult result;
        // Allow small floating point tolerance
        if (diff.compareTo(BigDecimal.valueOf(0.01)) > 0) {
            result = SafetyCheckResult.fail(String.format(
                "Currency conservation violated: expected %s but found %s (diff=%s)",
                expected, totalCurrent, diff));
        } else {
            result = SafetyCheckResult.pass();
        }
        
        log.record(result);
        enforceIfStrict(result, "Currency conservation");
        return result;
    }

    // ========================================================================
    // Invariant 5: Individual Rationality
    // ========================================================================

    /**
     * Verify all agents receive at least their outside option (minimum allocation).
     */
    public SafetyCheckResult checkIndividualRationality(
            Map<String, Long> allocations,
            List<Agent> agents,
            ResourceType type) {
        
        List<String> violations = new ArrayList<>();
        
        for (Agent agent : agents) {
            Long alloc = allocations.get(agent.getId());
            if (alloc == null) alloc = 0L;
            
            long outsideOption = agent.getMinimum(type);
            
            if (alloc < outsideOption) {
                violations.add(String.format(
                    "Individual rationality violated: agent %s got %d < outside option %d",
                    agent.getId(), alloc, outsideOption));
            }
        }
        
        SafetyCheckResult result = violations.isEmpty() 
            ? SafetyCheckResult.pass() 
            : SafetyCheckResult.fail(violations);
        
        log.record(result);
        enforceIfStrict(result, "Individual rationality");
        return result;
    }

    // ========================================================================
    // Composite Checks
    // ========================================================================

    /**
     * Run all safety checks on an allocation result.
     */
    public SafetyCheckResult checkAllocationResult(
            AllocationResult result,
            List<Agent> agents,
            long capacity) {
        
        List<String> allViolations = new ArrayList<>();
        
        // Check resource conservation
        SafetyCheckResult conservation = checkResourceConservation(result, capacity);
        if (!conservation.isSafe()) {
            allViolations.addAll(conservation.getViolations());
        }
        
        // Check bound compliance
        SafetyCheckResult bounds = checkBoundCompliance(
            result.getAllocations(), agents, result.getResourceType());
        if (!bounds.isSafe()) {
            allViolations.addAll(bounds.getViolations());
        }
        
        // Check individual rationality
        SafetyCheckResult ir = checkIndividualRationality(
            result.getAllocations(), agents, result.getResourceType());
        if (!ir.isSafe()) {
            allViolations.addAll(ir.getViolations());
        }
        
        return allViolations.isEmpty() 
            ? SafetyCheckResult.pass() 
            : SafetyCheckResult.fail(allViolations);
    }

    /**
     * Validate a proposed state transition before committing.
     */
    public SafetyCheckResult validateTransition(
            Map<String, Map<ResourceType, Long>> newAllocations,
            ResourcePool pool,
            List<Agent> agents) {
        
        List<String> allViolations = new ArrayList<>();
        
        // Check resource conservation
        SafetyCheckResult conservation = checkResourceConservation(newAllocations, pool);
        if (!conservation.isSafe()) {
            allViolations.addAll(conservation.getViolations());
        }
        
        // Check non-negativity
        SafetyCheckResult nonNeg = checkNonNegativity(newAllocations);
        if (!nonNeg.isSafe()) {
            allViolations.addAll(nonNeg.getViolations());
        }
        
        return allViolations.isEmpty() 
            ? SafetyCheckResult.pass() 
            : SafetyCheckResult.fail(allViolations);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private void enforceIfStrict(SafetyCheckResult result, String checkName) {
        if (strictMode && !result.isSafe()) {
            throw new SafetyViolationException(checkName, result.getViolations());
        }
    }

    public SafetyLog getLog() {
        return log;
    }

    /**
     * Exception thrown when a safety invariant is violated in strict mode.
     */
    public static class SafetyViolationException extends RuntimeException {
        private final String checkName;
        private final List<String> violations;

        public SafetyViolationException(String checkName, List<String> violations) {
            super(checkName + " violated: " + String.join("; ", violations));
            this.checkName = checkName;
            this.violations = violations;
        }

        public String getCheckName() { return checkName; }
        public List<String> getViolations() { return violations; }
    }

    @Override
    public String toString() {
        return String.format("SafetyMonitor[strict=%s, %s]", strictMode, log);
    }
}
