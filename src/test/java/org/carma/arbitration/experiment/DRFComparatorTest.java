package org.carma.arbitration.experiment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DRFComparatorTest {

    private long[][] highUpper(int n, int m, long v) {
        long[][] u = new long[n][m];
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) u[i][j] = v;
        return u;
    }

    private long[][] zeroLower(int n, int m) {
        return new long[n][m];
    }

    private long colSum(long[][] alloc, int j) {
        long s = 0;
        for (long[] row : alloc) s += row[j];
        return s;
    }

    @Test
    void allocationsReflectMandatoryDemandRatiosNotUpperBounds() {
        long[][] demand = {{4, 1}, {1, 4}};
        long[] cap = {10, 10};
        long[][] alloc = PlatformMediationHarness.drf(demand, zeroLower(2, 2), highUpper(2, 2, 100), cap);

        assertTrue(alloc[0][0] > alloc[0][1], "agent 0 favors its dominant resource 0");
        assertTrue(alloc[1][1] > alloc[1][0], "agent 1 favors its dominant resource 1");
        assertEquals(alloc[0][0], alloc[1][1], "symmetric demands give symmetric allocations");
    }

    @Test
    void changingUpperBoundDoesNotRedefineTheDemandVector() {
        long[][] demand = {{4, 1}, {1, 4}};
        long[] cap = {10, 10};
        long[][] loose = PlatformMediationHarness.drf(demand, zeroLower(2, 2), highUpper(2, 2, 100), cap);

        long[][] tightUpper = {{5, 100}, {100, 100}};
        long[][] tight = PlatformMediationHarness.drf(demand, zeroLower(2, 2), tightUpper, cap);

        assertTrue(tight[0][0] <= 5, "agent 0 respects its tightened upper bound");
        assertTrue(loose[0][0] > tight[0][0], "a tighter upper bound reduces agent 0's dominant allocation");
        assertTrue(tight[1][1] > loose[1][1], "freed capacity flows to the still-active agent");
    }

    @Test
    void totalAllocationStaysWithinEveryCapacity() {
        long[][] demand = {{6, 2, 1}, {1, 5, 3}, {2, 2, 6}};
        long[] cap = {10, 10, 10};
        long[][] alloc = PlatformMediationHarness.drf(demand, zeroLower(3, 3), highUpper(3, 3, 100), cap);
        for (int j = 0; j < 3; j++) {
            assertTrue(colSum(alloc, j) <= cap[j], "capacity respected on resource " + j);
        }
    }

    @Test
    void installedIntegersStayWithinAdmittedMinimumsAndUpperBounds() {
        long[][] demand = {{4, 1}, {1, 4}};
        long[][] lower = {{1, 1}, {1, 1}};
        long[][] upper = {{6, 3}, {3, 6}};
        long[] cap = {10, 10};
        long[][] alloc = PlatformMediationHarness.drf(demand, lower, upper, cap);
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                assertTrue(alloc[i][j] >= lower[i][j], "min respected at " + i + "," + j);
                assertTrue(alloc[i][j] <= upper[i][j], "upper respected at " + i + "," + j);
            }
        }
    }

    @Test
    void singleResourceReducesToEqualDominantShares() {
        long[][] demand = {{3}, {5}, {2}};
        long[] cap = {30};
        long[][] alloc = PlatformMediationHarness.drf(demand, zeroLower(3, 1), highUpper(3, 1, 100), cap);
        assertEquals(alloc[0][0], alloc[1][0], "equal dominant shares on one resource");
        assertEquals(alloc[1][0], alloc[2][0]);
        assertEquals(30, colSum(alloc, 0));
    }

    @Test
    void deterministicUnderAgentOrderPermutation() {
        long[][] demand = {{4, 1}, {1, 4}, {3, 3}};
        long[] cap = {12, 12};
        long[][] a = PlatformMediationHarness.drf(demand, zeroLower(3, 2), highUpper(3, 2, 100), cap);

        long[][] permutedDemand = {{3, 3}, {4, 1}, {1, 4}};
        long[][] p = PlatformMediationHarness.drf(permutedDemand, zeroLower(3, 2), highUpper(3, 2, 100), cap);

        assertArrayEquals(a[0], p[1], "agent order permutation maps allocations consistently");
        assertArrayEquals(a[1], p[2]);
        assertArrayEquals(a[2], p[0]);
    }
}
