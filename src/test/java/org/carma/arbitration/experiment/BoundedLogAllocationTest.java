package org.carma.arbitration.experiment;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BoundedLogAllocationTest {

    private static final double TOL = 1e-6;

    private long[] arr(long... v) {
        return v;
    }

    private double[] scores(double... v) {
        return v;
    }

    @Test
    void interiorSolutionSplitsProportionalToScores() {
        double[] x = PlatformMediationHarness.boundedLogAllocation(
            scores(1, 3), arr(1, 1), arr(100, 100), 10);
        assertEquals(2.5, x[0], TOL);
        assertEquals(7.5, x[1], TOL);
    }

    @Test
    void upperBoundBindsAndRedirectsMass() {
        double[] x = PlatformMediationHarness.boundedLogAllocation(
            scores(1, 3), arr(1, 1), arr(2, 100), 10);
        assertEquals(2.0, x[0], TOL);
        assertEquals(8.0, x[1], TOL);
    }

    @Test
    void lowerBoundBindsAndHoldsFloor() {
        double[] x = PlatformMediationHarness.boundedLogAllocation(
            scores(1, 9), arr(2, 1), arr(100, 100), 10);
        assertEquals(2.0, x[0], TOL);
        assertEquals(8.0, x[1], TOL);
    }

    @Test
    void returnsUpperBoundsWhenCapacityIsSlack() {
        double[] x = PlatformMediationHarness.boundedLogAllocation(
            scores(1, 3), arr(1, 1), arr(4, 5), 100);
        assertEquals(4.0, x[0], TOL);
        assertEquals(5.0, x[1], TOL);
    }

    @Test
    void zeroScoreCoordinatesStayAtLowerBound() {
        double[] x = PlatformMediationHarness.boundedLogAllocation(
            scores(0, 5), arr(2, 1), arr(100, 100), 10);
        assertEquals(2.0, x[0], TOL);
        assertEquals(8.0, x[1], TOL);
    }

    @Test
    void rejectsInfeasibleLowerBounds() {
        assertThrows(IllegalStateException.class, () ->
            PlatformMediationHarness.boundedLogAllocation(scores(1, 1), arr(6, 6), arr(10, 10), 10));
    }

    @Test
    void rejectsNegativeScore() {
        assertThrows(IllegalArgumentException.class, () ->
            PlatformMediationHarness.boundedLogAllocation(scores(-1, 1), arr(0, 0), arr(10, 10), 10));
    }

    @Test
    void rejectsInvalidBounds() {
        assertThrows(IllegalArgumentException.class, () ->
            PlatformMediationHarness.boundedLogAllocation(scores(1, 1), arr(5, 0), arr(2, 10), 10));
    }

    @Test
    void randomFeasibleInstancesSatisfyKktCapacityAndBounds() {
        Random rng = new Random(20240521L);
        for (int t = 0; t < 5000; t++) {
            int n = 2 + rng.nextInt(6);
            double[] s = new double[n];
            long[] lo = new long[n], up = new long[n];
            long sumLo = 0, sumUp = 0;
            for (int i = 0; i < n; i++) {
                s[i] = rng.nextDouble() < 0.15 ? 0.0 : 0.01 + rng.nextDouble() * 10;
                lo[i] = rng.nextInt(5);
                up[i] = lo[i] + rng.nextInt(20);
                sumLo += lo[i];
                sumUp += up[i];
            }
            long capacity = sumLo + (long) Math.floor(rng.nextDouble() * (sumUp - sumLo));
            if (capacity < sumLo) capacity = sumLo;
            double[] x = PlatformMediationHarness.boundedLogAllocation(s, lo, up, capacity);

            double sum = 0;
            for (int i = 0; i < n; i++) {
                assertTrue(x[i] >= lo[i] - TOL, "below lower");
                assertTrue(x[i] <= up[i] + TOL, "above upper");
                sum += x[i];
            }
            assertTrue(sum <= capacity + 1e-4, "over capacity: " + sum + " > " + capacity);

            for (int i = 0; i < n; i++) {
                for (int k = 0; k < n; k++) {
                    boolean canGive = x[k] > lo[k] + TOL;
                    boolean canTake = x[i] < up[i] - TOL;
                    if (canGive && canTake && s[i] > 0) {
                        double ri = s[i] / Math.max(x[i], 1e-12);
                        double rk = (s[k] > 0) ? s[k] / Math.max(x[k], 1e-12) : 0.0;
                        assertTrue(ri <= rk + 1e-4,
                            "KKT pairwise violated: marginal " + ri + " > " + rk);
                    }
                }
            }
        }
    }
}
