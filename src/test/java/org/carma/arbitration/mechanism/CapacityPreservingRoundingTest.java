package org.carma.arbitration.mechanism;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CapacityPreservingRoundingTest {

    @Test
    void roundingNeverExceedsCapacity() {
        double[][] cont = {{50.5, 25.4}, {24.5, 49.5}, {25.0, 25.1}};
        long[][] lower = {{0, 0}, {0, 0}, {0, 0}};
        long[][] upper = {{100, 100}, {100, 100}, {100, 100}};
        long[] cap = {100, 100};
        long[][] out = ConvexJointArbitrator.roundColumnsPreservingCapacity(cont, lower, upper, cap);
        for (int j = 0; j < 2; j++) {
            long col = 0;
            for (long[] row : out) col += row[j];
            assertTrue(col <= cap[j], "column " + j + " sum " + col + " > cap " + cap[j]);
        }
    }

    @Test
    void respectsLowerAndUpperBounds() {
        double[][] cont = {{10.4, 5.5}, {10.4, 5.5}};
        long[][] lower = {{10, 5}, {10, 5}};
        long[][] upper = {{50, 50}, {50, 50}};
        long[] cap = {100, 100};
        long[][] out = ConvexJointArbitrator.roundColumnsPreservingCapacity(cont, lower, upper, cap);
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                assertTrue(out[i][j] >= lower[i][j]);
                assertTrue(out[i][j] <= upper[i][j]);
            }
        }
    }

    @Test
    void randomizedCapacityAndBoundInvariants() {
        Random rng = new Random(42);
        for (int t = 0; t < 2000; t++) {
            int n = 2 + rng.nextInt(6);
            int m = 1 + rng.nextInt(4);
            double[][] cont = new double[n][m];
            long[][] lower = new long[n][m];
            long[][] upper = new long[n][m];
            long[] cap = new long[m];
            for (int j = 0; j < m; j++) {
                cap[j] = 20 + rng.nextInt(80);
                double[] frac = new double[n];
                double sum = 0;
                for (int i = 0; i < n; i++) { frac[i] = rng.nextDouble(); sum += frac[i]; }
                for (int i = 0; i < n; i++) {
                    long ub = 5 + rng.nextInt((int) cap[j]);
                    double v = Math.min(frac[i] / sum * cap[j], ub);
                    cont[i][j] = v;
                    lower[i][j] = 0;
                    upper[i][j] = ub;
                }
            }
            long[][] out = ConvexJointArbitrator.roundColumnsPreservingCapacity(cont, lower, upper, cap);
            for (int j = 0; j < m; j++) {
                long col = 0;
                for (int i = 0; i < n; i++) {
                    col += out[i][j];
                    assertTrue(out[i][j] >= lower[i][j]);
                    assertTrue(out[i][j] <= upper[i][j]);
                }
                assertTrue(col <= cap[j]);
            }
        }
    }

    @Test
    void deterministicOutput() {
        double[][] cont = {{33.4, 0.0}, {33.4, 0.0}, {33.4, 0.0}};
        long[][] lower = {{0, 0}, {0, 0}, {0, 0}};
        long[][] upper = {{100, 100}, {100, 100}, {100, 100}};
        long[] cap = {100, 100};
        long[][] a = ConvexJointArbitrator.roundColumnsPreservingCapacity(cont, lower, upper, cap);
        long[][] b = ConvexJointArbitrator.roundColumnsPreservingCapacity(cont, lower, upper, cap);
        assertArrayEquals(a, b);
    }
}
