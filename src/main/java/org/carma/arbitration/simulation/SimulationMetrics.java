package org.carma.arbitration.simulation;

import java.util.*;

/**
 * Tracks metrics over time for analyzing asymptotic behavior.
 */
public class SimulationMetrics {
    
    private final List<Double> welfareHistory;
    private final List<Double> giniHistory;
    private final List<Map<String, Double>> currencyHistory;
    private final List<Map<String, Long>> allocationHistory;
    private final List<Long> timestampHistory;
    private final Map<Integer, Integer> contentionHistogram; // numAgents -> count
    private final List<Double> contentionRatioHistory;
    private final long startTimeMs;

    public SimulationMetrics() {
        this.welfareHistory = new ArrayList<>();
        this.giniHistory = new ArrayList<>();
        this.currencyHistory = new ArrayList<>();
        this.allocationHistory = new ArrayList<>();
        this.timestampHistory = new ArrayList<>();
        this.contentionHistogram = new TreeMap<>(); // TreeMap for sorted output
        this.contentionRatioHistory = new ArrayList<>();
        this.startTimeMs = System.currentTimeMillis();
    }

    // ========================================================================
    // Recording
    // ========================================================================

    public void recordTick(double welfare, double gini, 
            Map<String, Double> currencies, Map<String, Long> allocations) {
        welfareHistory.add(welfare);
        giniHistory.add(gini);
        currencyHistory.add(new HashMap<>(currencies));
        allocationHistory.add(new HashMap<>(allocations));
        timestampHistory.add(System.currentTimeMillis() - startTimeMs);
    }

    /**
     * Record a contention event with the number of competing agents and contention ratio.
     */
    public void recordContention(int numAgents, double contentionRatio) {
        contentionHistogram.merge(numAgents, 1, Integer::sum);
        contentionRatioHistory.add(contentionRatio);
    }

    // ========================================================================
    // Analysis
    // ========================================================================

    public int getTickCount() {
        return welfareHistory.size();
    }

    public long getElapsedMs() {
        if (timestampHistory.isEmpty()) return 0;
        return timestampHistory.get(timestampHistory.size() - 1);
    }

    public double getAverageWelfare() {
        return welfareHistory.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    public double getWelfareStdDev() {
        double avg = getAverageWelfare();
        return Math.sqrt(welfareHistory.stream()
            .mapToDouble(w -> Math.pow(w - avg, 2))
            .average().orElse(0));
    }

    public double getAverageGini() {
        return giniHistory.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    public double getFinalWelfare() {
        if (welfareHistory.isEmpty()) return 0;
        return welfareHistory.get(welfareHistory.size() - 1);
    }

    public double getInitialWelfare() {
        if (welfareHistory.isEmpty()) return 0;
        return welfareHistory.get(0);
    }

    /**
     * Check if welfare has converged (stable over last N ticks).
     */
    public boolean hasConverged(int windowSize, double threshold) {
        if (welfareHistory.size() < windowSize) return false;
        
        List<Double> window = welfareHistory.subList(
            welfareHistory.size() - windowSize, welfareHistory.size());
        
        double avg = window.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double maxDev = window.stream()
            .mapToDouble(w -> Math.abs(w - avg))
            .max().orElse(0);
        
        return maxDev / Math.abs(avg) < threshold;
    }

    /**
     * Get welfare trend (slope of linear regression over last N points).
     */
    public double getWelfareTrend(int windowSize) {
        if (welfareHistory.size() < windowSize) return 0;
        
        List<Double> window = welfareHistory.subList(
            welfareHistory.size() - windowSize, welfareHistory.size());
        
        // Simple linear regression
        int n = window.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += window.get(i);
            sumXY += i * window.get(i);
            sumX2 += i * i;
        }
        
        double denom = n * sumX2 - sumX * sumX;
        if (Math.abs(denom) < 1e-10) return 0;
        return (n * sumXY - sumX * sumY) / denom;
    }

    /**
     * Get currency distribution statistics.
     */
    public Map<String, DoubleSummaryStatistics> getCurrencyStats() {
        Map<String, DoubleSummaryStatistics> stats = new HashMap<>();
        for (Map<String, Double> snapshot : currencyHistory) {
            for (var entry : snapshot.entrySet()) {
                stats.computeIfAbsent(entry.getKey(), k -> new DoubleSummaryStatistics())
                    .accept(entry.getValue());
            }
        }
        return stats;
    }

    // ========================================================================
    // History Access
    // ========================================================================

    public List<Double> getWelfareHistory() {
        return new ArrayList<>(welfareHistory);
    }

    public List<Double> getGiniHistory() {
        return new ArrayList<>(giniHistory);
    }

    public List<Long> getTimestampHistory() {
        return new ArrayList<>(timestampHistory);
    }

    /**
     * Get downsampled history for efficient plotting.
     */
    public List<Double> getDownsampledWelfare(int maxPoints) {
        if (welfareHistory.size() <= maxPoints) {
            return new ArrayList<>(welfareHistory);
        }
        
        List<Double> downsampled = new ArrayList<>();
        double step = (double) welfareHistory.size() / maxPoints;
        for (int i = 0; i < maxPoints; i++) {
            int idx = (int) (i * step);
            downsampled.add(welfareHistory.get(idx));
        }
        return downsampled;
    }

    /**
     * Get the contention histogram (number of agents -> occurrence count).
     */
    public Map<Integer, Integer> getContentionHistogram() {
        return new TreeMap<>(contentionHistogram);
    }

    /**
     * Get total number of contentions recorded.
     */
    public int getTotalContentions() {
        return contentionHistogram.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Get average contention ratio across all contentions.
     */
    public double getAverageContentionRatio() {
        return contentionRatioHistory.stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);
    }

    /**
     * Generate a text-based histogram of contention sizes.
     */
    public String getContentionHistogramString() {
        if (contentionHistogram.isEmpty()) {
            return "  No contentions recorded.\n";
        }
        
        StringBuilder sb = new StringBuilder();
        int maxCount = contentionHistogram.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        int totalContentions = getTotalContentions();
        int barWidth = 40; // Max width of histogram bar
        
        for (var entry : contentionHistogram.entrySet()) {
            int numAgents = entry.getKey();
            int count = entry.getValue();
            double percentage = 100.0 * count / totalContentions;
            int barLength = (int) Math.ceil((double) count / maxCount * barWidth);
            
            String bar = "█".repeat(barLength);
            sb.append(String.format("  %2d agents: %s %d (%.1f%%)\n", 
                numAgents, bar, count, percentage));
        }
        
        sb.append(String.format("  Total contentions: %d\n", totalContentions));
        sb.append(String.format("  Average contention ratio: %.2f\n", getAverageContentionRatio()));
        
        return sb.toString();
    }

    // ========================================================================
    // Reporting
    // ========================================================================

    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Simulation Metrics Summary:\n");
        sb.append(String.format("  Duration: %.2f seconds (%d ticks)\n", 
            getElapsedMs() / 1000.0, getTickCount()));
        sb.append(String.format("  Welfare: initial=%.4f, final=%.4f, avg=%.4f, stddev=%.4f\n",
            getInitialWelfare(), getFinalWelfare(), getAverageWelfare(), getWelfareStdDev()));
        sb.append(String.format("  Welfare change: %.2f%%\n",
            (getFinalWelfare() - getInitialWelfare()) / Math.abs(getInitialWelfare()) * 100));
        sb.append(String.format("  Gini coefficient: avg=%.4f\n", getAverageGini()));
        sb.append(String.format("  Converged: %s\n", hasConverged(20, 0.01)));
        sb.append(String.format("  Trend (last 20): %.6f\n", getWelfareTrend(20)));
        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format("SimulationMetrics[%d ticks, %.2fs, welfare=%.4f→%.4f]",
            getTickCount(), getElapsedMs() / 1000.0, getInitialWelfare(), getFinalWelfare());
    }
}
