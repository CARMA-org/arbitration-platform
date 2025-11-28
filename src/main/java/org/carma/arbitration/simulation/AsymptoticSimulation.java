package org.carma.arbitration.simulation;

import org.carma.arbitration.mechanism.*;
import org.carma.arbitration.model.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Long-running simulation to analyze asymptotic behavior of the arbitration platform.
 * 
 * This simulation runs for a configurable duration (default 15 seconds) and tracks:
 * - Welfare convergence
 * - Currency distribution evolution
 * - Allocation stability
 * - System equilibrium properties
 */
public class AsymptoticSimulation {
    
    private final ProportionalFairnessArbitrator arbitrator;
    private final PriorityEconomy economy;
    private final List<Agent> agents;
    private final ResourcePool pool;
    private final SimulationMetrics metrics;
    private final Random random;
    
    private long durationMs;
    private long tickIntervalMs;
    private boolean verbose;

    public AsymptoticSimulation(int numAgents, Map<ResourceType, Long> poolCapacity) {
        this.economy = new PriorityEconomy();
        this.arbitrator = new ProportionalFairnessArbitrator(economy);
        this.agents = new ArrayList<>();
        this.pool = new ResourcePool(poolCapacity);
        this.metrics = new SimulationMetrics();
        this.random = new Random(42); // Fixed seed for reproducibility
        
        this.durationMs = 15_000; // 15 seconds default
        this.tickIntervalMs = 50; // 50ms per tick = 20 ticks/second
        this.verbose = false;
        
        initializeAgents(numAgents, poolCapacity);
    }

    // ========================================================================
    // Configuration
    // ========================================================================

    public AsymptoticSimulation setDuration(long durationMs) {
        this.durationMs = durationMs;
        return this;
    }

    public AsymptoticSimulation setTickInterval(long tickIntervalMs) {
        this.tickIntervalMs = tickIntervalMs;
        return this;
    }

    public AsymptoticSimulation setVerbose(boolean verbose) {
        this.verbose = verbose;
        return this;
    }

    // ========================================================================
    // Agent Initialization
    // ========================================================================

    private void initializeAgents(int numAgents, Map<ResourceType, Long> poolCapacity) {
        List<ResourceType> types = new ArrayList<>(poolCapacity.keySet());
        
        for (int i = 0; i < numAgents; i++) {
            // Create diverse preferences
            Map<ResourceType, Double> prefs = new HashMap<>();
            double remaining = 1.0;
            for (int j = 0; j < types.size(); j++) {
                ResourceType type = types.get(j);
                if (j == types.size() - 1) {
                    prefs.put(type, remaining);
                } else {
                    // Random preference with some bias toward specialization
                    double weight = remaining * (0.3 + 0.7 * random.nextDouble());
                    prefs.put(type, weight);
                    remaining -= weight;
                }
            }
            
            // Randomize initial currency (50-150)
            double initialCurrency = 50 + random.nextDouble() * 100;
            
            Agent agent = new Agent(
                "A" + (i + 1),
                "Agent " + (i + 1),
                prefs,
                initialCurrency
            );
            
            // Set requests based on pool size and agent count
            for (var entry : poolCapacity.entrySet()) {
                ResourceType type = entry.getKey();
                long capacity = entry.getValue();
                long fairShare = capacity / numAgents;
                
                long min = (long) (fairShare * (0.2 + 0.3 * random.nextDouble()));
                long ideal = (long) (fairShare * (1.0 + random.nextDouble()));
                agent.setRequest(type, min, ideal);
            }
            
            agents.add(agent);
        }
    }

    // ========================================================================
    // Simulation Execution
    // ========================================================================

    public SimulationMetrics run() {
        long startTime = System.currentTimeMillis();
        long tickCount = 0;
        
        System.out.println("Starting asymptotic simulation...");
        System.out.println("  Duration: " + (durationMs / 1000.0) + " seconds");
        System.out.println("  Tick interval: " + tickIntervalMs + "ms");
        System.out.println("  Agents: " + agents.size());
        System.out.println("  Resources: " + pool.getTotalCapacity());
        System.out.println();
        
        while (System.currentTimeMillis() - startTime < durationMs) {
            tickCount++;
            
            // Run one arbitration cycle
            runTick(tickCount);
            
            // Record metrics
            double welfare = calculateTotalWelfare();
            double gini = calculateGini();
            Map<String, Double> currencies = agents.stream()
                .collect(Collectors.toMap(Agent::getId, a -> a.getCurrencyBalance().doubleValue()));
            Map<String, Long> allocations = agents.stream()
                .collect(Collectors.toMap(Agent::getId, Agent::getCurrentUtility))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().longValue()));
            
            metrics.recordTick(welfare, gini, currencies, allocations);
            
            // Progress reporting
            if (verbose && tickCount % 100 == 0) {
                long elapsed = System.currentTimeMillis() - startTime;
                System.out.printf("  Tick %d (%.1fs): welfare=%.4f, gini=%.4f\n",
                    tickCount, elapsed / 1000.0, welfare, gini);
            }
            
            // Sleep to maintain tick interval
            try {
                Thread.sleep(tickIntervalMs);
            } catch (InterruptedException e) {
                break;
            }
        }
        
        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println();
        System.out.println("Simulation complete:");
        System.out.println("  Total time: " + (totalTime / 1000.0) + " seconds");
        System.out.println("  Total ticks: " + tickCount);
        System.out.println("  Effective rate: " + String.format("%.1f", tickCount * 1000.0 / totalTime) + " ticks/sec");
        
        return metrics;
    }

    private void runTick(long tickCount) {
        // Reset pool for new round
        pool.reset();
        
        // Agents decide how much currency to burn (simple strategy)
        Map<String, BigDecimal> burns = new HashMap<>();
        for (Agent agent : agents) {
            // Strategy: burn 5-15% of balance based on currency level
            BigDecimal balance = agent.getCurrencyBalance();
            if (balance.compareTo(BigDecimal.ZERO) > 0) {
                double burnFraction = 0.05 + 0.10 * random.nextDouble();
                BigDecimal toBurn = balance.multiply(BigDecimal.valueOf(burnFraction))
                    .setScale(2, RoundingMode.HALF_UP);
                burns.put(agent.getId(), toBurn);
            } else {
                burns.put(agent.getId(), BigDecimal.ZERO);
            }
        }
        
        // Arbitrate each resource type
        for (ResourceType type : pool.getTotalCapacity().keySet()) {
            List<Agent> competing = agents.stream()
                .filter(a -> a.getIdeal(type) > 0)
                .toList();
            
            if (competing.size() < 2) continue;
            
            Contention contention = new Contention(type, competing, pool.getAvailable(type));
            
            // Record contention for histogram
            metrics.recordContention(competing.size(), contention.getContentionRatio());
            
            AllocationResult result = arbitrator.arbitrate(contention, burns);
            
            // Apply allocations
            for (Agent agent : competing) {
                long alloc = result.getAllocation(agent.getId());
                agent.setAllocation(type, alloc);
                if (alloc > 0) {
                    pool.allocate(type, alloc);
                }
            }
        }
        
        // Execute currency burns
        for (Agent agent : agents) {
            BigDecimal toBurn = burns.getOrDefault(agent.getId(), BigDecimal.ZERO);
            if (toBurn.compareTo(BigDecimal.ZERO) > 0 && agent.canBurn(toBurn)) {
                agent.burnCurrency(toBurn);
            }
        }
        
        // Some agents release early (earn currency based on scarcity)
        for (Agent agent : agents) {
            // 30% chance to release early
            if (random.nextDouble() < 0.30) {
                // Release from each resource type the agent holds
                for (ResourceType type : pool.getTotalCapacity().keySet()) {
                    long currentAlloc = agent.getAllocation(type);
                    if (currentAlloc > 0) {
                        // Release 20-50% of current allocation
                        double releaseFraction = 0.2 + 0.3 * random.nextDouble();
                        long released = (long) (currentAlloc * releaseFraction);
                        
                        if (released > 0) {
                            // Time remaining fraction (simulate 50-90% of lease remaining)
                            double timeRemainingFraction = 0.5 + 0.4 * random.nextDouble();
                            
                            // Calculate earnings using proper scarcity-aware formula
                            BigDecimal earnings = economy.calculateReleaseEarnings(
                                type, released, timeRemainingFraction, pool);
                            
                            agent.earnCurrency(earnings);
                            
                            // Update allocation to reflect release
                            agent.setAllocation(type, currentAlloc - released);
                            pool.release(type, released);
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    // Analysis Methods
    // ========================================================================

    private double calculateTotalWelfare() {
        double welfare = 0;
        for (Agent agent : agents) {
            double utility = agent.getCurrentUtility();
            if (utility > 0) {
                welfare += PriorityEconomy.BASE_WEIGHT * Math.log(utility);
            }
        }
        return welfare;
    }

    private double calculateGini() {
        List<Double> utilities = agents.stream()
            .map(Agent::getCurrentUtility)
            .sorted()
            .collect(Collectors.toList());
        
        int n = utilities.size();
        if (n < 2) return 0;
        
        double sum = 0;
        double total = 0;
        for (int i = 0; i < n; i++) {
            sum += (2 * (i + 1) - n - 1) * utilities.get(i);
            total += utilities.get(i);
        }
        
        if (total == 0) return 0;
        return sum / (n * total);
    }

    // ========================================================================
    // Main Entry Point
    // ========================================================================

    public static void main(String[] args) {
        // Default configuration: 10 agents, 2 resource types
        Map<ResourceType, Long> capacity = new HashMap<>();
        capacity.put(ResourceType.COMPUTE, 500L);
        capacity.put(ResourceType.STORAGE, 500L);
        
        int numAgents = 10;
        long durationSec = 15;
        
        // Parse command line args
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--agents", "-a" -> numAgents = Integer.parseInt(args[++i]);
                case "--duration", "-d" -> durationSec = Long.parseLong(args[++i]);
                case "--verbose", "-v" -> {}
            }
        }
        
        boolean verbose = Arrays.asList(args).contains("-v") || 
                         Arrays.asList(args).contains("--verbose");
        
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║         ASYMPTOTIC BEHAVIOR SIMULATION                           ║");
        System.out.println("║         Testing Convergence and Equilibrium Properties           ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();
        
        AsymptoticSimulation sim = new AsymptoticSimulation(numAgents, capacity)
            .setDuration(durationSec * 1000)
            .setTickInterval(50)
            .setVerbose(verbose);
        
        SimulationMetrics results = sim.run();
        
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("ASYMPTOTIC ANALYSIS RESULTS");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println(results.getSummary());
        
        // Print convergence analysis
        System.out.println("Convergence Analysis:");
        boolean converged = results.hasConverged(20, 0.01);
        System.out.println("  Converged (1% threshold): " + (converged ? "✓ YES" : "✗ NO"));
        System.out.println("  Welfare trend: " + String.format("%.6f", results.getWelfareTrend(20)));
        
        // Print contention histogram
        System.out.println();
        System.out.println("Contention Histogram (by number of competing agents):");
        System.out.print(results.getContentionHistogramString());
        
        // Print welfare trajectory (first 5, last 5)
        List<Double> welfare = results.getWelfareHistory();
        System.out.println();
        System.out.println("Welfare Trajectory:");
        System.out.println("  First 5 ticks: " + welfare.subList(0, Math.min(5, welfare.size())).stream()
            .map(w -> String.format("%.2f", w))
            .collect(Collectors.joining(" → ")));
        if (welfare.size() > 10) {
            System.out.println("  Last 5 ticks:  " + welfare.subList(welfare.size() - 5, welfare.size()).stream()
                .map(w -> String.format("%.2f", w))
                .collect(Collectors.joining(" → ")));
        }
        
        // Final agent states
        System.out.println();
        System.out.println("Final Agent States:");
        for (Agent agent : sim.agents) {
            System.out.printf("  %s: currency=%.2f, utility=%.2f\n",
                agent.getId(), agent.getCurrencyBalance(), agent.getCurrentUtility());
        }
        
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("SIMULATION COMPLETE");
        System.out.println("═══════════════════════════════════════════════════════════════════");
    }
}
