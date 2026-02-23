package org.carma.arbitration.pareto;

import org.carma.arbitration.mechanism.PriorityEconomy;
import org.carma.arbitration.mechanism.ProportionalFairnessArbitrator;
import org.carma.arbitration.model.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Orchestrates longitudinal Pareto analysis simulations.
 *
 * Runs multiple arbitration rounds and tracks:
 * - Per-agent allocations and utility over time
 * - Pareto optimality per round
 * - Pareto improvements between rounds
 * - Strategy effectiveness
 */
public class ParetoAnalysisSimulation {

    // Configuration
    private final int totalRounds;
    private final ResourceType resourceType;
    private final long resourceCapacity;
    private final double currencyEarningRate;  // Currency earned per unit released
    private final boolean verbose;

    // Components
    private final ProportionalFairnessArbitrator arbitrator;
    private final PriorityEconomy economy;
    private final ParetoVerifier verifier;
    private final LongitudinalTracker tracker;

    // Agents with strategies
    private final List<AgentWithStrategy> agents;

    /**
     * Agent paired with its strategy.
     */
    public record AgentWithStrategy(Agent agent, AgentStrategy strategy) {}

    /**
     * Result of running the simulation.
     */
    public record SimulationResult(
        // Question 1: Are all agents better off?
        boolean allAgentsBetterOff,
        List<String> betterOffAgents,
        List<String> worseOffAgents,

        // Question 2: Does sacrifice lead to gain?
        boolean sacrificeLeadsToGain,
        Map<String, Double> strategyEffectiveness,
        Map<String, Double> strategyEfficiency,

        // Pareto properties
        double paretoOptimalityRate,
        double paretoImprovementRate,
        long strictImprovementCount,

        // Full data
        LongitudinalTracker tracker,
        int roundsCompleted
    ) {}

    // ==========================================================================
    // Constructor
    // ==========================================================================

    public ParetoAnalysisSimulation(Builder builder) {
        this.totalRounds = builder.totalRounds;
        this.resourceType = builder.resourceType;
        this.resourceCapacity = builder.resourceCapacity;
        this.currencyEarningRate = builder.currencyEarningRate;
        this.verbose = builder.verbose;

        this.economy = new PriorityEconomy();
        this.arbitrator = new ProportionalFairnessArbitrator(economy);
        this.verifier = new ParetoVerifier();
        this.tracker = new LongitudinalTracker();

        this.agents = new ArrayList<>(builder.agents);

        // Register agents with tracker
        for (AgentWithStrategy aws : agents) {
            tracker.registerAgent(
                aws.agent().getId(),
                aws.strategy(),
                aws.agent().getIdeal(resourceType)
            );
        }
    }

    // ==========================================================================
    // Main Simulation
    // ==========================================================================

    public SimulationResult run() {
        if (verbose) {
            System.out.println("Starting simulation: " + totalRounds + " rounds, " +
                agents.size() + " agents, " + resourceCapacity + " " + resourceType);
        }

        Map<String, Double> previousUtilities = new HashMap<>();

        for (int round = 1; round <= totalRounds; round++) {
            Map<String, Double> currentUtilities = runRound(round, previousUtilities);
            previousUtilities = currentUtilities;

            if (verbose && round % 50 == 0) {
                System.out.println("  Round " + round + "/" + totalRounds + " complete");
            }
        }

        return buildResult();
    }

    private Map<String, Double> runRound(int round, Map<String, Double> previousUtilities) {
        // 1. Calculate contention ratio for adaptive strategies
        double contentionRatio = calculateContentionRatio();

        // 2. Each agent decides burn amount based on strategy
        Map<String, BigDecimal> burns = new HashMap<>();
        Map<String, Double> currencyBefore = new HashMap<>();

        for (AgentWithStrategy aws : agents) {
            Agent agent = aws.agent();
            AgentStrategy strategy = aws.strategy();

            currencyBefore.put(agent.getId(), agent.getCurrencyBalance().doubleValue());
            BigDecimal burn = strategy.decideBurn(agent, round, contentionRatio);

            // Ensure we don't burn more than allowed
            if (!agent.canBurn(burn)) {
                burn = agent.getCurrencyBalance().subtract(Agent.MIN_BALANCE);
                if (burn.compareTo(BigDecimal.ZERO) < 0) {
                    burn = BigDecimal.ZERO;
                }
            }
            burns.put(agent.getId(), burn);
        }

        // 3. Create contention and run arbitration
        List<Agent> agentList = agents.stream().map(AgentWithStrategy::agent).toList();
        Contention contention = new Contention(resourceType, agentList, resourceCapacity);
        AllocationResult result = arbitrator.arbitrate(contention, burns);

        // 4. Apply allocations and record metrics
        Map<String, Double> currentUtilities = new HashMap<>();
        Map<String, Long> allocations = new HashMap<>();
        Map<String, Double> weights = new HashMap<>();

        for (AgentWithStrategy aws : agents) {
            Agent agent = aws.agent();
            String id = agent.getId();

            long allocation = result.getAllocation(id);
            agent.setAllocation(resourceType, allocation);
            allocations.put(id, allocation);

            // Calculate weighted log utility
            double weight = economy.calculatePriorityWeight(burns.get(id));
            weights.put(id, weight);
            double utility = allocation > 0 ? weight * Math.log(allocation) : 0;
            currentUtilities.put(id, utility);

            // Execute burn
            BigDecimal burnAmount = burns.get(id);
            if (burnAmount.compareTo(BigDecimal.ZERO) > 0) {
                agent.burnCurrency(burnAmount);
            }

            // Earn currency from "releasing" resources (simplified model)
            BigDecimal earned = BigDecimal.valueOf(allocation * currencyEarningRate);
            agent.earnCurrency(earned);

            // Calculate satisfaction
            long ideal = agent.getIdeal(resourceType);
            double satisfaction = ideal > 0 ? (double) allocation / ideal : 1.0;

            // Record in tracker
            tracker.recordRound(
                id,
                round,
                allocation,
                utility,
                currencyBefore.get(id),
                burnAmount.doubleValue(),
                agent.getCurrencyBalance().doubleValue(),
                satisfaction
            );
        }

        // 5. Verify Pareto optimality
        boolean isOptimal = verifier.isParetoOptimal(allocations, weights, resourceCapacity);
        tracker.recordParetoOptimality(isOptimal);

        // 6. Compare with previous round (if not first round)
        if (!previousUtilities.isEmpty()) {
            ParetoVerifier.ParetoComparison comparison =
                verifier.compareParetoStates(previousUtilities, currentUtilities);
            tracker.recordRoundComparison(comparison);
        }

        return currentUtilities;
    }

    private double calculateContentionRatio() {
        long totalDemand = agents.stream()
            .mapToLong(aws -> aws.agent().getIdeal(resourceType))
            .sum();
        return (double) totalDemand / resourceCapacity;
    }

    private SimulationResult buildResult() {
        return new SimulationResult(
            tracker.areAllAgentsBetterOff(),
            tracker.getBetterOffAgents(),
            tracker.getWorseOffAgents(),
            tracker.didSacrificingAgentsGainMore(),
            tracker.getStrategyEffectiveness(),
            tracker.getStrategyEfficiency(),
            tracker.getParetoOptimalityRate(),
            tracker.getParetoImprovementRate(),
            tracker.getStrictImprovementCount(),
            tracker,
            totalRounds
        );
    }

    // ==========================================================================
    // Builder
    // ==========================================================================

    public static class Builder {
        private int totalRounds = 200;
        private ResourceType resourceType = ResourceType.COMPUTE;
        private long resourceCapacity = 500;
        private double currencyEarningRate = 0.05;  // 5% of allocation earned back
        private boolean verbose = false;
        private final List<AgentWithStrategy> agents = new ArrayList<>();

        public Builder totalRounds(int rounds) {
            this.totalRounds = rounds;
            return this;
        }

        public Builder resourceType(ResourceType type) {
            this.resourceType = type;
            return this;
        }

        public Builder resourceCapacity(long capacity) {
            this.resourceCapacity = capacity;
            return this;
        }

        public Builder currencyEarningRate(double rate) {
            this.currencyEarningRate = rate;
            return this;
        }

        public Builder verbose(boolean v) {
            this.verbose = v;
            return this;
        }

        public Builder addAgent(Agent agent, AgentStrategy strategy) {
            this.agents.add(new AgentWithStrategy(agent, strategy));
            return this;
        }

        public Builder addAgents(List<AgentWithStrategy> agentList) {
            this.agents.addAll(agentList);
            return this;
        }

        public ParetoAnalysisSimulation build() {
            if (agents.isEmpty()) {
                throw new IllegalStateException("At least one agent required");
            }
            return new ParetoAnalysisSimulation(this);
        }
    }

    // ==========================================================================
    // Factory Methods for Creating Test Agents
    // ==========================================================================

    /**
     * Create a diverse set of agents with different strategies.
     * 3 agents per strategy type for statistical significance.
     */
    public static List<AgentWithStrategy> createDiverseAgents(
            ResourceType resourceType,
            double initialCurrency,
            int agentsPerStrategy) {

        List<AgentWithStrategy> result = new ArrayList<>();

        // Create preference function (simple linear)
        Map<ResourceType, Double> prefs = Map.of(resourceType, 1.0);

        // Sacrifice round count for SacrificeAndRecover strategy
        int sacrificeRounds = 50;

        AgentStrategy[] strategies = {
            new AgentStrategy.ConservativeStrategy(),
            new AgentStrategy.AggressiveStrategy(),
            new AgentStrategy.AdaptiveStrategy(),
            new AgentStrategy.SacrificeAndRecoverStrategy(sacrificeRounds)
        };

        String[] codes = {"C", "A", "D", "S"};

        for (int s = 0; s < strategies.length; s++) {
            AgentStrategy strategy = strategies[s];
            String code = codes[s];

            for (int i = 1; i <= agentsPerStrategy; i++) {
                String id = code + i;
                Agent agent = new Agent(id, strategy.getName() + " Agent " + i, prefs, initialCurrency);

                // Set resource requests (random variation within range)
                long min = 10 + (i * 5);  // 15, 20, 25
                long ideal = 50 + (i * 10);  // 60, 70, 80
                agent.setRequest(resourceType, min, ideal);

                result.add(new AgentWithStrategy(agent, strategy));
            }
        }

        return result;
    }
}
