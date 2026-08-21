package org.carma.arbitration.experiment;

import org.yaml.snakeyaml.Yaml;

import java.util.*;

public class DecomposedCobbDouglasTool {

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        Yaml yaml = new Yaml();
        try (Scanner sc = new Scanner(System.in)) {
            while (sc.hasNextLine()) {
                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;
                Map<String, Object> in = (Map<String, Object>) yaml.load(line);
                System.out.println(solve(in));
                System.out.flush();
            }
        }
    }

    @SuppressWarnings("unchecked")
    static String solve(Map<String, Object> in) {
        double[][] W = matrix((List<Object>) in.get("W"));
        long[][] lower = longMatrix((List<Object>) in.get("lower"));
        long[][] upper = longMatrix((List<Object>) in.get("upper"));
        double[] priority = vector((List<Object>) in.get("priority"));
        long[] cap = longVector((List<Object>) in.get("capacities"));

        int n = W.length, m = cap.length;
        double[][] continuous = new double[n][m];
        for (int j = 0; j < m; j++) {
            double[] scores = new double[n];
            long[] lo = new long[n], up = new long[n];
            for (int i = 0; i < n; i++) {
                scores[i] = priority[i] * Math.max(W[i][j], 0.0);
                lo[i] = lower[i][j];
                up[i] = upper[i][j];
            }
            double[] col = PlatformMediationHarness.boundedLogAllocation(scores, lo, up, cap[j]);
            for (int i = 0; i < n; i++) continuous[i][j] = col[i];
        }
        long[][] rounded = PlatformMediationHarness.decomposedCobbDouglas(W, lower, upper, priority, cap);

        StringBuilder sb = new StringBuilder();
        sb.append("{\"continuous\":").append(matrixJson(continuous));
        sb.append(",\"rounded\":").append(longMatrixJson(rounded)).append("}");
        return sb.toString();
    }

    private static double[][] matrix(List<Object> rows) {
        double[][] out = new double[rows.size()][];
        for (int i = 0; i < rows.size(); i++) out[i] = vector((List<Object>) rows.get(i));
        return out;
    }

    private static long[][] longMatrix(List<Object> rows) {
        long[][] out = new long[rows.size()][];
        for (int i = 0; i < rows.size(); i++) out[i] = longVector((List<Object>) rows.get(i));
        return out;
    }

    private static double[] vector(List<Object> v) {
        double[] out = new double[v.size()];
        for (int i = 0; i < v.size(); i++) out[i] = ((Number) v.get(i)).doubleValue();
        return out;
    }

    private static long[] longVector(List<Object> v) {
        long[] out = new long[v.size()];
        for (int i = 0; i < v.size(); i++) out[i] = ((Number) v.get(i)).longValue();
        return out;
    }

    private static String matrixJson(double[][] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            sb.append("[");
            for (int j = 0; j < a[i].length; j++) {
                sb.append(a[i][j]);
                if (j < a[i].length - 1) sb.append(",");
            }
            sb.append("]");
            if (i < a.length - 1) sb.append(",");
        }
        return sb.append("]").toString();
    }

    private static String longMatrixJson(long[][] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            sb.append("[");
            for (int j = 0; j < a[i].length; j++) {
                sb.append(a[i][j]);
                if (j < a[i].length - 1) sb.append(",");
            }
            sb.append("]");
            if (i < a.length - 1) sb.append(",");
        }
        return sb.append("]").toString();
    }
}
