package org.carma.arbitration.pareto;

import org.carma.arbitration.model.Agent;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Strategy interface for currency burning decisions.
 * Different strategies test the "sacrifice now, gain later" hypothesis.
 */
public interface AgentStrategy {

    /**
     * Decide how much currency to burn this round.
     * @param agent The agent making the decision
     * @param round Current round number
     * @param contentionRatio Current demand/supply ratio (>1 means oversubscribed)
     * @return Amount of currency to burn
     */
    BigDecimal decideBurn(Agent agent, int round, double contentionRatio);

    /**
     * Get strategy name for reporting.
     */
    String getName();

    /**
     * Get short code for tables.
     */
    default String getCode() {
        return getName().substring(0, 1).toUpperCase();
    }

    // ==========================================================================
    // STRATEGY IMPLEMENTATIONS
    // ==========================================================================

    /**
     * Conservative Strategy: Burns minimal currency (0-5% of balance).
     * Represents risk-averse agents who prefer to conserve resources.
     * Baseline for comparison.
     */
    class ConservativeStrategy implements AgentStrategy {
        private static final double BURN_RATE = 0.03; // 3% of balance

        @Override
        public BigDecimal decideBurn(Agent agent, int round, double contentionRatio) {
            BigDecimal balance = agent.getCurrencyBalance();
            if (balance.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ZERO;
            }
            return balance.multiply(BigDecimal.valueOf(BURN_RATE))
                         .setScale(2, RoundingMode.HALF_UP);
        }

        @Override
        public String getName() {
            return "Conservative";
        }

        @Override
        public String getCode() {
            return "C";
        }
    }

    /**
     * Aggressive Strategy: Burns high currency (25-35% of balance).
     * Tests the hypothesis that sacrifice leads to greater allocation.
     * Should gain more per round but deplete currency faster.
     */
    class AggressiveStrategy implements AgentStrategy {
        private static final double BASE_BURN_RATE = 0.30; // 30% of balance

        @Override
        public BigDecimal decideBurn(Agent agent, int round, double contentionRatio) {
            BigDecimal balance = agent.getCurrencyBalance();
            if (balance.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ZERO;
            }
            // Burn more when contention is high
            double adjustedRate = BASE_BURN_RATE * Math.min(1.5, Math.max(0.8, contentionRatio));
            return balance.multiply(BigDecimal.valueOf(adjustedRate))
                         .setScale(2, RoundingMode.HALF_UP);
        }

        @Override
        public String getName() {
            return "Aggressive";
        }

        @Override
        public String getCode() {
            return "A";
        }
    }

    /**
     * Adaptive Strategy: Burns based on contention ratio.
     * Low contention: conserve currency (5%)
     * High contention: burn more to compete (20%)
     * Tests intelligent adaptation to market conditions.
     */
    class AdaptiveStrategy implements AgentStrategy {
        private static final double LOW_BURN = 0.05;
        private static final double HIGH_BURN = 0.20;

        @Override
        public BigDecimal decideBurn(Agent agent, int round, double contentionRatio) {
            BigDecimal balance = agent.getCurrencyBalance();
            if (balance.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ZERO;
            }

            // Scale burn rate linearly with contention
            // contentionRatio = 0.5 -> LOW_BURN
            // contentionRatio = 2.0 -> HIGH_BURN
            double normalized = (contentionRatio - 0.5) / 1.5; // 0 to 1
            normalized = Math.max(0, Math.min(1, normalized));
            double burnRate = LOW_BURN + normalized * (HIGH_BURN - LOW_BURN);

            return balance.multiply(BigDecimal.valueOf(burnRate))
                         .setScale(2, RoundingMode.HALF_UP);
        }

        @Override
        public String getName() {
            return "Adaptive";
        }

        @Override
        public String getCode() {
            return "D";
        }
    }

    /**
     * Sacrifice-and-Recover Strategy: Alternates between phases.
     * Phase 1 (first N rounds): Burn aggressively to accumulate allocations
     * Phase 2 (remaining rounds): Conserve to rebuild currency
     * Tests long-term sacrifice/recovery patterns.
     */
    class SacrificeAndRecoverStrategy implements AgentStrategy {
        private final int sacrificeRounds;
        private static final double SACRIFICE_RATE = 0.35; // 35% during sacrifice
        private static final double RECOVER_RATE = 0.02;   // 2% during recovery

        public SacrificeAndRecoverStrategy(int sacrificeRounds) {
            this.sacrificeRounds = sacrificeRounds;
        }

        @Override
        public BigDecimal decideBurn(Agent agent, int round, double contentionRatio) {
            BigDecimal balance = agent.getCurrencyBalance();
            if (balance.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ZERO;
            }

            double burnRate = round <= sacrificeRounds ? SACRIFICE_RATE : RECOVER_RATE;
            return balance.multiply(BigDecimal.valueOf(burnRate))
                         .setScale(2, RoundingMode.HALF_UP);
        }

        @Override
        public String getName() {
            return "Sacrifice" + sacrificeRounds;
        }

        @Override
        public String getCode() {
            return "S";
        }
    }
}
