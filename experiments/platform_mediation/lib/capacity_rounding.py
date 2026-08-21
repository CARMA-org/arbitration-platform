"""Bounded, capacity-preserving largest-remainder rounding.

Mirrors the Java roundSafe rule used by the platform harness: each resource
column is rounded independently so the column sum never exceeds its integer
capacity and every cell stays within its integer lower and upper bounds.
"""
import math


def capacity_preserving_round(cont, lower, upper, cap):
    n = len(cont)
    if n == 0:
        return []
    m = len(cap)
    out = [[0] * m for _ in range(n)]
    for j in range(m):
        base = [0] * n
        rem = [0.0] * n
        sum_base = 0
        col_sum = 0.0
        for i in range(n):
            f = int(math.floor(cont[i][j]))
            if f < lower[i][j]:
                f = lower[i][j]
            if f > upper[i][j]:
                f = upper[i][j]
            base[i] = f
            rem[i] = cont[i][j] - f
            sum_base += f
            col_sum += cont[i][j]
        target = min(cap[j], int(math.floor(col_sum + 0.5)))
        if target < sum_base:
            target = sum_base
        units = target - sum_base
        while units > 0:
            pick = -1
            best = -math.inf
            for i in range(n):
                if base[i] < upper[i][j] and rem[i] > best:
                    best = rem[i]
                    pick = i
            if pick < 0:
                break
            base[pick] += 1
            rem[pick] = -math.inf
            units -= 1
        col = sum(base)
        while col > cap[j]:
            pick = -1
            best_slack = 0
            for i in range(n):
                slack = base[i] - lower[i][j]
                if slack > best_slack:
                    best_slack = slack
                    pick = i
            if pick < 0:
                break
            base[pick] -= 1
            col -= 1
        for i in range(n):
            out[i][j] = base[i]
    return out
