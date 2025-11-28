package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Sequential implementation of JointArbitrator.
 * 
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  WARNING: THIS IS A FALLBACK IMPLEMENTATION                              ║
 * ║                                                                          ║
 * ║  This implementation solves each resource INDEPENDENTLY using the        ║
 * ║  water-filling algorithm. It achieves LOCAL Pareto optimality per        ║
 * ║  resource but NOT GLOBAL Pareto optimality across resources.             ║
 * ║                                                                          ║
 * ║  For true joint optimization, replace with ConvexJointArbitrator         ║
 * ║  using Clarabel or similar convex solver.                                ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 * 
 * LIMITATIONS:
 * - Cannot discover cross-resource trades (e.g., "A gives Compute to B for Storage")
 * - May leave welfare gains unrealized when agents have complementary preferences
 * - Decomposition loses coupling information between resources
 * 
 * TO IMPLEMENT TRUE JOINT OPTIMIZATION:
 * 
 * 1. Formulate as convex program:
 *    maximize: Σᵢ cᵢ · log(Σⱼ wᵢⱼ · aᵢⱼ)
 *    subject to:
 *      Σᵢ aᵢⱼ ≤ Qⱼ           ∀j  (resource limits)
 *      aᵢⱼ ≥ minᵢⱼ           ∀i,j
 *      aᵢⱼ ≤ idealᵢⱼ         ∀i,j
 * 
 * 2. Use Clarabel solver (Rust with Java bindings via JNI):
 *    - Exponential cone constraints for log terms
 *    - Second-order cone for smooth approximations
 *    - Interior point method with O(n²m) complexity
 * 
 * 3. Alternative: ECOS (Embedded Conic Solver) has Java bindings
 * 
 * 4. For prototype: use Python scipy.optimize.minimize with 
 *    method='trust-constr' and call via Jython or subprocess
 */
public class SequentialJointArbitrator implements JointArbitrator {

    private final ProportionalFairnessArbitrator singleResourceArbitrator;
    private final PriorityEconomy economy;

    public SequentialJointArbitrator(PriorityEconomy economy) {
        this.economy = economy;
        this.singleResourceArbitrator = new ProportionalFairnessArbitrator(economy);
    }

    public SequentialJointArbitrator() {
        this(new PriorityEconomy());
    }

    @Override
    public JointAllocationResult arbitrate(
            ContentionDetector.ContentionGroup group,
            Map<String, BigDecimal> currencyCommitments) {
        
        List<Agent> agents = new ArrayList<>(group.getAgents());
        Map<ResourceType, Long> available = group.getAvailableQuantities();
        
        // Create a temporary pool from the group's available quantities
        ResourcePool pool = new ResourcePool(available);
        
        return arbitrate(agents, pool, currencyCommitments);
    }

    @Override
    public JointAllocationResult arbitrate(
            List<Agent> agents,
            ResourcePool pool,
            Map<String, BigDecimal> currencyCommitments) {
        
        long startTime = System.currentTimeMillis();
        
        // Result storage
        Map<String, Map<ResourceType, Long>> allAllocations = new HashMap<>();
        for (Agent agent : agents) {
            allAllocations.put(agent.getId(), new HashMap<>());
        }
        
        // Track feasibility
        boolean allFeasible = true;
        StringBuilder messages = new StringBuilder();
        
        // Solve each resource independently (THIS IS THE LIMITATION)
        Set<ResourceType> resources = pool.getTotalCapacity().keySet();
        
        for (ResourceType type : resources) {
            // Find agents that want this resource
            List<Agent> competing = agents.stream()
                .filter(a -> a.getIdeal(type) > 0)
                .toList();
            
            if (competing.isEmpty()) continue;
            
            if (competing.size() == 1) {
                // Single agent gets what they want (up to available)
                Agent agent = competing.get(0);
                long alloc = Math.min(agent.getIdeal(type), pool.getAvailable(type));
                allAllocations.get(agent.getId()).put(type, alloc);
                continue;
            }
            
            // Create contention and arbitrate
            Contention contention = new Contention(type, competing, pool.getAvailable(type));
            
            if (!contention.isFeasible()) {
                allFeasible = false;
                messages.append(type.name()).append(": infeasible; ");
                continue;
            }
            
            AllocationResult result = singleResourceArbitrator.arbitrate(contention, currencyCommitments);
            
            // Store allocations
            for (Agent agent : competing) {
                long alloc = result.getAllocation(agent.getId());
                allAllocations.get(agent.getId()).put(type, alloc);
            }
        }
        
        // Calculate objective value
        double objectiveValue = calculateObjective(agents, allAllocations, currencyCommitments);
        
        return new JointAllocationResult(
            allAllocations,
            currencyCommitments,
            objectiveValue,
            allFeasible,
            allFeasible ? "Sequential optimization complete (LOCAL optimality only)" 
                       : "Partial feasibility: " + messages,
            System.currentTimeMillis() - startTime
        );
    }

    /**
     * Calculate the objective value: Σᵢ cᵢ · log(Φᵢ(A))
     */
    private double calculateObjective(
            List<Agent> agents,
            Map<String, Map<ResourceType, Long>> allocations,
            Map<String, BigDecimal> currencyCommitments) {
        
        double objective = 0;
        
        for (Agent agent : agents) {
            Map<ResourceType, Long> agentAllocs = allocations.get(agent.getId());
            if (agentAllocs == null || agentAllocs.isEmpty()) continue;
            
            // Calculate utility: Φᵢ(A) = Σⱼ wᵢⱼ · aᵢⱼ
            double utility = 0;
            for (var entry : agentAllocs.entrySet()) {
                double weight = agent.getPreferences().getWeight(entry.getKey());
                utility += weight * entry.getValue();
            }
            
            if (utility > 0) {
                // Priority weight: cᵢ = BaseWeight + burned
                double priorityWeight = economy.calculatePriorityWeight(
                    currencyCommitments.getOrDefault(agent.getId(), BigDecimal.ZERO));
                objective += priorityWeight * Math.log(utility);
            }
        }
        
        return objective;
    }

    /**
     * Estimate welfare loss from sequential vs joint optimization.
     * 
     * This is a rough estimate based on preference complementarity.
     * True welfare loss requires solving the joint problem.
     */
    public double estimateWelfareLoss(List<Agent> agents, ResourcePool pool) {
        // Calculate preference overlap matrix
        Set<ResourceType> resources = pool.getTotalCapacity().keySet();
        int n = agents.size();
        
        // If all agents have similar preferences, loss is minimal
        // If agents have complementary preferences, loss could be significant
        
        double totalComplementarity = 0;
        int pairs = 0;
        
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                PreferenceFunction p1 = agents.get(i).getPreferences();
                PreferenceFunction p2 = agents.get(j).getPreferences();
                
                // Calculate cosine similarity of preference vectors
                double dot = 0, mag1 = 0, mag2 = 0;
                for (ResourceType type : resources) {
                    double w1 = p1.getWeight(type);
                    double w2 = p2.getWeight(type);
                    dot += w1 * w2;
                    mag1 += w1 * w1;
                    mag2 += w2 * w2;
                }
                
                double similarity = (mag1 > 0 && mag2 > 0) 
                    ? dot / (Math.sqrt(mag1) * Math.sqrt(mag2)) : 0;
                
                // Complementarity = 1 - similarity
                totalComplementarity += (1 - similarity);
                pairs++;
            }
        }
        
        // Estimated loss percentage based on average complementarity
        double avgComplementarity = pairs > 0 ? totalComplementarity / pairs : 0;
        
        // Rough estimate: up to 20% welfare loss with high complementarity
        return avgComplementarity * 0.20;
    }

    public PriorityEconomy getEconomy() {
        return economy;
    }
}
