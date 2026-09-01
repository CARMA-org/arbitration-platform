"""Reduced central form of the canonical Leontief weighted proportional fairness
objective, used as an objective reference for the distributed solver and for
verification.

The canonical central objective is

  maximize  sum_i c_i * log( min_{j : r_ij > 0} a_ij / r_ij )
  subject to  sum_i a_ij <= Q_j,  min_ij <= a_ij <= ideal_ij.

Because Leontief utility depends only on the smallest ratio, at the optimum every
positively required resource of an agent is held in the fixed proportion r_ij, so the
allocation is a_ij = u_i * r_ij where u_i is the agent's utility level. The problem then
reduces to

  maximize  sum_i c_i * log(u_i)
  subject to  sum_i r_ij u_i <= Q_j,  lb_i <= u_i <= ub_i,

with lb_i = max_{j : r_ij>0} min_ij / r_ij and ub_i = min_{j : r_ij>0} ideal_ij / r_ij.
This reduced objective value equals the canonical solver's objective value.
"""
import numpy as np
import cvxpy as cp


def bounds(R, mn, up):
    n, m = R.shape
    lb = np.zeros(n)
    ub = np.full(n, np.inf)
    for i in range(n):
        pos = [j for j in range(m) if R[i, j] > 0]
        if pos:
            lb[i] = max(mn[i, j] / R[i, j] for j in pos)
            ub[i] = min(up[i, j] / R[i, j] for j in pos)
    return lb, ub


def leontief_objective(A, R, c):
    """Evaluate sum_i c_i log(min_j a_ij / r_ij) for a concrete allocation."""
    n, m = R.shape
    total = 0.0
    for i in range(n):
        ratios = [A[i, j] / R[i, j] for j in range(m) if R[i, j] > 0]
        u = min(ratios) if ratios else 0.0
        total += c[i] * np.log(max(u, 1e-12))
    return float(total)


def reduced_central_leontief(R, Q, mn, up, c):
    """Solve the reduced central problem. Returns (u, A_continuous, objective)."""
    n, m = R.shape
    u = cp.Variable(n, nonneg=True)
    cons = []
    for j in range(m):
        idx = [i for i in range(n) if R[i, j] > 0]
        if idx:
            cons.append(cp.sum(cp.hstack([R[i, j] * u[i] for i in idx])) <= Q[j])
    for i in range(n):
        for j in range(m):
            if R[i, j] > 0:
                cons.append(R[i, j] * u[i] >= mn[i, j])
                cons.append(R[i, j] * u[i] <= up[i, j])
    prob = cp.Problem(cp.Maximize(cp.sum(cp.multiply(c, cp.log(u)))), cons)
    prob.solve(solver=cp.CLARABEL)
    uv = np.asarray(u.value).reshape(-1)
    A = np.array([[uv[i] * R[i, j] if R[i, j] > 0 else 0.0 for j in range(m)] for i in range(n)])
    return uv, A, float(prob.value)
